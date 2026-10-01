package com.study.fileupload;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * 요청 단위 필터 (명세 2-1, 8장): 요청 수 제한과 반복 위반 IP 일시 차단.
 * 빠른 검증을 위해 임곗값을 낮춰 별도 컨텍스트로 실행한다.
 */
@SpringBootTest(properties = {
        "upload.rate-limit.max-requests=3",
        "upload.ip-block.threshold=6",
        "upload.ip-block.duration=5m"
})
@AutoConfigureMockMvc
class GuardIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mockMvc;

    static byte[] pngBytes() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private MockMultipartHttpServletRequestBuilder uploadFrom(String ip, MockMultipartFile file) {
        var builder = multipart("/api/uploads").file(file);
        builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
        return builder;
    }

    @Test
    @DisplayName("위장 파일 2회(3점×2=6점) 후 다음 요청은 TEMPORARILY_BLOCKED (명세 18장)")
    void repeatedDisguiseBlocksIp() throws Exception {
        String attacker = "10.9.9.1";
        MockMultipartFile disguised = new MockMultipartFile(
                "files", "fake.jpg", "image/jpeg", pngBytes());   // CONTENT_MISMATCH = 3점

        mockMvc.perform(uploadFrom(attacker, disguised))
                .andExpect(jsonPath("$.results[0].reasonCode").value("CONTENT_MISMATCH"));
        mockMvc.perform(uploadFrom(attacker, disguised))
                .andExpect(jsonPath("$.results[0].reasonCode").value("CONTENT_MISMATCH"));

        // 임곗값 도달 → 필터가 멀티파트 본문을 읽기 전에 거부
        mockMvc.perform(uploadFrom(attacker, disguised))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TEMPORARILY_BLOCKED"));

        // 다른 IP는 영향 없음
        mockMvc.perform(uploadFrom("10.9.9.100",
                        new MockMultipartFile("files", "ok.txt", "text/plain", "hi".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("차단 확장자 거부는 0점 — 반복해도 IP가 차단되지 않는다 (명세 8-1)")
    void blockedExtensionDoesNotAccumulate() throws Exception {
        jdbc.update("UPDATE extension_rule SET blocked = true WHERE extension = 'exe'");
        String ip = "10.9.9.2";
        MockMultipartFile exe = new MockMultipartFile(
                "files", "app.exe", "application/octet-stream", "x".getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(uploadFrom(ip, exe))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results[0].reasonCode").value("BLOCKED_EXTENSION"));
        }
        Integer blocks = jdbc.queryForObject(
                "SELECT count(*) FROM ip_block WHERE client_ip = ?", Integer.class, ip);
        org.assertj.core.api.Assertions.assertThat(blocks).isZero();
    }

    @Test
    @DisplayName("요청 수 제한: 창 안에서 3회를 넘으면 RATE_LIMITED (판정 결과와 무관)")
    void rateLimit() throws Exception {
        String ip = "10.9.9.3";
        MockMultipartFile ok = new MockMultipartFile(
                "files", "note.txt", "text/plain", "hi".getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(uploadFrom(ip, ok)).andExpect(status().isOk());
        }
        mockMvc.perform(uploadFrom(ip, ok))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
