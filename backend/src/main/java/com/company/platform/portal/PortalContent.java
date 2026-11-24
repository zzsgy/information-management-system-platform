package com.company.platform.portal;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 首页内容服务：公告和网站均从数据库读取，管理写入复用版本、幂等和审计事务。 */
@Service
public class PortalContent {
    /** 元数据访问边界；不存放文档原件或业务授权。 */
    private final Sql db;
    /** 公告摘要白名单，列表不传输正文。 */
    private static final String SUMMARY="id,title,category,state,scope,valid_from,valid_until,pinned,publisher_name,publisher_department,published_at,row_version";
    /** 展示范围使用员工当前部门，调岗后不会继续显示原部门公告。 */
    private static final String AUDIENCE_SCOPE="(a.scope='COMPANY' OR EXISTS(SELECT 1 FROM portal.announcement_department ad WHERE ad.announcement_id=a.id AND ad.department_id=u.department_id))";
    private static final String PUBLISHED_IN_SCOPE="a.state='PUBLISHED' AND a.valid_from<=CURRENT_TIMESTAMP AND EXISTS(SELECT 1 FROM iam.user_account u WHERE u.id=? AND "+AUDIENCE_SCOPE+")";
    private static final String CURRENT="(a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)";
    public PortalContent(Sql db){this.db=db;}

    /** 工作台取最近五条可见公告，并附上当前员工的归属与确认状态。 */
    public List<Map<String,Object>> recentAnnouncements(Actor actor){
        return db.rows("""
            SELECT a.*, EXISTS(SELECT 1 FROM portal.announcement_read r
                WHERE r.announcement_id=a.id AND r.user_id=?) AS viewed
            FROM portal.announcement a WHERE
            """+PUBLISHED_IN_SCOPE+" ORDER BY a.published_at DESC NULLS LAST,a.created_at DESC,a.id LIMIT 5",
            actor.userId(),actor.userId()).stream().map(row->announcementView(actor,row,SUMMARY+",viewed")).toList();
    }

