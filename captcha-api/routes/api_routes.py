"""Captcha recognition API routes.

Handles image recognition, error reporting, and membership management.
"""
import hashlib
import json
import os
import secrets
from datetime import datetime, timezone
from fastapi import APIRouter, Request, UploadFile, Form, File
from fastapi.responses import JSONResponse
from pydantic import BaseModel
import numpy as np
import cv2
import requests

from config import settings
from services.member_service import get_member_service
from services.renewal_service import get_renewal_service
from data.whitelist_repository import get_whitelist_repository
from data.usage_repository import get_usage_repository


# Create router
api_routes = APIRouter()

# Initialize services and repositories
member_service = get_member_service()
renewal_service = get_renewal_service()
whitelist_repo = get_whitelist_repository()
usage_repo = get_usage_repository()

# Error image directory
SAVE_DIR = settings.error_dir
os.makedirs(SAVE_DIR, exist_ok=True)

# Load models
CLASS_LABELS = []
detector = None
classifier = None
_DETECTOR_LOCK = None
_CLASSIFIER_LOCK = None
_DETECTOR_OUTPUT_LAYERS = None
_CLASSIFIER_INPUT_SIZE = None  # Will be set from config file


def _parse_darknet_config(cfg_path: str) -> tuple[int, int]:
    """Parse width and height from darknet config file."""
    width, height = 32, 32  # default values
    try:
        with open(cfg_path, 'r', encoding='utf-8') as f:
            in_net_section = False
            for line in f:
                line = line.strip()
                if line.startswith('['):
                    in_net_section = (line.lower() == '[net]')
                elif in_net_section:
                    if line.lower().startswith('width='):
                        width = int(line.split('=')[1].strip())
                    elif line.lower().startswith('height='):
                        height = int(line.split('=')[1].strip())
    except Exception:
        pass  # Use default values on error
    return width, height


def initialize_models():
    """Initialize YOLO detector and classifier models."""
    global detector, classifier, _DETECTOR_LOCK, _CLASSIFIER_LOCK, _DETECTOR_OUTPUT_LAYERS, CLASS_LABELS, _CLASSIFIER_INPUT_SIZE

    # Load classification labels
    with open(settings.classify_labels, 'r', encoding='utf-8') as f:
        CLASS_LABELS = [line.strip() for line in f if line.strip()]

    # Parse classifier input size from config file
    _CLASSIFIER_INPUT_SIZE = _parse_darknet_config(settings.classify_config)

    # Load models
    detector = cv2.dnn.readNetFromDarknet(settings.yolo_config, settings.yolo_weights)
    classifier = cv2.dnn.readNetFromDarknet(settings.classify_config, settings.classify_weights)

    import threading
    _DETECTOR_LOCK = threading.Lock()
    _CLASSIFIER_LOCK = threading.Lock()
    _DETECTOR_OUTPUT_LAYERS = detector.getUnconnectedOutLayersNames()


class ImageURLRequest(BaseModel):
    """Image URL request model."""
    url: str


def error_payload(message: str) -> dict:
    """Create error response payload."""
    return {
        "code": 1,
        "error": message,
        "msg": message,
        "result": f"识别失败：{message}",
        "emojiList": [],
    }


def recognize_error_payload(code: int, message: str) -> dict:
    """Create recognition error response payload."""
    return {
        "code": int(code),
        "msg": message,
        "result": f"识别失败：{message}",
        "emojiList": [],
    }


