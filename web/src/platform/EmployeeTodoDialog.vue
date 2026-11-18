<script setup lang="ts">
import RichTextView from "./RichTextView.vue";
import RichTextEditor from "./RichTextEditor.vue";
import Icon from "../preview/PlatformIcon.vue";
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { api, profile, query } from "./api";
import { portalTime } from "./portal";

/** 当前事项及分派人、接收人都由服务端授权后返回。 */
interface Todo {
  id: string;
  title: string;
  description: string;
  creatorId: string;
  assigneeId: string;
  creatorName: string;
  assigneeName: string;
  state: string;
  completionNote: string;
  submittedAt: string | null;
  completedAt: string | null;
  /** 可选提醒的绝对时刻。 */
  remindAt: string | null;
  rowVersion: number;
}
/** 分派范围内的人员及所属部门，用于联动两种选择方式。 */
interface Person {
  id: string;
  name: string;
  employeeNo: string;
  departmentId: string;
}
/** 分派人可选的组织部门。 */
interface Department {
  id: string;
  name: string;
}
const props = defineProps<{ id?: string; assign?: boolean }>();
const emit = defineEmits<{ close: []; changed: [] }>();
const dialog = ref<HTMLDialogElement | null>(null);
const todo = ref<Todo>();
const title = ref(""),
  description = ref(""),
  reminderTime = ref(""),
  recipient = ref<Person>(),
  note = ref("");
const editing = ref(!props.id),
  busy = ref(false),
  error = ref("");
const departments = ref<Department[]>([]),
  departmentId = ref(""),
  people = ref<Person[]>([]),
  peoplePage = ref(1),
  hasNext = ref(false),
  loadingPeople = ref(false);
const keyword = ref(""),
  searchOpen = ref(false),
  suggestions = ref<Person[]>([]),
  activeSuggestion = ref(-1),
  searching = ref(false),
  searchError = ref("");
const searchTerm = computed(() => keyword.value.trim());
const showSuggestions = computed(() => searchOpen.value && !!searchTerm.value);
/** 搜索选中的员工可能不在部门列表当前页，保留对应选项供核对。 */
const employeeOptions = computed(() => {
  const selected = recipient.value;
  return selected && !people.value.some((person) => person.id === selected.id)
    ? [selected, ...people.value]
    : people.value;
});
let createOperation = "",
  createPayload = "";
const names: Record<string, string> = {
  OPEN: "待办理",
  SUBMITTED: "待分派人确认",
  COMPLETED: "已完成",
};

/** 员工搜索和部门浏览共用授权候选接口。 */
function candidatePage(
  filters: Record<string, string | number>,
  signal: AbortSignal,
) {
  return api<{ items: Person[]; hasNext: boolean }>(
    `/portal/todos/candidates?${query(filters)}`,
    { signal },
    false,
  );
}

watch([departmentId, peoplePage], async ([department, page], _, onCleanup) => {
  people.value = [];
  hasNext.value = false;
  loadingPeople.value = !!department;
  if (!department) return;
  const request = new AbortController();
  onCleanup(() => request.abort());
  try {
    const result = await candidatePage(
      { departmentId: department, page },
      request.signal,
    );
    if (request.signal.aborted) return;
    people.value = result.items;
    hasNext.value = result.hasNext;
  } catch (e) {
    if (!request.signal.aborted) error.value = (e as Error).message;
  } finally {
    if (!request.signal.aborted) loadingPeople.value = false;
  }
});

/** 稍等输入停顿再查询，取消旧请求以免覆盖当前输入的候选。 */
watch([searchTerm, showSuggestions], ([term, open], _, onCleanup) => {
  suggestions.value = [];
  activeSuggestion.value = -1;
  searchError.value = "";
  searching.value = open;
  if (!open) return;
  const request = new AbortController();
  const timer = window.setTimeout(async () => {
    try {
      const result = await candidatePage({ keyword: term }, request.signal);
      if (!request.signal.aborted) suggestions.value = result.items;
    } catch (e) {
      if (!request.signal.aborted) searchError.value = (e as Error).message;
    } finally {
      if (!request.signal.aborted) searching.value = false;
    }
  }, 200);
  onCleanup(() => {
    window.clearTimeout(timer);
    request.abort();
  });
});

/** 选择搜索候选时同步部门和人员。 */
function pickSuggestion(person: Person) {
  recipient.value = person;
  departmentId.value = person.departmentId;
  peoplePage.value = 1;
  keyword.value = "";
  searchOpen.value = false;
}

