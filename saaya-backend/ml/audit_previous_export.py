"""Offline forensic preparation of an existing fictional Adaption export."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

from pydantic import ValidationError

from saaya_ml import (CONTACT_PATTERN, MIN_ROWS, ROOT, canonical, example_from_row,
                      fingerprint, prepare, rows, verify_holdouts, write_jsonl)

PROVENANCE = "synthetic:6efd95bd-33a0-41b9-8f3e-71dfbebdda32"
PAIR = ("enhanced_prompt", "enhanced_completion")


def parse_inner(value, repair=False):
    if not isinstance(value, str):
        raise ValueError("Expected a JSON text field")
    value = value.strip()
    if repair:
        fence = re.fullmatch(r"```(?:json)?[ \t]*\r?\n([\s\S]*?)\r?\n```", value, flags=re.IGNORECASE)
        if fence:
            return json.loads(fence.group(1))
    return json.loads(value)


def diagnose(line, error):
    if isinstance(error, ValidationError):
        return sorted({f"schema:{'.'.join(str(part) for part in e['loc'] if not isinstance(part, int))}:{e['type']}"
                       for e in error.errors()})
    if isinstance(error, json.JSONDecodeError):
        result = []
        try:
            row = json.loads(line)
        except ValueError:
            return ["invalid JSON:outer row"]
        for field in PAIR:
            try:
                parse_inner(row.get(field))
            except ValueError:
                result.append(f"invalid JSON:{field}")
        return result or ["invalid JSON:outer row"]
    if str(error) == "Unsupported source, quote or timestamp":
        row = json.loads(line)
        source_map = {item["id"]: item for item in parse_inner(row[PAIR[0]])["evidence"]["sources"]}
        findings = set()
        for claim in parse_inner(row[PAIR[1]])["claims"]:
            source = source_map.get(claim["sourceId"])
            if source is None:
                findings.add("unknown source reference")
            else:
                if claim["quote"].strip() not in source["text"]:
                    findings.add("quote absent from source")
                if claim["offsetMs"] != source.get("offsetMs"):
                    findings.add("source timestamp mismatch")
        return sorted(findings) or [str(error)]
    return [str(error)]


def repair(line):
    row = json.loads(line)
    prompt, target = (parse_inner(row[name], repair=True) for name in PAIR)
    if not isinstance(prompt, dict) or not isinstance(target, dict):
        raise ValueError("Prompt and target must be JSON objects")
    changes = []
    if any(row[name].lstrip().startswith("```") for name in PAIR):
        changes.append("unwrap JSON fence")
    # Separate embedded answer/schema fields from model inputs. All evidence fields remain untouched.
    for field in ("completion", "report", "completion_schema_required"):
        if field in prompt:
            prompt.pop(field)
            changes.append("remove embedded output or schema field")
    evidence = prompt.get("evidence")
    claims = target.get("claims")
    if isinstance(evidence, dict) and isinstance(claims, list):
        sources = {s.get("id"): s for s in evidence.get("sources", []) if isinstance(s, dict)}
        for claim in claims:
            if not isinstance(claim, dict):
                continue
            source = sources.get(claim.get("sourceId"), {})
            quote = claim.get("quote")
            if not isinstance(quote, str) or not quote.strip() or quote.strip() not in str(source.get("text", "")):
                continue
            if claim.get("category") == "observed" and source.get("kind") in ("note", "transcript"):
                claim["category"] = "reported"
                changes.append("attribute exact statement as reported")
            text = claim.get("text")
            if (claim.get("category") in ("reported", "observed") and isinstance(text, str)
                    and text.strip() != quote.strip() and " ".join(text.split()) == " ".join(quote.split())):
                claim["text"] = quote
                changes.append("normalize quote whitespace")
    repaired = canonical({"prompt": canonical(prompt), "completion": canonical(target)})
    return example_from_row(repaired, PROVENANCE), sorted(set(changes))


def audit(source, output):
    if source.resolve().is_relative_to(output.resolve()):
        raise ValueError("Output cannot overwrite the original export")
    original_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    frozen_path = ROOT / "ml" / "heldout.jsonl"
    frozen = [example_from_row(line) for _, line in rows(frozen_path)]
    initial, recovered, decisions = [], [], []
    categories, repair_types, rejected, primary = Counter(), Counter(), Counter(), Counter()
    for number, line in rows(source):
        try:
            initial.append((number, example_from_row(line, PROVENANCE)))
        except (ValueError, TypeError, KeyError) as error:
            primary[type(error).__name__] += 1
            failures = diagnose(line, error)
            categories.update(failures)
            try:
                example, changes = repair(line)
            except (ValueError, TypeError, KeyError) as failure:
                rejected[type(failure).__name__] += 1
                decisions.append({"line": number, "status": "rejected", "initialCategories": failures,
                                  "remaining": type(failure).__name__})
            else:
                recovered.append((number, example))
                repair_types.update(changes)
                decisions.append({"line": number, "status": "repaired", "initialCategories": failures, "repairs": changes})
    output.mkdir(parents=True, exist_ok=True)
    write_jsonl(output / "strict-initial.jsonl", (e.model_dump(mode="json") for _, e in initial))
    write_jsonl(output / "row-decisions.jsonl", decisions)
    candidates = []
    exclusions = Counter()
    for _, example in initial + recovered:
        if any(CONTACT_PATTERN.search(text) for text in
               [s.text for s in example.evidence.sources] + [c.text for c in example.target.claims] + example.target.uncertainties):
            exclusions["possible contact information"] += 1
            continue
        try:
            verify_holdouts([example], frozen)
        except ValueError:
            exclusions["frozen holdout overlap"] += 1
            continue
        candidates.append(example)
    cleaned = output / "training-candidates.jsonl"
    write_jsonl(cleaned, (example.model_dump(mode="json") for example in candidates))
    prepared = prepare(cleaned, output / "prepared", train_only=True, heldout=frozen_path)
    usable = prepared["counts"]["train"]
    ids, evidence, duplicates = set(), set(), Counter()
    for example in candidates:
        content = fingerprint(example)
        if example.id in ids:
            duplicates["reused row ID"] += 1
        elif content in evidence:
            duplicates["duplicate normalized evidence"] += 1
        else:
            ids.add(example.id)
            evidence.add(content)
    if len(prepared["rejected"]) != sum(duplicates.values()):
        raise ValueError("Unexpected rejection during final validation")
    if hashlib.sha256(source.read_bytes()).hexdigest() != original_hash:
        raise ValueError("Original export changed")
    summary = {"sourceSha256": original_hash, "exportRows": len(initial) + len(decisions),
               "strictInitial": len(initial), "initialRejected": len(decisions),
               "primaryFailureTypes": dict(primary), "remainingFailureTypes": dict(rejected),
               "failureCategories": dict(categories), "recovered": len(recovered), "repairTypes": dict(repair_types),
               "irreparableUnderPolicy": sum(rejected.values()), "postSchemaExclusions": dict(exclusions),
               "duplicateRows": len(prepared["rejected"]), "duplicateReasons": dict(duplicates),
               "frozenEvaluationRows": len(frozen),
               "usableTrainingRows": usable, "minimumTrainingRows": MIN_ROWS,
               "meetsMinimum": usable >= MIN_ROWS, "languages": prepared["languages"]["train"]}
    (output / "audit-summary.json").write_text(canonical(summary) + "\n", encoding="utf-8")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(canonical(audit(args.source, args.output)))
