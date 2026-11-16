# S3 → R2 사진 복제 Lambda (#647, 임시)

prod가 R2 primary로 전환하기 전까지 Cloudflare R2를 사진 S3 버킷의 완전한 사본으로 유지한다. 클라이언트가 presigned
URL로 저장소에 직접 올리므로 서버에서 이중 쓰기를 할 수 없어, S3 이벤트를 받아 R2에 같은 변경을 반영한다.
prod가 R2 primary가 되면 서버가 양쪽에서 직접 지우므로 이 Lambda와 경보는 정리한다(아래 "정리").

```text
업로드(presigned PUT) → S3 ─ObjectCreated/ObjectRemoved→ Lambda(비동기 호출) ─PUT/DELETE→ R2
                                                         └ 재시도 2회 후 실패 → SQS DLQ → Grafana 경보 → Discord
```

## 동작 (`handler.py`)

- **생성**: S3 `GetObject` → 없으면 건너뜀 → R2 `PutObject`(Content-Type 보존, metadata `replicated-from: s3`)
  → S3 `HeadObject`로 다시 확인해 그사이 지워졌으면 R2에서도 삭제. 업로드는 최대 10MB라 메모리로 읽는다.
- **삭제**: R2 `DeleteObject`. 없는 key도 성공이라 재시도·중복 전달에 안전하다. 탈퇴 파기가 R2 사본까지 닿는 경로다.
- S3 알림은 순서를 보장하지 않는다. 생성 처리가 항상 그 시점의 S3 상태를 읽으므로 삭제가 먼저 처리돼도 R2에 남지 않는다.
- `s3:TestEvent`(알림 설정 때 S3가 보내는 시험 이벤트)는 무시한다. 그 밖의 예외는 그대로 던져 Lambda가 재시도한다.
- 로그는 `{"result": "replicated|skipped_missing|rechecked_deleted|deleted|ignored"}`만 남긴다. key(=subject 해시가 든
  경로)는 남기지 않는다.
- R2 토큰(버킷 한정 Object Read & Write)은 SSM SecureString에 JSON `{"access_key_id": …, "secret_access_key": …}`로 두고
  처음 호출 때 한 번 읽는다.

## 이름

| 자원 | 이름 |
|---|---|
| Lambda | `laimory-shared-photo-replicator`(서울, python3.13, 256MB, timeout 30초) |
| DLQ | `laimory-shared-photo-replicator-dlq`(보관 14일) |
| role | `laimory-shared-photo-replicator-role` |
| R2 토큰 parameter | `/laimory/shared/photo-replicator/r2-token` |

monitoring의 `collect-aws-metrics.sh`가 Lambda·DLQ 이름을 그대로 쓴다. 이름을 바꾸면 그쪽도 같이 바꾼다.

## 배포 (수동, AWS CLI)

계정 ID·버킷 이름·R2 endpoint는 저장소에 적지 않는다. 아래 `<...>`는 실행할 때 채운다. 각 단계는 운영 변경이므로
대상·영향·되돌리는 방법을 확인하고 실행한다.

```bash
export AWS_REGION=ap-northeast-2
ACCOUNT_ID='<account id>'
PHOTO_BUCKET='<photo bucket>'
FN=laimory-shared-photo-replicator
DLQ=laimory-shared-photo-replicator-dlq
ROLE=laimory-shared-photo-replicator-role
PARAM=/laimory/shared/photo-replicator/r2-token
```

1. **R2 토큰**: Cloudflare 대시보드에서 R2 버킷 하나로 제한한 Object Read & Write 토큰을 만들고, 값을 화면·기록에
   남기지 않고 넣는다.

   ```bash
   read -rs R2_KEY_ID; read -rs R2_SECRET
   aws ssm put-parameter --name "$PARAM" --type SecureString \
     --value "$(jq -cn --arg a "$R2_KEY_ID" --arg s "$R2_SECRET" '{access_key_id:$a,secret_access_key:$s}')"
   unset R2_KEY_ID R2_SECRET
   ```

