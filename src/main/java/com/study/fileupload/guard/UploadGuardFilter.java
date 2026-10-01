package com.study.fileupload.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.fileupload.common.ErrorResponse;
import com.study.fileupload.config.UploadProperties;
import com.study.fileupload.policy.PolicyService;
import com.study.fileupload.upload.FileUploadRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 단위 검사 (명세 2-1). 멀티파트 본문을 읽기 전에 싼 검사로 거른다.
 * 순서: IP 차단 → 요청 수 제한 → 요청 전체 크기. 파일 개수는 멀티파트 파싱 후 컨트롤러에서.
 * 요청 단위 거부는 file_upload에 기록하지 않고 애플리케이션 로그에만 남긴다.
 */
@Component
public class UploadGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UploadGuardFilter.class);

    private final IpBlockService ipBlockService;
    private final FileUploadRepository fileUploadRepository;
    private final PolicyService policyService;
    private final UploadProperties properties;
    private final ObjectMapper objectMapper;

    public UploadGuardFilter(IpBlockService ipBlockService,
                             FileUploadRepository fileUploadRepository,
                             PolicyService policyService,
                             UploadProperties properties,
                             ObjectMapper objectMapper) {
        this.ipBlockService = ipBlockService;
        this.fileUploadRepository = fileUploadRepository;
        this.policyService = policyService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && "/api/uploads".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = request.getRemoteAddr();

        // 1. IP 차단
        Optional<IpBlock> block = ipBlockService.activeBlock(ip);
        if (block.isPresent()) {
            long remainSeconds = Math.max(1, Duration.between(Instant.now(), block.get().getBlockedUntil()).getSeconds());
            long remainMinutes = Math.max(1, (remainSeconds + 59) / 60);
            log.warn("request rejected TEMPORARILY_BLOCKED ip={}", ip);
            reject(response, 429, remainSeconds, "TEMPORARILY_BLOCKED",
                    "반복된 정책 위반으로 업로드가 일시 제한되었습니다. (약 " + remainMinutes + "분 후 해제)");
            return;
        }

        // 2. 요청 수 제한 (5분 10회)
        Instant since = Instant.now().minus(properties.rateLimit().window());
        long recent = fileUploadRepository.countRecentRequests(ip, since);
        if (recent >= properties.rateLimit().maxRequests()) {
            log.info("request rejected RATE_LIMITED ip={} recent={}", ip, recent);
            reject(response, 429, properties.rateLimit().window().getSeconds(), "RATE_LIMITED",
                    "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
            return;
        }

        // 3. 요청 전체 크기 (Content-Length). 멀티파트 설정이 최종 방어선으로 한 번 더 막는다
        long maxRequestBytes = policyService.defaultPolicy().getMaxRequestBytes();
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxRequestBytes) {
            log.info("request rejected REQUEST_TOO_LARGE ip={} length={}", ip, contentLength);
            reject(response, 413, -1, "REQUEST_TOO_LARGE",
                    "한 번에 올릴 수 있는 전체 크기는 " + (maxRequestBytes / 1024 / 1024) + "MB입니다.");
            return;
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, long retryAfterSeconds,
                        String code, String message) throws IOException {
        response.setStatus(status);
        if (retryAfterSeconds > 0) {
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ErrorResponse.of(code, message)));
    }
}
