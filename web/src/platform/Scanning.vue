<script setup lang="ts">
import ListFilters from "./ListFilters.vue";
import EmptyState from "./EmptyState.vue";
import { recentWeekFilters } from "./listDates";
import { computed, inject, onMounted, ref, watch } from "vue";
import { api, can, COLLECTION, dateTime, mutate, query } from "./api";
import ArchiveReader from "./ArchiveReader.vue";
import type { ArchiveItem, ArchiveRecord } from "./archiveFiles";
/** 最小设备和部门配置，不含任何密钥。 */
interface Unit {
  /** 标识。 */ id: string;
  /** 名称。 */ name: string;
  /** 编码。 */ code: string;
}
interface Device {
  /** 标识。 */ id: string;
  /** 名称。 */ name: string;
  /** 部门。 */ departmentId: string;
  /** 身份。 */ serviceIdentityId: string;
  /** 软件版本。 */ software: string;
  /** 暂存登记。 */ stagingLocation: string;
  /** 输出渠道。 */ allowedOutputNotes: string;
  /** 状态。 */ state: string;
  /** 版本。 */ rowVersion: number;
  /** 身份到期。 */ validUntil: string;
}
interface ScanItem {
  /** 明细标识。 */ id: string;
  /** 档案对象。 */ recordId: string;
  /** 题名。 */ title: string;
  /** 文件号。 */ fileNo: string;
  /** 预期页数。 */ expectedPages: number;
  /** 实测页数。 */ receivedPages: number;
  /** 原件情况。 */ sourceCondition: string;
  /** 附件情况。 */ attachments: string;
  /** 特殊作业。 */ specialHandling: boolean;
  /** 状态。 */ state: string;
  /** 轮次。 */ generation: number;
  /** 固定候选。 */ pdfVersionId: string | null;
  /** 并发版本。 */ rowVersion: number;
}
interface Batch {
  /** 标识。 */ id: string;
  /** 稳定批次号。 */ externalKey: string;
  /** 件数。 */ expectedCount: number;
  /** 页数。 */ expectedPages: number;
  /** 状态。 */ state: string;
  /** 暂停。 */ paused: boolean;
  /** 实体原件状态。 */ originalsState: string;
  /** 版本。 */ rowVersion: number;
  /** 登记时刻。 */ createdAt: string;
  /** 逐件明细。 */ items?: ScanItem[];
  /** 不可变事件。 */ events?: {
    id: string;
    eventType: string;
    occurredAt: string;
    details: Record<string, unknown>;
  }[];
}
/** 人工表单的原件清单，选中后必须逐件填写真实页数和状态。 */
interface DraftItem {
  /** 档案标识。 */ recordId: string;
  /** 题名。 */ title: string;
  /** 页数。 */ expectedPages: number;
  /** 原件情况。 */ sourceCondition: string;
  /** 附件情况。 */ attachments: string;
  /** 特殊批准。 */ specialHandling: boolean;
}
const ask = inject<(title: string) => Promise<string | null>>("askPassword")!;
/** 按实际权限开放业务和设备页面。 */
const manage = can("ARCHIVE.CATALOG_MANAGE"),
  configure = can("PLATFORM.SCAN_DEVICES_MANAGE"),
  tab = ref(manage ? "batches" : "devices");
const units = ref<Unit[]>([]),
  deviceUnits = ref<Unit[]>([]),
  devices = ref<Device[]>([]),
  department = ref(""),
  selectedDevice = ref(""),
  batches = ref<Batch[]>([]),
  chosen = ref<Batch | null>(null),
  keyword = ref(""),
  records = ref<ArchiveRecord[]>([]),
  draft = ref<DraftItem[]>([]),
  externalKey = ref(""),
  createOpen = ref(false),
  unreturned = ref(false),
  paused = ref(false),
  total = ref(0),
  page = ref(1);
/** 共用处理提示和本人确认，异步期间禁用重复提交。 */
const busy = ref(false),
  error = ref(""),
  notice = ref(""),
  reader = ref<InstanceType<typeof ArchiveReader> | null>(null);
