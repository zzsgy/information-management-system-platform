package com.company.platform.archive;

import org.springframework.stereotype.Component;
import java.time.Instant;

/** 实物业务的服务端时钟；生产只读系统时钟，测试可注入时钟验证跨日延期，不提供网页改时接口。 */
@Component
public class PhysicalTime {
    /** 返回当前真实时刻，所有实际交付与收到时间均不得晚于它。 */
    public Instant now(){return Instant.now();}
}