def fetch_image_bytes(url: str) -> bytes:
    """Download image from URL with validation."""
    import urllib.parse
    import ipaddress
    import socket

    def _is_disallowed_ip(ip_str: str) -> bool:
        try:
            ip = ipaddress.ip_address(ip_str)
        except ValueError:
            return True
        return ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_reserved or ip.is_unspecified

    def _validate_fetch_url(url: str) -> str:
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme not in {"http", "https"}:
            raise ValueError("unsupported scheme")
        hostname = parsed.hostname
        if not hostname:
            raise ValueError("missing hostname")
        if hostname.lower() in {"localhost"}:
            raise ValueError("disallowed hostname")
        try:
            ipaddress.ip_address(hostname)
            candidate_ips = [hostname]
        except ValueError:
            try:
                addrinfos = socket.getaddrinfo(hostname, parsed.port or (443 if parsed.scheme == "https" else 80))
            except socket.gaierror:
                raise ValueError("resolve failed")
            candidate_ips = list({ai[4][0] for ai in addrinfos if ai and ai[4]})
        if not candidate_ips or any(_is_disallowed_ip(ip) for ip in candidate_ips):
            raise ValueError("disallowed ip")
        return url

    session = requests.Session()
    current_url = _validate_fetch_url(url)

    for _ in range(settings.image_max_redirects + 1):
        with session.get(
            current_url,
            stream=True,
            allow_redirects=False,
            timeout=(settings.image_connect_timeout_seconds, settings.image_read_timeout_seconds),
            headers={"User-Agent": "captcha-api/1.0"},
        ) as resp:
            if resp.status_code in {301, 302, 303, 307, 308} and resp.headers.get("Location"):
                current_url = urllib.parse.urljoin(current_url, resp.headers["Location"])
                current_url = _validate_fetch_url(current_url)
                continue

            if resp.status_code != 200:
                raise Exception(f"bad status: {resp.status_code}")

            total = 0
            chunks = []
            for chunk in resp.iter_content(chunk_size=65536):
                if not chunk:
                    continue
                total += len(chunk)
                if total > settings.image_max_bytes:
                    raise Exception("too large")
                chunks.append(chunk)

            if total == 0:
                raise Exception("empty")

            return b"".join(chunks)

    raise Exception("too many redirects")


def detect_objects(image):
    """Detect objects in image using YOLO."""
    blob = cv2.dnn.blobFromImage(image, 1/255.0, (416, 416), swapRB=True, crop=False)
    with _DETECTOR_LOCK:
        detector.setInput(blob)
        outputs = detector.forward(_DETECTOR_OUTPUT_LAYERS)
    h, w = image.shape[:2]
    boxes, class_ids, confidences = [], [], []
    for output in outputs:
        for det in output:
            scores = det[5:]
            class_id = np.argmax(scores)
            confidence = scores[class_id]
            if confidence > 0.5:
                cx, cy, bw, bh = (det[:4] * np.array([w, h, w, h])).astype(int)
                x, y = int(cx - bw / 2), int(cy - bh / 2)
                boxes.append([x, y, int(bw), int(bh)])
                class_ids.append(class_id)
                confidences.append(float(confidence))

    if not boxes:
        return []

    indices = cv2.dnn.NMSBoxes(boxes, confidences, 0.5, 0.4)
    results = []
    if len(indices) > 0:
        for i in indices.flatten():
            results.append({'box': boxes[i], 'class_id': class_ids[i], 'confidence': confidences[i]})
    return results


def sort_detections_reading_order(detections):
    """Sort detections by center x coordinate (left to right)."""
    if not detections:
        return detections
    return sorted(detections, key=lambda det: det["box"][0] + det["box"][2] / 2)


def classify_char(region):
    """Classify a character region."""
    resized = cv2.resize(region, _CLASSIFIER_INPUT_SIZE)
    blob = cv2.dnn.blobFromImage(resized, 1/255.0, _CLASSIFIER_INPUT_SIZE, swapRB=True)
    with _CLASSIFIER_LOCK:
        classifier.setInput(blob)
        preds = classifier.forward().reshape(-1)
    idx = int(np.argmax(preds))
    if 0 <= idx < len(CLASS_LABELS):
        return CLASS_LABELS[idx]
    return str(idx)


@api_routes.post("/recognize_detail")
async def recognize_with_detail(req: ImageURLRequest, request: Request):
    """Recognize text and emojis with detailed segmentation results."""
    import logging
    import base64
    logger = logging.getLogger("captcha_api")

    try:
        np_arr = np.frombuffer(fetch_image_bytes(req.url), np.uint8)
        image = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)
        if image is None:
            return error_payload("图片解码失败")

        # Detect and sort objects
        detections = sort_detections_reading_order(detect_objects(image))

        result_items = []
        text_result = ""
        emoji_result = []

        for idx, det in enumerate(detections):
            x, y, w, h = det['box']
            h_img, w_img = image.shape[:2]
            x = max(0, x)
            y = max(0, y)
            x_end = min(w_img, x + w)
            y_end = min(h_img, y + h)
            if x_end <= x or y_end <= y:
                continue
            crop = image[y:y_end, x:x_end]
            if crop.size == 0:
                continue
            label = classify_char(crop)

            # Encode cropped image to base64
            _, buffer = cv2.imencode('.png', crop)
            crop_base64 = base64.b64encode(buffer).decode('utf-8')

            item = {
                'index': idx,
                'label': label,
                'confidence': det['confidence'],
                'box': det['box'],
                'image': f'data:image/png;base64,{crop_base64}',
                'type': 'text' if det["class_id"] == 0 else 'emoji'
            }

            result_items.append(item)

            if det["class_id"] == 0:
                text_result += label
            else:
                emoji_result.append(label)

        if not result_items:
            return error_payload("未识别到内容")

        # Draw boxes on original image
        annotated = image.copy()
        for item in result_items:
            x, y, w, h = item['box']
            cv2.rectangle(annotated, (x, y), (x + w, y + h), (0, 255, 0), 2)
            cv2.putText(annotated, item['label'], (x, y - 5),
                       cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)

        # Encode annotated image
        _, buffer = cv2.imencode('.png', annotated)
        annotated_base64 = base64.b64encode(buffer).decode('utf-8')

        response = {
            "code": 0,
            "result": text_result,
            "emojiList": emoji_result,
            "items": result_items,
            "annotated_image": f'data:image/png;base64,{annotated_base64}',
            "total_count": len(result_items)
        }

        return response

    except Exception as e:
        logger.exception("识别失败: %s", e)
        return error_payload("服务异常")


