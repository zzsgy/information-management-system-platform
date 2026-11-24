<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, can } from "../lib/api";
import { useAction } from "../lib/ui";
import { display, type Row } from "../lib/model";
import DataTable from "../components/DataTable.vue";
/** 权限范围内的聚合数据，未加载时不伪造业务数值。 */
const stats = ref<Row | null>(null);
/** 页面加载锁和失败提示。 */
const { busy, run } = useAction();
/** 从服务端读取真实统计；刷新失败保留上次结果并提示。 */
async function load() {
  await run(async () => {
    stats.value = await api("/archives/stats");
  });
}
/** 读取指定状态的数量，不把未知字段当作统计项。 */
function count(status: string) {
  return (
    stats.value?.by_status?.find((row: Row) => row.status === status)?.count ??
    0
  );
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>工作概览</h1>
      <p>查看权限范围内的档案与到期提醒</p>
    </div>
    <el-button :loading="busy" @click="load">刷新数据</el-button>
  </div>
  <div v-if="stats" class="stats">
    <div class="stat highlight">
      <span>档案总数</span><strong>{{ stats.total }}</strong
      ><small>当前可见范围</small>
    </div>
    <div class="stat">
      <span>已归档</span><strong>{{ count("archive") }}</strong
      ><small>纳入正式管理</small>
    </div>
    <div class="stat">
      <span>借出中</span><strong>{{ count("borrowed") }}</strong
      ><small>跟进归还与使用</small>
    </div>
    <div class="stat">
      <span>30天内到期</span><strong>{{ stats.expiring_count }}</strong
      ><small>到期后仍需鉴定审批</small>
    </div>
  </div>
  <div class="panel">
    <h3>常用入口</h3>
    <div class="quick-links">
      <router-link to="/archives">查看档案台账 →</router-link
      ><router-link v-if="can('borrow.apply')" to="/borrow"
        >提交借阅申请 →</router-link
      ><router-link v-if="can('approval.todo.view')" to="/approval"
        >处理我的待办 →</router-link
      >
    </div>
  </div>
  <div class="grid-two">
    <section class="panel">
      <h3>近期到期档案</h3>
      <p class="empty-note">
        到期不等于自动删除，处置须按审批流程办理。最多展示100条。
      </p>
      <DataTable
        :rows="stats?.expiring_soon ?? []"
        :loading="busy"
        :columns="[
          { key: 'archive_no', label: '档案编号' },
          { key: 'archive_name', label: '档案名称' },
          { key: 'expiry_date', label: '到期日期' },
        ]"
      />
    </section>
    <section class="panel">
      <h3>档案分类分布</h3>
      <div v-for="row in stats?.by_category" :key="row.name" class="state-line">
        <span>{{ display(row.name) }}</span
        ><b>{{ row.count }} 份</b>
      </div>
      <el-empty
        v-if="!stats?.by_category?.length"
        description="暂无档案分类数据"
        :image-size="65"
      />
    </section>
  </div>
</template>
