"""把 kb-samples/ 下的 txt 样本转成 md/docx/xlsx/pdf（供多格式上传测试）。

复用 app.parsers.parse_markdown（解析 txt 的 markdown 结构）+ app.exporters.export（生成各格式）。
运行：uv run python scripts/make_samples.py
"""
from __future__ import annotations

import os
import sys

# 让脚本能 import app 包（doc-processor 根）
DOC_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, DOC_DIR)

from app.parsers import parse_markdown  # noqa: E402
from app.exporters import export  # noqa: E402

KB = os.path.join(os.path.dirname(DOC_DIR), "kb-samples")

# 每个 txt 需要生成的目标格式（覆盖 md/docx/xlsx/pdf 四类）
PLAN = {
    "售后政策说明.txt": ["md", "docx", "pdf"],
    "退款规则说明.txt": ["docx", "xlsx"],
    "物流配送说明.txt": ["docx"],
    "收货地址修改规则.txt": ["md"],
    "优惠券使用规则.txt": ["md", "xlsx"],
}


def main():
    for txt_name, formats in PLAN.items():
        path = os.path.join(KB, txt_name)
        with open(path, encoding="utf-8") as f:
            text = f.read()
        # txt 内容为 markdown 风格，用 parse_markdown 提取标题/列表结构
        blocks = parse_markdown(text.encode("utf-8"))
        base = txt_name[:-4]
        for fmt in formats:
            data, _ct, ext = export(fmt, text, blocks)
            out = os.path.join(KB, f"{base}.{ext}")
            with open(out, "wb") as f:
                f.write(data)
            print(f"✓ {os.path.basename(out)}  ({len(data)} bytes)")


if __name__ == "__main__":
    main()
