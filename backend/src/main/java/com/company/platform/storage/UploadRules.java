package com.company.platform.storage;

import com.company.platform.*;
import java.util.*;
import java.util.regex.*;

/** 文件接收的固定业务限额和分片契约；检测通过与正式业务发布属于后续步骤。 */
public final class UploadRules {
    /** 创建会话时固定为 8 MiB，不能由客户端调大。 */
    public static final long PART_SIZE=8L*1024*1024;
    /** 支持的公盘文件扩展名；实际内容类型与病毒检测由检测服务确认。 */
    private static final Set<String> DRIVE_EXTENSIONS=Set.of("doc","docx","xls","xlsx","ppt","pptx","pdf","txt","csv","jpg","jpeg","png","tif","tiff","zip","rar","7z","dwg","dxf");
    /** 仅检查标准闭区间，不接受多个区间或无总大小的格式。 */
    private static final Pattern RANGE=Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    private UploadRules() { }

    /** 固定的业务上下文和声明内容；文件名只作为元数据，绝不参与磁盘路径。 */
    public record Spec(
        /** 独立业务模块。 */ String module,
        /** 固定领域对象或批次。 */ UUID context,
        /** 受控上传用途。 */ String purpose,
        /** 仅用于显示的原始名称。 */ String name,
        /** 声明的完整字节数。 */ long size,
        /** 声明的完整 SHA256。 */ String sha256,
        /** 待检测的声明媒体类型。 */ String mediaType) { }
    /** 校验结果是半开区间；实际字节流还需核对长度和摘要。 */
    public record Part(
        /** 起始字节。 */ long offset,
        /** 实际应收字节数。 */ long length,
        /** 从 1 开始的编号。 */ int number) { }

    /** 创建请求白名单、明确模块与用途、大小和名称；不静默修改文件名。 */
    public static Spec spec(Map<String,Object> body) {
        Inputs.only(body,"module","businessContextId","originalName","expectedSize","expectedSha256","expectedMediaType","purpose");
        String module=Inputs.choice(body.get("module"),"DRIVE","ARCHIVE","PLATFORM");
        String purpose=Inputs.choice(body.get("purpose"),"DRIVE_FILE","DRIVE_VERSION","ARCHIVE_PDF","EVIDENCE","IMPORT_XLSX","ARCHIVE_CHECKLIST","ARCHIVE_ATTACHMENT","ARCHIVE_REGISTRATION_CHECKLIST");
        if ((Set.of("DRIVE_FILE","DRIVE_VERSION").contains(purpose)&&!module.equals("DRIVE")) || (Set.of("ARCHIVE_PDF","EVIDENCE","ARCHIVE_CHECKLIST","ARCHIVE_ATTACHMENT","ARCHIVE_REGISTRATION_CHECKLIST").contains(purpose)&&!module.equals("ARCHIVE"))) throw ApiFault.invalid("上传用途与业务模块不匹配");
        String name=Inputs.text(body,"originalName",512), media=Inputs.text(body,"expectedMediaType",255).toLowerCase(Locale.ROOT);
        if(name.indexOf('/')>=0 || name.indexOf('\\')>=0 || name.chars().anyMatch(Character::isISOControl) || name.equals(".") || name.equals("..")) throw ApiFault.invalid("文件名不能包含路径或控制字符");
        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if (Set.of("DRIVE_FILE","DRIVE_VERSION").contains(purpose)&&!DRIVE_EXTENSIONS.contains(ext)) throw new ApiFault(415,"MEDIA_TYPE_NOT_ALLOWED","文件格式不在允许范围内");
        if (purpose.equals("ARCHIVE_PDF")&&(!ext.equals("pdf")||!media.equals("application/pdf"))) throw new ApiFault(415,"MEDIA_TYPE_NOT_ALLOWED","档案原件必须为 PDF");
        if (purpose.equals("IMPORT_XLSX")&&!ext.equals("xlsx")) throw new ApiFault(415,"MEDIA_TYPE_NOT_ALLOWED","导入文件必须为 XLSX");
        if(Set.of("ARCHIVE_CHECKLIST","ARCHIVE_REGISTRATION_CHECKLIST").contains(purpose)&&!ext.equals("xlsx"))throw new ApiFault(415,"MEDIA_TYPE_NOT_ALLOWED","电子清单必须为 XLSX");
        if(purpose.equals("ARCHIVE_ATTACHMENT")&&!Set.of("pdf","doc","docx","xls","xlsx","ppt","pptx").contains(ext))throw new ApiFault(415,"MEDIA_TYPE_NOT_ALLOWED","电子文档支持PDF、Word、Excel和PowerPoint的常用格式");
        // 审批凭据按 BOR-020 同时允许聊天截图和录音；正式归档仍只接受 PDF。
        if (purpose.equals("EVIDENCE")&&!Set.of("pdf","jpg","jpeg","png","mp3","m4a","wav","amr").contains(ext)) throw new ApiFault(415,"MEDIA_TYPE_NOT_ALLOWED","凭据格式不在允许范围内");
        if(!media.matches("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+")) throw ApiFault.invalid("媒体类型格式不正确");
        long maximum=switch(purpose) { case "DRIVE_FILE","DRIVE_VERSION" -> 5368709120L; case "ARCHIVE_PDF","ARCHIVE_ATTACHMENT" -> 1073741824L; default -> 52428800L; };
        long size=positiveLong(body.get("expectedSize")); if(size>maximum) throw new ApiFault(413,"FILE_TOO_LARGE","文件超过该用途的大小上限");
        return new Spec(module,Inputs.uuid(body.get("businessContextId")),purpose,name,size,sha(body.get("expectedSha256")),media);
    }
    /** 只接受不超过 long 范围的正整数，不将小数截断。 */
    private static long positiveLong(Object value) {
        try { long number=Long.parseLong(Objects.toString(value,"")); if(number<=0) throw new NumberFormatException(); return number; }
        catch(NumberFormatException ex) { throw ApiFault.invalid("文件大小必须是正整数字节数"); }
    }
    /** 摘要使用小写 64 位十六进制，不能让服务端猜测或截断。 */
    public static String sha(Object value) {
        String hash=Objects.toString(value,""); if(!hash.matches("[0-9a-f]{64}")) throw ApiFault.invalid("SHA256 必须是小写64位十六进制"); return hash;
    }
    /** 按会话固定长度推导每片范围，拒绝错序重叠、遗漏和伪造总大小。 */
    public static Part part(long total,int number,String contentRange,long length) {
        if(number<1 || total<=0) throw ApiFault.invalid("分片编号不正确");
        long offset=(number-1L)*PART_SIZE;
        if(offset>=total) throw ApiFault.invalid("分片编号超出文件范围");
        long expected=Math.min(PART_SIZE,total-offset); Matcher match=RANGE.matcher(Objects.toString(contentRange,""));
        try {
            if(!match.matches() || Long.parseLong(match.group(1))!=offset || Long.parseLong(match.group(2))!=offset+expected-1 || Long.parseLong(match.group(3))!=total || length!=expected) throw ApiFault.invalid("分片范围或长度不匹配");
        } catch(NumberFormatException ex) { throw ApiFault.invalid("分片范围格式不正确"); }
        return new Part(offset,expected,number);
    }
    /** 完成时按期望大小计算分片数，而不是相信客户端数量。 */
    public static int count(long size) { return Math.toIntExact((size+PART_SIZE-1)/PART_SIZE); }
}
