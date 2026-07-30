# Redis 운영 runbook

Redis 7은 private subnet에서 ACL을 켜고 사용한다. TLS를 쓰지 않는 현재 구성의 기밀 경계는 private
network와 SG이므로 6379 ingress는 WAS SG와 승인된 monitoring SG만 허용한다. public CIDR를 열지
않는다. 변경 전 `sandbox` SSO, 대상, SG, SSM Online 상태를 조회하고 host 수정은 별도 승인받는다.

`default` user는 off다. app user만 필요한 key/channel과 command를 사용하고, monitoring user는
확인된 monitoring private IP에서 `INFO`, `PING`, `CLIENT SETNAME`만 허용한다. 실제 password는 Git,
S3, process argument와 logs에 기록하지 않는다.

monitoring identity를 갱신할 때
`bootstrap/monitoring/scripts/configure-redis-exporter-user.sh`를 exact key로 내려받고 hidden
prompt를 사용한다. 적용 뒤 app user의 write/read, monitoring user의 허용 명령과 금지 명령,
`redis-cli --user ... PING`을 각각 검증한다.

복구는 새 private host에 Redis 7과 검토된 ACL을 구성하고 SG source를 확인한 뒤 app endpoint를
전환한다. 현재 저장소는 Redis data backup을 보장하지 않으므로 durable source로 취급하지 않는다.
실패하면 endpoint와 SG를 이전 host로 되돌리고 app health를 다시 확인한다.
