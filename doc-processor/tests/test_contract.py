"""doc-processor 接口契约测试（与 Java 侧 EvalRunner 解耦，可独立跑）。

验证 §10.2 契约：统一信封 {ok,data,error}、clean/parse 真实跑通、rerank 模型不可用时降级。
"""
from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

NOISY_TEXT = (
    "\ufeff【售后政策 V3】\n"
    "第一章 七天无理由退货.....12\n"
    "客户购买商品后，自签收之日起七日内，\n"
    "在商品完好不影响二次销售的前提下，可申请无理由退货。\n"
    "第一章 七天无理由退货.....12\n"
    "客服电话：13812345678，邮箱：service@example.com\n"
)


def test_health():
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["data"]["status"] == "UP"


def test_clean_basic():
    r = client.post("/api/v1/clean", json={"text": NOISY_TEXT})
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert set(body.keys()) >= {"ok", "data", "error"}
    data = body["data"]
    assert "七天无理由退货" in data["cleaned_text"]
    assert 0.0 <= data["clean_score"] <= 1.0
    assert isinstance(data["removed_flags"], list)


def test_clean_pii_masked():
    r = client.post("/api/v1/clean", json={"text": "联系 13812345678 或 service@example.com"})
    body = r.json()
    data = body["data"]
    assert "138****5678" in data["cleaned_text"]
    assert "s***@example.com" in data["cleaned_text"]
    assert "pii_masked" in data["removed_flags"]


def test_parse_text_fallback():
    content = "七天无理由退货：签收七日内可退。\n\n质量问题：支持换货。".encode("utf-8")
    r = client.post(
        "/api/v1/parse",
        files={"file": ("policy.txt", content, "text/plain")},
        data={"options": '{"ocr": false, "clean": true}'},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    data = body["data"]
    assert len(data["blocks"]) >= 1
    assert all(b["block_type"] for b in data["blocks"])
    assert 0.0 <= data["clean_score"] <= 1.0
    assert "text_fallback" in data["flags"]


def test_rerank_degraded_without_model():
    r = client.post(
        "/api/v1/rerank",
        json={
            "query": "七天无理由退货",
            "documents": [
                {"id": "1", "text": "签收七日内可无理由退货"},
                {"id": "2", "text": "物流查询方式"},
            ],
            "top_n": 2,
        },
    )
    assert r.status_code == 200
    body = r.json()
    # 未装模型时：ok=false 且带 error（Java 据此降级）
    assert body["ok"] is False
    assert body["error"]
