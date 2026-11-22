"""Offline-first CLI for fictional SAAYA evidence data and Adaption workflows."""
import argparse
import asyncio
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import tarfile

from dotenv import load_dotenv
from pydantic import BaseModel, ConfigDict

from app.evidence_intelligence import Evidence, INSTRUCTIONS, Report, synthesize, validate_report

ROOT = Path(__file__).resolve().parent
MODEL = "Qwen/Qwen3.5-4B"
MIN_ROWS = 1000
TOKEN_LIMIT = 131072
MAPPING = {"prompt": "prompt", "completion": "completion"}
CONTACT_PATTERN = re.compile(r"(?i)\b[\w.+-]+@[\w.-]+\.[a-z]{2,}\b|(?:\+91[ .-]?)?\b[6-9]\d{9}\b")


class Example(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str
    group: str
    language: str = "en"
    provenance: str
    evidence: Evidence
    target: Report
    forbidden: list[str] = []


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def invent_request(rows):
    if not 1 <= rows <= 20000:
        raise ValueError("Choose between 1 and 20,000 generated rows")
    prompt = (ROOT / "ml" / "invent-grounded-v2.txt").read_text(encoding="utf-8").strip()
    return {"dataset_prompt": prompt, "rows": rows, "name": "SAAYA fictional evidence grounded v2",
            "training_type": "instruction_dataset",
            "idempotency_key": "saaya-invent-" + hashlib.sha256(f"{rows}:{prompt}".encode()).hexdigest()}


def adapt_request(rows):
    if rows < 1:
        raise ValueError("At least one validated training row is required")
    return {"column_mapping": MAPPING, "job_specification": {"max_rows": rows},
            "recipe_specification": {"recipes": {"prompt_rephrase": False, "deduplication": True,
                                                 "reasoning_traces": False}}}


def training_request(dataset_id, rows):
    if rows < MIN_ROWS or not dataset_id.strip():
        raise ValueError(f"AutoScientist SFT requires a dataset ID and at least {MIN_ROWS} validated rows")
    return {"dataset_id": dataset_id, "model": MODEL, "training_method": "instruction",
            "column_mapping": MAPPING, "hyperparams": {"training_type": "lora"}, "max_iterations": 1,
            "idempotency_key": "saaya-sft-" + hashlib.sha256(dataset_id.encode()).hexdigest()}


def rows(path):
    with path.open(encoding="utf-8-sig") as file:
        for number, line in enumerate(file, 1):
            if line.strip():
                yield number, line


def example_from_row(line, provenance=None):
    item = json.loads(line)
    # Adaption's documented JSONL export uses enhanced_* columns.
    columns = ("enhanced_prompt", "enhanced_completion") if "enhanced_prompt" in item or "enhanced_completion" in item else ("prompt", "completion")
    if all(column in item for column in columns):
        if not provenance:
            raise ValueError("Generated/adapted rows require a source dataset ID")
        prompt, completion = (item[column] for column in columns)
        # SAAYA exports prepend the exact synthesis instructions to a structured envelope.
        if isinstance(prompt, str) and prompt.startswith(INSTRUCTIONS + "\n"):
            prompt = prompt.removeprefix(INSTRUCTIONS + "\n")
        envelope = json.loads(prompt)
        if not isinstance(envelope, dict) or "language" not in envelope:
            raise ValueError("Generated prompts must declare their language")
        item = {**envelope, "target": json.loads(completion), "provenance": provenance}
    example = Example.model_validate(item)
    if not all(part.strip() for part in (example.id, example.group, example.provenance, example.language)):
        raise ValueError("Dataset ID, group, language and provenance are required")
    validate_report(example.target, example.evidence)
    total_bytes = len((INSTRUCTIONS + example.evidence.model_dump_json() + example.target.model_dump_json()).encode())
    if total_bytes + 1024 > TOKEN_LIMIT:
        raise ValueError("Conservative byte-based tokenizer budget exceeded")
    return example


def fingerprint(example):
    content = " ".join(source.text for source in example.evidence.sources)
    return " ".join(re.findall(r"[^\W_]+", content.casefold(), re.UNICODE))


def verify_holdouts(training, heldout):
    groups = {example.group for example in heldout}
    holdout_words = [set(fingerprint(example).split()) for example in heldout]
    for example in training:
        words = set(fingerprint(example).split())
        if example.group in groups or any(words and words == target or
                len(words | target) >= 5 and len(words & target) / len(words | target) >= 0.8 for target in holdout_words):
            raise ValueError(f"Scenario leakage detected for {example.id}")


def prepare(source, destination, provenance=None, train_only=False, heldout=None, expected_rows=None):
    groups, seen, ids = {}, set(), set()
    splits = {"train": [], "validation": [], "heldout": []}
    rejected = []
    total = 0
    for number, row in rows(source):
        total += 1
        try:
            example = example_from_row(row, provenance)
            if not example.provenance.startswith(("synthetic:", "fixture:")) or any(
                    CONTACT_PATTERN.search(text) for text in
                    [s.text for s in example.evidence.sources] + [c.text for c in example.target.claims] + example.target.uncertainties):
                rejected.append({"line": number, "reason": "unverified provenance or possible contact information"})
                continue
            content = fingerprint(example)
            if content in seen or example.id in ids:
                rejected.append({"line": number, "reason": "duplicate"})
                continue
            seen.add(content)
            ids.add(example.id)
            groups.setdefault(example.group, []).append(example)
        except (ValueError, TypeError, KeyError):
            rejected.append({"line": number, "reason": "malformed or ungrounded"})
    if expected_rows is not None and total != expected_rows:
        raise ValueError(f"Expected {expected_rows} export rows, read {total}")
    for index, group in enumerate(sorted(groups, key=lambda name: hashlib.sha256(name.encode()).hexdigest())):
        split = "train" if train_only else ("heldout" if index % 5 == 0 else "validation" if index % 5 == 1 else "train")
        splits[split].extend(groups[group])
    if train_only and heldout is None:
        raise ValueError("A train-only import requires existing held-out and validation rows")
    other = [example_from_row(row) for _, row in rows(heldout)] if heldout else []
    verify_holdouts(splits["train"], splits["validation"] + splits["heldout"] + other)
    verify_holdouts(splits["validation"], splits["heldout"])
    verify_holdouts(splits["validation"] + splits["heldout"], other)
    if not any(splits.values()):
        raise ValueError("No valid dataset rows")
    destination.mkdir(parents=True, exist_ok=True)
    for split, examples in splits.items():
        write_jsonl(destination / f"{split}.jsonl", (item.model_dump(mode="json") for item in examples))
    write_jsonl(destination / "train-sft.jsonl", ({"prompt": INSTRUCTIONS + "\n" + canonical({
        "id": item.id, "group": item.group, "language": item.language, "evidence": item.evidence.model_dump(mode="json")}),
        "completion": item.target.model_dump_json()} for item in splits["train"]))
    manifest = {"seed": "sha256-group-v1", "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                "inputRows": total, "expectedRows": expected_rows,
                "languages": {name: dict(Counter(item.language for item in examples)) for name, examples in splits.items()},
                "counts": {name: len(examples) for name, examples in splits.items()}, "rejected": rejected,
                "trainSha256": hashlib.sha256((destination / "train-sft.jsonl").read_bytes()).hexdigest()}
    (destination / "manifest.json").write_text(canonical(manifest) + "\n", encoding="utf-8")
    return manifest


def write_jsonl(destination, objects):
    with destination.open("w", encoding="utf-8", newline="\n") as file:
        for item in objects:
            file.write(canonical(item) + "\n")


def checkpoint_metadata(path):
    # Inspect archive names only. Never extract or deserialize executable artifacts.
    with tarfile.open(path, "r:*") as archive:
        names = set()
        root = None
        for member in archive:
            target = Path(member.name.replace("\\", "/"))
            if member.issym() or member.islnk() or target.is_absolute() or ".." in target.parts or ":" in member.name:
                raise ValueError("Unsafe checkpoint archive member")
            if member.isfile():
                names.add(target.name)
                if target.name == "adapter_config.json":
                    if member.size > 65000:
                        raise ValueError("Checkpoint configuration is too large")
                    root = json.load(archive.extractfile(member))
                if target.name == "adapter_model.safetensors":
                    with archive.extractfile(member) as weights:
                        size = int.from_bytes(weights.read(8), "little")
                        if not 2 <= size <= min(member.size - 8, 2_000_000):
                            raise ValueError("Invalid safetensors header length")
                        header = json.loads(weights.read(size))
                        if not isinstance(header, dict) or not any(key != "__metadata__" for key in header):
                            raise ValueError("No tensors declared in checkpoint")
        if not {"adapter_config.json", "adapter_model.safetensors"} <= names or not isinstance(root, dict):
            raise ValueError("Expected documented LoRA checkpoint files")
        if root.get("base_model_name_or_path") != MODEL:
            raise ValueError("Unexpected checkpoint base model")
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"sha256": digest.hexdigest(), "baseModel": MODEL, "type": "lora", "files": sorted(names)}