@api_routes.post("/recognize_upload")
async def recognize_from_upload(image: UploadFile = File(...)):
    """Recognize text and emojis from uploaded image with detail."""
    import logging
    import base64
    logger = logging.getLogger("captcha_api")

    try:
        img_bytes = await image.read()
        if not img_bytes:
            return error_payload("未收到图片数据")

        np_arr = np.frombuffer(img_bytes, np.uint8)
        image = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)
        if image is None:
            return error_payload("图片解码失败")

        # Detect and sort objects
        detections = sort_detections_reading_order(detect_objects(image))

        result_items = []
        text_result = ""
        emoji_result = []

        for idx, det in enumerate(detections):
            x, y, w, h = det['box']
            h_img, w_img = image.shape[:2]
            x = max(0, x)
            y = max(0, y)
            x_end = min(w_img, x + w)
            y_end = min(h_img, y + h)
            if x_end <= x or y_end <= y:
                continue
            crop = image[y:y_end, x:x_end]
            if crop.size == 0:
                continue
            label = classify_char(crop)

            # Encode cropped image to base64
            _, buffer = cv2.imencode('.png', crop)
            crop_base64 = base64.b64encode(buffer).decode('utf-8')

            item = {
                'index': idx,
                'label': label,
                'confidence': det['confidence'],
                'box': det['box'],
                'image': f'data:image/png;base64,{crop_base64}',
                'type': 'text' if det["class_id"] == 0 else 'emoji'
            }

            result_items.append(item)

            if det["class_id"] == 0:
                text_result += label
            else:
                emoji_result.append(label)

        if not result_items:
            return error_payload("未识别到内容")

        # Draw boxes on original image
        annotated = image.copy()
        for item in result_items:
            x, y, w, h = item['box']
            cv2.rectangle(annotated, (x, y), (x + w, y + h), (0, 255, 0), 2)
            cv2.putText(annotated, item['label'], (x, y - 5),
                       cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)

        # Encode annotated image
        _, buffer = cv2.imencode('.png', annotated)
        annotated_base64 = base64.b64encode(buffer).decode('utf-8')

        response = {
            "code": 0,
            "result": text_result,
            "emojiList": emoji_result,
            "items": result_items,
            "annotated_image": f'data:image/png;base64,{annotated_base64}',
            "total_count": len(result_items)
        }

        return response

    except Exception as e:
        logger.exception("识别失败: %s", e)
        return error_payload("服务异常")


