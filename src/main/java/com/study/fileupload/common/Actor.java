package com.study.fileupload.common;

/** 요청자 정보. 인증 도입 시 ActorResolver 구현만 교체한다 (명세 12장). */
public record Actor(String ip, String userAgent) {
}
