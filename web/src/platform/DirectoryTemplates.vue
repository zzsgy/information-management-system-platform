<script setup lang="ts">
import Icon from "../preview/PlatformIcon.vue";
import { inject, onMounted, ref, toRaw } from "vue";
import { api, mutate, can } from "./api";
import type { CatalogField } from "./CatalogFields.vue";
/** 定义版本与启停版本分开，历史定义只读。 */
interface Template {
  /** 服务端唯一标识。 */
  id: string;
  /** 跨版本稳定的模板代码。 */
  templateKey: string;
  /** 递增定义版本号。 */
  versionNo: number;
  /** 中文显示名称。 */
  name: string;
  /** 档案分类代码。 */
  categoryCode: string;
  /** 盒或文件的目录类型。 */
  objectType: string;
  /** 当前业务状态。 */
  state: string;
  /** 并发校验版本，每次提交须匹配。 */
  rowVersion: number;
  /** 当前版本的字段定义。 */
  fields: CatalogField[];
}
const rows = ref<Template[]>([]),
  editing = ref<Template | null>(null),
  dialog = ref<HTMLDialogElement | null>(null),
  error = ref(""),
  busy = ref(false);
const ask = inject<(title: string) => Promise<string | null>>("askPassword")!;
const categories: Record<string, string> = {
  COMMON: "通用档案（所有部门）",
  ADMIN: "行政档案",
  SALES_CONTRACT: "销售合同",
  PURCHASE_CONTRACT: "采购合同",
  PROJECT_CONTRACT: "项目建设合同",
  QUALITY: "质量文件",
  WORK_RECORD: "工作记录",
};
/** 读取不可变历史定义与当前启停版本。 */
async function load() {
  try {
    rows.value = (
      await api<{ items: Template[] }>(
        "/admin/archive/directory-template-versions",
      )
    ).items;
  } catch (e) {
    error.value = (e as Error).message;
  }
}
/** 点击现有版本后复制定义，保存总是创建新版本。 */
function edit(row?: Template) {
  editing.value = row
    ? structuredClone(toRaw(row))
    : {
        id: "",
        templateKey: "",
        versionNo: 0,
        name: "",
        categoryCode: "COMMON",
        objectType: "RECORD",
        state: "ACTIVE",
        rowVersion: 0,
        fields: [],
      };
  dialog.value?.showModal();
}
/** 新字段默认普通字段，可由管理员显式设为关键字段。 */
function add() {
  editing.value?.fields.push({
    code: "",
    name: "",
    type: "TEXT",
    required: false,
    critical: false,
    visible: true,
    searchable: false,
    enabled: true,
    order: editing.value.fields.length,
    options: [],
  });
}
/** 发布独立版本并保留旧定义，提交前校验本人身份。 */
async function save() {
  if (!editing.value) return;
  const e = editing.value;
  const password = await ask("确认发布目录模板版本");
  if (password === null) return;
  busy.value = true;
  error.value = "";
  try {
    await mutate(
      e.id
        ? `/admin/archive/directory-templates/${e.id}/versions`
        : "/admin/archive/directory-templates",
      "POST",
      {
        templateKey: e.templateKey,
        name: e.name,
        categoryCode: e.categoryCode,
        objectType: e.objectType,
        fields: e.fields,
      },
      e.id || "00000000-0000-0000-0000-000000000001",
      e.id ? "ARCHIVE_REVISION" : "ARCHIVE_COLLECTION",
      e.id ? "REVISE_DIRECTORY_TEMPLATE" : "CREATE_DIRECTORY_TEMPLATE",
      password,
      e.id ? e.rowVersion : undefined,
    );
    dialog.value?.close();
    await load();
  } catch (f) {
    error.value = (f as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 启停只影响新的选用，仍以行版本保护并发配置。 */
async function toggle(row: Template) {
  const password = await ask(
    row.state === "ACTIVE" ? "停用此模板版本" : "启用此模板版本",
  );
  if (password === null) return;
  try {
    await mutate(
      `/admin/archive/directory-templates/${row.id}/state`,
      "PUT",
      { state: row.state === "ACTIVE" ? "DISABLED" : "ACTIVE" },
      row.id,
      "ARCHIVE_REVISION",
      "SET_DIRECTORY_TEMPLATE_STATE",
      password,
      row.rowVersion,
    );
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  }
}
onMounted(load);
</script>
<template>
  <section>
    <div class="panel-title">
      <div>
        <h2>目录模板</h2>
        <p class="muted">版本独立保存，停用后历史档案仍保留原字段和值。</p>
      </div>
      <button
        v-if="can('ARCHIVE.CONFIG_MANAGE')"
        class="primary"
        @click="edit()"
      >
        新建模板
      </button>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div class="table-scroll">
      <table>
        <thead>
          <tr>
            <th>模板</th>
            <th>类别 / 对象</th>
            <th>版本</th>
            <th>字段数</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td>{{ row.name }}</td>
            <td>
              {{ categories[row.categoryCode] }} /
              {{ row.objectType === "BOX" ? "盒" : "文件" }}
            </td>
            <td>V{{ row.versionNo }}</td>
            <td>{{ row.fields.length }}</td>
            <td>{{ row.state === "ACTIVE" ? "启用" : "停用" }}</td>
            <td>
              <template v-if="can('ARCHIVE.CONFIG_MANAGE')"
                ><button class="text-button" @click="edit(row)">新增版本</button
                ><button class="text-button" @click="toggle(row)">
                  {{ row.state === "ACTIVE" ? "停用" : "启用" }}
                </button></template
              >
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer class="list-footer">
      <button
        type="button"
        class="secondary list-refresh"
        :disabled="busy"
        @click="load()"
      >
        刷新列表
      </button>
      <div class="list-pagination">
        <span class="list-total">共 {{ rows.length }} 条</span>
      </div>
    </footer>
    <dialog
      ref="dialog"
      aria-labelledby="directory-template-title"
      class="form-dialog flow-dialog template-dialog"
    >
      <form class="flow-dialog-shell" v-if="editing" @submit.prevent="save">
        <header class="flow-dialog-header">
          <span class="flow-dialog-icon"
            ><Icon name="archive" :size="22"
          /></span>
          <h2 id="directory-template-title">
            {{ editing.id ? "修订模板" : "新建模板" }}
          </h2>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭模板编辑"
            @click="dialog?.close()"
          >
            ×
          </button>
        </header>
        <div class="flow-dialog-body">
          <section class="flow-dialog-section">
            <h3>模板信息</h3>
            <div class="form-grid template-meta">
              <label
                >名称<input
                  v-model="editing.name"
                  required
                  maxlength="128" /></label
              ><label
                >稳定代码<input
                  v-model="editing.templateKey"
                  :disabled="!!editing.id"
                  required
                  maxlength="128" /></label
              ><label
                >类别<select
                  v-model="editing.categoryCode"
                  :disabled="!!editing.id"
                >
                  <option
                    v-for="(name, key) in categories"
                    :key="key"
                    :value="key"
                  >
                    {{ name }}
                  </option>
                </select></label
              ><label
                >适用对象<select
                  v-model="editing.objectType"
                  :disabled="!!editing.id"
                >
                  <option value="BOX">档案盒</option>
                  <option value="RECORD">盒内文件</option>
                </select></label
              >
            </div>
          </section>
          <section class="flow-dialog-section">
            <div class="template-section-heading">
              <h3>专属字段</h3>
              <button
                type="button"
                class="secondary"
                :disabled="editing.fields.length >= 50"
                @click="add"
              >
                添加字段
              </button>
            </div>
            <div
              v-for="(field, index) in editing.fields"
              :key="index"
              class="form-grid field-editor"
            >
              <label>字段名称<input v-model="field.name" required /></label
              ><label
                >字段代码<input
                  v-model="field.code"
                  required
                  pattern="[a-z][A-Za-z0-9]{0,63}" /></label
              ><label
                >类型<select v-model="field.type">
                  <option value="TEXT">文本</option>
                  <option value="NUMBER">数值</option>
                  <option value="DATE">日期</option>
                  <option value="BOOLEAN">是 / 否</option>
                  <option value="SELECT">单选</option>
                </select></label
              ><label
                >显示顺序<input
                  v-model.number="field.order"
                  type="number"
                  min="0"
                  max="999" /></label
              ><label v-if="field.type === 'SELECT'" class="field-options"
                >选项（用中文逗号分隔）<input
                  :value="field.options.join('，')"
                  @input="
                    field.options = ($event.target as HTMLInputElement).value
                      .split(/[，,]/)
                      .map((s) => s.trim())
                      .filter(Boolean)
                  "
              /></label>
              <div class="field-flags">
                <label
                  ><input v-model="field.required" type="checkbox" />必填</label
                ><label
                  ><input
                    v-model="field.critical"
                    type="checkbox"
                  />关键字段</label
                ><label
                  ><input
                    v-model="field.searchable"
                    type="checkbox"
                  />可查询</label
                ><label
                  ><input v-model="field.visible" type="checkbox" />显示</label
                ><label
                  ><input v-model="field.enabled" type="checkbox" />启用</label
                >
              </div>
            </div>
            <p class="muted">
              已有字段可停用；修改代码、删除字段、改变类型或降低关键性会被拒绝。
            </p>
          </section>
          <p v-if="error" class="form-error">{{ error }}</p>
        </div>
        <footer class="flow-dialog-footer">
          <button type="button" class="secondary" @click="dialog?.close()">
            取消
          </button>
          <button class="primary" :disabled="busy">
            {{ busy ? "保存中…" : "发布新版本" }}
          </button>
        </footer>
      </form>
    </dialog>
  </section>
</template>
<style scoped>
table {
  width: 100%;
  border-collapse: collapse;
}
td,
th {
  padding: 13px;
  text-align: left;
  border-bottom: 1px solid var(--line);
}
.template-dialog {
  --flow-dialog-width: 880px;
}
.template-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
label {
  display: grid;
  gap: 7px;
}
.field-editor {
  padding: 16px;
  background: var(--surface-muted);
  margin: 12px 0;
  border-radius: 10px;
}
.field-options,
.field-flags {
  grid-column: 1/-1;
}
.field-flags {
  display: flex;
  gap: 22px;
}
.field-flags label {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 6px;
}
.field-flags input[type="checkbox"] {
  width: 16px;
  height: 16px;
  min-height: 0;
  padding: 0;
  flex: 0 0 16px;
  accent-color: var(--ink);
}
input,
select {
  min-width: 0;
  max-width: 100%;
}
</style>
