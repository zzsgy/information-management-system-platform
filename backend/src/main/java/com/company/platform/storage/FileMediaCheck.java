package com.company.platform.storage;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 根据实测文件头核对已验证格式；未实现检测的格式保持拒绝，不依赖浏览器声明。 */
public final class FileMediaCheck {
    private FileMediaCheck() { }
    /** PDF、图片按实测头核对；凭据录音额外检查容器结构和帧边界。 */
    public static String detect(Path source,String name,String declared) throws IOException {
        byte[] header; try(InputStream in=Files.newInputStream(source)) { header=in.readNBytes(16); }
        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        String media=null;
        if(header.length>=5 && new String(header,0,5,StandardCharsets.US_ASCII).equals("%PDF-") && ext.equals("pdf")) media="application/pdf";
        else if(header.length>=8 && Arrays.equals(Arrays.copyOf(header,8),new byte[]{(byte)137,80,78,71,13,10,26,10}) && ext.equals("png")) media="image/png";
        else if(header.length>=3 && header[0]==(byte)255 && header[1]==(byte)216 && header[2]==(byte)255 && Set.of("jpg","jpeg").contains(ext)) media="image/jpeg";
        else if(header.length>=4 && ((header[0]=='I'&&header[1]=='I'&&header[2]==42&&header[3]==0)||(header[0]=='M'&&header[1]=='M'&&header[2]==0&&header[3]==42)) && Set.of("tif","tiff").contains(ext)) media="image/tiff";
        if(media==null)media=DriveMediaCheck.detect(source,ext);
        if(media==null)media=AudioMediaCheck.detect(source,ext);
        // 浏览器常见 WAV/M4A 声明仅作为别名；最终入库使用实测标准媒体类型。
        if("audio/x-wav".equalsIgnoreCase(declared))declared="audio/wav";
        if("audio/x-m4a".equalsIgnoreCase(declared))declared="audio/mp4";
        if(media==null || !(media.equalsIgnoreCase(declared)||declared.equalsIgnoreCase("application/octet-stream"))) return null;
        return media;
    }
}
