<script setup lang="ts">
import { computed, nextTick, reactive, ref, useId } from "vue";
import { archiveLocation } from "./archiveChecklist";

/** 清单的一行对应一个实际可用的柜、列、层组合，编号沿用台账文本。 */
export interface ArchiveLocation {
  cabinetNo: string;
  columnNo: string;
  layerNo: string;
}

const props = defineProps<{
  /** 已确认的库位，留空时显示选择提示。 */
  modelValue: Record<string, unknown>;
  /** 固定库房清单，不从已有档案反推可用库位。 */
  locations: ArchiveLocation[];
  /** 纯电子盒的存放位置可留空。 */
  clearable?: boolean;
  /** 历史档案尚未拆分柜列层时显示原库位。 */
  fallback?: string;
}>();
const emit = defineEmits<{ "update:modelValue": [ArchiveLocation] }>();
const button = ref<HTMLButtonElement>();
const panel = ref<HTMLDivElement>();
const options = ref<HTMLDivElement>();
const panelId = useId();
/** 每次展示一个层级，已选择的上级保留在面板顶部。 */
const labels = { cabinetNo: "柜号", columnNo: "列号", layerNo: "层号" };
const step = ref<keyof ArchiveLocation>("cabinetNo");
const draft = reactive<ArchiveLocation>({
  cabinetNo: "",
  columnNo: "",
  layerNo: "",
});

const cabinets = computed(() => [
  ...new Set(props.locations.map((row) => row.cabinetNo)),
]);
const columns = computed(() => [
  ...new Set(
    props.locations
      .filter((row) => row.cabinetNo === draft.cabinetNo)
      .map((row) => row.columnNo),
  ),
]);
const layers = computed(() => [
  ...new Set(
    props.locations
      .filter(
        (row) =>
          row.cabinetNo === draft.cabinetNo && row.columnNo === draft.columnNo,
      )
      .map((row) => row.layerNo),
  ),
]);
const choices = computed(
  () =>
    ({
      cabinetNo: cabinets.value,
      columnNo: columns.value,
      layerNo: layers.value,
    })[step.value],
);
const summary = computed(() =>
  archiveLocation(props.modelValue, props.fallback || "请选择存放位置"),
);

/** 每次打开从柜号开始，保留已确认选项作为当前标记。 */
function restoreDraft(event: ToggleEvent) {
  if (event.newState !== "open") return;
  step.value = "cabinetNo";
  draft.cabinetNo = String(props.modelValue.cabinetNo ?? "");
  draft.columnNo = String(props.modelValue.columnNo ?? "");
  draft.layerNo = String(props.modelValue.layerNo ?? "");
}

/** 切换选项网格后将焦点移到当前选项或第一项。 */
async function moveTo(next: keyof ArchiveLocation) {
  step.value = next;
  await nextTick();
  (
    options.value?.querySelector<HTMLButtonElement>('[aria-pressed="true"]') ||
    options.value?.querySelector<HTMLButtonElement>("button")
  )?.focus();
}

/** 关闭选择器后回到表单字段。 */
function close() {
  panel.value?.hidePopover();
  button.value?.focus();
}

/** 改变上级会清除下级，选定最后一级时一次提交完整库位。 */
function choose(value: string) {
  if (step.value === "cabinetNo") {
    if (draft.cabinetNo !== value) {
      draft.columnNo = "";
      draft.layerNo = "";
    }
    draft.cabinetNo = value;
    void moveTo("columnNo");
  } else if (step.value === "columnNo") {
    if (draft.columnNo !== value) draft.layerNo = "";
    draft.columnNo = value;
    void moveTo("layerNo");
  } else {
    emit("update:modelValue", { ...draft, layerNo: value });
    close();
  }
}

/** 清空可选库位，三个原字段一并清除。 */
function clear() {
  emit("update:modelValue", { cabinetNo: "", columnNo: "", layerNo: "" });
  close();
}
</script>

<template>
  <div class="archive-location-picker">
    <button
      ref="button"
      type="button"
      class="secondary location-trigger"
      :aria-label="`存放位置，${summary}`"
      :popovertarget="panelId"
    >
      <span>{{ summary }}</span>
      <span aria-hidden="true">▾</span>
    </button>
    <div
      :id="panelId"
      ref="panel"
      class="location-panel"
      popover="auto"
      role="group"
      aria-label="选择存放位置"
      @beforetoggle="restoreDraft"
      @toggle="$event.newState === 'open' && moveTo(step)"
    >
      <div class="location-heading">
        <button
          v-if="step !== 'cabinetNo'"
          type="button"
          class="location-parent"
          :aria-label="`更改柜号，已选 ${draft.cabinetNo} 柜`"
          @click="moveTo('cabinetNo')"
        >
          {{ draft.cabinetNo }}柜 <span aria-hidden="true">▾</span>
        </button>
        <button
          v-if="step === 'layerNo'"
          type="button"
          class="location-parent"
          :aria-label="`更改列号，已选 ${draft.columnNo} 列`"
          @click="moveTo('columnNo')"
        >
          {{ draft.columnNo }}列 <span aria-hidden="true">▾</span>
        </button>
        <span class="location-step-label" aria-live="polite"
          >选择{{ labels[step] }}</span
        >
      </div>
      <div
        ref="options"
        class="location-options"
        role="group"
        :aria-label="labels[step]"
      >
        <button
          v-for="value in choices"
          :key="value"
          type="button"
          class="location-option"
          :aria-pressed="draft[step] === value"
          @click="choose(value)"
        >
          {{ value }}
        </button>
      </div>
      <div class="location-actions">
        <button
          v-if="clearable && modelValue.cabinetNo"
          type="button"
          class="text-button"
          @click="clear"
        >
          清空
        </button>
        <button type="button" class="text-button" @click="close">取消</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.archive-location-picker {
  min-width: 0;
}
.location-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  min-height: 42px;
  text-align: left;
}
.location-panel {
  width: min(272px, calc(100vw - 56px));
  max-height: min(320px, calc(100dvh - 24px));
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface, white);
  color: var(--ink);
  box-shadow: 0 8px 24px #182f4526;
}
/* 使用触发按钮作为锚点，弹层可越过表单滚动区域并在靠近底部时翻转。 */
@supports (position-area: block-end) {
  .location-panel {
    inset: auto;
    margin: 6px 0;
    position-area: block-end span-inline-end;
    justify-self: start;
    position-try-fallbacks:
      flip-block,
      flip-inline,
      flip-block flip-inline;
  }
}
.location-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 32px;
  margin-bottom: 8px;
}
.location-step-label {
  font-weight: 600;
}
.location-parent,
.location-option {
  border: 0;
  border-radius: 6px;
  color: inherit;
  font-weight: 600;
}
.location-parent {
  min-height: 30px;
  padding: 4px 8px;
  color: var(--brand);
  background: var(--brand-soft);
}
.location-options {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 4px;
}
.location-option {
  min-height: 34px;
  padding: 4px;
  background: transparent;
}
.location-option:hover,
.location-parent:hover {
  background: var(--surface-muted);
}
.location-option[aria-pressed="true"] {
  background: var(--brand);
  color: var(--brand-contrast);
}
.location-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 8px;
  border-top: 1px solid var(--line);
}
</style>
