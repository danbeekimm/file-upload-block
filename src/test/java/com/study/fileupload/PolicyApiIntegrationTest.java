package com.study.fileupload;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;

/** 정책 관리 API (명세 4장, 15장) — 실제 PostgreSQL 제약과 함께 검증 */
@SpringBootTest
@AutoConfigureMockMvc
class PolicyApiIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("고정 확장자 7개는 기본 unCheck로 조회된다")
    void fixedDefaultsUnchecked() throws Exception {
        mockMvc.perform(get("/api/admin/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixed.length()").value(7))
                .andExpect(jsonPath("$.fixed[?(@.blocked == true)]").isEmpty())
                .andExpect(jsonPath("$.fixed[0].extension").value("bat"))
                .andExpect(jsonPath("$.customCount").value(0))
                .andExpect(jsonPath("$.customMax").value(200));
    }

    @Test
    @DisplayName("고정 토글: check 후 새로고침에도 유지, 오래된 version은 409 (명세 18장)")
    void fixedToggleAndVersionConflict() throws Exception {
        mockMvc.perform(put("/api/admin/policies/fixed/exe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocked\":true,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.version").value(1));

        // 새로고침 = 재조회에도 유지 (DB 저장 확인)
        mockMvc.perform(get("/api/admin/policies"))
                .andExpect(jsonPath("$.fixed[?(@.extension == 'exe')].blocked").value(true));

        // 다른 사용자가 본 오래된 version으로 토글 → 409
        mockMvc.perform(put("/api/admin/policies/fixed/exe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocked\":false,\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    @DisplayName("커스텀 추가: .SH는 sh로 보정 저장되고 보정 안내를 반환한다 (명세 18장)")
    void customAddNormalizes() throws Exception {
        mockMvc.perform(post("/api/admin/policies/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\":\".SH\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.extension").value("sh"))
                .andExpect(jsonPath("$.corrected").value(true))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("중복 추가 방지: sh 추가 후 sh/SH 재추가는 409")
    void customDuplicateRejected() throws Exception {
        mockMvc.perform(post("/api/admin/policies/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\":\"sh\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/admin/policies/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\":\"sh\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EXTENSION"));
        mockMvc.perform(post("/api/admin/policies/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\":\"SH\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("고정 확장자와 충돌: 커스텀에 exe 추가는 409 (명세 18장)")
    void customFixedConflict() throws Exception {
        mockMvc.perform(post("/api/admin/policies/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\":\"exe\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIXED_CONFLICT"));
    }

    @Test
    @DisplayName("형식에 맞지 않는 입력은 400 (공백, 특수문자, 복합 확장자)")
    void customInvalidInput() throws Exception {
        for (String bad : new String[]{"s h", "sh!", "tar.gz", "한글"}) {
            mockMvc.perform(post("/api/admin/policies/custom")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"extension\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_EXTENSION"));
        }
    }

    @Test
    @DisplayName("200개 제한: 도달 시 422, 삭제 후 추가 가능")
    void customLimit() throws Exception {
        Long policyId = jdbc.queryForObject(
                "SELECT id FROM upload_policy WHERE name = 'default'", Long.class);
        jdbc.update("INSERT INTO extension_rule (policy_id, extension, rule_type, blocked) "
                + "SELECT ?, 'x' || i, 'CUSTOM', true FROM generate_series(1, 200) AS i", policyId);

        mockMvc.perform(post("/api/admin/policies/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\":\"zzz\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CUSTOM_LIMIT_EXCEEDED"));

        mockMvc.perform(delete("/api/admin/policies/custom/x1"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/admin/policies/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\":\"zzz\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("삭제는 멱등: 이미 없는 커스텀 삭제도 204")
    void deleteIsIdempotent() throws Exception {
        mockMvc.perform(delete("/api/admin/policies/custom/none"))
                .andExpect(status().isNoContent());
        // 고정 확장자를 커스텀 삭제 API로 지울 수 없다 (204이지만 행 유지)
        mockMvc.perform(delete("/api/admin/policies/custom/exe"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/admin/policies"))
                .andExpect(jsonPath("$.fixed.length()").value(7));
    }

    @Test
    @DisplayName("정책 변경은 같은 트랜잭션에서 이력에 남는다 (명세 4-4)")
    void changesAreLogged() throws Exception {
        mockMvc.perform(post("/api/admin/policies/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\":\"sh\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/admin/policies/fixed/js")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocked\":true,\"version\":0}"))
                .andExpect(status().isOk());

        Integer logs = jdbc.queryForObject("SELECT count(*) FROM policy_change_log", Integer.class);
        org.assertj.core.api.Assertions.assertThat(logs).isEqualTo(2);
    }
}
