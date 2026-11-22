# OCI prod 경보 (Grafana Cloud Free)

OCI 단일 호스트(api·ai·nginx·mysql)의 경보다. AWS의 `deploy/monitoring` 스택은 OCI로 가는 경로가 없어
이 호스트를 보지 못한다.

```text
OCI host ── alloy(compose 서비스) ──remote_write──▶ Grafana Cloud(Prometheus) ──경보──▶ Discord
   │  api:9090 actuator · ai:8080/health · 호스트 /proc·/sys · textfile(MySQL 복제·백업)
Synthetic Monitoring(서울 probe) ──▶ https://api.laimory.app/readyz ─────────┘
```

| 파일 | 내용 |
|---|---|
| `../alloy/config.alloy` | 수집 대상과 내보낼 지표 목록(keep 규칙) |
| `alert-rules.json` | 경보 16개(규칙 원본) |
| `notification.json` | Discord 메시지 템플릿·contact point 이름·알림 정책 |
| `apply-alerting.py` | 위 JSON을 Grafana Cloud API로 적용·상태 조회(관리자 Mac에서 실행) |

Grafana Cloud는 파일 provisioning을 지원하지 않는다. 규칙은 이 JSON이 원본이고 `apply-alerting.py apply`가
덮어쓴다. API로 만든 규칙은 UI에서 편집이 잠기므로 JSON을 고쳐 다시 apply한다.

## 무료 한도 규칙 (과금 0)

Free 계정만 쓴다. 공식 문서는 Free 가입에 카드가 필요 없다고만 하고, 한도를 넘었을 때 거절인지 과금인지는
밝히지 않는다. 그래서 결제 수단을 두지 않고 사용량을 설계로 묶는다.

| 항목 | Free 한도 | 사용량 | 지키는 방법 |
|---|---|---|---|
| 결제 수단 | — | 없음 | 카드·결제 수단을 등록하지 않는다. Pro 업그레이드·체험 전환을 누르지 않는다 |
| 지표 active series | 10,000 | 약 430 (HTTP histogram이 대부분) | `config.alloy`의 keep 규칙이 경보 식에 쓰는 지표 이름만 내보낸다 |
| Synthetic API test | 월 100,000회 | 월 43,200회 | check 1개 × 서울 probe 1곳 × 1분. check·probe를 늘리거나 주기를 줄이지 않는다 |
| 로그·trace·profile | 각각 | 0 | Alloy에 로그·trace 파이프라인이 없다. 다른 Cloud 기능을 켜지 않는다 |

경보를 추가하면서 새 지표를 쓰면 keep 정규식에도 넣는다. 넣지 않은 지표는 Cloud에 없어서 그 규칙은
NoData가 된다.

## 1. Grafana Cloud 준비 (사람이 UI에서)

1. Free로 가입하고 stack을 만든다. 리전은 일본(AWS Tokyo)이다. 생성 뒤에는 바꿀 수 없다.
   stack 주소(`https://<stack>.grafana.net`)는 저장소·이슈·PR에 적지 않는다.
2. Cloud Portal → Access Policies → New access policy로 Alloy용 policy를 만든다. scope는 metrics Write
   하나만 주고 Add token으로 token을 만든다. Prometheus 상세 페이지의 "Generate now"는 **읽기 전용**
   policy로 token을 만들어 remote_write가 401(`invalid scope requested`)로 거절된다. remote_write URL과
   username(숫자)은 Prometheus 상세 페이지에서 적어 둔다.
3. 규칙 적용용 service account를 만든다(role **Admin**). Editor로는 템플릿·contact point까지만 들어가고
   규칙 그룹 PUT이 403이다 — provisioning API로 규칙을 넣으려면(UI 편집 잠금) `alert.provisioning.provenance:write`가
   필요하고 이 권한은 Admin에만 있다. token은 관리자 Mac의 `~/.config/laimory/grafana-cloud-sa-token`에
   저장한다(`chmod 600`). 적용·시험이 끝나면 token(또는 service account)을 삭제한다.
