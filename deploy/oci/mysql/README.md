# OCI MySQL: prod replica · 백업 · 복구

OCI host에서 compose 서비스 `mysql`(`mysql:8.0.46`, digest 고정)로 도는 MySQL을 prod(AWS) MySQL의
replica로 따라가게 하고, 그 DB를 R2에 백업·복구하는 구성이다. 실제 전환(쓰기 정지·승격·앱 DB 주소 변경)은
이 문서의 범위가 아니다.

- 복제: binlog **위치 기반**(prod는 GTID off). prod는 x86_64, OCI는 arm64라 clone plugin을 쓸 수 없어
  논리 dump로 시드한 뒤 그 좌표부터 복제한다. 경로는 Tailscale, 계정 단위 TLS.
- 백업: 매일 04:15 KST dump + binlog 1분 반출 → R2 전용 버킷. 보관은 R2 lifecycle(`mysqldump/` 30일,
  `binlog/` 35일). 단일 host라 **R2가 유일한 오프호스트 사본**이고, host 전손 때 손실 창은 binlog timer 주기(1분)다.
- 지표: 스크립트가 `/var/lib/laimory/textfile/*.prom`에 쓰고 Alloy가 Grafana Cloud로 보낸다.

| 파일 | host 위치 | 역할 |
|---|---|---|
| `laimory.cnf` | `/opt/laimory/mysql/conf.d/laimory.cnf` | 서버 설정(server_id 2, ROW binlog, `log_replica_updates`, 복제 필터, read-only) |
| `scripts/configure-oci-mysql-local-users.sh` | 1회 실행 | OCI 로컬 `laimory_backup`·`laimory_monitor` 생성, 비밀번호를 설정 파일에 기록 |
| `scripts/configure-mysql-replication-user.sh` | prod host에서 1회 실행 | prod `laimory_repl`·`laimory_seed` 생성 / 시드 후 `laimory_seed` 삭제 |
| `scripts/backup-oci-mysql-dump.sh` | `/usr/local/sbin/backup-oci-mysql-dump` | dump → 검증 → R2 |
| `scripts/upload-oci-binlog.sh` | `/usr/local/sbin/upload-oci-binlog` | binlog 완결본·tail → R2 |
| `scripts/collect-oci-mysql-replica.sh` | `/usr/local/sbin/collect-oci-mysql-replica` | 복제 스레드·지연 지표(복제 기간 전용) |
| `systemd/*` | `/etc/systemd/system/` | 위 세 스크립트의 service·timer |

## compose 서비스

```yaml
  mysql:
    image: mysql:8.0.46@sha256:<arm64 digest>
    user: "<laimory-mysql uid>:<gid>"   # 이미지 기본 uid 999는 OCI Ubuntu에서 Oracle Cloud Agent 사용자와 겹친다
    environment:
      MYSQL_ROOT_PASSWORD_FILE: /run/secrets/mysql-root-password
    volumes:
      - ./mysql/data:/var/lib/mysql
      - ./mysql/conf.d/laimory.cnf:/etc/mysql/conf.d/laimory.cnf:ro
      - ./secrets/mysql-root-password:/run/secrets/mysql-root-password:ro
    oom_score_adj: -500        # 메모리 부족 시 커널이 mysqld보다 api를 먼저 고르게
    stop_grace_period: 120s    # InnoDB 정상 종료 시간
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "--protocol=socket"]
    restart: unless-stopped
    networks: [laimory]        # 호스트 포트 publish 없음 — api는 compose 네트워크에서 mysql:3306
```

- 이미지가 만드는 `root@'%'`는 지운다(`SET sql_log_bin=0; DROP USER 'root'@'%';`). root는 컨테이너 안 localhost만 쓴다.
- **빈 datadir 초기화 때는 `super_read_only`를 끈 채로 띄운다.** 이미지 entrypoint의 초기화 SQL이 read-only에
  막힌다. 초기화가 끝나면 override 없이 다시 띄운다.
- compose 네트워크 정의를 바꾼 뒤 `up -d`만 하면 일부 컨테이너가 서비스 이름 alias 없이 다시 붙는다.
  `mysql`만 추가·교체할 때는 `docker compose up -d mysql`로 다른 서비스를 재생성하지 않는다.

## host 준비

