#!/bin/bash
set -euo pipefail

systemctl disable --now node_exporter.service 2>/dev/null || true
rm -f /etc/systemd/system/node_exporter.service
rm -f /usr/local/bin/node_exporter
rm -rf /etc/node_exporter
systemctl daemon-reload
