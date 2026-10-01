package com.study.fileupload.upload;

import java.util.Optional;

/**
 * 실행 파일 시그니처 검사 (명세 5-1). 신뢰·비신뢰와 무관하게 모든 파일에 적용 —
 * 고정 확장자가 모두 unCheck여도 유지되는 최소 방어선.
 *
 * 판별 불가(한계, 명세 17장): bat/cmd/js/com, 셔뱅 없는 스크립트, MSI.
 */
public final class ExecutableSignatureDetector {

    private ExecutableSignatureDetector() {
    }

    /**
     * @param head     파일 앞부분 (최대 8KB)
     * @param fileSize 파일 전체 크기 — PE 헤더 오프셋이 파일 밖을 가리키면 PE일 수 없으므로
     *                 "MZ세대.txt" 같은 텍스트의 오탐을 줄이는 데 쓴다
     * @return 탐지된 실행 형식 이름. 없으면 empty
     */
    public static Optional<String> detect(byte[] head, long fileSize) {
        if (head.length >= 2 && head[0] == 'M' && head[1] == 'Z') {
            Optional<String> pe = checkPe(head, fileSize);
            if (pe.isPresent()) {
                return pe;
            }
        }
        if (startsWith(head, 0x7F, 'E', 'L', 'F')) {
            return Optional.of("elf");
        }
        if (startsWith(head, 0xFE, 0xED, 0xFA, 0xCE) || startsWith(head, 0xFE, 0xED, 0xFA, 0xCF)
                || startsWith(head, 0xCE, 0xFA, 0xED, 0xFE) || startsWith(head, 0xCF, 0xFA, 0xED, 0xFE)) {
            return Optional.of("mach-o");
        }
        // Mach-O 유니버설과 Java 클래스가 같은 값 — 둘 다 실행 가능하므로 구분 없이 차단
        if (startsWith(head, 0xCA, 0xFE, 0xBA, 0xBE)) {
            return Optional.of("mach-o/java-class");
        }
        if (head.length >= 2 && head[0] == '#' && head[1] == '!') {
            return Optional.of("script(shebang)");
        }
        return Optional.empty();
    }

    /**
     * "MZ" 두 바이트만으로 판정하면 "MZ세대.txt" 같은 텍스트가 오탐된다.
     * 오프셋 0x3C가 가리키는 위치의 "PE\0\0"까지 확인한다.
     * - 오프셋이 파일 크기 밖 → PE일 수 없음 (텍스트의 임의 바이트) → 통과
     * - 오프셋이 파일 안이지만 읽은 8KB 밖 → 확인 불가 → 보수적으로 거부 (명세 5-1)
     */
    private static Optional<String> checkPe(byte[] head, long fileSize) {
        if (head.length < 0x40) {
            return Optional.empty();   // e_lfanew 필드조차 없으면 PE일 수 없음
        }
        long peOffset = ((long) (head[0x3C] & 0xFF))
                | (head[0x3D] & 0xFF) << 8
                | (head[0x3E] & 0xFF) << 16
                | (long) (head[0x3F] & 0xFF) << 24;
        if (peOffset + 4 > fileSize) {
            return Optional.empty();   // PE 헤더가 파일 밖 → PE 아님
        }
        if (peOffset + 4 > head.length) {
            return Optional.of("pe(header-out-of-range)");   // 파일 안인데 확인 범위 밖 → 보수적 거부
        }
        int off = (int) peOffset;
        if (head[off] == 'P' && head[off + 1] == 'E' && head[off + 2] == 0 && head[off + 3] == 0) {
            return Optional.of("pe");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int... expected) {
        if (data.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
