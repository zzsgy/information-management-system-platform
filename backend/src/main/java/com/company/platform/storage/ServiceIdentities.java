package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.util.*;

/** 服务身份与员工登录分别核验；失效必须报出服务故障，不能误报文件损坏。 */
@Service
public class ServiceIdentities {
    private final Sql db;
    private final Environment env;
    public ServiceIdentities(Sql db,Environment env){this.db=db;this.env=env;}

    /** 每次执行重新读取网页绑定，停用和范围变更不会被启动时配置缓存掩盖。 */
    public String configured(String property) {
        String slot=switch(property) {
            case "platform.scanner.service-id" -> "DRIVE_SCANNER";
            case "platform.scanner.archive-service-id" -> "ARCHIVE_SCANNER";
            case "platform.scanner.platform-service-id" -> "PLATFORM_SCANNER";
            case "platform.office.service-id" -> "OFFICE";
            default -> throw ApiFault.invalid("不支持的服务用途");
        };
        var binding=db.one("SELECT service_identity_id FROM iam.service_binding WHERE slot=?",slot);
        return binding!=null && binding.get("service_identity_id")!=null
                ? binding.get("service_identity_id").toString() : env.getProperty(property,"");
    }

    public UUID require(UUID id,String type,String module,UUID department,String action){
        var row=db.one("SELECT *,valid_until>CURRENT_TIMESTAMP AS valid FROM iam.service_identity WHERE id=? FOR SHARE",id);
        String label=type.equals("OFFICE")?"Office预览与编辑服务":"文件检测服务";
        if(row==null||!type.equals(row.get("service_type"))||!module.equals(row.get("allowed_module"))||!"ACTIVE".equals(row.get("status")))
            throw new ApiFault(503,"SERVICE_IDENTITY_UNAVAILABLE",label+"身份未就绪，请联系管理员");
        if(!Boolean.TRUE.equals(row.get("valid")))throw new ApiFault(503,"SERVICE_IDENTITY_EXPIRED",label+"授权已到期，请联系管理员续期");
        if(!((List<?>)row.get("department_scope")).contains(department)||!((List<?>)row.get("allowed_actions")).contains(action))
            throw new ApiFault(503,"SERVICE_IDENTITY_SCOPE_MISMATCH",label+"未获当前部门或操作授权，请联系管理员");
        return id;
    }

    /** 仅展示当前部署实际引用的身份；不返回凭据及部门授权明细。 */
    public List<Map<String,Object>> status(){
        List<Map<String,Object>> result=new ArrayList<>();
        String[][] services={{"platform.scanner.service-id","DRIVE","FILE_CHECK","公盘文件检测"},{"platform.scanner.archive-service-id","ARCHIVE","FILE_CHECK","档案文件检测"},{"platform.scanner.platform-service-id","PLATFORM","FILE_CHECK","人员与台账文件检测"},{"platform.office.service-id","DRIVE","OFFICE","Office预览与编辑"}};
        for(var service:services){
            if(service[2].equals("OFFICE")&&!env.getProperty("platform.office.enabled",Boolean.class,false))continue;
            String configured=configured(service[0]);
            Map<String,Object> row=null;
            try{row=db.one("SELECT id,valid_until,status,service_type,allowed_module,cardinality(department_scope)>0 AS scoped,?=ANY(allowed_actions) AS action_allowed,valid_until<=CURRENT_TIMESTAMP AS expired,valid_until<=CURRENT_TIMESTAMP+interval '30 days' AS expiring FROM iam.service_identity WHERE id=?",service[2].equals("OFFICE")?"VIEW":"FILE_CHECK",UUID.fromString(configured));}catch(IllegalArgumentException ignored){/* 配置缺失也必须出现在检查结果中。 */}
            String state=row==null?"NOT_CONFIGURED":!"ACTIVE".equals(row.get("status"))?"DISABLED":!service[1].equals(row.get("allowed_module"))||!service[2].equals(row.get("service_type"))||!Boolean.TRUE.equals(row.get("scoped"))||!Boolean.TRUE.equals(row.get("action_allowed"))?"SCOPE_MISMATCH":Boolean.TRUE.equals(row.get("expired"))?"EXPIRED":Boolean.TRUE.equals(row.get("expiring"))?"EXPIRING":"READY";
            result.add(Sql.map("name",service[3],"module",service[1],"state",state,"validUntil",row==null?null:row.get("valid_until")));
        }
        return result;
    }
}