2. **DLQ**:

   ```bash
   aws sqs create-queue --queue-name "$DLQ" \
     --attributes MessageRetentionPeriod=1209600,SqsManagedSseEnabled=true
   ```

3. **role**: 신뢰 주체 `lambda.amazonaws.com`, inline policy는 아래만 허용한다. `s3:ListBucket`이 없으면 없는 key의
   GET·HEAD가 404가 아니라 403으로 와서 "이미 삭제됨"을 구분할 수 없다. S3 쓰기·삭제 권한은 주지 않는다.

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {"Effect": "Allow", "Action": "s3:GetObject", "Resource": "arn:aws:s3:::<photo bucket>/*"},
       {"Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::<photo bucket>"},
       {"Effect": "Allow", "Action": "ssm:GetParameter",
        "Resource": "arn:aws:ssm:ap-northeast-2:<account id>:parameter/laimory/shared/photo-replicator/r2-token"},
       {"Effect": "Allow", "Action": "sqs:SendMessage",
        "Resource": "arn:aws:sqs:ap-northeast-2:<account id>:laimory-shared-photo-replicator-dlq"},
       {"Effect": "Allow", "Action": ["logs:CreateLogStream", "logs:PutLogEvents"],
        "Resource": "arn:aws:logs:ap-northeast-2:<account id>:log-group:/aws/lambda/laimory-shared-photo-replicator:*"}
     ]
   }
   ```

   SecureString은 AWS 관리형 키 `aws/ssm`으로 암호화되어 있어 별도 `kms:Decrypt` 문장 없이 복호화된다.

4. **Lambda**: 로그 그룹을 먼저 만들어 보관 기간을 정하고(Lambda 자동 생성 그룹은 무기한 보관), 함수를 만든다.

   ```bash
   aws logs create-log-group --log-group-name "/aws/lambda/$FN"
   aws logs put-retention-policy --log-group-name "/aws/lambda/$FN" --retention-in-days 14
   (cd deploy/aws/photo-r2-replicator && zip -q /tmp/photo-r2-replicator.zip handler.py)
   aws lambda create-function --function-name "$FN" --runtime python3.13 --architectures arm64 \
     --handler handler.handler --memory-size 256 --timeout 30 \
     --role "arn:aws:iam::$ACCOUNT_ID:role/$ROLE" --zip-file fileb:///tmp/photo-r2-replicator.zip \
     --environment "Variables={R2_ENDPOINT=<r2 s3 endpoint>,R2_BUCKET=<r2 bucket>,R2_TOKEN_PARAMETER=$PARAM}"
   aws lambda put-function-event-invoke-config --function-name "$FN" \
     --maximum-retry-attempts 2 --maximum-event-age-in-seconds 21600 \
     --destination-config "{\"OnFailure\":{\"Destination\":\"arn:aws:sqs:$AWS_REGION:$ACCOUNT_ID:$DLQ\"}}"
   ```

   코드만 바꿀 때는 `aws lambda update-function-code --function-name "$FN" --zip-file fileb:///tmp/photo-r2-replicator.zip`.

5. **S3 호출 허용 + 버킷 알림**: 버킷 알림 설정은 전체를 덮어쓴다. 기존 설정을 먼저 조회해 합친다.

   ```bash
   aws lambda add-permission --function-name "$FN" --statement-id s3-photo-bucket \
     --action lambda:InvokeFunction --principal s3.amazonaws.com \
     --source-arn "arn:aws:s3:::$PHOTO_BUCKET" --source-account "$ACCOUNT_ID"
   aws s3api get-bucket-notification-configuration --bucket "$PHOTO_BUCKET"
   aws s3api put-bucket-notification-configuration --bucket "$PHOTO_BUCKET" \
     --notification-configuration "{\"LambdaFunctionConfigurations\":[{\"Id\":\"photo-r2-replication\",
       \"LambdaFunctionArn\":\"arn:aws:lambda:$AWS_REGION:$ACCOUNT_ID:function:$FN\",
       \"Events\":[\"s3:ObjectCreated:*\",\"s3:ObjectRemoved:*\"]}]}"
   ```

