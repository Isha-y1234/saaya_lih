"""Preflight and explicitly import the frozen SAAYA AutoScientist training artifact."""
import argparse
import hashlib
import json

from dotenv import dotenv_values

from saaya_ml import (
    MAPPING, ROOT, canonical, example_from_row, fingerprint, rows, training_request, verify_holdouts,
)

INPUT = ROOT / ".local-adaption/current-5a997683/merged/prepared/train-sft.jsonl"
EXPECTED_HASH = "6f3bdeb937d5320ed11fda7dcbdd8249d006b0cfa10ca67173613b0ea2762b1c"
EXPECTED_ROWS = 1034
FROZEN = ROOT / "ml/heldout.jsonl"
RECEIPT = INPUT.parent.parent / "raw-import-receipt.json"


def preflight(path, frozen, expected_hash, expected_count):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != expected_hash:
        raise ValueError("Training file differs from the reviewed SHA-256")
    examples = []
    for number, line in rows(path):
        record = json.loads(line)
        if not isinstance(record, dict) or set(record) != set(MAPPING) or any(
                not isinstance(value, str) or not value.strip() for value in record.values()):
            raise ValueError(f"Line {number} needs exactly two nonempty string columns")
        examples.append(example_from_row(line, "synthetic:saaya-merged"))
    if len(examples) != expected_count or len({item.id for item in examples}) != expected_count or len(
            {fingerprint(item) for item in examples}) != expected_count:
        raise ValueError("Unexpected count or duplicate training rows")
    heldout = [example_from_row(line) for _, line in rows(frozen)]
    if len(heldout) != 6:
        raise ValueError("Expected six frozen evaluation rows")
    verify_holdouts(examples, heldout)
    config = training_request("PENDING_RAW_IMPORT", expected_count)
    config.pop("idempotency_key")
    return {"sha256": digest, "rows": len(examples), "frozenRows": len(heldout),
            "processingMode": "raw", "columnMapping": MAPPING, "trainingPlan": config}


def import_raw(client, path, receipt, plan):
    with receipt.open("x", encoding="utf-8") as file:
        file.write(canonical({"status": "upload-attempted", "sha256": plan["sha256"]}) + "\n")
    result = client.datasets.upload_file(path, name="SAAYA 1034 validated evidence reports",
                                         processing_mode="raw", column_mapping=MAPPING)
    if not result.dataset_id or not result.status:
        raise ValueError("Adaption upload response lacked the dataset ID or status")
    record = {"datasetId": result.dataset_id, "status": result.status, "sha256": plan["sha256"],
              "expectedRows": plan["rows"]}
    receipt.write_text(canonical(record) + "\n", encoding="utf-8")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upload", action="store_true", help="Create the raw remote dataset")
    args = parser.parse_args()
    try:
        plan = preflight(INPUT, FROZEN, EXPECTED_HASH, EXPECTED_ROWS)
        if not args.upload:
            print(canonical(plan))
            return
        from adaption import Adaption
        key = dotenv_values(ROOT / ".env").get("ADAPTION_API_KEY")
        if not key:
            parser.exit(1, "Missing ADAPTION_API_KEY in the local .env.\n")
        with Adaption(api_key=key, timeout=60, max_retries=0) as client:
            print(canonical(import_raw(client, INPUT, RECEIPT, plan)))
    except Exception as error:
        parser.exit(1, f"Operation failed ({type(error).__name__}, HTTP {getattr(error, 'status_code', 'n/a')}).\n")


if __name__ == "__main__":
    main()
