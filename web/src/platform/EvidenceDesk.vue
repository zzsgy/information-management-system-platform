<script setup lang="ts">
import ListFilters from "./ListFilters.vue";
import { recentWeekFilters } from "./listDates";
import Icon from "../preview/PlatformIcon.vue";
import { computed, inject, onMounted, onUnmounted, ref } from "vue";
import { api, dateTime, mutate, query } from "./api";
import CatalogChangeReview, {
  type CatalogChange,
} from "./CatalogChangeReview.vue";
import {
  evidenceBlob,
  evidenceStates,
  type EvidenceFile,
  type EvidenceSubmission,
} from "./evidence";
/** 核验页面复用全局再验证及结果提示；口令不进入页面持久状态。 */
const askPassword =
    inject<(title: string) => Promise<string | null>>("askPassword")!,
  inform = inject<(message: string) => void>("inform")!;
/** 数据库分页、选择对象和加载代次，防止较早响应覆盖较新的选择。 */
const items = ref<EvidenceSubmission[]>([]),
  total = ref(0),
  page = ref(1),
  state = ref("PENDING"),
  loading = ref(false),
  busy = ref(false),
  error = ref(""),
  selected = ref<EvidenceSubmission | null>(null),
  dialog = ref<HTMLDialogElement | null>(null);
/** 真实人工核验说明；勾选只是操作者声明，不能替代服务器的对象与审批校验。 */
const confirmed = ref(false),
  comment = ref(""),
  reason = ref(""),
  opened = ref<string[]>([]);
let generation = 0,
  alive = true;
