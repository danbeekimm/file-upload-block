package com.study.fileupload.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 에러 응답 형식 {code, message, detail} (명세 15장).
 * Spring MVC가 400/404/405/415로 처리하는 클라이언트 오류를 명시적으로 받는다 —
 * 맨 아래 Exception 핸들러가 모든 예외를 먼저 가로채면 클라이언트 실수가 전부 500으로 응답되기 때문.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MultipartProperties multipart;

    public GlobalExceptionHandler(MultipartProperties multipart) {
        this.multipart = multipart;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(new ErrorResponse(e.code(), e.getMessage(), e.detail()));
    }

    /**
     * 멀티파트 요청 상한 초과 — 컨트롤러 전에 던져지므로 여기서 사유 코드로 변환 (명세 6장).
     * 파일별 상한(정책)은 파이프라인이 먼저 판정하도록 멀티파트 max-file-size를 정책 값보다 크게 둔다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        long maxMb = multipart.getMaxRequestSize().toMegabytes();
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of("REQUEST_TOO_LARGE", "한 번에 올릴 수 있는 전체 크기는 " + maxMb + "MB입니다."));
    }

    /** 본문 검증 실패 (@Valid) — 필드별 메시지를 detail에 담는다 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            fields.put(error.getField(), error.getDefaultMessage());
        }
        return clientError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.", fields, e);
    }

    /** 업로드 요청에 files 파트가 없거나 멀티파트가 아님 — 서비스의 NO_FILES와 같은 의미 */
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ErrorResponse> handleNoFiles(Exception e) {
        return clientError(HttpStatus.BAD_REQUEST, "NO_FILES", "업로드할 파일이 없습니다.", null, e);
    }

    /** 깨진 JSON, 타입 불일치(UUID가 아닌 id 등), 필수 파라미터 누락, 파라미터 검증 실패 */
    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class,
            ServletRequestBindingException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        return clientError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다.", null, e);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception e) {
        return clientError(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 경로를 찾을 수 없습니다.", null, e);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return clientError(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "허용되지 않은 메서드입니다.", null, e);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return clientError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 Content-Type입니다.", null, e);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        return clientError(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "응답할 수 없는 Accept 형식입니다.", null, e);
    }

    /** 위에서 받지 못한 예외만 서버 오류. 클라이언트 실수는 여기 오지 않아야 한다 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."));
    }

    /** 클라이언트 오류는 정상 동작 — ERROR가 아닌 INFO (명세 11장) */
    private ResponseEntity<ErrorResponse> clientError(HttpStatus status, String code, String message,
                                                      Map<String, Object> detail, Exception e) {
        log.info("client error {} {} ({})", status.value(), code, e.getClass().getSimpleName());
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, detail));
    }
}
