import uuid
from pathlib import Path

from ..config import settings


class StorageService:
    def __init__(self):
        self.backend = settings.storage_backend.lower()
        self.local_root = Path(settings.storage_local_path)
        if self.backend == "local":
            self.local_root.mkdir(parents=True, exist_ok=True)

    def save(self, content: bytes, filename: str, prefix: str) -> str:
        key = f"{prefix}/{uuid.uuid4().hex}_{Path(filename).name}"
        if self.backend == "s3":
            self._s3().put_object(
                Bucket=settings.s3_bucket,
                Key=key,
                Body=content,
            )
            return key
        path = self.local_root / Path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return key

    def read(self, key: str) -> bytes:
        if self.backend == "s3":
            response = self._s3().get_object(Bucket=settings.s3_bucket, Key=key)
            return response["Body"].read()
        path = (self.local_root / key).resolve()
        root = self.local_root.resolve()
        if root not in path.parents:
            raise ValueError("无效的文件路径")
        return path.read_bytes()

    def delete(self, key: str):
        if self.backend == "s3":
            self._s3().delete_object(Bucket=settings.s3_bucket, Key=key)
            return
        path = (self.local_root / key).resolve()
        if self.local_root.resolve() in path.parents and path.exists():
            path.unlink()

    def _s3(self):
        if not settings.s3_bucket:
            raise RuntimeError("S3_BUCKET 尚未配置")
        import boto3

        return boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint_url or None,
            aws_access_key_id=settings.s3_access_key or None,
            aws_secret_access_key=settings.s3_secret_key or None,
            region_name=settings.s3_region,
        )
