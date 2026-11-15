package com.company.platform.archive;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

/** 目录扩展字段按不可变模板版本解释；历史值不跟随新模板重排或改型。 */
@Service
public class CatalogTemplates {
    /** 查询、档案职责及原子管理命令。 */
    private final Sql db; private final ArchiveAuthority authority; private final ArchiveCommands commands;
    /** 基础字段不得通过扩展定义重名或降低关键性。 */
    private static final Set<String> RESERVED=Set.of("id","title","safeTitle","classification","departmentId","boxId","fileNo","boxNo","itemNo","retentionCode","archiveDate","pdfVersionId","templateVersionId","keywords","abstract","notes","location","ownerId","carrierType","categoryCode","formedDate","numberMonth","paperTags");
    public CatalogTemplates(Sql db,ArchiveAuthority authority,ArchiveCommands commands){this.db=db;this.authority=authority;this.commands=commands;}
    /** 创建初版或追加新版本；旧定义保留，停用另行办理，不覆盖已有值。 */
    public Map<String,Object> save(Commands.Context context,UUID previous,Map<String,Object> body){
        Inputs.only(body,"templateKey","name","categoryCode","objectType","fields");
        String key=Inputs.text(body,"templateKey",128),name=Inputs.text(body,"name",128),category=Inputs.choice(body.get("categoryCode"),"COMMON","ADMIN","SALES_CONTRACT","PURCHASE_CONTRACT","PROJECT_CONTRACT","QUALITY","WORK_RECORD"),type=Inputs.choice(body.get("objectType"),"BOX","RECORD");
        List<Map<String,Object>> fields=definitions(body.getOrDefault("fields",List.of()));
        UUID target=previous==null?ApiController.COLLECTION:previous;
        return commands.run(context,body,previous==null?"ARCHIVE_COLLECTION":"ARCHIVE_REVISION",target,previous==null?"CREATE_DIRECTORY_TEMPLATE":"REVISE_DIRECTORY_TEMPLATE",true,()->authority.requireSuper(context.actor(),"ARCHIVE.CONFIG_MANAGE"),()->{
            db.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","directory-template:"+key);
            int next=(int)db.count("SELECT COALESCE(max(version_no),0)+1 FROM archive.directory_template WHERE template_key=?",key);
            if(previous==null&&next!=1)throw ApiFault.conflict("模板代码已存在，请新增版本");
            if(previous!=null){var old=db.required("SELECT * FROM archive.directory_template WHERE id=? FOR SHARE",previous);Commands.version(context,old);
                if(!key.equals(old.get("template_key"))||!category.equals(old.get("category_code"))||!type.equals(old.get("object_type"))||next!=((Number)old.get("version_no")).intValue()+1)throw ApiFault.conflict("请基于同类型模板的最新版本修订");
                Map<String,Map<String,Object>> proposed=byCode(fields);
                for(var field:definitions(json(old.get("fields")))){var newer=proposed.get(field.get("code"));if(newer==null||!field.get("type").equals(newer.get("type"))||(Boolean.TRUE.equals(field.get("critical"))&&!Boolean.TRUE.equals(newer.get("critical"))))throw ApiFault.invalid("已有字段只能停用，不能删除、改型或降低关键性");}
            }
            UUID id=UUID.randomUUID();db.update("INSERT INTO archive.directory_template(id,template_key,version_no,category_code,object_type,name,fields,created_by) VALUES(?,?,?,?,?,?,?::jsonb,?)",id,key,next,category,type,name,Crypto.json(fields),context.actor().userId());return view(id);
        },this::view);
    }
    /** 启停只影响新的选用；已登记档案继续按原版本解释。 */
    public Map<String,Object> state(Commands.Context context,UUID id,Map<String,Object> body){Inputs.only(body,"state");String state=Inputs.choice(body.get("state"),"ACTIVE","DISABLED");return commands.run(context,body,"ARCHIVE_REVISION",id,"SET_DIRECTORY_TEMPLATE_STATE",true,()->authority.requireSuper(context.actor(),"ARCHIVE.CONFIG_MANAGE"),()->{var row=db.required("SELECT * FROM archive.directory_template WHERE id=? FOR UPDATE",id);Commands.version(context,row);db.update("UPDATE archive.directory_template SET state=?,row_version=row_version+1 WHERE id=?",state,id);return view(id);},this::view);}
    /** 只向目录维护者展示定义；定义不包含档案值或审批身份。 */
    public Map<String,Object> list(Actor actor,boolean all){actor.require("ARCHIVE.CATALOG_MANAGE");return Sql.map("items",db.rows("SELECT * FROM archive.directory_template "+(all?"":"WHERE state='ACTIVE' ")+"ORDER BY category_code,object_type,template_key,version_no DESC LIMIT 500").stream().map(this::projection).toList());}
    /** 固定模板版本投影，JSON类型显式解码。 */
    public Map<String,Object> view(UUID id){return projection(db.required("SELECT * FROM archive.directory_template WHERE id=?",id));}
    /** 批量列表直接投影已有行，避免每个模板重新查询。 */
    private Map<String,Object> projection(Map<String,Object> row){var result=Sql.view(row,"id,template_key,version_no,category_code,object_type,name,state,row_version");result.put("fields",json(row.get("fields")));return result;}
    /** 校验新增或变更的扩展值，停用字段只能保留原值，未知字段拒绝。 */
    public Map<String,Object> validate(UUID id,String category,String type,Object input,Map<String,Object> previous,boolean selecting){
        var template=db.required("SELECT * FROM archive.directory_template WHERE id=? FOR SHARE",id);
        // 通用台账的字段结构独立于业务类别，采购合同等类别沿用同一模板。
        if(!"COMMON".equals(template.get("category_code"))&&!category.equals(template.get("category_code")))throw ApiFault.invalid("所选模板不适用于当前档案类别，请使用通用台账模板");
        if(!type.equals(template.get("object_type")))throw ApiFault.invalid("档案盒与文件的模板不能混用");
        if(selecting&&!"ACTIVE".equals(template.get("state")))throw ApiFault.invalid("所选目录模板已停用，请选择有效模板");
        if(!(input instanceof Map<?,?> raw)||raw.size()>50)throw ApiFault.invalid("扩展字段值格式无效");
        Map<String,Object> values=new LinkedHashMap<>();Map<String,Map<String,Object>> fields=byCode(definitions(json(template.get("fields"))));
        for(var pair:raw.entrySet()){String code=Objects.toString(pair.getKey());if(type.equals("BOX")&&code.equals("paperTags")){values.put(code,ArchivePreservation.tags(pair.getValue()));continue;}var field=fields.get(code);if(field==null)throw ApiFault.invalid("包含模板未定义字段："+code);Object value=pair.getValue();
            if(!Boolean.TRUE.equals(field.get("enabled"))&&!Objects.equals(value,previous.get(code)))throw ApiFault.invalid("停用字段不能改写："+field.get("name"));
            if(value==null||"".equals(value)){values.put(code,null);continue;}
            switch((String)field.get("type")){
                case "TEXT" -> value=Inputs.text(Sql.map("value",value),"value",2000);
                case "NUMBER" -> {
                    if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue())||Math.abs(number.doubleValue())>1e15)
                        throw ApiFault.invalid("数值字段超出范围");
                    // 新建盒允许零份；历史台账仍按原样读取。
                    if(selecting&&type.equals("BOX")&&code.equals("copyCount")&&
                        (number.doubleValue()<0||new java.math.BigDecimal(number.toString()).stripTrailingZeros().scale()>0))
                        throw ApiFault.invalid("保存份数必须是大于等于 0 的整数");
                }
                case "BOOLEAN" -> {if(!(value instanceof Boolean))throw ApiFault.invalid("请选择是或否");}
                case "DATE" -> {try{value=LocalDate.parse(value.toString()).toString();}catch(RuntimeException invalid){throw ApiFault.invalid("日期字段须为有效年月日");}}
                case "SELECT" -> {if(!((List<?>)field.get("options")).contains(value))throw ApiFault.invalid("选项不在模板允许范围");}
                default -> throw ApiFault.invalid("字段类型无效");
            }values.put(code,value);
        }
        for(var f:fields.values()){String code=(String)f.get("code");if(Boolean.TRUE.equals(f.get("enabled"))&&Boolean.TRUE.equals(f.get("required"))&&values.get(code)==null)throw ApiFault.invalid("请填写："+f.get("name"));if(!Boolean.TRUE.equals(f.get("enabled"))&&previous.containsKey(code)&&!Objects.equals(previous.get(code),values.get(code)))throw ApiFault.invalid("停用字段的历史值必须保留");}
        return values;
    }
    /** 扩展字段是否需要复核由服务端固定模板决定。 */
    public boolean critical(UUID id,Map<String,Object> before,Map<String,Object> after){if(!Objects.equals(before.get("paperTags"),after.get("paperTags")))return true;for(var f:definitions(json(db.required("SELECT fields FROM archive.directory_template WHERE id=?",id).get("fields"))))if(Boolean.TRUE.equals(f.get("critical"))&&!Objects.equals(before.get(f.get("code")),after.get(f.get("code"))))return true;return false;}
    /** 有界字段定义，禁止脚本、任意表达式和基础字段覆盖。 */
    public static List<Map<String,Object>> definitions(Object input){List<Map<String,Object>> result=new ArrayList<>();Set<String> codes=new HashSet<>();for(var f:Inputs.objects(input,50)){Inputs.only(f,"code","name","type","required","critical","searchable","visible","enabled","order","options");String code=Inputs.text(f,"code",64),type=Inputs.choice(f.get("type"),"TEXT","NUMBER","DATE","BOOLEAN","SELECT");if(!code.matches("[a-z][A-Za-z0-9]{0,63}")||RESERVED.contains(code)||!codes.add(code))throw ApiFault.invalid("扩展字段代码重复或与基础字段冲突");Map<String,Object> checked=Sql.map("code",code,"name",Inputs.text(f,"name",100),"type",type,"order",Inputs.integer(f.get("order"),result.size(),0,999));for(String flag:List.of("required","critical","searchable","visible","enabled")){Object value=f.getOrDefault(flag,flag.equals("visible")||flag.equals("enabled"));if(!(value instanceof Boolean))throw ApiFault.invalid("字段开关必须为布尔值");checked.put(flag,value);}Object raw=f.getOrDefault("options",List.of());if(!(raw instanceof List<?> opts)||opts.size()>100)throw ApiFault.invalid("字段选项最多100个");List<String> options=((List<?>)raw).stream().map(v->Inputs.text(Sql.map("v",v),"v",100)).toList();if(new HashSet<>(options).size()!=options.size()||(type.equals("SELECT")&&options.isEmpty())||(!type.equals("SELECT")&&!options.isEmpty()))throw ApiFault.invalid("字段选项无效");checked.put("options",options);result.add(checked);}result.sort(Comparator.comparingInt(f->((Number)f.get("order")).intValue()));return result;}
    /** 按字段代码索引，避免逐值反复遍历定义。 */
    private static Map<String,Map<String,Object>> byCode(List<Map<String,Object>> fields){Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(var f:fields)result.put((String)f.get("code"),f);return result;}
    /** PostgreSQL JSONB只在业务边界解析，不把驱动对象返回前端。 */
    public static Object json(Object value){return value instanceof Map<?,?>||value instanceof List<?>?value:Crypto.JSON.readValue(value.toString(),Object.class);}
    /** 目录值对象显式解码；来源只允许数据库快照或已验证输入。 */
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value){Object parsed=json(value);if(!(parsed instanceof Map<?,?>))throw ApiFault.invalid("字段值必须为对象");return (Map<String,Object>)parsed;}
}
