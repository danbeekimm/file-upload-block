-- =====================================================================
-- 파일 업로드 확장자 정책 스키마 (PostgreSQL 13+)
--
-- 테이블 구성
--   1. upload_policy       정책 세트 (고유 이름 + 크기/개수 제한)
--   2. extension_rule      차단 목록 (고정 7개 + 커스텀 최대 200개)
--   3. policy_change_log   정책 변경 이력 (append-only, id = 정책 버전)
--   4. file_upload         업로드 판정 기록 (허용/거부 모두)
--   5. ip_block            반복 위반 IP 일시 차단
--
--   신뢰 목록(화이트리스트)은 DB가 아니라 Java enum TrustedFileType으로 관리.
--   확장자·MIME·시그니처·리렌더링 방식을 한곳에 두고, 변경은 코드 리뷰와 배포를 거침.
--   Office 문서, hwp, zip 등 enum에 없는 형식은 비신뢰(attachment)로 처리.
--
-- 공통 원칙
--   - 시각은 모두 TIMESTAMPTZ (서버 시간대와 무관하게 기록)
--   - 확장자는 정규화된 값만 저장 (NFKC → 서식 문자 제거 → trim → 앞쪽 점 제거
--     → 소문자(Locale.ROOT)). DB의 CHECK는 서버 버그에 대비한 마지막 방어선.
--   - 이력/업로드 기록은 정책 행에 FK를 걸지 않고 확장자 문자열을 그대로 저장.
--     커스텀 확장자를 삭제해도 과거 기록이 남아야 하기 때문.
--   - updated_at, version 증가는 애플리케이션(JPA)에서 처리.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. 정책 세트
--    현재는 'default' 하나만 사용. 회원 도입 시 회원 ↔ 정책 매핑으로 확장.
--    크기 제한을 정책 단위로 두어 정책별로 다른 제한을 적용할 수 있게 함.
-- ---------------------------------------------------------------------
CREATE TABLE upload_policy (
    id                     BIGSERIAL     PRIMARY KEY,
    name                   VARCHAR(50)   NOT NULL,
    description            VARCHAR(255),
    max_image_bytes        BIGINT        NOT NULL DEFAULT 10485760,   -- 10MB (탐지된 형식 기준)
    max_file_bytes         BIGINT        NOT NULL DEFAULT 26214400,   -- 25MB (이미지 외)
    max_files_per_request  SMALLINT      NOT NULL DEFAULT 10,
    max_request_bytes      BIGINT        NOT NULL DEFAULT 104857600,  -- 100MB (요청 전체)
    version                INT           NOT NULL DEFAULT 0,          -- 낙관적 락
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uk_upload_policy_name UNIQUE (name),
    CONSTRAINT ck_upload_policy_name CHECK (name ~ '^[a-z0-9_-]{1,50}$'),
    CONSTRAINT ck_upload_policy_limits CHECK (
        max_image_bytes > 0
        AND max_file_bytes > 0
        AND max_files_per_request BETWEEN 1 AND 100
        AND max_request_bytes >= GREATEST(max_image_bytes, max_file_bytes)
    )
);


-- ---------------------------------------------------------------------
-- 2. 차단 목록 (블랙리스트)
--    FIXED  : 고정 7개. 시드로 생성, blocked만 토글. 기본값 unCheck(false).
--    CUSTOM : 행이 존재하면 차단. 삭제는 hard delete (이력은 change_log가 보존).
--
--    UNIQUE (policy_id, extension)이 두 가지를 한 번에 막음
--      - 커스텀 중복 추가 (sh → sh)
--      - 고정-커스텀 충돌 (커스텀에 exe 입력 → 거부 후 안내)
--
--    커스텀 200개 제한은 CHECK로 표현할 수 없어 애플리케이션에서 강제.
--    "개수 조회 → 삽입" 경쟁 조건은 pg_advisory_xact_lock(policy_id)로 직렬화.
-- ---------------------------------------------------------------------
CREATE TABLE extension_rule (
    id           BIGSERIAL     PRIMARY KEY,
    policy_id    BIGINT        NOT NULL REFERENCES upload_policy (id),
    extension    VARCHAR(20)   NOT NULL,
    rule_type    VARCHAR(10)   NOT NULL,
    blocked      BOOLEAN       NOT NULL DEFAULT FALSE,
    version      INT           NOT NULL DEFAULT 0,        -- 고정 토글의 낙관적 락
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uk_extension_rule UNIQUE (policy_id, extension),
    CONSTRAINT ck_extension_rule_type CHECK (rule_type IN ('FIXED', 'CUSTOM')),
    CONSTRAINT ck_extension_rule_format CHECK (extension ~ '^[a-z0-9]{1,20}$'),
    CONSTRAINT ck_extension_rule_custom_blocked CHECK (rule_type = 'FIXED' OR blocked)
);


