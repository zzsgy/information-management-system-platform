package com.company.platform;

/** 仅用于写入任何业务变更之前的认证拒绝；允许提交失败计数，其余异常必须回滚。 */
public final class CredentialFault extends ApiFault {
    /** 区分口令失败与业务校验错误，避免保护故障意外提交半成品。 */
    public CredentialFault(int status,String code,String message) { super(status,code,message); }
}
