package com.company.platform.storage;

import com.company.platform.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 录音格式和上传边界反例，全部使用合成字节，不需要业务库或外部播放器。 */
class AudioMediaCheckTest {
    /** JUnit 自动分配的私有测试目录。 */
    @TempDir Path root;
    /** 一段完整 PCM WAV，仅验证结构与媒体类型，不评价内容音质。 */
    static byte[] wav(){byte[] b=new byte[48];ByteBuffer v=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);v.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);return b;}
    /** 只在固定临时文件写入本例合成录音。 */
    private String detect(byte[] bytes,String extension,String declared)throws Exception{Path file=root.resolve("sample."+extension);Files.write(file,bytes);return FileMediaCheck.detect(file,file.getFileName().toString(),declared);}
    @Test void pcmWaveHasMeasuredMediaType()throws Exception{assertEquals("audio/wav",detect(wav(),"wav","audio/x-wav"));}
    @Test void truncatedWaveOrWrongDeclarationIsRejected()throws Exception{assertNull(detect(Arrays.copyOf(wav(),47),"wav","audio/wav"));assertNull(detect(wav(),"wav","image/png"));}
    @Test void amrNeedsCompleteFrames()throws Exception{byte[] b=new byte[19];System.arraycopy("#!AMR\n".getBytes(),0,b,0,6);b[6]=4;assertEquals("audio/amr",detect(b,"amr","audio/amr"));assertNull(detect(Arrays.copyOf(b,18),"amr","audio/amr"));assertNull(detect("#!AMR\n".getBytes(),"amr","audio/amr"));}
    @Test void mp3NeedsConsecutiveLegalFrames()throws Exception{byte[] b=new byte[834];for(int start:new int[]{0,417}){b[start]=(byte)255;b[start+1]=(byte)251;b[start+2]=(byte)144;}assertEquals("audio/mpeg",detect(b,"mp3","audio/mpeg"));assertNull(detect(Arrays.copyOf(b,833),"mp3","audio/mpeg"));}
    @Test void oversizedId3CannotEscapeFrameValidation()throws Exception{assertNull(detect(new byte[]{73,68,51,4,0,0,127,127,127,127},"mp3","audio/mpeg"));}
    @Test void m4aNeedsAudioTrackAndMediaData()throws Exception{byte[] b=new byte[77];ByteBuffer v=ByteBuffer.wrap(b);v.putInt(16).put("ftypM4A ".getBytes()).putInt(0).putInt(52).put("moov".getBytes()).putInt(44).put("trak".getBytes()).putInt(36).put("mdia".getBytes()).putInt(28).put("hdlr".getBytes()).putLong(0).put("soun".getBytes()).putLong(0).putInt(9).put("mdat".getBytes()).put((byte)1);assertEquals("audio/mp4",detect(b,"m4a","audio/x-m4a"));b[56]='v';assertNull(detect(b,"m4a","audio/mp4"));}
    @Test void evidenceCannotMasqueradeAsPlatformFileOrExceedLimit(){var body=Sql.map("module","ARCHIVE","purpose","EVIDENCE","businessContextId",UUID.randomUUID().toString(),"originalName","录音.wav","expectedSize",52428800,"expectedSha256","a".repeat(64),"expectedMediaType","audio/wav");assertEquals(52428800,UploadRules.spec(body).size());body.put("expectedSize",52428801);assertEquals(413,assertThrows(ApiFault.class,()->UploadRules.spec(body)).status);body.put("expectedSize",48);body.put("module","PLATFORM");assertThrows(ApiFault.class,()->UploadRules.spec(body));}
}