@api_routes.post("/recognize")
async def recognize_from_url(req: ImageURLRequest, request: Request):
    """Recognize text and emojis in image from URL."""
    import logging
    logger = logging.getLogger("captcha_api")

    try:
        # Get auth info
        auth_type = getattr(request.state, 'auth_type', None)
        auth_info = getattr(request.state, 'auth_info', None)

        # Log access
        if auth_type == "ip":
            logger.info("识别请求 - IP白名单访问: %s", auth_info)
        elif auth_type == "qq":
            qq_comment = getattr(request.state, 'qq_comment', "未知用户")
            logger.info("识别请求 - QQ白名单访问: %s (%s)", auth_info, qq_comment)
        else:
            logger.info("识别请求 - 未认证访问: %s", request.client.host)

        try:
            img_bytes = fetch_image_bytes(req.url)
        except ValueError:
            return error_payload("图片URL不允许")
        except Exception:
            return error_payload("图片下载失败")

        np_arr = np.frombuffer(img_bytes, np.uint8)
        image = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)
        if image is None:
            return error_payload("图片解码失败")

        # Detect and sort objects
        detections = sort_detections_reading_order(detect_objects(image))

        text_result = ""
        emoji_result = []

        for det in detections:
            x, y, w, h = det['box']
            h_img, w_img = image.shape[:2]
            x = max(0, x)
            y = max(0, y)
            x_end = min(w_img, x + w)
            y_end = min(h_img, y + h)
            if x_end <= x or y_end <= y:
                continue
            crop = image[y:y_end, x:x_end]
            if crop.size == 0:
                continue
            label = classify_char(crop)
            if det["class_id"] == 0:
                text_result += label
            else:
                emoji_result.append(label)

        if not text_result and not emoji_result:
            return error_payload("未识别到内容")

        response = {
            "code": 0,
            "result": text_result,
            "emojiList": emoji_result
        }

        # Add user info for QQ authentication
        if auth_type == "qq":
            qq_comment = getattr(request.state, 'qq_comment', "未知用户")
            response["user_info"] = {
                "qq": auth_info,
                "comment": qq_comment
            }

        return response

    except Exception as e:
        logger.exception("识别失败: %s", e)
        return error_payload("服务异常")


@api_routes.post("/report_error")
async def report_error(
    request: Request,
    image: UploadFile = File(...),
    question: str = Form(...),
    answer: str = Form(...),
    imageUrl: str = Form(...),
    qq: str = Form(None)
):
    """Report recognition error."""
    import logging
    logger = logging.getLogger("captcha_api")

    logger.info("收到错误报告请求 - image=%s, question=%s, answer=%s, imageUrl=%s, qq=%s",
                image.filename if image else None, question, answer, imageUrl, qq)

    try:
        auth_type = getattr(request.state, 'auth_type', None)
        auth_info = getattr(request.state, 'auth_info', None)

        if auth_type == "ip":
            logger.info("错误报告 - IP白名单访问: %s", auth_info)
        elif auth_type == "qq":
            qq_comment = getattr(request.state, 'qq_comment', "未知用户")
            logger.info("错误报告 - QQ白名单访问: %s (%s)", auth_info, qq_comment)
        else:
            logger.info("错误报告 - 未认证访问: %s", request.client.host)

        img_bytes = await image.read()
        if not img_bytes:
            return error_payload("未收到图片数据")

        md5_hash = hashlib.md5(img_bytes).hexdigest()
        img_path = os.path.join(SAVE_DIR, f"{md5_hash}.jpg")
        json_path = os.path.join(SAVE_DIR, f"{md5_hash}.json")

        if not os.path.exists(img_path):
            with open(img_path, "wb") as f:
                f.write(img_bytes)

            save_data = {
                "question": question,
                "answer": answer,
                "imageUrl": imageUrl,
                "ip": request.client.host,
                "auth_type": auth_type,
                "auth_info": auth_info,
                "time": datetime.now().isoformat()
            }

            if auth_type == "qq":
                qq_comment = getattr(request.state, 'qq_comment', "未知用户")
                save_data["qq"] = auth_info
                save_data["user_comment"] = qq_comment

            with open(json_path, "w", encoding="utf-8") as f:
                json.dump(save_data, f, ensure_ascii=False, indent=2)

            # Increment error counter
            member_key = _usage_key_from_request(request)
            if member_key:
                now = datetime.now(timezone.utc)
                try:
                    usage_repo.increment_usage(member_key, now, daily_limit=None, monthly_limit=None, is_error=True, increment_call=False)
                except Exception:
                    pass  # Don't fail on error counting

            return {"code": 0, "msg": "已保存"}

        return {"code": 0, "msg": "图片已存在，未重复保存"}

    except Exception as e:
        logger.exception("错误上报失败: %s", e)
        return error_payload("服务异常")


def _usage_key_from_request(request: Request) -> str | None:
    """Extract usage key from request."""
    auth_type = getattr(request.state, "auth_type", None)
    auth_info = getattr(request.state, "auth_info", None)
    if auth_type == "ip" and auth_info:
        return f"ip:{auth_info}"
    if auth_type == "qq" and auth_info:
        return f"qq:{auth_info}"
    return None


# Initialize models on import (with error handling for missing models)
try:
    initialize_models()
except Exception as e:
    import logging
    logger = logging.getLogger("captcha_api")
    logger.warning("模型初始化失败（模型文件可能不存在）: %s", e)
    logger.info("API 将在调用 /recognize 时返回模型错误")
