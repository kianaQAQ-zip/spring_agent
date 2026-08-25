"""doc-processor 接口契约（Pydantic v2）。

所有响应统一信封 {ok, data, error}，与 §10.2 一致。
Java 侧 RestClient 调用时以此为准。
"""
from __future__ import annotations

from typing import Any, Optional

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# 统一响应信封
# ---------------------------------------------------------------------------
class ApiResponse(BaseModel):
    ok: bool = True
    data: Any = None
    error: Optional[str] = None


# ---------------------------------------------------------------------------
# /parse
# ---------------------------------------------------------------------------
class ParseOptions(BaseModel):
    ocr: bool = True
    clean: bool = True


class ParseBlock(BaseModel):
    block_type: str = Field(..., description="title|section|text|table|figure|list|footnote")
    text: str
    bbox: Optional[list[float]] = Field(None, description="[x0,y0,x1,y1]，归一化或像素坐标")
    page: int = Field(..., ge=1, description="1-based 页码")
    reading_order: int = Field(..., ge=0, description="全局阅读顺序，越小越靠前")
    token_count: int = Field(0, description="CJK 近似 token 数")


class ParseData(BaseModel):
    blocks: list[ParseBlock]
    clean_score: float = Field(..., ge=0.0, le=1.0)
    flags: list[str] = Field(default_factory=list, description="如 low_quality / ocr_fallback / multi_column")


# ---------------------------------------------------------------------------
# /clean
# ---------------------------------------------------------------------------
class CleanRequest(BaseModel):
    text: str = Field(..., min_length=1)


class CleanResponse(BaseModel):
    cleaned_text: str
    clean_score: float = Field(..., ge=0.0, le=1.0)
    removed_flags: list[str] = Field(default_factory=list, description="如 header_footer / toc / dup / control_char")


# ---------------------------------------------------------------------------
# /rerank
# ---------------------------------------------------------------------------
class RerankDoc(BaseModel):
    id: str
    text: str


class RerankRequest(BaseModel):
    query: str = Field(..., min_length=1)
    documents: list[RerankDoc] = Field(..., min_length=1)
    top_n: Optional[int] = Field(None, description="None 表示返回全量降序")


class RankedItem(BaseModel):
    id: str
    score: float


class RerankResponse(BaseModel):
    ranked: list[RankedItem]


# ---------------------------------------------------------------------------
# /caption
# ---------------------------------------------------------------------------
class CaptionRequest(BaseModel):
    image_base64: Optional[str] = Field(None, description="base64 图片（不含 data: 前缀）")
    image_url: Optional[str] = Field(None, description="图片 URL")
    prompt: Optional[str] = Field(None, description="可选自定义提示")


class CaptionResponse(BaseModel):
    caption: str
