<script setup lang="ts">
import { computed, onActivated, onDeactivated, ref, watch } from "vue";
import Icon from "../preview/PlatformIcon.vue";
import { api } from "./api";
import EmptyState from "./EmptyState.vue";

/** 未获会议详情权限时，服务端只返回地点、时段和占用状态。 */
interface RoomSlot {
  roomName: string;
  startsAt: string;
  endsAt: string;
  state: "SCHEDULED" | "IN_PROGRESS" | "ENDED";
  meetingId: string | null;
  subject: string | null;
  participantCount: number | null;
}

const siteZone = "Asia/Shanghai";
const dateKey = new Intl.DateTimeFormat("sv-SE", {
  timeZone: siteZone, year: "numeric", month: "2-digit", day: "2-digit",
});
const dayHeading = new Intl.DateTimeFormat("zh-CN", {
  timeZone: siteZone, year: "numeric", month: "long", day: "numeric", weekday: "long",
});
const clock = new Intl.DateTimeFormat("zh-CN", {
  timeZone: siteZone, hour: "2-digit", minute: "2-digit", hourCycle: "h23",
});
const shortDay = new Intl.DateTimeFormat("zh-CN", {
  timeZone: siteZone, month: "numeric", day: "numeric",
});
const stateLabels = {
  running: "进行中",
  soon: "即将开始",
  booked: "待开始",
  upcoming: "未开始",
  ended: "已结束",
  elapsed: "预约已过",
};
const now = ref(new Date());
const today = computed(() => dateKey.format(now.value));
const selectedDay = ref(today.value);
const dayLabel = computed(() => dayHeading.format(new Date(selectedDay.value + "T12:00:00Z")));
const slots = ref<RoomSlot[]>([]);
const loading = ref(false);
const failed = ref(false);
const expanded = ref(false);
let pending: AbortController | undefined;
let refreshTimer: number | undefined;

/** 快速翻日时只显示当前日期对应的请求结果。 */
async function loadSchedule() {
  pending?.abort();
  const request = new AbortController();
  pending = request;
  loading.value = true;
  failed.value = false;
  try {
    const data = await api<{ items: RoomSlot[] }>(
      "/meetings/room-schedule?" + new URLSearchParams({ date: selectedDay.value }),
      { signal: request.signal },
      false,
    );
    if (!request.signal.aborted) slots.value = data.items;
  } catch {
    if (!request.signal.aborted) {
      slots.value = [];
      failed.value = true;
    }
  } finally {
    if (!request.signal.aborted) loading.value = false;
  }
}

/** 日期以北京时间标识，翻日不受设备所在时区影响。 */
function shiftDay(days: number) {
  const next = new Date(selectedDay.value + "T12:00:00Z");
  next.setUTCDate(next.getUTCDate() + days);
  selectedDay.value = next.toISOString().slice(0, 10);
}

/** 跨日安排的起止时间附带月日，避免把次日结束理解为当天。 */
function slotTime(value: string) {
  const time = new Date(value);
  const day = dateKey.format(time) === selectedDay.value ? "" : shortDay.format(time) + " ";
  return day + clock.format(time);
}

/** 会议由发起人正式开始，单凭到达预约时间只标记为待开始。 */
function status(slot: RoomSlot): keyof typeof stateLabels {
  if (slot.state === "ENDED") return "ended";
  if (slot.state === "IN_PROGRESS") return "running";
  const time = now.value.getTime();
  if (Date.parse(slot.endsAt) <= time) return "elapsed";
  const until = Date.parse(slot.startsAt) - time;
  if (until <= 0) return "booked";
  return until <= 30 * 60 * 1000 ? "soon" : "upcoming";
}

/** 首页优先展示今天尚可赶上的安排，展开后按时段查看全天明细。 */
const shownSlots = computed(() => {
  const items = slots.value.map((slot) => ({ ...slot, status: status(slot) }));
  if (expanded.value || items.length <= 4) return items;
  const next = selectedDay.value === today.value
    ? items.findIndex(({ status }) => status !== "ended" && status !== "elapsed")
    : 0;
  const start = Math.min(Math.max(0, next), items.length - 4);
  return items.slice(start, start + 4);
});

watch(selectedDay, () => {
  expanded.value = false;
  slots.value = [];
  void loadSchedule();
});
onActivated(() => {
  now.value = new Date();
  void loadSchedule();
  refreshTimer = window.setInterval(() => {
    now.value = new Date();
  }, 60_000);
});
onDeactivated(() => {
  window.clearInterval(refreshTimer);
  pending?.abort();
});
</script>

