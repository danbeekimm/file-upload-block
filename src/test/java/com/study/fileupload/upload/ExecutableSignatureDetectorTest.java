package com.study.fileupload.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실행 파일 시그니처 (명세 5-1) */
class ExecutableSignatureDetectorTest {

    /** 유효한 최소 PE: MZ + e_lfanew(0x3C)=0x80 + 그 위치에 PE\0\0 */
    static byte[] minimalPe() {
        byte[] data = new byte[0x100];
        data[0] = 'M';
        data[1] = 'Z';
        data[0x3C] = (byte) 0x80;
        data[0x80] = 'P';
        data[0x81] = 'E';
        return data;
    }

    @Test
    @DisplayName("PE(exe/scr/cpl/dll)를 탐지한다")
    void detectsPe() {
        byte[] pe = minimalPe();
        assertThat(ExecutableSignatureDetector.detect(pe, pe.length)).contains("pe");
    }

    @Test
    @DisplayName("'MZ세대 설문.txt' 같은 텍스트는 오탐하지 않는다 (명세 18장)")
    void mzTextIsNotPe() {
        // MZ로 시작하는 한글 텍스트 — 0x3C의 임의 바이트는 파일 크기 밖을 가리킴
        String text = "MZ세대 설문조사입니다. " + "내용".repeat(100);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        assertThat(ExecutableSignatureDetector.detect(data, data.length)).isEmpty();
    }

    @Test
    @DisplayName("PE 헤더가 파일 안인데 읽은 8KB 밖이면 보수적으로 거부한다")
    void peHeaderBeyondHeadIsConservativelyRejected() {
        byte[] head = new byte[0x40];
        head[0] = 'M';
        head[1] = 'Z';
        head[0x3C] = 0x00;
        head[0x3D] = 0x40;   // e_lfanew = 0x4000 (16KB) — head(64B) 밖, 파일(1MB) 안
        assertThat(ExecutableSignatureDetector.detect(head, 1024 * 1024))
                .contains("pe(header-out-of-range)");
    }

    @Test
    @DisplayName("ELF를 탐지한다 — 확장자 없는 리눅스 실행 파일의 유일한 판정 수단")
    void detectsElf() {
        byte[] elf = {0x7F, 'E', 'L', 'F', 2, 1, 1, 0};
        assertThat(ExecutableSignatureDetector.detect(elf, elf.length)).contains("elf");
    }

    @Test
    @DisplayName("Mach-O와 Java 클래스를 탐지한다")
    void detectsMachOAndJavaClass() {
        assertThat(ExecutableSignatureDetector.detect(
                new byte[]{(byte) 0xFE, (byte) 0xED, (byte) 0xFA, (byte) 0xCF}, 4)).contains("mach-o");
        assertThat(ExecutableSignatureDetector.detect(
                new byte[]{(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE}, 4)).contains("mach-o");
        assertThat(ExecutableSignatureDetector.detect(
                new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}, 4)).contains("mach-o/java-class");
    }

    @Test
    @DisplayName("셔뱅 스크립트를 탐지한다")
    void detectsShebang() {
        byte[] script = "#!/bin/bash\nrm -rf /\n".getBytes(StandardCharsets.UTF_8);
        assertThat(ExecutableSignatureDetector.detect(script, script.length)).contains("script(shebang)");
    }

    @Test
    @DisplayName("일반 텍스트·이미지는 탐지하지 않는다")
    void ignoresBenign() {
        byte[] text = "hello world".getBytes(StandardCharsets.UTF_8);
        assertThat(ExecutableSignatureDetector.detect(text, text.length)).isEmpty();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        assertThat(ExecutableSignatureDetector.detect(png, png.length)).isEmpty();
    }
}