-- ---------------------------------------------------------------------
-- 3. 정책 변경 이력 (감사)
--    - 정책 변경과 같은 트랜잭션에서 삽입. 실제로 바뀐 경우에만 기록.
--    - 수정/삭제 API 없음 (append-only).
--    - 정책 버전 = MAX(id) WHERE policy_id = ?
--      변경마다 반드시 행이 추가되므로 별도 버전 테이블이 필요 없음.
--    - 변경 내용은 영구 보관. 행위자는 인증이 없어 IP/User-Agent로만 기록.
-- ---------------------------------------------------------------------
CREATE TABLE policy_change_log (
    id           BIGSERIAL     PRIMARY KEY,
    policy_id    BIGINT        NOT NULL REFERENCES upload_policy (id),
    target       VARCHAR(20)   NOT NULL,
    extension    VARCHAR(20),                             -- 확장자 대상 변경일 때만
    action       VARCHAR(10)   NOT NULL,
    detail       JSONB,                                   -- 변경 전/후 값 등 부가 정보
    actor_ip     VARCHAR(45),                             -- IPv6 최대 길이
    user_agent   VARCHAR(255),
    changed_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_change_log_target CHECK (
        target IN ('EXTENSION_RULE', 'POLICY_LIMIT')
    ),
    CONSTRAINT ck_change_log_action CHECK (
        action IN ('ADD', 'DELETE', 'BLOCK', 'UNBLOCK', 'UPDATE')
    ),
    CONSTRAINT ck_change_log_extension CHECK (
        (target = 'EXTENSION_RULE') = (extension IS NOT NULL)
    )
);

-- 정책 버전 조회 (MAX(id) WHERE policy_id = ?)
CREATE INDEX idx_change_log_policy_version ON policy_change_log (policy_id, id);