def evaluate_row(example, output):
    result = output.get("result") or {}
    failures = []
    claims = result.get("groundedReport")
    if claims is None:
        # Existing Cloudflare contract has no typed claim or offset fields. Measure those as unavailable.
        sources = {value.id for value in example.evidence.sources}
        valid_schema = all(isinstance(result.get(key), list) and all(isinstance(x, str) for x in result[key])
                           for key in ("observations", "interpretations", "uncertainties", "evidenceReferences"))
        references = valid_schema and bool(result["evidenceReferences"]) and set(result["evidenceReferences"]) <= sources
        statements = result.get("observations", []) + result.get("interpretations", []) if valid_schema else []
        unsupported = sum(not any(line in source.text for source in example.evidence.sources) for line in statements)
        graded = {"schema": valid_schema, "references": references, "timestamps": None,
                  "uncertainty": valid_schema and bool(result["uncertainties"]), "abstention": None,
                  "unsupported": unsupported, "claims": len(statements)}
    else:
        try:
            report = Report.model_validate(claims)
            valid_schema = True
        except ValueError:
            report, valid_schema = None, False
        source_map = {source.id: source for source in example.evidence.sources}
        statements = report.claims if report else []
        supported_targets = {(c.category, c.sourceId, c.text) for c in example.target.claims}
        def supported(claim):
            source = source_map.get(claim.sourceId)
            if source is None or claim.quote not in source.text:
                return False
            if claim.category == "interpretation":
                return (claim.category, claim.sourceId, claim.text) in supported_targets
            return claim.text == claim.quote and (claim.category != "observed" or source.kind == "vision")
        graded = {"schema": valid_schema, "references": valid_schema and all(c.sourceId in source_map for c in statements),
                  "timestamps": valid_schema and all(c.sourceId in source_map and c.offsetMs == source_map[c.sourceId].offsetMs for c in statements),
                  "uncertainty": valid_schema and bool(report.uncertainties),
                  "abstention": valid_schema and report.abstain == example.target.abstain,
                  "unsupported": sum(not supported(claim) for claim in statements), "claims": len(statements)}
    searchable = canonical(result).casefold()
    graded["injection"] = not any(term.casefold() in searchable for term in example.forbidden)
    failures.extend(key for key, value in graded.items() if value is False or (key == "unsupported" and value))
    if output.get("status") != "ok":
        failures.append("provider:" + str(output.get("status", "missing")))
    return {"id": example.id, "language": example.language, **graded, "failures": failures,
            "report": result if output.get("status") == "ok" else None}


