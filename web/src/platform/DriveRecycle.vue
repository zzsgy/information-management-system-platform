<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { api, mutate, dateTime, query } from "./api";
import DateRangePreset from "./DateRangePreset.vue";
import { recentDateRange } from "./listDates";
interface Item {
  id: string;
  name: string;
  rowVersion: number;
  deletedAt: string;
  retentionElapsed: boolean;
  canRestore: boolean;
  canPurge: boolean;
}
const emit = defineEmits<{ changed: [] }>(),
  ask = inject<(title: string) => Promise<string | null>>("askPassword")!;
const items = ref<Item[]>([]),
  folders = ref<Item[]>([]),
  page = ref(1),
  total = ref(0),
  error = ref(""),
  busy = ref(false),
  chosen = ref<Item>(),
  action = ref(""),
  folder = ref(false),
  reason = ref("");
const recycleRange = recentDateRange(7);
const kind = ref("files"),
  folderPage = ref(1),
  folderTotal = ref(0),
  keyword = ref(""),
  deletedFrom = ref(recycleRange.from),
  deletedTo = ref(recycleRange.to),
  retention = ref("");
const applied = ref({
  keyword: "",
  deletedFrom: recycleRange.from,
  deletedTo: recycleRange.to,
  retention: "",
});
const currentPage = computed(() =>
    kind.value === "files" ? page.value : folderPage.value,
  ),
  currentTotal = computed(() =>
    kind.value === "files" ? total.value : folderTotal.value,
  );