```bash
# mysqlbinlog(복구 재생용)은 mysql-server-core-8.0이 소유한다. 공식 mysql 이미지에는 없다.
# 패키지에 unit이 없어 데몬은 뜨지 않는다.
sudo NEEDRESTART_MODE=l apt-get install -y --no-install-recommends mysql-server-core-8.0 rclone

sudo install -m 0755 scripts/backup-oci-mysql-dump.sh /usr/local/sbin/backup-oci-mysql-dump
sudo install -m 0755 scripts/upload-oci-binlog.sh /usr/local/sbin/upload-oci-binlog
sudo install -m 0755 scripts/collect-oci-mysql-replica.sh /usr/local/sbin/collect-oci-mysql-replica
sudo install -m 0644 systemd/* /etc/systemd/system/
sudo systemctl daemon-reload
```

### 설정 파일

`/etc/laimory/`는 root 0700, 아래 두 파일은 root 0600이다. 값(버킷 이름·계정 ID·키)은 저장소에 두지 않는다.

`/etc/laimory/oci-mysql.env`

```sh
MYSQL_CONTAINER=<mysql 컨테이너 이름>
MYSQL_DATADIR=<datadir의 host 경로>
RCLONE_CONFIG=/etc/laimory/rclone-r2-backup.conf
R2_REMOTE=<rclone remote>:<버킷>
R2_PREFIX=                      # 운영은 비움. 리허설은 rehearsal/
# 아래 네 줄은 configure-oci-mysql-local-users.sh가 쓴다.
MYSQL_BACKUP_USER=laimory_backup
MYSQL_BACKUP_PASSWORD=...
MYSQL_MONITOR_USER=laimory_monitor
MYSQL_MONITOR_PASSWORD=...
```

`/etc/laimory/rclone-r2-backup.conf` — R2 토큰은 **이 버킷 한정 Object Read & Write**로 대시보드에서 발급한다.

```ini
[<remote>]
type = s3
provider = Cloudflare
access_key_id = ...
secret_access_key = ...
endpoint = https://<계정 ID>.r2.cloudflarestorage.com
acl = private
no_check_bucket = true        # 버킷 한정 토큰은 버킷 생성 확인 권한이 없다
```

## 복제 시작 (시드)

1. prod host 대화형 세션의 root 셸: `bash configure-mysql-replication-user.sh create <OCI 주소>`.
   비밀번호 두 개를 입력하고, 같은 값을 OCI 쪽 0600 파일로만 옮긴다(send-command·대화·로그에 남기지 않는다).
2. OCI 로컬 계정: `sudo bash scripts/configure-oci-mysql-local-users.sh`.
3. 저트래픽 시각에 시드 dump. 시작 순간 짧은 전역 잠금(FTWRL)을 잡으므로 직전에 prod
   `SHOW PROCESSLIST`로 긴 쿼리가 없는지 본다. host에는 `mysqldump`가 없으므로(`mysql-server-core-8.0`에
   없다) mysql 컨테이너 안의 클라이언트로 뜬다. 비밀번호는 `-e MYSQL_PWD`로 호출자 env에서 넘긴다.

   ```bash
   docker exec -e MYSQL_PWD <mysql 컨테이너> mysqldump --host=<prod 주소> --user=laimory_seed --ssl-mode=REQUIRED \
     --single-transaction --source-data=2 --no-tablespaces --databases laimory | gzip > seed.sql.gz
   ```

4. 적재: `SET GLOBAL super_read_only=OFF` → `zcat seed.sql.gz | mysql` → 테이블 수·Flyway 최신 버전 확인 →
   `SET GLOBAL super_read_only=ON`.
5. 복제 시작(좌표는 dump 앞부분의 `CHANGE MASTER TO` 주석). 필터는 `laimory.cnf`의 `replicate_wild_do_table`이
   이미 건다 — `CHANGE REPLICATION FILTER`로 건 필터는 재시작하면 사라진다.

   ```sql
   CHANGE REPLICATION SOURCE TO
     SOURCE_HOST = '<prod 주소>', SOURCE_PORT = 3306,
     SOURCE_USER = 'laimory_repl', SOURCE_PASSWORD = '<…>',
     SOURCE_LOG_FILE = '<dump 좌표>', SOURCE_LOG_POS = <dump 좌표>,
     SOURCE_SSL = 1, SOURCE_CONNECT_RETRY = 10, SOURCE_RETRY_COUNT = 86400,
     SOURCE_HEARTBEAT_PERIOD = 30;
   START REPLICA;
   ```

   `SOURCE_PASSWORD`는 `mysql.slave_master_info`에 평문으로 남는다(재시작 후 자동 재개를 위한 MySQL 기본 동작).
   datadir은 0700이고, 전환 때 `RESET REPLICA ALL`로 지운다.
