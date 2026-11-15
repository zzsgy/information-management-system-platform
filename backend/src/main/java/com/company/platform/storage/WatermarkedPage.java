package com.company.platform.storage;

import com.company.platform.ApiFault;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** 本人阅读页水印算法原型；只处理已渲染 PNG，不是 PDF 渲染器或文件交付接口。 */
public final class WatermarkedPage {
    /** 限制输入与像素，防止解压后的图像占满工作进程；部署时仍须单独进程限额。 */
    private static final int MAX_INPUT=32*1024*1024, MAX_PIXELS=16000000;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
    private WatermarkedPage() { }
    /** 上游从当前会话和授权文件版本构造，不能使用浏览器提交的身份水印。 */
    public record Stamp(
        /** 经过授权的员工姓名。 */ String name,
        /** 当前会话对应的工号。 */ String employeeNo,
        /** 固定档案文件编号。 */ String fileNumber,
        /** 服务端生成的当前时刻。 */ Instant serverTime) { }
    /** 以受限 PNG 输入生成新的个人水印图像；每次调用独立输出，不共享成品缓存。 */
    public static byte[] render(byte[] input,Stamp stamp) {
        if(stamp==null || stamp.serverTime()==null) throw ApiFault.invalid("水印身份或时间无效");
        if(input==null || input.length>MAX_INPUT || input.length<8 || !Arrays.equals(Arrays.copyOf(input,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) throw ApiFault.invalid("阅读页面不是允许的 PNG 图像");
        String mark=field(stamp.name())+"  "+field(stamp.employeeNo())+"  "+field(stamp.fileNumber())+"  "+TIME.format(Objects.requireNonNull(stamp.serverTime()));
        try(MemoryCacheImageInputStream stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(stream); if(!readers.hasNext()) throw ApiFault.invalid("阅读页面无法解析");
            ImageReader reader=readers.next();
            try {
                reader.setInput(stream,true,true); int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || (long)width*height>MAX_PIXELS) throw ApiFault.invalid("阅读页面像素超过上限");
                BufferedImage source=reader.read(0); if(source==null) throw ApiFault.invalid("阅读页面无法解析");
                // 索引色源图先转换为真彩色，避免有限调色板吞掉半透明水印。
                BufferedImage page=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
                Graphics2D copy=page.createGraphics(); try { copy.setColor(Color.WHITE); copy.fillRect(0,0,width,height); copy.drawImage(source,0,0,null); } finally { copy.dispose(); }
                Graphics2D graphics=page.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.16f));
                    graphics.setColor(new Color(45,65,51)); graphics.setFont(new Font("Microsoft YaHei",Font.PLAIN,Math.max(14,Math.min(26,width/45))));
                    graphics.rotate(-Math.PI/10,width/2.0,height/2.0);
                    int rowHeight=Math.max(110,height/5), spacing=Math.max(340,graphics.getFontMetrics().stringWidth(mark)+80);
                    for(int y=-height;y<height*2;y+=rowHeight) for(int x=-width;x<width*2;x+=spacing) graphics.drawString(mark,x,y);
                } finally { graphics.dispose(); }
                // 使用内存输出缓存，不让 ImageIO 将敏感阅读页面写入系统默认临时目录。
                ByteArrayOutputStream result=new ByteArrayOutputStream();
                try(MemoryCacheImageOutputStream output=new MemoryCacheImageOutputStream(result)) { if(!ImageIO.write(page,"png",output)) throw ApiFault.protection(); }
                return result.toByteArray();
            } finally { reader.dispose(); }
        } catch(ApiFault error) { throw error; }
        catch(Exception error) { throw ApiFault.protection(); }
    }
    /** 拒绝缺失身份、超长或控制字符，避免水印信息被换行隐藏。 */
    private static String field(String text) {
        if(text==null || text.isBlank() || text.length()>128 || text.chars().anyMatch(Character::isISOControl)) throw ApiFault.invalid("水印身份或文件编号无效");
        return text.trim();
    }
}
