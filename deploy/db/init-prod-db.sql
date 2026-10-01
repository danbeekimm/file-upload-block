-- 운영 VM의 기존 PostgreSQL에 이 앱 전용 롤과 DB를 만든다. 슈퍼유저로 1회만 실행 (멱등 — 있으면 건너뜀).
--   네이티브 설치:  sudo -u postgres psql -v db_user=fileupload -v db_name=fileupload -v db_password='<비밀번호>' -f deploy/db/init-prod-db.sql
--   컨테이너:       docker exec -i <pg컨테이너> psql -U postgres -v db_user=fileupload -v db_name=fileupload -v db_password='<비밀번호>' < deploy/db/init-prod-db.sql
-- db_user / db_name / db_password 는 GitHub 의 DB_USER / DB_NAME / DB_PASSWORD 와 같은 값이어야 한다.
-- 테이블(schema.sql)은 첫 배포 때 deploy.sh 가 이 롤로 적용하므로 여기서는 만들지 않는다.
--   롤이 DB 소유자라 public 스키마에 CREATE 권한을 가진다 (PostgreSQL 15+ 포함).
\set ON_ERROR_STOP on

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'db_user', :'db_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'db_user')
\gexec

SELECT format('CREATE DATABASE %I OWNER %I ENCODING ''UTF8''', :'db_name', :'db_user')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db_name')
\gexec

\echo 롤/DB 준비 완료: :db_user @ :db_name
