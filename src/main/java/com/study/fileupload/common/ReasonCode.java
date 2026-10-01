package com.study.fileupload.common;

/**
 * 파일 단위 거부 사유 코드 (file_upload.reject_reason CHECK와 1:1).
 * violationScore는 판정 시점에 기록에 복사한다 — 이후 가중치를 바꿔도
 * 과거 기록의 의미가 바뀌지 않게 하기 위함 (명세 8-1).
 */
public enum ReasonCode {

    BLOCKED_EXTENSION(0),
    BLOCKED_INNER_EXTENSION(0),
    EXECUTABLE_DETECTED(3),
    CONTENT_MISMATCH(3),
    SIZE_EXCEEDED(0),
    INVALID_FILENAME(0),
    ACTIVE_CONTENT(2),
    ARCHIVE_NESTED(1),
    ARCHIVE_LIMIT_EXCEEDED(1),
    ARCHIVE_ENCRYPTED(0),
    ARCHIVE_UNSUPPORTED(0);

    private final int violationScore;

    ReasonCode(int violationScore) {
        this.violationScore = violationScore;
    }

    public int violationScore() {
        return violationScore;
    }
}
