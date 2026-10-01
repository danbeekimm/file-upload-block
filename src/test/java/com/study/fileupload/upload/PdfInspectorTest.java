package com.study.fileupload.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PDF 이름 토큰 검색 (명세 5-4) */
class PdfInspectorTest {

    private PdfInspector.Result scan(String content) throws IOException {
        return PdfInspector.scan(new ByteArrayInputStream(content.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    @DisplayName("/Launch, /EmbeddedFile은 거부 대상")
    void rejectNames() throws IOException {
        assertThat(scan("%PDF-1.4 1 0 obj << /Type /Action /S /Launch >>").reject()).isTrue();
        assertThat(scan("%PDF-1.4 << /Type /EmbeddedFile >>").reject()).isTrue();
    }

    @Test
    @DisplayName("이름 인코딩 우회: /J#61vaScript = /JavaScript → attachment로 낮춤 (명세 18장)")
    void hexEncodedNameIsDecoded() throws IOException {
        PdfInspector.Result result = scan("%PDF-1.4 << /S /J#61vaScript /JS (app.alert(1)) >>");
        assertThat(result.reject()).isFalse();
        assertThat(result.downgrade()).isTrue();
        assertThat(result.foundNames()).contains("JavaScript");
    }

    @Test
    @DisplayName("토큰 경계: /JS는 잡고 /JSON은 잡지 않는다")
    void tokenBoundary() throws IOException {
        assertThat(scan("%PDF-1.4 << /JS (code) >>").downgrade()).isTrue();
        assertThat(scan("%PDF-1.4 << /JSON (data) >>").downgrade()).isFalse();
    }

    @Test
    @DisplayName("/Encrypt, /ObjStm, /AA는 attachment로 낮춤")
    void downgradeNames() throws IOException {
        assertThat(scan("%PDF-1.5 << /Encrypt 5 0 R >>").downgrade()).isTrue();
        assertThat(scan("%PDF-1.5 << /Type /ObjStm /N 10 >>").downgrade()).isTrue();
        assertThat(scan("%PDF-1.5 << /AA << /O 3 0 R >> >>").downgrade()).isTrue();
    }

    @Test
    @DisplayName("/OpenAction 단독은 통과하되 발견 사실만 기록 (명세 18장)")
    void openActionAlonePasses() throws IOException {
        PdfInspector.Result result = scan("%PDF-1.4 << /OpenAction [3 0 R /Fit] >>");
        assertThat(result.reject()).isFalse();
        assertThat(result.downgrade()).isFalse();
        assertThat(result.openAction()).isTrue();
    }

    @Test
    @DisplayName("위험 요소가 없는 PDF는 inline 가능")
    void cleanPdf() throws IOException {
        PdfInspector.Result result = scan("%PDF-1.4 1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj");
        assertThat(result.reject()).isFalse();
        assertThat(result.downgrade()).isFalse();
        assertThat(result.openAction()).isFalse();
    }
}
