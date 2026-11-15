package com.company.platform.storage;

import com.company.platform.Actor;
import java.util.*;

/** 新业务技术任务的受控扩展口，查询与执行均重查本领域授权，不以任务ID代替正文权限。 */
public interface FileDomainJobs {
    /** 由服务器创建的稳定任务类型。 */ String jobType();
    /** 可信工作器执行固定任务，不接受浏览器指定内部文件路径。 */ void process(Actor actor,UUID job,UUID request);
    /** 只返回本人仍有权读取的技术状态与业务结果。 */ Map<String,Object> status(Actor actor,Map<String,Object> job);
}
