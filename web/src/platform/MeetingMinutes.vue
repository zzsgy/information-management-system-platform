<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { api } from "./api";
import RichTextEditor from "./RichTextEditor.vue";
import MeetingMaterialSummary from "./MeetingMaterialSummary.vue";
import MeetingPeoplePicker from "./MeetingPeoplePicker.vue";
import {
  meetingDateInput,
  meetingInstant,
  type MeetingDetail,
  type MeetingOption,
} from "./meeting";

const props = defineProps<{
  meeting: MeetingDetail;
  departments: MeetingOption[];
}>();
const emit = defineEmits<{ saved: [published: boolean] }>();
const draft = props.meeting.minutes;
const minutes = reactive({
  materialsText: draft?.materialsText ?? "",
  notes: draft?.notes ?? draft?.summary ?? "",
  decisions: draft?.decisions ?? "",
  noteReaderIds: draft?.noteReaderIds ?? [],
  shareReports: draft?.sharedSummaries?.REPORT ?? false,
  shareTopics: draft?.sharedSummaries?.TOPICS ?? false,
  shareCoordination: draft?.sharedSummaries?.COORDINATION ?? false,
});
const readerSearch = ref("");
const readerOptions = computed(() => {
  const query = readerSearch.value.trim().toLowerCase();
  return props.meeting.attendees.filter((person) =>
    (person.name + " " + person.employeeNo).toLowerCase().includes(query),
  );
});
let sequence = 0;
const items = ref(
  (draft?.items ?? []).map((item) => ({
    ...item,
    dueAt: meetingDateInput(item.dueAt),
    key: sequence++,
    assigneeName: item.assigneeName ?? "",
  })),
);
const busy = ref(false),
  error = ref("");
const publish = ref(false);
/** 现场勾选的协调结论在纪要发布时生成待办。 */
const coordinationTasks = computed(() =>
  props.meeting.coordinationResults?.filter((result) => result.followUp).length ?? 0);

