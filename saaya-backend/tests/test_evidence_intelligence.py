import asyncio
import io
import json
from pathlib import Path
import tarfile
from types import SimpleNamespace

from adaption import APIStatusError
import httpx
import pytest

from app import evidence_intelligence as ai
import saaya_ml as ml

FIXTURE = Path(__file__).resolve().parents[1] / "ml" / "fixtures.jsonl"


def examples():
    return [ml.example_from_row(row) for _, row in ml.rows(FIXTURE)]


def test_dataset_mapping_and_reproducible_split(tmp_path):
    first = ml.prepare(FIXTURE, tmp_path / "first")
    second = ml.prepare(FIXTURE, tmp_path / "second")
    assert first == second
    assert first["counts"] == {"train": 8, "validation": 3, "heldout": 3}
    assert not first["rejected"]
    row = next(ml.rows(tmp_path / "first" / "train-sft.jsonl"))[1]
    assert ml.example_from_row(row, "synthetic:fixture").target.uncertainties
    exported = json.loads(row)
    vendor_row = json.dumps({"enhanced_prompt": exported["prompt"], "enhanced_completion": exported["completion"]})
    assert ml.example_from_row(vendor_row, "synthetic:fixture") == ml.example_from_row(row, "synthetic:fixture")
    fixture = examples()[0]
    with pytest.raises(ValueError, match="leakage"):
        ml.verify_holdouts([fixture], [fixture.model_copy(update={"group": "different"})])
    request = ml.invent_request(1000)
    assert "Hindi-English code-switching" in request["dataset_prompt"]
    assert request["idempotency_key"] == ml.invent_request(1000)["idempotency_key"]
    assert ml.adapt_request(1000)["column_mapping"] == {"prompt": "prompt", "completion": "completion"}
    assert ml.training_request("fixture-dataset", 1000)["model"] == ml.MODEL
    with pytest.raises(ValueError):
        ml.training_request("fixture-dataset", 999)
    heldout = [ml.example_from_row(row) for _, row in ml.rows(FIXTURE.with_name("heldout.jsonl"))]
    ml.verify_holdouts(examples(), heldout)
    assert any(row.forbidden and row.language == "hi-Hinglish" for row in heldout)


def test_online_estimate_commands_never_launch_jobs(monkeypatch):
    import adaption

    def invent(**request):
        assert request.pop("estimate") is True
        assert request == ml.invent_request(1000)
        return SimpleNamespace(estimated_credits=100, available_credits=1050)

    def adapt(identity, **request):
        assert identity == "synthetic-fixture"
        assert request.pop("estimate") is True
        assert request == ml.adapt_request(1000)
        return SimpleNamespace(estimated_credits_consumed=4)

    class SDK:
        datasets = SimpleNamespace(invent=invent, adapt=adapt)
        def __init__(self, **kwargs): pass
        def __enter__(self): return self
        def __exit__(self, *_): pass

    monkeypatch.setattr(adaption, "Adaption", SDK)
    args = SimpleNamespace(command="invent", online=True, approve_spend=False, rows=1000, dataset="synthetic-fixture")
    assert ml.execute(args) == {"estimatedCredits": 100}
    args.command = "adapt"
    assert ml.execute(args) == {"estimatedCredits": 4}


def test_recover_waits_for_success_then_downloads_and_splits(tmp_path):
    calls = []
    pending = SimpleNamespace(status="running", row_count=None, error_data=None)
    complete = SimpleNamespace(status="succeeded", row_count=len(examples()), error_data=None)

    def wait(identity, **limits):
        calls.append(("wait", identity, limits["timeout"]))
        return complete

    def download(identity, **options):
        assert options == {"file_format": "jsonl"}
        calls.append(("download", identity))
        return SimpleNamespace(write_to_file=lambda dest: dest.write_bytes(FIXTURE.read_bytes()))

    api = SimpleNamespace(datasets=SimpleNamespace(get_status=lambda _: pending, wait_for_completion=wait, download=download))
    result = ml.recover(api, "existing", tmp_path, 60, complete.row_count, FIXTURE.with_name("heldout.jsonl"))
    assert calls == [("wait", "existing", 60), ("download", "existing")]
    assert result["validation"]["inputRows"] == complete.row_count
    assert result["validation"]["counts"] == {"train": 8, "validation": 3, "heldout": 3}
    assert any("hi-Hinglish" in counts for counts in result["validation"]["languages"].values())


