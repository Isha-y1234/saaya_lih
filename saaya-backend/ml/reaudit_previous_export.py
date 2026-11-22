"""Conservative second pass over the original SAAYA export, with row-level lineage."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

from ml.audit_previous_export import PAIR, PROVENANCE, parse_inner, repair
from saaya_ml import (
    CONTACT_PATTERN, ROOT, canonical, example_from_row, fingerprint, prepare, rows,
    verify_holdouts, write_jsonl,
)


def second_repair(line, conservative_cleanup=None):
    raw = json.loads(line)
    values, changes = [], []
    for field in PAIR:
        if conservative_cleanup is not None:
            value, cleanup = conservative_cleanup(field, raw[field])
            raw[field] = value
            changes.extend(cleanup)
        try:
            values.append(parse_inner(raw[field], repair=True))
        except json.JSONDecodeError as error:
            if not error.msg.startswith("Invalid control character"):
                raise
            # Decode the same literal character, then escape it on JSON serialization.
            values.append(json.loads(error.doc, strict=False))
            changes.append("escape literal JSON control character")
    prompt, report = values
    if not isinstance(prompt, dict) or not isinstance(report, dict):
        raise ValueError("Expected prompt and report objects")
    if {"claims", "uncertainties", "abstain"} <= report.keys():
        metadata = report.keys() & {"$defs", "additionalProperties", "properties", "required", "title", "type"}
        if metadata and (isinstance(report.get("properties"), dict) or isinstance(report.get("$defs"), dict)):
            report = {key: value for key, value in report.items() if key not in metadata}
            changes.append("remove schema annotations surrounding intact report")
    claims = report.get("claims")
    if isinstance(claims, list) and all(isinstance(c, dict) for c in claims):
        known = [i for i, c in enumerate(claims) if type(c.get("offsetMs")) is int]
        ordered = sorted((claims[i] for i in known), key=lambda c: c["offsetMs"])
        if ordered != [claims[i] for i in known]:
            for index, claim in zip(known, ordered):
                claims[index] = claim
            changes.append("sort known claims by existing source offset")
    encoded = canonical(dict(zip(PAIR, (canonical(prompt), canonical(report)))))
    item, first_changes = repair(encoded)
    return item, sorted(set(changes + first_changes))


def reaudit(source, prior, frozen, output):
    existing = prior / "prepared"
    protected = (source, existing / "train.jsonl", existing / "train-sft.jsonl",
                 prior / "row-decisions.jsonl", frozen)
    if any(path.resolve().is_relative_to(output.resolve()) for path in protected):
        raise ValueError("Output must be separate from protected inputs")
    sha = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in protected}
    journal = {entry["line"]: entry for _, line in rows(protected[3]) if (entry := json.loads(line))}
    old = [example_from_row(line) for _, line in rows(protected[1])]
    holdouts = [example_from_row(line) for _, line in rows(frozen)]
    verify_holdouts(old, holdouts)
    evidence = {fingerprint(item) for item in old}
    ids = {item.id for item in old}
    retained = {item.id: item for item in old}
    frozen_ids = {item.id for item in holdouts}
    additions, audit = [], []
    outcomes, repairs = Counter(), Counter()
    for number, line in rows(source):
        previous = journal.get(number, {"status": "initial-pass"})
        earlier = previous["status"]
        entry = {"line": number, "previous": earlier}
        changes = []
        try:
            if earlier == "rejected":
                entry["previousIssues"] = previous["initialCategories"]
                item, changes = second_repair(line)
            elif earlier == "repaired":
                item, _ = repair(line)
            else:
                item = example_from_row(line, PROVENANCE)
        except (ValueError, TypeError, KeyError) as error:
            issue = (error.msg if isinstance(error, json.JSONDecodeError)
                     else str(error) if type(error) is ValueError else type(error).__name__)
            entry.update(outcome="invalid", reason=issue)
        else:
            content = fingerprint(item)
            all_text = ([s.text for s in item.evidence.sources] + [c.text for c in item.target.claims]
                        + item.target.uncertainties)
            if retained.get(item.id) == item:
                retained.pop(item.id)
                entry.update(outcome="retained baseline")
            elif any(CONTACT_PATTERN.search(value) or re.search(r"https?://|www\.", value, re.I) for value in all_text):
                entry.update(outcome="possible contact details")
            elif not content or content in evidence:
                entry.update(outcome="empty or duplicate normalized evidence")
            else:
                try:
                    verify_holdouts([item], holdouts)
                except ValueError:
                    entry.update(outcome="frozen scenario overlap")
                else:
                    entry["originalRowId"] = item.id
                    if item.id in ids or item.id in frozen_ids:
                        # This is a dataset record identifier, never an evidence source ID.
                        new_id = f"saaya-6efd95bd-export-line-{number}"
                        if new_id in ids or new_id in frozen_ids:
                            raise ValueError("Stable administrative ID collision")
                        item = item.model_copy(update={"id": new_id})
                        changes.append("disambiguate dataset row ID using original line")
                    evidence.add(content)
                    ids.add(item.id)
                    repairs.update(changes)
                    additions.append(item)
                    entry.update(outcome="added", rowId=item.id, repairs=changes)
        outcomes[(earlier, entry["outcome"])] += 1
        audit.append(entry)
    if len(audit) != 1249 or len(old) != 383 or len(holdouts) != 6 or retained:
        raise ValueError("Unexpected original, training, or frozen row count")
    output.mkdir(parents=True, exist_ok=True)
    merged = output / "candidates.jsonl"
    write_jsonl(merged, (item.model_dump(mode="json") for item in old + additions))
    manifest = prepare(merged, output / "prepared", train_only=True, heldout=frozen,
                       expected_rows=len(old) + len(additions))
    if manifest["rejected"] or manifest["counts"]["train"] != len(old) + len(additions):
        raise ValueError("Combined dataset failed strict revalidation")
    if any(hashlib.sha256(path.read_bytes()).hexdigest() != digest for path, digest in sha.items()):
        raise ValueError("Protected input modified")
    write_jsonl(output / "row-audit.jsonl", audit)
    report = {"originalRows": len(audit), "originalSha256": sha[source], "retainedRows": len(old),
              "addedRows": len(additions), "combinedRows": len(old) + len(additions),
              "frozenRows": len(holdouts), "repairs": dict(repairs),
              "outcomes": {f"{earlier}: {result}": count for (earlier, result), count in outcomes.items()},
              "remainingInvalid": dict(Counter(row["reason"] for row in audit if row["outcome"] == "invalid")),
              "languages": manifest["languages"]["train"]}
    (output / "summary.json").write_text(canonical(report) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(canonical(reaudit(args.source, ROOT / ".local-adaption/primary-6efd95bd",
                            ROOT / "ml/heldout.jsonl", args.output)))
