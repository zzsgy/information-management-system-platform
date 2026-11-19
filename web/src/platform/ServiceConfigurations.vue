<script setup lang="ts">
import { inject, onMounted, ref } from "vue";
import { api, dateTime, mutate } from "./api";

interface ServiceConfiguration {
  id: string; slot: string; label: string; name: string; configured: boolean;
  rowVersion: number; identityVersion: number; departmentIds: string[];
  validUntil: string | null; status: "ACTIVE" | "DISABLED"; source: string;
}
interface Check { name: string; ready: boolean; message: string; errorCode?: string }
const items = ref<ServiceConfiguration[]>([]), departments = ref<{id: string; name: string}[]>([]);
const busy = ref(false), error = ref(""), notice = ref("");
const checks = ref<Check[]>([]), checkRequest = ref("");
const selected = ref<ServiceConfiguration | null>(null), dialog = ref<HTMLDialogElement>();
const form = ref({name: "", departmentIds: [] as string[], validUntil: "", status: "ACTIVE" as "ACTIVE" | "DISABLED", reason: ""});
const askPassword = inject<(title: string) => Promise<string | null>>("askPassword")!;
const path = "/admin/operations/service-configurations";
async function load() {
  error.value = "";
  try {
    const result = await api<{items: ServiceConfiguration[]; departments: {id: string; name: string}[]}>(path);
    items.value = result.items; departments.value = result.departments;
  } catch (failure) { error.value = (failure as Error).message; }
}
function edit(item: ServiceConfiguration) {
  selected.value = item;
  const date = item.validUntil ? new Date(item.validUntil) : new Date(Date.now() + 365 * 86400000);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0,16);
  form.value = {name: item.name, departmentIds: [...item.departmentIds], validUntil: local, status: item.status, reason: ""};
  dialog.value?.showModal();
}
async function save() {
  const item = selected.value;
  if (!item) return;
  busy.value = true; error.value = "";
  try {
    const password = await askPassword(`确认${item.configured ? "修改" : "创建并绑定"}${item.label}授权`);
    if (password === null) return;
    await mutate(`${path}/${item.id}`, "PUT", {
      ...form.value, validUntil: new Date(form.value.validUntil).toISOString(), identityVersion: item.identityVersion,
    }, item.id, "SERVICE_BINDING", "CONFIGURE_SERVICE", password, item.rowVersion);
    dialog.value?.close(); selected.value = null;
    notice.value = "服务授权已保存并生效，运行环境检查结果见下方。";
    await load(); await check();
  } catch (failure) { error.value = (failure as Error).message; }
  finally { busy.value = false; }
}
async function check() {
  busy.value = true; error.value = "";
  try {
    const result = await api<{checks: Check[]; requestId: string}>(path + "/check", {method: "POST"});
    checks.value = result.checks; checkRequest.value = result.requestId;
    window.dispatchEvent(new Event("ims-service-config-changed"));
  } catch (failure) { error.value = (failure as Error).message; }
  finally { busy.value = false; }
}
onMounted(load);
</script>
<template>
  <section class="panel service-configuration">
    <header><div><h2>文件处理服务配置</h2><p class="muted">维护后台服务的部门范围、有效期和启停状态，保存后即时生效。</p></div>
      <div class="actions"><button class="secondary" :disabled="busy" @click="load">刷新配置</button><button class="primary" :disabled="busy" @click="check">检查运行环境</button></div>
    </header>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="error && !selected" class="form-error" role="alert">{{ error }}</p>
    <div class="services-grid">
      <article v-for="item in items" :key="item.id">
        <div class="service-heading"><h3>{{ item.label }}</h3><span class="chip">{{ !item.configured ? '尚未配置' : item.status === 'DISABLED' ? '已停用' : item.validUntil && new Date(item.validUntil).getTime() <= Date.now() ? '已到期' : '已配置' }}</span></div>
        <p>{{ item.configured ? item.name : '尚未绑定可用的服务身份' }}</p>
        <p class="muted">获准部门：{{ item.departmentIds.length }} 个 · 截止：{{ item.validUntil ? dateTime(item.validUntil) : '未设置' }}</p>
        <p v-if="item.slot === 'OFFICE'" class="muted">此处维护服务授权；Office 连接及密钥由部署配置提供。</p>
        <button class="secondary" :disabled="busy" @click="edit(item)">{{ item.configured ? '编辑授权 / 续期 / 停用' : '配置服务' }}</button>
      </article>
    </div>
    <ul v-if="checks.length" class="runtime-checks"><li v-for="item in checks" :key="item.name" :class="{failed: !item.ready}"><strong>{{ item.name }}：{{ item.ready ? '通过' : '未通过' }}</strong> — {{ item.message }} <span v-if="item.errorCode">（{{ item.errorCode }}）</span></li></ul>
    <p v-if="checkRequest" class="muted">检查请求编号：{{ checkRequest }}</p>
  </section>
  <dialog ref="dialog" class="flow-dialog" @close="selected = null">
    <form class="flow-dialog-shell" @submit.prevent="save">
      <header class="flow-dialog-header"><h2>{{ selected?.label }}授权配置</h2><button type="button" class="secondary" :disabled="busy" @click="dialog?.close()">关闭</button></header>
      <div class="flow-dialog-body service-form">
        <p>只授予所选部门的后台处理权限，不会赋予员工查看或下载档案的权限。</p>
        <label>服务名称<input v-model="form.name" required maxlength="128" /></label>
        <fieldset><legend>获准部门（至少选择一个）</legend><button type="button" class="text-button" @click="form.departmentIds = departments.map(d => d.id)">选择当前全部部门</button><button type="button" class="text-button" @click="form.departmentIds = []">清空选择</button>
          <div class="department-options"><label v-for="dept in departments" :key="dept.id"><input v-model="form.departmentIds" type="checkbox" :value="dept.id" />{{ dept.name }}</label></div>
        </fieldset>
        <label>授权截止时间<input v-model="form.validUntil" type="datetime-local" required /></label>
        <label>启停状态<select v-model="form.status"><option value="ACTIVE">启用</option><option value="DISABLED">停用</option></select></label>
        <label>配置原因<textarea v-model="form.reason" required maxlength="1000" rows="2" placeholder="说明本次创建、续期或调整的原因" /></label>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer"><button class="primary" :disabled="busy || !form.departmentIds.length">保存并生效</button></footer>
    </form>
  </dialog>
</template>
<style scoped>
.service-configuration { margin: 20px 0; }
header, .service-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.actions { display: flex; gap: 10px; flex-wrap: wrap; }
.services-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 20px; }
.services-grid article { border: 1px solid var(--line, #dbe6ec); border-radius: 10px; padding: 20px; min-width: 0; }
h2, h3 { margin: 0; }
.service-form { display: grid; gap: 18px; }
.service-form label { display: grid; gap: 8px; }
.service-form fieldset { border: 1px solid var(--line, #dbe6ec); border-radius: 8px; padding: 16px; }
.department-options { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; margin-top: 12px; }
.department-options label { display: flex; align-items: center; }
.department-options input { width: auto; margin: 0; }
.runtime-checks { padding-left: 22px; line-height: 1.8; }
.runtime-checks .failed { color: var(--danger, #a65546); }
@media (max-width: 800px) { .services-grid { grid-template-columns: 1fr; } }
</style>