/** 键盘可选中候选，输入法组合期间沿用浏览器的按键行为。 */
function handleSearchKey(event: KeyboardEvent) {
  if (event.isComposing) return;
  if (event.key === "Enter") {
    event.preventDefault();
    const person = suggestions.value[activeSuggestion.value];
    if (showSuggestions.value && person) pickSuggestion(person);
  } else if (event.key === "Escape" && showSuggestions.value) {
    event.preventDefault();
    searchOpen.value = false;
  } else if (
    showSuggestions.value &&
    suggestions.value.length &&
    (event.key === "ArrowDown" || event.key === "ArrowUp")
  ) {
    event.preventDefault();
    const last = suggestions.value.length - 1;
    activeSuggestion.value =
      event.key === "ArrowDown"
        ? activeSuggestion.value >= last
          ? 0
          : activeSuggestion.value + 1
        : activeSuggestion.value <= 0
          ? last
          : activeSuggestion.value - 1;
    void nextTick(() => {
      document
        .getElementById(`todo-suggestion-${activeSuggestion.value}`)
        ?.scrollIntoView({ block: "nearest" });
    });
  }
}

onMounted(async () => {
  await nextTick();
  dialog.value?.showModal();
  if (props.assign) {
    try {
      departments.value = (
        await api<{ items: Department[] }>(
          "/portal/todos/candidates?view=departments",
        )
      ).items;
    } catch (e) {
      error.value = (e as Error).message;
    }
  }
  if (props.id) {
    busy.value = true;
    try {
      const result = await api<Todo>(`/portal/todos/${props.id}`);
      todo.value = result;
      title.value = result.title;
      description.value = result.description;
      if (result.remindAt) {
        reminderTime.value = new Intl.DateTimeFormat("sv-SE", {
          timeZone: "Asia/Shanghai",
          year: "numeric",
          month: "2-digit",
          day: "2-digit",
          hour: "2-digit",
          minute: "2-digit",
        })
          .format(new Date(result.remindAt))
          .replace(" ", "T");
      }
    } catch (e) {
      error.value = (e as Error).message;
    } finally {
      busy.value = false;
    }
  }
});

