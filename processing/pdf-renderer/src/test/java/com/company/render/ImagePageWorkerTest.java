package com.company.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.*;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Arrays;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class ImagePageWorkerTest {
    @TempDir Path root;

    @Test void rendersPngAndJpegPixelsWithoutChangingOriginal() throws Exception {
        for(String format:Arrays.asList("png","jpeg")) {
            Path source=root.resolve("sample."+format);ImageIO.write(image(120,80,Color.RED),format,source.toFile());
            byte[] original=Files.readAllBytes(source);assertEquals(1,ImagePageWorker.pageCount(source));
            var rendered=ImagePageWorker.render(source,1);assertEquals(120,rendered.getWidth());assertEquals(80,rendered.getHeight());
            assertTrue(new Color(rendered.getRGB(5,5)).getRed()>240);assertArrayEquals(original,Files.readAllBytes(source));
            assertThrows(java.io.IOException.class,()->ImagePageWorker.render(source,2));
        }
    }

    @Test void tiffPagesAreDecodedSeparatelyInOriginalOrder() throws Exception {
        Path source=root.resolve("two-pages.tiff");var writer=ImageIO.getImageWritersByFormatName("TIFF").next();
        try(var output=new FileImageOutputStream(source.toFile())) {
            writer.setOutput(output);writer.prepareWriteSequence(null);
            writer.writeToSequence(new IIOImage(image(90,60,Color.RED),null,null),null);
            writer.writeToSequence(new IIOImage(image(60,90,Color.BLUE),null,null),null);writer.endWriteSequence();
        }finally {writer.dispose();}
        assertEquals(2,ImagePageWorker.pageCount(source));
        var first=ImagePageWorker.render(source,1);var second=ImagePageWorker.render(source,2);
        assertEquals(90,first.getWidth());assertEquals(Color.RED.getRGB(),first.getRGB(5,5));
        assertEquals(60,second.getWidth());assertEquals(Color.BLUE.getRGB(),second.getRGB(5,5));
    }

    /** 只修改真实PNG的IHDR及校验值，不分配大图；读取器必须在解码前拒绝巨大像素声明。 */
    @Test void oversizedDimensionsFailBeforePixelAllocation() throws Exception {
        Path source=root.resolve("huge.png");ImageIO.write(image(2,2,Color.RED),"png",source.toFile());byte[] bytes=Files.readAllBytes(source);
        ByteBuffer.wrap(bytes).putInt(16,20000).putInt(20,20000);CRC32 crc=new CRC32();crc.update(bytes,12,17);ByteBuffer.wrap(bytes).putInt(29,(int)crc.getValue());Files.write(source,bytes);
        assertThrows(java.io.IOException.class,()->ImagePageWorker.render(source,1));
    }

    @Test void malformedRelativeAndUnsupportedInputsAreRejected() throws Exception {
        Path broken=root.resolve("broken.png");Files.writeString(broken,"not an image");
        assertThrows(java.io.IOException.class,()->ImagePageWorker.pageCount(broken));
        assertThrows(java.io.IOException.class,()->ImagePageWorker.pageCount(Path.of("relative.png")));
        Path gif=root.resolve("unsupported.gif");ImageIO.write(image(4,4,Color.RED),"gif",gif.toFile());
        assertThrows(java.io.IOException.class,()->ImagePageWorker.render(gif,1));
    }

    private BufferedImage image(int width,int height,Color color) {
        var result=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var graphics=result.createGraphics();
        try {graphics.setColor(color);graphics.fillRect(0,0,width,height);}finally {graphics.dispose();}return result;
    }
}
