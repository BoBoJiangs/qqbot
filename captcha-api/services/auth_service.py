"""Authentication business logic service.

Handles admin authentication, session management, and password operations.
"""
import hashlib
import hmac
import json
import os
import secrets
import threading
import time
from typing import Dict, Optional, Any
from fastapi import Request, HTTPException
from config import settings


class AuthService:
    """Service for authentication business logic."""

    def __init__(self):
        """Initialize auth service."""
        self._lock = threading.Lock()
        self._credentials_cache = {"loaded_at": 0.0, "data": None}
        self._secret_bytes = self._get_or_create_admin_secret()

    def _read_text_file(self, path: str) -> str:
        """Read text file with error handling."""
        try:
            with open(path, "r", encoding="utf-8") as f:
                return f.read()
        except FileNotFoundError:
            return ""

    def _atomic_write_text(self, path: str, content: str) -> None:
        """Atomically write text file."""
        directory = os.path.dirname(os.path.abspath(path)) or "."
        base = os.path.basename(path)
        tmp_path = os.path.join(directory, f".{base}.{secrets.token_hex(8)}.tmp")
        with open(tmp_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        os.replace(tmp_path, path)

    def _read_json_file(self, path: str, default: Any) -> Any:
        """Read JSON file with error handling."""
        try:
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
        except FileNotFoundError:
            return default
        except Exception:
            return default

    def _atomic_write_json(self, path: str, data: Any) -> None:
        """Atomically write JSON file."""
        content = json.dumps(data, ensure_ascii=False, indent=2)
        self._atomic_write_text(path, content)

    def _b64url_encode(self, raw: bytes) -> str:
        """Base64 URL-safe encode."""
        import base64
        return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")

    def _b64url_decode(self, text: str) -> bytes:
        """Base64 URL-safe decode."""
        import base64
        pad = "=" * (-len(text) % 4)
        return base64.urlsafe_b64decode((text + pad).encode("ascii"))

    def _get_or_create_admin_secret(self) -> bytes:
        """Get or create admin secret for token signing."""
        secret_file = settings.admin_secret_file
        with self._lock:
            existing = self._read_text_file(secret_file).strip()
            if existing:
                try:
                    return self._b64url_decode(existing)
                except Exception:
                    pass
            secret_bytes = secrets.token_bytes(32)
            self._atomic_write_text(secret_file, self._b64url_encode(secret_bytes))
            return secret_bytes

    def _hash_password(self, password: str, salt: Optional[bytes] = None) -> Dict[str, Any]:
        """Hash password using PBKDF2.

        Args:
            password: Plain text password
            salt: Salt bytes (generated if None)

        Returns:
            Dict with salt, hash, algo, iter
        """
        if salt is None:
            salt = secrets.token_bytes(16)
        dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 200_000)
        return {
            "salt": self._b64url_encode(salt),
            "hash": self._b64url_encode(dk),
            "algo": "pbkdf2_sha256",
            "iter": 200_000
        }

    def _verify_password(self, password: str, record: Dict[str, Any]) -> bool:
        """Verify password against hash.

        Args:
            password: Plain text password
            record: Password record with salt and hash

        Returns:
            True if password matches
        """
        try:
            salt = self._b64url_decode(record["salt"])
            expected = self._b64url_decode(record["hash"])
            iterations = int(record.get("iter") or 200_000)
            dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
            return hmac.compare_digest(dk, expected)
        except Exception:
            return False

    def load_credentials(self) -> Dict[str, Any]:
        """Load admin credentials.

        Returns:
            Credentials dict with username, password, must_change_password
        """
        now = time.time()
        # Cache for 60 seconds
        if self._credentials_cache["data"] and (now - self._credentials_cache["loaded_at"]) < 60:
            return self._credentials_cache["data"]

        default = {
            "username": "admin",
            "password": self._hash_password("admin"),
            "must_change_password": True,
        }

        with self._lock:
            data = self._read_json_file(settings.admin_credentials_file, default)
            if "username" not in data or "password" not in data:
                data = default
            if not os.path.exists(settings.admin_credentials_file):
                self._atomic_write_json(settings.admin_credentials_file, data)

            self._credentials_cache["loaded_at"] = now
            self._credentials_cache["data"] = data
            return data

    def save_credentials(self, data: Dict[str, Any]) -> None:
        """Save admin credentials.

        Args:
            data: Credentials dict
        """
        with self._lock:
            self._atomic_write_json(settings.admin_credentials_file, data)
            self._credentials_cache["loaded_at"] = time.time()
            self._credentials_cache["data"] = data

    def verify_login(self, username: str, password: str) -> bool:
        """Verify admin login credentials.

        Args:
            username: Admin username
            password: Plain text password

        Returns:
            True if credentials are valid
        """
        credentials = self.load_credentials()
        if credentials.get("username") != username:
            return False
        return self._verify_password(password, credentials.get("password", {}))

    def change_password(self, username: str, old_password: str, new_password: str) -> bool:
        """Change admin password.

        Args:
            username: Admin username
            old_password: Current password
            new_password: New password

        Returns:
            True if password changed successfully

        Raises:
            ValueError: If old password is incorrect
        """
        if not self.verify_login(username, old_password):
            raise ValueError("当前密码错误")

        credentials = self.load_credentials()
        credentials["password"] = self._hash_password(new_password)
        credentials["must_change_password"] = False
        self.save_credentials(credentials)
        return True

    def must_change_password(self) -> bool:
        """Check if password must be changed.

        Returns:
            True if password change is required
        """
        credentials = self.load_credentials()
        return credentials.get("must_change_password", False)

    def create_token(self, username: str) -> str:
        """Create admin session token.

        Args:
            username: Admin username

        Returns:
            JWT-like token string
        """
        now_ts = int(time.time())
        exp_ts = now_ts + int(settings.admin_session_hours * 3600)
        payload = {"u": username, "iat": now_ts, "exp": exp_ts}
        payload_bytes = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        payload_b64 = self._b64url_encode(payload_bytes)
        sig = hmac.new(self._secret_bytes, payload_b64.encode("ascii"), hashlib.sha256).digest()
        return f"{payload_b64}.{self._b64url_encode(sig)}"

    def verify_token(self, token: str) -> Optional[Dict[str, Any]]:
        """Verify admin session token.

        Args:
            token: Token string

        Returns:
            Payload dict with username, iat, exp or None if invalid
        """
        try:
            payload_b64, sig_b64 = token.split(".", 1)
            expected_sig = hmac.new(self._secret_bytes, payload_b64.encode("ascii"), hashlib.sha256).digest()
            if not hmac.compare_digest(self._b64url_encode(expected_sig), sig_b64):
                return None
            payload = json.loads(self._b64url_decode(payload_b64))
            if int(payload.get("exp") or 0) < int(time.time()):
                return None
            return payload
        except Exception:
            return None

    def get_current_user(self, request: Request) -> Optional[str]:
        """Get current authenticated admin username.

        Args:
            request: FastAPI request

        Returns:
            Username or None if not authenticated
        """
        token = request.cookies.get("admin_session")
        if not token:
            return None
        payload = self.verify_token(token)
        if not payload:
            return None
        return payload.get("u")

    def require_admin(self, request: Request) -> str:
        """Require admin authentication or raise exception.

        Args:
            request: FastAPI request

        Returns:
            Username

        Raises:
            HTTPException: If not authenticated (303 redirect to login)
        """
        user = self.get_current_user(request)
        if not user:
            raise HTTPException(status_code=303, headers={"Location": "/admin/login"})
        return user

    def is_local_only(self) -> bool:
        """Check if admin access is restricted to localhost.

        Returns:
            True if local only
        """
        return settings.admin_local_only


# Global service instance
_auth_service: Optional[AuthService] = None


def get_auth_service() -> AuthService:
    """Get or create global auth service instance.

    Returns:
        AuthService instance
    """
    global _auth_service
    if _auth_service is None:
        _auth_service = AuthService()
    return _auth_service
