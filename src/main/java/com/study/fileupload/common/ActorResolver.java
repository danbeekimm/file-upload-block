package com.study.fileupload.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 요청자 식별을 한곳에서 담당 (명세 12장).
 * 프록시 뒤 실제 클라이언트 IP 는 코드가 아니라 설정으로 해결한다 — 운영 배포는 server.forward-headers-strategy=native 로
 * Tomcat RemoteIpValve 가 신뢰 프록시(같은 호스트 nginx)의 X-Forwarded-For 를 getRemoteAddr() 에 반영한다 (docs/배포가이드.md).
 */
@Component
public class ActorResolver {

    private static final int MAX_UA_LENGTH = 255;

    public Actor resolve(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua != null && ua.length() > MAX_UA_LENGTH) {
            ua = ua.substring(0, MAX_UA_LENGTH);
        }
        return new Actor(request.getRemoteAddr(), ua);
    }
}