    /** 员工列表先筛选可见范围再分页计数；管理列表需要独立公告维护权。 */
    public Map<String,Object> announcements(Actor actor,Map<String,String> query,boolean manage){
        Inputs.only(query,"page","pageSize","keyword","category","publisher","publishedFrom","publishedTo","state","view");
        if(manage)actor.require("PLATFORM.ANNOUNCEMENTS_MANAGE");
        // 到期公告继续按原发布范围供员工查阅；已查看列表仍按本人阅读记录筛选。
        String view=manage?"CURRENT":Inputs.choice(query.getOrDefault("view","CURRENT"),"CURRENT","READ","EXPIRED","CREATED");
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),10,1,100);
        List<Object> args=new ArrayList<>();
        String from=" FROM portal.announcement a";
        if(!manage){
            from+=" LEFT JOIN portal.announcement_read r ON r.announcement_id=a.id AND r.user_id=?";
            args.add(actor.userId());
        }
        from+=" WHERE "+(manage?"true":switch(view){
            case "CREATED" -> "a.created_by=?";
            case "EXPIRED" -> PUBLISHED_IN_SCOPE+" AND a.valid_until<=CURRENT_TIMESTAMP";
            case "READ" -> PUBLISHED_IN_SCOPE+" AND r.user_id IS NOT NULL";
            default -> PUBLISHED_IN_SCOPE+" AND "+CURRENT;
        });
        if(!manage)args.add(actor.userId());
        from+=new ListConditions(query,args)
            .contains("keyword","a.title").contains("publisher","a.publisher_name")
            .choice("category","a.category","通知公告","公司动态","制度通知")
            .choice("state","a.state","DRAFT","PUBLISHED","WITHDRAWN").dates("published","a.published_at").sql();
        long total=db.count("SELECT count(*)"+from,args.toArray());args.add(size);args.add((page-1)*size);
        String select=manage?"SELECT a.*":"SELECT a.*,r.user_id IS NOT NULL AS viewed";
        String fields=manage?SUMMARY:SUMMARY+",viewed";
        return Sql.map("items",db.rows(select+from+" ORDER BY a.pinned DESC,a.published_at DESC NULLS LAST,a.created_at DESC,a.id LIMIT ? OFFSET ?",args.toArray()).stream().map(row->announcementView(actor,row,fields)).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 归属取首次创建时记录的账号，他人代为发布或编辑均不改变发起人。 */
    private static Map<String,Object> announcementView(Actor actor,Map<String,Object> row,String fields){
        var result=Sql.view(row,fields);
        result.put("isCreator",actor.userId().equals(row.get("created_by")));
        return result;
    }
    /** 员工可查看已对自己发布的公告，或追溯自己发起的公告；打开详情不记已读。 */
    public Map<String,Object> announcement(Actor actor,UUID id,boolean manage){
        if(manage)actor.require("PLATFORM.ANNOUNCEMENTS_MANAGE");
        var row=db.required("SELECT a.*,("+PUBLISHED_IN_SCOPE+") AS in_audience, r.user_id IS NOT NULL AS viewed "
            +"FROM portal.announcement a LEFT JOIN portal.announcement_read r ON r.announcement_id=a.id AND r.user_id=? WHERE a.id=?",
            actor.userId(),actor.userId(),id);
        boolean inAudience=(boolean)row.get("in_audience");
        if(!manage&&!inAudience&&!actor.userId().equals(row.get("created_by")))throw ApiFault.hidden();
        var result=announcementView(actor,row,SUMMARY+",body,content_format,image_keys,viewed");
        result.put("canConfirmRead",inAudience&&!(boolean)row.get("viewed"));
        if(manage)result.put("departmentIds",db.rows("SELECT department_id FROM portal.announcement_department WHERE announcement_id=? ORDER BY department_id",id).stream().map(d->d.get("department_id")).toList());
        return result;
    }
    /** 员工图片随公告发布范围可见；管理入口可预览草稿；旧版本移除的图片统一返回 404。 */
    public InlineImages.Image announcementImage(Actor actor,UUID id,UUID key,boolean manage){
        if(manage)actor.require("PLATFORM.ANNOUNCEMENTS_MANAGE");
        String select="""
            SELECT i.media_type,i.image_bytes FROM portal.announcement_image i
            JOIN portal.announcement a ON a.id=i.announcement_id
            WHERE a.id=? AND i.image_key=? AND i.image_key=ANY(a.image_keys)
            """;
        var row=manage?db.required(select,id,key):db.required(select+" AND (a.created_by=? OR ("+PUBLISHED_IN_SCOPE+"))",id,key,actor.userId(),actor.userId());
        return new InlineImages.Image((String)row.get("media_type"),(byte[])row.get("image_bytes"));
    }
    /** 员工明确确认才写入首次已读时间；旧记录和重复确认均保留原时间。 */
    public Map<String,Object> confirmAnnouncementRead(Actor actor,UUID id){
        db.required("WITH visible AS (SELECT a.id FROM portal.announcement a WHERE a.id=? AND "+PUBLISHED_IN_SCOPE+"), "
            +"confirmation AS (INSERT INTO portal.announcement_read(user_id,announcement_id) SELECT ?,id FROM visible ON CONFLICT DO NOTHING) "
            +"SELECT id FROM visible",id,actor.userId(),actor.userId());
        return Sql.map("viewed",true);
    }
    /** 发起人和公告维护人员按当前受众统计；到期或撤下后仍可追溯原有阅读时间。 */
    public Map<String,Object> announcementReadership(Actor actor, UUID id, Map<String,String> query,boolean manage) {
        if(manage)actor.require("PLATFORM.ANNOUNCEMENTS_MANAGE");
        else db.required("SELECT id FROM portal.announcement WHERE id=? AND created_by=?",id,actor.userId());
        Inputs.only(query,"status","page","pageSize");
        String status=Inputs.choice(query.getOrDefault("status","ALL"),"ALL","READ","UNREAD");
        int page=Inputs.integer(query.get("page"),1,1,100000);
        int size=Inputs.integer(query.get("pageSize"),10,1,100);
        // 以公告作左表，范围内没有员工时返回零人统计，并与查询不到公告区分。
        String audience="""
                FROM portal.announcement a
                LEFT JOIN iam.user_account u ON u.status='ACTIVE' AND NOT u.it_shared_account AND %s
                LEFT JOIN iam.department d ON d.id=u.department_id
                LEFT JOIN portal.announcement_read r ON r.announcement_id=a.id AND r.user_id=u.id
                WHERE a.id=? AND a.state IN ('PUBLISHED','WITHDRAWN') AND a.published_at IS NOT NULL
                  AND a.valid_from<=CURRENT_TIMESTAMP
                """.formatted(AUDIENCE_SCOPE);
        String statusFilter=switch(status) {
            case "READ" -> "r.read_at IS NOT NULL";
            case "UNREAD" -> "r.read_at IS NULL";
            default -> "TRUE";
        };
        var counts=db.required("SELECT count(r.read_at) AS read_count,count(u.id)-count(r.read_at) AS unread_count, "
            +"count(u.id) FILTER (WHERE "+statusFilter+") AS total "+audience+" GROUP BY a.id",id);
        var items=db.rows("SELECT u.employee_no,u.name,d.name AS department_name,r.read_at "+audience
            +" AND u.id IS NOT NULL AND "+statusFilter+" ORDER BY d.sort_order,d.code,u.employee_no LIMIT ? OFFSET ?",
            id,size,(page-1)*size).stream().map(row->Sql.view(row,"employee_no,name,department_name,read_at")).toList();
        return Sql.map("items",items,"page",page,"pageSize",size,"total",counts.get("total"),
            "readCount",counts.get("read_count"),"unreadCount",counts.get("unread_count"));
    }
    /** 有权限的编辑者取得最小部门选择数据，不借用人员管理接口。 */
    public Map<String,Object> departments(Actor actor){actor.require("PLATFORM.ANNOUNCEMENTS_MANAGE");return Sql.map("items",db.rows("SELECT id,name FROM iam.department WHERE status='ACTIVE' ORDER BY sort_order,code"));}

    /** Commands事务内部执行；发布者快照取真实账号，客户端不可指定发布人。 */
    public Map<String,Object> saveAnnouncement(Commands.Context ctx,UUID id,Map<String,Object> body){
        Inputs.only(body,"title","category","body","contentFormat","images","imageKeys","state","scope","validFrom","validUntil","pinned","departmentIds");
        String title=Inputs.text(body,"title",200),category=Inputs.choice(body.get("category"),"通知公告","公司动态","制度通知"),content=Inputs.text(body,"body",10000);
        String state=Inputs.choice(body.get("state"),"DRAFT","PUBLISHED","WITHDRAWN"),scope=Inputs.choice(body.get("scope"),"COMPANY","DEPARTMENTS");
        Instant from=instant(body.get("validFrom")),until=body.get("validUntil")==null?null:instant(body.get("validUntil"));
        if(until!=null&&!until.isAfter(from))throw ApiFault.invalid("公告结束时间必须晚于开始时间");
        boolean pinned=bool(body.get("pinned"));
        if(!(body.get("departmentIds") instanceof List<?> raw)||raw.size()>200)throw ApiFault.invalid("请选择有效部门范围，最多200个");
        var departments=raw.stream().map(Inputs::uuid).distinct().toList();
        if(scope.equals("DEPARTMENTS")&&departments.isEmpty()||scope.equals("COMPANY")&&!departments.isEmpty())throw ApiFault.invalid("公告范围与部门选择不一致");
        if(!departments.isEmpty()&&db.count("SELECT count(*) FROM iam.department WHERE status='ACTIVE' AND id IN(SELECT jsonb_array_elements_text(?::jsonb)::uuid)",Crypto.json(departments))!=departments.size())throw ApiFault.invalid("所选部门已停用或不存在");
        UUID target=id==null?UUID.randomUUID():id;
        Map<String,Object> prior=id==null?Map.of("content_format","LEGACY","image_keys",List.of()):
                db.required("SELECT row_version,content_format,image_keys FROM portal.announcement WHERE id=? FOR UPDATE",id);
        if(id!=null)Commands.version(ctx,prior);
        String format=Inputs.choice(body.getOrDefault("contentFormat",prior.get("content_format")),"LEGACY","MARKDOWN");
        if(format.equals("MARKDOWN"))content=(String)body.get("body"); // 首行缩进与行尾空格是 Markdown 正文的一部分。
        var uploaded=InlineImages.prepare(body.get("images"));
        var keys=announcementImageKeys(body,format,(List<?>)prior.get("image_keys"),uploaded.keySet());
        var author=db.required("SELECT u.name,d.name AS department FROM iam.user_account u JOIN iam.department d ON d.id=u.department_id WHERE u.id=?",ctx.actor().userId());
        if(id==null)db.update("INSERT INTO portal.announcement(id,title,category,body,content_format,image_keys,state,scope,valid_from,valid_until,pinned,created_by) VALUES(?,?,?,?,?,?::uuid[],?,?,?,?,?,?)",target,title,category,content,format,keys,state,scope,Timestamp.from(from),until==null?null:Timestamp.from(until),pinned,ctx.actor().userId());
        else db.update("UPDATE portal.announcement SET title=?,category=?,body=?,content_format=?,image_keys=?::uuid[],state=?,scope=?,valid_from=?,valid_until=?,pinned=?,row_version=row_version+1 WHERE id=?",title,category,content,format,keys,state,scope,Timestamp.from(from),until==null?null:Timestamp.from(until),pinned,target);
        uploaded.forEach((key,image)->{
            int inserted=db.update("INSERT INTO portal.announcement_image(announcement_id,image_key,media_type,image_bytes) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                    target,key,image.mediaType(),image.bytes());
            if(inserted==0)throw ApiFault.invalid("该公告图片标识已使用，请重新插入图片");
        });
        if(state.equals("PUBLISHED"))db.update("UPDATE portal.announcement SET publisher_name=?,publisher_department=?,published_at=CURRENT_TIMESTAMP WHERE id=? AND published_at IS NULL",author.get("name"),author.get("department"),target);
        db.update("DELETE FROM portal.announcement_department WHERE announcement_id=?",target);
        for(UUID department:departments)db.update("INSERT INTO portal.announcement_department VALUES(?,?)",target,department);
        var saved=announcement(ctx.actor(),target,true);
        var snapshot=new LinkedHashMap<>(saved);
        // 个人阅读和归属标识随查看人变化，不属于公告的修订内容。
        snapshot.keySet().removeAll(Set.of("viewed","isCreator","canConfirmRead"));
        db.update("INSERT INTO portal.announcement_revision(announcement_id,version,snapshot,changed_by) VALUES(?,?,?::jsonb,?)",target,saved.get("rowVersion"),Crypto.json(snapshot),ctx.actor().userId());
        return saved;
    }
    /** 保留的标识只能来自这篇公告上一版，新图由图片解码结果提供。 */
    private static String[] announcementImageKeys(Map<String,Object> body,String format,List<?> previous,Set<UUID> uploaded){
        if(!(body.getOrDefault("imageKeys",previous) instanceof List<?> requested)||requested.size()+uploaded.size()>3)
            throw ApiFault.invalid("每篇公告最多添加 3 张图片");
        var keys=new LinkedHashSet<UUID>();
        for(Object item:requested){
            UUID key=Inputs.uuid(item);
            if(!previous.contains(key))throw ApiFault.invalid("公告中的图片已变更，请刷新后重试");
            if(!keys.add(key)||uploaded.contains(key))throw ApiFault.invalid("公告中的图片标识重复");
        }
        keys.addAll(uploaded);
        if(!keys.isEmpty()&&!format.equals("MARKDOWN"))throw ApiFault.invalid("只有 Markdown 公告可以插入图片");
        return keys.stream().map(UUID::toString).toArray(String[]::new);
    }
    /** 员工只看启用链接；固定数量上限避免首页无限增长，管理页面有独立维护权。 */
    public Map<String,Object> sites(Actor actor,boolean manage){if(manage)actor.require("PLATFORM.SITES_MANAGE");return Sql.map("items",db.rows("SELECT * FROM portal.site"+(manage?"":" WHERE enabled")+" ORDER BY category,sort_order,id LIMIT 100").stream().map(row->Sql.view(row,"id,name,url,category,sort_order,enabled,row_version")).toList());}
    /** 网站查询仅供受权命令回执；员工入口只读取启用列表。 */
    public Map<String,Object> site(UUID id){return Sql.view(db.required("SELECT * FROM portal.site WHERE id=?",id),"id,name,url,category,sort_order,enabled,row_version");}
    /** 链接入口只接收HTTP(S)，不允许脚本、凭据或相对路径；不向目标站点发起服务端请求。 */
    public Map<String,Object> saveSite(Commands.Context ctx,UUID id,Map<String,Object> body){
        Inputs.only(body,"name","url","category","sortOrder","enabled");
        String category=body.containsKey("category")?Inputs.choice(body.get("category"),"QUICK_LINK","ONLINE_TOOL","SOFTWARE_DOWNLOAD"):null;String name=Inputs.text(body,"name",80),url=Inputs.text(body,"url",2000);
        try{URI uri=URI.create(url);if(!Set.of("http","https").contains(Objects.toString(uri.getScheme(),"").toLowerCase(Locale.ROOT))||uri.getHost()==null||uri.getRawUserInfo()!=null)throw new IllegalArgumentException();}catch(IllegalArgumentException failure){throw ApiFault.invalid("网站地址必须是完整的HTTP或HTTPS地址，且不包含登录凭据");}
        int sort=Inputs.integer(body.get("sortOrder"),0,0,10000);boolean enabled=bool(body.get("enabled"));UUID target=id==null?UUID.randomUUID():id;
        if(id==null){if(db.count("SELECT count(*) FROM portal.site")>=100)throw ApiFault.conflict("最多维护100个网站，请编辑或删除已有网站");db.update("INSERT INTO portal.site(id,name,url,category,sort_order,enabled) VALUES(?,?,?,?,?,?)",target,name,url,Objects.requireNonNullElse(category,"QUICK_LINK"),sort,enabled);}
        else{Commands.version(ctx,db.required("SELECT row_version FROM portal.site WHERE id=? FOR UPDATE",id));db.update("UPDATE portal.site SET name=?,url=?,category=COALESCE(?,category),sort_order=?,enabled=?,row_version=row_version+1 WHERE id=?",name,url,category,sort,enabled,id);}
        return site(target);
    }
    /** 删除链接须匹配当前版本，删除后从管理列表和员工首页移除。 */
    public Map<String,Object> deleteSite(Commands.Context ctx,UUID id){
        Commands.version(ctx,db.required("SELECT row_version FROM portal.site WHERE id=? FOR UPDATE",id));
        db.update("DELETE FROM portal.site WHERE id=?",id);
        return Sql.map("id",id);
    }
    /** 日期在API入口统一转换，避免数据库驱动把字符串当时间隐式比较。 */
    private static Instant instant(Object value){try{return Instant.parse(Objects.toString(value,""));}catch(DateTimeException error){throw ApiFault.invalid("请填写带时区的有效日期时间");}}
    /** JSON布尔字段不接受字符串或数字降级。 */
    private static boolean bool(Object value){if(!(value instanceof Boolean flag))throw ApiFault.invalid("开关必须为布尔值");return flag;}
}
