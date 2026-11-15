package com.company.platform.operations;

/** 通道失败只保留安全分类；发送后断连的结果未知，不自动重发造成重复通知。 */
public final class DeliveryFailure extends RuntimeException {
    /** true表示对端可能已接受，需要人工核对；不携带服务响应或秘密。 */
    public final boolean uncertain;
    public DeliveryFailure(boolean uncertain) { super(uncertain?"DELIVERY_UNKNOWN":"DELIVERY_UNAVAILABLE"); this.uncertain=uncertain; }
}