4. Discord 경보 채널의 webhook URL을 `~/.config/laimory/discord-webhook-url`에 저장한다(`chmod 600`).
5. Synthetic Monitoring을 초기화하고 HTTP check를 하나 만든다.

   | 항목 | 값 |
   |---|---|
   | Job name | `api-laimory-readyz` (경보 식이 이 이름으로 찾는다) |
   | Target | `https://api.laimory.app/readyz` |
   | Probe | Seoul 한 곳 |
   | Frequency | 1분 |
   | 성공 조건 | HTTP 200, TLS 검증 켬 |

   check 자체의 경보 설정은 쓰지 않는다. 경보는 `alert-rules.json`이 소유한다.

## 2. OCI host에 Alloy 띄우기

`/opt/laimory/compose.yml`의 `services:`에 추가한다. 다른 서비스와 `networks:` 정의는 건드리지 않는다
(네트워크 정의를 바꾸면 기존 컨테이너의 서비스 이름 alias가 빠질 수 있다).

```yaml
  # 경보용 지표 수집(Grafana Cloud Free). 호스트 포트 없음, Alloy UI는 컨테이너 루프백에만 열린다.
  alloy:
    image: grafana/alloy:v1.22.0@sha256:9acc2529bc1edb7f5b2bd801dd8334ebc80c158e3a9e5526ecd7c9a273d43189
    command:
      - run
      - --server.http.listen-addr=127.0.0.1:12345
      - --storage.path=/alloy-data
      - --disable-reporting        # Grafana로 가는 사용 통계 끔
      - /etc/alloy/config.alloy
    env_file: ./grafana-cloud.env
    volumes:
      - ./alloy:/etc/alloy:ro
      - ./secrets/grafana-cloud-metrics-token:/run/secrets/grafana-cloud-metrics-token:ro
      - /proc:/host/proc:ro
      - /sys:/host/sys:ro
      - /:/host/root:ro,rslave
    # 전송 버퍼(WAL). 재시작 때 비워져도 된다. 이미지의 /var/lib/alloy는 0770 alloy 소유라
    # cap_drop: ALL인 root가 들어갈 수 없어 밖에 tmpfs를 둔다.
    tmpfs:
      - /alloy-data:size=64m
    cap_drop: [ALL]
    security_opt: [no-new-privileges:true]
    mem_limit: 512m
    restart: unless-stopped
    networks: [laimory]
```

설정 파일 (값은 저장소에 넣지 않는다)

| host 경로 | 권한 | 내용 |
|---|---|---|
| `/opt/laimory/alloy/config.alloy` | 0644 | 저장소 `deploy/oci/alloy/config.alloy` 사본. 디렉터리째 마운트하므로 파일을 바꾼 뒤 `docker compose restart alloy` |
| `/opt/laimory/grafana-cloud.env` | 0600 | `GRAFANA_CLOUD_PROM_URL=<remote_write URL>`, `GRAFANA_CLOUD_PROM_USER=<숫자 username>` |
| `/opt/laimory/secrets/grafana-cloud-metrics-token` | 0400 root | access policy token(개행 없이). 컨테이너 root는 capability가 없어 root 소유여야 읽힌다. 교체는 같은 inode에 덮어쓴다(`sudo sh -c 'cat new > token'`) — Alloy가 요청마다 파일을 읽어 재시작이 필요 없다 |

기동과 확인

```bash
cd /opt/laimory
docker run --rm -v "$PWD/alloy:/etc/alloy:ro" grafana/alloy:v1.22.0@sha256:9acc2529bc1edb7f5b2bd801dd8334ebc80c158e3a9e5526ecd7c9a273d43189 validate /etc/alloy/config.alloy
docker compose up -d alloy
docker compose logs --since 5m alloy | grep -iE 'error|warn' || echo "no errors"
```

