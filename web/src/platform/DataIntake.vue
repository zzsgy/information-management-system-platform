<script setup lang="ts">
import { computed, ref } from "vue";
import EmptyState from "./EmptyState.vue";
import Icon from "../preview/PlatformIcon.vue";

/** 接入能力尚属规划；这些内容只描述界面，不代表已创建数据源或任务。 */
const sections = [
  {
    code: "sources",
    name: "数据源管理",
    icon: "database",
    columns: ["数据源名称", "接入方式", "所属部门", "连接状态", "最近同步"],
    empty: "尚未配置数据源",
    hint: "接入后在这里查看数据源、连接状态及最近同步时间。",
    step: "确认来源系统、接入方式与数据范围",
  },
  {
    code: "mappings",
    name: "字段映射",
    icon: "grid",
    columns: ["映射名称", "来源数据源", "目标对象", "配置状态", "更新时间"],
    empty: "尚未配置字段映射",
    hint: "字段映射用于对应来源字段与系统字段，明确转换和校验规则。",
    step: "对应来源字段，明确校验与转换规则",
  },
  {
    code: "tasks",
    name: "同步任务",
    icon: "task",
    columns: ["任务名称", "来源数据源", "执行方式", "任务状态", "最近执行"],
    empty: "尚未创建同步任务",
    hint: "任务接通后在这里查看执行计划、运行状态与处理结果。",
    step: "设置同步范围、执行计划与处理方式",
  },
  {
    code: "runs",
    name: "运行记录",
    icon: "history",
    columns: ["执行时间", "任务名称", "处理数量", "执行结果", "执行耗时"],
    empty: "暂无运行记录",
    hint: "实际执行后展示处理结果、失败原因与重试记录。",
    step: "核对处理结果，跟进失败与异常记录",
  },
] as const;
/** 切换仅影响当前页面视图，不提交配置或读取正式业务数据。 */
const selected = ref<(typeof sections)[number]["code"]>("sources");
const current = computed(
  () => sections.find((item) => item.code === selected.value)!,
);
</script>

<template>
  <section class="data-intake-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">业务协同</p>
        <h1>业务数据接入 <span class="preview-badge">界面预览</span></h1>
        <p class="muted">集中管理数据来源、字段映射与同步任务。</p>
      </div>
    </header>

    <p class="preview-notice" role="note">
      <Icon name="help" :size="17" />当前为页面布局预览，尚未配置或连接数据源。
    </p>

    <nav class="module-tabs" aria-label="数据接入视图">
      <button
        v-for="item in sections"
        :key="item.code"
        :class="{ selected: selected === item.code }"
        :aria-pressed="selected === item.code"
        @click="selected = item.code"
      >
        <Icon :name="item.icon" :size="17" />{{ item.name }}
      </button>
    </nav>

    <div class="intake-layout">
      <section class="panel intake-list" :aria-label="current.name">
        <div class="list-heading">
          <h2>{{ current.name }}</h2>
          <span class="muted">尚未接入数据</span>
        </div>
        <div class="table-container">
          <table>
            <thead>
              <tr>
                <th v-for="column in current.columns" :key="column">
                  {{ column }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td :colspan="current.columns.length">
                  <EmptyState
                    :title="current.empty"
                    :hint="current.hint"
                    :icon="current.icon"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <aside class="panel intake-guide">
        <h2>接入流程</h2>
        <p class="muted">从数据来源到运行追踪</p>
        <ol>
          <li
            v-for="(item, index) in sections"
            :key="item.code"
            :class="{ active: selected === item.code }"
          >
            <span class="step-number">{{ index + 1 }}</span>
            <div>
              <h3>{{ item.name }}</h3>
              <p>{{ item.step }}</p>
            </div>
          </li>
        </ol>
        <p class="guide-note">正式接入前需确认数据范围、更新规则和责任部门。</p>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
}
h1 {
  display: flex;
  align-items: center;
  gap: 12px;
}
.preview-badge {
  padding: 4px 8px;
  color: var(--brand);
  background: var(--brand-soft);
  border-radius: 5px;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0;
}
.preview-notice {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  margin: 0 0 18px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--brand-soft);
  color: var(--ink);
  font-size: 13px;
}
.intake-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  align-items: start;
  gap: 20px;
}
.intake-list {
  min-width: 0;
  overflow: hidden;
  padding: 0;
}
.list-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 20px;
}
h2 {
  margin: 0;
  font-size: 15px;
}
.list-heading .muted,
.intake-guide > .muted {
  font-size: 12px;
}
.table-container {
  overflow-x: auto;
}
table {
  width: 100%;
  margin: 0;
}
th {
  white-space: nowrap;
}
td {
  padding: 20px 12px;
  border-bottom: 0;
}
.intake-guide {
  padding: 20px;
}
.intake-guide > .muted {
  margin: 8px 0 20px;
}
ol {
  list-style: none;
  padding: 0;
  margin: 0;
}
li {
  display: flex;
  gap: 12px;
  padding-bottom: 24px;
}
li:last-child {
  padding-bottom: 0;
}
.step-number {
  flex: 0 0 26px;
  height: 26px;
  display: grid;
  place-items: center;
  color: var(--text-secondary);
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 50%;
  font-size: 12px;
}
.active .step-number {
  color: var(--brand);
  background: var(--brand-soft);
  border-color: var(--brand);
}
h3 {
  margin: 3px 0 6px;
  font-size: 13px;
  font-weight: 500;
}
li p,
.guide-note {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.7;
}
.guide-note {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}
@media (max-width: 1360px) {
  .intake-layout {
    grid-template-columns: minmax(0, 1fr) 260px;
    gap: 16px;
  }
}
@media (max-width: 900px) {
  .intake-layout { grid-template-columns: minmax(0, 1fr); }
}
</style>
