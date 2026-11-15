package com.company.ims.archive;

import com.company.ims.identity.*;
import com.company.ims.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 保留原有轻量网页入口，所有查询复用桌面端相同的权限和数据范围。 */
@RestController
@RequestMapping("/web")
public class WebController {
    /** 网页只调用档案服务，不另写无权限约束的查询。 */
    private final ArchiveService archives;
    /** 网页与桌面端共用账号、停用检查和会话撤销。 */
    private final AuthenticationService authentication;
    /** 状态值对应的中文名称。 */
    private static final Map<String,String> STATUS = Map.of("draft","草稿","archive","已归档","borrowed","已借出","damaged","已损坏","locked","已锁定","destroy_pending","待销毁","destroyed","已销毁");
    /** 轻量只读页样式，继续使用系统中文字体。 */
    private static final String STYLE = "body{font-family:'Microsoft YaHei',sans-serif;background:#f3f5f8;color:#273b4b;margin:0}main{max-width:1080px;margin:24px auto;padding:24px;background:white;border-radius:8px}table{border-collapse:collapse;width:100%;margin-top:20px}td,th{border:1px solid #dce4eb;padding:9px;text-align:left}input,button{padding:8px;margin:6px}a{color:#1769aa}dt{font-weight:bold;margin-top:12px}dd{margin:4px 0}nav{display:flex;justify-content:space-between}p{line-height:1.7}";
    /** 注入统一业务服务和身份服务。 */
    public WebController(ArchiveService archives, AuthenticationService authentication) { this.archives=archives; this.authentication=authentication; }
    /** 展示登录表单，错误信息先转义后输出。 */
    @GetMapping(value="/login", produces="text/html;charset=UTF-8")
    public String loginPage() { return loginHtml(""); }
    /** 校验来源后登录，Cookie限定网页路径且不向脚本开放。 */
    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestParam String username, @RequestParam String password, HttpServletRequest request) {
        checkOrigin(request);
        try {
            // 登录响应只取随机会话凭证，权限由下一次请求重新加载。
            Map<String,Object> result=authentication.login(username,password);
            // HTTPS部署设置Secure，浏览器重启后重新登录。
            ResponseCookie cookie=ResponseCookie.from("ims_session",result.get("access_token").toString()).path("/web").httpOnly(true).secure(request.isSecure()).sameSite("Strict").build();
            return ResponseEntity.status(303).header(HttpHeaders.LOCATION,"/web/archives").header(HttpHeaders.SET_COOKIE,cookie.toString()).cacheControl(CacheControl.noStore()).build();
        } catch (BusinessException error) {
            return ResponseEntity.status(error.status()).contentType(new MediaType("text","html",StandardCharsets.UTF_8)).cacheControl(CacheControl.noStore()).body(loginHtml(error.getMessage()));
        }
    }
    /** 退出采用POST及同源校验，撤销服务器会话后清除Cookie。 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        checkOrigin(request);
        authentication.logout(CurrentUser.require(),AuthenticationFilter.token(request));
        return ResponseEntity.status(303).header(HttpHeaders.LOCATION,"/web/login").header(HttpHeaders.SET_COOKIE,ResponseCookie.from("ims_session","").path("/web").httpOnly(true).sameSite("Strict").maxAge(0).build().toString()).build();
    }
    /** 根路径转向带权限过滤的档案列表。 */
    @GetMapping({"", "/"})
    public ResponseEntity<Void> root() { return ResponseEntity.status(303).header(HttpHeaders.LOCATION,"/web/archives").build(); }
    /** 查询参数只能缩小可见范围，网页搜索和分页不能绕过部门隔离。 */
    @GetMapping(value="/archives", produces="text/html;charset=UTF-8")
    @SuppressWarnings("unchecked")
    public ResponseEntity<String> list(@RequestParam(defaultValue="") String keyword, @RequestParam(defaultValue="1") int page) {
        // 服务统一校验页码上限并统计同范围数据。
        Map<String,Object> result=archives.list(Map.of("keyword",keyword,"page",Integer.toString(page),"page_size","50"));
        // 表格单元格全部使用转义文本，档号和名称不能注入HTML。
        StringBuilder body=new StringBuilder(nav()).append("<h1>档案列表</h1><form method='get'><input name='keyword' placeholder='档号 / 名称 / 关键词' value='").append(esc(keyword)).append("'><button>检索</button></form><p>共 ").append(result.get("total")).append(" 条</p><table><tr><th>档号</th><th>名称</th><th>部门</th><th>密级</th><th>状态</th></tr>");
        for(Map<String,Object> row:(List<Map<String,Object>>)result.get("items")) {
            body.append("<tr><td><a href='/web/archives/").append(row.get("id")).append("'>").append(esc(row.get("archive_no"))).append("</a></td><td>").append(esc(row.get("archive_name"))).append("</td><td>").append(esc(row.get("dept_name"))).append("</td><td>").append(esc(row.get("security_name"))).append("</td><td>").append(esc(STATUS.getOrDefault(row.get("status"),row.get("status").toString()))).append("</td></tr>");
        }
        body.append("</table><p>");
        // 查询字符串先URL编码再HTML转义，保留中文和特殊字符。
        String query=esc(URLEncoder.encode(keyword,StandardCharsets.UTF_8));
        if(page>1) body.append("<a href='?page=").append(page-1).append("&amp;keyword=").append(query).append("'>上一页</a> ");
        if(((Number)result.get("total")).longValue()>page*50L) body.append("<a href='?page=").append(page+1).append("&amp;keyword=").append(query).append("'>下一页</a>");
        return html("档案列表",body.append("</p>").toString());
    }
    /** 对象详情和API共用范围检查、状态读取及查询审计。 */
    @GetMapping(value="/archives/{id}", produces="text/html;charset=UTF-8")
    public ResponseEntity<String> detail(@PathVariable long id) {
        // 不可见对象由服务返回404，防止通过网页枚举其他部门档案。
        Map<String,Object> row=archives.detail(id);
        // 显示字段固定，不将内部存储路径或任意列暴露给页面。
        String[][] fields={{"archive_no","档号"},{"archive_name","名称"},{"category_name","分类"},{"dept_name","部门"},{"security_name","密级"},{"retention_name","保管期限"},{"owner_name","负责人"},{"physical_location","原件位置"},{"storage_type","保存类型"},{"copy_count","份数"},{"archive_date","存档日期"},{"expiry_date","到期日期"},{"summary","摘要"},{"remark","备注"}};
        // 所有正文使用同一个转义函数。
        StringBuilder body=new StringBuilder(nav()).append("<h1>档案详情</h1><p>状态：").append(esc(STATUS.getOrDefault(row.get("status"),row.get("status").toString()))).append("</p><dl>");
        for(String[] field:fields) body.append("<dt>").append(field[1]).append("</dt><dd>").append(esc(row.get(field[0]))).append("</dd>");
        return html("档案详情",body.append("</dl>").toString());
    }
    /** 返回统一的防缓存、防嵌入HTML响应。 */
    private ResponseEntity<String> html(String title,String body) {
        return ResponseEntity.ok().contentType(new MediaType("text","html",StandardCharsets.UTF_8)).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'").body(document(title,body));
    }
    /** 用纯文本构造网页标题与正文框架。 */
    private String document(String title,String body) { return "<!doctype html><html lang='zh-CN'><head><meta charset='UTF-8'><title>"+esc(title)+"</title><style>"+STYLE+"</style></head><body><main>"+body+"</main></body></html>"; }
    /** 不在登录页回显密码或用户名，防止失败响应泄露凭据。 */
    private String loginHtml(String error) { return document("档案系统登录","<h1>档案管理系统</h1><p>"+esc(error)+"</p><form method='post' action='/web/login'><p><label>用户名 <input name='username' autocomplete='username' required></label></p><p><label>密码 <input name='password' type='password' autocomplete='current-password' required></label></p><button>登录</button></form>"); }
    /** 公共导航中的退出操作保持显式表单提交。 */
    private String nav() { return "<nav><a href='/web/archives'>档案列表</a><form method='post' action='/web/logout'><span>"+esc(CurrentUser.require().realName())+"</span><button>退出登录</button></form></nav>"; }
    /** HTML实体转义覆盖引号与尖括号，空值显示为空。 */
    private String esc(Object value) { return HtmlUtils.htmlEscape(Objects.toString(value,""),"UTF-8"); }
    /** 浏览器POST必须携带同源Origin，拒绝跨站登录和退出。 */
    private void checkOrigin(HttpServletRequest request) {
        // 服务端重建的同源地址；反向代理应明确配置可信转发头。
        String expected=request.getScheme()+"://"+request.getServerName()+((request.getServerPort()==80&&request.getScheme().equals("http"))||(request.getServerPort()==443&&request.getScheme().equals("https"))?"":":"+request.getServerPort());
        if(!expected.equals(request.getHeader("Origin"))) throw new BusinessException(403,"网页操作来源不受信任，请从本站登录页重新操作");
    }
}
