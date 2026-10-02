# prod host 보안 패치와 서비스 재시작

prod host 3대(`laimory-prod-mysql-01`, `laimory-prod-was-01`, `laimory-prod-was-02`)의 OS 보안 패치는
자동으로 설치하되, 서비스 재시작은 자동으로 하지 않고 계획된 시간에 사람이 한다.

## 배경

2026-09-30 15:58 KST와 10-01 15:10 KST에 unattended-upgrades가 libevent와 openssl을 갱신했다.
직후 needrestart가 mysqld를 자동으로 재시작했고(같은 순간 systemd-networkd, resolved, journald도 재시작),
두 WAS의 DB 연결이 약 12초 동안 끊겼다
(`/readyz` 503, prod 5xx critical 경보). 두 WAS가 같은 DB를 쓰므로 ALB가 다른 대상으로 넘길 수 없다.
그 시각에 실사용자 트래픽이 없었을 뿐, 트래픽이 있었다면 DB를 쓰는 모든 요청이 실패했다.
자동 갱신은 매일 15:00~16:00 KST 사이 무작위 시각(`apt-daily-upgrade.timer`, 06:00 UTC +
최대 60분)에 실행된다.

## 현재 host 설정

| 파일 | 대상 | 내용 | 이유 |
|---|---|---|---|
| `/etc/needrestart/conf.d/90-laimory-list-only.conf` | 3대 | `$nrconf{restart} = 'l';` | 라이브러리가 갱신되어도 재시작할 서비스를 나열만 한다 |
| `/etc/apt/apt.conf.d/51laimory-unattended-blacklist` | mysql-01 | `Unattended-Upgrade::Package-Blacklist { "mysql-"; };` | mysql 패키지는 설치 과정(postinst)에서 needrestart와 상관없이 mysqld를 재시작한다 |

- Ubuntu needrestart(3.6)는 APT hook(`-m u`)으로 실행될 때 `restart` 값을 비운 뒤 설정 파일(conf.d 포함)을
  읽는다. 명시값이 있으면 그 값을 쓰고, 없으면 `'a'`(자동 재시작)로 동작한다. 이 기본값 `'a'`가 사고의 원인이었다.
- 보안 패치 설치(`APT::Periodic::Unattended-Upgrade "1"`)는 그대로 켜져 있다.
- docker-ce와 containerd.io는 Docker 저장소에서 받은 패키지다. unattended-upgrades가 허용하는
  origin(Ubuntu security)이 아니므로 자동으로 갱신되지 않는다.
- 커널은 자동으로 재부팅하지 않는다(`Unattended-Upgrade::Automatic-Reboot` 기본값 false).
- 되돌릴 때는 두 파일을 지운다. 다음 자동 갱신부터 이전 동작(자동 재시작)으로 돌아간다.

대가: 패치된 라이브러리는 해당 서비스를 재시작하기 전까지 실행 중인 프로세스에 반영되지 않는다.
mysql 보안 패치는 수동으로 설치하기 전까지 적용되지 않는다. 그래서 아래 정기 절차가 필요하다.

## 정기 재시작 절차

주 1회, 그리고 openssl처럼 중요한 보안 공지가 나오면 바로 수행한다.

### 1. 대상 확인(비변경)

3대에 SSM send-command로 다음을 실행한다.

```bash
sudo needrestart -b
apt list --upgradable 2>/dev/null | grep '^mysql-'   # mysql-01만
```

- `NEEDRESTART-SVC`: 재시작이 필요한 서비스
- `NEEDRESTART-KSTA`: `1`이면 최신 커널, `2`나 `3`이면 재부팅이 필요하다
- `dbus`, `systemd-logind`, `getty@*`, `unattended-upgrades`는 needrestart 기본 설정상 자동 재시작에서
  빠지므로 목록에 계속 남는다. 이 서비스들은 재부팅할 때 함께 반영된다.

### 2. 시간 선택

트래픽이 적고 아래 배치(KST)와 겹치지 않는 시간을 고른다. 기본은 05:00~06:00 KST다.

| 시각 | 작업 |
|---|---|
| 02:30 | 탈퇴 계정 데이터 삭제 |
| 03:00 | 타임라인 사진 삭제 job |
| 03:30 | orphan Item sweep |
| 04:00 | draft cleanup |
| 04:15 | prod mysqldump 백업(monitoring host) |
| 04:30 | User Memory 갱신 |
| 21:00 | 일일 리마인더 |

mysql 재시작 동안 prod 5xx 경보가 울릴 수 있다. 계획된 작업이면 그 시간대만 Grafana silence를 건다.

### 3. 실행

**mysql-01** — 수 초 동안 DB를 쓰는 모든 요청이 실패한다.

1. 보류된 mysql 갱신이 있으면 먼저 설치한다: `sudo apt-get install --only-upgrade <패키지>`
   (설치 과정에서 mysqld가 재시작된다).
2. 남은 서비스를 재시작한다: `sudo needrestart -r a`. 커널 갱신이 남아 있으면 대신 `sudo reboot`한다.

**WAS** — 한 대씩 진행한다.

- 서비스 재시작만 필요하면 `sudo needrestart -r a`로 끝난다. 앱 컨테이너는 dockerd가 관리하므로,
  dockerd가 목록에 없으면 앱에는 영향이 없다.
- 재부팅이 필요하면 target group에서 해당 WAS를 deregister하고 draining이 끝난 뒤 `sudo reboot`한다.
  컨테이너는 `--restart always`로 다시 뜬다. 다시 register하고 healthy를 확인한 다음 다른 한 대를 진행한다.

### 4. 확인

- 3대 모두 `sudo needrestart -b`의 `NEEDRESTART-SVC`가 위의 기본 제외 서비스만 남았는지 확인한다.
- Grafana에서 두 WAS의 `/readyz`가 200으로 돌아왔는지 확인한다.
- monitoring host의 `laimory-binlog-stream`이 `active`인지 확인한다. mysqld가 재시작되면
  `Restart=always`로 10초 뒤 다시 연결된다(2026-10-01 실측).
