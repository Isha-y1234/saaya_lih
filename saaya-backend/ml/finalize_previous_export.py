"""Finalize the original SAAYA export with formatting-only repairs and provenance."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import unicodedata

from pydantic import ValidationError

from ml.audit_previous_export import PAIR, parse_inner
from ml.reaudit_previous_export import second_repair
from saaya_ml import (
    CONTACT_PATTERN, MIN_ROWS, ROOT, canonical, example_from_row, fingerprint, prepare,
    rows, verify_holdouts, write_jsonl,
)


def presentation(text):
    text = " ".join(unicodedata.normalize("NFC", text).split())
    if len(text) >= 2 and (text[0], text[-1]) in {('"', '"'), ("'", "'"), ("“", "”"), ("‘", "’")}:
        text = text[1:-1].strip()
    return text


def cleanup(field, raw):
    changes = []
    try:
        value = parse_inner(raw, repair=True)
    except json.JSONDecodeError:
        # Match complete JSON strings before separators, leaving quoted evidence untouched.
        fixed = re.sub(r'"(?:[^"\\]|\\.)*"|,\s*(?=[}\]])', lambda match: (
            match[0] if match[0].startswith('"') else match[0][1:]), raw)
        if fixed == raw:
            return raw, changes
        value = parse_inner(fixed, repair=True)
        raw = fixed
        changes.append("remove trailing JSON delimiter outside strings")
    if field != PAIR[1] or not isinstance(value, dict) or not isinstance(value.get("claims"), list):
        return raw, changes
    for claim in value["claims"]:
        if not isinstance(claim, dict):
            continue
        text, quote = claim.get("text"), claim.get("quote")
        if (claim.get("category") in {"observed", "reported"} and isinstance(text, str)
                and isinstance(quote, str) and text != quote and presentation(text) == presentation(quote)):
            claim["text"] = quote
            changes.append("normalize presentation of exact extract")
        offset = claim.get("offsetMs")
        if isinstance(offset, str) and re.fullmatch(r"0|[1-9][0-9]{0,4}", offset):
            claim["offsetMs"] = int(offset)
            changes.append("decode decimal offset without changing its value")
    return (canonical(value) if changes else raw), changes


def issues(error):
    if isinstance(error, ValidationError):
        return sorted({f"{'.'.join(str(p) for p in entry['loc'] if not isinstance(p, int))}:{entry['type']}"
                       for entry in error.errors()})
    if isinstance(error, json.JSONDecodeError):
        return ["JSON: " + error.msg]
    return [str(error) if type(error) is ValueError else type(error).__name__]


def finalize(source, prior, frozen, output):
    protected = (source, prior / "prepared/train.jsonl", prior / "prepared/train-sft.jsonl",
                 prior / "row-audit.jsonl", frozen)
    if any(path.resolve().is_relative_to(output.resolve()) for path in protected):
        raise ValueError("Output must be separate from inputs")
    checksums = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in protected}
    old = [example_from_row(line) for _, line in rows(protected[1])]
    holdouts = [example_from_row(line) for _, line in rows(frozen)]
    journal = {item["line"]: item for _, line in rows(protected[3]) if (item := json.loads(line))}
    rejected = {number: item for number, item in journal.items() if item["outcome"] == "invalid"}
    occupied = {item.id for item in old + holdouts}
    seen = {fingerprint(item) for item in old}
    additions, decisions = [], []
    for number, line in rows(source):
        if number not in rejected:
            continue
        decision = {"line": number, "previousFailure": rejected[number]["reason"]}
        try:
            item, changes = second_repair(line, conservative_cleanup=cleanup)
            if not changes:
                raise ValueError("No supported deterministic repair")
            contents = [s.text for s in item.evidence.sources] + [c.text for c in item.target.claims] + item.target.uncertainties
            if any(CONTACT_PATTERN.search(text) or re.search(r"https?://|www\.", text, re.I) for text in contents):
                raise ValueError("Possible contact details or URL")
            content = fingerprint(item)
            if not content or content in seen:
                raise ValueError("Empty or duplicate normalized evidence")
            verify_holdouts([item], holdouts)
        except (ValueError, TypeError, KeyError) as error:
            decision.update(outcome="rejected", issues=issues(error))
        else:
            decision["originalRowId"] = item.id
            if item.id in occupied:
                new_id = f"saaya-6efd95bd-export-line-{number}"
                if new_id in occupied:
                    raise ValueError("Stable administrative ID collision")
                item = item.model_copy(update={"id": new_id})
                changes.append("disambiguate dataset row ID using original line")
            seen.add(content)
            occupied.add(item.id)
            additions.append(item)
            decision.update(outcome="recovered", rowId=item.id, repairs=changes)
        decisions.append(decision)
    if len(decisions) != len(rejected) or len(journal) != 1249 or len(old) != 690 or len(holdouts) != 6:
        raise ValueError("Unexpected input row counts")
    output.mkdir(parents=True, exist_ok=True)
    merged = output / "candidates.jsonl"
    final = old + additions
    write_jsonl(merged, (item.model_dump(mode="json") for item in final))
    result = prepare(merged, output / "prepared", train_only=True, heldout=frozen, expected_rows=len(final))
    if result["rejected"] or result["counts"]["train"] != len(final):
        raise ValueError("Combined dataset failed strict revalidation")
    if any(hashlib.sha256(path.read_bytes()).hexdigest() != digest for path, digest in checksums.items()):
        raise ValueError("Protected input changed")
    write_jsonl(output / "recovery-audit.jsonl", decisions)
    report = {
        "originalSha256": checksums[source], "baselineRows": len(old), "reviewedRejected": len(decisions),
        "recoveredRows": len(additions), "finalRows": len(final), "minimumRows": MIN_ROWS,
        "meetsMinimum": len(final) >= MIN_ROWS, "frozenRows": len(holdouts),
        "remainingIssues": dict(Counter(issue for decision in decisions for issue in decision.get("issues", []))),
        "repairs": dict(Counter(change for decision in decisions for change in decision.get("repairs", []))),
        "languages": result["languages"]["train"], "mediaKinds": dict(Counter(item.evidence.kind for item in final)),
        "scenarioGroups": len({item.group for item in final}),
        "abstentions": sum(item.target.abstain for item in final),
        "devanagariSourceRows": sum(any(re.search(r"[\u0900-\u097f]", source.text)
                                        for source in item.evidence.sources) for item in final),
    }
    (output / "summary.json").write_text(canonical(report) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(canonical(finalize(args.source, ROOT / ".local-adaption/primary-6efd95bd-reaudit",
                             ROOT / "ml/heldout.jsonl", args.output)))
