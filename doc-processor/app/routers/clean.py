"""POST /api/v1/clean — 纯文本清洗链（§9.1）。"""
from __future__ import annotations

from fastapi import APIRouter

from ..cleaner import DocumentCleaner
from ..models import ApiResponse, CleanRequest, CleanResponse

router = APIRouter(prefix="/api/v1", tags=["clean"])
_cleaner = DocumentCleaner()


@router.post("/clean")
def clean(req: CleanRequest) -> ApiResponse:
    r = _cleaner.clean(req.text)
    return ApiResponse(
        ok=True,
        data=CleanResponse(
            cleaned_text=r.cleaned_text,
            clean_score=r.clean_score,
            removed_flags=r.removed_flags,
        ),
    )
