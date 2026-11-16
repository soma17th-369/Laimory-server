"""S3 사진 버킷의 객체 생성·삭제를 Cloudflare R2로 복제하는 Lambda (#647).

prod가 R2 primary로 전환하기 전까지 R2를 S3의 완전한 사본으로 유지하는 임시 장치다. 클라이언트가 presigned URL로
저장소에 직접 올리므로 서버에서 이중 쓰기를 할 수 없어 S3 이벤트로 복제한다. 배포·권한·재처리는 README.md 참고.

- 생성: S3에서 읽어 R2에 쓴다. S3에 이미 없으면 건너뛴다(삭제 이벤트가 먼저 처리됐거나 재시도 전에 지워진 경우).
  쓴 뒤 S3를 다시 확인해 그사이 지워졌으면 R2에서도 지운다 — 생성 처리 도중 삭제가 끼어들어도 R2에 사본이 남지 않는다.
- 삭제: R2에서 지운다(없는 key도 성공이라 재시도·중복 전달에 안전). 탈퇴 파기가 R2 사본까지 닿게 하는 경로다.
- 그 외 예외는 그대로 던져 Lambda 재시도 → 실패 시 DLQ로 보낸다.
- 로그에는 key(=subject 해시가 든 경로)를 남기지 않는다.
"""

import json
import os
from urllib.parse import unquote_plus

REPLICATION_MARKER = {"replicated-from": "s3"}
NOT_FOUND_CODES = {"NoSuchKey", "404", "NotFound"}

_clients = None


def handler(event, context):
    s3, r2, r2_bucket = _clients if _clients is not None else _init_clients()
    records = event.get("Records") or []
    if not records:
        _log("ignored", event=event.get("Event", "unknown"))
    for record in records:
        process_record(record, s3, r2, r2_bucket)


def process_record(record, s3, r2, r2_bucket):
    event_name = record.get("eventName", "")
    bucket = record["s3"]["bucket"]["name"]
    key = unquote_plus(record["s3"]["object"]["key"])

    if event_name.startswith("ObjectCreated:"):
        _replicate_created(s3, r2, r2_bucket, bucket, key)
    elif event_name.startswith("ObjectRemoved:"):
        r2.delete_object(Bucket=r2_bucket, Key=key)
        _log("deleted")
    else:
        _log("ignored", event=event_name)


def _replicate_created(s3, r2, r2_bucket, bucket, key):
    try:
        source = s3.get_object(Bucket=bucket, Key=key)
    except Exception as error:
        if _is_not_found(error):
            _log("skipped_missing")
            return
        raise

    r2.put_object(
        Bucket=r2_bucket,
        Key=key,
        Body=source["Body"].read(),
        ContentType=source.get("ContentType") or "application/octet-stream",
        Metadata=REPLICATION_MARKER,
    )

    try:
        s3.head_object(Bucket=bucket, Key=key)
    except Exception as error:
        if _is_not_found(error):
            r2.delete_object(Bucket=r2_bucket, Key=key)
            _log("rechecked_deleted")
            return
        raise
    _log("replicated")


def _is_not_found(error):
    code = getattr(error, "response", {}).get("Error", {}).get("Code")
    return code in NOT_FOUND_CODES


def _log(result, **fields):
    print(json.dumps({"result": result, **fields}))


def _init_clients():
    global _clients
    import boto3
    from botocore.config import Config

    parameter = boto3.client("ssm").get_parameter(Name=os.environ["R2_TOKEN_PARAMETER"], WithDecryption=True)
    token = json.loads(parameter["Parameter"]["Value"])
    r2 = boto3.client(
        "s3",
        endpoint_url=os.environ["R2_ENDPOINT"],
        region_name="auto",
        aws_access_key_id=token["access_key_id"],
        aws_secret_access_key=token["secret_access_key"],
        # R2는 S3 외 구현이라 SDK 기본 CRC 체크섬 동작과 어긋날 수 있다 — 서버 R2 클라이언트와 같은 설정.
        config=Config(
            s3={"addressing_style": "path"},
            request_checksum_calculation="when_required",
            response_checksum_validation="when_required",
        ),
    )
    _clients = (boto3.client("s3"), r2, os.environ["R2_BUCKET"])
    return _clients
