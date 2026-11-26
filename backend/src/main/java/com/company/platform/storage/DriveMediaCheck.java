package com.company.platform.storage;

import java.io.*;
import java.net.URI;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.stream.*;

/** 公盘格式识别采用有界结构检查；压缩包和CAD仅存储下载，不解包或执行内容。 */
public final class DriveMediaCheck {
    private DriveMediaCheck() { }
    /** OOXML需要内容类型与主部件同时匹配。 */
    public static String detect(Path source,String ext) throws IOException {
        if(Set.of("docx","xlsx","pptx").contains(ext))return office(source,ext);
        byte[] header;try(var input=Files.newInputStream(source)){header=input.readNBytes(1024);}
        if(Set.of("doc","xls","ppt").contains(ext)&&compound(source,ext))return switch(ext){case "doc"->"application/msword";case "xls"->"application/vnd.ms-excel";default->"application/vnd.ms-powerpoint";};
        if(Set.of("txt","csv").contains(ext)&&text(source))return ext.equals("csv")?"text/csv":"text/plain";
        if(ext.equals("zip")) {try(var zip=new ZipFile(source.toFile())){if(zip.size()>200000)return null;return "application/zip";}catch(ZipException invalid){return null;}}
        if(ext.equals("7z")&&starts(header,new byte[]{55,122,(byte)188,(byte)175,39,28})&&header.length>=32)return "application/x-7z-compressed";
        if(ext.equals("rar")&&(starts(header,new byte[]{82,97,114,33,26,7,0})||starts(header,new byte[]{82,97,114,33,26,7,1,0})))return "application/vnd.rar";
        String prefix=new String(header,StandardCharsets.ISO_8859_1);
        if(ext.equals("dwg")&&prefix.matches("(?s)^AC10[0-9]{2}.*"))return "image/vnd.dwg";
        if(ext.equals("dxf")&&(prefix.startsWith("AutoCAD Binary DXF\r\n\u001a\0")||prefix.matches("(?s)^\\s*0\\s+SECTION\\s+2\\s+.*")))return "image/vnd.dxf";
        return null;
    }
    private static final int MAX_OFFICE_MANIFEST = 1024 * 1024;
    private static final String OFFICE_RELATION = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";
    private static final String STRICT_OFFICE_RELATION = "http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument";

    /** 主部件位置由根关系声明，按 OPC 规则忽略部件路径中的大小写。 */
    private static String office(Path source, String ext) throws IOException {
        String conventionalPart = switch (ext) {
            case "docx" -> "word/document.xml";
            case "xlsx" -> "xl/workbook.xml";
            default -> "ppt/presentation.xml";
        };
        String media = switch (ext) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        };
        try (var zip = new ZipFile(source.toFile())) {
            if (zip.size() > 50000) return null;
            Map<String, ZipEntry> entries = new HashMap<>();
            for (var item = zip.entries(); item.hasMoreElements();) {
                ZipEntry entry = item.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                // 部分 Windows 生成器会为合法部件额外写入带反斜杠的空目录记录。
                if (entry.isDirectory() && entry.getSize() == 0) name = name.replace('\\', '/');
                if (entries.putIfAbsent(name, entry) != null || name.contains("\\") || name.startsWith("/")
                    || Arrays.asList(name.split("/")).contains("..") || name.endsWith("vbaproject.bin")) return null;
            }
            var factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);