async function save() {
  if (busy.value || (props.assign && !recipient.value)) return;
  busy.value = true;
  error.value = "";
  try {
    const body = JSON.stringify({
      title: title.value,
      description: description.value,
      remindAt: reminderTime.value
        ? new Date(`${reminderTime.value}:00+08:00`).toISOString()
        : null,
      ...(props.assign ? { assigneeId: recipient.value?.id } : {}),
    });
    if (todo.value) {
      await api(`/portal/todos/${todo.value.id}`, {
        method: "PUT",
        body,
        headers: {
          "X-Operation-Id": crypto.randomUUID(),
          "X-Expected-Version": String(todo.value.rowVersion),
        },
      });
    } else {
      if (body !== createPayload) {
        createPayload = body;
        createOperation = crypto.randomUUID();
      }
      await api("/portal/todos", {
        method: "POST",
        body,
        headers: { "X-Operation-Id": createOperation },
      });
    }
    emit("changed");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}

async function act(action: string) {
  const current = todo.value;
  if (!current || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await api(`/portal/todos/${current.id}/actions`, {
      method: "POST",
      body: JSON.stringify({
        action,
        ...(action === "SUBMIT" ? { completionNote: note.value } : {}),
      }),
      headers: {
        "X-Operation-Id": crypto.randomUUID(),
        "X-Expected-Version": String(current.rowVersion),
      },
    });
    emit("changed");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
const deleteConfirm = ref(false);
</script>

<template>
  <dialog
    ref="dialog"
    class="form-dialog flow-dialog flow-dialog-compact"
    aria-labelledby="todo-title"
    @cancel.prevent="emit('close')"
  >
    <form class="flow-dialog-shell" @submit.prevent="editing && save()">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="task" :size="22" /></span>
        <h2 id="todo-title">
          {{
            id
              ? editing
                ? "修改个人待办"
                : "待办详情"
              : assign
                ? "分派任务"
                : "新建个人待办"
          }}
        </h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭待办弹窗"
          @click="emit('close')"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <template v-if="editing">
          <section v-if="assign" class="flow-dialog-section">
            <h3>接收人</h3>
            <div class="todo-search">
              <label>
                查找员工
                <input
                  v-model="keyword"
                  maxlength="100"
                  placeholder="姓名或工号"
                  autocomplete="off"
                  role="combobox"
                  aria-autocomplete="list"
                  :aria-expanded="showSuggestions"
                  :aria-controls="
                    showSuggestions ? 'todo-suggestions' : undefined
                  "
                  :aria-activedescendant="
                    showSuggestions && activeSuggestion >= 0
                      ? `todo-suggestion-${activeSuggestion}`
                      : undefined
                  "
                  @focus="searchOpen = true"
                  @input="searchOpen = true"
                  @blur="searchOpen = false"
                  @keydown="handleSearchKey"
                />
              </label>
              <div v-if="showSuggestions" class="todo-suggestions">
                <p v-if="searchError" role="alert">{{ searchError }}</p>
                <p v-else-if="searching" role="status">正在查找员工…</p>
                <p v-else-if="!suggestions.length" role="status">
                  暂无匹配员工
                </p>
                <ul id="todo-suggestions" role="listbox" aria-label="匹配员工">
                  <li
                    v-for="(person, index) in suggestions"
                    :id="`todo-suggestion-${index}`"
                    :key="person.id"
                    role="option"
                    :aria-selected="index === activeSuggestion"
                    @mousedown.prevent
                    @click="pickSuggestion(person)"
                  >
                    <span>{{ person.name }}</span>
                    <span>（{{ person.employeeNo }}）</span>
                  </li>
                </ul>
              </div>
            </div>
            <label class="flow-inline-field">
              选择部门
              <select
                v-model="departmentId"
                @change="
                  recipient = undefined;
                  peoplePage = 1;
                "
              >
                <option value="">请选择部门</option>
                <option
                  v-for="department in departments"
                  :key="department.id"
                  :value="department.id"
                >
                  {{ department.name }}
                </option>
              </select>
            </label>
            <label class="flow-inline-field">
              选择员工
              <select
                :value="recipient?.id || ''"
                :disabled="!departmentId || loadingPeople"
                required
                @change="
                  recipient = employeeOptions.find(
                    (person) =>
                      person.id === ($event.target as HTMLSelectElement).value,
                  )
                "
              >
                <option value="">
                  {{
                    !departmentId
                      ? "请先选择部门"
                      : loadingPeople
                        ? "正在加载员工…"
                        : employeeOptions.length
                          ? "请选择员工"
                          : "该部门暂无可选员工"
                  }}
                </option>
                <option
                  v-for="person in employeeOptions"
                  :key="person.id"
                  :value="person.id"
                >
                  {{ person.name }}（{{ person.employeeNo }}）
                </option>
              </select>
            </label>
            <div v-if="peoplePage > 1 || hasNext" class="todo-picker">
              <button
                type="button"
                class="secondary"
                :disabled="loadingPeople || peoplePage === 1"
                @click="peoplePage--"
              >
                上一页
              </button>
              <button
                type="button"
                class="secondary"
                :disabled="loadingPeople || !hasNext"
                @click="peoplePage++"
              >
                下一页
              </button>
            </div>
          </section>
          <section class="flow-dialog-section">
            <h3>待办内容</h3>
            <label class="flow-inline-field"
              >标题 <input v-model="title" required maxlength="160"
            /></label>
            <RichTextEditor v-model="description" label="说明（选填）" :limit="2000" :rows="3" />
            <label class="flow-inline-field">
              提醒时间（选填，上海时间）
              <input v-model="reminderTime" type="datetime-local" />
            </label>
          </section>
        </template>
        <template v-else-if="todo">
          <section class="flow-dialog-overview">
            <div class="todo-summary">
              <h3>{{ todo.title }}</h3>
              <span class="todo-state">{{ names[todo.state] }}</span>
            </div>
            <dl
              v-if="
                todo.creatorId !== todo.assigneeId ||
                todo.completedAt ||
                todo.remindAt
              "
              class="todo-facts"
            >
              <div v-if="todo.remindAt">
                <dt>提醒时间</dt>
                <dd>{{ portalTime(todo.remindAt) }}</dd>
              </div>
              <template v-if="todo.creatorId !== todo.assigneeId">
                <div>
                  <dt>分派人</dt>
                  <dd>{{ todo.creatorName }}</dd>
                </div>
                <div>
                  <dt>接收人</dt>
                  <dd>{{ todo.assigneeName }}</dd>
                </div>
                <div v-if="todo.submittedAt">
                  <dt>最近提交</dt>
                  <dd>{{ portalTime(todo.submittedAt) }}</dd>
                </div>
              </template>
              <div v-if="todo.completedAt">
                <dt>完成时间</dt>
                <dd>{{ portalTime(todo.completedAt) }}</dd>
              </div>
            </dl>
          </section>
          <section v-if="todo.description" class="flow-dialog-section">
            <h3>待办说明</h3>
            <RichTextView class="todo-description" :value="todo.description" />
          </section>
          <section
            v-if="
              todo.creatorId !== todo.assigneeId &&
              (todo.completionNote ||
                (todo.assigneeId === profile?.user.id && todo.state === 'OPEN'))
            "
            class="flow-dialog-section"
          >
            <h3>办理结果</h3>
            <RichTextView v-if="todo.completionNote" class="todo-description" :value="todo.completionNote" />
            <RichTextEditor
              v-if="todo.assigneeId === profile?.user.id && todo.state === 'OPEN'"
              v-model="note" label="完成说明（选填）" :rows="3" :limit="2000"
            />
          </section>
          <p
            v-if="deleteConfirm && todo.creatorId === todo.assigneeId"
            class="form-error"
            role="alert"
          >
            确认删除这条个人待办？
          </p>
        </template>
      </div>
      <footer class="flow-dialog-footer">
        <template v-if="editing">
          <button type="button" class="secondary" @click="emit('close')">
            取消
          </button>
          <button class="primary" :disabled="busy || (assign && !recipient)">
            {{ assign ? "确认分派" : "保存" }}
          </button>
        </template>
        <template
          v-else-if="
            todo && todo.creatorId === todo.assigneeId && deleteConfirm
          "
        >
          <button
            type="button"
            class="secondary"
            @click="deleteConfirm = false"
          >
            取消
          </button>
          <button
            type="button"
            class="secondary danger"
            :disabled="busy"
            @click="act('DELETE')"
          >
            确认删除
          </button>
        </template>
        <template v-else>
          <button type="button" class="secondary" @click="emit('close')">
            关闭
          </button>
          <template v-if="todo && todo.creatorId === todo.assigneeId">
            <button
              type="button"
              class="secondary"
              :disabled="busy"
              @click="editing = true"
            >
              修改
            </button>
            <button
              type="button"
              class="secondary danger"
              :disabled="busy"
              @click="deleteConfirm = true"
            >
              删除
            </button>
            <button
              type="button"
              class="primary"
              :disabled="busy"
              @click="act(todo.state === 'COMPLETED' ? 'REOPEN' : 'COMPLETE')"
            >
              {{ todo.state === "COMPLETED" ? "重新打开" : "标记完成" }}
            </button>
          </template>
          <button
            v-else-if="
              todo?.assigneeId === profile?.user.id && todo?.state === 'OPEN'
            "
            type="button"
            class="primary"
            :disabled="busy"
            @click="act('SUBMIT')"
          >
            提交完成申请
          </button>
          <template
            v-else-if="
              todo?.creatorId === profile?.user.id &&
              todo?.state === 'SUBMITTED'
            "
          >
            <button
              type="button"
              class="secondary"
              :disabled="busy"
              @click="act('RETURN')"
            >
              退回继续办理
            </button>
            <button
              type="button"
              class="primary"
              :disabled="busy"
              @click="act('CONFIRM')"
            >
              确认完成
            </button>
          </template>
        </template>
      </footer>
    </form>
  </dialog>
</template>

<style scoped>
.todo-search {
  position: relative;
}
.todo-suggestions {
  position: absolute;
  z-index: 1;
  inset: calc(100% + 4px) 0 auto;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
  box-shadow: 0 6px 18px rgb(26 46 58 / 14%);
}
.todo-suggestions p {
  padding: 10px 12px;
  color: var(--text-secondary);
  font-size: 13px;
}
.todo-suggestions ul {
  max-height: min(220px, 30dvh);
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}
.todo-suggestions li {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-height: 36px;
  padding: 6px 12px;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.todo-suggestions li:hover,
.todo-suggestions li[aria-selected="true"] {
  background: var(--surface-muted);
}
.todo-suggestions li span + span {
  color: var(--text-secondary);
  font-size: 12px;
}
.todo-picker {
  display: flex;
  align-items: end;
  gap: 12px;
}
.todo-summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.todo-state {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 6px;
  background: var(--surface);
  color: var(--text-secondary);
  font-size: 12px;
}
.todo-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin: 4px 0 0;
  font-size: 13px;
}
.todo-facts > div {
  display: flex;
  flex-wrap: wrap;
  gap: 0 8px;
}
.todo-facts dt {
  color: var(--text-secondary);
}
.todo-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.todo-description {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
