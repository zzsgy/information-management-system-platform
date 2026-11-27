<script setup lang="ts">
import { computed, inject, nextTick, ref, useTemplateRef, watch } from "vue";
import { api, profile, type Page } from "./api";
import { portalTime } from "./portal";
import Icon from "../preview/PlatformIcon.vue";
import EmptyState from "./EmptyState.vue";
import MeetingPermissionSettings from "./MeetingPermissionSettings.vue";
import MeetingEditor from "./MeetingEditor.vue";
import MeetingMaterialSummary from "./MeetingMaterialSummary.vue";
import MeetingMaterialForm from "./MeetingMaterialForm.vue";
import MeetingNarrativeView from "./MeetingNarrativeView.vue";
import MeetingMinutes from "./MeetingMinutes.vue";
import MeetingLiveDecision from "./MeetingLiveDecision.vue";
import MeetingPublishedDecisions from "./MeetingPublishedDecisions.vue";
import MeetingPublishedItemEditor from "./MeetingPublishedItemEditor.vue";
import MeetingReading from "./MeetingReading.vue";
import MeetingWorkbookPanel from "./MeetingWorkbookPanel.vue";
import MeetingWeeklyImport from "./MeetingWeeklyImport.vue";
import MeetingIncompleteExport from "./MeetingIncompleteExport.vue";
import { vNumbered } from "./meetingNumbered";
import RichTextEditor from "./RichTextEditor.vue";
import RichTextView from "./RichTextView.vue";
import { plainPreview } from "./formattedText";
import {
  materialLabels,
  type MaterialKind,
  meetingStates,
  type MeetingDetail,
  type MeetingItem,
  type MeetingMaterial,
  type MeetingOptions,
  type MeetingRoom,
  type MeetingSummary,
} from "./meeting";

const props = defineProps<{ route: string }>();
const inform = inject<(message: string) => void>("inform")!;
const options = ref<MeetingOptions>();
const results = ref<Page<MeetingSummary>>();
const detail = ref<MeetingDetail>();
const item = ref<MeetingItem>();
const rooms = ref<MeetingRoom[]>([]);
const error = ref(""),
  loading = ref(false),
  busy = ref(false);
const editor = ref(false),
  cancelling = ref(false);
const page = ref(1),
  reason = ref(""),
  replyReason = ref("");
const decline = ref(false),
  replyError = ref(""),
  returning = ref(false),
  returnReason = ref("");
const report = ref(""),
  feedback = ref("");
const roomName = ref(""),
  enabled = ref(true),
  roomId = ref("");

const declineInput = ref<HTMLTextAreaElement>();
const cancelInput = ref<HTMLTextAreaElement>();

async function openDecline() {
  replyError.value = "";
  decline.value = !decline.value;
  if (decline.value) {
    await nextTick();
    declineInput.value?.focus();
  }
}

async function openCancellation() {
  cancelling.value = !cancelling.value;
  if (cancelling.value) {
    await nextTick();
    cancelInput.value?.focus();
  }
}

const liveDecision =
  useTemplateRef<InstanceType<typeof MeetingLiveDecision>>("liveDecision");

/** 通过页面返回入口离开前，先保存尚未提交的会中决议。 */
async function returnToList() {
  if (busy.value) return;
  busy.value = true;
  try {
    if (!liveDecision.value || (await liveDecision.value.flush()))
      location.hash = "#/meetings";
  } finally {
    busy.value = false;
  }
}
const params = computed(
  () => new URLSearchParams(props.route.split("?")[1] ?? ""),
);
const meetingId = computed(() => params.value.get("id") ?? "");
const itemId = computed(() => params.value.get("item") ?? "");
const creating = computed(() => !meetingId.value && !itemId.value && params.value.get("new") === "1");
/** 配置页只由管理中心进入，会议室接口仍按现有管理权限鉴权。 */
const configuring = computed(() => props.route === "meeting-config");
const roomDialog = ref<HTMLDialogElement>();
const roomEditing = ref(false);
watch(roomDialog, (dialog) => dialog?.showModal(), { flush: "post" });

function closeRoom() {
  if (!busy.value) roomEditing.value = false;
}
const materialOrder: MaterialKind[] = ["REPORT", "WEEKLY_PLAN", "COORDINATION"];
const formKinds = computed(() =>
  materialOrder.filter((kind) => detail.value?.materialTypes?.includes(kind)),
);
const own = computed(() =>
  detail.value?.attendees.find((a) => !a.materialOnly && a.userId === profile.value?.user.id),
);
/** Excel 和逐人提交状态由发起人查看。 */
const organizer = computed(() => detail.value?.organizerId === profile.value?.user.id);

/** 汇总只在发起人详情中显示，数据沿用详情接口已有的查看权限。 */
const summaryTypes = computed<MaterialKind[]>(() =>
  detail.value?.shareAll === true ||
  detail.value?.organizerId === profile.value?.user.id
    ? (detail.value?.materialTypes ?? []).filter((kind) => kind === "COORDINATION")
    : [],
);
const submissions = computed(
  () =>
    detail.value?.attendees.flatMap((person) => person.materials ?? []) ?? [],
);

/** 本人事项优先；退回补充的事项仍需填写，未获授权读取的状态单独列出。 */
const followUpOwners = computed(() => {
  const tasks = (detail.value?.items ?? []).map((task) => {
    const own = task.trackers.find((tracker) => tracker.assigneeId === profile.value?.user.id);
    const next = task.trackers.find((tracker) => needsFeedback(tracker.state)) ?? task.trackers[0];
    return { ...task, mine: !!own, state: (own ?? next)?.state ?? null };
  });
  return [true, false]
    .map((mine) => {
      const assigned = tasks.filter((task) => task.mine === mine);
      return {
        mine,
        groups: [
          { label: "未填写反馈", tasks: assigned.filter((task) => needsFeedback(task.state)) },
          {
            label: "已填写反馈",
            tasks: assigned.filter((task) => task.state === "SUBMITTED" || task.state === "CONFIRMED"),
          },
          { label: "反馈状态未共享", tasks: assigned.filter((task) => task.state == null) },
        ].filter((group) => group.tasks.length),
      };
    })
    .filter((owner) => owner.groups.length);
});

/** 退回补充与初次分派均可填写反馈。 */
function needsFeedback(state: string | null) {
  return state === "PENDING" || state === "RETURNED";
}

