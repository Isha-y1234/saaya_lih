import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from ml.raw_training_import import import_raw


def test_raw_import_maps_columns_records_dataset_id_and_blocks_duplicate_attempt(tmp_path):
    dataset = Mock()
    dataset.upload_file.return_value = SimpleNamespace(dataset_id="remote-id", status="pending")
    client = SimpleNamespace(datasets=dataset)
    receipt = tmp_path / "receipt.json"
    path = Path("train-sft.jsonl")
    plan = {"sha256": "reviewed", "rows": 1034}

    result = import_raw(client, path, receipt, plan)

    dataset.upload_file.assert_called_once_with(
        path, name="SAAYA 1034 validated evidence reports", processing_mode="raw",
        column_mapping={"prompt": "prompt", "completion": "completion"},
    )
    assert result == json.loads(receipt.read_text(encoding="utf-8"))
    assert result["datasetId"] == "remote-id" and result["status"] == "pending"
    with pytest.raises(FileExistsError):
        import_raw(client, path, receipt, plan)
    assert dataset.upload_file.call_count == 1
