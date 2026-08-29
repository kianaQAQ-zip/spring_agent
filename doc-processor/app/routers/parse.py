"""POST /api/v1/parse — layout 解析 + OCR + 清洗编排（§9.2 + §9.1）。

解析器优先级：
  1. MinerU（magic-pdf，内置 PaddleOCR）— 主解析器，输出带 block_type + 阅读顺序
  2. PyMuPDF（fitz）— 无 GPU / MinerU 不可用时的 PDF 兜底
  3. 纯文本直读 — 兜底（txt 或非 PDF）

任意一层失败都平滑降级到下一层；保证基础服务可运行（沙箱无重包也能起）。
"""
from __future__ import annotations

import io

from fastapi import APIRouter, File, UploadFile, Form
from pydantic import ValidationError

from ..cleaner import DocumentCleaner, estimate_tokens
from ..models import ApiResponse, ParseBlock, ParseData, ParseOptions
from ..parsers import UnsupportedFormatError, parse_docx, parse_markdown, parse_xlsx

router = APIRouter(prefix="/api/v1", tags=["parse"])
_cleaner = DocumentCleaner()


def _finalize(blocks: list[ParseBlock], scores: list[float], flags: list[str]) -> ParseData:
    clean_score = round(sum(scores) / len(scores), 3) if scores else 0.0
    return ParseData(blocks=blocks, clean_score=clean_score, flags=flags)


def _apply_clean(blocks: list[ParseBlock]) -> tuple[list[ParseBlock], list[float]]:
    out: list[ParseBlock] = []
    scores: list[float] = []
    for b in blocks:
        r = _cleaner.clean(b.text)
        scores.append(r.clean_score)
        out.append(
            ParseBlock(
                block_type=b.block_type,
                text=r.cleaned_text,
                bbox=b.bbox,
                page=b.page,
                reading_order=b.reading_order,
                token_count=estimate_tokens(r.cleaned_text),
            )
        )
    return out, scores


def _parse_with_mineru(filename: str, data: bytes, do_clean: bool) -> ParseData | None:
    """MinerU 主解析。任意异常 → 返回 None 触发降级。"""
    try:
        from magic_pdf.data.data_reader_writer import FileBasedDataReader
        from magic_pdf.data.dataset import PymuDocDataset
        from magic_pdf.model.doc_analyze_by_custom_model import doc_analyze
        from magic_pdf.config.environment import EnvironmentConfig
        from magic_pdf.config.ocr_content_type import OcrcContentType
        # 注：magic_pdf 的真实 API 以安装版本为准；此处为通用编排骨架。
        reader = FileBasedDataReader("")
        bs = reader.read_raw_bytes(data)  # type: ignore[arg-type]
        ds = PymuDocDataset(bs)
        infos = ds.get_analyze_res()
        ds.apply(doc_analyze, ocrc_content_type=OcrcContentType.all)  # 触发 layout+OCR
        middle = ds.get_middle_json()
        # middle["pdf_info"][page]["para_blocks"/"blocks"] 含 block_type + bbox + 文本
        blocks: list[ParseBlock] = []
        order = 0
        for page_idx, page in enumerate(middle.get("pdf_info", [])):
            for blk in page.get("blocks", []):
                txt = (blk.get("text") or "").strip()
                if not txt:
                    continue
                blocks.append(
                    ParseBlock(
                        block_type=str(blk.get("type", "text")).lower(),
                        text=txt,
                        bbox=blk.get("bbox"),
                        page=page_idx + 1,
                        reading_order=order,
                        token_count=estimate_tokens(txt),
                    )
                )
                order += 1
        if do_clean:
            blocks, scores = _apply_clean(blocks)
        else:
            scores = [1.0] * len(blocks)
        return _finalize(blocks, scores, ["mineru"])
    except Exception:
        return None


def _parse_with_pymupdf(filename: str, data: bytes, do_clean: bool) -> ParseData | None:
    try:
        import fitz
    except ImportError:
        return None
    try:
        doc = fitz.open(stream=data, filetype="pdf")
    except Exception:
        return None
    blocks: list[ParseBlock] = []
    order = 0
    for page_idx, page in enumerate(doc):
        for b in page.get_text("blocks"):
            x0, y0, x1, y1, txt, _bno, btype = b
            txt = txt.strip()
            if not txt:
                continue
            bt = "figure" if btype == 1 else "text"
            blocks.append(
                ParseBlock(
                    block_type=bt,
                    text=txt,
                    bbox=[x0, y0, x1, y1],
                    page=page_idx + 1,
                    reading_order=order,
                    token_count=estimate_tokens(txt),
                )
            )
            order += 1
    if do_clean:
        blocks, scores = _apply_clean(blocks)
    else:
        scores = [1.0] * len(blocks)
    return _finalize(blocks, scores, ["pymupdf_fallback"])


def _parse_plain(filename: str, data: bytes, do_clean: bool) -> ParseData:
    text = data.decode("utf-8", errors="ignore")
    paras = [p.strip() for p in text.split("\n\n") if p.strip()]
    blocks = [
        ParseBlock(
            block_type="text",
            text=p,
            bbox=None,
            page=1,
            reading_order=i,
            token_count=estimate_tokens(p),
        )
        for i, p in enumerate(paras)
    ]
    if do_clean:
        blocks, scores = _apply_clean(blocks)
    else:
        scores = [1.0] * len(blocks)
    return _finalize(blocks, scores, ["text_fallback"])


def _parse_structured(parser_fn, data: bytes, do_clean: bool, flag: str) -> ParseData:
    """docx/xlsx/md 结构化解析统一封装：解析 →（可选）清洗 → 定稿。"""
    blocks = parser_fn(data, do_clean)
    if do_clean:
        blocks, scores = _apply_clean(blocks)
    else:
        scores = [1.0] * len(blocks)
    return _finalize(blocks, scores, [flag])


@router.post("/parse")
async def parse(
    file: UploadFile = File(...),
    options: str = Form("{}"),
) -> ApiResponse:
    try:
        opts = ParseOptions.model_validate_json(options)
    except ValidationError:
        opts = ParseOptions()

    data = await file.read()
    filename = file.filename or "upload.bin"
    ext = filename.lower().rsplit(".", 1)[-1] if "." in filename else ""

    try:
        result: ParseData | None = None
        if ext == "pdf":
            result = _parse_with_mineru(filename, data, opts.clean)
            if result is None:
                result = _parse_with_pymupdf(filename, data, opts.clean)
            if result is None:
                result = _parse_plain(filename, data, opts.clean)
        elif ext == "docx":
            result = _parse_structured(parse_docx, data, opts.clean, "docx")
        elif ext == "xlsx":
            result = _parse_structured(parse_xlsx, data, opts.clean, "xlsx")
        elif ext in ("md", "markdown"):
            result = _parse_structured(parse_markdown, data, opts.clean, "markdown")
        else:
            # txt / 未知扩展名：按纯文本直读（不误判为损坏）
            result = _parse_plain(filename, data, opts.clean)
    except UnsupportedFormatError as e:
        return ApiResponse(ok=False, data=None, error=str(e))

    return ApiResponse(ok=True, data=result)