/** 工作台入口在详情加载后定位到相应操作，保留已有的材料直达链接。 */
function taskTarget() {
  switch (params.value.get("action")) {
    case "reply":
      return "meeting-reply";
    case "minutes":
      return "meeting-minutes-editor";
    case "follow-up":
      return "meeting-follow-up";
    case "materials": {
      if (detail.value?.materialTypes == null) return "meeting-material-legacy";
      const kind = missingMaterialKinds(formKinds.value, own.value?.materials ?? [])[0];
      return kind ? "meeting-material-" + kind : undefined;
    }
    default: {
      const kind = params.value.get("material");
      return formKinds.value.some((entry) => entry === kind) ? "meeting-material-" + kind : undefined;
    }
  }
}

async function refresh() {
  if (itemId.value) {
    item.value = await api<MeetingItem>("/meetings/items/" + itemId.value);
  } else if (meetingId.value) {
    const record = await api<MeetingDetail>("/meetings/" + meetingId.value);
    detail.value = record;
    report.value = own.value?.report ?? "";
    replyReason.value = own.value?.declineReason ?? "";
  } else if (configuring.value) {
    rooms.value = (
      await api<{ items: MeetingRoom[] }>("/admin/meetings/rooms")
    ).items;
  } else {
    results.value = await api<Page<MeetingSummary>>(
      "/meetings?page=" + page.value,
    );
  }
}

/** 子表单保存后留在详情页，更新提交状态与汇总。 */
async function reloadDetail() {
  try {
    await refresh();
  } catch (e) {
    error.value = (e as Error).message;
  }
}

/** 关联待办从同一个协调结果表单更正，避免两套独立输入。 */
function openLinkedCoordination(key: string) {
  document.getElementById("meeting-coordination-" + key)?.click();
}

/** 同一会议页承接工作台和消息跳转。换路由时清除上一个业务对象的表单。 */
watch(
  [() => props.route, page],
  async (_, __, onCleanup) => {
    let current = true;
    onCleanup(() => {
      current = false;
    });
    error.value = reason.value = feedback.value = returnReason.value = replyError.value = "";
    editor.value = cancelling.value = decline.value = returning.value = false;
    detail.value = undefined;
    item.value = undefined;
    loading.value = true;
    try {
      options.value ??= await api<MeetingOptions>("/meetings/options");
      if (!current) return;
      if (current && !creating.value) await refresh();
    } catch (e) {
      if (current) error.value = (e as Error).message;
    } finally {
      if (current) {
        loading.value = false;
        const targetId = detail.value && taskTarget();
        if (targetId) {
          await nextTick();
          if (current) document.getElementById(targetId)?.scrollIntoView({ block: "start" });
        }
      }
    }
  },
  { immediate: true },
);

/** 提交成功后回到会议列表，跨路由成功提示复用平台通知。 */
function finishMeetingAction(message: string) {
  editor.value = false;
  location.hash = "#/meetings";
  inform(message);
}

/** 会议写入成功即离开办理页，会议室配置在本页刷新。 */
async function act(
  path: string,
  body: object,
  success: string,
  method = "POST",
  displayError = error,
) {
  if (busy.value) return;
  busy.value = true;
  displayError.value = "";
  try {
    await api(path, { method, body: JSON.stringify(body) });
    if (configuring.value) {
      roomEditing.value = false;
      inform(success);
      await refresh();
    } else {
      finishMeetingAction(success);
    }
    return true;
  } catch (e) {
    displayError.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}

function saved() {
  finishMeetingAction(meetingId.value ? "会议安排已保存" : "会议已发布");
}

function cancelEditor() {
  if (creating.value) location.hash = "#/meetings";
  else editor.value = false;
}

async function replyMeeting(id: string, decision: "ACCEPTED" | "DECLINED") {
  await act(
    "/meetings/" + id + "/reply",
    decision === "ACCEPTED" ? { decision } : { decision, reason: replyReason.value },
    decision === "ACCEPTED" ? "已参加" : "已回复不参加",
    "POST",
    replyError,
  );
}
/** 开始后留在详情查看材料，结束后滚动到纪要编辑区。 */
async function changeMeetingPhase(action: "start" | "end") {
  if (busy.value || !detail.value) return;
  const id = detail.value.id;
  const message =
    action === "start" ? "会议已开始" : "会议已结束，请填写会议纪要";
  busy.value = true;
  error.value = "";
  try {
    if (action === "end" && liveDecision.value && !(await liveDecision.value.flush()))
      return;
    await api("/meetings/" + id + "/" + action, { method: "POST" });
    inform(message);
    await refresh();
    if (action === "end") {
      await nextTick();
      document
        .getElementById("meeting-minutes-editor")
        ?.scrollIntoView({ block: "start" });
    }
  } catch (e) {
    error.value = (e as Error).message;
    await nextTick();
    document
      .getElementById("meeting-detail-error")
      ?.scrollIntoView({ block: "nearest" });
  } finally {
    busy.value = false;
  }
}

function minutesSaved(published: boolean) {
  finishMeetingAction(
    published
      ? detail.value?.trackFollowUp
        ? "纪要已发布"
        : "纪要已发布，会议已办结"
      : "纪要草稿已保存",
  );
}
function chooseRoom(room?: MeetingRoom) {
  error.value = "";
  roomEditing.value = true;
  roomId.value = room?.id ?? "";
  roomName.value = room?.name ?? "";
  enabled.value = room?.enabled ?? true;
}
async function saveRoom() {
  await act(
    "/admin/meetings/rooms" + (roomId.value ? "/" + roomId.value : ""),
    { name: roomName.value, enabled: enabled.value },
    "会议室配置已保存",
    roomId.value ? "PUT" : "POST",
  );
}
/** 列表日期与时段统一用北京时间，跨天会议显示结束日期。 */
const calendarDay = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai",
  month: "long",
  day: "numeric",
});
/** 会议日期卡片以北京时间展示星期，和排期时段使用同一时区。 */
const weekDay = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai", weekday: "long",
});
const clock = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});
function dateLabel(value: string) {
  return calendarDay.format(new Date(value));
}
function timeRange(start: string, end: string) {
  const endDate =
    dateLabel(start) === dateLabel(end) ? "" : dateLabel(end) + " ";
  return (
    clock.format(new Date(start)) +
    " – " +
    endDate +
    clock.format(new Date(end))
  );
}

