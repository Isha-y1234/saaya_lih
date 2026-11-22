"""Private B2 storage. No SDK errors or signed URLs may enter application logs."""
import os
import re
from datetime import datetime

import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError
from fastapi import HTTPException


EVIDENCE_BUCKET = "saaya-evidence"


class EvidenceStorage:
    def __init__(self):
        self.bucket = os.getenv("B2_BUCKET", EVIDENCE_BUCKET).strip() or EVIDENCE_BUCKET
        self.endpoint = os.getenv("B2_ENDPOINT_URL", "").strip()
        self.region = os.getenv("B2_REGION", "").strip()
        self.key_id = os.getenv("B2_APPLICATION_KEY_ID", "").strip()
        self.secret = os.getenv("B2_APPLICATION_KEY", "").strip()
        self.photo_max = self.number("EVIDENCE_PHOTO_MAX_BYTES", 10 * 1024 * 1024, 1024, 20 * 1024 * 1024)
        self.video_max = self.number("EVIDENCE_VIDEO_MAX_BYTES", 50 * 1024 * 1024, 1024, 100 * 1024 * 1024)
        self.ttl = self.number("EVIDENCE_URL_TTL_SECONDS", 120, 30, 300)

    @staticmethod
    def number(name, default, minimum, maximum):
        try:
            return max(minimum, min(maximum, int(os.getenv(name, str(default)))))
        except ValueError:
            raise RuntimeError(f"Invalid {name}") from None

    def client(self):
        if (self.bucket != EVIDENCE_BUCKET or not all((self.region, self.key_id, self.secret)) or
                not re.fullmatch(r"https://s3\.[a-z0-9-]+\.backblazeb2\.com", self.endpoint) or
                self.endpoint != f"https://s3.{self.region}.backblazeb2.com"):
            raise HTTPException(503, "Evidence storage is not configured")
        return boto3.client("s3", endpoint_url=self.endpoint, region_name=self.region,
            aws_access_key_id=self.key_id, aws_secret_access_key=self.secret,
            config=Config(signature_version="s3v4", connect_timeout=5, read_timeout=15,
                          retries={"max_attempts": 2}, s3={"addressing_style": "path"},
                          request_checksum_calculation="when_required", response_checksum_validation="when_required"))

    def put_url(self, doc):
        try:
            return self.client().generate_presigned_url("put_object", Params={"Bucket": self.bucket,
                "Key": doc["objectKey"], "ContentType": doc["mediaType"], "ContentLength": doc["fileSize"],
                "Metadata": {"evidence-id": str(doc["_id"]) }}, ExpiresIn=self.ttl)
        except (BotoCoreError, ClientError):
            raise HTTPException(503, "Evidence storage unavailable. Retry later.") from None

    def verify(self, doc, *, wav=False):
        try:
            client = self.client()
            head = client.head_object(Bucket=self.bucket, Key=doc["objectKey"])
            version = head.get("VersionId")
            if (head["ContentLength"] != doc["fileSize"] or head.get("ContentType") != doc["mediaType"] or
                    head.get("Metadata", {}).get("evidence-id") != str(doc["_id"]) or
                    not version or version == "null"):
                raise HTTPException(409, "Uploaded object metadata does not match authorization")
            result = client.get_object(Bucket=self.bucket, Key=doc["objectKey"], VersionId=version, Range="bytes=0-31")
            try:
                prefix = result["Body"].read(32)
            finally:
                result["Body"].close()
            if wav:
                valid = len(prefix) >= 12 and prefix[:4] == b"RIFF" and prefix[8:12] == b"WAVE"
            elif doc["mediaType"] == "image/jpeg":
                valid = prefix.startswith(b"\xff\xd8\xff")
            else:
                valid = len(prefix) >= 12 and prefix[4:8] == b"ftyp"
            if not valid:
                raise HTTPException(409, "Uploaded content does not match media type")
            modified = head.get("LastModified")
            if not isinstance(modified, datetime):
                raise HTTPException(409, "Upload metadata unavailable")
            return version, modified
        except ClientError as error:
            if error.response.get("Error", {}).get("Code") in ("404", "NoSuchKey", "NotFound"):
                raise HTTPException(409, "Upload not found. Retry upload.") from None
            raise HTTPException(503, "Could not verify evidence. Retry confirmation.") from None
        except BotoCoreError:
            raise HTTPException(503, "Could not verify evidence. Retry confirmation.") from None

    def get_url(self, doc):
        try:
            return self.client().generate_presigned_url("get_object", Params={"Bucket": self.bucket,
                "Key": doc["objectKey"], "VersionId": doc["versionId"],
                "ResponseContentDisposition": "attachment", "ResponseCacheControl": "private, no-store"}, ExpiresIn=self.ttl)
        except (BotoCoreError, ClientError):
            raise HTTPException(503, "Evidence download unavailable") from None
