<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { api, query } from "../lib/api";
import { useAction } from "../lib/ui";
import type { Page } from "../lib/model";
import DataTable from "../components/DataTable.vue";
/** 精确模块和动作筛选；不接受任意SQL条件。 */
const filter = reactive({ module: "", action: "", page: 1, page_size: 20 });
/** 可见审计记录，普通日志读取人仍受所属部门限制。 */
const result = ref<Page>({ items: [], total: 0 });
const { busy, run } = useAction();
/** 每次查询重新经过服务端授权。 */
async function load() {
  result.value = await api<Page>("/logs" + query(filter));
}
onMounted(() => run(load));
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>操作日志</h1>
      <p>查阅授权范围内的业务操作与管理留痕</p>
    </div>
  </div>
  <section class="panel">
    <form
      class="toolbar"
      @submit.prevent="
        filter.page = 1;
        run(load);
      "
    >
      <el-input
        v-model="filter.module"
        placeholder="模块，如 archive、file"
        aria-label="日志模块"
      /><el-input
        v-model="filter.action"
        placeholder="动作，如 create、download"
        aria-label="日志动作"
      /><el-button native-type="submit" type="primary" :loading="busy"
        >查询</el-button
      >
    </form>
    <DataTable
      :rows="result.items"
      :loading="busy"
      :columns="[
        { key: 'created_at', label: '时间', width: 180 },
        { key: 'user_id', label: '用户编号' },
        { key: 'module', label: '模块' },
        { key: 'action', label: '动作' },
        { key: 'target_id', label: '对象编号' },
        { key: 'detail', label: '记录详情', width: 350 },
      ]"
    /><el-pagination
      :disabled="busy"
      class="pagination"
      v-model:current-page="filter.page"
      :page-size="20"
      :total="result.total"
      layout="total, prev, pager, next"
      @current-change="run(load)"
    />
  </section>
</template>