function missingMaterialKinds(
  types: MaterialKind[],
  submitted: MeetingMaterial[],
) {
  return types.filter((kind) => kind !== "COORDINATION" && !submitted.some((entry) => entry.kind === kind));
}

/** 受邀人员卡片展示实际缺少的材料名称。 */
function missingMaterials(types: MaterialKind[], submitted: MeetingMaterial[]) {
  return missingMaterialKinds(types, submitted).map((kind) => materialLabels[kind]).join("、");
}
function replyLabel(reply?: string) {
  return reply === "ACCEPTED"
    ? "参加"
    : reply === "DECLINED"
      ? "不参加"
      : "未回复";
}
</script>

<template>
  <div
    class="platform-page-content meeting-page"
    :class="{ 'room-management': configuring, 'meeting-create': creating, 'meeting-detail-page': meetingId }"
  >
    <a
      v-if="meetingId"
      class="meeting-detail-back"
      href="#/meetings"
      :aria-disabled="busy || undefined"
      @click.prevent="returnToList"
    >
      <span aria-hidden="true">←</span> 返回会议列表
    </a>
    <header v-else-if="!creating" class="meeting-actions meeting-spread">
      <div class="meeting-page-title">
        <span v-if="!configuring" class="flow-dialog-icon" aria-hidden="true"
          ><Icon name="calendar" :size="23"
        /></span>
        <h1>{{ configuring ? "会议配置" : "会议预约" }}</h1>
      </div>
      <div class="meeting-actions">
        <a v-if="itemId" class="secondary" href="#/meetings">会议列表</a>
        <button
          v-if="configuring && options?.canManageRooms"
          class="primary room-action"
          @click="chooseRoom()"
        >
          <Icon name="plus" :size="16" />新增会议室
        </button>
        <a
          v-if="
            options?.canCreate &&
            !editor &&
            !meetingId &&
            !itemId &&
            !configuring
          "
          class="primary"
          href="#/meetings?new=1"
        >
          <Icon name="plus" :size="16" />发起会议
        </a>
      </div>
    </header>
    <p
      v-if="error && !detail && !roomEditing"
      class="form-error meeting-text"
      role="alert"
    >
      {{ error }}
    </p>
    <MeetingEditor
      v-if="options && ((creating && options.canCreate) || editor)"
      :key="detail?.id ?? 'new'"
      :detail="detail"
      :options="options"
      @saved="saved"
      @cancel="cancelEditor"
    />

    <p v-if="loading" role="status">加载中</p>
    <p v-else-if="creating && options && !options.canCreate" role="alert">你没有发起会议的权限。<a href="#/meetings">返回会议列表</a></p>

    <section v-else-if="configuring" class="room-panel" aria-label="会议室列表">
      <header class="room-panel-head">
        <span class="flow-dialog-icon" aria-hidden="true"
          ><Icon name="calendar" :size="20"
        /></span>
        <div>
          <h2>会议室列表</h2>
          <p>已启用的会议室会显示在预约下拉选项中。</p>
        </div>
      </header>
      <table v-if="rooms.length" class="room-table">
        <thead>
          <tr>
            <th scope="col">会议室名称</th>
            <th scope="col">状态</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="room in rooms" :key="room.id">
            <td>{{ room.name }}</td>
            <td>
              <span class="room-state" :class="{ enabled: room.enabled }">{{
                room.enabled ? "已启用" : "已停用"
              }}</span>
            </td>
            <td>
              <button
                type="button"
                class="secondary"
                :aria-label="'编辑 ' + room.name"
                @click="chooseRoom(room)"
              >
                编辑
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <EmptyState
        v-else
        icon="calendar"
        title="暂无会议室"
        hint="新增会议室后即可维护预约地点。"
      />
      <footer v-if="rooms.length" class="room-panel-footer">
        共 {{ rooms.length }} 间会议室
      </footer>
    </section>

    <template v-else-if="item">
      <section class="room-panel task-panel meeting-section">
        <h2><Icon name="task" :size="20" /> 会后待办</h2>
        <RichTextView class="meeting-text" :value="item.content" />
        <p>
          跟踪人 {{ item.assigneeName }} · 截止 {{ portalTime(item.dueAt) }} ·
          {{ item.overdue ? "逾期" : meetingStates[item.state] }}
        </p>
        <ul v-if="item.history.length" class="meeting-history">
          <li v-for="(entry, index) in item.history" :key="index">
            <strong
              >{{ entry.actorName }} · {{ meetingStates[entry.state] }}</strong
            >
            <small>{{ portalTime(entry.at) }}</small>
            <RichTextView v-if="entry.text" class="meeting-text" :value="entry.text" />
          </li>
        </ul>
        <form
          v-if="item.canSubmit"
          class="meeting-section"
          @submit.prevent="
            act(
              '/meetings/items/' + item.id + '/feedback',
              { feedback },
              '反馈已提交',
            )
          "
        >
          <RichTextEditor v-model="feedback" label="落实情况" :rows="4" :limit="8000" required />
          <button class="primary" :disabled="busy">提交反馈</button>
        </form>
        <div v-if="item.canReview" class="meeting-actions">
          <button
            class="primary"
            :disabled="busy"
            @click="
              act(
                '/meetings/items/' + item.id + '/review',
                { decision: 'CONFIRMED' },
                '事项已确认',
              )
            "
          >
            确认完成
          </button>
          <button
            class="secondary"
            :disabled="busy"
            @click="returning = !returning"
          >
            退回补充
          </button>
        </div>
        <form
          v-if="item.canReview && returning"
          class="meeting-section"
          @submit.prevent="
            act(
              '/meetings/items/' + item.id + '/review',
              { decision: 'RETURNED', reason: returnReason },
              '已退回责任人',
            )
          "
        >
          <label
            >退回原因<textarea v-numbered
              v-model="returnReason"
              required
              maxlength="1000"
              rows="3"
            />
          </label>
          <button class="primary" :disabled="busy">确认退回</button>
        </form>
      </section>
    </template>

    <section
      v-else-if="!creating && !meetingId && !itemId && !configuring && results"
      class="room-panel booking-list"
      aria-label="我的会议"
    >
      <header class="room-panel-head booking-list-head">
        <h2>我的会议</h2>
        <MeetingIncompleteExport />
        <span v-if="results.total" class="booking-count"
          >共 {{ results.total }} 场</span
        >
      </header>
      <p
        v-if="options?.canCreate && !options.rooms.length"
        class="booking-list-note"
      >
        可选择其他地点，并在会议说明中填写具体位置。
      </p>
      <a
        v-for="meeting in results.items"
        :key="meeting.id"
        class="booking-row"
        :href="'#/meetings?id=' + meeting.id"
      >
        <time class="booking-date" :datetime="meeting.startsAt">
          <small>{{ weekDay.format(new Date(meeting.startsAt)) }}</small>
          <strong>{{ dateLabel(meeting.startsAt) }}</strong>
        </time>
        <div class="booking-copy">
          <div class="booking-title">
            <strong>{{ meeting.subject }}</strong>
            <span v-if="meeting.kind === 'WEEKLY'" class="booking-badge"
              >周会</span
            >
            <span v-if="meeting.urgent" class="booking-badge urgent">紧急</span>
          </div>
          <div class="booking-meta">
            <span
              ><Icon name="clock" :size="15" />{{
                timeRange(meeting.startsAt, meeting.endsAt)
              }}</span
            >
            <span>{{ meeting.roomName }}</span>
          </div>
        </div>
        <span class="booking-badge" :data-state="meeting.state">{{
          meetingStates[meeting.state]
        }}</span>
        <Icon
          class="booking-chevron"
          name="chevron"
          :size="16"
          aria-hidden="true"
        />
      </a>
      <EmptyState
        v-if="!results.items.length"
        icon="calendar"
        title="暂无会议"
        hint="你发起和受邀的会议会显示在这里。"
      />
      <nav
        v-if="results.total"
        class="meeting-actions booking-list-footer"
        aria-label="会议列表分页"
      >
        <button
          class="secondary"
          @click="refresh().catch((e) => (error = e.message))"
        >
          刷新
        </button>
        <span>第 {{ page }} 页</span>
        <button class="secondary" :disabled="page <= 1" @click="page--">
          上一页
        </button>
        <button class="secondary" :disabled="!results.hasNext" @click="page++">
          下一页
        </button>
      </nav>
    </section>
    <MeetingPermissionSettings
      v-if="configuring && options?.canManageRooms && !loading"
      :departments="options.departments"
    />

    <dialog
      v-if="roomEditing"
      ref="roomDialog"
      class="form-dialog flow-dialog flow-dialog-compact"
      aria-labelledby="meeting-room-title"
      @cancel.prevent="closeRoom"
    >
      <form class="flow-dialog-shell" @submit.prevent="saveRoom">
        <header class="flow-dialog-header">
          <span class="flow-dialog-icon" aria-hidden="true"
            ><Icon name="calendar" :size="20"
          /></span>
          <h2 id="meeting-room-title">
            {{ roomId ? "编辑会议室" : "新增会议室" }}
          </h2>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭会议室弹窗"
            :disabled="busy"
            @click="closeRoom"
          >
            ×
          </button>
        </header>
        <div class="flow-dialog-body">
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <label
            >会议室名称<input
              v-model="roomName"
              required
              maxlength="100"
              autofocus
          /></label>
          <label class="room-status-check"
            ><input v-model="enabled" type="checkbox" />启用此会议室</label
          >
        </div>
        <footer class="flow-dialog-footer">
          <button
            type="button"
            class="secondary"
            :disabled="busy"
            @click="closeRoom"
          >
            取消
          </button>
          <button class="primary" :disabled="busy || !roomName.trim()">
            保存
          </button>
        </footer>
      </form>
    </dialog>

    <section v-if="detail" class="meeting-detail" aria-labelledby="meeting-detail-title">
      <div class="meeting-detail-content">
        <header class="meeting-detail-header">
          <div class="meeting-page-title detail-heading">
            <span class="flow-dialog-icon" aria-hidden="true">
              <Icon name="calendar" :size="23" />
            </span>
            <div class="detail-title">
              <h1 id="meeting-detail-title">{{ detail.subject }}</h1>
              <span class="booking-badge" :data-state="detail.state">
                {{ detail.urgent ? "紧急 · " : "" }}{{ meetingStates[detail.state] }}
              </span>
            </div>
          </div>
          <div
            v-if="detail.canStart || detail.canEnd || detail.canEdit"
            class="meeting-actions detail-actions"
            role="group"
            aria-label="会议操作"
          >
            <button
              v-if="detail.canStart"
              type="button"
              class="primary"
              :disabled="busy"
              @click="changeMeetingPhase('start')"
            >
              开始会议
            </button>
            <button
              v-if="detail.canEnd"
              type="button"
              class="primary"
              :disabled="busy"
              @click="changeMeetingPhase('end')"
            >
              结束会议
            </button>
            <button
              v-if="detail.canEdit"
              type="button"
              class="secondary"
              :disabled="busy"
              @click="editor = true"
            >
              修改安排
            </button>
            <button
              v-if="detail.canEdit"
              type="button"
              class="secondary"
              :disabled="busy"
              @click="openCancellation"
            >
              取消会议
            </button>
          </div>
        </header>
        <div class="meeting-detail-body">
          <p
            v-if="error"
            id="meeting-detail-error"
            class="form-error meeting-text"
            role="alert"
          >
            {{ error }}
          </p>
          <section class="flow-dialog-section detail-overview" aria-label="会议信息">
            <dl class="detail-facts">
              <div class="detail-period">
                <dt>预约时间</dt>
                <dd>
                  {{ portalTime(detail.startsAt) }} 至
                  {{ portalTime(detail.endsAt) }}
                </dd>
              </div>
              <div>
                <dt>会议室</dt>
                <dd>{{ detail.roomName }}</dd>
              </div>
              <div>
                <dt>发起人</dt>
                <dd>{{ detail.organizerName }}</dd>
              </div>
              <div v-if="detail.actualStartedAt">
                <dt>实际开始</dt>
                <dd>{{ portalTime(detail.actualStartedAt) }}</dd>
              </div>
              <div v-if="detail.actualEndedAt">
                <dt>实际结束</dt>
                <dd>{{ portalTime(detail.actualEndedAt) }}</dd>
              </div>
            </dl>
            <RichTextView v-if="detail.description" class="meeting-text" :value="detail.description" />
            <p v-if="detail.cancelReason" class="meeting-text">
              取消原因 {{ detail.cancelReason }}
            </p>
            <form
              v-if="cancelling"
              class="meeting-section"
              @submit.prevent="
                act(
                  '/meetings/' + detail.id + '/cancel',
                  { reason },
                  '会议已取消',
                )
              "
            >
              <label
                >取消原因<textarea v-numbered
                  ref="cancelInput"
                  v-model="reason"
                  required
                  maxlength="1000"
                  rows="3"
                />
              </label>
              <button class="primary" :disabled="busy">确认取消</button>
            </form>
            <MeetingWorkbookPanel
              v-if="organizer"
              :key="detail.id"
              class="detail-workbook"
              :meeting-id="detail.id"
              :can-import="detail.canStart && !!formKinds.length"
              @imported="reloadDetail"
            />
            <MeetingWeeklyImport
              v-if="organizer && (detail.canStart || detail.canEnd || detail.canWriteMinutes)"
              :key="'weekly-' + detail.id"
              :meeting-id="detail.id"
              @imported="reloadDetail"
            />
          </section>

          <section class="flow-dialog-section detail-attendees" aria-labelledby="meeting-attendees-title">
            <header class="meeting-actions meeting-spread">
              <h2 id="meeting-attendees-title">
                受邀员工
                <span class="booking-count">{{ detail.attendees.filter((person) => !person.materialOnly).length }} 人</span>
                <span v-if="detail.attendees.some((person) => person.materialOnly)" class="booking-count"> · 含补录材料</span>
              </h2>
              <div v-if="(organizer && detail.canStart) || detail.readingRevision" class="meeting-actions">
                <button
                  v-if="organizer && detail.canStart"
                  type="button"
                  class="secondary"
                  :disabled="busy"
                  @click="reloadDetail()"
                >
                  刷新提交与阅读状态
                </button>
                <MeetingReading
                  v-if="detail.readingRevision"
                  :key="detail.id"
                  :meeting-id="detail.id"
                  :revision="detail.readingRevision"
                  :other-attendees="detail.attendees.filter((person) => !person.materialOnly && person.userId !== own?.userId)"
                  @saved="reloadDetail"
                  @stale="reloadDetail"
                />
              </div>
            </header>
            <ul class="meeting-history">
              <li
                v-for="person in detail.attendees"
                :key="person.userId"
                class="booking-person"
              >
                <div class="booking-person-name">
                  <span aria-hidden="true" class="booking-avatar">{{
                    person.name.slice(-1)
                  }}</span>
                  <strong>{{ person.name }}</strong
                  ><span class="booking-count">{{ person.employeeNo }}</span>
                  <span v-if="person.materialOnly" class="booking-count"> · 材料补录</span>
                </div>
                <template v-if="person.reply">
                  <span>
                    · {{ replyLabel(person.reply)
                    }}<template
                      v-if="
                        person.reply === 'PENDING' &&
                        detail.state !== 'SCHEDULED'
                      "
                      >（{{
                        detail.state === "CANCELLED"
                          ? "会议已取消"
                          : "回复已截止"
                      }}）</template
                    >
                  </span>
                  <p v-if="person.declineReason" class="meeting-text">
                    {{ person.declineReason }}
                  </p>
                </template>
                <small v-if="person.readingRequired">
                  {{ person.readMaterialAt ? "资料已确认阅读 · " + portalTime(person.readMaterialAt) : "会前资料待确认" }}
                </small>
                <template v-if="person.materialRequired && detail.materialTypes == null">
                  <small v-if="person.materialAt"
                    >已提交会前材料 · {{ portalTime(person.materialAt) }}</small
                  >
                  <small
                    v-else-if="
                      detail.kind === 'WEEKLY' || person.reply !== 'DECLINED'
                    "
                    >未提交会前材料</small
                  >
                </template>
                <div
                  v-if="detail.materialTypes && person.materials"
                  class="material-results"
                >
                  <small v-if="person.materials.some((entry) => entry.data.source === 'EXCEL')">Excel 已提交</small>
                  <p
                    v-if="
                      (organizer || person.userId === own?.userId) &&
                      person.reply !== 'DECLINED' &&
                      missingMaterials(formKinds, person.materials)
                    "
                    class="booking-count"
                  >
                    未提交
                    {{
                      missingMaterials(formKinds, person.materials)
                    }}
                  </p>
                  <small v-else-if="(organizer || person.userId === own?.userId) && person.materials.length
                    && !person.materials.some((entry) => entry.data.source === 'EXCEL')">会前材料已提交</small>
                  <details
                    v-for="material in person.materials.filter(
                      (entry) =>
                        entry.kind !== 'TOPICS',
                    )"
                    :key="material.kind"
                  >
                    <summary>
                      {{ materialLabels[material.kind] }} ·
                      {{ portalTime(material.submittedAt) }}
                    </summary>
                    <p class="booking-count">
                      {{ material.data.author.name }} ·
                      {{ material.data.author.department }}
                    </p>
                    <MeetingNarrativeView
                      v-if="material.kind === 'REPORT' || material.kind === 'WEEKLY_PLAN'"
                      class="meeting-text" :meeting-id="detail.id" :material="material"
                    />
                    <ol v-else>
                      <li
                        v-for="(row, index) in material.data.rows"
                        :key="index"
                      >
                        <strong>{{ row.subject }}</strong>
                        <p class="meeting-text">期望结果 {{ row.expected }}</p>
                        <small v-if="row.department">{{ row.department }}</small>
                        <small v-if="row.assigneeName"> · 会前填报责任人 {{ row.assigneeName }}</small>
                      </li>
                    </ol>
                  </details>
                </div>
                <div v-if="person.report" class="material-results">
                  <small>汇报</small><RichTextView class="meeting-text" :value="person.report" />
                </div>
              </li>
            </ul>
          </section>
          <MeetingMaterialSummary
            v-if="summaryTypes.length && (!detail.minutes || detail.canWriteMinutes)"
            :types="summaryTypes"
            :submissions="submissions"
            :results="detail.coordinationResults"
            :editing="(detail.canEnd || detail.canWriteMinutes) && options
              ? { meetingId: detail.id, departments: options.departments, trackFollowUp: detail.trackFollowUp }
              : undefined"
            @saved="reloadDetail"
          />
          <section
            v-if="
              detail.canSubmitMaterials &&
              formKinds.length &&
              detail.materialAuthor &&
              options
            "
            class="flow-dialog-section"
          >
            <MeetingMaterialForm
              v-for="kind in formKinds"
              :key="detail.id + ':' + kind"
              :meeting-id="detail.id"
              :id="'meeting-material-' + kind"
              :kind="kind"
              :author="detail.materialAuthor"
              :existing="own?.materials?.find((entry) => entry.kind === kind)"
              :departments="options.departments"
              @saved="reloadDetail"
            />
          </section>
          <form
            v-if="detail.canSubmitMaterials && detail.materialTypes == null"
            id="meeting-material-legacy"
            class="flow-dialog-section"
            @submit.prevent="
              act(
                '/meetings/' + detail.id + '/materials',
                { report },
                '会前材料已保存',
                'PUT',
              )
            "
          >
            <h3>会前汇报</h3>
            <RichTextEditor v-model="report" label="汇报信息" :rows="4" :limit="4000" />
            <button
              class="primary"
              :disabled="busy || !report.trim()"
            >
              提交材料
            </button>
          </form>
          <MeetingLiveDecision
            v-if="detail.canEnd"
            ref="liveDecision"
            :meeting-id="detail.id"
            :initial="detail.liveDecisions ?? null"
            :disabled="busy"
          />
          <section v-else-if="detail.liveDecisions && !detail.minutes?.publishedAt" class="flow-dialog-section">
            <h3>会议决议</h3>
            <RichTextView class="meeting-text" :value="detail.liveDecisions" />
          </section>
          <MeetingMinutes
            v-if="detail.canWriteMinutes && options"
            id="meeting-minutes-editor"
            :key="detail.id"
            :meeting="detail"
            :departments="options.departments"
            @saved="minutesSaved"
          />
          <section v-else-if="detail.minutes" class="flow-dialog-section">
            <h2>
              {{ detail.minutes.publishedAt ? "会议纪要" : "会议纪要底稿" }}
            </h2>
            <small v-if="detail.minutes.publishedAt"
              >发布于 {{ portalTime(detail.minutes.publishedAt) }}</small
            >
            <MeetingMaterialSummary
              v-if="
                detail.minutes.materialTables &&
                detail.minutes.summaryTypes?.length
              "
              :types="detail.minutes.summaryTypes"
              :submissions="detail.minutes.materialTables"
              :results="detail.coordinationResults"
              :editing="organizer && detail.minutes.publishedAt && options
                ? { meetingId: detail.id, departments: options.departments, trackFollowUp: detail.trackFollowUp, published: true }
                : undefined"
              @saved="reloadDetail"
            />
            <template v-if="detail.minutes.materialsText">
              <h3>会前材料汇总</h3>
              <RichTextView class="meeting-text" :value="detail.minutes.materialsText" />
            </template>
            <template v-if="detail.minutes.notes">
              <h3>会议记录</h3>
              <RichTextView class="meeting-text" :value="detail.minutes.notes" />
            </template>
            <template v-if="detail.minutes.summary">
              <h3>会议总结</h3>
              <RichTextView class="meeting-text" :value="detail.minutes.summary" />
            </template>
            <MeetingPublishedDecisions v-if="organizer && detail.minutes.publishedAt"
              :meeting-id="detail.id" :version="detail.rowVersion" :value="detail.minutes.decisions"
              @saved="reloadDetail" />
            <template v-else-if="detail.minutes.decisions">
              <h3>会议决议</h3>
              <RichTextView class="meeting-text" :value="detail.minutes.decisions" />
            </template>
            <template v-if="detail.minutes.items?.length">
              <h3>拟分派事项</h3>
              <ul class="meeting-history">
                <li v-for="(task, index) in detail.minutes.items" :key="index">
                  <RichTextView class="meeting-text" :value="task.content" />
                  <small
                    >{{ task.trackers.map((person) => person.name).join('、') }} · 截止
                    {{ portalTime(task.dueAt) }}</small
                  >
                </li>
              </ul>
            </template>
          </section>
          <section
            v-if="detail.trackFollowUp && detail.minutes?.publishedAt"
            id="meeting-follow-up"
            class="flow-dialog-section"
          >
            <h3>会后待办与反馈</h3>
            <template v-if="detail.items.length">
              <div v-for="owner in followUpOwners" :key="String(owner.mine)" class="meeting-follow-up-owner">
                <h4>{{ owner.mine ? '我的待办' : '其他待办' }}</h4>
                <section v-for="group in owner.groups" :key="group.label" class="meeting-follow-up-group">
                  <h5>{{ group.label }}</h5>
                  <ul class="meeting-history meeting-follow-up-list">
                    <li v-for="task in group.tasks" :key="task.groupId" :class="{ 'own-follow-up': owner.mine }">
                      <div class="meeting-follow-up-copy">
                        <a v-if="task.trackers.some((tracker) => tracker.canOpen)" :href="'#/meetings?item=' + task.id">{{ plainPreview(task.content) }}</a>
                        <RichTextView v-else class="meeting-text" :value="task.content" />
                        <small>截止 {{ portalTime(task.dueAt) }}</small>
                        <div v-for="tracker in task.trackers" :key="tracker.id" class="meeting-tracker-line">
                          <small>{{ tracker.assigneeName }}<template v-if="tracker.state"> · {{ meetingStates[tracker.state] }}</template></small>
                          <a v-if="tracker.canOpen" :href="'#/meetings?item=' + tracker.id">
                            {{ tracker.assigneeId === profile?.user.id && needsFeedback(tracker.state) ? '填写反馈' : '查看待办' }}
                          </a>
                        </div>
                      </div>
                      <div v-if="organizer && options" class="meeting-follow-up-actions">
                        <button v-if="task.coordinationKey" type="button" class="secondary"
                          @click="openLinkedCoordination(task.coordinationKey)">编辑关联协调结果</button>
                        <MeetingPublishedItemEditor v-else :item="task" :departments="options.departments"
                          @saved="reloadDetail" />
                      </div>
                    </li>
                  </ul>
                </section>
              </div>
            </template>
            <p v-else class="booking-count">
              {{
                detail.shareAll || detail.organizerId === profile?.user.id
                  ? "本次会议未分派会后待办。"
                  : "当前没有可查看的会后待办。"
              }}
            </p>
            <button
              v-if="detail.canClose"
              class="primary"
              :disabled="busy"
              @click="
                act('/meetings/' + detail.id + '/close', {}, '会议已办结')
              "
            >
              确认会议办结
            </button>
          </section>
          <footer v-if="detail.canReply" id="meeting-reply" class="meeting-reply-footer" aria-label="参会回复">
            <div class="meeting-actions">
              <button type="button" class="primary" :disabled="busy" @click="replyMeeting(detail.id, 'ACCEPTED')">参加</button>
              <button type="button" class="secondary" :disabled="busy" :aria-expanded="decline" @click="openDecline">不参加</button>
            </div>
            <form v-if="decline" class="meeting-decline-form" @submit.prevent="replyMeeting(detail.id, 'DECLINED')">
              <label>
                不参加的理由
                <textarea v-numbered ref="declineInput" v-model="replyReason" required maxlength="1000" rows="3" />
              </label>
              <button type="submit" class="primary" :disabled="busy">提交回复</button>
            </form>
            <p v-if="replyError" class="form-error" role="alert">{{ replyError }}</p>
          </footer>
        </div>
      </div>
    </section>
  </div>