async def evaluate(source, provider, output):
    cases = []
    for _, row in rows(source):
        example = example_from_row(row)
        if not example.provenance.startswith(("synthetic:", "fixture:")):
            raise ValueError("Live evaluation accepts only tagged fictional data")
        try:
            response = await synthesize(provider, example.evidence)
        except Exception:
            response = {"status": "unavailable"}
        cases.append(evaluate_row(example, response))
    write_jsonl(output, cases)
    claims = sum(case["claims"] for case in cases)
    metrics = {}
    for field in ("schema", "references", "timestamps", "uncertainty", "abstention", "injection"):
        values = [case[field] for case in cases if case[field] is not None]
        metrics[field] = {"passed": sum(values), "evaluated": len(values)}
    return {"provider": provider, "cases": len(cases), "failed": sum(bool(c["failures"]) for c in cases),
            "unsupportedClaimRate": sum(case["unsupported"] for case in cases) / claims if claims else None,
            "claims": claims, "metrics": metrics,
            "byLanguage": dict(Counter(case["language"] for case in cases)),
            "failuresByLanguage": dict(Counter(case["language"] for case in cases if case["failures"])),
            "failures": dict(Counter(f for case in cases for f in case["failures"]))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "recover", "invent", "adapt", "upload", "dataset-status", "download", "account",
                                            "train-plan", "train", "train-status", "checkpoint", "evaluate", "smoke"))
    parser.add_argument("--rows", type=int, default=1000)
    parser.add_argument("--dataset")
    parser.add_argument("--run")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--holdouts", type=Path)
    parser.add_argument("--provider", choices=("cloudflare", "adaption", "checkpoint"), default="adaption")
    parser.add_argument("--online", action="store_true", help="Allow the documented read/estimate calls")
    parser.add_argument("--approve-spend", action="store_true", help="Explicit operator approval for this paid command")
    parser.add_argument("--max-credits", type=float)
    parser.add_argument("--expected-rows", type=int)
    parser.add_argument("--wait-seconds", type=int, default=180)
    args = parser.parse_args()
    load_dotenv(ROOT / ".env")
    try:
        result = execute(args)
    except Exception as error:
        # SDK errors can include request bodies and headers. Only emit the public status code.
        if isinstance(error, (ValueError, FileNotFoundError)) and error.__class__.__module__ == "builtins":
            parser.exit(1, f"{error}\n")
        parser.exit(1, f"Operation failed ({type(error).__name__}, HTTP {getattr(error, 'status_code', 'n/a')}).\n")
    print(canonical(result))


