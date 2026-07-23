# Laimory dev monitoring

Prometheus, Grafana, blackbox exporter를 private dev monitoring EC2 한 대에서 실행하는 재구축 자산이다.
실제 AWS 반영은 `terraform/README.md`의 Console/SSM runbook을 따른다. Terraform은 recipe이며 살아
있는 dev에 blanket `terraform apply`를 하지 않는다.

## 기본 동작

- Prometheus: 30초 수집, TSDB 7일 또는 12GB 중 먼저 도달한 제한
- blackbox exporter: public dev `/status`를 60초마다 확인
- Grafana: Prometheus datasource만 기본 provisioning
- 외부 publish: Grafana 3000만 loopback과 monitoring private IP에 bind
- Prometheus 9090과 blackbox 9115는 Docker network에만 expose
- Grafana anonymous access와 sign-up은 비활성화
- Prometheus 2GiB, Grafana 768MiB, blackbox 128MiB memory limit
- `nginx/manage-grafana-proxy.sh`: 기존 Kibana snippet을 보존하는 allowlist proxy enable/disable

MySQL/Redis exporter, Elasticsearch datasource, dashboard와 alert provisioning은 후속 #185에서 추가한다.
node target은 미리 렌더되지만 각 host에 node_exporter를 설치하기 전에는 DOWN이 정상이다.

24시간 관찰에서 host memory 75% 초과가 15분 이상 반복되거나 OOM/restart가 생기면 collector와 label을
먼저 줄인다. active series 10,000 초과, root disk 70% 초과, scrape duration이 interval의 50% 이상인
상태가 계속되면 원인을 줄인 뒤에도 해소되지 않을 때 `t3.large` 전환을 별도 변경으로 검토한다.

## Secret gate

`secrets/grafana_admin_password`와 `secrets/grafana_secret_key`는 Git, Terraform, S3 bootstrap에 넣지
않는다. host의 `/opt/laimory-monitoring/secrets`에 SSM Session Manager로 주입한 뒤에만 systemd 전체
stack을 시작한다. 두 파일 중 하나라도 비어 있으면 `ExecCondition`이 Grafana 시작을 막는다.
Grafana는 `restart: on-failure`로 process 장애만 Docker가 복구한다. host boot는 systemd가 시작하고,
Docker service를 재시작했다면 `sudo systemctl start laimory-monitoring`으로 secret을 다시 확인한다.

host에서는 parent directory를 `0700 root:root`, 파일을 `0400 472:root`로 유지한다. parent directory
때문에 일반 host 사용자는 읽을 수 없고 Grafana container UID 472는 read-only bind mount를 통해서만
읽는다. 관리자 비밀번호는 Grafana DB가 처음 만들어질 때 각인되므로 이후 회전은 Grafana admin 절차를
사용한다. `grafana_secret_key`는 datasource credential 복호화를 위해 재부팅·재배포에도 같은 값을
유지한다.

## 검증

비밀값을 출력하지 않는 검사만 사용한다.

```bash
docker compose -f deploy/monitoring/docker-compose.yml config --quiet
docker run --rm --entrypoint promtool \
  -v "$PWD/deploy/monitoring/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.13.1 check config /etc/prometheus/prometheus.yml
```

운영 host에서 `docker compose config`는 interpolation된 secret을 출력할 수 있으므로 쓰지 않고
`docker compose config --quiet`만 사용한다. volume 삭제가 필요한 별도 승인 없이
`docker compose down -v`를 실행하지 않는다.
