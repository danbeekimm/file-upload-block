# 파일 업로드 확장자 차단

확장자 차단 정책을 관리하는 화면과, 그 정책을 **서버에서 실제로 강제**하는 파일 업로드 API.
설계 기준 문서는 [`docs/기획명세.md`](docs/기획명세.md), 데이터 모델은 [`schema.sql`](schema.sql)이 유일한 기준입니다.

| 문서 | 내용 |
|---|---|
| [docs/기획명세.md](docs/기획명세.md) | 개발 기준 명세 (판정 파이프라인, 정책 규칙, UX, 한계) |
| [CONSIDERATIONS.md](CONSIDERATIONS.md) | 고려사항과 판단 근거 |
| [PROMPT_LOG.md](PROMPT_LOG.md) | AI 활용 기록 |
| [docs/테스트.md](docs/테스트.md) | 테스트 전략·시나리오·실행 결과 |
| [docs/배포가이드.md](docs/배포가이드.md) | GitHub Actions → GHCR → SSH 배포 (기존 nginx/PostgreSQL 연동, `deploy/`) |
| [docs/소스구조.md](docs/소스구조.md) | 소스 구조와 처리 흐름 (패키지, 판정 파이프라인, 데이터 모델) |
| [docs/ERD.md](docs/ERD.md) | ERD (실행 중인 DB 카탈로그 기준, Mermaid) |
| [docs/결함보고.md](docs/결함보고.md) | 재현 확인된 결함 4건 (증상·원인·수정 방향) |
| [docs/과제-요구사항.md](docs/과제-요구사항.md) | 과제 원문 (요구사항·고려사항 목록) |

## 기술 스택

Java 17 · Spring Boot 3.3 · Gradle (wrapper) · Spring Data JPA · PostgreSQL 16 · Thymeleaf · AWS SDK v2 (S3) · JUnit 5

## 실행 방법

### 1. DB (PostgreSQL)

```bash
docker compose up -d
```

- 호스트 포트 **55432** (5432는 다른 프로젝트와 충돌하여 변경)
- 최초 기동 시 `schema.sql`이 자동 적용됩니다 (테이블 5개 + default 정책 + 고정 확장자 7개 시드)

### 2. 애플리케이션

```bash
# 로컬 저장소 모드 (기본)
./gradlew bootRun

# S3 저장소 모드 — 자격 증명은 .env.example 과 같은 이름의 환경 변수로 넘긴다
STORAGE_MODE=s3 AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... ./gradlew bootRun
# PowerShell: $env:STORAGE_MODE="s3"; $env:AWS_ACCESS_KEY_ID="..."; $env:AWS_SECRET_ACCESS_KEY="..."; .\gradlew bootRun
```

http://localhost:8080 접속 → 업로드 화면(드래그앤드롭, 진행률, 업로드 목록·다운로드). http://localhost:8080/admin → 차단 관리 화면(고정·커스텀 확장자, IP 차단 해제).

### 3. 테스트

```bash
docker compose up -d   # 통합 테스트가 로컬 PostgreSQL(55432)을 사용
./gradlew test
```

단위 67개 + 통합 27개. 통합 테스트는 `fileupload_test` DB를 자동 생성해 운영 데이터와 분리합니다.
상세는 [docs/테스트.md](docs/테스트.md) 참고.

## 저장소 (storage)

| 모드 | 설정 | 비고 |
|---|---|---|
| `local` (기본) | `STORAGE_DIR` (기본 `./data/objects`) | 웹 루트 밖, UUID 키로만 저장 |
| `s3` | `S3_BUCKET`, `S3_REGION`, `S3_PREFIX`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | 자격 증명은 `.env.example` 항목 그대로 환경 변수로 전달 (`.env`는 미커밋). 둘 다 비우면 SDK 기본 체인(EC2 인스턴스 역할) |

### S3 IAM 정책 (연결 완료 · 2026-10-01 검증)