def test_recover_running_only_writes_status(tmp_path):
    pending = SimpleNamespace(status="running", row_count=None, error_data=None)
    api = SimpleNamespace(datasets=SimpleNamespace(get_status=lambda _: pending))
    result = ml.recover(api, "existing", tmp_path, 0, 1250)
    assert result["status"] == "running" and result["providerRows"] is None
    assert not (tmp_path / "generated.jsonl").exists()


def test_prepare_counts_duplicates_and_rejects_contact_patterns(tmp_path):
    fixture = examples()[0]
    flagged = fixture.model_copy(update={"id": "flagged", "evidence": fixture.evidence.model_copy(update={
        "sources": [fixture.evidence.sources[0].model_copy(update={"text": fixture.evidence.sources[0].text + " fixture@example.invalid"})]
    })})
    path = tmp_path / "input.jsonl"
    ml.write_jsonl(path, [fixture.model_dump(mode="json")] * 2 + [flagged.model_dump(mode="json")])
    with pytest.raises(ValueError, match="Expected 1250 export rows, read 3"):
        ml.prepare(path, tmp_path / "incorrect", expected_rows=1250)
    result = ml.prepare(path, tmp_path / "validated", expected_rows=3)
    assert [entry["reason"] for entry in result["rejected"]] == [
        "duplicate", "unverified provenance or possible contact information"]


def test_invent_submission_checks_balance_and_returns_real_sdk_id(monkeypatch):
    import adaption

    balance = 50
    submissions = []

    def invent(**request):
        if request.pop("estimate", False):
            return SimpleNamespace(estimated_credits=100, available_credits=balance)
        submissions.append(request)
        return SimpleNamespace(id="actual-invent-id")

    class SDK:
        datasets = SimpleNamespace(invent=invent)
        def __init__(self, **kwargs): pass
        def __enter__(self): return self
        def __exit__(self, *_): pass

    monkeypatch.setattr(adaption, "Adaption", SDK)
    args = SimpleNamespace(command="invent", online=True, approve_spend=True, rows=1000, max_credits=100)
    with pytest.raises(ValueError, match="Insufficient existing"):
        ml.execute(args)
    assert not submissions
    balance = 1050
    assert ml.execute(args)["datasetId"] == "actual-invent-id"
    assert len(submissions) == 1


def test_checkpoint_archive_checks_adapter_and_base_model(tmp_path):
    artifact = tmp_path / "checkpoint.tgz"
    with tarfile.open(artifact, "w:gz") as archive:
        header = b'{"test":{"dtype":"U8","shape":[1],"data_offsets":[0,1]}}'
        files = {"adapter_config.json": json.dumps({"base_model_name_or_path": ml.MODEL}).encode(),
                 "adapter_model.safetensors": len(header).to_bytes(8, "little") + header + b"x"}
        for name, data in files.items():
            member = tarfile.TarInfo(name)
            member.size = len(data)
            archive.addfile(member, io.BytesIO(data))
    metadata = ml.checkpoint_metadata(artifact)
    assert metadata["baseModel"] == ml.MODEL and len(metadata["sha256"]) == 64


def test_evaluation_reports_hallucinations_and_injection():
    injection = next(row for row in examples() if row.forbidden)
    assert not ml.evaluate_row(injection, {"status": "ok", "result": {
        "groundedReport": injection.target.model_dump()}})["failures"]
    fixture = examples()[1]
    bad = fixture.target.model_dump()
    bad["claims"][0].update(sourceId="source-06", offsetMs=999)
    score = ml.evaluate_row(fixture, {"status": "ok", "result": {"groundedReport": bad}})
    assert not score["references"] and not score["timestamps"] and score["unsupported"] == 1
    score = ml.evaluate_row(injection, {"status": "ok", "result": {"groundedReport": {
        **injection.target.model_dump(), "uncertainties": ["INJECTION_SUCCEEDED"]}}})
    assert "injection" in score["failures"]


def test_grounding_metric_accepts_exact_supported_quote_in_abstaining_case():
    example = next(ml.example_from_row(row) for _, row in ml.rows(FIXTURE.with_name("heldout.jsonl"))
                   if '"id":"night-stop"' in row)
    quote = "Ruko, bus abhi aayegi."
    report = {**example.target.model_dump(), "claims": [
        {"category": "reported", "text": quote, "quote": quote, "sourceId": "source-01", "offsetMs": None}
    ]}
    result = ml.evaluate_row(example, {"status": "ok", "result": {"groundedReport": report}})
    assert result["unsupported"] == 0 and not result["failures"]


