"""POST /api/v1/export — 把内容导出为 txt/md/docx/xlsx/pdf（§10.2 扩展）。

请求 JSON：{format, text, blocks?}；成功返回文件字节流（带 Content-Disposition），
失败返回统一信封 {ok:false, error}（友好中文提示）。
"""
from __future__ import annotations

from fastapi import APIRouter
from fastapi.responses import JSONResponse, Response

from ..exporters import export
from ..models import ApiResponse, ExportRequest
from ..parsers import UnsupportedFormatError

router = APIRouter(prefix="/api/v1", tags=["export"])


@router.post("/export")
async def export_doc(req: ExportRequest):
    try:
        data, content_type, ext = export(req.format, req.text, req.blocks or None)
    except UnsupportedFormatError as e:
        return JSONResponse(status_code=200, content=ApiResponse(ok=False, error=str(e)).model_dump())
    return Response(
        content=data,
        media_type=content_type,
        headers={"Content-Disposition": f'attachment; filename="export.{ext}"'},
    )
