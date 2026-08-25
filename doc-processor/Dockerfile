# doc-processor 容器化（未来服务器部署，当前本机 uv run 即可）
# 构建：docker build -t doc-processor .
# 运行：docker run -p 8000:8000 --env DASHSCOPE_API_KEY=... doc-processor
FROM python:3.11-slim

# uv 安装（Astral 官方脚本）
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/

WORKDIR /app

# 先拷贝依赖清单，利用 Docker 层缓存
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --extra ml --extra pdf --no-dev

# 拷贝源码
COPY app ./app

EXPOSE 8000
CMD ["uv", "run", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