/** 设备配置表单和事件表单分开，避免职责之间复用输入。 */
const deviceForm = ref({
  departmentId: "",
  name: "",
  software: "",
  stagingLocation: "",
  allowedOutputNotes: "",
});
const eventType = ref("TAKE_OUT"),
  fromPerson = ref(""),
  toPerson = ref(""),
  actualQuantity = ref(0),
  condition = ref(""),
  decision = ref(""),
  reason = ref("");
/** 质量检查和纠正仅针对当前选中的明细版本。 */
const qualityItem = ref<ScanItem | null>(null),
  qualityResult = ref("PASS"),
  checkedPages = ref(1),
  contentChecked = ref(false),
  orderChecked = ref(false),
  qualityCondition = ref(""),
  qualityReason = ref(""),
  correctionItem = ref<ScanItem | null>(null),
  correctedRecord = ref(""),
  correctionReason = ref("");
const applicableDevices = computed(() =>
  devices.value.filter(
    (item) => item.departmentId === department.value && item.state === "ACTIVE",
  ),
);
const names: Record<string, string> = {
  OPEN: "待取件",
  RECEIVING: "接收 / 返工中",
  QUALITY_REVIEW: "待人工质检",
  READY: "质检完成",
  CLOSED: "已关闭",
  WAITING: "待接收",
  RECEIVED: "已收到待质检",
  REWORK: "待返工",
  PASSED: "质量通过",
  STORED: "原件在库",
  OUT: "原件未归还",
  RETURNED: "原件已归还",
  REGISTER: "登记清单",
  TAKE_OUT: "原件取出",
  TEMPORARY_CUSTODY: "临时保管交接",
  RETURN: "原件归还",
  SPECIAL_APPROVAL: "特殊作业批准",
  PAUSE: "异常暂停",
  RESOLVE: "登记异常处置",
  RESUME: "批准恢复",
  CLEANUP_APPROVAL: "批准暂存清理",
  CLEANUP_COMPLETED: "记录清理结果",
  CLOSE: "关闭批次",
  QUALITY_PASS: "人工质量通过",
  QUALITY_REWORK: "人工判定返工",
  FILE_CHECKED: "文件检测通过",
  FILE_CHECK_FAILED: "文件检测未完成",
  FILE_REJECTED: "文件检测拒绝",
  CORRECT: "纠正对应关系",
};
const events = [
  "TAKE_OUT",
  "TEMPORARY_CUSTODY",
  "RETURN",
  "SPECIAL_APPROVAL",
  "PAUSE",
  "RESOLVE",
  "RESUME",
  "CLEANUP_APPROVAL",
  "CLEANUP_COMPLETED",
  "CLOSE",
];
/** 首次加载按业务范围取选项，不加载其他部门的台账。 */
async function load() {
  try {
    devices.value = (
      await api<{ items: Device[] }>("/admin/archive/scan-devices")
    ).items;
    if (manage) {
      units.value = (
        await api<{ departments: Unit[] }>("/archive/catalog-options")
      ).departments;
      department.value ||= units.value[0]?.id || "";
    }
    if (configure) {
      deviceUnits.value = (
        await api<{ departments: Unit[] }>("/admin/archive/scan-options")
      ).departments;
      deviceForm.value.departmentId ||= deviceUnits.value[0]?.id || "";
    }
    if (department.value) await refresh();
  } catch (e) {
    error.value = (e as Error).message;
  }
}
/** 业务查询在部门内分页，未归还和异常队列可单独筛选。 */
async function refresh() {
  if (!department.value) return;
  try {
    const result = await api<{ items: Batch[]; total: number }>(
      `/archive/scan-batches?${query({ departmentId: department.value, page: page.value, pageSize: 20, unreturned: String(unreturned.value), paused: String(paused.value), ...listFilters.value })}`,
    );
    batches.value = result.items;
    total.value = result.total;
  } catch (e) {
    error.value = (e as Error).message;
  }
}
/** 选择批次重新读版本，不在状态变化后提交旧事件。 */
async function select(batch: Batch) {
  chosen.value = await api<Batch>(`/archive/scan-batches/${batch.id}`);
  actualQuantity.value = chosen.value.expectedCount;
  qualityItem.value = null;
  correctionItem.value = null;
}
/** 候选只检索当前部门前五十条，输入关键词可继续缩小范围。 */
async function search() {
  try {
    const result = await api<{ items: ArchiveItem[] }>(
      `/archive/records?${query({ departmentId: department.value, state: "DRAFT", keyword: keyword.value, pageSize: 50 })}`,
    );
    records.value = result.items.flatMap((item) =>
      item.record?.carrierType === "MIXED" ? [item.record] : [],
    );
  } catch (e) {
    error.value = (e as Error).message;
  }
}
/** 清单去重，已选项不会因为重新检索丢失。 */
function add(record: ArchiveRecord | undefined) {
  if (
    !record ||
    draft.value.some((item) => item.recordId === record.id) ||
    draft.value.length >= 50
  )
    return;
  draft.value.push({
    recordId: record.id,
    title: record.title,
    expectedPages: 1,
    sourceCondition: "",
    attachments: "",
    specialHandling: false,
  });
}
/** 所有改变状态的操作共用本人验证和版本约束。 */
async function change(
  path: string,
  method: string,
  body: object,
  id: string,
  type: string,
  action: string,
  version?: number,
) {
  const password = await ask("确认扫描管理操作");
  if (password === null) return null;
  return mutate<Record<string, unknown>>(
    path,
    method,
    body,
    id,
    type,
    action,
    password,
    version,
  );
}
/** 统一保存期间阻止双击，成功后刷新当前清单与事件。 */
async function execute(work: () => Promise<unknown>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const result = await work();
    if (result === null) return;
    notice.value = "操作已保存";
    await refresh();
    if (chosen.value) await select(chosen.value);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 新建批次时冻结清单总页数，后续设备只能接收这批明细。 */
function createBatch() {
  return execute(async () => {
    const result = await change(
      "/archive/scan-batches",
      "POST",
      {
        departmentId: department.value,
        deviceId: selectedDevice.value,
        externalKey: externalKey.value,
        expectedCount: draft.value.length,
        expectedPages: draft.value.reduce(
          (sum, item) => sum + item.expectedPages,
          0,
        ),
        items: draft.value.map(({ title: _title, ...item }) => item),
      },
      COLLECTION,
      "SCAN_BATCH",
      "CREATE_SCAN_BATCH",
    );
    if (!result) return null;
    createOpen.value = false;
    draft.value = [];
    await select(result as unknown as Batch);
    return result;
  });
}
/** 真实交接双方和情况必须填写；特殊批准由服务端限定独立职责。 */
function saveEvent() {
  if (!chosen.value) return;
  const batch = chosen.value;
  return execute(() =>
    change(
      `/archive/scan-batches/${batch.id}/events`,
      "POST",
      {
        eventType: eventType.value,
        fromPerson: fromPerson.value,
        toPerson: toPerson.value,
        actualQuantity: actualQuantity.value,
        condition: condition.value,
        decision: decision.value,
        reason: reason.value,
      },
      batch.id,
      "SCAN_BATCH",
      "SCAN_EVENT",
      batch.rowVersion,
    ),
  );
}
/** 质检弹出当前项目，逐轮清空确认勾选，不能继承上一次人工结论。 */
function inspect(item: ScanItem) {
  qualityItem.value = item;
  checkedPages.value = item.receivedPages;
  qualityResult.value = "PASS";
  contentChecked.value = false;
  orderChecked.value = false;
  qualityCondition.value = "";
  qualityReason.value = "";
}
/** 原件只通过个人水印候选阅读器检查，不下发扫描 PDF。 */
async function read(item: ScanItem) {
  await reader.value?.open(
    {
      reviewContext: {
        type: "SCAN_QUALITY",
        id: item.recordId,
        candidatePdfVersionId: item.pdfVersionId,
      },
    },
    item.title,
  );
}
function saveQuality() {
  const item = qualityItem.value;
  if (!item) return;
  return execute(() =>
    change(
      `/archive/scan-items/${item.id}/quality-reviews`,
      "POST",
      {
        result: qualityResult.value,
        receivedPages: checkedPages.value,
        contentChecked: contentChecked.value,
        pageOrderChecked: orderChecked.value,
        condition: qualityCondition.value,
        reason: qualityReason.value,
      },
      item.id,
      "SCAN_ITEM",
      "SCAN_QUALITY",
      item.rowVersion,
    ),
  );
}
/** 纠正目标只能从本部门当前候选列表选取。 */
async function correct(item: ScanItem) {
  // 编辑副本，取消更正时不改变已显示的服务器快照。
  correctionItem.value = { ...item };
  correctedRecord.value = item.recordId;
  correctionReason.value = "";
  await search();
}
function saveCorrection() {
  const item = correctionItem.value;
  if (!item) return;
  return execute(() =>
    change(
      `/archive/scan-items/${item.id}`,
      "PATCH",
      {
        recordId: correctedRecord.value,
        expectedPages: correctionItem.value!.expectedPages,
        sourceCondition: item.sourceCondition,
        reason: correctionReason.value,
      },
      item.id,
      "SCAN_ITEM",
      "CORRECT_SCAN_ITEM",
      item.rowVersion,
    ),
  );
}
/** IT 配置成功后显示专用身份标识，页面从不接触密钥正文。 */
function saveDevice() {
  return execute(async () => {
    const result = await change(
      "/admin/archive/scan-devices",
      "POST",
      deviceForm.value,
      COLLECTION,
      "SCAN_DEVICE",
      "CREATE_SCAN_DEVICE",
    );
    if (result) await load();
    return result;
  });
}
function toggleDevice(item: Device) {
  return execute(async () => {
    const result = await change(
      `/admin/archive/scan-devices/${item.id}`,
      "PATCH",
      {
        state: item.state === "ACTIVE" ? "DISABLED" : "ACTIVE",
        reason: "设备管理页面调整接收状态",
      },
      item.id,
      "SCAN_DEVICE",
      "UPDATE_SCAN_DEVICE",
      item.rowVersion,
    );
    if (result) await load();
    return result;
  });
}
watch(department, () => {
  chosen.value = null;
  draft.value = [];
  records.value = [];
  page.value = 1;
  selectedDevice.value = "";
  void refresh();
});
watch([unreturned, paused], () => {
  page.value = 1;
  void refresh();
});
onMounted(load);
/** 当前列表已提交的筛选条件。 */
const listFilters = ref(recentWeekFilters("created"));
</script>

<template>
  <section class="scan-page">
    <header>
      <p class="eyebrow">实体原件 · 扫描接收 · 人工质检</p>
      <h2>扫描作业</h2>
      <p>
        从原件取出到归还形成完整记录。文件收到、质量通过和正式归档分别办理。
      </p>
    </header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status" class="notice">{{ notice }}</p>
    <nav class="module-tabs" aria-label="扫描作业分类">
      <button
        v-if="manage"
        :class="{ active: tab === 'batches' }"
        @click="tab = 'batches'"
      >
        扫描批次</button
      ><button
        v-if="configure"
        :class="{ active: tab === 'devices' }"
        @click="tab = 'devices'"
      >
        设备与接收范围
      </button>
    </nav>
    <template v-if="tab === 'batches'"
      ><section class="panel">
        <div class="toolbar scanning-filters">
          <label class="list-filter-field"
            >部门<select v-model="department" :disabled="busy">
              <option v-for="unit in units" :key="unit.id" :value="unit.id">
                {{ unit.name }}
              </option>
            </select></label
          ><label v-if="!createOpen" class="check"
            ><input v-model="unreturned" type="checkbox" />原件未归还</label
          ><label v-if="!createOpen" class="check"
            ><input v-model="paused" type="checkbox" />异常暂停</label
          ><button
            v-if="!createOpen"
            class="primary register-batch"
            :disabled="!department || busy"
            @click="
              createOpen = true;
              search();
            "
          >
            登记扫描批次
          </button>
        </div>
        <ListFilters
          v-show="!createOpen"
          class="batch-list-filters"
          :fields="[
            { key: 'keyword', label: '扫描批次号' },
            {
              key: 'state',
              label: '电子状态',
              options: {
                OPEN: names.OPEN!,
                RECEIVING: names.RECEIVING!,
                QUALITY_REVIEW: names.QUALITY_REVIEW!,
                READY: names.READY!,
                CLOSED: names.CLOSED!,
              },
            },
            {
              key: 'originalsState',
              label: '实体原件状态',
              options: {
                STORED: names.STORED!,
                OUT: names.OUT!,
                RETURNED: names.RETURNED!,
              },
            },
            { key: 'created', label: '登记日期', type: 'date' },
          ]"
          :values="listFilters"
          :busy="busy"
          @apply="
            listFilters = $event;
            page = 1;
            refresh();
          "
        />
        <section v-if="createOpen" class="batch-registration" aria-labelledby="scan-registration-title">
          <h3 id="scan-registration-title">登记扫描批次</h3>
          <div class="batch-fields">
            <label
              >设备<select v-model="selectedDevice">
                <option value="">请选择本部门启用设备</option>
                <option
                  v-for="device in applicableDevices"
                  :key="device.id"
                  :value="device.id"
                >
                  {{ device.name }}
                </option>
              </select></label
            ><label
              >扫描批次号<input
                v-model="externalKey"
                maxlength="128"
                placeholder="与扫描代理保持一致的稳定批次号"
            /></label>
          </div>
          <div class="batch-lookup">
            <form class="record-search" @submit.prevent="search">
              <label>
                查找待扫描档案
                <input v-model.trim="keyword" type="search" placeholder="题名或文件号" />
              </label>
              <button type="submit" class="secondary" :disabled="busy">检索</button>
            </form>
            <label>
              加入扫描清单
              <select
                :disabled="busy || !records.length || draft.length >= 50"
                @change="
                  add(
                    records.find(
                      (row) =>
                        row.id === ($event.target as HTMLSelectElement).value,
                    )!,
                  );
                  ($event.target as HTMLSelectElement).value = '';
                "
              >
                <option value="">{{ records.length ? "选择未发布的混合载体档案" : "暂无符合条件的档案" }}</option>
                <option
                  v-for="record in records"
                  :key="record.id"
                  :value="record.id"
                >
                  {{ record.fileNo }} · {{ record.title }}
                </option>
              </select>
            </label>
          </div>
          <div class="batch-manifest">
            <h4>原件清单 <small>{{ draft.length }} / 50 件</small></h4>
            <EmptyState v-if="!draft.length" icon="search" title="尚未加入档案" />
            <div v-else class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>档案</th>
                    <th>页数</th>
                    <th>原件状态 / 破损</th>
                    <th>附件对应</th>
                    <th>特殊作业</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(item, index) in draft" :key="item.recordId">
                    <td>{{ item.title }}</td>
                    <td>
                      <input
                        v-model.number="item.expectedPages"
                        aria-label="预期页数"
                        type="number"
                        min="1"
                        max="10000"
                        class="number"
                      />
                    </td>
                    <td>
                      <input
                        v-model="item.sourceCondition"
                        aria-label="原件状态"
                        placeholder="折页、印章、原有破损等"
                      />
                    </td>
                    <td>
                      <input
                        v-model="item.attachments"
                        aria-label="附件说明"
                        placeholder="附件数量与对应关系"
                      />
                    </td>
                    <td>
                      <input
                        v-model="item.specialHandling"
                        aria-label="需要特殊作业批准"
                        type="checkbox"
                      />
                    </td>
                    <td><button @click="draft.splice(index, 1)">移除</button></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
          <p class="hint">
            请核对原件状态、附件和页数。需要拆订、复原等操作时，勾选「特殊作业」。
          </p>
          <div class="registration-actions">
            <button type="button" class="secondary" :disabled="busy" @click="createOpen = false">
              返回批次列表
            </button>
            <button
              class="primary"
              :disabled="
                busy || !draft.length || !selectedDevice || !externalKey.trim()
              "
              @click="createBatch"
            >
              保存原件清单
            </button>
          </div>
        </section>
        <template v-else>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>批次号</th>
                  <th>文件 / 页数</th>
                  <th>电子状态</th>
                  <th>实体原件</th>
                  <th>登记时间</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="batch in batches" :key="batch.id">
                  <td>{{ batch.externalKey }}</td>
                  <td>
                    {{ batch.expectedCount }} 件 / {{ batch.expectedPages }} 页
                  </td>
                  <td>
                    <span :class="{ danger: batch.paused }">{{
                      batch.paused ? "异常暂停" : names[batch.state]
                    }}</span>
                  </td>
                  <td>{{ names[batch.originalsState] }}</td>
                  <td>{{ dateTime(batch.createdAt) }}</td>
                  <td><button @click="select(batch)">办理</button></td>
                </tr>
                <tr v-if="!batches.length">
                  <td colspan="6" class="empty">当前条件下没有扫描批次</td>
                </tr>
              </tbody>
            </table>
          </div>
          <footer class="list-footer">
            <button
              type="button"
              class="secondary list-refresh"
              :disabled="busy"
              @click="refresh()"
            >
              刷新列表
            </button>
            <div class="list-pagination">
              <button
                :disabled="page <= 1"
                @click="
                  page--;
                  refresh();
                "
              >
                上一页</button
              ><span>第 {{ page }} 页 · 共 {{ total }} 批</span
              ><button
                :disabled="page * 20 >= total"
                @click="
                  page++;
                  refresh();
                "
              >
                下一页
              </button>
            </div>
          </footer>
        </template>
      </section>
      <section v-if="chosen && !createOpen" class="panel">
        <div class="toolbar">
          <h3>{{ chosen.externalKey }}</h3>
          <span
            >{{ chosen.paused ? "异常暂停" : names[chosen.state] }} ·
            {{ names[chosen.originalsState] }}</span
          >
        </div>
        <p class="hint">
          扫描代理按已批准清单接收，断网后继续保留原暂存文件及重试状态。质检完成后，到档案中心办理正式归档复核。
        </p>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>文件</th>
                <th>预期 / 实收页数</th>
                <th>轮次与结果</th>
                <th>原件及附件</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in chosen.items" :key="item.id">
                <td>
                  {{ item.title }}<small>{{ item.fileNo }}</small>
                </td>
                <td>
                  {{ item.expectedPages }} /
                  {{ item.receivedPages || "未接收" }}
                </td>
                <td>第 {{ item.generation }} 轮 · {{ names[item.state] }}</td>
                <td>
                  {{ item.sourceCondition
                  }}<small>{{ item.attachments }}</small>
                </td>
                <td>
                  <button
                    v-if="item.pdfVersionId && item.state !== 'REWORK'"
                    @click="read(item)"
                  >
                    受控检查
                  </button>
                  <button
                    v-if="item.state === 'RECEIVED'"
                    :disabled="busy || chosen.paused"
                    @click="inspect(item)"
                  >
                    质量结论</button
                  ><button
                    v-if="['WAITING', 'REWORK'].includes(item.state)"
                    @click="correct(item)"
                  >
                    纠正对应
                  </button>
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
            @click="select(chosen)"
          >
            刷新列表
          </button>
          <div class="list-pagination">
            <span class="list-total"
              >共 {{ chosen.items?.length ?? 0 }} 份文件</span
            >
          </div>
        </footer>
        <div v-if="qualityItem" class="form-section">
          <h3>质量检查：{{ qualityItem.title }}</h3>
          <div class="form-grid grid">
            <label
              >结论<select v-model="qualityResult">
                <option value="PASS">通过</option>
                <option value="REWORK">返工</option>
              </select></label
            ><label
              >实际核对页数<input
                v-model.number="checkedPages"
                type="number"
                min="1" /></label
            ><label>原件与内容情况<input v-model="qualityCondition" /></label
            ><label>返工原因 / 补充说明<input v-model="qualityReason" /></label>
          </div>
          <label class="check"
            ><input
              v-model="contentChecked"
              type="checkbox"
            />已核对正反面、折页、附件、印章、裁边和关键内容清晰完整</label
          ><label class="check"
            ><input
              v-model="orderChecked"
              type="checkbox"
            />已核对页序和重复页，档号、来源、附件版本对应正确</label
          ><button
            class="primary"
            :disabled="busy || !qualityCondition.trim()"
            @click="saveQuality"
          >
            提交本轮质量结论
          </button>
        </div>
        <div v-if="correctionItem" class="form-section">
          <h3>纠正对应关系 / 预期页数</h3>
          <label
            >纠正后预期页数<input
              v-model.number="correctionItem.expectedPages"
              type="number"
              min="1"
              max="10000"
          /></label>
          <label
            >正确档案<select
              v-model="correctedRecord"
              :disabled="!!correctionItem.pdfVersionId"
            >
              <option
                v-for="record in records"
                :key="record.id"
                :value="record.id"
              >
                {{ record.fileNo }} · {{ record.title }}
              </option>
            </select></label
          ><label>原因<input v-model="correctionReason" /></label
          ><button
            :disabled="busy || !correctionReason.trim()"
            @click="saveCorrection"
          >
            保存纠正
          </button>
        </div>
        <details v-if="chosen.state !== 'CLOSED'" open class="form-section">
          <summary>原件交接与异常办理</summary>
          <div class="form-grid grid">
            <label
              >办理事项<select v-model="eventType">
                <option v-for="event in events" :key="event" :value="event">
                  {{ names[event] }}
                </option>
              </select></label
            ><label
              >实核件数<input
                v-model.number="actualQuantity"
                type="number"
                min="0"
                max="50" /></label
            ><label>交出人<input v-model="fromPerson" maxlength="128" /></label
            ><label>接收人<input v-model="toPerson" maxlength="128" /></label
            ><label>完好及数量情况<input v-model="condition" /></label
            ><label>处理决定<input v-model="decision" /></label
            ><label>原因 / 批准依据<input v-model="reason" /></label>
          </div>
          <p class="hint">
            异常先暂停，再登记处置和独立批准恢复；特殊作业及暂存清理由与登记人不同的档案超级管理员批准。这里只保存实际办理记录。
          </p>
          <button
            class="primary"
            :disabled="
              busy || !condition.trim() || !decision.trim() || !reason.trim()
            "
            @click="saveEvent"
          >
            保存办理记录
          </button>
        </details>
        <details class="history">
          <summary>最近处理记录</summary>
          <ol>
            <li v-for="event in chosen.events" :key="event.id">
              <strong>{{ names[event.eventType] || event.eventType }}</strong
              ><time>{{ dateTime(event.occurredAt) }}</time>
              <p>{{ event.details.condition || event.details.reason || "" }}</p>
            </li>
          </ol>
        </details>
      </section></template
    >
    <section v-else class="panel">
      <h3>扫描设备与接收范围</h3>
      <p class="hint">
        IT
        登记真实设备、软件版本、受控暂存位置和批准输出渠道。新设备密钥仅写入服务器私有部署目录，由
        IT 安全配置到代理。
      </p>
      <div class="form-grid grid">
        <label
          >所属部门<select v-model="deviceForm.departmentId">
            <option v-for="unit in deviceUnits" :key="unit.id" :value="unit.id">
              {{ unit.name }}
            </option>
          </select></label
        ><label>设备名称<input v-model="deviceForm.name" /></label
        ><label>软件版本<input v-model="deviceForm.software" /></label
        ><label
          >受控暂存位置<input v-model="deviceForm.stagingLocation" /></label
        ><label class="wide"
          >批准输出渠道与现场核验说明<textarea
            v-model="deviceForm.allowedOutputNotes"
            rows="2"
          />
        </label>
      </div>
      <button class="primary" :disabled="busy" @click="saveDevice">
        登记设备及专用身份
      </button>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>设备</th>
              <th>暂存位置</th>
              <th>接收状态</th>
              <th>身份有效期</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="device in devices" :key="device.id">
              <td>
                {{ device.name }}<small>{{ device.software }}</small>
              </td>
              <td>{{ device.stagingLocation }}</td>
              <td>{{ device.state === "ACTIVE" ? "启用" : "停用" }}</td>
              <td>{{ dateTime(device.validUntil) }}</td>
              <td>
                <button :disabled="busy" @click="toggleDevice(device)">
                  {{ device.state === "ACTIVE" ? "停用接收" : "启用接收" }}
                </button>
                <details>
                  <summary>部署标识</summary>
                  <small>设备 {{ device.id }}</small
                  ><small>服务 {{ device.serviceIdentityId }}</small>
                </details>
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
          <span class="list-total">共 {{ devices.length }} 台设备</span>
        </div>
      </footer>
    </section>
    <ArchiveReader ref="reader" @failure="error = $event" />
  </section>
