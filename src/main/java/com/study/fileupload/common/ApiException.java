package com.study.fileupload.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** 코드 + 메시지 + 부가 정보(detail)를 담는 API 예외. 전역 핸들러가 에러 응답으로 변환한다. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> detail;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> detail) {
        super(message);
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> detail() {
        return detail;
    }
}
