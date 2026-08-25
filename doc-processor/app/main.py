"""doc-processor FastAPI 入口。

挂载 4 个路由：parse / clean / rerank / caption（§10.2 接口契约）。
统一异常 → 信封 {ok:false, error}，保证 Java 侧 RestClient 不解析裸 500。
"""
from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .models import ApiResponse
from .routers import parse, clean, rerank, caption

app = FastAPI(title="doc-processor", version="0.1.0", description="电商客服 Agent 文档处理子项目")

app.include_router(parse.router)
app.include_router(clean.router)
app.include_router(rerank.router)
app.include_router(caption.router)


@app.exception_handler(Exception)
async def unhandled_exc(request: Request, exc: Exception):
    return JSONResponse(
        status_code=200,
        content=ApiResponse(ok=False, error=str(exc)).model_dump(),
    )


@app.get("/health")
def health():
    return ApiResponse(ok=True, data={"status": "UP", "service": "doc-processor"})
