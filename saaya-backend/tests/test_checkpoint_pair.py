import asyncio
import json
from unittest.mock import MagicMock

from app import activity_adaption
from ml.evaluate_checkpoint_pair import FIXTURES, compare
from saaya_ml import example_from_row, rows


def test_checkpoint_pair_uses_identical_requests_and_validates_both_aliases(tmp_path, monkeypatch):
    monkeypatch.setenv("ACTIVITY_INFERENCE_URL", "https://private.invalid/v1/chat/completions")
    monkeypatch.setenv("ACTIVITY_INFERENCE_TOKEN", "synthetic-test-token")
    monkeypatch.setenv("ACTIVITY_INFERENCE_CHECKPOINT", "existing-model")
    targets = {}
    for _, line in rows(FIXTURES):
        case = example_from_row(line)
        targets[case.evidence.model_dump_json()] = case.target.model_dump_json()
    requests = []

    def post(url, *, json, **kwargs):
        requests.append(json.copy())
        content = targets[json["messages"][1]["content"]]
        envelope = {"model": json["model"], "choices": [{"message": {"content": content}}]}
        response = MagicMock()
        response.__enter__.return_value.status_code = 200
        response.__enter__.return_value.iter_content.return_value = [encode(envelope)]
        return response

    monkeypatch.setattr(activity_adaption.requests, "post", post)
    output = tmp_path / "paired"
    result = asyncio.run(compare("base", "adapter", output))

    assert result["complete"]
    assert result["base"]["cases"] == result["adapter"]["cases"] == 6
    assert all(r["temperature"] == 0 for r in requests)
    assert [dict(r, model="same") for r in requests[:6]] == [dict(r, model="same") for r in requests[6:]]
    assert json.loads((output / "summary.json").read_text(encoding="utf-8")) == result


def encode(envelope):
    return json.dumps(envelope).encode()