            String part = officePart(manifest(zip, entries.get("_rels/.rels"), factory), conventionalPart);
            ZipEntry main = entries.get(part);
            if (main == null || main.isDirectory() || main.getSize() <= 0) return null;
            String type = officeContentType(manifest(zip, entries.get("[content_types].xml"), factory), part);
            return (media + ".main+xml").equalsIgnoreCase(type) ? media : null;
        } catch (ZipException | XMLStreamException | IllegalArgumentException invalid) {
            return null;
        }
    }

    /** 清单只在内存中读取有限的 XML 字节，不展开原件或跟随外部链接。 */
    private static XMLStreamReader manifest(ZipFile zip, ZipEntry entry, XMLInputFactory factory)
        throws IOException, XMLStreamException {
        if (entry == null || entry.getSize() < 1 || entry.getSize() > MAX_OFFICE_MANIFEST) throw new XMLStreamException();
        try (var input = zip.getInputStream(entry)) {
            byte[] xml = input.readNBytes(MAX_OFFICE_MANIFEST + 1);
            if (xml.length > MAX_OFFICE_MANIFEST) throw new XMLStreamException();
            return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
        }
    }

    /** 旧生成器未声明主关系时，沿用已支持的标准路径。 */
    private static String officePart(XMLStreamReader reader, String conventionalPart) throws XMLStreamException {
        String part = null;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) throw new XMLStreamException();
                if (event != XMLStreamConstants.START_ELEMENT || !reader.getLocalName().equals("Relationship")) continue;
                String kind = reader.getAttributeValue(null, "Type");
                if (!OFFICE_RELATION.equals(kind) && !STRICT_OFFICE_RELATION.equals(kind)) continue;
                String mode = reader.getAttributeValue(null, "TargetMode");
                String target = reader.getAttributeValue(null, "Target");
                if (part != null || (mode != null && !mode.equals("Internal")) || target == null) throw new XMLStreamException();
                URI uri = URI.create(target).normalize();
                String path = uri.getRawPath();
                if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || path == null || path.isEmpty()) throw new XMLStreamException();
                part = (path.startsWith("/") ? path.substring(1) : path).toLowerCase(Locale.ROOT);
            }
        } finally {
            reader.close();
        }
        return part == null ? conventionalPart : part;
    }

    /** 主部件的专用类型优先。未声明专用类型时读取同扩展名的默认类型。 */
    private static String officeContentType(XMLStreamReader reader, String part) throws XMLStreamException {
        String override = null;
        String defaultType = null;
        int dot = part.lastIndexOf('.');
        String extension = dot > part.lastIndexOf('/') ? part.substring(dot + 1) : "";
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) throw new XMLStreamException();
                if (event != XMLStreamConstants.START_ELEMENT) continue;
                String element = reader.getLocalName();
                if (element.equals("Override") && ("/" + part).equalsIgnoreCase(reader.getAttributeValue(null, "PartName")))
                    override = reader.getAttributeValue(null, "ContentType");
                if (element.equals("Default") && extension.equalsIgnoreCase(reader.getAttributeValue(null, "Extension")))
                    defaultType = reader.getAttributeValue(null, "ContentType");
            }
        } finally {
            reader.close();
        }
        return override == null ? defaultType : override;
    }
    /** 旧Office检查复合文档头和目录流；大于目录检查上界或不完整FAT链保持拒绝。 */
    private static boolean compound(Path source,String ext) throws IOException {
        try(var file=new RandomAccessFile(source.toFile(),"r")) {
            if(file.length()<512)return false;byte[] header=new byte[512];file.readFully(header);
            if(!starts(header,new byte[]{(byte)208,(byte)207,17,(byte)224,(byte)161,(byte)177,26,(byte)225}))return false;
            ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);int shift=h.getShort(30);if(h.getShort(28)!=(short)0xfffe||(shift!=9&&shift!=12))return false;
            int sector=1<<shift,current=h.getInt(48);Set<Integer> seen=new HashSet<>();boolean found=false;
            for(int count=0;current>=0&&count<2048;count++) {
                long offset=(current+1L)*sector;if(!seen.add(current)||offset+sector>file.length())return false;byte[] block=new byte[sector];file.seek(offset);file.readFully(block);ByteBuffer d=ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<sector;i+=128){int length=Short.toUnsignedInt(d.getShort(i+64));if(block[i+66]!=2||length<2||length>64||length%2!=0)continue;String name=new String(block,i,length-2,StandardCharsets.UTF_16LE);if((ext.equals("doc")&&name.equals("WordDocument"))||(ext.equals("xls")&&Set.of("Workbook","Book").contains(name))||(ext.equals("ppt")&&name.equals("PowerPoint Document")))found=true;}
                int fatIndex=current/(sector/4);if(fatIndex>=109)return false;int fat=h.getInt(76+fatIndex*4);long fatOffset=(fat+1L)*sector+current%(sector/4)*4L;if(fat<0||fatOffset+4>file.length())return false;file.seek(fatOffset);current=Integer.reverseBytes(file.readInt());
            }
            return found&&current==-2;
        }catch(EOFException invalid){return false;}
    }
    /** 文本使用严格UTF-8流式验证；不猜编码、不加载整文件，二进制控制字节拒绝。 */
    private static boolean text(Path source) throws IOException {
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(var reader=new InputStreamReader(Files.newInputStream(source),decoder)){char[] block=new char[32768];int count;while((count=reader.read(block))!=-1)for(int i=0;i<count;i++)if(Character.isISOControl(block[i])&&block[i]!='\n'&&block[i]!='\r'&&block[i]!='\t')return false;return true;}catch(CharacterCodingException invalid){return false;}
    }
    /** 有界文件头比较，不把缺少头部的截断文件识别为允许格式。 */
    private static boolean starts(byte[] actual,byte[] expected){return actual.length>=expected.length&&Arrays.equals(Arrays.copyOf(actual,expected.length),expected);}
}
