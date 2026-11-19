package com.company.platform.operations;

import com.company.platform.*;
import com.company.platform.storage.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** 版本探测在后台执行，管理员页面读取最近结果和实时有效期。 */
@Service
public class ServiceHealth {
    private final ServiceIdentities identities;
    private final OfficeGateway office;
    private final boolean scheduled;
    private volatile Map<String,Object> officeState=Sql.map("state","CHECKING","checkedAt",null);
    /** IT 最近一次实测结果；与身份有效期分别展示，不把配置完整等同于程序可用。 */
    private volatile List<Map<String,Object>> runtimeChecks=List.of();
    public void runtimeChecks(List<Map<String,Object>> checks){runtimeChecks=List.copyOf(checks);}
    public ServiceHealth(ServiceIdentities identities,OfficeGateway office,Environment env){this.identities=identities;this.office=office;this.scheduled=env.getProperty("platform.operations.schedule-enabled",Boolean.class,true);}
    @Scheduled(fixedDelay=60000,initialDelay=1000)
    public void check(){if(scheduled)probe();}
    public void probe(){
        String state="DISABLED";
        if(Boolean.TRUE.equals(office.status().get("enabled"))){try{office.verifyVersion();state="READY";}catch(ApiFault failure){state=failure.code;}catch(RuntimeException failure){state="OFFICE_UNAVAILABLE";}}
        officeState=Sql.map("state",state,"checkedAt",Instant.now().toString());
    }
    public Map<String,Object> status(){
        var services=identities.status();var current=officeState;
        boolean warning=services.stream().anyMatch(row->!"READY".equals(row.get("state")))||!Set.of("READY","DISABLED").contains(current.get("state"))||runtimeChecks.stream().anyMatch(row->!Boolean.TRUE.equals(row.get("ready")));
        return Sql.map("warning",warning,"identities",services,"office",current,"runtimeChecks",runtimeChecks);
    }
}