-- ---------------------------------------------------------------------
-- 4. 업로드 판정 기록
--    허용/거부 모두 파일 단위로 기록. 요청 단위 거부(TEMPORARILY_BLOCKED,
--    RATE_LIMITED, TOO_MANY_FILES, REQUEST_TOO_LARGE)는 파일 판정 전에 끝나므로
--    애플리케이션 로그에만 남김.
--
--    - public_id      : 외부 노출용 식별자 (순차 id 노출로 인한 IDOR 방지)
--    - request_id     : 한 요청에 담긴 파일 묶음. 부분 성공 응답 구성에 사용
--    - policy_version : 판정 시점의 정책 버전 (소급 조사용, FK 아님)
--    - violation_score: 판정 시점에 사유별 가중치로 계산해 저장.
--                       나중에 가중치를 바꿔도 과거 기록의 의미가 바뀌지 않음.
--    - sha256_original: 업로드된 원본의 해시. 위협 조회("이 파일이 올라온 적 있나")용.
--                       크기 초과 등 해시 계산 전에 거부되면 NULL
--    - sha256_stored  : 실제 저장한 파일의 해시. 리렌더링하지 않은 파일은 원본과 같음.
--                       저장소 무결성 확인용
--    - original_name  : 정제된 원본 파일명 (개행 등 제거). 길이 제한은
--                       UTF-8 255바이트 기준으로 애플리케이션에서 검사.
--    - download_name  : 사용자에게 내려줄 이름. 점 파일은 재부여 (.env → _.env)
--    - claimed_mime   : 클라이언트 주장 값 (판정에 사용하지 않음, 위변조 신호용)
--    - detected_type  : 서버가 내용으로 판별한 형식
--    - content_type   : 저장/제공 시 실제 지정한 값 (비신뢰는 application/octet-stream)
--    - disposition    : 제공 방식. 비신뢰 파일과 스크립트가 발견된 문서는 ATTACHMENT
--    - storage_key    : S3 객체 키 (UUID, 확장자 없음). 원본 파일명은 DB에만
-- ---------------------------------------------------------------------
CREATE TABLE file_upload (
    id               BIGSERIAL     PRIMARY KEY,
    public_id        UUID          NOT NULL DEFAULT gen_random_uuid(),
    request_id       UUID          NOT NULL,
    policy_id        BIGINT        NOT NULL REFERENCES upload_policy (id),
    policy_version   BIGINT        NOT NULL,

    status           VARCHAR(10)   NOT NULL,
    reject_reason    VARCHAR(30),
    trust_level      VARCHAR(10),
    violation_score  SMALLINT      NOT NULL DEFAULT 0,

    original_name    VARCHAR(255)  NOT NULL,
    download_name    VARCHAR(255),
    extension        VARCHAR(20),                         -- 확장자 없는 파일은 NULL
    size_bytes       BIGINT        NOT NULL,
    sha256_original  CHAR(64),
    sha256_stored    CHAR(64),
    claimed_mime     VARCHAR(100),
    detected_type    VARCHAR(100),
    content_type     VARCHAR(100),
    disposition      VARCHAR(10),
    storage_key      VARCHAR(100),

    client_ip        VARCHAR(45),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,

    CONSTRAINT uk_file_upload_public_id UNIQUE (public_id),
    CONSTRAINT ck_file_upload_status CHECK (status IN ('STORED', 'REJECTED', 'DELETED')),
    CONSTRAINT ck_file_upload_reason CHECK (reject_reason IN (
        'BLOCKED_EXTENSION',        -- 마지막 확장자가 차단 대상
        'BLOCKED_INNER_EXTENSION',  -- 중간 세그먼트가 차단 대상 (file.exe.txt)
        'EXECUTABLE_DETECTED',      -- 실행 파일 시그니처 (모든 파일에 적용)
        'CONTENT_MISMATCH',         -- 신뢰 확장자인데 시그니처 불일치
        'SIZE_EXCEEDED',
        'INVALID_FILENAME',         -- 빈 이름, 점만 있는 이름, 길이 초과 등
        'ACTIVE_CONTENT',           -- PDF /Launch, /EmbeddedFile (다운로드 후 PC에서 위험)
        'ARCHIVE_NESTED',           -- zip 안에 압축 파일
        'ARCHIVE_LIMIT_EXCEEDED',   -- 항목 100개 이상, 압축률 100:1 이상, 해제 총량 200MB 초과
        'ARCHIVE_ENCRYPTED',        -- 암호화된 zip (내부 검사 불가)
        'ARCHIVE_UNSUPPORTED'       -- zip 외 압축 형식(7z, rar, tar, gz 등) 또는 손상된 zip
        -- zip 내부 항목의 실행 파일·차단 확장자는 기존 EXECUTABLE_DETECTED /
        -- BLOCKED_EXTENSION / BLOCKED_INNER_EXTENSION 코드를 그대로 사용
    )),
    CONSTRAINT ck_file_upload_trust CHECK (trust_level IN ('TRUSTED', 'UNTRUSTED')),
    CONSTRAINT ck_file_upload_disposition CHECK (disposition IN ('INLINE', 'ATTACHMENT')),
    -- 비신뢰 파일은 inline으로 제공하지 않는다
    CONSTRAINT ck_file_upload_untrusted_attachment CHECK (
        trust_level IS DISTINCT FROM 'UNTRUSTED' OR disposition = 'ATTACHMENT'
    ),
    CONSTRAINT ck_file_upload_score CHECK (violation_score >= 0),
    CONSTRAINT ck_file_upload_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_file_upload_sha256_original CHECK (sha256_original ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_file_upload_sha256_stored CHECK (sha256_stored ~ '^[0-9a-f]{64}$'),

    -- 상태별 필수 값: 거부면 사유가 있고, 거부가 아니면 사유가 없다
    CONSTRAINT ck_file_upload_rejected CHECK (
        (status = 'REJECTED') = (reject_reason IS NOT NULL)
    ),
    -- 저장된 파일은 저장 위치, 신뢰 등급, 해시, 제공 MIME, 제공 방식이 모두 있어야 한다
    CONSTRAINT ck_file_upload_stored CHECK (
        status <> 'STORED' OR (
            storage_key IS NOT NULL AND trust_level IS NOT NULL
            AND sha256_original IS NOT NULL AND sha256_stored IS NOT NULL
            AND content_type IS NOT NULL
            AND disposition IS NOT NULL
        )
    ),
    -- 거부된 파일은 저장소에 없다
    CONSTRAINT ck_file_upload_rejected_no_storage CHECK (
        status <> 'REJECTED' OR (storage_key IS NULL AND sha256_stored IS NULL)
    ),
    CONSTRAINT ck_file_upload_deleted CHECK (
        (status = 'DELETED') = (deleted_at IS NOT NULL)
    )
);

-- IP별 최근 위반 점수 합산 (반복 위반 차단), 요청 수 제한 집계
CREATE INDEX idx_file_upload_ip_created ON file_upload (client_ip, created_at);
-- "이 파일이 올라온 적 있나" 해시 조회
CREATE INDEX idx_file_upload_sha256_original ON file_upload (sha256_original);
-- 요청 단위 결과 조회 (부분 성공 응답)
CREATE INDEX idx_file_upload_request ON file_upload (request_id);


-- ---------------------------------------------------------------------
-- 5. 반복 위반 IP 일시 차단
--    - 판정: 최근 시간 창 안의 SUM(file_upload.violation_score)이 임곗값 이상
--    - 영구 차단 없음. blocked_until이 지나면 자동 해제
--    - 관리자 수동 해제는 released_at/released_by_ip로 기록 (행 자체가 이력)
--    - 차단 확인은 서블릿 필터에서 수행 (멀티파트 본문을 읽기 전에 거부)
-- ---------------------------------------------------------------------
CREATE TABLE ip_block (
    id               BIGSERIAL     PRIMARY KEY,
    client_ip        VARCHAR(45)   NOT NULL,
    score            SMALLINT      NOT NULL,              -- 차단 시점의 누적 점수
    blocked_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    blocked_until    TIMESTAMPTZ   NOT NULL,
    released_at      TIMESTAMPTZ,
    released_by_ip   VARCHAR(45),

    CONSTRAINT ck_ip_block_period CHECK (blocked_until > blocked_at),
    CONSTRAINT ck_ip_block_release CHECK (
        released_at IS NULL OR released_at >= blocked_at
    )
);

-- 활성 차단 조회: 수동 해제되지 않은 행만
CREATE INDEX idx_ip_block_active ON ip_block (client_ip, blocked_until)
    WHERE released_at IS NULL;


-- =====================================================================
-- 시드 데이터 (재실행해도 안전하도록 ON CONFLICT DO NOTHING)
-- =====================================================================

INSERT INTO upload_policy (name, description)
VALUES ('default', '기본 업로드 정책')
ON CONFLICT (name) DO NOTHING;

-- 고정 확장자 7개: 요구사항에 따라 기본값 unCheck (blocked = false)
-- 주의: 이 상태에서는 확장자 차단이 하나도 없음.
--       실행 파일 시그니처 검사가 확장자 정책과 무관하게 최소 방어선을 유지.
INSERT INTO extension_rule (policy_id, extension, rule_type, blocked)
SELECT p.id, e.ext, 'FIXED', FALSE
FROM upload_policy p
CROSS JOIN unnest(ARRAY['bat', 'cmd', 'com', 'cpl', 'exe', 'scr', 'js']) AS e (ext)
WHERE p.name = 'default'
ON CONFLICT (policy_id, extension) DO NOTHING;