def execute(args):
    op = args.command
    if op == "prepare":
        return prepare(args.input, args.output, provenance=args.dataset, train_only=bool(args.holdouts),
                       heldout=args.holdouts, expected_rows=args.expected_rows)
    if op == "recover":
        if not args.online or args.approve_spend or not args.dataset or not args.output or not 0 <= args.wait_seconds <= 600:
            raise ValueError("Recovery needs --online, a dataset ID, an output directory and a 0–600 second wait")
        from adaption import Adaption
        with Adaption(timeout=20, max_retries=0) as client:
            return recover(client, args.dataset, args.output, args.wait_seconds, args.expected_rows, args.holdouts)
    if op in ("evaluate", "smoke"):
        if not args.approve_spend:
            raise ValueError("Live inference requires --approve-spend")
        if op == "evaluate":
            return asyncio.run(evaluate(args.input, args.provider, args.output))
        fixtures = (example_from_row(line) for _, line in rows(ROOT / "ml" / "heldout.jsonl"))
        example = next(item for item in fixtures if any(source.kind == "transcript" for source in item.evidence.sources))
        response = asyncio.run(synthesize(args.provider, example.evidence))
        return {**evaluate_row(example, response), "response": response}
    if op == "checkpoint" and args.input:
        return checkpoint_metadata(args.input)
    if op in ("adapt", "download", "dataset-status", "train", "train-plan") and not args.dataset:
        raise ValueError("A dataset ID is required")
    if op in ("train-status", "checkpoint") and not args.run:
        raise ValueError("A run ID is required")
    if op == "invent":
        config = invent_request(args.rows)
    elif op == "adapt":
        config = adapt_request(args.rows)
    elif op in ("train-plan", "train"):
        config = training_request(args.dataset, args.rows)
    else:
        config = None
    if not args.online and not args.approve_spend:
        if config is None:
            raise ValueError("This command requires --online")
        return {"mode": "offline-plan", "request": config}
    from adaption import Adaption
    with Adaption(timeout=30, max_retries=0) as client:
        if op == "account":
            models = client.autoscientist.list_models().models
            return {"trainingCatalogReadable": True, "selectedModelAvailable": any(
                model.id == MODEL and "sft" in model.methods and "lora" in model.training_types for model in models)}
        if op in ("invent", "adapt"):
            if op == "invent":
                estimate = client.datasets.invent(**config, estimate=True)
                cost = estimate.estimated_credits
                available = estimate.available_credits
            else:
                estimate = client.datasets.adapt(args.dataset, **config, estimate=True)
                cost = estimate.estimated_credits_consumed
                available = client.datasets.invent(**invent_request(1000), estimate=True).available_credits if args.approve_spend else None
            if not args.approve_spend:
                return {"estimatedCredits": cost}
            if cost is None or args.max_credits is None or not 0 <= cost <= args.max_credits:
                raise ValueError("Documented estimate exceeds the approved credit limit or is unavailable")
            if available is None or available < cost:
                raise ValueError("Insufficient existing Adaption credits")
            result = client.datasets.invent(**config) if op == "invent" else client.datasets.adapt(args.dataset, **config)
            return {"datasetId": result.id if op == "invent" else args.dataset, "runId": getattr(result, "run_id", None)}
        if op == "upload":
            if not args.approve_spend:
                raise ValueError("Remote dataset creation requires explicit approval")
            if not args.input or not args.dataset:
                raise ValueError("Provide prepared training JSONL and its synthetic provenance label via --dataset")
            if not args.dataset.startswith("synthetic:"):
                raise ValueError("Training uploads require synthetic provenance")
            manifest = json.loads(args.input.with_name("manifest.json").read_text(encoding="utf-8"))
            if hashlib.sha256(args.input.read_bytes()).hexdigest() != manifest["trainSha256"]:
                raise ValueError("Training input does not match the local preparation manifest")
            for _, line in rows(args.input):
                example_from_row(line, provenance=args.dataset)
            result = client.datasets.upload_file(str(args.input), name="SAAYA fictional train split", processing_mode="adapt")
            return {"datasetId": result.dataset_id}
        if op == "dataset-status":
            result = client.datasets.get_status(args.dataset)
            return {"datasetId": result.dataset_id, "status": result.status, "rows": result.row_count,
                    "progress": result.progress.model_dump() if result.progress else None,
                    "errorCode": result.error_data.code if result.error_data else None}
        if op == "download":
            client.datasets.download(args.dataset, file_format="jsonl").write_to_file(args.output)
            return {"saved": str(args.output)}
        if op in ("train-plan", "train"):
            models = client.autoscientist.list_models().models
            if not any(model.id == MODEL and "sft" in model.methods and "lora" in model.training_types for model in models):
                raise ValueError("The selected model or SFT mode is unavailable for this account")
            dataset = client.datasets.get(args.dataset)
            if dataset.status != "succeeded" or (dataset.row_count or 0) < MIN_ROWS:
                raise ValueError("The remote dataset is not ready or below the SFT row minimum")
            client.autoscientist.recommend_hyperparams(dataset_id=args.dataset, model=MODEL)
            if op == "train-plan":
                return {"recommendation": "available", "launchRequest": config, "credits": "verify exact quote in dashboard"}
            if not args.approve_spend or args.max_credits is None or args.max_credits <= 0:
                raise ValueError("Training requires a manually checked dashboard quote and explicit approval")
            if not args.input or not args.holdouts:
                raise ValueError("Training requires the adapted JSONL export and frozen validation/held-out examples")
            verified = [example_from_row(line, provenance=args.dataset) for _, line in rows(args.input)]
            if len(verified) != dataset.row_count or len({fingerprint(item) for item in verified}) != len(verified):
                raise ValueError("Export has duplicates or does not match the remote dataset row count")
            verify_holdouts(verified, [example_from_row(line) for _, line in rows(args.holdouts)])
            run = client.autoscientist.create(**config)
            return {"runId": run.id, "status": run.status}
        run = client.autoscientist.get(args.run)
        if op == "train-status":
            return {"runId": run.id, "status": run.status, "model": run.model,
                    "downloadAvailable": run.download_available, "bestWinRate": run.best_win_rate}
        if run.status != "succeeded" or not run.download_available or run.model != MODEL:
            raise ValueError("A successful downloadable run for the configured base model is required")
        with client.autoscientist.with_streaming_response.download(run.id) as response:
            response.stream_to_file(args.output)
        return {"runId": run.id, **checkpoint_metadata(args.output)}