</template>

<style>
/* 新建与详情使用页面滚动，窄屏时让顶栏和会议内容随可用宽度重排。 */
.platform-shell:has(:is(.booking-create-page, .meeting-detail-page)) {
  min-width: 0;
}
@media (max-width: 960px) {
  .platform-shell:has(:is(.booking-create-page, .meeting-detail-page)) > .topbar {
    grid-template-columns: minmax(0, 1fr);
    gap: 8px;
  }
  .platform-shell:has(:is(.booking-create-page, .meeting-detail-page)) > .topbar > * {
    grid-column: 1;
    grid-row: auto;
    min-width: 0;
  }
  .platform-shell:has(:is(.booking-create-page, .meeting-detail-page)) > .topbar > .account-actions {
    justify-self: start;
    flex-wrap: wrap;
    white-space: normal;
  }
}
@media (max-width: 640px) {
  .platform-shell:has(:is(.booking-create-page, .meeting-detail-page)) {
    --page-gutter: 12px;
  }
  .platform-shell:has(:is(.booking-create-page, .meeting-detail-page)) > .topbar {
    position: static;
  }
}
.material-results {
  display: grid;
  gap: 5px;
  padding-top: 6px;
}
.material-results summary {
  cursor: pointer;
  color: var(--brand);
}

