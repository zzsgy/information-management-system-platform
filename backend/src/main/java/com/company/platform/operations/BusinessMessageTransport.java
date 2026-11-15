package com.company.platform.operations;

import java.util.UUID;

/** 可替换的业务消息投递边界；稳定投递号必须作为适配器去重键，重试不能重新执行业务。 */
public interface BusinessMessageTransport {
    /** 最小消息，不接受档案题名、正文、附件路径或口令。邮箱来自当前已维护员工资料。 */
    record Message(
        /** 每次重试保持不变的投递号。 */ UUID deliveryId,
        /** 站内通知号，链接打开后仍须重新鉴权。 */ UUID notificationId,
        /** 明确接收人。 */ UUID recipientId,
        /** 已维护的公司邮箱，不从工号推测。 */ String address,
        /** 固定安全模板类型。 */ String kind,
        /** 受支持来源类别。 */ String sourceKind,
        /** 不构成授权的来源引用。 */ UUID sourceId) { }
    /** 成功返回稳定回执；任何不可确定结果抛出异常，领取者按租约和幂等键恢复。 */
    String send(Message message);
    /** 展示真实通道，不能把SMTP接受说成员工已阅读。 */
    default String channel(){return "LOCAL_DEVELOPMENT_ONLY";}
    /** 本机文件可安全去重；SMTP发送后崩溃需要人工核对。 */
    default boolean replaySafe(){return true;}
}
