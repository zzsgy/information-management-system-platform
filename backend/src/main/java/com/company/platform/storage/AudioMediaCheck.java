package com.company.platform.storage;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 凭据录音结构检查：识别容器和连续音频帧；病毒检查仍由独立扫描器执行。 */
final class AudioMediaCheck {
    private AudioMediaCheck() { }
    /** 单件上限五十 MiB；不调用外部播放器，不接受改扩展名的任意二进制。 */
    static String detect(Path file,String ext) throws IOException {
        if(!Set.of("wav","amr","mp3","m4a").contains(ext)||Files.size(file)>52428800)return null;
        byte[] data=Files.readAllBytes(file);
        try{return switch(ext){case "wav"->wav(data)?"audio/wav":null;case "amr"->amr(data)?"audio/amr":null;case "mp3"->mp3(data)?"audio/mpeg":null;case "m4a"->m4a(data)?"audio/mp4":null;default->null;};}catch(IndexOutOfBoundsException|IllegalArgumentException invalid){return null;}
    }
    /** RIFF 大小、格式块及数据块必须完整，压缩或多声道仍由合法格式编号表达。 */
    private static boolean wav(byte[] b){if(b.length<44||!text(b,0,4).equals("RIFF")||!text(b,8,4).equals("WAVE")||number(b,4,true)+8!=b.length)return false;boolean format=false,data=false;int p=12;while(p+8<=b.length){long size=number(b,p+4,true);long end=p+8L+size;if(end>b.length)return false;String kind=text(b,p,4);if(kind.equals("fmt ")){if(size<16)return false;int channels=ByteBuffer.wrap(b,p+10,2).order(ByteOrder.LITTLE_ENDIAN).getShort()&65535;long rate=number(b,p+12,true);if(channels<1||channels>32||rate<8000||rate>384000)return false;format=true;}if(kind.equals("data")&&size>0)data=true;p=(int)(end+(size&1));}return format&&data&&p==b.length;}
    /** AMR/AMR-WB 必须由合法帧完整组成，只有文件头的空录音拒绝。 */
    private static boolean amr(byte[] b){boolean wide=b.length>=9&&text(b,0,9).equals("#!AMR-WB\n");int p=wide?9:6;if(!wide&&(b.length<6||!text(b,0,6).equals("#!AMR\n")))return false;int[] sizes=wide?new int[]{18,24,33,37,41,47,51,59,61,6,0,0,0,0,1,1}:new int[]{13,14,16,18,20,21,27,32,6,0,0,0,0,0,0,1};int frames=0;while(p<b.length){int header=b[p]&255,type=(header>>3)&15;if((header&131)!=0||sizes[type]==0||p+sizes[type]>b.length)return false;p+=sizes[type];frames++;}return frames>0;}
    /** ID3 长度受界限保护，至少两帧合法 MPEG Layer III，拒绝伪造同步字。 */
    private static boolean mp3(byte[] b){int p=0;if(b.length>=10&&text(b,0,3).equals("ID3")){if((b[3]&255)<2||(b[3]&255)>4)return false;int size=0;for(int i=6;i<10;i++){if((b[i]&128)!=0)return false;size=(size<<7)|(b[i]&127);}p=10+size+(((b[5]&16)!=0)?10:0);}int frames=0;while(p+4<=b.length){if(b.length-p==128&&text(b,p,3).equals("TAG"))return frames>=2;int h=ByteBuffer.wrap(b,p,4).getInt(),version=(h>>>19)&3,layer=(h>>>17)&3,bitrate=(h>>>12)&15,sample=(h>>>10)&3;if((h&0xffe00000)!=0xffe00000||version==1||layer!=1||bitrate==0||bitrate==15||sample==3)return false;int[] rates=version==3?new int[]{0,32,40,48,56,64,80,96,112,128,160,192,224,256,320}:new int[]{0,8,16,24,32,40,48,56,64,80,96,112,128,144,160};int hz=new int[]{44100,48000,32000}[sample]/(version==3?1:version==2?2:4);int size=(version==3?144000:72000)*rates[bitrate]/hz+((h>>>9)&1);if(size<4||p+size>b.length)return false;p+=size;frames++;}return p==b.length&&frames>=2;}
    /** M4A 必须含完整 ISO 容器、媒体数据及音频轨，视频轨拒绝；不宣称完成音质校验。 */
    private static boolean m4a(byte[] b){boolean brand=false,data=false,audio=false;int p=0;while(p+8<=b.length){long size=number(b,p,false);if(size<8||p+size>b.length)return false;String kind=text(b,p+4,4);if(kind.equals("ftyp")){if(size<16)return false;String major=text(b,p+8,4);brand=Set.of("M4A ","isom","mp42","mp41","iso2").contains(major);}if(kind.equals("mdat")&&size>8)data=true;if(kind.equals("moov")){int tracks=tracks(b,p+8,(int)(p+size),0);if(tracks<0)return false;audio=tracks>0;}p+= (int)size;}return p==b.length&&brand&&data&&audio;}
    /** 只遍历已知有界容器层，最大深度六层，非音频处理器在媒体轨内拒绝。 */
    private static int tracks(byte[] b,int start,int end,int depth){if(depth>6)return -1;int audio=0,p=start;while(p+8<=end){long size=number(b,p,false);if(size<8||p+size>end)return -1;String kind=text(b,p+4,4);if(kind.equals("hdlr")){if(size<24||!text(b,p+16,4).equals("soun"))return -1;audio++;}else if(Set.of("trak","mdia").contains(kind)){int nested=tracks(b,p+8,(int)(p+size),depth+1);if(nested<0)return -1;audio+=nested;}p+=(int)size;}return p==end?audio:-1;}
    /** 容器字段以无符号长度读取，禁止负值绕过边界。 */
    private static long number(byte[] b,int offset,boolean little){return Integer.toUnsignedLong(ByteBuffer.wrap(b,offset,4).order(little?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN).getInt());}
    /** 固定位置 ASCII 标签，只在调用方完成长度校验后读取。 */
    private static String text(byte[] b,int offset,int length){return new String(b,offset,length,StandardCharsets.US_ASCII);}
}