.meeting-page:not(.room-management, .meeting-create, .meeting-detail-page) {
  max-width: 1180px;
  width: 100%;
  margin-inline: auto;
}
.meeting-page-title,
.meeting-page-title + .meeting-actions .primary {
  display: flex;
  align-items: center;
  gap: 10px;
}
.meeting-page-title h1 {
  font-size: clamp(21px, 3vw, 25px);
}
.booking-list-head {
  border-bottom: 1px solid var(--line);
  justify-content: space-between;
  flex-wrap: wrap;
}
.booking-count,
.booking-list-note {
  color: var(--text-secondary);
  font-size: 13px;
}
.booking-list-note {
  padding: 12px 20px;
}
.booking-row {
  display: grid;
  grid-template-columns: 94px minmax(0, 1fr) auto 16px;
  gap: 16px;
  align-items: center;
  padding: 16px 20px;
  color: inherit;
  text-decoration: none;
}
.booking-row + .booking-row {
  border-top: 1px solid var(--line);
}
.booking-row:hover,
.booking-row:focus-visible {
  background: var(--surface-muted);
}
.booking-date {
  display: inline-grid;
  justify-items: center;
  align-content: center;
  padding: 6px 8px;
  border-radius: 8px;
  background: var(--brand-soft);
  color: var(--brand-hover);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.booking-date small { color: var(--text-secondary); font-size: 11px; }
.booking-title strong { font-size: 1.08rem; line-height: 1.5; }
.booking-copy,
.booking-title {
  min-width: 0;
}
.booking-title,
.booking-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px 10px;
  overflow-wrap: anywhere;
}
.booking-meta {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-secondary);
}
.booking-meta span {
  display: inline-flex;
  gap: 5px;
  align-items: center;
}
.booking-badge {
  display: inline-flex;
  align-items: center;
  justify-self: start;
  padding: 3px 9px;
  border-radius: 999px;
  color: var(--text-secondary);
  background: var(--surface-muted);
  font-size: 12px;
  line-height: 1.5;
  white-space: nowrap;
}
.booking-badge[data-state="SCHEDULED"],
.booking-badge[data-state="IN_PROGRESS"] {
  background: var(--brand-soft);
  color: var(--brand);
}
.booking-badge.urgent,
.booking-badge[data-state="AWAITING_MINUTES"],
.booking-badge[data-state="FOLLOW_UP"] {
  background: var(--warning-soft);
  color: var(--warning);
}
.booking-badge[data-state="CLOSED"] { background: var(--success-soft); color: var(--success); }
.booking-chevron {
  color: var(--text-secondary);
}
.booking-list-footer {
  border-top: 1px solid var(--line);
  padding: 12px 20px;
  justify-content: flex-end;
}
.booking-list-footer > :first-child {
  margin-right: auto;
}
.task-panel {
  padding: 20px;
}
.task-panel h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
}
.task-panel .meeting-history {
  padding: 0 14px;
  border-radius: 8px;
  background: var(--surface-muted);
}
.meeting-page .booking-person {
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
}
.booking-person-name {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
}
.booking-avatar {
  display: inline-grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--brand-soft);
  color: var(--brand);
}
@media (max-width: 620px) {
  .booking-row {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 8px;
    padding: 12px;
  }
  .booking-date {
    justify-self: start;
    grid-row: 1;
    display: inline-flex;
    align-items: baseline;
    gap: 6px;
  }
  .booking-copy {
    grid-column: 1 / -1;
    grid-row: 2;
  }
  .booking-chevron {
    display: none;
  }
}

