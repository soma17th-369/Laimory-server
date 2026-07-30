# Laimory 운영 자산

이 디렉터리는 현재 배포 절차와 host에 배치할 비밀 없는 자산을 소유한다. 실제 AWS 리소스,
GitHub Variables, DNS와 host 설정이 살아 있는 환경의 권위 원천이다. 저장소는 전체 AWS topology나
한 번의 명령으로 재구축할 수 있음을 보장하지 않는다.

## 변경 경계

- AWS 작업은 먼저 `sandbox` SSO 로그인을 확인하고 AWS 조회와 SSM 비변경 진단만 수행한다.
- AWS, S3 또는 host를 수정하기 전 대상·영향·rollback을 설명하고 별도 승인을 받는다.
- `.github/workflows/deploy.yml`은 `dev` merge 시 ECR image를 push하고 SSM으로 dev WAS를 갱신한다.
- `deploy-monitoring.yml`은 지정된 alert rule 변경만 monitoring host에 반영한다.
- runbook과 예제에는 credential 값이나 실제 ID, IP, ARN, bucket 이름을 복제하지 않는다.

GitHub repository Variables에는 `AWS_DEPLOY_ROLE_ARN`, `DEV_INSTANCE_ID`,
`MONITORING_INSTANCE_ID`, `MONITORING_BACKUP_BUCKET`이 필요하다. OIDC role은 저장소의 `dev`
branch만 신뢰하고, ECR push와 지정 EC2 SSM command 및 필요한 S3 prefix로 권한을 제한한다.

## Bootstrap 자산

[`bootstrap-assets.txt`](bootstrap-assets.txt)는 S3 `bootstrap/` prefix에 게시할 파일,
입력으로부터 생성할 파일, 의도적으로 제외한 파일을 전부 분류한다. 게시기는 허용된 repository
경로의 tracked file과 manifest가 정확히 일치하는지 검사한다.

```bash
# AWS 호출 없이 manifest 완전성 검사
deploy/scripts/publish-bootstrap-assets.sh --check

# 생성 결과와 업로드 대상을 출력할 뿐 S3를 변경하지 않음
deploy/scripts/publish-bootstrap-assets.sh \
  --bucket '<backup-bucket>' \
  --values /secure/path/monitoring-targets.json \
  --profile sandbox
```

`monitoring-targets.json`은 아래 여섯 key만 허용한다. 값은 Git에 넣지 않는다.

```json
{
  "dev_was_private_ip": "<private-ip>",
  "monitoring_private_ip": "<private-ip>",
  "dev_mysql_private_ip": "<private-ip>",
  "redis_private_ip": "<private-ip>",
  "elk_private_ip": "<private-ip>",
  "dev_api_domain": "<hostname>"
}
```

실제 게시는 dry-run 결과와 대상·영향·rollback을 검토하고 AWS write 승인을 받은 뒤에만 같은 명령에
`--apply`를 추가한다. 게시 후에는 S3 object의 checksum과 필요한 EC2 role의 exact
`bootstrap/...` GetObject 권한을 확인한다. 실패했으면 이전 object version 또는 검증된 이전 commit
자산을 같은 key에 다시 게시한다.

## 재구성 순서

1. Console 또는 승인된 AWS CLI 변경으로 VPC, SG, IAM, S3, ECR, EC2, Route 53 상태를 복구한다.
2. bootstrap manifest를 검증하고 승인 후 S3에 게시한다.
3. MySQL schema와 계정을 먼저 준비하고 Redis, ELK, monitoring, WAS 순으로 host runbook을 수행한다.
4. GitHub Variables와 OIDC trust를 확인한 뒤 배포 workflow 계약 테스트를 실행한다.
5. public DNS/TLS, `/status`, app health, logs, metrics와 rollback 경로를 확인한다.

세부 절차는 [`was/README.md`](was/README.md), [`mysql/README.md`](mysql/README.md),
[`redis/README.md`](redis/README.md), [`elk/README.md`](elk/README.md),
[`monitoring/README.md`](monitoring/README.md)에 있다.
