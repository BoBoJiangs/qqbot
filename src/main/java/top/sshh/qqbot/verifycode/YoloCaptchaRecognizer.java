package top.sshh.qqbot.verifycode;

import org.apache.commons.lang3.StringUtils;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class YoloCaptchaRecognizer {
    private static final Map<String, Integer> CHINESE_NUMBERS = new HashMap<>();

    static {
        // 中文数字映射
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
        // 加载 OpenCV 本地库
//        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        nu.pattern.OpenCV.loadLocally();
//        OpenCV.loadShared();
    }

    // 模型路径
    private  String detectionModelWeights;
    private  String detectionModelConfig;
    private  String classificationModelWeights;
    private  String classificationModelConfig;

    // 网络对象
    private Net detectionNet;
    private Net classificationNet;

    // 检测类别（文本和表情）
    private static final String[] DETECTION_CLASSES = {"文字", "表情", "数字"};

    // 更新后的中文分类类别
    private static final String[] CLASSIFICATION_CLASSES = {
            "请", "击", "5", "的", "加", "点", "结", "果", "8",
            "2", "五", "情", "按", "第", "钮", "表", "图", "中",
            "乘", "书本", "电池", "图钉", "1", "9", "钉", "3", "电",
            "瓜", "鸡头", "鲸鱼", "个", "九", "减", "苹", "七", "6",
            "西", "鱼", "鲸", "四", "4", "车", "蟹", "0", "汽",
            "漏", "螃", "沙", "苹果", "池", "萄", "葡", "脑", "鸡",
            "书", "西瓜", "电脑", "本·", "沙漏", "7", "六", "汽车",
            "葡萄", "本", "书沙"
    };

    // 置信度阈值
    private static final float CONFIDENCE_THRESHOLD = 0.5f;
    private static final float NMS_THRESHOLD = 0.4f;

//    public YoloCaptchaRecognizer(
//            String detectionModelConfig,
//            String detectionModelWeights,
//            String classificationModelConfig,
//            String classificationModelWeights) {
//
//        this.detectionModelConfig = "models/yolov4-tiny.cfg";
//        this.detectionModelWeights = "models/yolov4-tiny.weights";
//        this.classificationModelConfig = "models/darknet.cfg";
//        this.classificationModelWeights = "models/darknet_last.weights";
//
//        loadModels();
//
//    }

    @PostConstruct
    public void init() {
        detectionModelConfig = "models/yolov4-tiny.cfg";
        detectionModelWeights = "models/yolov4-tiny.weights";
        classificationModelConfig = "models/darknet.cfg";
        classificationModelWeights = "models/darknet_last.weights";

        loadModels();
    }

    // 加载模型
    private void loadModels() {
        // 检查模型文件
        checkModelFile(detectionModelConfig);
        checkModelFile(detectionModelWeights);
        checkModelFile(classificationModelConfig);
        checkModelFile(classificationModelWeights);

        try {
            // 加载检测模型 - 方法1：使用 readNet
            detectionNet = Dnn.readNet(detectionModelWeights, detectionModelConfig);

            // 方法2：或者继续使用 readNetFromDarknet 但禁用 Winograd
            // detectionNet = Dnn.readNetFromDarknet(detectionModelConfig, detectionModelWeights);

            detectionNet.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            detectionNet.setPreferableTarget(Dnn.DNN_TARGET_CPU);

            // 关键修复：禁用 Winograd 优化
            if (System.getProperty("os.arch").toLowerCase().contains("aarch64")) {
                // ARM 架构可能需要特别处理
                System.setProperty("OPENCV_OPENCL_DEVICE", "CPU:ARM");
            }
//            detectionNet.enableWinograd(false);

            // 加载分类模型
            classificationNet = Dnn.readNet(classificationModelWeights, classificationModelConfig);
            classificationNet.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            classificationNet.setPreferableTarget(Dnn.DNN_TARGET_CPU);
//            classificationNet.enableWinograd(false);

            System.out.println("YOLO模型加载成功");
        } catch (Exception e) {
            throw new RuntimeException("加载模型失败: " + e.getMessage(), e);
        }
    }

    private void checkModelFile(String path) {
        java.io.File file = new java.io.File(path);
        if (!file.exists()) {
            throw new RuntimeException("模型文件未找到: " + path);
        }
    }

    // 识别验证码
    public RecognitionResult recognizeCaptcha(Mat image) {

        if (image.empty()) {
            throw new RuntimeException("无法读取图像: ");
        }

        // 使用检测模型找出所有文字和表情区域
        List<DetectionResult> detections = detectObjects(image);

        // 按位置排序（从左到右）
        detections.sort((d1, d2) -> Double.compare(d1.box.x, d2.box.x));

        // 识别每个区域
        StringBuilder result = new StringBuilder();
        List<String> emojiList = new ArrayList<>();
        for (DetectionResult detection : detections) {
            // 裁剪区域
            Mat cropped = new Mat(image, detection.box);

            // 使用分类模型识别内容
            String classification = classifyRegion(cropped);

            // 添加到结果
            result.append(classification);

            // 打印检测信息
            System.out.printf("检测到 %s: [x=%d, y=%d, w=%d, h=%d] -> 分类为: %s%n",
                    DETECTION_CLASSES[detection.classId],
                    detection.box.x, detection.box.y,
                    detection.box.width, detection.box.height,
                    classification);
            if(DETECTION_CLASSES[detection.classId].equals("表情")){
                emojiList.add(classification);
            }
        }

        return new RecognitionResult(emojiList,result.toString());
    }

    private static class RecognitionResult {
        List<String> emojiList;
        String result;

        public RecognitionResult(List<String> emojiList, String result) {
            this.emojiList = emojiList;
            this.result = result;
        }
    }

    // 检测对象并返回包含类别信息的结果
    private List<DetectionResult> detectObjects(Mat image) {
        // 准备输入图像
        Mat blob = Dnn.blobFromImage(image, 1 / 255.0, new Size(416, 416), new Scalar(0, 0, 0), true, false);
        detectionNet.setInput(blob);

        // 获取输出层名称
        List<String> outLayerNames = detectionNet.getUnconnectedOutLayersNames();

        // 运行推理
        List<Mat> outputs = new ArrayList<>();
        detectionNet.forward(outputs, outLayerNames);

        // 解析检测结果
        List<DetectionResult> detections = new ArrayList<>();

        for (Mat output : outputs) {
            for (int i = 0; i < output.rows(); i++) {
                Mat row = output.row(i);
                Mat scores = row.colRange(5, output.cols());
                Core.MinMaxLocResult mm = Core.minMaxLoc(scores);

                float confidence = (float) mm.maxVal;
                if (confidence > CONFIDENCE_THRESHOLD) {
                    // 获取边界框坐标
                    float centerX = (float) row.get(0, 0)[0] * image.cols();
                    float centerY = (float) row.get(0, 1)[0] * image.rows();
                    float width = (float) row.get(0, 2)[0] * image.cols();
                    float height = (float) row.get(0, 3)[0] * image.rows();

                    // 转换为矩形坐标
                    int x = (int) (centerX - width / 2);
                    int y = (int) (centerY - height / 2);
                    int w = (int) width;
                    int h = (int) height;

                    // 确保在图像范围内
                    x = Math.max(0, x);
                    y = Math.max(0, y);
                    w = Math.min(w, image.cols() - x);
                    h = Math.min(h, image.rows() - y);

                    if (w > 0 && h > 0) {
                        Rect box = new Rect(x, y, w, h);
                        int classId = (int) mm.maxLoc.x;
                        detections.add(new DetectionResult(box, classId, confidence));
                    }
                }
            }
        }

        // 应用非极大值抑制 (NMS)
        if (detections.isEmpty()) {
            return new ArrayList<>();
        }

        // 准备NMS输入
        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        for (DetectionResult detection : detections) {
            boxes2d.add(new Rect2d(
                    detection.box.x,
                    detection.box.y,
                    detection.box.width,
                    detection.box.height
            ));
            confidences.add(detection.confidence);
        }

        MatOfRect2d boxesMat = new MatOfRect2d();
        boxesMat.fromList(boxes2d);

        MatOfFloat confidencesMat = new MatOfFloat();
        confidencesMat.fromList(confidences);

        // 应用NMS
        MatOfInt indices = new MatOfInt();
        Dnn.NMSBoxes(boxesMat, confidencesMat, CONFIDENCE_THRESHOLD, NMS_THRESHOLD, indices);

        // 收集最终检测结果
        List<DetectionResult> finalDetections = new ArrayList<>();
        for (int index : indices.toArray()) {
            finalDetections.add(detections.get(index));
        }

        return finalDetections;
    }

    // 分类单个区域
    private String classifyRegion(Mat region) {
        try {
            // 1. 转换图像格式
            Mat processed = new Mat();
            if (region.channels() == 1) {
                Imgproc.cvtColor(region, processed, Imgproc.COLOR_GRAY2BGR);
            } else if (region.channels() == 4) {
                Imgproc.cvtColor(region, processed, Imgproc.COLOR_BGRA2BGR);
            } else {
                region.copyTo(processed);
            }

            // 2. 调整尺寸
            Mat resized = new Mat();
            Imgproc.resize(processed, resized, new Size(32, 32));

            // 3. 归一化处理
            Mat blob = Dnn.blobFromImage(resized, 1.0 / 255.0, new Size(32, 32),
                    new Scalar(0, 0, 0), true, false);

            // 4. 设置模型输入
            classificationNet.setInput(blob);

            // 5. 运行推理
            Mat output = classificationNet.forward();

            // 6. 打印输出形状（调试）
            System.out.print("Output shape: [");
            for (int i = 0; i < output.dims(); i++) {
                System.out.print(output.size(i));
                if (i < output.dims() - 1) {
                    System.out.print(", ");
                }
            }
            System.out.println("]");

            // 7. 将输出转换为 1D 数组（如果输出是多维的）
//            output = output.reshape(1, (int) output.total());
            // 重塑输出为 [1, 64] 格式
            output = output.reshape(1, 1);

            // 只取前60个类别
            int numClasses = Math.min(output.cols(), CLASSIFICATION_CLASSES.length);

            // 8. 打印前 10 个输出值（调试）
            float[] scores = new float[Math.min(10, output.cols())];
            output.get(0, 0, scores);
            System.out.println("Top 10 scores: " + Arrays.toString(scores));

            // 9. 查找最高分
            int classId = -1;
            float maxScore = -Float.MAX_VALUE;
            for (int i = 0; i < numClasses; i++) {
                float score = (float) output.get(0, i)[0];
                if (score > maxScore) {
                    maxScore = score;
                    classId = i;
                }
            }

            if (classId >= 0 && classId < CLASSIFICATION_CLASSES.length) {
                System.out.printf("分类结果: %s (%.2f)%n",
                        CLASSIFICATION_CLASSES[classId], maxScore);
                return CLASSIFICATION_CLASSES[classId];
            }

            return "?";
        } catch (Exception e) {
            e.printStackTrace();
            return "?";
        }
    }

    // 检测结果容器类
    private static class DetectionResult {
        Rect box;
        int classId;
        float confidence;

        DetectionResult(Rect box, int classId, float confidence) {
            this.box = box;
            this.classId = classId;
            this.confidence = confidence;
        }
    }

    public String recognizeVerifyCode(String imagePath,String title) {
        System.out.println("开始识别验证码: " + imagePath);
        try {
            Mat mat = downloadImageToMat(imagePath);
            RecognitionResult recognitionResult = recognizeCaptcha(mat);
            String resultText = recognitionResult.result;

            String answer = "";
            if(StringUtils.isNotBlank(title) && StringUtils.isNotBlank(resultText)){
                if(title.contains("请问深色文字中字符") && title.contains("出现了几次")){
                    Character targetChar = extractTargetChar(title);
                    if (targetChar == null) {
                        System.out.println("未找到要统计的字符！");
                        return "识别失败，请手动点击验证码";
                    }
                    // 2. 统计字符出现次数
                    answer = countCharOccurrences(resultText, targetChar) + "";
                }else if(title.contains("请按照深色文字的题目点击对应的答案")){
                    if(resultText.contains("加") || resultText.contains("减") || resultText.contains("乘") || resultText.contains("除")){
                        answer = calculate(resultText) + "";
                    }

                    if(resultText.startsWith("请点击") && resultText.length()<7){
                        answer = resultText.substring("请点击".length()).trim();
                        if(answer.equals("漏萄")){
                            answer = "葡萄";
                        }
                    }

                    if(resultText.contains("个表情") || resultText.contains("第")){
                        if(recognitionResult.emojiList.isEmpty()){
                            resultText = resultText.replaceAll("请点击请","请点击第");
                            answer = resultText.split("[第个]")[1];
                            if(StringUtils.isNumeric(answer)){
                                answer = "序号"+ answer;
                            }else{
                                answer = "序号"+ parseNumber(answer);
                            }

                        }else{
                            answer = recognitionResult.emojiList.get(Integer.parseInt(resultText.split("[第个]")[1])-1);

                        }

                    }

                }else if(title.contains("请问图中深色文字中包含几个字符")){
                    answer = resultText.length() + "";
                }
            }
            resultText =  resultText + "\n正确答案："+answer;
//            System.out.println("识别结果: " + resultText + "正确答案："+answer);
            return resultText;
        } catch (IOException e) {

            System.err.println("验证码识别失败，尝试保存错误图片: " + imagePath);
            e.printStackTrace();
            saveErrorImage(imagePath);
            return "识别失败，请手动点击验证码";
        }
    }

    public void saveErrorImage(String imagePath) {
        // 保存错误图片
        try {
            Mat errorImage = downloadImageToMat(imagePath); // 尝试重新读取图片
            File dir = new File("errorPic");
            if (!dir.exists()) {
                dir.mkdirs(); // 自动创建目录
            }

            String filename = "errorPic/error_" + System.currentTimeMillis() + ".jpg";
            Imgcodecs.imwrite(filename, errorImage);
            System.out.println("错误图片已保存到：" + filename);
        } catch (Exception ex) {
            System.err.println("保存错误图片失败：" + ex.getMessage());
        }
    }



    public static Mat downloadImageToMat(String imageUrl) throws IOException {
        URL url = new URL(imageUrl);
        InputStream inputStream = url.openStream();
        byte[] imageBytes = readAllBytes(inputStream);
        Mat imageMat = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);
        return imageMat;
    }

    public static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192]; // 8KB buffer
        int bytesRead;
        while ((bytesRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toByteArray();
    }

    // 提取题干中的目标字符（在引号中的）
    public static Character extractTargetChar(String question) {
        int start = question.indexOf("“");
        int end = question.indexOf("”");

        // 如果找不到中文引号，尝试英文引号
        if (start == -1 || end == -1) {
            start = question.indexOf("\"");
            end = question.lastIndexOf("\"");
        }

        if (start != -1 && end != -1 && end > start + 1) {
            return question.charAt(start + 1);
        }
        return null;
    }

    // 统计字符出现次数
    public static int countCharOccurrences(String text, char target) {
        int count = 0;
        for (char c : text.toCharArray()) {
            if (c == target) {
                count++;
            }
        }
        return count;
    }

    public static int calculate(String question) {
        // 匹配数字和运算符（支持最多3个数字，2个运算符）
        Pattern pattern = Pattern.compile(
                "(\\d+|[" + String.join("", CHINESE_NUMBERS.keySet()) + "]+)" +
                        "([加减乘])" +
                        "(\\d+|[" + String.join("", CHINESE_NUMBERS.keySet()) + "]+)" +
                        "([加减乘])?" +
                        "(\\d+|[" + String.join("", CHINESE_NUMBERS.keySet()) + "]+)?"
        );
        Matcher matcher = pattern.matcher(question);

        if (matcher.find()) {
            int num1 = parseNumber(matcher.group(1));
            String op1 = matcher.group(2);
            int num2 = parseNumber(matcher.group(3));

            // 先计算前两个数
            int result = compute(num1, op1, num2);

            // 如果有第三个数和运算符，继续计算
            if (matcher.group(4) != null && matcher.group(5) != null) {
                String op2 = matcher.group(4);
                int num3 = parseNumber(matcher.group(5));
                result = compute(result, op2, num3);
            }

            return result;
        } else {
            throw new IllegalArgumentException("无法解析题目: " + question);
        }
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
                throw new IllegalArgumentException("不支持的运算符: " + op);
        }
    }




    private static int parseNumber(String numStr) {
        if (numStr.matches("\\d+")) {
            return Integer.parseInt(numStr);
        }
        if (CHINESE_NUMBERS.containsKey(numStr)) {
            return CHINESE_NUMBERS.get(numStr);
        }
        throw new IllegalArgumentException("无法识别的数字: " + numStr);
    }

//    public static void main(String[] args) {
//        // 模型路径配置
//        String detectionCfg = "models/yolov4-tiny.cfg";
//        String detectionWeights = "models/yolov4-tiny.weights";
//        String classificationCfg = "models/darknet.cfg";
//        String classificationWeights = "models/darknet_last.weights";
//
//        // 创建识别器
//        YoloCaptchaRecognizer recognizer = new YoloCaptchaRecognizer(
//                detectionCfg, detectionWeights,
//                classificationCfg, classificationWeights
//        );
//
//        // 识别验证码
////        String imagePath = "src/main/resources/13.jpg";
//        String imagePath = "https://qqbot.ugcimg.cn/102074059/dee967b60b087b408885838a315cfd14fdf899d6/ec1e96cda9ccf559e6e59059c7dc20e5";
//        System.out.println("开始识别验证码: " + imagePath);
//        try {
//            Mat mat = downloadImageToMat(imagePath);
//            String result = recognizer.recognizeCaptcha(mat);
//            System.out.println("最终识别结果: " + result);
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//
//
//    }
}
