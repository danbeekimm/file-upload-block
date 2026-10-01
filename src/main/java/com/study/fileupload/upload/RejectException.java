package com.study.fileupload.upload;

import com.study.fileupload.common.ReasonCode;

/** 파이프라인 내부에서 파일 하나를 거부할 때 사용. 파일 단위 결과로 변환된다 (부분 성공, 명세 2-2). */
public class RejectException extends RuntimeException {

    private final ReasonCode reason;
    /** 로그 전용 상세 (예: "MZ 헤더 감지"). 사용자 응답에는 넣지 않는다 (명세 9장). */
    private final String logDetail;

    public RejectException(ReasonCode reason, String userMessage) {
        this(reason, userMessage, null);
    }

    public RejectException(ReasonCode reason, String userMessage, String logDetail) {
        super(userMessage);
        this.reason = reason;
        this.logDetail = logDetail;
    }

    public ReasonCode reason() {
        return reason;
    }

    public String logDetail() {
        return logDetail;
    }
}
