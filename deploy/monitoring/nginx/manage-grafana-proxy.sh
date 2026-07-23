#!/usr/bin/env bash
set -euo pipefail

EXTRA_SNIPPET=/etc/nginx/snippets/laimory-extra.conf
GRAFANA_SNIPPET=/etc/nginx/snippets/laimory-grafana.conf
GRAFANA_INCLUDE='include /etc/nginx/snippets/laimory-grafana.conf;'
BACKUP_DIR=/var/backups/laimory-nginx

usage() {
  echo "usage: $0 enable <monitoring-private-ip> <allowed-cidr>... | disable" >&2
  exit 2
}

if [[ ${EUID} -ne 0 ]]; then
  echo "run as root" >&2
  exit 1
fi

action=${1:-}
[[ $action == "enable" || $action == "disable" ]] || usage

install -d -m 0755 /etc/nginx/snippets "$BACKUP_DIR"
[[ -f $EXTRA_SNIPPET ]] || install -m 0644 /dev/null "$EXTRA_SNIPPET"

stamp=$(date -u +%Y%m%dT%H%M%S%N)
extra_backup="$BACKUP_DIR/laimory-extra.conf.$stamp"
cp -a "$EXTRA_SNIPPET" "$extra_backup"

grafana_backup=""
if [[ -f $GRAFANA_SNIPPET ]]; then
  grafana_backup="$BACKUP_DIR/laimory-grafana.conf.$stamp"
  cp -a "$GRAFANA_SNIPPET" "$grafana_backup"
fi

restore_previous() {
  set +e
  cp -a "$extra_backup" "$EXTRA_SNIPPET"
  if [[ -n $grafana_backup ]]; then
    cp -a "$grafana_backup" "$GRAFANA_SNIPPET"
  else
    rm -f "$GRAFANA_SNIPPET"
  fi
  nginx -t
  systemctl reload nginx
}

if [[ $action == "disable" ]]; then
  sed -i '\|^[[:space:]]*include /etc/nginx/snippets/laimory-grafana\.conf;[[:space:]]*$|d' "$EXTRA_SNIPPET"
  if ! nginx -t || ! systemctl reload nginx; then
    restore_previous
    echo "Grafana proxy disable failed; previous nginx snippet restored" >&2
    exit 1
  fi
  echo "Grafana proxy disabled; Kibana and other locations were preserved"
  exit 0
fi

[[ $# -ge 3 ]] || usage
monitoring_ip=$2
shift 2
allowed_cidrs=("$@")

python3 - "$monitoring_ip" "${allowed_cidrs[@]}" <<'PY'
import ipaddress
import sys

address = ipaddress.ip_address(sys.argv[1])
if address.version != 4 or not address.is_private:
    raise SystemExit("monitoring address must be a private IPv4 address")

for value in sys.argv[2:]:
    network = ipaddress.ip_network(value, strict=False)
    if network.version != 4:
        raise SystemExit(f"allowlist entry must be IPv4 CIDR: {value}")
PY

snippet_tmp=$(mktemp /etc/nginx/snippets/.laimory-grafana.XXXXXX)
cleanup() {
  rm -f "$snippet_tmp"
}
trap cleanup EXIT

write_location() {
  local matcher=$1

  printf 'location %s {\n' "$matcher"
  local cidr
  for cidr in "${allowed_cidrs[@]}"; do
    printf '    allow %s;\n' "$cidr"
  done
  printf '%s\n' \
    '    deny all;' \
    "    proxy_pass http://$monitoring_ip:3000;" \
    '    proxy_http_version 1.1;' \
    '    proxy_set_header Host $host;' \
    '    proxy_set_header X-Real-IP $remote_addr;' \
    '    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;' \
    '    proxy_set_header X-Forwarded-Proto $scheme;' \
    '    proxy_set_header X-Forwarded-Prefix /grafana;' \
    '    proxy_set_header Upgrade $http_upgrade;' \
    '    proxy_set_header Connection "upgrade";' \
    '}'
}

{
  write_location '= /grafana'
  echo
  write_location '/grafana/'
} > "$snippet_tmp"

install -m 0644 "$snippet_tmp" "$GRAFANA_SNIPPET"
if ! grep -Fqx "$GRAFANA_INCLUDE" "$EXTRA_SNIPPET"; then
  printf '\n%s\n' "$GRAFANA_INCLUDE" >> "$EXTRA_SNIPPET"
fi

if ! nginx -t || ! systemctl reload nginx; then
  restore_previous
  echo "Grafana proxy enable failed; previous nginx snippets restored" >&2
  exit 1
fi

echo "Grafana proxy enabled with ${#allowed_cidrs[@]} allowlist entry or entries"