6. prod: `bash configure-mysql-replication-user.sh drop-seed <OCI 주소>`. 이 DROP은 복제로 넘어오지만
   필터가 계정 문장을 건너뛴다.

## 백업

```bash
sudo systemctl enable --now laimory-oci-binlog-upload.timer laimory-oci-mysql-replica-status.timer \
  laimory-oci-mysqldump-backup.timer
sudo systemctl start laimory-oci-mysqldump-backup.service   # 첫 dump를 바로 뜬다
```

- dump는 `--flush-logs`로 매일 binlog를 새 파일로 넘긴다. 이 서버 binlog는 하루 1MB 안팎이라 크기 기준
  회전이 사실상 없어서, 넘기지 않으면 활성 파일이 계속 커지고 로컬 expire(7일)도 동작하지 않는다.
  덤프 좌표는 새 파일의 시작이 된다.
- binlog key는 `binlog/<server_uuid>/<파일>`(완결본), `binlog/<server_uuid>/tail/<파일>`(활성 사본)이다.
  서버가 재생성되면 파일명이 1번부터 다시 쓰이므로 uuid로 세대를 나눈다.
- rclone은 **`--s3-no-head`가 필수**다. Ubuntu rclone 1.60은 업로드 뒤 R2가 준 versionId로 HEAD를
  보내고 R2가 501로 거절한다(업로드는 들어가지만 실패로 보고·재시도한다). 무결성은 PUT의 Content-MD5를
  R2가 검증한다.

## 복구 (PITR)

R2 토큰이 읽기도 되므로 OCI host에서 바로 복구할 수 있다. **R2에서 받은 파일만** 쓴다.

1. 최신 dump를 받아 uuid와 좌표를 읽는다. uuid가 받을 binlog 세대다.

   ```bash
   zcat laimory-<stamp>.sql.gz | awk 'NR<=50 && /laimory_source_uuid|CHANGE MASTER TO/'
   ```

2. `binlog/<uuid>/`에서 좌표의 파일부터 이후 전부를 받는다. `tail/`의 사본은 **정규 key에 같은 이름이
   없을 때만, 항상 마지막 파일로만** 쓴다.
3. 격리된 임시 mysqld에 적재·재생한다. 같은 이미지, `--network none`, 임시 datadir, 같은 `laimory.cnf`에
   `--super-read-only=OFF --read-only=OFF --server-id=<운영과 다른 값> --skip-replica-start`를 덧붙여 띄운다.
   dump가 `--databases laimory`라 `USE laimory`를 포함하므로, 운영 중인 다른 서버에 적재하면 그쪽을 덮어쓴다.

   ```bash
   set -o pipefail
   zcat laimory-<stamp>.sql.gz | docker exec -i <복구 컨테이너> mysql
   mysqlbinlog --force-if-open --start-position=<pos> <첫 파일> <나머지…> [--stop-position=<pos>] \
     | docker exec -i <복구 컨테이너> mysql
   ```

   - `--start-position`은 **명령줄의 첫 파일에만** 적용된다. 파일 순서를 틀리면 조용히 잘못 복원된다.
   - `--force-if-open`: 서버가 쓰던 활성 파일의 사본(tail)은 IN_USE 표시가 있고 마지막 이벤트가 잘릴 수 있다.
   - `pipefail`이 없으면 mysqlbinlog 실패가 `mysql`의 종료 코드에 가려 조용히 부분 복원된다.
   - 논리 사고를 되감을 때는 `--stop-position`/`--stop-datetime`으로 사고 직전에서 끊는다.
4. 검증: 테이블별 `COUNT(*)`·`CHECKSUM TABLE`을 기준 시점 값과 대조한다(같은 이미지라 CHECKSUM 비교가 유효하다).

## 정리

timer 3개 disable → unit·`/usr/local/sbin` 스크립트·`/var/lib/laimory/oci-mysql`·`/var/lib/laimory/textfile/laimory_oci_*.prom`
삭제 → 설정 파일 2개 삭제 → 대시보드에서 R2 토큰 폐기. prod에서는 `laimory_repl` DROP.
