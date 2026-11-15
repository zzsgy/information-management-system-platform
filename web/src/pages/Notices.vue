<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, can } from "../lib/api";
import { confirmAction, useAction } from "../lib/ui";
import { options, type Row, type Page, type Field } from "../lib/model";
import DataTable from "../components/DataTable.vue";
import FormDialog from "../components/FormDialog.vue";
/** 公告分页与阅读详情。 */
const result = ref<Page>({ items: [], total: 0 });
const page = ref(1);
const detail = ref<Row | null>(null);
const detailOpen = ref(false);
/** 发布弹窗和可选择的接收人。 */
const open = ref(false);
const users = ref<Row[]>([]);
const { busy, run } = useAction();
/** 收件人为空意味着全员发布，在提交前再次明确展示。 */
const fields = computed<Field[]>(() => [
  { key: "title", label: "公告标题", required: true },
  { key: "notice_type", label: "公告类型", initial: "system", required: true },
  {
    key: "content",
    label: "公告正文",
    type: "textarea",
    required: true,
    length: 100000,
  },
  {
    key: "receiver_user_ids",
    label: "指定接收人",
    type: "multi",
    options: options(users.value, "real_name"),
    help: "留空表示向全体用户发布。",
  },
  {
    key: "expire_time",
    label: "截止日期",
    type: "date",
    help: "留空表示长期有效；选择日期的当天23:59北京时间截止。",
  },
]);
/** 服务端根据接收人和有效期返回可见公告。 */
async function load() {
  result.value = await api<Page>(`/notices?page=${page.value}&page_size=20`);
}
onMounted(() => run(load));
/** 打开详情时记录当前用户阅读回执。 */
async function read(row: Row) {
  await run(async () => {
    detail.value = await api(`/notices/${row.id}`);
    await api(`/notices/${row.id}/read`, "POST");
    detailOpen.value = true;
    await load();
  });
}
/** 接收人查询仍遵守用户目录权限；不额外授予普通发布人账号管理能力。 */
async function publish() {
  await run(async () => {
    users.value = [];
    if (can("user.read")) {
      let index = 1;
      let total = 0;
      do {
        const batch = await api<Page>(`/users?page=${index++}&page_size=200`);
        users.value.push(
          ...batch.items.filter((row) => row.account_status === "active"),
        );
        total = batch.total;
        if (index * 200 > total + 200) break;
      } while ((index - 1) * 200 < total && index <= 50);
    }
    open.value = true;
  });
}
/** 公告发布必须明确接收范围；成功后再清空输入。 */
async function save(value: Row) {
  await run(async () => {
    await confirmAction(
      value.receiver_user_ids?.length
        ? `向所选 ${value.receiver_user_ids.length} 位用户发布公告？`
        : "未指定接收人，确认向全体用户发布此公告？",
    );
    await api("/notices", "POST", {
      ...value,
      expire_time: value.expire_time
        ? `${value.expire_time}T23:59:59+08:00`
        : null,
    });
    open.value = false;
    await load();
  }, "公告已发布");
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>公告通知</h1>
      <p>查看当前有效且对本人可见的公告</p>
    </div>
    <el-button
      v-if="can('notice.publish')"
      type="primary"
      :loading="busy"
      @click="publish"
      >发布公告</el-button
    >
  </div>
  <section class="panel">
    <DataTable
      :rows="result.items"
      :loading="busy"
      :columns="[
        { key: 'title', label: '公告标题', width: 330 },
        { key: 'notice_type', label: '类型' },
        { key: 'created_at', label: '发布时间', width: 180 },
        { key: 'read_at', label: '阅读时间', width: 180 },
      ]"
      ><template #default="{ row }"
        ><el-button link type="primary" :disabled="busy" @click="read(row)"
          >阅读</el-button
        ></template
      ></DataTable
    ><el-pagination
      :disabled="busy"
      class="pagination"
      v-model:current-page="page"
      :total="result.total"
      :page-size="20"
      layout="total, prev, pager, next"
      @current-change="run(load)"
    />
  </section>
  <el-dialog v-model="detailOpen" :title="detail?.title" width="800px"
    ><p class="notice-content">{{ detail?.content }}</p></el-dialog
  ><FormDialog
    v-model="open"
    title="发布公告"
    :fields="fields"
    :busy="busy"
    @save="save"
  />
</template>
