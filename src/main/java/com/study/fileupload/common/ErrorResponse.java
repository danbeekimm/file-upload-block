package com.study.fileupload.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** 에러 응답 형식 (명세 15장): { code, message, detail } */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Map<String, Object> detail) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }
}