6. **기존 객체 채우기**: 알림을 켠 **뒤에** 실행해야 그사이 올라온 객체를 놓치지 않는다. 02:00~04:30 KST(탈퇴 파기·draft
   정리·고아 스위퍼)는 피한다 — 복사가 방금 지워진 객체를 되살리지 않게 한다. `sync`는 쓰지 않는다(R2에만 있는 객체를 지운다).

   ```bash
   rclone copy --ignore-existing s3:<photo bucket> r2:<r2 bucket>
   ```

## 확인

```bash
python3 -m unittest discover -s deploy/aws/photo-r2-replicator
```

배포 뒤에는 앱 namespace 밖의 시험 key(예: `replication-test/<uuid>.txt`)를 S3에 올리고 지우면서 R2 반영과 로그 결과를
본다. S3와 R2의 객체 목록(key·크기·ETag)을 전수 대조한다.

- S3의 모든 key가 R2에 있고 크기·ETag(MD5)가 같아야 한다. 사진 버킷은 SSE-S3이고 multipart 업로드가 없어 ETag가 MD5다.
- R2에만 있는 key 중 복제 표식(`replicated-from` 또는 rclone `mtime` metadata)이 있는 것은 0이어야 한다. 있다면 S3에서
  지워졌는데 R2에 남은 사본이다.

결과는 개수만 출력하고 key는 출력하지 않는다.

## 실패 이벤트 재처리

Grafana 경보 `S3 to R2 photo replication failed`는 DLQ에 메시지가 있다는 뜻이다. 삭제 이벤트가 실패했다면 탈퇴자 사진이
R2에 남아 있으므로 **파기 기한(5일) 안에** 처리한다. 원인(R2 장애·토큰 폐기 등)을 먼저 해결한 뒤, DLQ 메시지의 원본
이벤트(`requestPayload`)로 Lambda를 다시 호출하고 성공한 메시지만 지운다.

```bash
QUEUE_URL=$(aws sqs get-queue-url --queue-name "$DLQ" --query QueueUrl --output text)
while msg=$(aws sqs receive-message --queue-url "$QUEUE_URL" --max-number-of-messages 1 \
              --visibility-timeout 120 --output json) && [ "$(jq '.Messages | length' <<<"$msg")" = 1 ]; do
  jq -c '.Messages[0].Body | fromjson | .requestPayload' <<<"$msg" > /tmp/replay.json
  if aws lambda invoke --function-name "$FN" --payload fileb:///tmp/replay.json /tmp/replay.out \
       --query FunctionError --output text | grep -qx None; then
    aws sqs delete-message --queue-url "$QUEUE_URL" \
      --receipt-handle "$(jq -r '.Messages[0].ReceiptHandle' <<<"$msg")"
  else
    echo "replay failed; message left in DLQ" >&2; break
  fi
done
rm -f /tmp/replay.json /tmp/replay.out
```

`S3 to R2 photo replication events lost`는 DLQ에도 넣지 못한 이벤트가 있다는 뜻이다. 원본이 남지 않으므로 위 "확인"의
전수 대조로 빠진 사본을 채우고 남은 사본을 지운다.

## 정리 (prod가 R2 primary가 된 뒤)

**기존 객체를 채운 뒤에는 알림만 끄고 끝내면 안 된다.** R2에 prod 사본이 있는데 S3 삭제가 더 이상 따라오지 않으면 탈퇴자
사본이 R2에 남는다. 아래 둘 중 하나가 먼저 성립해야 한다.

- prod 서버가 R2에서도 직접 지우는 상태(`PHOTO_STORAGE_PRIMARY=r2` 또는 R2 설정이 있는 #643 이후 버전)
- R2의 복제 사본(`replicated-from`·rclone 표식)을 모두 지운 상태

그다음 버킷 알림에서 이 Lambda 설정을 제거하고, Lambda·로그 그룹·DLQ·role·parameter를 삭제하고, 복제용 R2 토큰을
폐기한다. monitoring `collect-aws-metrics.sh`의 복제 지표 2개와 `extended-infrastructure-rules.yml`의 경보 2개도 같이 지운다.
