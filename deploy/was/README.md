# WAS 운영 runbook

WAS는 nginx에서 TLS를 종료하고 host network의 app container `127.0.0.1:8080`을 프록시한다.
변경 전 `sandbox` SSO와 대상 instance를 조회로 확인하고 SSM 비변경 진단을 먼저 한다. 아래 명령을
실행하는 것은 별도 host 수정 승인을 받은 뒤다.

## DNS, TLS, swap

Route 53 A record가 대상 EIP를 가리키는지 확인한 뒤 nginx `server_name`을 설정하고 certbot으로
certificate를 발급한다. domain 소유 registrar의 NS 위임과 `certbot.timer`도 별도로 확인한다.

메모리 급증 시 host 전체가 멈추지 않도록 WAS에는 2 GiB swap을 한 번 구성한다.

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
grep -q '^/swapfile' /etc/fstab ||
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

## Runtime 환경과 FCM

`/home/ubuntu/app/.env`가 장기 실행 app container의 유일한 runtime env source다. 배포 workflow는
secret key 존재 여부와 아래 값을 정확히 한 줄로 검사한 뒤에만 기존 container를 중지한다.

- `REDIS_KEY_PREFIX=dev_`, `APP_ENV=dev`, `APP_GEO_MODE=kakao`, `SWAGGER_ENABLED=true`
- `APP_AI_MODE=noop|fake|http`; `http`이면 `APP_AI_HTTP_BASE_URL`도 정확히 한 줄
- `APP_PUSH_MODE=noop|firebase`

`.env` 수정은 같은 directory의 mode `0600` 임시 파일을 만들고 owner를 보존한 뒤 atomic rename한다.
값을 SSM command, shell history 또는 logs에 출력하지 않는다.

Firebase mode에서는 service-account JSON을
`/home/ubuntu/app/secrets/firebase-service-account.json`에 owner UID `1001`, mode `0400`으로
배치한다. JSON은 Git, image, S3 bootstrap과 `.env` 값에 넣지 않는다. `.env`에는 아래 ADC 경로만 둔다.

```dotenv
APP_PUSH_MODE=firebase
GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-service-account.json
```

배포 전에 image의 runtime user가 read-only mount를 읽을 수 있는지 확인한다. `noop`이면 credential과
mount를 모두 제거한다.

## nginx trusted edge

앱이 신뢰할 client IP header는 nginx가 덮어쓰는
`Laimory-Client-IP $remote_addr` 하나뿐이다. 다음 절차는 예상한 app location만 허용하고, 변경 뒤
semantic check, `nginx -t`, reload, effective config check 중 하나라도 실패하면 원본을 복원한다.

```bash
sudo bash <<'ROOT'
set -euo pipefail
SITE=/etc/nginx/sites-available/laimory
PATCHER=/usr/local/sbin/patch-laimory-trusted-edge-nginx
BACKUP_DIR=/var/backups/laimory-nginx
BACKUP_BUCKET='<backup-bucket>'
REGION=ap-northeast-2

[[ -f "$SITE" && ! -L "$SITE" ]] || {
  echo "trusted-edge rejected: expected a regular non-symlink site file" >&2
  exit 1
}
install -d -m 0700 "$BACKUP_DIR"
BACKUP="$BACKUP_DIR/laimory.$(date -u +%Y%m%dT%H%M%SZ).conf"
TMP=$(mktemp "$BACKUP_DIR/candidate.XXXXXX")
EFFECTIVE=$(mktemp "$BACKUP_DIR/effective.XXXXXX")
trap 'rm -f "$TMP" "$EFFECTIVE"' EXIT

aws s3 cp \
  "s3://$BACKUP_BUCKET/bootstrap/was/patch_trusted_edge_nginx.py" \
  "$PATCHER" --region "$REGION" --only-show-errors
chmod 0750 "$PATCHER"
cp -a -- "$SITE" "$BACKUP"
python3 "$PATCHER" "$SITE" "$TMP"
chown --reference="$SITE" "$TMP"
chmod --reference="$SITE" "$TMP"

restore_and_fail() {
  MESSAGE=$1
  cp -a -- "$BACKUP" "$SITE"
  nginx -t >/dev/null 2>&1 && systemctl reload nginx || true
  echo "$MESSAGE; restored $BACKUP" >&2
  exit 1
}

mv "$TMP" "$SITE"
python3 "$PATCHER" --check "$SITE" \
  || restore_and_fail "trusted-edge semantic post-check failed"
nginx -t || restore_and_fail "nginx config test failed"
systemctl reload nginx || restore_and_fail "nginx reload failed"
nginx -T > "$EFFECTIVE" 2>&1 \
  || restore_and_fail "effective nginx config dump failed"
python3 "$PATCHER" --check "$EFFECTIVE" \
  || restore_and_fail "effective nginx semantic post-check failed"
echo "backup=$BACKUP"
ROOT
```

nginx access log는 query string의 인증 code가 남지 않도록 `$request` 대신
`"$request_method $uri $server_protocol"`을 쓰는 `noquery` format이어야 한다. 적용 전후
`nginx -T`와 새 access log 한 줄로 query가 기록되지 않는지 확인한다.

## 배포, DB tunnel, rollback

dev merge는 commit SHA로 image를 ECR에 push하고 SSM으로 `.env` preflight, pull, 기존 container
교체, `/api/v1/intro` health check를 수행한다. 실패 로그에 secret 값이 없는지 확인한다.

dev DB를 읽을 때는 public DB ingress를 만들지 않고 SSM port forwarding을 WAS에 연 다음 MySQL
read-only 계정으로 private DB endpoint에 연결한다. 세션 종료 뒤 local port가 닫혔는지 확인한다.

rollback은 검증된 이전 commit SHA의 ECR image를 다시 pull해 동일한 `--env-file`, host network,
log rotation, optional FCM read-only mount 조건으로 재기동하고 health check한다. DNS나 nginx 변경의
rollback은 각각 이전 Route 53 record와 위 root-only backup을 사용한다.
