package com.company.render;

import javax.imageio.*;
import javax.imageio.stream.FileImageInputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 图片与TIFF分页仅在独立工作器解析；重新编码的PNG不携带原图EXIF或其他元数据。 */
public final class ImagePageWorker {
    private static final long MAX_BYTES=1073741824L,MAX_PIXELS=16000000L;
    private static final int MAX_PAGES=10000;
    private ImagePageWorker() { }

    public static int pageCount(Path input) throws IOException {
        try(var source=open(input)) {return source.pages();}
    }

    public static BufferedImage render(Path input,int page) throws IOException {
        try(var source=open(input)) {
            if(page<1||page>source.pages())throw new IOException("图片页码不存在");
            int width=source.reader().getWidth(page-1),height=source.reader().getHeight(page-1);
            if(width<1||height<1||width>10000||height>10000||(long)width*height>MAX_PIXELS)throw new IOException("图片像素超过限制");
            BufferedImage decoded=source.reader().read(page-1),output=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            var graphics=output.createGraphics();
            try {graphics.setColor(Color.WHITE);graphics.fillRect(0,0,width,height);graphics.drawImage(decoded,0,0,null);}
            finally {graphics.dispose();decoded.flush();}
            return output;
        }
    }

    /** 每个进程调用独占读取器，释放本地文件句柄；多页只解析页目录而不同时解码全部页面。 */
    private record Source(FileImageInputStream input,ImageReader reader,int pages) implements AutoCloseable {
        @Override public void close() throws IOException {try {reader.dispose();}finally {input.close();}}
    }
    private static Source open(Path input) throws IOException {
        if(!input.isAbsolute()||!Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS)||Files.size(input)==0||Files.size(input)>MAX_BYTES)throw new IOException("图片输入无效");
        for(Path node=input;node!=null;node=node.getParent())if(Files.isSymbolicLink(node))throw new IOException("图片路径包含链接");
        var stream=new FileImageInputStream(input.toFile());ImageReader reader=null;
        try {
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IOException("图片格式不可解析");
            reader=readers.next();if(!Set.of("png","jpeg","jpg","tiff","tif").contains(reader.getFormatName().toLowerCase(Locale.ROOT)))throw new IOException("图片格式不受支持");
            reader.setInput(stream,false,true);int pages=reader.getNumImages(true);
            if(pages<1||pages>MAX_PAGES)throw new IOException("图片页数超过限制");
            return new Source(stream,reader,pages);
        }catch(IOException|RuntimeException failure){if(reader!=null)reader.dispose();stream.close();throw failure;}
    }
}
