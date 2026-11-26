# OCI MySQL: prod primary · 백업 · 복구

OCI host에서 compose 서비스 `mysql`(`mysql:8.0.46`, digest 고정)로 도는 **prod primary** MySQL과 그 R2
백업·복구 구성이다. 2026-11-23에 AWS MySQL의 replica에서 승격됐다(binlog 위치 복제 → 쓰기 정지 → 대조 → 승격).
AWS MySQL은 읽기 전용으로 남아 있고 쓰지 않는다.

- 클라이언트: API는 아직 AWS WAS 2대(`laimory.app` → ALB)에서 돌고 Tailscale로 이 DB의 3306에 붙는다. OCI api는
  compose 네트워크에서 `mysql:3306`. WAS ↔ OCI는 DERP 릴레이 경유라 쿼리마다 약 40ms가 더해진다.
- 백업: 매일 04:15 KST dump + binlog 1분 반출 → R2 전용 버킷. 보관은 R2 lifecycle(`mysqldump/` 30일,
  `binlog/` 35일). 단일 host라 **R2가 유일한 오프호스트 사본**이고, host 전손 때 손실 창은 binlog timer 주기(1분)다.
- 지표: 스크립트가 `/var/lib/laimory/textfile/*.prom`에 쓰고 Alloy가 Grafana Cloud로 보낸다.

| 파일 | host 위치 | 역할 |
|---|---|---|
| `laimory.cnf` | `/opt/laimory/mysql/conf.d/laimory.cnf` | 서버 설정(server_id 2, ROW binlog, 로컬 binlog 7일) |
| `scripts/configure-oci-mysql-local-users.sh` | 1회 실행 | OCI 로컬 `laimory_backup` 생성, 비밀번호를 설정 파일에 기록 |
| `scripts/backup-oci-mysql-dump.sh` | `/usr/local/sbin/backup-oci-mysql-dump` | dump → 검증 → R2 |
| `scripts/upload-oci-binlog.sh` | `/usr/local/sbin/upload-oci-binlog` | binlog 완결본·tail → R2 |
| `systemd/*` | `/etc/systemd/system/` | 위 두 백업 스크립트의 service·timer |

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
    # AWS WAS가 Tailscale로 접속한다. 인터넷은 OCI 보안 목록(80·443만)이 막고, 계정은 WAS Tailscale IP로 제한한다.
    ports: ["3306:3306"]
    oom_score_adj: -500        # 메모리 부족 시 커널이 mysqld보다 api를 먼저 고르게
    stop_grace_period: 120s    # InnoDB 정상 종료 시간
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "--protocol=socket"]
    restart: unless-stopped
    networks: [laimory]        # OCI api는 compose 네트워크에서 mysql:3306
```

- 이미지가 만드는 `root@'%'`는 지운다(`SET sql_log_bin=0; DROP USER 'root'@'%';`). root는 컨테이너 안 localhost만 쓴다.
- 3306은 Tailscale IP가 아니라 모든 인터페이스에 게시한다. Tailscale IP에만 bind하면 재부팅 때 docker가
  tailscaled보다 먼저 뜨는 순간 bind가 실패해 mysql이 기동하지 않는다.
- compose 네트워크 정의를 바꾼 뒤 `up -d`만 하면 일부 컨테이너가 서비스 이름 alias 없이 다시 붙는다.
  `mysql`만 추가·교체할 때는 `docker compose up -d mysql`로 다른 서비스를 재생성하지 않는다.

## host 준비

```bash
# mysqlbinlog(복구 재생용)은 mysql-server-core-8.0이 소유한다. 공식 mysql 이미지에는 없다.
# 패키지에 unit이 없어 데몬은 뜨지 않는다.
sudo NEEDRESTART_MODE=l apt-get install -y --no-install-recommends mysql-server-core-8.0 rclone

sudo install -m 0755 scripts/backup-oci-mysql-dump.sh /usr/local/sbin/backup-oci-mysql-dump
sudo install -m 0755 scripts/upload-oci-binlog.sh /usr/local/sbin/upload-oci-binlog
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
# 아래 두 줄은 configure-oci-mysql-local-users.sh가 쓴다.
MYSQL_BACKUP_USER=laimory_backup
MYSQL_BACKUP_PASSWORD=...
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

## 앱 접속

| 계정 | host | 권한 | TLS |
|---|---|---|---|
| `laimory`(앱 DataSource) | WAS별 Tailscale IP, compose 대역(OCI api) | `laimory.*` DML | WAS는 `REQUIRE SSL` |
| `laimory_flyway`(migration) | WAS별 Tailscale IP, compose 대역(OCI api — 구조 전환 #669 때 생성) | `laimory.*` DML + `CREATE, DROP, REFERENCES, INDEX, ALTER` | `REQUIRE SSL` |

- 권한은 AWS MySQL 시절과 같다. 비밀번호도 같아서 WAS·OCI api는 `.env`의 `DB_HOST`만 바꿨다. 계정은 원본의
  `SHOW CREATE USER`(`print_identified_with_as_hex=ON`) 해시를 옮겨 만들었다 — 비밀번호 원문을 꺼내지 않는다.
- **OCI의 Tailscale은 `--snat-subnet-routes=false`여야 한다.** 기본값(true)은 tailscale0으로 들어와 docker로 포워딩되는
  패킷을 MASQUERADE해서 MySQL이 WAS를 compose 브리지 게이트웨이 주소로 본다. 그러면 Tailscale IP 단위 계정이
  맞지 않아 Flyway 계정이 1045로 거절되고, 같은 비밀번호인 compose 대역 계정에 엉뚱하게 붙는다.
- Tailscale ACL은 WAS 태그 → OCI `tcp:3306`만 허용한다. WAS를 새로 만들면 Tailscale 가입·계정 host·ACL을 함께 맞춘다.
- JDBC URL은 포트 3306이 고정이고 sslMode를 지정하지 않아(PREFERRED) TLS로 붙는다.

## 백업

```bash
sudo systemctl enable --now laimory-oci-binlog-upload.timer laimory-oci-mysqldump-backup.timer
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
   `--server-id=<운영과 다른 값>`을 덧붙여 띄운다.
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

timer 2개 disable → unit·`/usr/local/sbin` 스크립트·`/var/lib/laimory/oci-mysql`·`/var/lib/laimory/textfile/laimory_oci_*.prom`
삭제 → 설정 파일 2개 삭제 → 대시보드에서 R2 토큰 폐기.