<template>
  <section class="panel portal-panel room-schedule" aria-labelledby="room-schedule-heading">
    <div class="portal-heading">
      <h2 id="room-schedule-heading">会议室占用</h2>
      <div class="room-actions">
        <button class="text-button" type="button" :disabled="loading" aria-label="刷新会议室安排" @click="loadSchedule">刷新</button>
        <button
          v-if="slots.length > 4"
          class="text-button"
          type="button"
          :aria-expanded="expanded"
          aria-controls="room-schedule-list"
          @click="expanded = !expanded"
        >
          {{ expanded ? "收起安排" : `查看全部（${slots.length}）` }}
          <Icon :name="expanded ? 'down' : 'chevron'" :size="14" />
        </button>
      </div>
    </div>
    <div class="room-date">
      <button type="button" aria-label="上一天" @click="shiftDay(-1)">
        <Icon class="room-date-previous" name="chevron" :size="16" />
      </button>
      <div class="room-date-label" aria-live="polite">
        <time :datetime="selectedDay">{{ dayLabel }}</time>
        <span v-if="selectedDay === today" class="room-today">今天</span>
        <button v-else type="button" class="text-button" @click="selectedDay = today">回到今天</button>
      </div>
      <button type="button" aria-label="下一天" @click="shiftDay(1)">
        <Icon name="chevron" :size="16" />
      </button>
    </div>

    <div class="workspace-panel-body">
      <div v-if="failed" class="room-message">
        <p role="alert">会议室安排暂时无法加载。</p>
        <button class="secondary" type="button" @click="loadSchedule">重新加载</button>
      </div>
      <p v-else-if="loading && !slots.length" class="room-message muted" role="status">正在加载会议室安排…</p>
      <EmptyState v-else-if="!slots.length" icon="calendar" title="当天暂无会议室安排" />
      <ul v-else id="room-schedule-list" class="room-slots" :class="{ 'room-slots-expanded': expanded }">
        <li
          v-for="slot in shownSlots"
          :key="`${slot.roomName}:${slot.startsAt}:${slot.endsAt}`"
          class="room-slot"
          :data-state="slot.status"
        >
          <div class="room-slot-time">
            <time :datetime="slot.startsAt">{{ slotTime(slot.startsAt) }}</time>
            <span>–</span>
            <time :datetime="slot.endsAt">{{ slotTime(slot.endsAt) }}</time>
          </div>
          <div class="room-slot-copy">
            <a v-if="slot.meetingId" class="room-subject" :href="`#/meetings?id=${slot.meetingId}`">{{ slot.subject }}</a>
            <strong v-else class="room-subject">已预约</strong>
            <div class="room-slot-meta">
              <span><Icon name="home" :size="14" />{{ slot.roomName }}</span>
              <span v-if="slot.participantCount !== null"><Icon name="people" :size="14" />{{ slot.participantCount }} 人</span>
            </div>
          </div>
          <span class="room-slot-state">{{ stateLabels[slot.status] }}</span>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.room-schedule {
  container: room-schedule / inline-size;
}
.room-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.room-schedule .portal-heading .text-button {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  white-space: nowrap;
}
.room-date {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  min-height: 44px;
  padding: 2px 4px;
  border: 1px solid var(--line);
  border-radius: var(--radius-control);
  background: var(--surface-muted);
}
.room-date > button {
  display: grid;
  place-items: center;
  width: 38px;
  min-height: 38px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  color: var(--ink);
  background: transparent;
}
.room-date > button:hover {
  color: var(--brand);
  background: var(--brand-soft);
}
.room-date-previous {
  rotate: 180deg;
}
.room-date-label {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 2px 8px;
  padding: 3px 0;
  text-align: center;
  font-weight: 600;
}
.room-today {
  color: var(--brand);
  font-size: 0.85rem;
}
.room-date-label .text-button {
  padding: 2px 4px;
  font-size: 0.85rem;
}
.room-message {
  padding: 20px 12px;
  text-align: center;
}
.room-slots {
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}
.room-slots-expanded {
  max-height: 26rem;
  overflow: auto;
  scrollbar-gutter: stable;
}
.room-slot {
  --slot-tone: var(--text-secondary);
  --slot-tint: var(--surface-muted);
  display: grid;
  grid-template-columns: clamp(6.8rem, 28cqi, 10rem) minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px 12px;
  padding: 10px 5px 10px 12px;
  border-inline-start: 2px solid transparent;
  border-bottom: 1px solid var(--line);
}
.room-slot:last-child {
  border-bottom: 0;
}
.room-slot[data-state="running"] {
  --slot-tone: var(--success);
  --slot-tint: var(--success-soft);
  border-inline-start-color: var(--success);
}
.room-slot[data-state="soon"] {
  --slot-tone: var(--brand);
  --slot-tint: var(--brand-soft);
  border-inline-start-color: var(--brand);
}
.room-slot[data-state="booked"] {
  --slot-tone: var(--warning);
  --slot-tint: var(--warning-soft);
  border-inline-start-color: var(--warning);
}
.room-slot-time,
.room-slot-meta {
  color: var(--text-secondary);
  font-size: 0.87rem;
}
.room-slot-time {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 1px 4px;
  font-variant-numeric: tabular-nums;
}
.room-slot-copy {
  min-width: 0;
}
.room-subject {
  display: inline-block;
  color: var(--ink);
  font-size: 1rem;
  font-weight: 650;
  line-height: 1.4;
  overflow-wrap: anywhere;
}
a.room-subject:hover {
  color: var(--brand);
  text-decoration: underline;
}
.room-slot-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 3px 10px;
  margin-top: 4px;
}
.room-slot-meta span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  overflow-wrap: anywhere;
}
.room-slot-meta .p-icon {
  flex-shrink: 0;
}
.room-slot-state {
  padding: 3px 8px;
  border-radius: 6px;
  background: var(--slot-tint);
  color: var(--slot-tone);
  font-size: 0.83rem;
  font-weight: 600;
  white-space: nowrap;
}
@container room-schedule (max-width: 23rem) {
  .room-slot {
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .room-slot-time {
    grid-column: 1 / -1;
  }
}
</style>
