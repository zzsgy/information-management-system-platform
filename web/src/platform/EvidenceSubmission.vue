<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { api, dateTime } from "./api";
import { uploadArchivePdf } from "./archiveFiles";
import { evidenceStates, type EvidenceSubmission } from "./evidence";
/** 只接受本人子单当前节点；服务端负责重新判定允许的职责。 */
const props = defineProps<{ stepId: string }>();
/** 节点事实、已提交记录和上传状态；原件标识仅保存在当前页面内存。 */
const node = ref<{
    id: string;
    assigneeId: string;
    dutyCode: string;
    rowVersion: number;
    state: string;
  } | null>(null),
  items = ref<EvidenceSubmission[]>([]),
  supported = ref(false),
  busy = ref(false),
  error = ref(""),
  progress = ref("");
/** 日期由使用者填写实际同意时刻，不能自动把补录时间当成同意时间。 */
const agreedAt = ref(""),
  description = ref(""),
  selected = ref<File[]>([]),
  objectIds = ref<string[]>([]);
let alive = true,
  operation = crypto.randomUUID(),
  lastBody = "";
/** 读取最新节点和核验记录，页面卸载后不写回。 */
async function load() {
  try {
    const [detail, submissions] = await Promise.all([
      api<{ step: NonNullable<typeof node.value> }>(
        `/workflow/steps/${props.stepId}`,
      ),
      api<{ supported: boolean; items: EvidenceSubmission[] }>(
        `/workflow/steps/${props.stepId}/evidence`,
      ),
    ]);
    if (!alive) return;
    node.value = detail.step;
    supported.value = submissions.supported;
    items.value = submissions.items;
  } catch (e) {
    if (alive) error.value = (e as Error).message;
  }
}
/** 重新选件清空旧对象引用，避免同意说明与上一组附件混用。 */
function choose(event: Event) {
  selected.value = Array.from((event.target as HTMLInputElement).files || []);
  objectIds.value = [];
  error.value = selected.value.length > 10 ? "每次最多提交10份凭据" : "";
}
/** 顺序上传限制内存占用，全部检测成功后才创建凭据批次；提交失败保留操作号供重试。 */
async function submit() {
  if (busy.value || !node.value) return;
  busy.value = true;
  error.value = "";
  try {
    if (
      !agreedAt.value ||
      !description.value.trim() ||
      selected.value.length < 1 ||
      selected.value.length > 10
    )
      throw new Error("请填写实际同意时间、说明，并选择1至10份凭据");
    for (
      let index = objectIds.value.length;
      index < selected.value.length;
      index++
    ) {
      const upload = await uploadArchivePdf(
        props.stepId,
        selected.value[index]!,
        () => alive,
        (message) => {
          progress.value = `第${index + 1}份：${message}`;
        },
        "EVIDENCE",
      );
      const result = await api<{ objectId: string; state: string }>(
        `/storage/upload-sessions/${upload}`,
      );
      if (!alive) return;
      if (result.state !== "COMPLETE") throw new Error("凭据检测尚未完成");
      objectIds.value.push(result.objectId);
    }
    const body = JSON.stringify({
      originalApproverId: node.value.assigneeId,
      agreedAt: new Date(agreedAt.value).toISOString(),
      objectIds: objectIds.value,
      description: description.value,
    });
    if (body !== lastBody) {
      operation = crypto.randomUUID();
      lastBody = body;
    }
    await api(`/workflow/steps/${props.stepId}/evidence`, {
      method: "POST",
      headers: {
        "X-Operation-Id": operation,
        "X-Expected-Version": String(node.value.rowVersion),
      },
      body,
    });
    if (alive) {
      progress.value = "凭据已提交，等待 IT 核验；审批尚未自动通过。";
      await load();
    }
  } catch (e) {
    if (alive) error.value = (e as Error).message;
  } finally {
    if (alive) busy.value = false;
  }
}
/** 只撤回凭据，不撤回借阅；重新提交必须重新选择完整附件。 */
async function cancel(item: EvidenceSubmission) {
  busy.value = true;
  error.value = "";
  try {
    await api(`/workflow/evidence-submissions/${item.id}/cancel`, {
      method: "POST",
      headers: {
        "X-Operation-Id": crypto.randomUUID(),
        "X-Expected-Version": String(item.rowVersion),
      },
      body: JSON.stringify({ reason: "申请人撤回凭据后重新整理" }),
    });
    objectIds.value = [];
    selected.value = [];
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(load);
onUnmounted(() => {
  alive = false;
});
</script>
<template>
  <details v-if="supported" class="evidence-submit">
    <summary>已有线下同意，提交审批凭据</summary>
    <p class="muted">
      仅用于补录本节点已有的真实同意。请上传完整截图、PDF或录音，每份不超过50
      MB，最多10份。
    </p>
    <article v-for="item in items" :key="item.id" class="evidence-history">
      <strong>{{ evidenceStates[item.state] }}</strong
      ><span>{{ dateTime(item.createdAt) }} · {{ item.approverName }}</span>
      <p v-if="item.reviewNote">{{ item.reviewNote }}</p>
      <button
        v-if="item.state === 'PENDING'"
        type="button"
        class="text-button"
        :disabled="busy"
        @click="cancel(item)"
      >
        撤回凭据
      </button>
    </article>
    <div
      v-if="
        node?.state === 'PENDING' &&
        !items.some((item) => item.state === 'PENDING')
      "
      class="evidence-fields"
    >
      <label
        >实际同意时间<input
          v-model="agreedAt"
          type="datetime-local"
          :disabled="busy"
      /></label>
      <label
        >同意范围说明<textarea
          v-model="description"
          maxlength="2000"
          rows="3"
          placeholder="说明同意人、对应文件、使用动作及期限"
          :disabled="busy"
        />
      </label>
      <label
        >原始凭据<input
          type="file"
          accept=".pdf,.jpg,.jpeg,.png,.mp3,.m4a,.wav,.amr"
          multiple
          :disabled="busy"
          @change="choose"
      /></label>
      <button type="button" class="primary" :disabled="busy" @click="submit">
        {{ busy ? "正在处理…" : "提交凭据核验" }}
      </button>
    </div>
    <p v-if="progress" class="muted" role="status">{{ progress }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  </details>
  <p v-else-if="error" class="form-error" role="alert">{{ error }}</p>
</template>
<style scoped>
/* 电脑端子单内联操作保持清晰分区，避免额外弹窗层级。 */
.evidence-submit {
  flex-basis: 100%;
  width: 100%;
  padding: 16px 0 4px;
  border-top: 1px solid var(--line);
  margin-top: 12px;
}
.evidence-submit summary {
  cursor: pointer;
  color: var(--ink);
  font-weight: 650;
}
.evidence-fields {
  display: grid;
  gap: 20px;
  max-width: 680px;
  margin-top: 16px;
}
.evidence-fields label {
  display: grid;
  gap: 7px;
}
.evidence-fields .primary {
  justify-self: start;
}
.evidence-history {
  background: var(--surface-muted);
  border-radius: 8px;
  padding: 12px;
  margin: 10px 0;
}
.evidence-history span {
  margin-left: 15px;
  color: var(--text-secondary);
}
</style>
