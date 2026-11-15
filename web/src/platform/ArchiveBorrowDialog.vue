<script setup lang="ts">
import { computed, inject, ref } from "vue";
import Icon from "../preview/PlatformIcon.vue";
import { api, query } from "./api";

/** 申请对象来自当前获权目录，送审时由服务器确定实际成员。 */
export interface BorrowTarget {
  objectType: "BOX" | "RECORD";
  objectId: string;
  label: string;
}
type Form = "ELECTRONIC" | "PAPER" | "MIXED";
interface Options {
  electronic: boolean;
  physicalItemId: string | null;
}
const borrowMethods: Record<Form, { label: string; description: string }> = {
  ELECTRONIC: { label: "电子", description: "获批后在线使用电子文件" },
  PAPER: { label: "纸质", description: "获批后向档案员领取原件，到期归还" },
  MIXED: { label: "电子+纸质", description: "同时申请电子文件和纸质原件" },
};
const inform = inject<(message: string) => void>("inform")!;
const dialog = ref<HTMLDialogElement>();
const targets = ref<BorrowTarget[]>([]);
const options = ref<Options[]>([]);
const form = ref<Form | "">("");
const actions = ref(["VIEW"]);
/** 无水印电子动作须送总经理审批。 */
const unwatermarked = ref(false);
const pages = ref("");
const copies = ref(1);
const purpose = ref("");
const mode = ref("WORKDAYS");
const days = ref(3);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
let operation = "";
const forms = computed<Form[]>(() => {
  if (!options.value.length) return [];
  const electronic = options.value.every((item) => item.electronic);
  const paper = options.value.every((item) => item.physicalItemId);
  const result: Form[] = [];
  if (electronic) result.push("ELECTRONIC");
  if (paper) result.push("PAPER");
  if (electronic && paper) result.push("MIXED");
  return result;
});

/** 单行读取当前可借载体，原有批量入口继续提交电子申请。 */
async function open(items: BorrowTarget[], electronicOnly = false) {
  targets.value = items;
  options.value = [];
  form.value = "";
  actions.value = ["VIEW"];
  unwatermarked.value = false;
  pages.value = "";
  copies.value = 1;
  purpose.value = "";
  mode.value = "WORKDAYS";
  days.value = 3;
  operation = crypto.randomUUID();
  error.value = "";
  loading.value = true;
  dialog.value?.showModal();
  try {
    options.value = electronicOnly
      ? items.map(() => ({ electronic: true, physicalItemId: null }))
      : await Promise.all(
          items.map((item) =>
            api<Options>(
              `/archive/borrow-options?${query({ objectType: item.objectType, objectId: item.objectId })}`,
            ),
          ),
        );
    form.value = forms.value[0] || "";
    if (!form.value) error.value = "当前没有可申请的借阅方式。";
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    loading.value = false;
  }
}

