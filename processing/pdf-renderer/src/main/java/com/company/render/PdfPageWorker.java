package com.company.render;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;

/** 独立 PDF 单页渲染原型；标准输出仅发送页面 PNG，权限与水印由可信调用方另行执行。 */
public final class PdfPageWorker {
    /** 档案原件上限 1 GiB；页面与 DPI 限制是本机原型保护阈值，需结合样本和机器容量验证。 */
    private static final long MAX_BYTES=1073741824L,MAX_PIXELS=16000000L;
    private static final int MAX_PAGES=10000;
    private PdfPageWorker() { }
    /** 元数据同样在受限进程解析，仅返回受控页数，不返回正文或源路径。 */
    public static int pageCount(Path input) throws IOException {
        if(!input.isAbsolute() || !Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS) || Files.size(input)>MAX_BYTES) throw new IOException("PDF输入无效");
        for(Path node=input;node!=null;node=node.getParent()) if(Files.isSymbolicLink(node)) throw new IOException("PDF路径包含链接");
        try(PDDocument document=Loader.loadPDF(input.toFile())) {
            int pages=document.getNumberOfPages();
            if(document.isEncrypted() || pages<1 || pages>MAX_PAGES) throw new IOException("PDF不可解析");
            return pages;
        }
    }

    /** 按只读绝对路径与从 1 开始的页码生成单页；不返回原 PDF、不写入源文件。 */
    public static BufferedImage render(Path input,int page,int dpi) throws IOException {
        if(!input.isAbsolute() || page<1 || dpi<72 || dpi>150) throw new IOException("PDF 页码或分辨率不在允许范围");
        Path source=input.normalize();
        for(Path node=source;node!=null;node=node.getParent()) if(Files.isSymbolicLink(node)) throw new IOException("PDF 路径不能包含链接");
        if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS) || Files.size(source)==0 || Files.size(source)>MAX_BYTES) throw new IOException("PDF 原件不存在或超出大小限制");
        // 文件式随机访问避免将整个 1 GiB 原件读进堆；调用方还须使用受限工作进程。
        try(PDDocument document=Loader.loadPDF(source.toFile())) {
            if(document.isEncrypted()) throw new IOException("加密 PDF 不能直接渲染");
            int pages=document.getNumberOfPages();
            if(pages<1 || pages>MAX_PAGES || page>pages) throw new IOException("PDF 页码不存在或页数超过限制");
            PDRectangle box=document.getPage(page-1).getCropBox();
            double width=box.getWidth()*dpi/72.0,height=box.getHeight()*dpi/72.0;
            if(!Double.isFinite(width) || !Double.isFinite(height) || width<=0 || height<=0 || width>10000 || height>10000 || Math.ceil(width)*Math.ceil(height)>MAX_PIXELS) throw new IOException("PDF 页面像素超过限制");
            PDFRenderer renderer=new PDFRenderer(document);
            return renderer.renderImageWithDPI(page-1,dpi,ImageType.RGB);
        }
    }
    /** PDF与图片共用受限入口；外部请求不能选择处理程序或取得原件路径。 */
    public static void main(String[] args) {
        try {
            if(args.length==3 && args[1].equals("export")) {UsagePdfWorker.export(Path.of(args[0]),Path.of(args[2]),System.out);return;}
            if(args.length==2 && args[1].equals("info")) { System.out.print("{\"pageCount\":"+pageCount(Path.of(args[0]))+"}"); return; }
            if(args.length==2 && args[1].equals("image-info")) {System.out.print("{\"pageCount\":"+ImagePageWorker.pageCount(Path.of(args[0]))+"}");return;}
            if(args.length!=3) throw new IOException("参数不正确");
            BufferedImage page=args[1].equals("image-page")?ImagePageWorker.render(Path.of(args[0]),Integer.parseInt(args[2])):render(Path.of(args[0]),Integer.parseInt(args[1]),Integer.parseInt(args[2]));
            // 页面只经内存流交给受控调用方，不使用系统默认临时文件。
            try(MemoryCacheImageOutputStream output=new MemoryCacheImageOutputStream(System.out)) { if(!ImageIO.write(page,"png",output)) throw new IOException("PNG 编码不可用"); output.flush(); }
        } catch(Exception error) { System.err.println("受控页面处理失败，请查看任务状态。"); System.exit(2); }
    }
}
