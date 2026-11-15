package com.company.platform.archive;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** 借阅目录只投影获权题名和可申请的载体，提交仍由原业务服务核对。 */
@Service
public class ArchiveBorrowDirectory {
    private final Sql db;
    private final ArchiveAuthority authority;
    public ArchiveBorrowDirectory(Sql db,ArchiveAuthority authority){this.db=db;this.authority=authority;}

    // 整盒纸质申请使用全部成员；电子申请只使用已有发布 PDF 的成员。
    private static final String BOX_SCOPE="""
        WITH members AS (
          SELECT b.id,b.title,b.box_no,r.id AS record_id,r.state,r.safe_title,r.file_no,
            r.current_revision_id,r.current_pdf_version_id,
            archive.visibility(r.id,?::uuid,?) AS representation
          FROM archive.box b JOIN archive.record r ON r.box_id=b.id
          WHERE b.state='PUBLISHED'
        ), grouped AS (
          SELECT id,
            CASE WHEN bool_and(representation IN ('METADATA','MANAGE')) THEN title
              ELSE min(coalesce(safe_title,file_no)) FILTER (WHERE state='PUBLISHED' AND representation<>'HIDDEN') || '（所在档案盒）' END AS label,
            CASE WHEN bool_and(representation IN ('METADATA','MANAGE')) THEN box_no END AS box_no,
            count(*) FILTER (WHERE state='PUBLISHED' AND current_pdf_version_id IS NOT NULL) BETWEEN 1 AND 50
              AND bool_and(representation<>'HIDDEN') FILTER (WHERE state='PUBLISHED' AND current_pdf_version_id IS NOT NULL) AS electronic,
            count(*)<=50 AND bool_and(state='PUBLISHED' AND current_revision_id IS NOT NULL AND representation<>'HIDDEN') AS paper_members
          FROM members GROUP BY id,title,box_no
        ), originals AS (
          SELECT g.*,p.id AS physical_item_id,? AND g.paper_members AND p.id IS NOT NULL AS paper_eligible, EXISTS (
            SELECT 1 FROM archive.physical_loan l WHERE l.box_id=g.id AND l.state='IN_USE'
          ) AS occupied
          FROM grouped g LEFT JOIN archive.physical_item p ON p.box_id=g.id AND p.object_type='BOX'
        ), scoped AS (
          SELECT *,paper_eligible AND NOT occupied AS paper,paper_eligible AND occupied AS paper_occupied
          FROM originals
        )
        """;

    /** 只分页返回可借盒。题名开放时，完整盒名和盒号不参与检索。 */
    public Map<String,Object> boxes(Actor actor,Map<String,String> query){
        actor.require("ARCHIVE.ACCESS");
        Inputs.only(query,"page","pageSize","keyword");
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);
        String keyword=query.getOrDefault("keyword","").trim();
        if(keyword.length()>200)throw ApiFault.invalid("检索条件过长");
        String match="%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        String where=" FROM scoped WHERE (electronic OR paper_eligible) AND label IS NOT NULL AND concat_ws(' ',label,box_no) ILIKE ?";
        Object[] args={actor.userId(),canManage(actor),canBorrowPaper(actor),match};
        long total=db.count(BOX_SCOPE+"SELECT count(*)"+where,args);
        var rows=db.rows(BOX_SCOPE+"SELECT id,label,box_no,electronic,paper,paper_occupied"+where+" ORDER BY label,id LIMIT ? OFFSET ?",
            actor.userId(),canManage(actor),canBorrowPaper(actor),match,size,(page-1)*size);
        return Sql.map("items",rows.stream().map(row->Sql.view(row,"id,label,box_no,electronic,paper,paper_occupied")).toList(),"total",total);
    }

    /** 对当前盒或文件返回真实的可用载体，纸质标识只用于本次申请。 */
    public Map<String,Object> options(Actor actor,String type,UUID id){
        actor.require("ARCHIVE.ACCESS");
        if("BOX".equals(type)){
            var row=db.required(BOX_SCOPE+"SELECT * FROM scoped WHERE id=? AND (electronic OR paper_eligible)",
                actor.userId(),canManage(actor),canBorrowPaper(actor),id);
            return Sql.map("electronic",Boolean.TRUE.equals(row.get("electronic")),
                "physicalItemId",Boolean.TRUE.equals(row.get("paper"))?row.get("physical_item_id"):null);
        }
        authority.detail(actor,id);
        var row=authority.record(id);
        boolean published="PUBLISHED".equals(row.get("state"))&&"PUBLISHED".equals(row.get("box_state"));
        var physical=published&&canBorrowPaper(actor)?db.one("""
            SELECT p.id FROM archive.physical_item p WHERE p.record_id=? AND NOT EXISTS(
              SELECT 1 FROM archive.physical_loan l JOIN archive.physical_item q ON q.id=l.physical_item_id
              WHERE l.box_id=p.box_id AND l.state='IN_USE' AND (q.object_type='BOX' OR q.id=p.id))
            """,id):null;
        return Sql.map("electronic",published&&row.get("current_pdf_version_id")!=null,
            "physicalItemId",physical==null?null:physical.get("id"));
    }

    private boolean canManage(Actor actor){return actor.actions().contains("ARCHIVE.CATALOG_MANAGE");}
    private boolean canBorrowPaper(Actor actor){return actor.actions().contains("ARCHIVE.PHYSICAL_BORROW");}
}
