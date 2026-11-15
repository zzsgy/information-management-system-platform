<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { api, profile } from "./api";
import Icon from "../preview/PlatformIcon.vue";
import MeetingTimeField from "./MeetingTimeField.vue";
import MeetingPeoplePicker from "./MeetingPeoplePicker.vue";
import {
  materialLabels,
  type MaterialKind,
  meetingDateInput,
  meetingInstant,
  type MeetingDetail,
  type MeetingOptions,
  type MeetingPerson,
} from "./meeting";

const props = defineProps<{
  options: MeetingOptions;
  detail?: MeetingDetail;
}>();
const emit = defineEmits<{ saved: [id: string]; cancel: [] }>();
const original = props.detail;
const originalRoom = original?.roomId;
// 下一整分钟是能实际预约的最早时刻，使用北京时间显示。
const now = ref(Date.now());
const nextMinute = (time: number) => (Math.floor(time / 60000) + 1) * 60000;
const startMinimum = computed(() =>
  meetingDateInput(new Date(nextMinute(now.value)).toISOString()),
);
const data = reactive({
  subject: original?.subject ?? "",
  description: original?.description ?? "",
  kind: original?.kind ?? "ORDINARY",
  urgent: original?.urgent ?? false,
  startsAt: original ? meetingDateInput(original.startsAt) : startMinimum.value,
  endsAt: original
    ? meetingDateInput(original.endsAt)
    : meetingDateInput(
        new Date(nextMinute(now.value) + 30 * 60000).toISOString(),
      ),
  roomId: original ? (original.roomId ?? "OTHER") : "",
  collectMaterials: original?.collectMaterials ?? false,
  trackFollowUp: original?.trackFollowUp ?? false,
  shareAll: false,
});
const attendees = ref<MeetingPerson[]>(
  original?.attendees.map((a) => ({
    id: a.userId,
    name: a.name,
    employeeNo: a.employeeNo,
  })) ?? [],
);
const materialTypes = ref<MaterialKind[]>([]);
const materialUsers = ref(
  original?.attendees.filter((a) => a.materialRequired).map((a) => a.userId) ??
    [],
);
const busy = ref(false),
  error = ref("");

const dialog = ref<HTMLDialogElement>();
let clock: number;
onMounted(() => {
  dialog.value?.showModal();
  clock = window.setInterval(() => {
    now.value = Date.now();
  }, 1000);
});
onUnmounted(() => window.clearInterval(clock));

const endMinimum = computed(() =>
  meetingDateInput(
    new Date(
      nextMinute(
        Math.max(now.value, Date.parse(meetingInstant(data.startsAt))),
      ),
    ).toISOString(),
  ),
);

function updateStart(value: string) {
  data.startsAt = value;
  if (data.endsAt < endMinimum.value) data.endsAt = endMinimum.value;
}

function dismiss() {
  if (!busy.value) emit("cancel");
}

function remove(id: string) {
  attendees.value = attendees.value.filter((a) => a.id !== id);
  materialUsers.value = materialUsers.value.filter((user) => user !== id);
}