</template>
<style scoped>
.scan-page {
  min-width: 0;
  color: var(--ink);
}
.eyebrow {
  font-size: 12px;
  letter-spacing: 2px;
  color: var(--ink);
}
h2 {
  font-size: 24px;
  margin: 6px 0 10px;
}
header p,
.hint {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.7;
}
button {
  padding: 9px 14px;
  border: 1px solid var(--line);
  background: #fff;
  border-radius: 7px;
  color: var(--brand);
  cursor: pointer;
  font-size: 13px;
}
button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.primary {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
}
.panel {
  padding: 24px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
  margin-bottom: 20px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 0 0 16px;
}
.toolbar h3 {
  margin-right: auto;
}
.toolbar label {
  margin: 0;
}
.scanning-filters {
  align-items: center;
}
.scanning-filters .check {
  min-height: 40px;
}
.register-batch {
  margin-inline-start: auto;
}
.platform-shell .batch-list-filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(150px, 100%), 1fr));
  align-items: end;
  gap: 12px;
  padding: 16px;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 10px;
}
.platform-shell .batch-list-filters :deep(.list-filter-field) {
  display: grid;
  min-width: 0;
}
.batch-list-filters :deep(.list-filter-field :is(input, select)) {
  width: 100%;
  min-width: 0;
}
.batch-registration {
  display: grid;
  gap: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}