Grafana Cloud Explore에서 `count({host="oci-prod"})`가 수백~1,000대이고 `up{host="oci-prod"}`가 job
`node`·`spring_boot`·`ai_health` 모두 1이면 정상이다.

## 3. 경보 적용

```bash
export GRAFANA_CLOUD_URL=https://<stack>.grafana.net
deploy/oci/grafana-cloud/apply-alerting.py apply
deploy/oci/grafana-cloud/apply-alerting.py status
```

`status`에서 16개 모두 `inactive`(Normal)이고 health가 `ok`여야 한다. `nodata`면 지표 이름·label이나 keep
규칙을, `error`면 식을 확인한다.

## 4. 발화·해소 시험

api·ai·nginx는 멈추지 않는다. prod MySQL은 건드리지 않는다.

| 경보 | 방법 |
|---|---|
| OCI MySQL replica stopped | OCI mysql 컨테이너에서 `STOP REPLICA IO_THREAD` → 발화 확인 → `START REPLICA IO_THREAD` → 해소 확인 |
| OCI MySQL binlog upload stale | `systemctl stop laimory-oci-binlog-upload.timer` → 약 16분 뒤 발화 → `start` → 해소 |
| 나머지 | `apply --threshold <uid>=<항상 참인 값>`으로 임시 임계값을 넣어 발화 확인 → `apply`(인자 없이)로 원복 → 해소 |

## 경보별 대응

### mysql-replica

복제 기간(OCI가 prod의 replica인 동안) 전용이다. `laimory.app` 전환 때 이 두 규칙과
`laimory-oci-mysql-replica-status` timer를 함께 지운다.

- OCI mysql 컨테이너에서 `SHOW REPLICA STATUS\G`의 `Last_IO_Error`·`Last_SQL_Error`를 본다.
- IO 스레드 끊김은 `SOURCE_CONNECT_RETRY`로 스스로 재접속한다. 5분 넘게 이어질 때만 발화한다.
- 수집 timer가 멈춰 값이 굳어도(`last_collect`가 5분 넘게 오래됨) 정지로 본다.

### mysql-backup

`deploy/oci/mysql/README.md`의 백업 절과 timer 로그(`journalctl -u laimory-oci-mysqldump-backup`,
`-u laimory-oci-binlog-upload`)를 본다. 전환 뒤에도 유지하는 경보다.

### availability

- OCI API down / AI health: `docker compose ps`, `docker compose logs --since 30m api|ai`.
  지표가 아예 안 들어와도(Alloy·host 정지) 발화한다.
- HTTPS probe: 밖(서울)에서 본 `/readyz`다. readyz는 DB·Redis를 포함하므로 앱이 떠 있어도 의존성이
  끊기면 발화한다. nginx·인증서·DNS도 함께 본다.

### tls

인증서는 OCI host의 `laimory-cert-renew.timer`가 하루 두 번 갱신하고 hook이 nginx용 사본을 만든다.
14일 경보는 몇 주째 갱신이 실패했다는 뜻이다.

### application

AWS `deploy/monitoring`의 앱 경보와 같은 식·임계값이다(5xx 5%, p95 1초, heap 85%, Hikari 90%+대기).
요청이 5분에 20건 미만이면 5xx·p95는 평가하지 않는다.

### host

swap이 없는 호스트다. 메모리 경보가 오면 `docker stats`로 컨테이너별 사용량을 본다. OOM 경보는 한 번만
알린다(`notify_once`).

## 아직 OCI로 옮기지 않은 경보

- lifecycle 3종(크레딧 미정산·미처리 문의·AI 실패): AWS WAS와 같은 prod DB를 세므로 지금 옮기면 같은 알림이
  두 번 온다. `laimory.app`을 OCI로 전환할 때 AWS 쪽과 함께 옮긴다.
- ERROR 로그 경보: OCI 로그는 ELK로 가지 않는다. 로그 저장소(Loki)를 둘 때 옮긴다.
