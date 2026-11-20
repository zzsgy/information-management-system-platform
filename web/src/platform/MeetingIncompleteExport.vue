<script setup lang="ts">
import {ref} from "vue";
import {ApiError, profile, requestFetch, responseError, responseRequestId} from "./api";
const props=defineProps<{meetingId?:string}>();
const busy=ref(false),error=ref("");
/** 当前会议与本人所有会议使用独立GET导出，不改变筛选、邀请或事项状态。 */
async function download(){
 busy.value=true;error.value="";
 try{
  const path=props.meetingId?`/meetings/${props.meetingId}/incomplete-export`:"/meetings/incomplete-export";
  const response=await requestFetch("/api/v1"+path,{credentials:"same-origin",cache:"no-store",headers:{"X-Request-Id":crypto.randomUUID(),"X-CSRF-Token":profile.value?.csrfToken||"","X-User-Activity":"1"}});
  if(!response.ok)throw await responseError(response,"会议未完成情况导出失败");
  if(response.headers.get("Content-Type")?.split(";")[0]!=="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")throw new ApiError("WORKBOOK_RESPONSE_INVALID","导出响应不是Excel文件，未保存文件",responseRequestId(response));
  const blob=await response.blob();if(!blob.size)throw new ApiError("WORKBOOK_CONTENT_EMPTY","服务器返回的导出文件为空",responseRequestId(response));
  const url=URL.createObjectURL(blob),link=document.createElement("a");link.href=url;link.download=props.meetingId?"本场会议未完成情况.xlsx":"我发起会议未完成汇总.xlsx";link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
 }catch(failure){error.value=(failure as Error).message;}finally{busy.value=false;}
}
</script>
<template>
 <div class="incomplete-export">
  <button type="button" class="secondary" :disabled="busy" :title="meetingId ? '按当前状态导出本场未完成事项' : '导出所有由本人发起的会议，不受当前列表筛选和分页限制'" @click="download">{{busy?'正在导出…':meetingId?'导出本场未完成情况':'导出我发起会议的未完成汇总'}}</button>
  <p v-if="!meetingId" class="muted export-hint">仅本人发起的会议，不受列表筛选和分页限制</p>
  <p v-if="error" class="form-error" role="alert">{{error}}</p>
 </div>
</template>
<style scoped>
.incomplete-export{min-width:0}.export-hint{font-size:12px;margin:6px 0 0}.form-error{white-space:pre-wrap;max-width:650px}
</style>
