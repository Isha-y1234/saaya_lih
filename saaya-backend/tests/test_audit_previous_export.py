import json
from pathlib import Path

import pytest

from ml.audit_previous_export import audit, repair
from ml.finalize_previous_export import cleanup
from ml.reaudit_previous_export import second_repair
from saaya_ml import example_from_row, rows, verify_holdouts

FIXTURES = Path(__file__).resolve().parents[1] / "ml" / "fixtures.jsonl"


def exported():
    example = next(example_from_row(line) for _, line in rows(FIXTURES) if "fixture-verbal" in line)
    prompt = example.model_dump(mode="json", exclude={"target", "provenance", "forbidden"})
    target = example.target.model_dump()
    return example, prompt, target


def test_repairs_fence_and_attribution_without_changing_evidence():
    original, prompt, target = exported()
    target["claims"][0]["category"] = "observed"
    line = json.dumps({"enhanced_prompt": json.dumps(prompt),
                       "enhanced_completion": "```json\n" + json.dumps(target) + "\n```"})
    fixed, changes = repair(line)
    assert fixed.evidence == original.evidence
    assert fixed.target == original.target
    assert set(changes) == {"attribute exact statement as reported", "unwrap JSON fence"}


@pytest.mark.parametrize("unsafe", [{"quote": "Fabricated quote"}, {"offsetMs": 999}, {"sourceId": "source-09"}])
def test_repairs_never_create_missing_grounding(unsafe):
    _, prompt, target = exported()
    target["claims"][0].update(unsafe)
    with pytest.raises(ValueError):
        repair(json.dumps({"enhanced_prompt": json.dumps(prompt), "enhanced_completion": json.dumps(target)}))
    with pytest.raises(ValueError):
        second_repair(json.dumps({"enhanced_prompt": json.dumps(prompt), "enhanced_completion": json.dumps(target)}))


def test_second_repair_only_removes_schema_metadata_and_reorders_known_claims():
    _, prompt, target = exported()
    prompt["evidence"]["sources"] = [
        {"id": "source-01", "kind": "transcript", "text": "First.", "offsetMs": 10},
        {"id": "source-02", "kind": "transcript", "text": "Second.", "offsetMs": 20},
    ]
    target["claims"] = [
        {"sourceId": source["id"], "category": "reported", "text": source["text"],
         "quote": source["text"], "offsetMs": source["offsetMs"]}
        for source in reversed(prompt["evidence"]["sources"])
    ]
    target["properties"] = {"claims": {"type": "array"}}
    line = json.dumps({"enhanced_prompt": json.dumps(prompt), "enhanced_completion": json.dumps(target)})
    fixed, changes = second_repair(line)
    assert [claim.offsetMs for claim in fixed.target.claims] == [10, 20]
    assert [claim.quote for claim in fixed.target.claims] == ["First.", "Second."]
    assert set(changes) == {"remove schema annotations surrounding intact report",
                            "sort known claims by existing source offset"}


def test_final_cleanup_preserves_quote_and_rejects_changed_offset():
    _, prompt, target = exported()
    claim = target["claims"][0]
    quote = claim["quote"]
    claim["text"] = f"“{quote}”"
    line = json.dumps({"enhanced_prompt": json.dumps(prompt), "enhanced_completion": json.dumps(target)})
    fixed, changes = second_repair(line, conservative_cleanup=cleanup)
    assert fixed.target.claims[0].text == fixed.target.claims[0].quote == quote
    assert "normalize presentation of exact extract" in changes
    claim["offsetMs"] = "999"
    line = json.dumps({"enhanced_prompt": json.dumps(prompt), "enhanced_completion": json.dumps(target)})
    with pytest.raises(ValueError, match="Unsupported source, quote or timestamp"):
        second_repair(line, conservative_cleanup=cleanup)


def test_audit_keeps_original_and_frozen_set_separate(tmp_path):
    _, prompt, target = exported()
    source, output = tmp_path / "original.jsonl", tmp_path / "audit"
    line = json.dumps({"enhanced_prompt": json.dumps(prompt), "enhanced_completion": json.dumps(target)})
    raw = (line + "\n" + line + "\n").encode()
    source.write_bytes(raw)
    result = audit(source, output)
    assert source.read_bytes() == raw
    assert result["strictInitial"] == 2 and result["duplicateRows"] == 1
    assert result["usableTrainingRows"] == 1 and not result["meetsMinimum"]
    retained = [example_from_row(value) for _, value in rows(output / "prepared" / "train.jsonl")]
    frozen = [example_from_row(value) for _, value in rows(FIXTURES.with_name("heldout.jsonl"))]
    verify_holdouts(retained, frozen)
    assert not (output / "prepared" / "heldout.jsonl").stat().st_size
