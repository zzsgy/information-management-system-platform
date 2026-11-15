package com.company.platform.storage;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.company.platform.storage.DriveLifecycleController.*;

@RestController @RequestMapping("/api/v1/drive")
public class DriveFolderController {
    private final DriveFolders folders;
    public DriveFolderController(DriveFolders folders){this.folders=folders;}
    @PatchMapping("/folders/{id}") public Map<String,Object> rename(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request){return ok(folders.change(context(request),id,body,"RENAME_FOLDER"),request);}
    @DeleteMapping("/folders/{id}") public Map<String,Object> recycle(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request){return ok(folders.change(context(request),id,body,"RECYCLE_FOLDER"),request);}
    @GetMapping("/recycle/folders") public Map<String,Object> list(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(folders.recycledFolders(actor(request),query),request);}
    @PostMapping("/recycle/folders/{id}/restore") public Map<String,Object> restore(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request){return ok(folders.change(context(request),id,body,"RESTORE_FOLDER"),request);}
    @DeleteMapping("/recycle/folders/{id}") public Map<String,Object> purge(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request){return ok(folders.change(context(request),id,body,"PURGE_FOLDER"),request);}
}
