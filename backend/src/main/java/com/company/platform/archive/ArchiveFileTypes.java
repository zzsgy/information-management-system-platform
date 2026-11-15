package com.company.platform.archive;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** 维护新登记的文件类型选项，已登记档案仍保存原有文字。 */
@Service
public class ArchiveFileTypes {
    private final Sql db;
    private final Commands commands;

    public ArchiveFileTypes(Sql db,Commands commands) {
        this.db=db;
        this.commands=commands;
    }

    /** 登记读取选项名称，调用方已校验档案维护权限。 */
    List<String> names() {
        return db.rows("SELECT name FROM archive.file_type_option ORDER BY sort_order").stream()
            .map(row->(String)row.get("name")).toList();
    }

    /** 只有平台 IT 管理员可以通过管理中心读取选项列表。 */
    public Map<String,Object> options(Actor actor) {
        requireIt(actor);
        return Sql.map("items",names());
    }

    /** 追加非空且唯一的名称，复用本人再验证、幂等回执和审计。 */
    public Map<String,Object> add(Commands.Context context,Map<String,Object> body) {
        Inputs.only(body,"name");
        String name=Inputs.text(body,"name",100);
        return commands.run(context,body,"COLLECTION",ApiController.COLLECTION,"CREATE_ARCHIVE_FILE_TYPE",
            ()->requireIt(context.actor()),()->{
                var inserted=db.one("INSERT INTO archive.file_type_option(name) VALUES(?) ON CONFLICT(name) DO NOTHING RETURNING id,name",name);
                if(inserted==null)throw ApiFault.conflict("该文件类型已存在");
                return inserted;
            },id->db.required("SELECT id,name FROM archive.file_type_option WHERE id=?",id));
    }

    /** 通用档案盒登记可留空，填了就必须来自当前选项。 */
    void validateRegistration(Object value) {
        if(value!=null && !value.equals("") &&
            (!(value instanceof String name) || db.count("SELECT count(*) FROM archive.file_type_option WHERE name=?",name.trim())!=1))
            throw ApiFault.invalid("请选择文件类型下拉选项");
    }

    private void requireIt(Actor actor) {
        if(!actor.systemAdministrator())throw ApiFault.denied();
    }
}
