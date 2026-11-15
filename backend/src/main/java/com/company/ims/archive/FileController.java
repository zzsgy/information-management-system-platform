package com.company.ims.archive;

import com.company.ims.identity.CurrentUser;
import com.company.ims.shared.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 附件接口：每次请求同时验证文件动作权限和所属档案范围。 */
@RestController
@RequestMapping("/api/files")
public class FileController {
    /** 附件元数据数据库。 */
    private final Db db;
    /** 档案范围检查器，避免通过file_id绕过部门隔离。 */
    private final ArchiveAccess access;
    /** 附件存储端口，便于以后接入公盘。 */
    private final AttachmentStorage storage;
    /** 操作审计服务。 */
    private final AuditService audit;
    /** 注入附件与授权能力。 */
    public FileController(Db db,ArchiveAccess access,AttachmentStorage storage,AuditService audit){this.db=db;this.access=access;this.storage=storage;this.audit=audit;}
    /** 列出可见档案的附件版本，不向客户端暴露storage_key。 */
    @GetMapping public ApiResponse list(@RequestParam("archive_id") long archiveId){access.requireRead(archiveId);return ApiResponse.ok(db.list("select id,archive_id,file_name,file_ext,file_size,version_no,is_current,sha256,uploaded_at from archive_file where archive_id=? and status='active' order by file_name,version_no desc",archiveId));}
    /**
     * 上传同名附件产生新版本；持档案行锁复核销毁冻结后才读取和保存上传内容。
     *
     * @param archiveId 当前请求身份具有附件上传范围的档案主键
     * @param file 本次上传流及展示名称，名称不能决定物理存储路径
     * @return 新版本的附件元数据，不包含内部存储键
     * @throws IOException 读取上传内容或写入附件存储失败时回滚数据库事务
     */
    @PostMapping("/upload") @Transactional(rollbackFor=Exception.class)
    public ApiResponse upload(@RequestParam("archive_id") long archiveId,@RequestParam MultipartFile file) throws IOException {
        access.require(archiveId,"file.upload");
        // 档案行锁使版本号计算与销毁状态变化保持串行。
        Map<String,Object> archive=db.required("select * from archive where id=? for update",archiveId);
        access.requireNoActiveDestruction(archiveId);
        if(archive.get("deleted_at")!=null||!Set.of("draft","archive").contains(archive.get("status")))throw new BusinessException(409,"档案当前状态不能上传附件");
        // 清理路径部分后的附件展示文件名，不用于决定存储位置。
        String name=Objects.toString(file.getOriginalFilename(),"").replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1);
        if(name.isBlank()||name.length()>255||name.chars().anyMatch(ch->ch<32))throw new BusinessException(400,"附件文件名不合法");
        // 写盘结果在事务失败时必须删除，避免产生无元数据附件。
        AttachmentStorage.StoredFile saved;
        try(InputStream input=file.getInputStream()){saved=storage.save(archiveId,name,input);}
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            /** 数据库回滚时清理本次新增文件，不触碰旧版本。 */
            @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{storage.remove(saved.key());}catch(IOException error){throw new IllegalStateException("未提交附件清理失败",error);}}
        });
        // 同一档案同名附件的下一版本号。
        long version=db.scalarLong("select coalesce(max(version_no),0)+1 from archive_file where archive_id=? and file_name=?",archiveId,name);
        db.execute("update archive_file set is_current=0 where archive_id=? and file_name=?",archiveId,name);
        // 已筛选的业务写入字段，与原始请求分离。
        Map<String,Object> values=new LinkedHashMap<>();
        values.putAll(Map.of("archive_id",archiveId,"file_name",name,"storage_key",saved.key(),"file_size",saved.size(),"sha256",saved.sha256(),"version_no",version,"uploaded_by",CurrentUser.require().id()));
        values.put("file_ext",name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"");
        values.put("mime_type",Objects.toString(file.getContentType(),"application/octet-stream"));
        // 本次业务记录或引用对象的主键，供关联和审计使用。
        long id=db.insert("archive_file",values);
        db.execute("update archive set file_count=(select count(*) from archive_file where archive_id=? and is_current=1 and status='active'),updated_at=current_timestamp where id=?",archiveId,archiveId);
        audit.record(CurrentUser.require().id(),"file","upload","archive_file",id,"附件版本 "+version);
        return ApiResponse.ok(db.required("select id,archive_id,file_name,file_ext,file_size,version_no,is_current,sha256,uploaded_at from archive_file where id=?",id),"附件上传成功");
    }
    /** 下载使用附件动作授权，不因具备查询权限自动放行。 */
    @GetMapping("/{id}/download") @Transactional
    public ResponseEntity<Resource> download(@PathVariable long id) throws IOException {return response(id,false);}
    /** 预览使用独立权限，HTML等可执行内容强制附件下载。 */
    @GetMapping("/{id}/preview") @Transactional
    public ResponseEntity<Resource> preview(@PathVariable long id) throws IOException {return response(id,true);}
    /** 创建安全的流式响应并记录访问审计。 */
    private ResponseEntity<Resource> response(long id,boolean preview) throws IOException {
        // 待访问的附件元数据，读取实体前还须验证档案权限。
        Map<String,Object> file=db.required("select * from archive_file where id=? and status='active'",id);
        access.require(Input.id(file.get("archive_id")),preview?"file.preview":"file.download");
        // 经过规范化和存储范围验证的实际文件路径。
        Path path=storage.resolve(file.get("storage_key").toString());
        if(!Files.isRegularFile(path))throw new BusinessException(404,"附件实体缺失，请联系管理员核查");
        // 附件声明的媒体类型，内联预览仅采用固定安全白名单。
        String mime=Objects.toString(file.get("mime_type"),"application/octet-stream");
        // 是否满足允许内联预览的动作和媒体类型条件。
        boolean inline=preview&&Set.of("application/pdf","image/png","image/jpeg","image/gif","text/plain").contains(mime);
        audit.record(CurrentUser.require().id(),"file",preview?"preview":"download","archive_file",id,null);
        // 响应文件名与下载方式，使用UTF8编码避免中文乱码。
        ContentDisposition disposition=(inline?ContentDisposition.inline():ContentDisposition.attachment()).filename(file.get("file_name").toString(),StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString()).header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).contentType(inline?MediaType.parseMediaType(mime):MediaType.APPLICATION_OCTET_STREAM).contentLength(Files.size(path)).body(new FileSystemResource(path));
    }
}