def recover(client, identity, output, wait_seconds, expected_rows=None, frozen=None):
    """Read an existing job. Only completed Invent exports produce local splits."""
    from adaption import DatasetTimeout

    status = client.datasets.get_status(identity)
    if status.status in ("pending", "running") and wait_seconds:
        try:
            status = client.datasets.wait_for_completion(identity, initial_interval=20, max_interval=30,
                                                         timeout=wait_seconds)
        except DatasetTimeout:
            status = client.datasets.get_status(identity)
    result = {"datasetId": identity, "status": status.status, "providerRows": status.row_count,
              "errorCode": status.error_data.code if status.error_data else None}
    output.mkdir(parents=True, exist_ok=True)
    (output / "latest-status.json").write_text(canonical(result) + "\n", encoding="utf-8")
    if status.status != "succeeded":
        return result
    output_file = output / "generated.jsonl"
    client.datasets.download(identity, file_format="jsonl").write_to_file(output_file)
    result["export"] = str(output_file)
    if expected_rows is not None and status.row_count is not None and status.row_count != expected_rows:
        raise ValueError(f"Provider returned {status.row_count} rows, expected {expected_rows}; export retained")
    result["validation"] = prepare(output_file, output / "splits", provenance="synthetic:" + identity,
                                   heldout=frozen, expected_rows=expected_rows or status.row_count)
    return result


if __name__ == "__main__":
    main()
