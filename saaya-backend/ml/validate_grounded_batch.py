"""Validate the revised Invent export and merge locally without changing frozen inputs."""
import argparse
from collections import Counter
import hashlib
from pathlib import Path
import re

from ml.audit_previous_export import PROVENANCE, diagnose
from saaya_ml import (
    CONTACT_PATTERN, MIN_ROWS, ROOT, canonical, example_from_row, fingerprint,
    prepare, rows, verify_holdouts, write_jsonl,
)


def validate_batch(export, previous, frozen, output, dataset_id, expected_rows, legacy_labels=False,
                   extractive_interpretations=False):
    protected = (export, previous, frozen)
    if any(path.resolve().is_relative_to(output.resolve()) for path in protected):
        raise ValueError("Output must be separate from input files")
    checksums = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in protected}
    old = [example_from_row(line, PROVENANCE) for _, line in rows(previous)]
    heldout = [example_from_row(line) for _, line in rows(frozen)]
    verify_holdouts(old, heldout)
    seen_ids = {item.id for item in old + heldout}
    seen_text = {fingerprint(item) for item in old}
    allowed_labels = {"en", "hi", "hi-Hinglish"}
    if legacy_labels:
        allowed_labels.update({"English", "en-Hi", "en-HI"})
    decisions, added, renamed = [], [], []
    counts, languages, scripts, kinds = Counter(), Counter(), Counter(), Counter()
    for number, line in rows(export):
        try:
            item = example_from_row(line, "synthetic:" + dataset_id)
        except (TypeError, ValueError, KeyError) as error:
            counts["invalid schema or grounding"] += 1
            decisions.append({"line": number, "issues": diagnose(line, error)})
            continue
        evidence = fingerprint(item)
        contents = ([s.text for s in item.evidence.sources] +
                    [c.text for c in item.target.claims] + item.target.uncertainties)
        if item.language not in allowed_labels:
            issue = "invalid language label"
        elif any(CONTACT_PATTERN.search(text) or re.search(r"https?://|www\.", text, re.I) for text in contents):
            issue = "possible contact information or URL"
        elif extractive_interpretations and any(
                claim.category == "interpretation" and claim.text != claim.quote for claim in item.target.claims):
            issue = "non-extractive interpretation held for semantic review"
        elif not evidence or evidence in seen_text or (item.id in seen_ids and not legacy_labels):
            issue = "duplicate ID or normalized evidence"
        else:
            try:
                verify_holdouts([item], heldout)
            except ValueError:
                issue = "possible frozen evaluation leakage"
            else:
                issue = None
        if issue:
            counts[issue] += 1
            decisions.append({"line": number, "issues": [issue]})
            continue
        if item.id in seen_ids:
            new_id = f"saaya-{dataset_id}-export-line-{number}"
            if new_id in seen_ids:
                raise ValueError("Stable administrative row ID collision")
            renamed.append({"line": number, "originalRowId": item.id, "rowId": new_id})
            item = item.model_copy(update={"id": new_id})
        seen_ids.add(item.id)
        seen_text.add(evidence)
        added.append(item)
        languages[item.language] += 1
        kinds[item.evidence.kind] += 1
        if any(re.search(r"[\u0900-\u097f]", source.text) for source in item.evidence.sources):
            scripts[item.language] += 1
    total = len(added) + len(decisions)
    if total != expected_rows:
        raise ValueError(f"Expected {expected_rows} exported rows, received {total}; raw export retained")
    output.mkdir(parents=True, exist_ok=True)
    write_jsonl(output / "rejected.jsonl", decisions)
    write_jsonl(output / "renamed-row-ids.jsonl", renamed)
    merged = output / "merged-candidates.jsonl"
    write_jsonl(merged, (item.model_dump(mode="json") for item in old + added))
    prepared = prepare(merged, output / "prepared", train_only=True, heldout=frozen,
                       expected_rows=len(old) + len(added))
    if prepared["rejected"]:
        raise ValueError("Unexpected rejection during combined dataset validation")
    if any(hashlib.sha256(path.read_bytes()).hexdigest() != digest for path, digest in checksums.items()):
        raise ValueError("A protected input changed during validation")
    report = {"datasetId": dataset_id, "exportSha256": checksums[export],
              "previousSha256": checksums[previous], "frozenSha256": checksums[frozen],
              "exportRows": total, "existingRows": len(old),
              "frozenRows": len(heldout), "addedRows": len(added), "rejections": dict(counts),
              "renamedRowIds": len(renamed),
              "addedInterpretationClaims": sum(claim.category == "interpretation" for item in added for claim in item.target.claims),
              "addedLanguageLabels": dict(languages), "addedDevanagariSourceRowsByLabel": dict(scripts),
              "addedEvidenceKinds": dict(kinds), "combinedRows": len(old) + len(added),
              "minimumRows": MIN_ROWS, "meetsMinimum": len(old) + len(added) >= MIN_ROWS}
    (output / "validation-report.json").write_text(canonical(report) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset_id")
    parser.add_argument("export", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--previous", type=Path,
                        default=ROOT / ".local-adaption/primary-6efd95bd-final/prepared/train-sft.jsonl")
    parser.add_argument("--expected-rows", type=int, default=800)
    parser.add_argument("--legacy-labels", action="store_true",
                        help="Accept original Invent language aliases without changing row labels")
    parser.add_argument("--extractive-interpretations", action="store_true",
                        help="Hold new non-verbatim interpretations for semantic review")
    args = parser.parse_args()
    print(canonical(validate_batch(args.export, args.previous, ROOT / "ml/heldout.jsonl", args.output,
                                   args.dataset_id, args.expected_rows, legacy_labels=args.legacy_labels,
                                   extractive_interpretations=args.extractive_interpretations)))