.batch-registration :is(h3, h4, label, p) {
  margin: 0;
}
.batch-fields,
.batch-lookup {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(280px, 100%), 1fr));
  align-items: end;
  gap: 14px;
}
.record-search {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 10px;
}
.batch-registration :is(input:not([type="checkbox"]), select, .record-search button) {
  min-height: 40px;
}
.batch-registration :is(input, select) {
  min-width: 0;
}
.batch-manifest {
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
}
.batch-manifest h4 {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: var(--surface-muted);
}
.batch-manifest small {
  margin: 0;
  font-weight: 400;
}
.batch-manifest table {
  margin: 0;
  min-width: 640px;
}
.registration-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
}
.wide {
  grid-column: 1/-1;
}
label {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin: 12px 0;
  font-size: 13px;
}
.check {
  flex-direction: row;
  align-items: center;
}
input[type="checkbox"] {
  /* 覆盖全局文本框最小高度，桌面清单使用紧凑的标准复选框。 */
  width: 16px;
  height: 16px;
  min-height: 0;
}
.number {
  width: 70px;
}
td input:not([type="checkbox"]) {
  max-width: 170px;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  margin-top: 20px;
}
th {
  text-align: left;
  font-weight: 500;
  background: var(--surface-muted);
  color: var(--text-secondary);
}
td,
th {
  padding: 12px 9px;
  border-bottom: 1px solid var(--line);
}
small {
  display: block;
  color: var(--text-secondary);
  overflow-wrap: anywhere;
  margin-top: 5px;
}
.form-section {
  padding: 18px;
  background: var(--surface-muted);
  border-radius: 10px;
  margin-top: 22px;
}
.form-section h3 {
  margin-top: 0;
}
.error {
  padding: 12px;
  background: #fff1ee;
  border-radius: 8px;
  color: #a43527;
}
.notice {
  padding: 12px;
  background: #eef8f2;
  color: #2d6b53;
  border-radius: 8px;
}
.danger {
  color: #b74b38;
}
.empty {
  padding: 40px;
  text-align: center;
  color: var(--text-secondary);
}
.history {
  margin-top: 24px;
}
.history li {
  margin: 14px 0;
  font-size: 13px;
}
.history time {
  margin-left: 20px;
  color: var(--text-secondary);
}
.history p {
  color: var(--text-secondary);
}
summary {
  font-size: 13px;
  cursor: pointer;
}
h3 {
  font-size: 17px;
}
</style>