`s3-uploader` 사용자에 아래 정책을 연결한 뒤 SDK 직접 호출과 앱 S3 모드로 검증했습니다.
`uploads/` 아래 PutObject·GetObject·DeleteObject 성공, 앱 업로드 → S3 저장 → 다운로드 왕복 성공(txt 바이트 일치, png는 리렌더링된 PNG로 inline 제공).
의도적으로 주지 않은 권한: `s3:ListBucket`(목록 조회 불필요), prefix 밖 쓰기(거부 확인). ListBucket이 없으면 존재하지 않는 키의 GetObject가 404가 아닌 403으로 오지만,
앱은 자신이 저장한 키만 읽으므로 영향이 없습니다. 상세는 [docs/테스트.md](docs/테스트.md) 수동 테스트 표.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::work-841820566166-us-east-1-an/uploads/*"
    }
  ]
}
```

버킷은 비공개를 유지하고, 다운로드는 반드시 애플리케이션을 거칩니다
(`Content-Type`/`Content-Disposition`/`nosniff`를 서버가 통제하기 위함 — 명세 7장).

## API 요약

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/policies` | 고정(체크 상태)·커스텀 목록, 개수, version |
| PUT | `/api/admin/policies/fixed/{ext}` | 고정 토글. `{"blocked":true,"version":0}` — 멱등, 버전 불일치 409 |
| POST | `/api/admin/policies/custom` | 커스텀 추가. `{"extension":".SH"}` → `sh` 보정 저장 |
| DELETE | `/api/admin/policies/custom/{ext}` | 커스텀 삭제 (없어도 204) |
| GET | `/api/admin/ip-blocks` | 활성 IP 차단 목록 |
| DELETE | `/api/admin/ip-blocks/{id}` | 차단 수동 해제 |
| POST | `/api/uploads` | 멀티파트 업로드 (`files`). 파일별 부분 성공 응답 |
| GET | `/api/uploads?limit=50` | 저장된 파일 목록 최신순 (업로더 구분 없음, 최대 200) |
| GET | `/api/uploads/{publicId}` | 다운로드. 판정 시 결정된 Content-Type/Disposition + nosniff |

거부 사유 코드와 메시지는 명세 9장을 그대로 구현했습니다.

```bash
# 화면을 거치지 않아도 서버가 차단하는지 확인 (서버 검증 증명)
curl -X POST http://localhost:8080/api/uploads -F "files=@evil.exe"
# → {"results":[{"status":"REJECTED","reasonCode":"BLOCKED_EXTENSION",
#     "message":"evil.exe — exe 파일은 보안 정책상 업로드할 수 없습니다."}]}
```

## 테이블 스키마

5개 테이블: `upload_policy`(정책 세트), `extension_rule`(차단 목록, `UNIQUE(policy_id, extension)`),
`policy_change_log`(append-only 이력, id = 정책 버전), `file_upload`(허용·거부 판정 기록),
`ip_block`(반복 위반 일시 차단). 신뢰 목록은 DB가 아니라 Java enum `TrustedFileType`입니다(명세 5-2).

관계도는 [`docs/ERD.md`](docs/ERD.md), 컬럼·제약·인덱스 상세는 [`schema.sql`](schema.sql)의 주석 참고.
DB CHECK/UNIQUE 제약은 "서버 버그가 있어도 잘못된 데이터가 들어가지 않게 하는" 마지막 방어선으로,
통합 테스트가 실제 PostgreSQL에서 함께 검증합니다.

## 프로젝트 구조

하위 모듈 없는 단일 Gradle 프로젝트입니다 (`settings.gradle`의 루트 이름 `file-upload-block`, 빌드는 `./gradlew`).

```
src/main/java/com/study/fileupload
├── common/     사유 코드, 예외·에러 응답, Actor(요청자), 확장자 정규화 (정책·업로드 공용)
├── config/     upload.* / storage.* 설정 바인딩
├── policy/     정책 관리 (엔티티, 서비스, 관리 API) — advisory lock, 낙관적 락, 변경 이력
├── upload/     판정 파이프라인 — 파일명 처리, 실행 파일 시그니처, 신뢰 목록 enum,
│               이미지 리렌더링, PDF 검사, zip 검사, 업로드/다운로드 API
├── guard/      요청 단위 필터 (IP 차단, 요청 수 제한), IP 차단 관리
├── storage/    ObjectStorage (local / S3)
└── web/        Thymeleaf 페이지
```
