#!/usr/bin/env bash
# Cloudflare 가 공개하는 프록시 IP 대역으로 nginx real_ip 스니펫을 생성한다.
#   sudo deploy/nginx/refresh-cloudflare-ips.sh            → /etc/nginx/snippets/cloudflare-real-ip.conf 갱신 + reload
# 대역은 가끔 바뀌므로 분기 1회 정도 재실행 (cron 등록 예: 0 4 1 */3 * root /path/refresh-cloudflare-ips.sh)
set -euo pipefail
OUT="${1:-/etc/nginx/snippets/cloudflare-real-ip.conf}"
tmp=$(mktemp)
{
  echo "# generated $(date -u +%FT%TZ) from https://www.cloudflare.com/ips-v4 , ips-v6 — 직접 수정하지 말 것"
  for u in https://www.cloudflare.com/ips-v4 https://www.cloudflare.com/ips-v6; do
    curl -fsS --max-time 15 "$u" | sed 's/^/set_real_ip_from /; s/$/;/'
  done
  echo "real_ip_header CF-Connecting-IP;"
} > "$tmp"
grep -q '^set_real_ip_from' "$tmp" || { echo "대역을 받지 못했습니다"; exit 1; }
install -m 644 "$tmp" "$OUT" && rm -f "$tmp"
nginx -t && systemctl reload nginx
echo "갱신 완료: $OUT ($(grep -c set_real_ip_from "$OUT") 개 대역)"
