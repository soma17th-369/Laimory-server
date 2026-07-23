#!/bin/bash
# Installs a pinned node_exporter and binds it to this EC2 instance's private IPv4 address.
set -euo pipefail

NODE_EXPORTER_VERSION="${1:?node_exporter version is required}"
NODE_EXPORTER_SHA256="${2:?node_exporter linux-amd64 sha256 is required}"
VERSION_WITHOUT_V="${NODE_EXPORTER_VERSION#v}"

if [ "$(dpkg --print-architecture)" != "amd64" ]; then
  echo "node_exporter installer only supports amd64" >&2
  exit 1
fi

TOKEN="$(curl -fsS -X PUT \
  -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
  http://169.254.169.254/latest/api/token)"
PRIVATE_IP="$(curl -fsS \
  -H "X-aws-ec2-metadata-token: ${TOKEN}" \
  http://169.254.169.254/latest/meta-data/local-ipv4)"

if ! [[ "$PRIVATE_IP" =~ ^10\.([0-9]{1,3}\.){2}[0-9]{1,3}$ ]]; then
  echo "IMDS did not return a VPC private IPv4 address" >&2
  exit 1
fi

ARCHIVE="node_exporter-${VERSION_WITHOUT_V}.linux-amd64.tar.gz"
DOWNLOAD_URL="https://github.com/prometheus/node_exporter/releases/download/${NODE_EXPORTER_VERSION}/${ARCHIVE}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

curl -fsSL "$DOWNLOAD_URL" -o "$WORK_DIR/$ARCHIVE"
printf '%s  %s\n' "$NODE_EXPORTER_SHA256" "$WORK_DIR/$ARCHIVE" | sha256sum --check --status
tar -xzf "$WORK_DIR/$ARCHIVE" -C "$WORK_DIR"

id node_exporter >/dev/null 2>&1 ||
  useradd --system --no-create-home --shell /usr/sbin/nologin node_exporter
install -m 0755 \
  "$WORK_DIR/node_exporter-${VERSION_WITHOUT_V}.linux-amd64/node_exporter" \
  /usr/local/bin/node_exporter
install -d -m 0755 /etc/node_exporter
printf 'NODE_EXPORTER_LISTEN_ADDRESS=%s:9100\n' "$PRIVATE_IP" \
  > /etc/node_exporter/environment
chown root:node_exporter /etc/node_exporter/environment
chmod 0640 /etc/node_exporter/environment

cat > /etc/systemd/system/node_exporter.service <<'UNIT'
[Unit]
Description=Prometheus node exporter
After=network-online.target
Wants=network-online.target

[Service]
User=node_exporter
Group=node_exporter
EnvironmentFile=/etc/node_exporter/environment
ExecStart=/usr/local/bin/node_exporter --web.listen-address=${NODE_EXPORTER_LISTEN_ADDRESS}
Restart=on-failure
RestartSec=5s
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now node_exporter
systemctl is-active --quiet node_exporter