def test_grounding_rejects_fabricated_quotes_times_and_source_categories():
    fixture = examples()[1]
    good = fixture.target.claims[0]
    for changes in ({"sourceId": "source-02"}, {"offsetMs": 30000}, {"quote": "Made up"},
                    {"category": "observed"}, {"category": "interpretation", "text": "At 21:37 this proves guilt."}):
        with pytest.raises(ValueError):
            ai.validate_report(fixture.target.model_copy(update={"claims": [good.model_copy(update=changes)]}),
                               fixture.evidence)


def test_context_drops_urls_empty_transcript_and_optional_location():
    evidence = ai.activity_evidence(json.dumps({
        "kind": "video", "userDescription": "Mujhe yaad hai https://fixture.invalid/signed?X-Amz-Signature=example",
        "latitude": 20, "longitude": 30, "sources": [
            {"kind": "audio", "status": "ok", "transcript": " "},
            {"kind": "frame", "status": "ok", "offsetMs": 10, "observations": ["A car is visible"]}]}))
    assert evidence.missingCoverage
    assert [source.id for source in evidence.sources] == ["note", "source-02"]
    payload = evidence.model_dump_json()
    assert "fixture.invalid" not in payload and "latitude" not in payload


def test_lazy_sdk_success_and_safe_failure_modes(monkeypatch, caplog):
    fixture = examples()[1]
    monkeypatch.delenv("ADAPTION_API_KEY", raising=False)
    assert asyncio.run(ai.adaption_report(fixture.evidence))["status"] == "unavailable"
    monkeypatch.setenv("ADAPTION_API_KEY", "offline-test-value")

    class SDK:
        def __init__(self, **config):
            assert config["max_retries"] == 0
            self.responses = SimpleNamespace(create=self.create)

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_):
            return None

        async def create(self, **request):
            assert request["tool_choice"] == "none" and not request["store"]
            assert "Hinglish" in request["instructions"]
            if isinstance(result, BaseException):
                raise result
            return SimpleNamespace(status="completed", output_text=result)

    monkeypatch.setattr(ai, "AsyncAdaption", SDK)
    result = "```json\n" + fixture.target.model_dump_json() + "\n```"
    good = asyncio.run(ai.adaption_report(fixture.evidence))
    assert good["status"] == "ok" and good["result"]["provider"] == "Adaption pretrained"
    result = "malformed private marker"
    assert asyncio.run(ai.adaption_report(fixture.evidence))["status"] == "unavailable"
    result = asyncio.TimeoutError("private marker")
    assert asyncio.run(ai.adaption_report(fixture.evidence))["status"] == "uncertain"
    response = httpx.Response(429, request=httpx.Request("POST", "https://fixture.invalid"))
    result = APIStatusError("private marker", response=response, body={})
    assert asyncio.run(ai.adaption_report(fixture.evidence))["status"] == "quota"
    assert "private marker" not in caplog.text


def test_real_sdk_serializes_documented_responses_contract_offline(monkeypatch):
    from adaption import AsyncAdaption

    fixture = examples()[1]
    monkeypatch.setenv("ADAPTION_API_KEY", "offline-transport-test")
    monkeypatch.setenv("ADAPTION_MODEL", "gemma-4-26b-a4b-it")

    def request(req):
        assert req.url.path.endswith("/responses")
        body = json.loads(req.content)
        assert body["model"] == "gemma-4-26b-a4b-it" and body["max_output_tokens"] == 1600
        assert body["tool_choice"] == "none" and body["store"] is False
        assert json.loads(body["input"])["sources"][0]["kind"] == "transcript"
        return httpx.Response(200, json={"id": "fixture", "object": "response", "status": "completed",
                                         "model": body["model"], "output": [],
                                         "output_text": fixture.target.model_dump_json()})

    monkeypatch.setattr(ai, "AsyncAdaption", lambda **kwargs: AsyncAdaption(
        base_url="https://fixture.invalid", http_client=httpx.AsyncClient(transport=httpx.MockTransport(request)), **kwargs))
    assert asyncio.run(ai.adaption_report(fixture.evidence))["status"] == "ok"
