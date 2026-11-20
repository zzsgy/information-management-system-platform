package com.company.platform;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/** 内联图片共用 PNG、JPEG 的尺寸与字节校验，去掉原文件的附带信息。 */
public final class InlineImages {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PIXELS = 12_000_000;

    private InlineImages() {}

    /** 截图格式和字节来自服务端解码，客户端只提供内容和正文内的标识。 */
    public record Image(String mediaType, byte[] bytes) {}

    /** 校验每张图片的标识、格式、体积和解码尺寸，拒绝重复标识。 */
    public static Map<UUID, Image> prepare(Object value) {
        Map<UUID, Image> images = new LinkedHashMap<>();
        if (value == null) return images;
        for (var attachment : Inputs.objects(value, 3)) {
            Inputs.only(attachment, "id", "data");
            UUID id = Inputs.uuid(attachment.get("id"));
            if (images.containsKey(id)) throw ApiFault.invalid("不能重复添加同一张截图");
            images.put(id, decode(Inputs.text(attachment, "data", (MAX_BYTES + 2) / 3 * 4)));
        }
        return images;
    }

    /** 先读尺寸再解码，编码时去掉附带信息，整个过程中不写临时文件。 */
    private static Image decode(String data) {
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            if (bytes.length > MAX_BYTES) throw ApiFault.invalid("每张截图最大 2 MB");
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw ApiFault.invalid("截图必须是有效的 PNG 或 JPEG 图片");
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("png", "jpeg").contains(format)) throw ApiFault.invalid("截图仅支持 PNG 和 JPEG 格式");
                    reader.setInput(input, true, true);
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS)
                        throw ApiFault.invalid("单张截图最多支持 1200 万像素");
                    var result = new ByteArrayOutputStream();
                    try (var output = new MemoryCacheImageOutputStream(result)) {
                        if (!ImageIO.write(reader.read(0), format, output)) throw ApiFault.invalid("无法保存截图");
                    }
                    if (result.size() > MAX_BYTES) throw ApiFault.invalid("截图处理后超过 2 MB，请缩小后重试");
                    return new Image("image/" + format, result.toByteArray());
                } finally {
                    reader.dispose();
                }
            }
        } catch (IllegalArgumentException | IOException failure) {
            throw ApiFault.invalid("截图无法读取，请重新粘贴或选择图片");
        }
    }
}