async function load() {
  try {
    const result = await api<{ items: Item[]; total: number }>(
      `/drive/recycle?${query({ ...applied.value, page: page.value, pageSize: 20 })}`,
    );
    items.value = result.items;
    total.value = result.total;
    const folderResult = await api<{ items: Item[]; total: number }>(
      `/drive/recycle/folders?${query({ ...applied.value, page: folderPage.value, pageSize: 20 })}`,
    );
    folders.value = folderResult.items;
    folderTotal.value = folderResult.total;
    error.value = "";
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
function search() {
  applied.value = {
    keyword: keyword.value,
    deletedFrom: deletedFrom.value,
    deletedTo: deletedTo.value,
    retention: retention.value,
  };
  page.value = folderPage.value = 1;
  void load();
}
function resetSearch() {
  keyword.value = deletedFrom.value = deletedTo.value = retention.value = "";
  search();
}
function turn(next: number) {
  if (kind.value === "files") page.value = next;
  else folderPage.value = next;
  void load();
}
function choose(item: Item, kind: string, isFolder = false) {
  chosen.value = item;
  action.value = kind;
  folder.value = isFolder;
  reason.value = "";
}
async function apply() {
  if (!chosen.value) return;
  busy.value = true;
  try {
    const item = chosen.value,
      password = await ask(
        action.value === "RESTORE"
          ? "恢复回收站项目"
          : "确认保留期满后的业务清理",
      );
    if (!password) return;
    const type = folder.value ? "FOLDER" : "DRIVE_DOCUMENT",
      operation = folder.value ? action.value + "_FOLDER" : action.value;
    await mutate(
      `/drive/recycle/${folder.value ? "folders" : "documents"}/${item.id}${action.value === "RESTORE" ? "/restore" : ""}`,
      action.value === "RESTORE" ? "POST" : "DELETE",
      { reason: reason.value },
      item.id,
      type,
      operation,
      password,
      item.rowVersion,
    );
    chosen.value = undefined;
    page.value = folderPage.value = 1;
    await load();
    emit("changed");
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section>
    <div class="recycle-heading">
      <div>
        <h2>回收站</h2>
        <p class="muted">保留30天，到期后由负责人处理，不会自动清空。</p>
      </div>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <form
      class="recycle-search"
      id="drive-recycle-filters"
      aria-label="回收站筛选"
      @submit.prevent="search"
    >
      <label class="list-filter-field"
        >名称<input
          v-model="keyword"
          maxlength="200"
          placeholder="文件或目录名称"
      /></label>
      <label class="list-filter-field"
        >保留状态<select v-model="retention">
          <option value="">全部状态</option>
          <option value="RETAINED">保留中</option>
          <option value="ELAPSED">保留期已满</option>
        </select></label
      >
      <DateRangePreset
        label="回收日期范围"
        :from="deletedFrom"
        :to="deletedTo"
        @apply="
          deletedFrom = $event.from;
          deletedTo = $event.to;
          search();
        "
      />
      <label class="list-filter-field"
        >回收开始日期<input
          v-model="deletedFrom"
          type="date"
          :max="deletedTo || undefined"
      /></label>
      <label class="list-filter-field"
        >回收结束日期<input
          v-model="deletedTo"
          type="date"
          :min="deletedFrom || undefined"
      /></label>
      <div class="recycle-filter-actions">
        <button class="primary">搜索</button
        ><button type="button" class="secondary" @click="resetSearch">
          重置
        </button>
      </div>
    </form>
    <div class="recycle-tabs" role="tablist" aria-label="回收项目类型">
      <button
        role="tab"
        :aria-selected="kind === 'files'"
        @click="kind = 'files'"
      >
        文件</button
      ><button
        role="tab"
        :aria-selected="kind === 'folders'"
        @click="kind = 'folders'"
      >
        目录
      </button>
    </div>
    <form v-if="chosen" class="recycle-form" @submit.prevent="apply">
      <strong
        >{{ action === "RESTORE" ? "恢复" : "清理" }}：{{ chosen.name }}</strong
      >
      <p v-if="action === 'PURGE'" class="muted">
        清理后不再出现在业务回收站，历史引用和受保护备份仍按保留规则保存。
      </p>
      <label
        >原因<textarea
          v-model="reason"
          maxlength="1000"
          rows="2"
          required
        /></label
      ><button class="primary" :disabled="busy">确认</button
      ><button
        type="button"
        class="secondary"
        :disabled="busy"
        @click="chosen = undefined"
      >
        取消
      </button>
    </form>
    <div class="table-scroll">
      <table aria-label="回收项目">
        <thead>
          <tr>
            <th>名称</th>
            <th>回收时间</th>
            <th>保留状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in kind === 'files' ? items : []" :key="item.id">
            <td>{{ item.name }}</td>
            <td>{{ dateTime(item.deletedAt) }}</td>
            <td>{{ item.retentionElapsed ? "保留期已满" : "保留中" }}</td>
            <td>
              <button
                v-if="item.canRestore"
                class="text-button"
                @click="choose(item, 'RESTORE')"
              >
                恢复</button
              ><button
                v-if="item.canPurge && item.retentionElapsed"
                class="text-button"
                @click="choose(item, 'PURGE')"
              >
                清理
              </button>
            </td>
          </tr>
          <tr v-for="item in kind === 'folders' ? folders : []" :key="item.id">
            <td>目录 · {{ item.name }}</td>
            <td>{{ dateTime(item.deletedAt) }}</td>
            <td>{{ item.retentionElapsed ? "保留期已满" : "保留中" }}</td>
            <td>
              <button
                class="text-button"
                @click="choose(item, 'RESTORE', true)"
              >
                恢复目录</button
              ><button
                v-if="item.retentionElapsed"
                class="text-button"
                @click="choose(item, 'PURGE', true)"
              >
                清理目录
              </button>
            </td>
          </tr>
          <tr v-if="!(kind === 'files' ? items.length : folders.length)">
            <td colspan="4" class="muted">
              当前条件下暂无有权处理的回收项目。
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
        <template v-if="currentTotal > 20">
          <button
            class="secondary"
            :disabled="currentPage <= 1"
            @click="turn(currentPage - 1)"
          >
            上一页</button
          ><span>{{ currentPage }} / {{ Math.ceil(currentTotal / 20) }}</span>
          ><button
            class="secondary"
            :disabled="currentPage * 20 >= currentTotal"
            @click="turn(currentPage + 1)"
          >
            下一页
          </button> </template
        ><span class="list-total">共 {{ currentTotal }} 项</span>
      </div>
    </footer>
  </section>
</template>
<style scoped>
.recycle-heading {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  justify-content: space-between;
  align-items: center;
}
.recycle-heading h2 {
  font-size: 20px;
}
.recycle-form {
  padding: 20px;
  background: var(--surface-muted);
  border-radius: 10px;
  margin: 16px 0;
}
.recycle-form label {
  display: grid;
  gap: 8px;
  margin: 16px 0;
}
.recycle-form button {
  margin-right: 10px;
}
.recycle-search {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px 24px;
  background: var(--surface-muted);
  padding: 16px 18px;
  border: 1px solid var(--line);
  border-radius: 10px;
  margin: 16px 0;
}
#drive-recycle-filters label {
  margin: 0;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 12px;
}
.recycle-search input,
.recycle-search select {
  min-width: 0;
  width: 100%;
  height: 40px;
  box-sizing: border-box;
  font: inherit;
  font-size: 13px;
}
.recycle-filter-actions {
  grid-column: 1 / -1;
  display: flex;
  gap: 10px;
}
.recycle-tabs {
  display: flex;
  gap: 20px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 12px;
}
.recycle-tabs button {
  border: 0;
  border-bottom: 2px solid transparent;
  padding: 10px 0;
  background: none;
  font: inherit;
  cursor: pointer;
  color: var(--muted);
}
.recycle-tabs button[aria-selected="true"] {
  border-color: var(--brand);
  color: var(--brand);
  font-weight: 600;
}
@media (max-width: 860px) {
  .recycle-search {
    grid-template-columns: minmax(0, 1fr);
  }
}
table {
  width: 100%;
  border-collapse: collapse;
}
th,
td {
  padding: 16px 8px;
  text-align: left;
  border-bottom: 1px solid var(--line);
  font-size: 14px;
}
td button {
  margin-right: 12px;
}
</style>