/** 混合申请一次提交，电子授权与实际纸质交接仍分别处理。 */
async function submit() {
  if (!form.value) return;
  busy.value = true;
  error.value = "";
  try {
    await api("/archive/borrow-batches", {
      method: "POST",
      headers: { "X-Operation-Id": operation },
      body: JSON.stringify({
        form: form.value,
        actions: form.value === "PAPER" ? ["BORROW_PHYSICAL"] : actions.value,
        ...(form.value !== "PAPER" && unwatermarked.value ? { unwatermarked: true } : {}),
        purpose: purpose.value,
        items:
          form.value === "PAPER"
            ? options.value.map((item) => ({
                physicalItemId: item.physicalItemId,
              }))
            : targets.value.map(({ objectType, objectId }) => ({
                objectType,
                objectId,
                actions: actions.value,
                ...(actions.value.includes("PRINT")
                  ? {
                      pageRange: pages.value || undefined,
                      copies: copies.value,
                    }
                  : {}),
              })),
        period: { mode: mode.value, amount: days.value },
      }),
    });
    dialog.value?.close();
    emit("submitted");
    inform("借阅申请已提交，可在流程中心查看进度。");
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
const emit = defineEmits<{ submitted: [] }>();
defineExpose({ open });
</script>

<template>
  <dialog
    ref="dialog"
    class="form-dialog flow-dialog flow-dialog-compact"
    aria-labelledby="archive-borrow-title"
    @cancel="(busy || loading) && $event.preventDefault()"
  >
    <form class="flow-dialog-shell" @submit.prevent="submit">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="file" :size="22" /></span>
        <h2 id="archive-borrow-title">申请借阅</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭借阅申请"
          :disabled="busy || loading"
          @click="dialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-overview">
          <h3>申请范围</h3>
          <p>
            {{
              targets.length === 1
                ? targets[0]?.label
                : `已选 ${targets.length} 份文件`
            }}
          </p>
        </section>
        <p v-if="loading" class="muted" role="status">正在核对可借方式…</p>
        <fieldset v-if="!loading && options.length" class="borrow-methods">
          <legend>借阅方式</legend>
          <label
            v-for="(method, value) in borrowMethods"
            :key="value"
            :class="{ 'is-selected': form === value }"
          >
            <input
              v-model="form"
              type="radio"
              name="borrow-method"
              :value="value"
              :disabled="busy || !forms.includes(value)"
            />
            <span>
              <strong>{{ method.label }}</strong>
              <small class="muted">{{ method.description }}</small>
            </span>
            <small v-if="!forms.includes(value)" class="muted unavailable">当前不可选</small>
          </label>
        </fieldset>
        <section v-if="form" class="flow-dialog-section">
          <p v-if="form === 'MIXED'" class="muted">
            电子和纸质分别审批，可在「我的借阅」查看两笔申请。
          </p>
          <p v-if="form !== 'ELECTRONIC'" class="muted">
            纸质末级批准后立即占用并开始计时，取件时由档案超级管理员和所属部门档案员共同确认。
          </p>
          <fieldset v-if="form !== 'PAPER'" class="borrow-actions">
            <legend>电子使用权限（分别批准）</legend>
            <label
              ><input
                v-model="actions"
                type="checkbox"
                value="VIEW"
              />查看</label
            >
            <label
              ><input
                v-model="actions"
                type="checkbox"
                value="DOWNLOAD"
              />下载</label
            >
            <label
              ><input
                v-model="actions"
                type="checkbox"
                value="PRINT"
              />打印</label
            >
          </fieldset>
          <label v-if="form !== 'PAPER'" class="unwatermarked-choice">
            <input v-model="unwatermarked" type="checkbox" />
            申请无水印电子版（所选动作须经总经理审批）
          </label>
          <div
            v-if="form !== 'PAPER' && actions.includes('PRINT')"
            class="flow-dialog-fields"
          >
            <label
              >打印页码<input
                v-model="pages"
                maxlength="2000"
                placeholder="留空为全部页，如 1-3,5"
            /></label>
            <label
              >累计打印份数<input
                v-model.number="copies"
                type="number"
                min="1"
                max="100"
                required
            /></label>
          </div>
          <label
            >实际业务用途<textarea
              v-model="purpose"
              maxlength="2000"
              required
              rows="3"
            />
          </label>
          <div class="flow-dialog-fields">
            <label
              >期限规则<select v-model="mode">
                <option value="WORKDAYS">工作日</option>
                <option value="NATURAL_DAYS">自然日</option>
              </select></label
            >
            <label
              >期限天数<input
                v-model.number="days"
                type="number"
                min="1"
                max="30"
                required
            /></label>
          </div>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button
          type="button"
          class="secondary"
          :disabled="busy || loading"
          @click="dialog?.close()"
        >
          取消
        </button>
        <button
          class="primary"
          :disabled="
            loading || busy || !form || (form !== 'PAPER' && !actions.length)
          "
        >
          提交申请
        </button>
      </footer>
    </form>
  </dialog>
</template>

<style scoped>
.borrow-methods {
  display: grid;
  gap: 6px;
  padding: 0;
  margin: 0;
  min-width: 0;
  border: 0;
}
.borrow-methods legend {
  margin-bottom: 8px;
  padding: 0;
}
.borrow-methods label {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
}
.borrow-methods label.is-selected {
  border-color: var(--brand);
}
.borrow-methods input {
  width: auto;
  min-height: 0;
  margin: 0;
}
.borrow-methods span small {
  display: block;
}
.unavailable {
  white-space: nowrap;
}
.borrow-actions {
  display: flex;
  gap: 20px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  margin: 0;
}
.borrow-actions label {
  flex-direction: row;
  align-items: center;
}
.borrow-actions input {
  width: auto;
  min-height: 0;
  margin: 0;
}
.unwatermarked-choice {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
}
.unwatermarked-choice input {
  width: auto;
  min-height: 0;
  margin: 0;
}
</style>
