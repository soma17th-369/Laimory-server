# MySQL 운영 runbook

MySQL 8은 private subnet에서만 실행한다. `bind-address`는 private 연결을 받더라도 3306 ingress는
WAS SG와 승인된 monitoring SG로 제한하며 public CIDR를 허용하지 않는다. 변경 전 `sandbox` SSO와
대상, SG, SSM Online 상태를 조회하고 host 수정은 별도 승인을 받는다.

앱은 `ddl-auto=validate`이므로 [`schema.sql`](../../src/main/resources/db/schema.sql)을 앱보다 먼저
적용한다. 게시된 `bootstrap/schema.sql`을 checksum 확인 후 임시 파일로 내려받고, backup과 적용
대상을 확인한 뒤 `mysql < schema.sql`을 실행한다.

필요한 identity는 다음과 같이 분리한다.

- app account: application schema에 필요한 read/write
- dev read-only account: application schema의 `SELECT`만, SSM tunnel을 통해 사용
- monitoring exporter: 확인된 monitoring private IP에서만 접속, `MAX_USER_CONNECTIONS 3`,
  `USAGE`와 status/variables 수집만

credential은 Git, S3와 command argument에 넣지 않는다. exporter 계정은
`bootstrap/monitoring/scripts/configure-mysql-exporter-user.sh`를 내려받아 hidden prompt로
설정한다.

prod만 ROW binlog와 7일 만료를 사용하고 매시간 닫힌 binlog를 backup bucket의 `binlog/` prefix에
업로드한다. IAM write는 그 prefix로 제한한다. 복구 훈련에서는 새 DB에 schema를 적용한 뒤 선택한
binlog chain을 순서대로 재생하고 app 연결 전 row count와 핵심 query를 검증한다. 실패 시 원본 DB를
계속 유지하고 DNS나 app endpoint를 전환하지 않는다.
