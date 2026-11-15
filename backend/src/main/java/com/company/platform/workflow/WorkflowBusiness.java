package com.company.platform.workflow;

import com.company.platform.Actor;
import java.util.*;

/** 固定流程的业务适配边界；通用引擎不通过字符串表名修改档案或借阅状态。 */
public interface WorkflowBusiness {
    /** 单一稳定业务类型。 */ String type();
    /** 依据不可变输入核对当前对象及版本；每次决定和最终生效前都执行。 */ void verify(Map<String,Object> instance);
    /** 在同一决定事务内提交批准或拒绝的实际业务结果，不执行外部消息发送。 */ void finish(Map<String,Object> instance,boolean approved,UUID approver);
    /** 返回当前流程人员必要的受控业务详情，不能借任务接口取得原PDF。 */ Map<String,Object> view(Actor actor,Map<String,Object> instance);
}
