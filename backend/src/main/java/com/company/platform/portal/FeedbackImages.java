package com.company.platform.portal;

import com.company.platform.Actor;
import com.company.platform.Sql;
import com.company.platform.InlineImages.Image;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 反馈截图随正文保存，访问权限继承反馈。 */
@Component
public class FeedbackImages {
    private final Sql db;

    public FeedbackImages(Sql db) {
        this.db = db;
    }

    /** 在正文的同一事务内写入截图。 */
    void save(UUID feedback, UUID reply, Map<UUID, Image> images) {
        images.forEach((key, image) -> db.update("""
                INSERT INTO portal.feedback_image(feedback_id,reply_id,image_key,media_type,image_bytes)
                VALUES(?,?,?,?,?)
                """, feedback, reply, key, image.mediaType(), image.bytes()));
    }

    /** 仅反馈人和持有反馈办理权限的 IT 可以查看，越权按不可见处理。 */
    Image read(Actor actor, UUID feedback, UUID message, UUID key) {
        var row = db.required("""
                SELECT i.media_type,i.image_bytes
                FROM portal.feedback_image i JOIN portal.feedback f ON f.id=i.feedback_id
                WHERE f.id=? AND COALESCE(i.reply_id,i.feedback_id)=? AND i.image_key=? AND (f.applicant_id=? OR ?)
                """, feedback, message, key, actor.userId(), actor.actions().contains("PLATFORM.FEEDBACK_MANAGE"));
        return new Image((String) row.get("media_type"), (byte[]) row.get("image_bytes"));
    }
}