function addItem() {
  items.value.push({
    content: "",
    assigneeId: "",
    assigneeName: "",
    dueAt: "",
    key: sequence++,
  });
}
async function save(submit: boolean) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    if (submit && !minutes.decisions.trim() && !props.meeting.coordinationResults?.length)
      throw Error("请填写会议决议");
    await api("/meetings/" + props.meeting.id + "/minutes", {
      method: "PUT",
      headers: { "X-Expected-Version": String(props.meeting.rowVersion) },
      body: JSON.stringify({
        ...minutes,
        publish: submit,
        items: items.value.map((item) => ({
          content: item.content,
          assigneeId: item.assigneeId,
          dueAt: meetingInstant(item.dueAt),
        })),
      }),
    });
    emit("saved", submit);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <form class="meeting-section minutes-panel" @submit.prevent="save(publish)">
    <h2>会议纪要底稿</h2>
    <MeetingMaterialSummary
      v-if="draft?.materialTables && meeting.materialTypes"
      :types="meeting.materialTypes"
      :submissions="draft.materialTables"
      :results="meeting.coordinationResults"
    />
    <RichTextEditor v-model="minutes.materialsText" label="会前材料汇总" :rows="7" :limit="4100000" />
    <RichTextEditor v-model="minutes.decisions" label="会议决议" :rows="3" :limit="12000"
      :required="publish && !meeting.coordinationResults?.length" />
    <fieldset v-if="meeting.shareAll == null" class="meeting-fieldset">
      <legend>发布后的共享范围</legend>
      <details v-if="minutes.notes" class="minutes-readers">
        <summary>
          会议记录共享对象 · 已选 {{ minutes.noteReaderIds.length }} 人
        </summary>
        <p>勾选可查看会议记录的受邀员工。</p>
        <label
          >查找受邀员工<input
            v-model="readerSearch"
            type="search"
            placeholder="姓名或工号"
        /></label>
        <div class="minutes-reader-list">
          <label
            v-for="person in readerOptions"
            :key="person.userId"
            class="minutes-check"
          >
            <input
              v-model="minutes.noteReaderIds"
              type="checkbox"
              :value="person.userId"
            />
            {{ person.name }} · {{ person.employeeNo }}
          </label>
          <p v-if="!readerOptions.length">没有匹配的受邀员工。</p>
        </div>
      </details>
      <label class="minutes-check">
        <input
          v-model="minutes.shareReports"
          type="checkbox"
        />向所有受邀员工共享工作汇报
      </label>
      <label
        v-if="meeting.materialTypes?.includes('TOPICS')"
        class="minutes-check"
      >
        <input
          v-model="minutes.shareTopics"
          type="checkbox"
        />向所有受邀员工共享会议议题汇总
      </label>
      <label
        v-if="meeting.materialTypes?.includes('COORDINATION')"
        class="minutes-check"
      >
        <input
          v-model="minutes.shareCoordination"
          type="checkbox"
        />向所有受邀员工共享部门协调事宜汇总
      </label>
    </fieldset>
    <template v-if="meeting.trackFollowUp">
      <div class="meeting-actions meeting-spread">
        <h3>会后待办</h3>
        <button type="button" class="secondary" @click="addItem">
          添加事项
        </button>
      </div>
      <p v-if="coordinationTasks">已有 {{ coordinationTasks }} 条事项结论将在发布时生成会后待办。</p>
      <p v-else-if="!items.length">
        点击「添加事项」填写内容、责任人和期限。纪要发布后，责任人将收到反馈待办。
      </p>
      <fieldset
        v-for="(item, index) in items"
        :key="item.key"
        class="meeting-fieldset"
      >
        <legend>事项 {{ index + 1 }}</legend>
        <RichTextEditor v-model="item.content" label="事项内容" :limit="4000" :rows="2" required />
        <div class="meeting-form-row">
          <div>
            <p>
              责任人
              {{
                item.assigneeName ||
                meeting.attendees.find((a) => a.userId === item.assigneeId)
                  ?.name ||
                (item.assigneeId ? "已选定" : "未选择")
              }}
            </p>
            <MeetingPeoplePicker
              :departments="departments"
              label="选择责任人"
              @select="
                (person) => {
                  item.assigneeId = person.id;
                  item.assigneeName = person.name;
                }
              "
            />
          </div>
          <label
            >完成期限（北京时间）<input
              v-model="item.dueAt"
              type="datetime-local"
              step="1"
              required
          /></label>
        </div>
        <button type="button" class="secondary" @click="items.splice(index, 1)">
          移除事项
        </button>
      </fieldset>
    </template>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div class="meeting-actions">
      <button
        type="submit"
        class="secondary"
        :disabled="busy"
        @click="publish = false"
      >
        保存草稿
      </button>
      <button
        v-if="!publish"
        type="button"
        class="primary"
        :disabled="busy"
        @click="publish = true"
      >
        发布纪要
      </button>
      <template v-else>
        <span>{{
          meeting.trackFollowUp
            ? items.length + coordinationTasks
              ? "本次将分派 " +
                (items.length + coordinationTasks) +
                " 项会后待办，发布后纪要和待办将锁定。"
              : "本次未添加会后待办，发布后不会向成员发送反馈待办。"
            : "发布纪要后会议自动办结。"
        }}</span>
        <button type="button" class="secondary" @click="publish = false">
          返回编辑
        </button>
        <button type="submit" class="primary" :disabled="busy">确认发布</button>
      </template>
    </div>
  </form>
</template>

<style scoped>
.minutes-panel {
  padding: 16px;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 10px;
}
.minutes-panel h2 {
  font-size: 16px;
}
.minutes-panel .meeting-fieldset {
  background: var(--surface);
}
.minutes-readers {
  margin-bottom: 12px;
}
.minutes-readers summary {
  cursor: pointer;
  font-weight: 600;
}
.minutes-reader-list {
  max-height: 180px;
  overflow: auto;
  margin-top: 8px;
}
.minutes-panel .minutes-check {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  margin: 8px 0;
}
.minutes-check input[type="checkbox"] {
  width: 16px;
  height: 16px;
  min-height: 0;
}
</style>