const duties: Record<string, string> = {
  DEPARTMENT_MANAGER: "部门负责人",
  GENERAL_MANAGER: "总经理",
};
const actions: Record<string, string> = {
  VIEW: "查看",
  DOWNLOAD: "下载",
  PRINT: "打印",
  BORROW_PHYSICAL: "纸质借阅",
};
/** 范围表仅使用本次提交的固定快照，不额外读取档案内容。 */
const scopeItems = computed(() =>
  Array.isArray(selected.value?.scope.items)
    ? (selected.value!.scope.items as Record<string, unknown>[])
    : [],
);
/** 目录更正采用与在线审批相同的前后值展示，不把事项目的摘要当成全部范围。 */
const catalogChange = computed(
  () => selected.value?.scope.catalogChange as CatalogChange | undefined,
);
const period = computed(
  () =>
    selected.value?.scope.period as
      | { mode?: string; amount?: number }
      | undefined,
);
/** 有界分页和筛选只读取核验队列摘要。 */
async function load() {
  const current = ++generation;
  loading.value = true;
  error.value = "";
  try {
    const result = await api<{ items: EvidenceSubmission[]; total: number }>(
      `/admin/workflow/evidence?${query({ state: state.value, page: page.value, pageSize: 20, ...listFilters.value })}`,
    );
    if (alive && current === generation) {
      items.value = result.items;
      total.value = result.total;
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    if (current === generation) loading.value = false;
  }
}
/** 切换提交记录清空旧核验声明及已读取清单，防止跨对象复用确认。 */
async function open(item: Pick<EvidenceSubmission, "id">) {
  error.value = "";
  try {
    selected.value = await api<EvidenceSubmission>(
      `/workflow/evidence-submissions/${item.id}`,
    );
    confirmed.value = false;
    comment.value = "";
    reason.value = "";
    opened.value = [];
    dialog.value?.showModal();
  } catch (e) {
    error.value = (e as Error).message;
  }
}
/** 原件逐次鉴权并校验摘要后保存；对象 URL 随即销毁，不生成公共分享链接。 */
async function download(item: EvidenceFile) {
  busy.value = true;
  error.value = "";
  try {
    const blob = await evidenceBlob(item);
    if (!alive) return;
    const url = URL.createObjectURL(blob),
      link = document.createElement("a");
    link.href = url;
    link.download = item.originalName;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    opened.value = [...new Set([...opened.value, item.id])];
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 补录只绑定当前节点，核验退回只结束凭据；均要求明确理由和本人再验证。 */
async function decide(approve: boolean) {
  const item = selected.value;
  if (!item || busy.value) return;
  error.value = "";
  if (
    !reason.value.trim() ||
    (approve &&
      (!comment.value.trim() ||
        !confirmed.value ||
        opened.value.length !== item.evidence.length))
  ) {
    error.value = "请查看全部原件，并填写核验意见、原因和同意范围确认";
    return;
  }
  const password = await askPassword(
    approve ? "核验已有同意并补录当前节点" : "退回审批凭据",
  );
  if (!password) return;
  busy.value = true;
  try {
    await mutate(
      approve
        ? `/workflow/steps/${item.stepId}/evidence-decisions`
        : `/workflow/evidence-submissions/${item.id}/reject`,
      "POST",
      approve
        ? {
            evidenceIds: item.evidence.map((file) => file.id),
            originalApproverId: item.originalApproverId,
            agreedAt: item.agreedAt,
            comment: comment.value,
            reason: reason.value,
          }
        : { reason: reason.value },
      item.stepId,
      "WORKFLOW_STEP",
      approve ? "EVIDENCE_RECORD" : "REJECT_EVIDENCE",
      password,
      approve ? item.currentStepVersion : item.rowVersion,
    );
    dialog.value?.close();
    inform(
      approve
        ? "当前节点已补录，后续审批按原路线继续"
        : "凭据已退回，业务审批保持原状态",
    );
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 从首页进入仍通过凭据详情API验证当前IT处理权。 */
onMounted(async () => {
  await load();
  const id = new URLSearchParams(location.hash.split("?")[1] || "").get("id");
  if (id) await open({ id });
});
onUnmounted(() => {
  alive = false;
  generation++;
});
/** 当前列表已提交的筛选条件。 */
const listFilters = ref(recentWeekFilters("created"));
</script>
<template>
  <section class="evidence-desk">
    <header class="continuity-heading">
      <div>
        <span class="eyebrow">审批连续性</span>
        <h1>凭据核验与补录</h1>
        <p class="muted">
          核验已有的真实同意，保留原同意人、实际经办人和完整原件。
        </p>
      </div>
    </header>
    <div class="panel">
      <div class="continuity-toolbar">
        <label class="list-filter-field"
          >办理状态<select
            v-model="state"
            @change="
              page = 1;
              load();
            "
          >
            <option
              v-for="(name, code) in evidenceStates"
              :key="code"
              :value="code"
            >
              {{ name }}
            </option>
          </select></label
        >
      </div>
      <ListFilters
        :fields="[
          { key: 'applicant', label: '申请人', placeholder: '姓名或工号' },
          { key: 'approver', label: '原同意人', placeholder: '姓名或工号' },
          { key: 'dutyCode', label: '必要节点', options: duties },
          { key: 'created', label: '提交日期', type: 'date' },
        ]"
        :values="listFilters"
        :busy="loading"
        @apply="
          listFilters = $event;
          page = 1;
          load();
        "
      />
      <div class="table-scroll">
        <table class="data-table">
          <thead>
            <tr>
              <th>申请人</th>
              <th>原同意人</th>
              <th>必要节点</th>
              <th>提交时间</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id">
              <td>{{ item.applicantName }}</td>
              <td>{{ item.approverName }}</td>
              <td>{{ duties[item.dutyCode] }}</td>
              <td>{{ dateTime(item.createdAt) }}</td>
              <td>{{ evidenceStates[item.state] }}</td>
              <td>
                <button class="text-button" @click="open(item)">
                  {{ item.state === "PENDING" ? "核验凭据" : "查看记录" }}
                </button>
              </td>
            </tr>
            <tr v-if="!items.length">
              <td colspan="6" class="empty">
                {{ loading ? "正在加载…" : "暂无此状态的凭据记录" }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="list-footer">
        <button
          type="button"
          class="secondary list-refresh"
          :disabled="loading"
          @click="load()"
        >
          刷新列表
        </button>
        <div class="list-pagination">
          <button
            class="secondary"
            :disabled="page === 1 || loading"
            @click="
              page--;
              load();
            "
          >
            上一页</button
          ><span>第 {{ page }} 页</span
          ><button
            class="secondary"
            :disabled="page * 20 >= total || loading"
            @click="
              page++;
              load();
            "
          >
            下一页
          </button>
          <span class="list-total">共 {{ total }} 项</span>
        </div>
      </footer>
    </div>
    <p v-if="error && !dialog?.open" class="form-error" role="alert">
      {{ error }}
    </p>
    <dialog
      ref="dialog"
      aria-labelledby="evidence-review-title"
      class="form-dialog flow-dialog evidence-detail"
      @cancel="busy && $event.preventDefault()"
    >
      <div v-if="selected" class="flow-dialog-shell">
        <header class="flow-dialog-header">
          <span class="flow-dialog-icon"><Icon name="file" :size="22" /></span>
          <div class="flow-dialog-title">
            <span class="eyebrow"
              >{{ duties[selected.dutyCode] }} ·
              {{ evidenceStates[selected.state] }}</span
            >
            <h2 id="evidence-review-title">
              {{ selected.applicantName }} 的审批凭据
            </h2>
          </div>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭凭据详情"
            :disabled="busy"
            @click="dialog?.close()"
          >
            ×
          </button>
        </header>
        <div class="flow-dialog-body">
          <section class="flow-dialog-overview">
            <h3>审批概况</h3>
            <div class="evidence-facts">
              <div>
                <small>原同意人</small
                ><strong>{{ selected.approverName }}</strong>
              </div>
              <div>
                <small>实际同意时间</small
                ><strong>{{ dateTime(selected.agreedAt) }}</strong>
              </div>
              <div>
                <small>申请期限</small
                ><strong>{{
                  period
                    ? `${period.amount} ${period.mode === "WORKDAYS" ? "个工作日" : "天"}`
                    : "按固定申请范围"
                }}</strong>
              </div>
            </div>
          </section>
          <section class="flow-dialog-section">
            <h3>本次同意范围</h3>
            <p>{{ selected.description }}</p>
            <p v-if="selected.scope.title">{{ selected.scope.title }}</p>
            <table v-if="scopeItems.length" class="data-table">
              <thead>
                <tr>
                  <th>固定文件或实物</th>
                  <th>动作</th>
                  <th>版本及范围</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(item, index) in scopeItems" :key="index">
                  <td>
                    {{
                      item.label ||
                      item.title ||
                      item.physicalItemId ||
                      item.recordId
                    }}
                  </td>
                  <td>
                    {{
                      actions[
                        String(
                          item.action ||
                            (selected.scope.form === "PAPER"
                              ? "BORROW_PHYSICAL"
                              : "VIEW"),
                        )
                      ] || item.action
                    }}
                  </td>
                  <td>
                    <details>
                      <summary>查看固定标识</summary>
                      <small
                        >文件：{{ item.recordId || item.physicalItemId
                        }}<br />版本：{{
                          item.pdfVersionId ||
                          item.recordRevisionId ||
                          "纸质原件"
                        }}<br v-if="item.printPages" /><span
                          v-if="item.printPages"
                          >页码：{{ item.printPages }} ·
                          {{ item.copies }}份</span
                        ></small
                      >
                    </details>
                  </td>
                </tr>
              </tbody>
            </table>
            <CatalogChangeReview v-if="catalogChange" :change="catalogChange" />
            <div v-if="selected.scope.departmentNames" class="policy-scope">
              <p>
                可见部门：{{
                  (selected.scope.departmentNames as string[]).join("、") ||
                  "无"
                }}
              </p>
              <p>
                指定阅读人员：{{
                  (selected.scope.userNames as string[]).join("、") || "无"
                }}
              </p>
              <p>
                本部门基础阅读：{{
                  selected.scope.allowOwnerDepartmentView ? "开放" : "不开放"
                }}
              </p>
              <p>
                范围有效期：{{
                  selected.scope.validFrom
                    ? dateTime(String(selected.scope.validFrom))
                    : "未指定"
                }}
                至
                {{
                  selected.scope.validUntil
                    ? dateTime(String(selected.scope.validUntil))
                    : "长期"
                }}
              </p>
              <details>
                <summary>查看固定文件与策略</summary>
                <p>
                  文件：{{ selected.scope.objectId }}<br />目录版本：{{
                    selected.scope.recordRevisionId
                  }}<br />PDF版本：{{ selected.scope.pdfVersionId
                  }}<br />题名策略：{{ selected.scope.titleVisibility }}
                </p>
              </details>
            </div>
          </section>
          <section class="flow-dialog-section">
            <h3>原始凭据</h3>
            <div
              class="attachment-row"
              v-for="file in selected.evidence"
              :key="file.id"
            >
              <div>
                <strong>{{ file.originalName }}</strong
                ><small
                  >{{ (file.size / 1024).toFixed(1) }} KB ·
                  {{ file.format }}</small
                >
              </div>
              <button
                class="secondary"
                :disabled="busy"
                @click="download(file)"
              >
                {{ opened.includes(file.id) ? "再次查看原件" : "下载核验原件" }}
              </button>
            </div>
          </section>
          <section
            v-if="selected.state === 'PENDING'"
            class="flow-dialog-section review-fields"
          >
            <h3>核验结果</h3>
            <label
              >核验意见<textarea
                v-model="comment"
                maxlength="2000"
                rows="2"
                placeholder="同意人、文件版本、使用动作和期限的核对结论"
              /></label
            ><label
              >补录或退回原因<textarea
                v-model="reason"
                maxlength="1000"
                rows="2"
              /></label
            ><label class="confirmation"
              ><input
                v-model="confirmed"
                type="checkbox"
              />我已核对全部原件，确认是该同意人对本次固定范围已有的真实同意。</label
            >
            <p class="muted">
              补录仅完成当前节点；不得覆盖驳回、跳过必要节点或替申请人同意。
            </p>
          </section>
          <p v-else>{{ selected.reviewNote }}</p>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        </div>
        <footer class="flow-dialog-footer">
          <button
            type="button"
            class="secondary"
            :disabled="busy"
            @click="dialog?.close()"
          >
            关闭
          </button>
          <template v-if="selected.state === 'PENDING'">
            <button class="secondary" :disabled="busy" @click="decide(false)">
              核验退回</button
            ><button
              class="primary"
              :disabled="
                busy || !confirmed || opened.length !== selected.evidence.length
              "
              @click="decide(true)"
            >
              确认补录当前节点
            </button>
          </template>
        </footer>
      </div>
    </dialog>
  </section>
</template>
<style scoped>
/* 电脑端列表和核验面板，名称与说明换行而不挤压操作列。 */
.continuity-heading,
.continuity-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 24px;
}
.continuity-heading h1 {
  margin: 7px 0 12px;
}
.continuity-toolbar {
  flex-wrap: wrap;
  padding: 16px 20px;
  margin: 0;
}
.continuity-toolbar label {
  display: flex;
  align-items: center;
  gap: 12px;
}
.evidence-detail {
  --flow-dialog-width: 880px;
}
.evidence-facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 150px), 1fr));
  gap: 18px;
  background: var(--surface-muted);
  padding: 20px;
  border-radius: 10px;
}
.evidence-facts small,
.attachment-row small {
  display: block;
  color: var(--text-secondary);
  margin-bottom: 7px;
}
.evidence-detail h3 {
  margin: 26px 0 12px;
}
.attachment-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  border: 1px solid var(--line);
  border-radius: 9px;
  padding: 14px;
  margin: 9px 0;
  gap: 18px;
}
.attachment-row strong {
  overflow-wrap: anywhere;
}
.review-fields {
  display: grid;
  gap: 20px;
  margin-top: 24px;
}
.review-fields label {
  display: grid;
  gap: 8px;
}
.review-fields .confirmation {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  gap: 10px;
}
.confirmation input {
  width: 16px;
  height: 16px;
  min-width: 16px;
  padding: 0;
  margin-top: 4px;
}
.data-table small {
  overflow-wrap: anywhere;
}
.empty {
  text-align: center;
  padding: 48px !important;
  color: var(--text-secondary);
}
</style>
