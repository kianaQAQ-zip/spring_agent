"""DocumentCleaner — 非结构化文档清洗（§9.1）。

纯 Python 实现，可独立运行与测试。步骤：
1. 归一化：Unicode NFKC、去零宽/控制字符、去 BOM、CJK 换行规范化
2. 页眉页脚 / 目录残骸：高频重复行 + "标题.....页码" 点线模式删除
3. 近重复去重：SimHash（CJK 3-gram）跨段落去重
4. 质量评分：clean_score（噪声比、空行比、乱码比）
5. PII 预扫：知识库样例 PII 在 ingest 阶段即 mask（呼应 §5）
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

# PII 正则（与 Java PiiMaskUtil 对齐）
_RE_PHONE = re.compile(r"(1[3-9]\d)\d{4}(\d{4})")
_RE_ID = re.compile(r"(\d{6})\d{8}(\d{4})")
_RE_EMAIL = re.compile(r"([a-zA-Z0-9._+])[a-zA-Z0-9._+]*(@[a-zA-Z0-9.]+)")

# 控制字符（保留 \n \t \r）
_CTRL_RE = re.compile(r"[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]")
_ZERO_WIDTH = ("\u200b", "\u200c", "\u200d", "\u2060", "\ufeff", "\u00a0")
_TOC_RE = re.compile(r"^[^\n]{1,40}?\.{3,}\s*\d+\s*$")  # 标题.....12
_CJK_END = set("。！？；：，、）】”')")


def estimate_tokens(text: str) -> int:
    """CJK 近似 token 数：中文按 len/1.5，其余按空格分词（§9.4）。"""
    cjk = sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")
    other = len(re.findall(r"[A-Za-z0-9]+", text))
    return int(cjk / 1.5 + other)


def _mask_pii(text: str) -> str:
    s = _RE_PHONE.sub(r"\1****\2", text)
    s = _RE_ID.sub(r"\1********\2", s)
    s = _RE_EMAIL.sub(r"\1***\2", s)
    return s


def _simhash(text: str, bits: int = 64) -> int:
    """CJK 3-gram 指纹。"""
    grams = [text[i:i + 3] for i in range(len(text) - 2)]
    if not grams:
        grams = [text] if text else []
    v = [0] * bits
    for g in grams:
        h = hash(g)
        for i in range(bits):
            bit = (h >> i) & 1
            v[i] += 1 if bit else -1
    fingerprint = 0
    for i in range(bits):
        if v[i] > 0:
            fingerprint |= 1 << i
    return fingerprint


def _hamming(a: int, b: int) -> int:
    return bin(a ^ b).count("1")


@dataclass
class CleanResult:
    cleaned_text: str
    clean_score: float
    removed_flags: list[str] = field(default_factory=list)
    pii_masked: bool = False


class DocumentCleaner:
    def __init__(self, dup_hamming_threshold: int = 3):
        self.dup_hamming_threshold = dup_hamming_threshold

    def clean(self, text: str) -> CleanResult:
        if text is None:
            text = ""
        original_len = max(1, len(text))
        flags: list[str] = []

        # 1) 归一化
        text = unicodedata.normalize("NFKC", text)
        for zw in _ZERO_WIDTH:
            text = text.replace(zw, "")
        text = _CTRL_RE.sub("", text)
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        # CJK 换行规范化：句内单换行并空格，段间双换行保留
        lines = text.split("\n")
        norm_lines: list[str] = []
        for i, ln in enumerate(lines):
            stripped = ln.strip()
            if not stripped:
                norm_lines.append("")
                continue
            if norm_lines and norm_lines[-1] != "":
                prev = norm_lines[-1]
                # 上一行非空且非句尾标点 → 视为同一句内换行
                if prev and prev[-1] not in _CJK_END:
                    norm_lines[-1] = prev + " " + stripped
                    continue
            norm_lines.append(stripped)
        text = "\n".join(norm_lines)

        # 2) 页眉页脚 / 目录残骸（高频重复行 + 点线页码）
        lines = [ln for ln in text.split("\n")]
        # 统计行频（忽略空行）
        from collections import Counter
        freq = Counter(ln for ln in lines if ln)
        threshold = max(2, len({ln for ln in lines if ln}) * 0.8 / max(1, len(lines)) * len(lines))
        # 简化：出现 >=3 次且占非空行 >40% 视为 boilerplate
        non_empty = [ln for ln in lines if ln]
        if non_empty:
            boiler_threshold = max(3, int(len(non_empty) * 0.4))
            boiler = {ln for ln, c in freq.items() if c >= boiler_threshold}
        else:
            boiler = set()
        kept: list[str] = []
        removed_header_footer = False
        for ln in lines:
            if ln and (ln in boiler or _TOC_RE.match(ln)):
                removed_header_footer = True
                continue
            kept.append(ln)
        if removed_header_footer:
            flags.append("header_footer")
        text = "\n".join(kept)

        # 3) 近重复去重（段落级）
        paras = [p for p in text.split("\n\n") if p.strip()]
        seen_hashes: list[int] = []
        deduped: list[str] = []
        removed_dup = False
        for p in paras:
            h = _simhash(p)
            if any(_hamming(h, s) <= self.dup_hamming_threshold for s in seen_hashes):
                removed_dup = True
                continue
            seen_hashes.append(h)
            deduped.append(p)
        if removed_dup:
            flags.append("dup")
        text = "\n\n".join(deduped)

        # 4) PII 预扫
        if _RE_PHONE.search(text) or _RE_ID.search(text) or _RE_EMAIL.search(text):
            text = _mask_pii(text)
            flags.append("pii_masked")

        # 5) 质量评分
        cleaned_len = max(1, len(text))
        removed_ratio = 1 - cleaned_len / original_len
        control_ratio = 0.0  # 已在前段清除
        empty_ratio = text.count("\n\n") / max(1, len(paras))
        clean_score = max(0.0, min(1.0, 1.0 - removed_ratio * 0.4 - empty_ratio * 0.2 - control_ratio))
        # 若仍含大量连续空白或极短，降分
        if cleaned_len < 20:
            clean_score *= 0.5

        return CleanResult(
            cleaned_text=text.strip(),
            clean_score=round(clean_score, 3),
            removed_flags=flags,
            pii_masked="pii_masked" in flags,
        )
