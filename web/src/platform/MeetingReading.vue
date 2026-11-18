<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { api, ApiError } from "./api";
import { materialLabels, type MeetingAttendee } from "./meeting";
import RichTextView from "./RichTextView.vue";
import MeetingMaterialSummary from "./MeetingMaterialSummary.vue";

const props = defineProps<{
  meetingId: string;
  revision: string;
  otherAttendees: MeetingAttendee[];
}>();
const emit = defineEmits<{ saved: []; stale: [] }>();
const open = ref(false);
const dialog = ref<HTMLDialogElement>();
const busy = ref(false);
const error = ref("");
/** 弹窗只展示需确认的他人材料，汇报正文保留网页已支持的格式。 */
const reports = computed(() =>
  props.otherAttendees.filter(
    (person) =>
      person.report ||
      person.topics ||
      person.materials?.some(
        ({ kind }) => kind === "REPORT" || kind === "WEEKLY_PLAN",
      ),
  ),
);
const agenda = computed(() =>
  props.otherAttendees.flatMap((person) =>
    (person.materials ?? []).filter(
      ({ kind }) => kind === "TOPICS" || kind === "COORDINATION",
    ),
  ),
);
watch(dialog, (value) => value?.showModal(), { flush: "post" });

/** 员工确认时发送一次请求；版本过期需刷新后重新查看。 */
async function confirm() {
  busy.value = true;
  error.value = "";
  try {
    await api("/meetings/" + props.meetingId + "/reading", {
      method: "POST",
      body: JSON.stringify({ revision: props.revision }),
    });
    open.value = false;
    emit("saved");
  } catch (e) {
    error.value = (e as Error).message;
    if (e instanceof ApiError && e.code === "VERSION_OR_STATE_CONFLICT")
      emit("stale");
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <button
    class="secondary"
    type="button"
    @click="
      error = '';
      open = true;
    "
  >
    阅读会前资料
  </button>
  <dialog
    v-if="open"
    ref="dialog"
    class="flow-dialog"
    aria-label="阅读会前资料"
    @cancel.prevent="!busy && (open = false)"
  >
    <header class="flow-dialog-header"><h2>阅读会前资料</h2></header>
    <div class="flow-dialog-body">
      <section
        v-for="person in reports"
        :key="person.userId"
        class="flow-dialog-section"
      >
        <h3>{{ person.name }} · {{ person.employeeNo }}</h3>
        <template v-for="material in person.materials" :key="material.kind">
          <template
            v-if="material.kind === 'REPORT' || material.kind === 'WEEKLY_PLAN'"
          >
            <h4>{{ materialLabels[material.kind] }}</h4>
            <RichTextView
              :value="material.data.text"
              :html="material.data.html"
            />
          </template>
        </template>
        <RichTextView v-if="person.report" :value="person.report" />
        <RichTextView v-if="person.topics" :value="person.topics" />
      </section>
      <MeetingMaterialSummary
        v-if="agenda.length"
        :types="[...new Set(agenda.map(({ kind }) => kind))]"
        :submissions="agenda"
      />
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </div>
    <footer class="flow-dialog-footer">
      <button
        type="button"
        class="secondary"
        :disabled="busy"
        @click="open = false"
      >
        关闭
      </button>
      <button
        type="button"
        class="primary"
        :disabled="busy || !revision"
        @click="confirm"
      >
        确认已阅读
      </button>
    </footer>
  </dialog>
</template>
