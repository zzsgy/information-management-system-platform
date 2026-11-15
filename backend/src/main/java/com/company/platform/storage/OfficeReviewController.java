package com.company.platform.storage;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ContentDisposition;
import java.util.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static com.company.platform.storage.DriveLifecycleController.*;
@RestController @RequestMapping("/api/v1/drive")
public class OfficeReviewController {
    private final OfficeReview review;
    public OfficeReviewController(OfficeReview review){this.review=review;}
    /** 编辑批次与保存回执各自分页，只返回当前有权查看的记录。 */
    @GetMapping("/documents/{id}/office-history") public Map<String,Object> history(@PathVariable UUID id,@RequestParam Map<String,String> query,HttpServletRequest request){return ok(review.list(actor(request),id,query),request);}
    @PostMapping("/edit-sessions/{id}/terminate") public Map<String,Object> terminate(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request){return ok(review.terminate(context(request),id,body),request);}
    @PostMapping("/save-receipts/{id}/resolve") public Map<String,Object> resolve(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request){return ok(review.resolve(context(request),id,body),request);}
    @GetMapping("/save-receipts/{id}/candidate") public void candidate(@PathVariable UUID id,HttpServletRequest request,HttpServletResponse response)throws IOException{var object=review.candidate(actor(request),id);response.setContentType("application/octet-stream");response.setHeader("Cache-Control","no-store");response.setHeader("Content-Disposition",ContentDisposition.attachment().filename((String)object.get("original_name"),StandardCharsets.UTF_8).build().toString());response.setContentLengthLong(((Number)object.get("byte_size")).longValue());review.stream(actor(request),id,response.getOutputStream(),com.company.platform.ApiController.requestId(request));}
}
