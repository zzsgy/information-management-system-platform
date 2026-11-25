<script setup lang="ts">
import { inject, onMounted, ref } from "vue";
import { api, mutate } from "./api";
interface Field { key: string; label: string; type: string; fallback: string }
interface Group { id: string; section: string; rowVersion: number; fields: Field[]; values: Record<string, string> }
const groups = ref<Group[]>([]), selected = ref("SCANNER"), busy = ref(false), error = ref(""), notice = ref(""), reason = ref("");
const titles: Record<string,string> = {SCANNER: "文件检测", STORAGE: "原件存储", READING: "阅读与导出", OFFICE: "Office连接", PROTECTION: "存储保护", MAINTENANCE: "备份与维护"};
const descriptions: Record<string,string> = {
 SCANNER: "安装ClamAV并准备有效的官方病毒库后，在这里填写部署电脑上的路径。保存时固定检测程序摘要，检查时实际扫描无害文件。公盘上传病毒检测默认启用，停用期间仍校验文件完整性和类型。",
 STORAGE: "两个原件目录必须分开。已有文件时需先停机迁移并核验；这里不会移动或删除原文件。",
 READING: "配置Java、PDF处理程序、阅读回执和导出目录。目录不能与原件、账本、邮箱或其他临时目录互相包含。",
 OFFICE: "Office为可选服务。需要先部署文档服务器；这里维护连接地址和部署机上的密钥文件位置，不上传密钥内容。",
 PROTECTION: "单台电脑请选择单机保护：每次文件写入仍核验双副本，并要求最长间隔内存在已验证完整备份。外部主备模式需要真实的独立保护代理，不会自动生成假证据。",
 MAINTENANCE: "备份同时覆盖数据库与持久文件。独立执行器在维护窗口停止应用，完成备份或隔离恢复后恢复应用服务。首次需在部署电脑安装计划任务；本页面显示真实心跳。备份目录请选择独立磁盘，不能只是D盘的另一个文件夹。",
};
const password = inject<(title: string) => Promise<string | null>>("askPassword")!;
const path = "/admin/operations/service-configurations/deployment";
async function load() {
 try { groups.value = (await api<{items: Group[]}>(path)).items; } catch(e) { error.value = (e as Error).message; }
}
async function save(group: Group) {
 busy.value = true; error.value = ""; notice.value = "";
 try {
  const secret = await password(`确认修改${titles[group.section]}部署配置`); if(secret === null) return;
  await mutate(`${path}/${group.id}`, "PUT", {values: group.values, reason: reason.value}, group.id, "DEPLOYMENT_SETTINGS", "CONFIGURE_DEPLOYMENT", secret, group.rowVersion);
  await load(); reason.value = ""; notice.value = "配置已保存并生效。请点击检查运行环境，确认实际程序、存储及执行器可用。";
  window.dispatchEvent(new Event("ims-service-config-changed"));
 } catch(e) {error.value = (e as Error).message;} finally {busy.value = false;}
}
onMounted(load);
</script>
<template>
<section class="panel management-settings-panel deployment-settings" aria-label="部署运行配置">
 <header><div><h2>部署运行配置</h2><p class="muted">仅平台IT管理员可维护；服务授权和运行环境须分别完成。</p></div><button class="secondary" :disabled="busy" @click="load">重新读取配置</button></header>
 <nav class="module-tabs" aria-label="部署配置分类"><button v-for="(title,key) in titles" :key="key" :class="{ active: selected === key }" :aria-pressed="selected === key" @click="selected = key; error = ''; notice = ''">{{ title }}</button></nav>
 <template v-for="group in groups" :key="group.id"><form v-if="group.section === selected" @submit.prevent="save(group)">
  <p class="description">{{ descriptions[group.section] }}</p>
  <div class="configuration-grid"><label v-for="field in group.fields" :key="field.key">{{ field.label }}
   <select v-if="field.type === 'boolean'" v-model="group.values[field.key]"><option value="false">停用</option><option value="true">启用</option></select>
   <select v-else-if="field.type === 'mode'" v-model="group.values[field.key]"><option value="LEGACY">沿用现有部署保护配置</option><option value="SINGLE">单机双副本与独立完整备份</option><option value="EXTERNAL">外部主备保护代理</option></select>
   <input v-else v-model="group.values[field.key]" :type="field.type === 'number' ? 'number' : 'text'" :placeholder="field.fallback || '填写部署电脑的实际配置'" maxlength="2048" />
  </label></div>
  <label class="configuration-reason">修改原因<textarea v-model="reason" required maxlength="1000" rows="2" /></label>
  <footer><button class="primary" :disabled="busy">保存{{ titles[group.section] }}配置</button><span class="muted">保存不会安装软件，也不会伪造检查结果。</span></footer>
  <p v-if="group.section === 'MAINTENANCE'" class="muted"><a href="/api/v1/admin/operations/service-configurations/deployment-agent.zip" download>下载独立执行器安装包与说明</a>。执行器安装入口：项目 scripts/platform/Install-OperationsAgent.ps1；完整步骤见 docs/service-configuration.md。首次安装需要部署电脑的Windows服务管理权限。</p>
 </form></template>
 <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
</section>
</template>
<style scoped>
header,footer{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap}h2{margin:0}.description{line-height:1.8;max-width:1000px}.configuration-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;margin:24px 0}.configuration-grid label,.configuration-reason{display:grid;gap:8px;min-width:0}.configuration-reason{margin:20px 0}input,select{width:100%;min-width:0}footer{justify-content:flex-start}@media(max-width:800px){.configuration-grid{grid-template-columns:1fr}}
</style>
