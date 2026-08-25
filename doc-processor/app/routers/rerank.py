"""POST /api/v1/rerank — Cross-encoder 重排（§9.5 Stage2）。

懒加载单例 `bge-reranker-v2-m3`（FlagEmbedding）。模型不可用返回 ok=false，
Java 侧降级为 score 阈值 + MMR（§9.5 降级），不阻断主流程。
"""
from __future__ import annotations

import threading

from fastapi import APIRouter

from ..models import ApiResponse, RerankRequest, RerankResponse, RankedItem

router = APIRouter(prefix="/api/v1", tags=["rerank"])

_MODEL_NAME = "BAAI/bge-reranker-v2-m3"
_reranker = None
_lock = threading.Lock()


def _get_reranker():
    """懒加载单例。返回 (model, error)。"""
    global _reranker
    if _reranker is not None:
        return _reranker, None
    with _lock:
        if _reranker is not None:
            return _reranker, None
        try:
            from FlagEmbedding import FlagReranker
        except ImportError:
            return None, "未安装 FlagEmbedding（需 `uv sync --extra ml`）"
        try:
            _reranker = FlagReranker(_MODEL_NAME, use_fp16=True)
        except Exception as e:
            return None, f"reranker 模型加载失败：{e}"
    return _reranker, None


@router.post("/rerank")
def rerank(req: RerankRequest) -> ApiResponse:
    model, err = _get_reranker()
    if err:
        return ApiResponse(ok=False, error=err)

    pairs = [[req.query, d.text] for d in req.documents]
    try:
        scores = model.compute_score(pairs, normalize=True)
    except Exception as e:
        return ApiResponse(ok=False, error=f"rerank 计算失败：{e}")

    # 单文档时 compute_score 返回标量，需包成列表
    if not isinstance(scores, (list, tuple)):
        scores = [scores]
    scored = sorted(
        zip(req.documents, scores),
        key=lambda x: x[1],
        reverse=True,
    )
    if req.top_n is not None and req.top_n > 0:
        scored = scored[: req.top_n]
    ranked = [RankedItem(id=d.id, score=round(float(s), 4)) for d, s in scored]
    return ApiResponse(ok=True, data=RerankResponse(ranked=ranked))