.meeting-page.room-management {
  max-width: 1080px;
  width: 100%;
  margin-inline: auto;
}
.room-management .room-action {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.room-panel {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 12px;
  overflow: hidden;
}
.room-panel-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 20px;
}
.room-panel-head h2 {
  font-size: 16px;
}
.room-panel-head p {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-secondary);
}
.room-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
}
.room-table th,
.room-table td {
  padding: 12px 20px;
  border-top: 1px solid var(--line);
}
.room-table th {
  background: var(--surface-muted);
  color: var(--text-secondary);
  font-size: 13px;
}
.room-table th:last-child,
.room-table td:last-child {
  text-align: right;
}
.room-table th:nth-child(n + 2),
.room-table td:nth-child(n + 2) {
  white-space: nowrap;
  width: 1%;
}
.room-table td:first-child {
  font-weight: 600;
  overflow-wrap: anywhere;
}
.room-table .secondary {
  min-height: 32px;
}
.room-state {
  display: inline-block;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--surface-muted);
  color: var(--text-secondary);
}
.room-state.enabled {
  background: var(--brand-soft);
  color: var(--brand);
}
.room-panel-footer {
  padding: 12px 20px;
  border-top: 1px solid var(--line);
  color: var(--text-secondary);
  font-size: 13px;
}
.meeting-page label.room-status-check {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  margin: 0;
}
.room-status-check input {
  margin: 0;
  accent-color: var(--brand);
}
@media (max-width: 520px) {
  .room-table th,
  .room-table td {
    padding-inline: 10px;
  }
}

