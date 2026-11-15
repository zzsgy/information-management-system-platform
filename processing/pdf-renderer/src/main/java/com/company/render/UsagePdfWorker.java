package com.company.render;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** 使用副本处理器：栅格化后重建PDF，原签名、脚本、附件及隐藏文本不会混入使用副本。 */
public final class UsagePdfWorker {
    private UsagePdfWorker() { }
    /** 可信计划来自私有文件，最多300个输出页；原件始终只读。 */
    public static void export(Path input,Path planFile,OutputStream output) throws IOException {
        PdfPageWorker.pageCount(input);Properties plan=new Properties();
        try(Reader reader=Files.newBufferedReader(planFile)) {plan.load(reader);}
        boolean unwatermarked="true".equals(plan.getProperty("unwatermarked"));
        String[] pages=plan.getProperty("pages").split(",");int copies=Integer.parseInt(plan.getProperty("copies"));
        if(copies<1||copies>100||pages.length*copies>300) throw new IOException("输出页数超限");
        try(PDDocument source=Loader.loadPDF(input.toFile());PDDocument result=new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            PDFRenderer renderer=new PDFRenderer(source);
            for(int copy=1;copy<=copies;copy++) for(String value:pages) {
                int page=Integer.parseInt(value);if(page<1||page>source.getNumberOfPages()) throw new IOException("页码越界");
                PDRectangle box=source.getPage(page-1).getCropBox();double width=box.getWidth()*150/72.0,height=box.getHeight()*150/72.0,pixels=Math.ceil(width)*Math.ceil(height);
                if(!Double.isFinite(pixels)||width<=0||height<=0||width>10000||height>10000||pixels>16000000) throw new IOException("页面尺寸超限");
                BufferedImage image=renderer.renderImageWithDPI(page-1,150,ImageType.RGB);
                if(!unwatermarked)image=watermarked(image,plan,page,copy);
                PDPage target=new PDPage(new PDRectangle(image.getWidth()*72f/150,image.getHeight()*72f/150));result.addPage(target);
                try(PDPageContentStream stream=new PDPageContentStream(result,target)) {stream.drawImage(JPEGFactory.createFromImage(result,image,0.92f),0,0,target.getMediaBox().getWidth(),target.getMediaBox().getHeight());}
                image.flush();
            }
            result.getDocumentInformation().setTitle("个人授权使用副本");
            result.getDocumentInformation().setSubject(unwatermarked?"获准无水印使用副本":"水印使用副本；不承诺原件数字签名有效性");
            result.save(output);
        }
    }
    /** 水印页脚单独留白，原始页面底部内容随栅格副本保留。 */
    private static BufferedImage watermarked(BufferedImage original,Properties plan,int page,int copy) {
        int fontSize=Math.max(15,original.getWidth()/52);
        int footer=(footerLines(plan,original.getWidth(),fontSize,page,copy).size()+1)*(fontSize+8);
        BufferedImage image=new BufferedImage(original.getWidth(),original.getHeight()+footer,BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas=image.createGraphics();
        try{
            canvas.setColor(Color.WHITE);
            canvas.fillRect(0,0,image.getWidth(),image.getHeight());
            canvas.drawImage(original,0,0,null);
        }finally{
            canvas.dispose();
            original.flush();
        }
        stamp(image,plan,page,copy);
        return image;
    }
    /** 水印烘入每页像素；姓名、工号、服务端时间和独立文件号均可见。 */
    private static void stamp(BufferedImage image,Properties plan,int page,int copy) {
        Graphics2D graphics=image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            String identity=plan.getProperty("name")+" / "+plan.getProperty("employeeNo"),scope=plan.getProperty("fileNo")+" / "+plan.getProperty("at");
            int fontSize=Math.max(15,image.getWidth()/52);graphics.setFont(new Font("Microsoft YaHei",Font.PLAIN,fontSize));graphics.setColor(new Color(65,83,83));graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.24f));
            var lines=footerLines(plan,image.getWidth(),fontSize,page,copy);int footer=(lines.size()+1)*(fontSize+8),top=image.getHeight()-footer;
            for(int y=180;y<top;y+=340) for(int x=30;x<image.getWidth();x+=Math.max(640,image.getWidth()/2)) {graphics.rotate(-Math.PI/9,x,y);graphics.drawString(identity,x,y);graphics.drawString(scope,x,y+fontSize+8);graphics.rotate(Math.PI/9,x,y);}
            graphics.setComposite(AlphaComposite.SrcOver);graphics.setColor(Color.WHITE);graphics.fillRect(0,top,image.getWidth(),footer);
            graphics.setColor(new Color(45,70,70));int baseline=top+fontSize+8;for(String line:lines){graphics.drawString(line,20,baseline);baseline+=fontSize+8;}
        } finally {graphics.dispose();}
    }
    /** 长姓名和工号按实际字宽换行，不能缩成不可辨识的小字或裁掉关键身份字段。 */
    private static java.util.List<String> footerLines(Properties plan,int width,int size,int page,int copy) {
        var result=new java.util.ArrayList<String>();Font font=new Font("Microsoft YaHei",Font.PLAIN,size);var metrics=new java.awt.font.FontRenderContext(null,true,true);
        for(String text:java.util.List.of(plan.getProperty("name")+" / "+plan.getProperty("employeeNo"),plan.getProperty("fileNo")+" / "+plan.getProperty("at"),"使用副本 · "+("PRINT".equals(plan.getProperty("action"))?"打印":"下载")+" | "+plan.getProperty("usage")+" | 原第"+page+"页 / 第"+copy+"份")) {
            StringBuilder line=new StringBuilder();for(int point:text.codePoints().toArray()){String next=new String(Character.toChars(point));if(!line.isEmpty()&&font.getStringBounds(line.toString()+next,metrics).getWidth()>width-40){result.add(line.toString());line.setLength(0);}line.append(next);}if(!line.isEmpty())result.add(line.toString());
        }return result;
    }
}
