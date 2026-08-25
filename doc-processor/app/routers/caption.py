"""POST /api/v1/caption — 图表/表格多模态摘要（§9.3，可选）。

调 Qwen-VL（DashScope OpenAI 兼容视觉接口）生成文字摘要。
无视觉接口/Key 时返回 ok=false，Java 侧图表退化为 "图片引用 + OCR 文本"（caption_fallback）。
"""
from __future__ import annotations

import os

from fastapi import APIRouter

from ..models import ApiResponse, CaptionRequest, CaptionResponse

router = APIRouter(prefix="/api/v1", tags=["caption"])

_DEFAULT_PROMPT = (
    "请用一句话概括这张电商业务图表/表格的核心信息（含关键数值与结论），"
    "不要解释、不要发散。"
)


def _build_messages(req: CaptionRequest):
    content = []
    if req.image_url:
        content.append({"image": req.image_url})
    elif req.image_base64:
        content.append({"image": f"data:image/png;base64,{req.image_base64}"})
    else:
        raise ValueError("image_base64 或 image_url 必须提供一个")
    content.append({"text": req.prompt or _DEFAULT_PROMPT})
    return [{"role": "user", "content": content}]


@router.post("/caption")
def caption(req: CaptionRequest) -> ApiResponse:
    if not req.image_base64 and not req.image_url:
        return ApiResponse(ok=False, error="image_base64 或 image_url 必须提供一个")
    api_key = os.getenv("DASHSCOPE_API_KEY")
    if not api_key:
        return ApiResponse(ok=False, error="未配置 DASHSCOPE_API_KEY，无法调用 Qwen-VL")

    try:
        import dashscope  # 懒导入：在 [ml] extra
        from dashscope import MultiModalConversation
    except ImportError:
        return ApiResponse(ok=False, error="未安装 dashscope（需 `uv sync --extra ml`）")

    dashscope.api_key = api_key
    try:
        resp = MultiModalConversation.call(
            model="qwen-vl-max",
            messages=_build_messages(req),
        )
    except Exception as e:
        return ApiResponse(ok=False, error=f"Qwen-VL 调用失败：{e}")

    if resp.get("status_code") != 200:
        return ApiResponse(ok=False, error=f"Qwen-VL 返回错误：{resp}")
    text = resp["output"]["choices"][0]["message"]["content"]
    # content 可能是 list[{text:...}] 结构
    if isinstance(text, list):
        text = "".join(part.get("text", "") for part in text)
    return ApiResponse(ok=True, data=CaptionResponse(caption=text.strip()))