/** 新建与变更共用人员选择和时间输入，变更携带当前会议版本。 */
async function save() {
  if (busy.value) return;
  error.value = "";
  busy.value = true;
  try {
    const schedule = {
      startsAt:
        original && data.startsAt === meetingDateInput(original.startsAt)
          ? original.startsAt
          : meetingInstant(data.startsAt),
      endsAt:
        original && data.endsAt === meetingDateInput(original.endsAt)
          ? original.endsAt
          : meetingInstant(data.endsAt),
      roomId: data.roomId === "OTHER" ? null : data.roomId,
      ...(data.roomId === "OTHER" ? { description: data.description } : {}),
      attendeeIds: attendees.value.map((a) => a.id),
      materialUserIds:
        data.kind === "ORDINARY" && data.collectMaterials
          ? materialUsers.value
          : [],
    };
    if (
      schedule.startsAt !== original?.startsAt &&
      Date.parse(schedule.startsAt) <= Date.now()
    )
      throw Error("开始时间已过，请重新选择");
    if (Date.parse(schedule.endsAt) <= Date.now())
      throw Error("预约时段已过，请重新安排会议时间");
    if (Date.parse(schedule.endsAt) <= Date.parse(schedule.startsAt))
      throw Error("结束时间须晚于开始时间");
    const result = await api<{ id: string }>(
      original ? "/meetings/" + original.id : "/meetings",
      {
        method: original ? "PUT" : "POST",
        headers: original
          ? { "X-Expected-Version": String(original.rowVersion) }
          : undefined,
        body: JSON.stringify(
          original
            ? schedule
            : {
                ...schedule,
                subject: data.subject,
                description: data.description,
                kind: data.kind,
                urgent: data.urgent,
                materialTypes: materialTypes.value,
                trackFollowUp: data.trackFollowUp,
                shareAll: data.shareAll,
              },
        ),
      },
    );
    emit("saved", result.id);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <dialog
    ref="dialog"
    class="form-dialog flow-dialog meeting-editor"
    aria-labelledby="meeting-editor-title"
    @cancel.prevent="dismiss"
  >
    <form class="flow-dialog-shell" @submit.prevent="save">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon" aria-hidden="true"
          ><Icon name="calendar" :size="22"
        /></span>
        <h2 id="meeting-editor-title">
          {{ original ? "修改会议安排" : "发起会议" }}
        </h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭会议弹窗"
          :disabled="busy"
          @click="dismiss"
        >
          ×
        </button>
      </header>

      <div class="flow-dialog-body">
        <section class="flow-dialog-section">
          <h3>会议安排</h3>
          <div class="flow-dialog-fields booking-fields">
            <label v-if="!original" class="booking-wide">
              会议主题<input
                v-model="data.subject"
                required
                maxlength="200"
                autofocus
              />
            </label>
            <div class="booking-wide booking-time-zone">
              会议时间（北京时间）
            </div>
            <MeetingTimeField
              label="开始时间"
              :model-value="data.startsAt"
              :minimum="startMinimum"
              @update:model-value="updateStart"
            />
            <MeetingTimeField
              label="结束时间"
              v-model="data.endsAt"
              :minimum="endMinimum"
            />
            <label :class="{ 'booking-wide': original || !options.canWeekly }"
              >会议室
              <select v-model="data.roomId" required>
                <option value="" disabled>请选择会议室</option>
                <option
                  v-if="
                    original?.roomId &&
                    !options.rooms.some((r) => r.id === originalRoom)
                  "
                  :value="original.roomId"
                >
                  {{ original.roomName }}（原预约）
                </option>
                <option
                  v-for="room in options.rooms"
                  :key="room.id"
                  :value="room.id"
                >
                  {{ room.name }}
                </option>
                <option value="OTHER">其他地点</option>
              </select>
            </label>
            <label v-if="!original && options.canWeekly"
              >会议类型
              <select v-model="data.kind">
                <option value="ORDINARY">普通会议</option>
                <option value="WEEKLY">周会</option>
              </select>
            </label>
            <label
              v-if="!original || data.roomId === 'OTHER'"
              class="booking-wide"
            >
              <span
                >会议说明
                <small v-if="data.roomId !== 'OTHER'">选填</small></span
              >
              <textarea
                v-model="data.description"
                maxlength="2000"
                rows="2"
                :required="data.roomId === 'OTHER'"
                :placeholder="
                  data.roomId === 'OTHER' ? '请填写具体会议地点' : undefined
                "
              />
            </label>
          </div>
        </section>

        <section
          v-if="!original || data.kind === 'WEEKLY'"
          class="flow-dialog-section"
        >
          <h3>会议流程</h3>
          <div v-if="!original" class="booking-options">
            <label
              v-for="(label, kind) in materialLabels"
              :key="kind"
              class="booking-check"
            >
              <input
                v-model="materialTypes"
                type="checkbox"
                :value="kind"
              />征集{{ label }}
            </label>
            <label v-if="data.kind === 'ORDINARY'" class="booking-check">
              <input v-model="data.trackFollowUp" type="checkbox" />跟踪会后反馈
            </label>
            <label v-if="options.canUrgent" class="booking-check">
              <input v-model="data.urgent" type="checkbox" />紧急会议
            </label>
          </div>
          <label class="booking-check">
            <input
              v-model="data.shareAll"
              type="checkbox"
            />向所有受邀员工共享会议全部内容
          </label>
          <p>
            共享后，受邀员工可查看彼此的回复、会前材料、会中记录、纪要和会后反馈。
          </p>
          <p>会议结束后须填写并发布纪要。</p>
          <p v-if="!original && materialTypes.length">
            选中的会前材料将发给所有受邀员工。
          </p>
        </section>

        <section class="flow-dialog-section booking-people">
          <div class="booking-people-head">
            <h3>
              受邀员工
              <small v-if="attendees.length"
                >已选 {{ attendees.length }} 人</small
              >
            </h3>
            <span v-if="!attendees.length">请选择至少一位员工</span>
          </div>
          <MeetingPeoplePicker
            :departments="options.departments"
            label="选择受邀员工"
            multiple
            :excluded="[profile?.user.id ?? '', ...attendees.map((a) => a.id)]"
            @confirm="(people) => attendees.push(...people)"
          />
          <ul v-if="attendees.length" class="booking-attendees">
            <li v-for="person in attendees" :key="person.id">
              <div>
                <strong>{{ person.name }}</strong>
                <small>{{ person.employeeNo }}</small>
              </div>
              <label
                v-if="
                  original &&
                  original.materialTypes == null &&
                  data.kind === 'ORDINARY' &&
                  data.collectMaterials
                "
                class="booking-check"
              >
                <input
                  v-model="materialUsers"
                  type="checkbox"
                  :value="person.id"
                />需要会前提交
              </label>
              <button
                type="button"
                class="secondary"
                :aria-label="'移除 ' + person.name"
                @click="remove(person.id)"
              >
                移除
              </button>
            </li>
          </ul>
        </section>
      </div>

      <footer class="flow-dialog-footer">
        <p v-if="error" class="form-error booking-error" role="alert">
          {{ error }}
        </p>
        <button
          type="button"
          class="secondary"
          :disabled="busy"
          @click="dismiss"
        >
          取消
        </button>
        <button
          class="primary"
          :disabled="busy || !data.roomId || !attendees.length"
        >
          {{ original ? "保存安排" : "发布会议" }}
        </button>
      </footer>
    </form>
  </dialog>
</template>

<style scoped>
.meeting-editor {
  --flow-dialog-width: 760px;
}
.booking-fields {
  gap: 14px 20px;
}
.booking-time-zone {
  color: var(--text-secondary);
  font-size: 12px;
}
.booking-wide {
  grid-column: 1 / -1;
}
.booking-fields > label {
  min-width: 0;
}
.booking-fields input,
.booking-fields select,
.booking-fields textarea {
  min-width: 0;
  width: 100%;
}
.booking-fields textarea {
  resize: vertical;
}
.booking-options {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 20px;
}
.meeting-editor label.booking-check {
  display: inline-flex;
  flex-direction: row;
  align-items: center;
  justify-content: flex-start;
  gap: 7px;
  margin: 0;
}
.meeting-editor label.booking-check input {
  width: 16px;
  height: 16px;
  margin: 0;
  flex: 0 0 auto;
  accent-color: var(--brand);
}
.booking-people-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px 14px;
}
.booking-people-head span,
.meeting-editor small {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 400;
}
.booking-attendees {
  margin: 0;
  padding: 0;
  list-style: none;
}
.booking-attendees li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 14px;
  padding: 8px 0;
  border-top: 1px solid var(--line);
}
.booking-attendees li > div {
  flex: 1 1 140px;
  overflow-wrap: anywhere;
}
.booking-error {
  flex-basis: 100%;
  margin: 0;
  max-height: 24dvh;
  overflow: auto;
  white-space: pre-wrap;
}
</style>
