<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, ApiError } from "../lib/api";
import { confirmAction, useAction } from "../lib/ui";
import type { Row } from "../lib/model";
import DataTable from "../components/DataTable.vue";
/** 已完成的整体备份列表和附件校验报告。 */
const backups = ref<Row[]>([]);
const report = ref<Row | null>(null);
const restoreMessage = ref("");
const { busy, run } = useAction();
/** 临时或未完成备份不在服务端列表内。 */
async function load() {
  backups.value = await api<Row[]>("/security/db-backups");
}
onMounted(() => run(load));
/** 备份同时包含数据库与附件，成功消息以服务器校验结果为准。 */
async function backup() {
  await run(async () => {
    await confirmAction("备份期间会短暂暂停新业务请求，请确认当前适合维护。");
    await api("/security/db-backup", "POST");
    await load();
  }, "数据库与附件整体备份已完成并校验");
}
/** 校验所有附件历史版本，不修改异常文件。 */
async function integrity() {
  await run(async () => {
    report.value = await api("/security/integrity/check");
  });
}
/** 删除确认的单个备份文件，不能借此删除业务数据。 */
async function remove(row: Row) {
  await run(async () => {
    await confirmAction(`确认删除备份“${row.name}”？删除后无法在此恢复。`);
    await api(
      `/security/db-backups/${encodeURIComponent(row.name)}?confirm=true`,
      "DELETE",
    );
    await load();
  }, "备份已删除");
}
/** 409离线恢复提示只表示包校验通过，不把它显示为已恢复成功。 */
async function inspect(row: Row) {
  await run(async () => {
    restoreMessage.value = "";
    try {
      await api("/security/db-restore", "POST", {
        file: row.name,
        confirm: true,
      });
      throw new Error("服务端返回了不符合离线恢复约定的结果，请联系管理员。");
    } catch (error) {
      if (
        error instanceof ApiError &&
        error.status === 409 &&
        error.message.startsWith("offline_required")
      )
        restoreMessage.value =
          "备份包校验通过。实际恢复须由管理员在停服后恢复到新空数据库和新附件目录，验收后切换服务配置。当前业务数据没有被恢复或覆盖。";
      else throw error;
    }
  });
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>备份与校验</h1>
      <p>备份数据库及全部附件版本，恢复采用隔离验收流程</p>
    </div>
  </div>
  <section class="panel">
    <div class="toolbar">
      <el-button type="primary" :loading="busy" @click="backup"
        >创建整体备份</el-button
      ><el-button :disabled="busy" @click="integrity">校验附件完整性</el-button
      ><el-button :disabled="busy" @click="run(load)">刷新列表</el-button>
    </div>
    <el-alert
      v-if="busy"
      title="正在执行操作，请勿重复提交。维护期间其他业务请求可能暂时被拒绝。"
      type="info"
      :closable="false"
    /><el-alert
      v-if="restoreMessage"
      :title="restoreMessage"
      type="warning"
      :closable="false"
    /><DataTable
      :rows="backups"
      :loading="busy"
      :columns="[
        { key: 'name', label: '备份文件', width: 350 },
        { key: 'size', label: '大小（字节）' },
        { key: 'mtime', label: '创建时间', width: 180 },
      ]"
      ><template #default="{ row }"
        ><el-button type="primary" link :disabled="busy" @click="inspect(row)"
          >恢复前校验</el-button
        ><el-button type="danger" link :disabled="busy" @click="remove(row)"
          >删除备份</el-button
        ></template
      ></DataTable
    >
  </section>
  <section v-if="report" class="panel">
    <h3>附件校验结果</h3>
    <p>
      共 {{ report.total }} 个版本，通过 {{ report.ok }} 个，异常
      {{ report.failed }} 个。
    </p>
    <DataTable
      :rows="report.items ?? []"
      :columns="[
        { key: 'file_id', label: '附件编号' },
        { key: 'file_name', label: '附件名称', width: 250 },
        { key: 'reason', label: '异常说明', width: 350 },
      ]"
    />
  </section>
</template>