.meeting-detail-page {
  width: 100%;
  max-width: 1440px;
  min-width: 0;
  margin-inline: auto;
}
.meeting-detail-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  justify-self: start;
  color: var(--brand);
  font-size: 14px;
}
.meeting-detail-back[aria-disabled="true"] {
  color: var(--text-secondary);
  cursor: wait;
}
.meeting-detail {
  container: meeting-detail / inline-size;
  min-width: 0;
}
.meeting-detail-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px 24px;
  margin-bottom: 20px;
}
.meeting-detail-header .detail-heading {
  flex: 1 1 280px;
  min-width: 0;
}
.detail-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  min-width: 0;
}
.meeting-detail-header h1 {
  font-size: clamp(22px, 2.2vw, 30px);
  overflow-wrap: anywhere;
}
.meeting-detail-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.meeting-detail-body > :is(.flow-dialog-section, .meeting-section) {
  padding: clamp(14px, 1.5vw, 22px);
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  min-width: 0;
}
.meeting-detail-body > .flow-dialog-section > :is(h2, h3),
.detail-attendees > header > h2 {
  font-size: 16px;
}
.meeting-detail-body :is(#meeting-detail-error, #meeting-minutes-editor, #meeting-material-legacy, #meeting-follow-up, #meeting-reply, .material-form) {
  scroll-margin-block-start: 92px;
}
.detail-workbook :is(a, button),
.detail-actions button,
.detail-attendees header button {
  white-space: nowrap;
}
.detail-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
  margin: 0;
}
.detail-period {
  grid-column: 1 / -1;
}
.detail-facts dt {
  color: var(--text-secondary);
  font-size: 12px;
}
.detail-facts dd {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
}
.detail-overview > :is(.meeting-text, .detail-workbook) {
  padding-top: 12px;
  border-top: 1px solid var(--line);
}
@container meeting-detail (min-width: 820px) {
  .detail-facts {
    grid-template-columns: minmax(0, 2fr) repeat(2, minmax(0, 1fr));
  }
  .detail-period {
    grid-column: auto;
  }
}
.meeting-page {
  display: grid;
  gap: 16px;
  align-content: start;
}
.meeting-page h1,
.meeting-page h2,
.meeting-page h3,
.meeting-page p {
  margin: 0;
}
.meeting-page .meeting-section {
  display: grid;
  gap: 12px;
  align-content: start;
}
.meeting-page .surface {
  padding: 18px;
}
.meeting-page .meeting-actions,
.meeting-page .meeting-checks {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.meeting-page .meeting-spread {
  justify-content: space-between;
}
.meeting-page .meeting-form-row {
  display: flex;
  gap: 12px;
  align-items: end;
  flex-wrap: wrap;
}
.meeting-page label {
  display: grid;
  gap: 5px;
}
.meeting-page .meeting-checks label,
.meeting-page label.meeting-inline,
.meeting-page .meeting-attendees label {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}
.meeting-page input[type="checkbox"] {
  width: auto;
}
.meeting-page textarea {
  resize: vertical;
}
.meeting-page .meeting-person-panel,
.meeting-page .meeting-fieldset {
  max-width: 100%;
  border: 1px solid var(--line);
  padding: 12px;
  margin-top: 8px;
  border-radius: 8px;
  display: grid;
  gap: 10px;
}
.meeting-page .meeting-person-results {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.meeting-page .meeting-attendees,
.meeting-page .meeting-history {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 8px;
}
.meeting-page .meeting-attendees li {
  display: flex;
  align-items: center;
  gap: 10px;
}
.meeting-page .meeting-attendees li > span {
  min-width: 160px;
}
.meeting-page .meeting-history li {
  padding-block: 8px;
  border-bottom: 1px solid var(--line);
}
.meeting-page .meeting-history small {
  margin-block: 3px;
  display: block;
}
.meeting-page .meeting-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.meeting-page .meeting-section > button {
  justify-self: start;
}
</style>

<style scoped>
.meeting-follow-up-owner + .meeting-follow-up-owner {
  margin-block-start: 20px;
}
.meeting-follow-up-owner h4 {
  margin: 0 0 8px;
  font-size: 15px;
}
.meeting-follow-up-group + .meeting-follow-up-group {
  margin-block-start: 12px;
}
.meeting-follow-up-group h5 {
  margin: 0 0 4px;
  color: var(--text-secondary);
  font-size: 13px;
}
/* 操作入口位于事项右侧，窄屏时自然换行。 */
.meeting-follow-up-list > li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.meeting-follow-up-list > li.own-follow-up {
  padding-inline: 12px;
  margin-block: 4px;
  background: var(--brand-soft);
  border: 0;
  border-inline-start: 3px solid var(--brand);
  border-radius: 6px;
}
.meeting-follow-up-copy {
  flex: 1 1 260px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.meeting-tracker-line,
.meeting-follow-up-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.meeting-reply-footer {
  display: grid;
  justify-items: center;
  gap: 12px;
  padding-block: 12px;
}
.meeting-reply-footer > .meeting-actions {
  justify-content: center;
}
.meeting-decline-form {
  width: min(100%, 600px);
  display: grid;
  gap: 12px;
}
.meeting-decline-form > button {
  justify-self: center;
}
</style>
