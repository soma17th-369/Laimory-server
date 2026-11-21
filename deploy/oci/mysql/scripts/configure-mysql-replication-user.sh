#!/bin/bash
# prod MySQL host(AWS)의 root 셸(대화형 SSM 세션)에서 실행한다. root는 socket 인증이라 비밀번호 없이 붙는다.
# OCI mysql이 prod를 따라가는 데 필요한 두 계정을 만들거나, 시드가 끝난 뒤 시드 계정을 지운다.
#
#   create <oci-address>     laimory_repl(상시)·laimory_seed(시드 1회용) 생성
#   drop-seed <oci-address>  laimory_seed 삭제
#
# 두 계정 모두 전역 권한이 필요하다(REPLICATION SLAVE/CLIENT·RELOAD는 DB 범위로 좁힐 수 없다).
# 대신 접속 주소를 OCI host 한 곳으로 고정하고 TLS를 계정 단위로 강제한다
# (서버 전역 require_secure_transport는 OFF다). 시드 계정을 따로 두는 이유는 dump의 일관 좌표에
# 필요한 RELOAD(짧은 전역 잠금)와 테이블 SELECT를 상시 복제 계정에 주지 않기 위해서다.
#
# 비밀번호는 대화형으로 받는다(SSM send-command 인자로 보내지 않는다). 리허설처럼 터미널이 없는
# 실행에서는 LAIMORY_REPL_PASSWORD·LAIMORY_SEED_PASSWORD 환경변수로 넘길 수 있다.
set -euo pipefail

readonly REPL_USER=laimory_repl
readonly SEED_USER=laimory_seed
readonly PASSWORD_PATTERN='^[A-Za-z0-9!#%^*_+=:.,~@-]{16,128}$'

usage() {
  echo "usage: $0 create|drop-seed <oci-address>" >&2
  echo "  oci-address: exact IPv4 address the OCI host connects from ('%' is rejected)" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
ACTION=$1
CLIENT_HOST=$2
# 와일드카드 호스트는 받지 않는다 — 복제 계정은 서버 전체의 모든 row 변경을 읽을 수 있다.
[[ $CLIENT_HOST =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { echo "oci-address must be an exact IPv4 address" >&2; exit 1; }

sql() { mysql --batch --skip-column-names "$@"; }

read_password() {
  local name=$1 env_name=$2 value
  value=${!env_name:-}
  if [[ -z $value ]]; then
    read -rsp "$name password: " value
    echo >&2
  fi
  if ! [[ $value =~ $PASSWORD_PATTERN ]]; then
    echo "$name password must be 16-128 safe characters without quotes, spaces, dollar signs, or backslashes" >&2
    exit 1
  fi
  printf '%s' "$value"
}

ssl_type() {
  sql -e "SELECT ssl_type FROM mysql.user WHERE user='$1' AND host='${CLIENT_HOST}'"
}

case $ACTION in
  create)
    repl_password=$(read_password "$REPL_USER" LAIMORY_REPL_PASSWORD)
    seed_password=$(read_password "$SEED_USER" LAIMORY_SEED_PASSWORD)
    sql <<SQL
CREATE USER IF NOT EXISTS '${REPL_USER}'@'${CLIENT_HOST}' REQUIRE SSL WITH MAX_USER_CONNECTIONS 2;
ALTER USER '${REPL_USER}'@'${CLIENT_HOST}' IDENTIFIED BY '${repl_password}'
  REQUIRE SSL WITH MAX_USER_CONNECTIONS 2 ACCOUNT UNLOCK;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${REPL_USER}'@'${CLIENT_HOST}';
-- REPLICATION SLAVE: replica IO 스레드의 binlog 수신. REPLICATION CLIENT: 상태 조회.
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '${REPL_USER}'@'${CLIENT_HOST}';

CREATE USER IF NOT EXISTS '${SEED_USER}'@'${CLIENT_HOST}' REQUIRE SSL WITH MAX_USER_CONNECTIONS 1;
ALTER USER '${SEED_USER}'@'${CLIENT_HOST}' IDENTIFIED BY '${seed_password}'
  REQUIRE SSL WITH MAX_USER_CONNECTIONS 1 ACCOUNT UNLOCK;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${SEED_USER}'@'${CLIENT_HOST}';
-- mysqldump --single-transaction --source-data=2 --no-tablespaces의 최소 권한.
-- RELOAD: 좌표를 읽는 순간의 FTWRL, REPLICATION CLIENT: SHOW MASTER STATUS.
GRANT SELECT ON laimory.* TO '${SEED_USER}'@'${CLIENT_HOST}';
GRANT RELOAD, REPLICATION CLIENT ON *.* TO '${SEED_USER}'@'${CLIENT_HOST}';
SQL
    unset repl_password seed_password

    repl_grants=$(sql -e "SHOW GRANTS FOR '${REPL_USER}'@'${CLIENT_HOST}'")
    seed_grants=$(sql -e "SHOW GRANTS FOR '${SEED_USER}'@'${CLIENT_HOST}'")
    if [[ $repl_grants != *"REPLICATION SLAVE, REPLICATION CLIENT"* ]] ||
      [[ $repl_grants == *"SELECT"* || $repl_grants == *"RELOAD"* || $repl_grants == *"SUPER"* ]] ||
      [[ $seed_grants != *"RELOAD, REPLICATION CLIENT"* ]] ||
      [[ $seed_grants != *"GRANT SELECT ON \`laimory\`.*"* ]] ||
      [[ $seed_grants == *"INSERT"* || $seed_grants == *"REPLICATION SLAVE"* || $seed_grants == *"SUPER"* ]]; then
      echo "unexpected replication or seed grants" >&2
      exit 1
    fi
    # REQUIRE SSL은 SHOW GRANTS에 나오지 않으므로 계정 메타데이터에서 직접 확인한다.
    [[ $(ssl_type "$REPL_USER") == ANY && $(ssl_type "$SEED_USER") == ANY ]] ||
      { echo "accounts are not TLS-restricted" >&2; exit 1; }
    echo "ready: ${REPL_USER} (REPLICATION SLAVE, CLIENT) and ${SEED_USER} (SELECT laimory.*, RELOAD, REPLICATION CLIENT) from ${CLIENT_HOST} over TLS"
    ;;
  drop-seed)
    sql -e "DROP USER IF EXISTS '${SEED_USER}'@'${CLIENT_HOST}'"
    [[ -z $(sql -e "SELECT 1 FROM mysql.user WHERE user='${SEED_USER}' AND host='${CLIENT_HOST}'") ]] ||
      { echo "seed account still exists" >&2; exit 1; }
    echo "dropped: ${SEED_USER}@${CLIENT_HOST}"
    ;;
  *)
    usage
    ;;
esac
