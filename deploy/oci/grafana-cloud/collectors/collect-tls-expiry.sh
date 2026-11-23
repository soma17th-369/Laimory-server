#!/bin/bash
# nginx가 실제로 내보내는 인증서의 만료 시각을 textfile 지표로 남긴다(이름별).
# 파일이 아니라 127.0.0.1:443에 SNI로 붙어 받은 인증서를 읽는다 — 갱신은 됐는데 nginx 사본 복사나 reload가
# 빠진 경우도 잡기 위해서다. 만료 경보와 수집 정지 경보가 이 값으로 발화한다.
set -euo pipefail

readonly OUTPUT_DIR=/var/lib/laimory/textfile
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_oci_tls.prom"
readonly NAMES=(laimory.app api.laimory.app)

install -d -m 0755 -o root -g root "$OUTPUT_DIR"
tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_oci_tls.XXXXXX")
trap 'rm -f "$tmp_file"' EXIT

{
  echo "# HELP laimory_oci_tls_cert_expiry_unixtime_seconds notAfter of the certificate nginx serves for the name."
  echo "# TYPE laimory_oci_tls_cert_expiry_unixtime_seconds gauge"
  for name in "${NAMES[@]}"; do
    end=$(openssl s_client -connect 127.0.0.1:443 -servername "$name" </dev/null 2>/dev/null \
      | openssl x509 -noout -enddate 2>/dev/null | sed -n 's/^notAfter=//p') || true
    [[ -n $end ]] || { echo "tls expiry: no certificate for $name" >&2; continue; }
    echo "laimory_oci_tls_cert_expiry_unixtime_seconds{name=\"$name\"} $(date -d "$end" +%s)"
  done
  echo "# HELP laimory_oci_tls_last_collect_unixtime_seconds Unix time this collector last ran."
  echo "# TYPE laimory_oci_tls_last_collect_unixtime_seconds gauge"
  echo "laimory_oci_tls_last_collect_unixtime_seconds $(date +%s)"
} > "$tmp_file"
chmod 0644 "$tmp_file"
mv -f "$tmp_file" "$OUTPUT_FILE"
