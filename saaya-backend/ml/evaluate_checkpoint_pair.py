"""Compare the base and trained aliases on the frozen synthetic Activity cases."""
import argparse
import asyncio
import hashlib
import os
from pathlib import Path

from dotenv import load_dotenv

from saaya_ml import ROOT, canonical, evaluate

FIXTURES = ROOT / "ml/heldout.jsonl"
FIXTURES_SHA256 = "200881c63e08ce26ba3d005fcfaede7184fb0db6c86ce8ea071c71075044e45f"


async def compare(base, adapter, output):
    if not base.strip() or not adapter.strip() or base == adapter:
        raise ValueError("Supply distinct base and adapter aliases")
    if hashlib.sha256(FIXTURES.read_bytes()).hexdigest() != FIXTURES_SHA256:
        raise ValueError("The frozen evaluation file does not match its reviewed SHA-256")
    if not all(os.environ.get(name) for name in ("ACTIVITY_INFERENCE_URL", "ACTIVITY_INFERENCE_TOKEN")):
        raise ValueError("Configure the private checkpoint endpoint and token")
    output.mkdir(parents=True, exist_ok=False)
    previous = os.environ.get("ACTIVITY_INFERENCE_CHECKPOINT")
    results = {}
    try:
        for label, alias in (("base", base), ("adapter", adapter)):
            os.environ["ACTIVITY_INFERENCE_CHECKPOINT"] = alias
            results[label] = await evaluate(FIXTURES, "checkpoint", output / f"{label}.jsonl")
    finally:
        if previous is None:
            os.environ.pop("ACTIVITY_INFERENCE_CHECKPOINT", None)
        else:
            os.environ["ACTIVITY_INFERENCE_CHECKPOINT"] = previous
    results["aliases"] = {"base": base, "adapter": adapter}
    results["complete"] = all(results[label]["cases"] == 6 and not any(
        reason.startswith("provider:") for reason in results[label]["failures"]
    ) for label in ("base", "adapter"))
    (output / "summary.json").write_text(canonical(results) + "\n", encoding="utf-8")
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--adapter", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    load_dotenv(ROOT / ".env")
    try:
        results = asyncio.run(compare(args.base, args.adapter, args.output))
    except (OSError, ValueError) as error:
        parser.exit(1, f"Comparison could not start ({type(error).__name__}).\n")
    print(canonical(results))
    if not results["complete"]:
        parser.exit(2, "At least one provider response was unavailable or invalid.\n")


if __name__ == "__main__":
    main()
