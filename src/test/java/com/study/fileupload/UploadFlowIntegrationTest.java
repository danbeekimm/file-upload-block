package com.study.fileupload;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 업로드 판정 파이프라인 종단 테스트 (명세 2-2, 18장).
 * 요청 수 제한·IP 차단 임곗값은 높여서 이 테스트에 간섭하지 않게 한다 (전용 테스트는 GuardIntegrationTest).
 */
@SpringBootTest(properties = {
        "upload.rate-limit.max-requests=100000",
        "upload.ip-block.threshold=100000"
})
@AutoConfigureMockMvc
class UploadFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mockMvc;

    /* ---------- 파일 픽스처 ---------- */

    static byte[] pngBytes() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0xFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    static byte[] jpegBytes() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    static byte[] elfBytes() {
        byte[] elf = new byte[128];
        elf[0] = 0x7F;
        elf[1] = 'E';
        elf[2] = 'L';
        elf[3] = 'F';
        return elf;
    }

    static byte[] zipWith(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, "application/octet-stream", content);
    }

    void blockFixed(String ext) throws Exception {
        mockMvc.perform(put("/api/admin/policies/fixed/" + ext)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocked\":true,\"version\":0}"))
                .andExpect(status().isOk());
    }

    void addCustom(String ext) throws Exception {
        mockMvc.perform(post("/api/admin/policies/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\":\"" + ext + "\"}"))
                .andExpect(status().isCreated());
    }

    /* ---------- 시나리오 ---------- */

    @Test
    @DisplayName("여러 파일 한 요청: 파일별 독립 판정(부분 성공)으로 각자의 사유를 받는다")
    void multiFileUpload_partialSuccess() throws Exception {
        blockFixed("exe");
        addCustom("sh");

        mockMvc.perform(multipart("/api/uploads")
                        .file(file("setup.EXE", "plain text".getBytes(StandardCharsets.UTF_8)))
                        .file(file("script.sh", "echo hi".getBytes(StandardCharsets.UTF_8)))
                        .file(file("file.exe.txt", "text".getBytes(StandardCharsets.UTF_8)))
                        .file(file("com.example.config.txt", "conf".getBytes(StandardCharsets.UTF_8)))
                        .file(file("photo.png", pngBytes()))
                        .file(file("fake.jpg", pngBytes()))
                        .file(file("backdoor", elfBytes()))
                        .file(file("manual.pdf", "%PDF-1.4 << /S /Launch >>".getBytes(StandardCharsets.ISO_8859_1)))
                        .file(file(".env", "SECRET=1".getBytes(StandardCharsets.UTF_8)))
                        .file(file("archive.zip", zipWith("a.exe", "x".getBytes(StandardCharsets.UTF_8)))))
                .andExpect(status().isOk())
                // 1. 대소문자 우회: setup.EXE → exe 차단
                .andExpect(jsonPath("$.results[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.results[0].reasonCode").value("BLOCKED_EXTENSION"))
                // 2. 커스텀 차단: script.sh
                .andExpect(jsonPath("$.results[1].reasonCode").value("BLOCKED_EXTENSION"))
                // 3. 중간 세그먼트: file.exe.txt
                .andExpect(jsonPath("$.results[2].reasonCode").value("BLOCKED_INNER_EXTENSION"))
                // 4. 첫 세그먼트 제외: com.example.config.txt는 com 차단과 무관하게 저장
                .andExpect(jsonPath("$.results[3].status").value("STORED"))
                .andExpect(jsonPath("$.results[3].disposition").value("ATTACHMENT"))
                // 5. 정상 이미지: 신뢰 + inline
                .andExpect(jsonPath("$.results[4].status").value("STORED"))
                .andExpect(jsonPath("$.results[4].disposition").value("INLINE"))
                // 6. 위장: jpg 확장자에 PNG 내용
                .andExpect(jsonPath("$.results[5].reasonCode").value("CONTENT_MISMATCH"))
                // 7. 확장자 없는 ELF: 시그니처가 최소 방어선
                .andExpect(jsonPath("$.results[6].reasonCode").value("EXECUTABLE_DETECTED"))
                // 8. PDF /Launch: PC에서 열 때 위험 → 거부
                .andExpect(jsonPath("$.results[7].reasonCode").value("ACTIVE_CONTENT"))
                // 9. 점 파일: 저장하되 다운로드 이름 재부여 안내
                .andExpect(jsonPath("$.results[8].status").value("STORED"))
                .andExpect(jsonPath("$.results[8].notices[0]").value(
                        org.hamcrest.Matchers.containsString("_.env")))
                // 10. zip 내부의 차단 확장자
                .andExpect(jsonPath("$.results[9].reasonCode").value("BLOCKED_EXTENSION"));
    }

    @Test
    @DisplayName("정책이 바뀌면 같은 파일의 판정이 바뀐다 — 정책이 실제로 강제되는 증거")
    void policyChangeChangesVerdict() throws Exception {
        MockMultipartFile exe = file("tool.exe", "text".getBytes(StandardCharsets.UTF_8));

        // exe 미차단 상태: 확장자 정책은 통과 (비신뢰 저장)
        mockMvc.perform(multipart("/api/uploads").file(exe))
                .andExpect(jsonPath("$.results[0].status").value("STORED"))
                .andExpect(jsonPath("$.results[0].disposition").value("ATTACHMENT"));

        // exe 차단 후: 거부
        blockFixed("exe");
        mockMvc.perform(multipart("/api/uploads").file(exe))
                .andExpect(jsonPath("$.results[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.results[0].reasonCode").value("BLOCKED_EXTENSION"))
                .andExpect(jsonPath("$.results[0].message").value(
                        org.hamcrest.Matchers.containsString("exe")));
    }

    @Test
    @DisplayName("차단 우선: 신뢰 목록의 jpg도 커스텀 차단이 이긴다 (명세 1장)")
    void blacklistBeatsTrustList() throws Exception {
        addCustom("jpg");
        // 진짜 JPEG이 아니어도 확장자 차단이 시그니처 검사보다 먼저 (2-2 순서)
        mockMvc.perform(multipart("/api/uploads").file(file("photo.jpg", pngBytes())))
                .andExpect(jsonPath("$.results[0].reasonCode").value("BLOCKED_EXTENSION"));
    }

    @Test
    @DisplayName("zip 정책: 지원하지 않는 압축 형식과 중첩 zip은 거부")
    void archiveRules() throws Exception {
        mockMvc.perform(multipart("/api/uploads")
                        .file(file("files.7z", "7z content".getBytes(StandardCharsets.UTF_8)))
                        .file(file("a.tar.gz", "gz".getBytes(StandardCharsets.UTF_8)))
                        .file(file("nested.zip", zipWith("inner.zip", zipWith("x.txt", new byte[]{1})))))
                .andExpect(jsonPath("$.results[0].reasonCode").value("ARCHIVE_UNSUPPORTED"))
                .andExpect(jsonPath("$.results[1].reasonCode").value("ARCHIVE_UNSUPPORTED"))
                .andExpect(jsonPath("$.results[2].reasonCode").value("ARCHIVE_NESTED"));
    }

    @Test
    @DisplayName("요청당 파일 수 초과는 요청 전체 거부 (TOO_MANY_FILES)")
    void tooManyFiles() throws Exception {
        var request = multipart("/api/uploads");
        for (int i = 0; i < 11; i++) {
            request = request.file(file("f" + i + ".txt", new byte[]{1}));
        }
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_FILES"));
    }

    @Test
    @DisplayName("다운로드: 비신뢰는 octet-stream + attachment + nosniff, 신뢰 이미지는 inline (명세 7장)")
    void downloadHeaders() throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/uploads")
                        .file(file("노트.txt", "메모".getBytes(StandardCharsets.UTF_8)))
                        .file(file("photo.png", pngBytes())))
                .andExpect(status().isOk())
                .andReturn();
        String json = uploaded.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String txtId = JsonPath.read(json, "$.results[0].publicId");
        String pngId = JsonPath.read(json, "$.results[1].publicId");

        mockMvc.perform(get("/api/uploads/" + txtId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment")))
                // 한글 파일명 RFC 5987 인코딩
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''")));

        mockMvc.perform(get("/api/uploads/" + pngId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("inline")));
    }

    @Test
    @DisplayName("판정 기록: 거부·허용 모두 file_upload에 남고 위반 점수가 저장된다 (명세 8-1, 11장)")
    void auditTrail() throws Exception {
        mockMvc.perform(multipart("/api/uploads")
                        .file(file("fake.jpg", pngBytes()))       // CONTENT_MISMATCH = 3점
                        .file(file("ok.txt", new byte[]{1})))     // STORED = 0점
                .andExpect(status().isOk());

        var rows = jdbc.queryForList(
                "SELECT status, reject_reason, violation_score FROM file_upload ORDER BY id");
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(rows.get(0))
                .containsEntry("status", "REJECTED")
                .containsEntry("reject_reason", "CONTENT_MISMATCH");
        org.assertj.core.api.Assertions.assertThat(((Number) rows.get(0).get("violation_score")).intValue())
                .isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(rows.get(1)).containsEntry("status", "STORED");
        org.assertj.core.api.Assertions.assertThat(((Number) rows.get(1).get("violation_score")).intValue())
                .isZero();
    }

    @Test
    @DisplayName("파일명 처리: 한글 86자 거부, 확장자 없는 텍스트 허용, MZ 텍스트 오탐 없음")
    void filenameEdgeCases() throws Exception {
        mockMvc.perform(multipart("/api/uploads")
                        .file(file("가".repeat(86) + ".txt", new byte[]{1}))
                        .file(file("README", "docs".getBytes(StandardCharsets.UTF_8)))
                        .file(file("MZ세대 설문.txt",
                                ("MZ세대 설문조사입니다. " + "문항".repeat(100)).getBytes(StandardCharsets.UTF_8))))
                .andExpect(jsonPath("$.results[0].reasonCode").value("INVALID_FILENAME"))
                .andExpect(jsonPath("$.results[1].status").value("STORED"))
                .andExpect(jsonPath("$.results[2].status").value("STORED"));
    }

    @Test
    @DisplayName("DB 길이를 넘는 이름(확장자 21자, 300자 이름)은 500이 아니라 파일 단위 INVALID_FILENAME으로 기록된다")
    void overlongNamesAreRejectedPerFile() throws Exception {
        mockMvc.perform(multipart("/api/uploads")
                        .file(file("a." + "b".repeat(21), new byte[]{1}))
                        .file(file("x".repeat(300) + ".txt", new byte[]{1}))
                        .file(file("ok.txt", new byte[]{1})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].reasonCode").value("INVALID_FILENAME"))
                .andExpect(jsonPath("$.results[1].reasonCode").value("INVALID_FILENAME"))
                .andExpect(jsonPath("$.results[2].status").value("STORED"));
        // 거부 기록도 저장된다 (요청 전체가 무너지지 않음)
        Integer rejected = jdbc.queryForObject(
                "SELECT count(*) FROM file_upload WHERE reject_reason = 'INVALID_FILENAME'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(rejected).isEqualTo(2);
    }

    @Test
    @DisplayName(".jpeg는 형식이 바뀌지 않으므로 이름을 유지하고 변환 안내도 없다 (webp → PNG만 안내)")
    void jpegKeepsNameWithoutConversionNotice() throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/uploads").file(file("photo.jpeg", jpegBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("STORED"))
                .andExpect(jsonPath("$.results[0].notices").doesNotExist())
                .andReturn();
        String id = JsonPath.read(uploaded.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.results[0].publicId");
        mockMvc.perform(get("/api/uploads/" + id))
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("photo.jpeg")));
    }

    @Test
    @DisplayName("파일별 크기 상한(이미지 외 25MB)은 요청 전체가 아니라 그 파일만 SIZE_EXCEEDED로 거부된다 (명세 6장)")
    void oversizedFileIsRejectedPerFile() throws Exception {
        mockMvc.perform(multipart("/api/uploads")
                        .file(file("big.bin", new byte[26 * 1024 * 1024 + 1]))
                        .file(file("small.txt", new byte[]{1})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].reasonCode").value("SIZE_EXCEEDED"))
                .andExpect(jsonPath("$.results[0].message").value(org.hamcrest.Matchers.containsString("25MB")))
                .andExpect(jsonPath("$.results[1].status").value("STORED"));
    }
}
