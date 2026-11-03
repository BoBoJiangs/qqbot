# KNN 背景抑制

管理后台打开 `/admin/knn-background`，使用原有管理员账号。默认关闭。

## 使用

1. 上传验证码图片，或在 KNN 人工纠错中点击“背景抑制预览”。
2. 勾选启用、选择轻度/标准/较强预设，或调整抑制强度、背景灵敏度、轮廓保护扩展、小轮廓内部保留。
3. 对照原图与处理后的检测框、分类标签及 KNN 相似度。调参只刷新预览，不保存、不计识别用量、
   不生成错误快照、不修改人工标注或索引。候选框数量变化不等于识别准确率变化。
4. 点击“保存并生效”，所有接入同一 API 的 KNN 请求从下一次开始读取新参数，不需要重启。
5. 关闭开关后保存即可恢复原图检测。重启 API 仍保留已保存的参数。

后台参数保存在服务工作目录的 `knn_background.json`，可通过 `KNN_BACKGROUND_FILE` 改变位置。
该文件属于运行配置，不随代码发布覆盖，也不提交 Git。并发打开页面时，旧页面保存会提示刷新，避免覆盖更新。

## 识别顺序与索引兼容

- 首次 `recognizer=primary` 沿用原图检测、分类，不读取新参数。
- 重试 `recognizer=knn`：读取一份参数快照 → 整图背景抑制 → 重新检测及排序 → 从原图按新框裁切分类 →
  KNN 原框/扩框特征检索。预处理不接收检测框，也不会仅清理原有候选框内部。
- 分类和 KNN 特征来自原图，以继续匹配当前原图样本索引，无需为每次调参重建基准索引。
- 原始图片及原始索引保留。失败上报会记录当次参数与版本，人工纠错继续使用原图与本次实际检测框。
- 响应新增 `knnBackground`，包含开关、实际是否处理、完整参数、版本与流程名；现有 Java 客户端兼容这些扩展字段。
- 配置损坏或处理失败会显式返回服务错误，不会静默使用另一组参数。首次主模型仍可正常处理。

算法保留彩色、深色像素及其轮廓，将其他区域混合为浅灰。浅色真实图标也可能受影响，深色干扰线仍可能保留。
部分图片在抑制后反而会新增误检框；请先预览并观察实际验证统计，不预设准确率提升。
预览图片最大 5 MB、200 万像素；抑制运算也限制为 200 万像素。

## 部署

只需更新验证码 API，Java 不需要更换。保留模型、`knn/` 索引、`knn_review/`、会员/白名单/用量/管理员数据，
以及已存在的 `knn_background.json`。

新增模块 `knn_background.py`、`recognition_pipeline.py`、`routes/knn_background_routes.py`、
`templates/knn_background.html`，并更新两个 `api_routes.py`、`config.py`、`captcha_api.py`、`admin_ui.py`、
`services/knn_review_service.py`、`templates/knn_review.html`。

管理员接口：`GET/POST /admin/knn-background/settings`；`POST /admin/knn-background/preview` 接收上传图片或已存在的错题 ID。
所有设置与预览都要求管理员登录并完成密码设置。未授权客户端不能通过识别请求指定或修改全局抑制参数。

测试：`python -B -m unittest discover -s tests -p 'test_knn*.py' -v`。
