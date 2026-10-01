#!/usr/bin/env bash
# 운영 VM에서 실행되는 배포 스크립트. GitHub Actions의 deploy 잡이 ssh 로 호출한다 (docs/배포가이드.md).
#   필요한 환경 변수 (워크플로가 넘김): APP_DIR, APP_IMAGE, GHCR_USER, GHCR_TOKEN
#   APP_DIR 에 docker-compose.yml, .env, schema.sql 이 먼저 복사되어 있어야 한다.
# 하는 일
#   1. GHCR 로그인 후 이미지 pull
#   2. DB에 upload_policy 테이블이 없으면 schema.sql 1회 적용 (멱등)
#   3. docker compose up -d --wait (healthcheck 통과까지 대기)
#   4. 스모크 테스트 + 오래된 이미지 정리
set -euo pipefail

: "${APP_DIR:?APP_DIR 필요}"
: "${APP_IMAGE:?APP_IMAGE 필요}"
cd "$APP_DIR"

# .env 의 값(APP_PORT, DB_*)을 스크립트에서도 쓴다. compose 는 같은 파일을 자체적으로 읽는다.
# source 대신 줄 단위로 읽어 비밀번호의 $, 공백 같은 문자를 셸이 해석하지 않게 한다 (compose 의 env_file 과 같은 리터럴 의미)
envval() { grep -E "^$1=" .env | head -n1 | cut -d= -f2-; }
APP_PORT="$(envval APP_PORT)";   APP_PORT="${APP_PORT:-8090}"
DB_PORT="$(envval DB_PORT)";     DB_PORT="${DB_PORT:-5432}"
DB_NAME="$(envval DB_NAME)";     DB_NAME="${DB_NAME:-fileupload}"
DB_USER="$(envval DB_USER)";     DB_USER="${DB_USER:-fileupload}"
DB_PASSWORD="$(envval DB_PASSWORD)"
export APP_IMAGE

echo "==> [1/4] 이미지 pull: $APP_IMAGE"
if [ -n "${GHCR_TOKEN:-}" ]; then
  echo "$GHCR_TOKEN" | docker login ghcr.io -u "${GHCR_USER:-github}" --password-stdin
fi
docker compose pull --quiet

echo "==> [2/4] 스키마 확인 (${DB_USER}@127.0.0.1:${DB_PORT}/${DB_NAME})"
# psql 을 호스트에 설치하지 않고 postgres 이미지의 psql 을 host 네트워크로 사용한다
psql_run() {
  docker run --rm -i --network host -e PGPASSWORD="$DB_PASSWORD" postgres:17-alpine \
    psql -h 127.0.0.1 -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q "$@"
}
has_schema=$(psql_run -tA -c "SELECT to_regclass('public.upload_policy') IS NOT NULL")
if [ "$has_schema" = "t" ]; then
  echo "    이미 적용됨 — 건너뜀"
else
  echo "    upload_policy 없음 — schema.sql 적용"
  psql_run -f - < schema.sql
fi

echo "==> [3/4] 컨테이너 기동 (healthcheck 대기)"
if ! docker compose up -d --wait --remove-orphans; then
  echo "!! 기동 실패 — 최근 로그:"
  docker compose logs --tail=100 app || true
  exit 1
fi

echo "==> [4/4] 스모크 테스트"
curl -fsS "http://127.0.0.1:${APP_PORT}/api/admin/policies" | head -c 300; echo
docker image prune -f > /dev/null
echo "==> 배포 완료: $APP_IMAGE (127.0.0.1:${APP_PORT})"
