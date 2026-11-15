<script setup lang="ts">
import { computed } from "vue";
import {
  materialLabels,
  type MaterialKind,
  type MeetingMaterial,
} from "./meeting";

const props = defineProps<{
  types: MaterialKind[];
  submissions: MeetingMaterial[];
}>();

/** 按材料类型汇总，使用提交时保存的填写人和部门。 */
const tables = computed(() =>
  (["TOPICS", "COORDINATION"] as const)
    .filter((kind) => props.types.includes(kind))
    .map((kind) => ({
      kind,
      rows: props.submissions
        .filter((entry) => entry.kind === kind)
        .flatMap(({ data }) =>
          (data.rows ?? []).map((row) => ({ ...row, author: data.author })),
        ),
    })),
);
</script>

<template>
  <section
    v-for="{ kind, rows } in tables"
    :key="kind"
    class="flow-dialog-section"
  >
    <h3>
      {{ materialLabels[kind] }}汇总
      <span class="summary-count">{{ rows.length }} 条</span>
    </h3>
    <p v-if="!rows.length" class="summary-count">暂无提交</p>
    <div
      v-else
      class="summary-scroll"
      tabindex="0"
      role="region"
      :aria-label="materialLabels[kind] + '汇总区域'"
    >
      <table
        :class="{ coordination: kind === 'COORDINATION' }"
        :aria-label="materialLabels[kind] + '汇总'"
      >
        <thead>
          <tr>
            <th scope="col">序号</th>
            <th scope="col">{{ kind === "TOPICS" ? "议题" : "具体事宜" }}</th>
            <th scope="col">
              {{
                kind === "TOPICS"
                  ? "需公司协调什么 / 期望结果"
                  : "需对方做什么 / 期望结果"
              }}
            </th>
            <th scope="col">发起部门</th>
            <th scope="col">发起人</th>
            <th v-if="kind === 'COORDINATION'" scope="col">配合部门</th>
            <th v-if="kind === 'COORDINATION'" scope="col">配合人</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in rows" :key="index">
            <th scope="row">{{ index + 1 }}</th>
            <td>{{ row.subject }}</td>
            <td>{{ row.expected }}</td>
            <td>{{ row.author.department }}</td>
            <td>{{ row.author.name }}</td>
            <td v-if="kind === 'COORDINATION'">{{ row.department }}</td>
            <td v-if="kind === 'COORDINATION'">{{ row.person }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.summary-count {
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 400;
}
.summary-scroll {
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
}
table {
  width: 100%;
  min-width: 620px;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 13px;
}
table.coordination {
  min-width: 870px;
}
thead,
tbody th {
  background: var(--surface-muted);
}
th,
td {
  padding: 9px 8px;
  text-align: left;
  vertical-align: top;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
:is(th, td) + :is(th, td) {
  border-left: 1px solid var(--line);
}
tbody tr {
  border-top: 1px solid var(--line);
}
thead th:nth-child(1) {
  width: 48px;
}
thead th:is(:nth-child(4), :nth-child(5), :nth-child(6), :nth-child(7)) {
  width: 95px;
}
thead th:nth-child(3) {
  width: 32%;
}
</style>
