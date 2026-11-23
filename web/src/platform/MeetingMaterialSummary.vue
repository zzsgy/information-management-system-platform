<script setup lang="ts">
import { computed } from "vue";
import { dateTime } from "./api";
import MeetingCoordinationResult from "./MeetingCoordinationResult.vue";
import {
  coordinationLabels,
  type CoordinationResult,
  type MaterialKind,
  type MeetingMaterial,
  type MeetingOption,
} from "./meeting";

const props = defineProps<{
  types: MaterialKind[];
  submissions: MeetingMaterial[];
  results?: CoordinationResult[];
  editing?: {
    meetingId: string;
    departments: MeetingOption[];
    trackFollowUp: boolean;
    published?: boolean;
  };
}>();
const emit = defineEmits<{ saved: [] }>();
/** 保存的协调结论按冻结后的会前材料行匹配。 */
const tables = computed(() => {
  const results = new Map(props.results?.map((row) => [row.key, row]));
  if (!props.types.includes("COORDINATION")) return [];
  const items = props.submissions
      .filter((entry) => entry.kind === "COORDINATION")
      .flatMap(({ data, attendeeId }, materialIndex) =>
        (data.rows ?? []).map((row, index) => {
          const key = (attendeeId ?? materialIndex) + ":" + index;
          const result = results.get(key);
          const due = result ? result.dueAt : row.dueAt;
          const displayDeadline = result?.deadlineText
            ?? (result?.dueAt ? dateTime(result.dueAt) : row.deadlineText ?? (due ? dateTime(due) : ""));
          return { ...row, key, author: data.author, result, displayDeadline };
        }),
      );
  return [
      {
        heading: coordinationLabels.COMPANY,
        rows: items.filter((row) => row.category === "COMPANY"),
      },
      {
        heading: coordinationLabels.DEPARTMENT,
        rows: items.filter((row) => row.category !== "COMPANY"),
      },
  ];
});
</script>

<template>
  <section
    v-for="{ heading, rows } in tables"
    :key="heading"
    class="flow-dialog-section"
  >
    <h3>
      {{ heading }} <small>{{ rows.length }} 条</small>
    </h3>
    <p v-if="!rows.length">无事项</p>
    <div
      v-else
      class="summary-scroll"
      tabindex="0"
      role="region"
      :aria-label="heading"
    >
      <table
        :class="{ conclusions: editing || results?.length }"
      >
        <thead>
          <tr>
            <th scope="col">序号</th>
            <th scope="col">事项</th>
            <th scope="col">需协调或配合内容</th>
            <th scope="col">期望结果 / 验收标准</th>
            <th scope="col">填报部门</th>
            <th scope="col">填报人</th>
            <th scope="col">配合部门</th>
            <th scope="col">责任人</th>
            <template v-if="editing || results?.length || rows.some((row) => row.displayDeadline)">
              <th v-if="editing || results?.length" scope="col">会议结论</th>
              <th scope="col">截止时间</th>
              <th v-if="editing" scope="col">操作</th>
            </template>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in rows" :key="row.key">
            <th scope="row">{{ index + 1 }}</th>
            <td>{{ row.subject }}</td>
            <td>{{ row.support }}</td>
            <td>{{ row.expected }}</td>
            <td>{{ row.author.department }}</td>
            <td>{{ row.author.name }}</td>
            <td>{{ row.result?.department ?? row.department }}</td>
            <td>{{ row.result?.assigneeName ?? row.assigneeName }}</td>
            <template v-if="editing || results?.length || rows.some((item) => item.displayDeadline)">
              <td v-if="editing || results?.length">{{ row.result?.conclusion }}</td>
              <td>{{ row.displayDeadline }}</td>
              <td v-if="editing">
                <MeetingCoordinationResult
                  :row-key="row.key"
                  :subject="row.subject"
                  :initial="row.result"
                  :department-id="row.departmentId"
                  :department-name="row.department"
                  :source-departments="row.departments"
                  :assignee-id="row.assigneeId"
                  :assignee-name="row.assigneeName"
                  :source-assignees="row.assignees"
                  :deadline-text="row.displayDeadline"
                  :source-due-at="row.dueAt"
                  :meeting-id="editing.meetingId"
                  :departments="editing.departments"
                  :track-follow-up="editing.trackFollowUp"
                  :published="editing.published"
                  :trigger-id="editing.published ? 'meeting-coordination-' + row.key : undefined"
                  @saved="emit('saved')"
                />
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
small {
  color: var(--text-secondary);
  font-weight: 400;
}
.summary-scroll {
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
}
table {
  width: 100%;
  min-width: 630px;
  border-collapse: collapse;
  font-size: 13px;
}
table.conclusions {
  min-width: 1070px;
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
th {
  min-width: 52px;
}
td:last-child button {
  white-space: nowrap;
}
:is(th, td) + :is(th, td) {
  border-left: 1px solid var(--line);
}
tbody tr {
  border-top: 1px solid var(--line);
}
</style>
