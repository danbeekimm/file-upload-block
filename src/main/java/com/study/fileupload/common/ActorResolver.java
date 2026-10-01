package com.study.fileupload.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 요청자 식별을 한곳에서 담당 (명세 12장).
 * 프록시 뒤 실제 클라이언트 IP 판별(X-Forwarded-For)은 미결 사항 — 현재는 remote address 사용.
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
