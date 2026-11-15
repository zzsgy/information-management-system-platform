package com.company.platform.storage;

import com.company.platform.*;
import jakarta.servlet.http.*;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.IOException;

/** 公盘电脑端生命周期接口；写入需当前权限、期望版本、操作号和绑定对象的本人再验证。 */
@RestController @RequestMapping("/api/v1/drive")
public class DriveLifecycleController {
    /** 业务变更服务不接受HTTP伪造的操作者身份。 */
    private final DriveLifecycle lifecycle;
    public DriveLifecycleController(DriveLifecycle lifecycle) {this.lifecycle=lifecycle;}
    /** 重命名与负责人协作开关不可混用同一次再验证动作。 */
    @PatchMapping("/documents/{id}") public Map<String,Object> patch(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request) {return ok(body.containsKey("collaborationEnabled")?lifecycle.collaboration(context(request),id,body):lifecycle.rename(context(request),id,body),request);}
    /** 版本上传仅引用同文档EDIT会话中的检测通过原件。 */
    @PostMapping("/documents/{id}/versions") public Map<String,Object> append(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request) {return ok(lifecycle.append(context(request),id,body),request);}
    /** 当前VIEW权限下分页读取不可变历史。 */
    @GetMapping("/documents/{id}/versions") public Map<String,Object> versions(@PathVariable UUID id,@RequestParam Map<String,String> query,HttpServletRequest request) {return ok(lifecycle.versions(actor(request),id,query),request);}
    /** 恢复历史追加新版本，不回退版本序号。 */
    @PostMapping("/documents/{id}/restore-version") public Map<String,Object> restoreVersion(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request) {return ok(lifecycle.restoreVersion(context(request),id,body),request);}
    /** 移动前确认会变化的可见范围，不对无权目标返回目录信息。 */
    @GetMapping("/documents/{id}/move-check") public Map<String,Object> moveCheck(@PathVariable UUID id,@RequestParam UUID targetFolderId,HttpServletRequest request) {return ok(lifecycle.moveCheck(actor(request),id,targetFolderId),request);}
    /** 确认摘要过期或目标权限撤销时拒绝移动。 */
    @PostMapping("/documents/{id}/move") public Map<String,Object> move(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request) {return ok(lifecycle.move(context(request),id,body),request);}
    /** 回收只改业务状态，正式原件保留。 */
    @DeleteMapping("/documents/{id}") public Map<String,Object> recycle(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request) {return ok(lifecycle.recycle(context(request),id,body),request);}
    /** 回收站计数不包含无权恢复或清理对象。 */
    @GetMapping("/recycle") public Map<String,Object> recycleList(@RequestParam Map<String,String> query,HttpServletRequest request) {return ok(lifecycle.recycleList(actor(request),query),request);}
    /** 恢复仍按当前目录与独立RESTORE授权检查。 */
    @PostMapping("/recycle/documents/{id}/restore") public Map<String,Object> restore(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request) {return ok(lifecycle.restore(context(request),id,body),request);}
    /** 保留期满后负责人可逻辑清理；绝不自动擦除历史和备份。 */
    @DeleteMapping("/recycle/documents/{id}") public Map<String,Object> purge(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request) {return ok(lifecycle.purge(context(request),id,body),request);}
    /** 单区间续传200/206；无DOWNLOAD或撤权后均拒绝，下载原件不默认添加个人水印。 */
    @GetMapping("/documents/{id}/download") public void download(@PathVariable UUID id,@RequestParam(required=false) UUID versionId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        Actor actor=actor(request);var object=lifecycle.download(actor,id,versionId);long size=((Number)object.get("byte_size")).longValue();
        // 固定此次交付版本，防止读取元数据后被另一人换版导致长度和内容不一致。
        UUID fixed=versionId; if(fixed==null) fixed=Inputs.uuid(object.get("version_id"));
        String etag="\""+object.get("sha256")+"\"",ifRange=request.getHeader("If-Range");
        // 原件换版时，旧If-Range必须退回完整响应，不能把两版内容拼为一个下载文件。
        long[] range=range(ifRange!=null&&!etag.equals(ifRange)?null:request.getHeader("Range"),size);response.setStatus(range[2]==1?206:200);response.setContentType("application/octet-stream");response.setContentLengthLong(range[1]-range[0]+1);response.setHeader("ETag",etag);
        response.setHeader("Accept-Ranges","bytes");response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Content-Disposition",ContentDisposition.attachment().filename((String)object.get("document_name"),StandardCharsets.UTF_8).build().toString());
        if(range[2]==1)response.setHeader("Content-Range","bytes "+range[0]+"-"+range[1]+"/"+size);
        lifecycle.stream(actor,id,fixed,range[0],range[1]-range[0]+1,response.getOutputStream(),ApiController.requestId(request));
    }
    /** 仅接受一个合法字节区间，支持尾段和起点续传；拒绝溢出与多段放大。 */
    static long[] range(String value,long size) {
        if(value==null)return new long[]{0,size-1,0};
        try {if(!value.matches("bytes=[0-9]*-[0-9]*"))throw new IllegalArgumentException();String[] pair=value.substring(6).split("-",-1);
            long start=pair[0].isEmpty()?Math.max(0,size-Long.parseLong(pair[1])):Long.parseLong(pair[0]);long end=pair[0].isEmpty()||pair[1].isEmpty()?size-1:Math.min(size-1,Long.parseLong(pair[1]));
            if(start<0||start>=size||end<start)throw new IllegalArgumentException();return new long[]{start,end,1};
        }catch(IllegalArgumentException failure){throw new ApiFault(416,"RANGE_NOT_SATISFIABLE","下载区间无效");}
    }
    /** 复用可信登录边界，客户端不可自行传入员工身份。 */
    static Actor actor(HttpServletRequest request) {if(request.getAttribute(HttpBoundary.ACTOR) instanceof Actor actor)return actor;throw ApiFault.session();}
    /** 所有写操作的重试键、版本和再验证凭据均取自固定请求头。 */
    static Commands.Context context(HttpServletRequest request) {String version=request.getHeader("X-Expected-Version");return new Commands.Context(actor(request),ApiController.requestId(request),Inputs.uuid(request.getHeader("X-Operation-Id")),request.getMethod()+" "+request.getRequestURI(),request.getHeader("X-Reauth-Token"),version==null?null:Inputs.integer(version,0,1,Integer.MAX_VALUE));}
    /** 不对外回传存储键与内部对象字段。 */
    static Map<String,Object> ok(Object data,HttpServletRequest request) {return ApiController.envelope("OK","操作成功",data,ApiController.requestId(request));}
}
