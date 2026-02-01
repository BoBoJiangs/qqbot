FROM python:3.10-slim

# 设置环境变量，避免交互提示
ENV DEBIAN_FRONTEND=noninteractive

# 替换 APT 源为清华源，并安装依赖（不需要 libgl1 等 GUI 相关库）
RUN echo "deb https://mirrors.tuna.tsinghua.edu.cn/debian/ bullseye main contrib non-free" > /etc/apt/sources.list && \
    echo "deb https://mirrors.tuna.tsinghua.edu.cn/debian/ bullseye-updates main contrib non-free" >> /etc/apt/sources.list && \
    echo "deb https://mirrors.tuna.tsinghua.edu.cn/debian-security bullseye-security main contrib non-free" >> /etc/apt/sources.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends build-essential && \
    rm -rf /var/lib/apt/lists/*

# 设置 pip 清华源（可选）
RUN mkdir -p /root/.pip && \
    echo '[global]\nindex-url = https://pypi.tuna.tsinghua.edu.cn/simple' > /root/.pip/pip.conf

# 创建应用目录
WORKDIR /app

# 拷贝代码到容器
COPY . /app

# 安装依赖（使用 opencv-python-headless，适合无GUI）
RUN pip install --no-cache-dir -r requirements.txt

# 启动 API 服务（FastAPI）
CMD ["uvicorn", "captcha_api:app", "--host", "0.0.0.0", "--port", "8000"]
