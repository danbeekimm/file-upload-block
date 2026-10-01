package com.study.fileupload;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** 클라이언트 오류는 500이 아니라 400/404/405로 응답하고, 형식은 {code, message} (명세 15장) */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorResponseIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("본문 검증 실패(@NotNull)는 400 INVALID_REQUEST + 필드별 detail")
    void validationFailure() throws Exception {
        mockMvc.perform(put("/api/admin/policies/fixed/exe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail.blocked").exists())
                .andExpect(jsonPath("$.detail.version").exists());

        // @Size(max = 100) 초과
        mockMvc.perform(post("/api/admin/policies/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\":\"" + "a".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("깨진 JSON과 타입 불일치(UUID가 아닌 id)는 400")
    void malformedInputs() throws Exception {
        mockMvc.perform(put("/api/admin/policies/fixed/exe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/uploads/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("업로드에 files 파트가 없거나 멀티파트가 아니면 400 NO_FILES")
    void uploadWithoutFiles() throws Exception {
        mockMvc.perform(multipart("/api/uploads")
                        .file(new MockMultipartFile("other", "t.txt", "text/plain", new byte[]{1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_FILES"));

        mockMvc.perform(post("/api/uploads"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_FILES"));
    }

    @Test
    @DisplayName("없는 경로는 404, 허용되지 않은 메서드는 405")
    void notFoundAndMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(delete("/api/admin/policies"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
