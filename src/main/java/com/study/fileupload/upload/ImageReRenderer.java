package com.study.fileupload.upload;

import com.study.fileupload.common.ReasonCode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 이미지 리렌더링 (명세 5-3).
 * 픽셀 상한은 디코딩 전에 헤더의 가로·세로만 읽어 검사하고(메모리 소진 방지),
 * 재인코딩으로 폴리글랏 꼬리와 EXIF 등 메타데이터를 제거한다.
 */
public final class ImageReRenderer {

    private ImageReRenderer() {
    }

    public record Result(byte[] bytes, int width, int height) {
    }

    public static Result rerender(InputStream in, TrustedFileType type, long maxPixels, String displayName) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new RejectException(ReasonCode.CONTENT_MISMATCH,
                        displayName + " — 파일 내용이 확장자와 일치하지 않습니다.", "no image reader");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width * height > maxPixels) {
                    throw new RejectException(ReasonCode.SIZE_EXCEEDED,
                            displayName + " — 이미지 해상도가 허용 범위를 넘습니다.",
                            width + "x" + height + " > " + maxPixels + "px");
                }
                BufferedImage image = reader.read(0);   // gif는 첫 프레임만 (감수, 5-3)
                return new Result(encode(image, type.storeAs()), (int) width, (int) height);
            } finally {
                reader.dispose();
            }
        } catch (RejectException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // 시그니처는 맞지만 이미지로 읽을 수 없음 → 위장 파일
            throw new RejectException(ReasonCode.CONTENT_MISMATCH,
                    displayName + " — 파일 내용이 확장자와 일치하지 않습니다.", "decode failed: " + e.getMessage());
        }
    }

    private static byte[] encode(BufferedImage image, TrustedFileType.StoreAs storeAs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String format = switch (storeAs) {
            case JPEG -> "jpg";
            case PNG -> "png";
            case GIF -> "gif";
            case ORIGINAL -> throw new IllegalStateException("이미지가 아닌 형식은 리렌더링하지 않음");
        };
        BufferedImage target = image;
        if (storeAs == TrustedFileType.StoreAs.JPEG && image.getColorModel().hasAlpha()) {
            // JPEG은 알파 채널이 없으므로 흰 배경에 합성
            target = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = target.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        if (!ImageIO.write(target, format, out)) {
            throw new IOException("no writer for " + format);
        }
        return out.toByteArray();
    }
}
