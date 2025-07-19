package top.sshh.qqbot.verifycode;

import org.apache.commons.lang3.StringUtils;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class YoloCaptchaRecognizer {

    private static final Map<String, Integer> CHINESE_NUMBERS = new HashMap<>();

    static {
        CHINESE_NUMBERS.put("零", 0);
        CHINESE_NUMBERS.put("一", 1);
        CHINESE_NUMBERS.put("二", 2);
        CHINESE_NUMBERS.put("三", 3);
        CHINESE_NUMBERS.put("四", 4);
        CHINESE_NUMBERS.put("五", 5);
        CHINESE_NUMBERS.put("六", 6);
        CHINESE_NUMBERS.put("七", 7);
        CHINESE_NUMBERS.put("八", 8);
        CHINESE_NUMBERS.put("九", 9);
        CHINESE_NUMBERS.put("十", 10);
    }

    static {
        nu.pattern.OpenCV.loadLocally();
    }

    @Value("${captcha.detection-weights}")
    private String detectionModelWeights;
    @Value("${captcha.detection-config}")
    private String detectionModelConfig;
    @Value("${captcha.classification-weights}")
    private String classificationModelWeights;
    @Value("${captcha.classification-config}")
    private String classificationModelConfig;

    private static final String[] DETECTION_CLASSES = {"文字", "表情"};

//    private static final String[] CLASSIFICATION_CLASSES = {
//            "请", "击", "5", "的", "加", "点", "结", "果", "8",
//            "2", "五", "情", "按", "第", "钮", "表", "图", "中",
//            "乘", "书本", "电池", "图钉", "1", "9", "钉", "3", "电",
//            "瓜", "鸡头", "鲸鱼", "个", "九", "减", "苹", "七", "6",
//            "西", "鱼", "鲸", "四", "4", "车", "蟹", "0", "汽",
//            "漏", "螃", "沙", "苹果", "池", "萄", "葡", "脑", "鸡",
//            "书", "西瓜", "电脑", "本·", "沙漏", "7", "六", "汽车",
//            "葡萄", "本", "书沙"
//    };
private static final String[] CLASSIFICATION_CLASSES = {
        "4", "9", "击", "点", "请", "漏", "车", "书", "螃",
        "3", "2", "8", "的", "乘", "七", "果", "结", "1",
        "第", "表", "钮", "个", "四", "中", "情", "按", "沙",
        "本", "图", "鲸鱼", "书本", "西瓜", "电池", "电脑", "沙漏",
        "萄", "葡", "减", "6", "瓜", "加", "苹果", "鸡", "葡萄",
        "图钉", "汽车", "5", "五", "电", "西", "汽", "脑", "7",
        "池", "蟹", "苹", "九", "钉", "六", "0", "鱼", "鲸"
};

    private static final float CONFIDENCE_THRESHOLD = 0.5f;
    private static final float NMS_THRESHOLD = 0.4f;

    private Net detectionNet;
    private Net classificationNet;

    // 同步初始化，单例加载模型
    public synchronized void init() {
        if (detectionNet == null) {
            checkModelFile(detectionModelConfig);
            checkModelFile(detectionModelWeights);
            detectionNet = Dnn.readNet(detectionModelWeights, detectionModelConfig);
            detectionNet.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            detectionNet.setPreferableTarget(Dnn.DNN_TARGET_CPU);
        }
        if (classificationNet == null) {
            checkModelFile(classificationModelConfig);
            checkModelFile(classificationModelWeights);
            classificationNet = Dnn.readNet(classificationModelWeights, classificationModelConfig);
            classificationNet.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            classificationNet.setPreferableTarget(Dnn.DNN_TARGET_CPU);
        }
    }

    private static void checkModelFile(String path) {
        if (!new File(path).exists()) throw new RuntimeException("模型文件未找到: " + path);
    }

    public String recognizeVerifyCode(String imageUrl, String title) {
        init();
        System.out.println("开始识别验证码: " + imageUrl);

        String answer = "";
        Mat mat = null;
        try {
            mat = downloadImageToMat(imageUrl);
            RecognitionResult recognitionResult = recognizeCaptcha(mat);
            String resultText = recognitionResult.result;
            resultText =  resultText.replaceAll("请点点","请点击");
            if (StringUtils.isNotBlank(title) && StringUtils.isNotBlank(resultText)) {
                if (title.contains("请问深色文字中字符") && title.contains("出现了几次")) {
                    Character targetChar = extractTargetChar(title);
                    if (targetChar != null) {
                        answer = String.valueOf(countCharOccurrences(resultText, targetChar));
                    } else {
                        return "识别失败，请手动点击验证码";
                    }
                } else if (title.contains("请按照深色文字的题目点击对应的答案")) {
                    if (resultText.contains("加") || resultText.contains("减") || resultText.contains("乘")) {
                        answer = String.valueOf(calculate(resultText));
                    }
                    if (resultText.startsWith("请点击") && resultText.length() < 7) {
                        answer = resultText.substring("请点击".length()).trim();
                        if (answer.equals("漏萄")) answer = "葡萄";
                    }
                    if (resultText.contains("个表情") || resultText.contains("第")) {
                        if (recognitionResult.emojiList.isEmpty()) {
                            resultText = resultText.replaceAll("请点击请", "请点击第");
                            answer = resultText.split("[第个]")[1];
                            answer = "序号" + (StringUtils.isNumeric(answer) ? answer : parseNumber(answer));
                        } else {
                            if (recognitionResult.emojiList.size() >= 3) {
                                int idx = Integer.parseInt(resultText.split("[第个]")[1]) - 1;
                                if (idx >= 0 && idx < recognitionResult.emojiList.size()) {
                                    answer = recognitionResult.emojiList.get(idx);
                                }
                            }
                        }
                    }
                } else if (title.contains("请问图中深色文字中包含几个字符")) {
                    answer = String.valueOf(resultText.length());
                }
            }
            return resultText + "\n正确答案：" + answer;
        } catch (Exception e) {
            e.printStackTrace();
            saveErrorImage(imageUrl);
            return "识别失败，请手动点击验证码";
        } finally {
            if (mat != null) mat.release();
        }
    }

    public RecognitionResult recognizeCaptcha(Mat image) {
        if (image.empty()) throw new RuntimeException("无法读取图像");

        List<DetectionResult> detections = detectObjects(image);
        detections.sort(Comparator.comparingInt(d -> d.box.x));

        StringBuilder result = new StringBuilder();
        List<String> emojiList = new ArrayList<>();

        int lastX = -10000;
        for (DetectionResult d : detections) {
            // 简单基于X坐标距离的去重，避免重复字符
            if (d.box.x - lastX < 5) continue;

            Mat cropped = new Mat(image, d.box);
            try {
                String cls = classifyRegion(cropped);
                result.append(cls);
                if ("表情".equals(DETECTION_CLASSES[d.classId])) emojiList.add(cls);
            } finally {
                cropped.release();
            }
            lastX = d.box.x;
        }
        return new RecognitionResult(emojiList, result.toString());
    }

    private List<DetectionResult> detectObjects(Mat image) {
        Mat blob = Dnn.blobFromImage(image, 1 / 255.0, new Size(416, 416),
                new Scalar(0, 0, 0), true, false);
        detectionNet.setInput(blob);

        List<Mat> outs = new ArrayList<>();
        detectionNet.forward(outs, detectionNet.getUnconnectedOutLayersNames());

        List<DetectionResult> rawDetections = new ArrayList<>();
        for (Mat out : outs) {
            for (int i = 0; i < out.rows(); i++) {
                Mat row = out.row(i);
                Mat scores = row.colRange(5, out.cols());
                Core.MinMaxLocResult mm = Core.minMaxLoc(scores);
                float conf = (float) mm.maxVal;
                if (conf > CONFIDENCE_THRESHOLD) {
                    float centerX = (float) row.get(0, 0)[0] * image.cols();
                    float centerY = (float) row.get(0, 1)[0] * image.rows();
                    float width = (float) row.get(0, 2)[0] * image.cols();
                    float height = (float) row.get(0, 3)[0] * image.rows();

                    int x = Math.max(0, (int) (centerX - width / 2));
                    int y = Math.max(0, (int) (centerY - height / 2));
                    int w = Math.min((int) width, image.cols() - x);
                    int h = Math.min((int) height, image.rows() - y);

                    if (w > 0 && h > 0)
                        rawDetections.add(new DetectionResult(new Rect(x, y, w, h), (int) mm.maxLoc.x, conf));
                }
                row.release();
                scores.release();
            }
            out.release();
        }
        blob.release();

        // NMS 非极大值抑制去除重叠框
        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        for (DetectionResult d : rawDetections) {
            boxes2d.add(new Rect2d(d.box.x, d.box.y, d.box.width, d.box.height));
            confidences.add(d.confidence);
        }
        MatOfRect2d boxesMat = new MatOfRect2d();
        boxesMat.fromList(boxes2d);
        MatOfFloat confidencesMat = new MatOfFloat();
        confidencesMat.fromList(confidences);
        MatOfInt indices = new MatOfInt();

        Dnn.NMSBoxes(boxesMat, confidencesMat, CONFIDENCE_THRESHOLD, NMS_THRESHOLD, indices);

        List<DetectionResult> finalDetections = new ArrayList<>();
        for (int idx : indices.toArray()) {
            finalDetections.add(rawDetections.get(idx));
        }

        boxesMat.release();
        confidencesMat.release();
        indices.release();

        return finalDetections;
    }

    private String classifyRegion(Mat region) {
        Mat processed = new Mat(), resized = new Mat(), blob = new Mat(), output = new Mat();
        try {
            if (region.channels() == 1) Imgproc.cvtColor(region, processed, Imgproc.COLOR_GRAY2BGR);
            else if (region.channels() == 4) Imgproc.cvtColor(region, processed, Imgproc.COLOR_BGRA2BGR);
            else region.copyTo(processed);

            Imgproc.resize(processed, resized, new Size(32, 32));
            blob = Dnn.blobFromImage(resized, 1 / 255.0, new Size(32, 32), new Scalar(0, 0, 0), true, false);

            classificationNet.setInput(blob);
            output = classificationNet.forward().reshape(1, 1);

            int best = -1;
            float max = -Float.MAX_VALUE;
            for (int i = 0; i < Math.min(output.cols(), CLASSIFICATION_CLASSES.length); i++) {
                float score = (float) output.get(0, i)[0];
                if (score > max) {
                    max = score;
                    best = i;
                }
            }
            return (best >= 0) ? CLASSIFICATION_CLASSES[best] : "?";
        } finally {
            processed.release();
            resized.release();
            blob.release();
            output.release();
        }
    }

    public static Mat downloadImageToMat(String url) throws IOException {
        try (InputStream in = new URL(url).openStream()) {
            return Imgcodecs.imdecode(new MatOfByte(readAllBytes(in)), Imgcodecs.IMREAD_COLOR);
        }
    }

    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) buf.write(b, 0, n);
        return buf.toByteArray();
    }

    public void saveErrorImage(String url) {
        try {
            Mat img = downloadImageToMat(url);
            File dir = new File("errorPic");
            if (!dir.exists()) dir.mkdirs();
            File[] files = dir.listFiles();
            if (files != null && files.length > 500) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                files[0].delete();
            }

            String path = "errorPic/error_" + System.currentTimeMillis() + ".jpg";
            Imgcodecs.imwrite(path, img);
            img.release();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Character extractTargetChar(String q) {
        int s = q.indexOf("“"), e = q.indexOf("”");
        if (s == -1 || e == -1) {
            s = q.indexOf("\"");
            e = q.lastIndexOf("\"");
        }
        return (s != -1 && e > s + 1) ? q.charAt(s + 1) : null;
    }

    public static int countCharOccurrences(String t, char c) {
        int cnt = 0;
        for (char ch : t.toCharArray()) if (ch == c) cnt++;
        return cnt;
    }

    public static int calculate(String expr) {
        Pattern p = Pattern.compile("(\\d+|[" + String.join("", CHINESE_NUMBERS.keySet()) + "]+)" +
                "([加减乘])" +
                "(\\d+|[" + String.join("", CHINESE_NUMBERS.keySet()) + "]+)");
        Matcher m = p.matcher(expr);
        return m.find() ? compute(parseNumber(m.group(1)), m.group(2), parseNumber(m.group(3))) : -1;
    }

    private static int compute(int a, String op, int b) {
        switch (op) {
            case "加":
                return a + b;
            case "减":
                return a - b;
            case "乘":
                return a * b;
            default:
                throw new RuntimeException("不支持的运算符: " + op);
        }
    }

    private static int parseNumber(String s) {
        return s.matches("\\d+") ? Integer.parseInt(s) : CHINESE_NUMBERS.getOrDefault(s, -1);
    }

    public static class RecognitionResult {
        public List<String> emojiList;
        public String result;

        public RecognitionResult(List<String> list, String r) {
            this.emojiList = list;
            this.result = r;
        }
    }

    private static class DetectionResult {
        Rect box;
        int classId;
        float confidence;

        DetectionResult(Rect b, int c, float f) {
            box = b;
            classId = c;
            confidence = f;
        }
    }
}
