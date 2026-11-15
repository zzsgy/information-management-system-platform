<script lang="ts">
/** 保存类型的台账显示名称。 */
export const registrationCarriers: Record<string, string> = {
  PAPER: "纸质",
  MIXED: "纸质+电子",
  ELECTRONIC: "电子",
};
</script>
<script setup lang="ts">
import { computed, ref, useId } from "vue";
import ArchiveLocationPicker, {
  type ArchiveLocation,
} from "./ArchiveLocationPicker.vue";
import CatalogFields, { type CatalogField } from "./CatalogFields.vue";
import { classifications } from "./archiveFiles";

/** 新建与编辑共用的档案盒登记字段。 */
export interface ArchiveBoxForm {
  title: string;
  departmentId: string;
  ownerId: string;
  classification: string;
  carrierType: string;
  archiveDate: string;
  retentionCode: string;
  numberMonth: string;
  safeTitle: string;
  notes: string;
  keywords: string;
  extensionValues: Record<string, unknown>;
}
const entry = defineModel<ArchiveBoxForm>({ required: true });
const props = defineProps<{
  fields: CatalogField[];
  fileTypes: string[];
  departments: { id: string; name: string }[];
  owners: { id: string; name: string }[];
  /** 保存类型和编号月份在盒号及实物登记建立后只读。 */
  editing?: boolean;
  lockDepartmentAndClassification?: boolean;
  /** 旧盒只有自由文本库位时仍展示其原值。 */
  locationFallback?: string;
}>();
defineEmits<{ "department-change": [] }>();
const warehouseLocations: ArchiveLocation[] = Array.from(
  { length: 14 },
  (_, index) => String(index + 1).padStart(2, "0"),
).flatMap((cabinetNo) =>
  [..."ABCDEFG"].flatMap((columnNo) =>
    [..."123456"].map((layerNo) => ({ cabinetNo, columnNo, layerNo })),
  ),
);
/** 保留已停用或导入的文件类型，用户可改选当前有效选项。 */
const basicFields = computed(() =>
  props.fields
    .filter((field) => ["fileType", "copyCount"].includes(field.code))
    .map((field) => {
      if (field.code !== "fileType") return field;
      const current = String(entry.value.extensionValues.fileType || "");
      const options =
        current && !props.fileTypes.includes(current)
          ? [current, ...props.fileTypes]
          : props.fileTypes;
      return { ...field, type: "SELECT", options };
    }),
);
const originalFields = computed(() =>
  props.fields.filter(
    (field) =>
      ![
        "cabinetNo",
        "columnNo",
        "layerNo",
        "inventory",
        "preservationType",
        "fileType",
        "copyCount",
      ].includes(field.code),
  ),
);
const paperChoiceOpen = ref(false);
const carrierSelect = ref<HTMLSelectElement>();
const carrierId = useId();
const basicTitle = useId();
const originalTitle = useId();
const paperKindOptions = ["原件", "复印件"];
/** 兼容此前允许同时绑定的历史标签，只有主动改选才替换。 */
const paperKind = computed(() =>
  ((entry.value.extensionValues.paperTags as string[] | undefined) || [])
    .map((tag) => tag.replace(/^纸质/, ""))
    .join("、"),
);
const selectedCarrierLabel = computed(() => {
  const carrier = entry.value.carrierType;
  return paperKind.value && carrier !== "ELECTRONIC"
    ? `纸质（${paperKind.value}）${carrier === "MIXED" ? "+电子" : ""}`
    : registrationCarriers[carrier];
});
function changeRegistrationCarrier() {
  entry.value.extensionValues.paperTags = [];
  paperChoiceOpen.value = entry.value.carrierType !== "ELECTRONIC";
}
function choosePaperKind(kind: string) {
  entry.value.extensionValues.paperTags = [`纸质${kind}`];
  paperChoiceOpen.value = false;
  carrierSelect.value?.focus();
}
defineExpose({
  showPaperChoices: () => {
    paperChoiceOpen.value = true;
  },
});
</script>
<template>
  <section
    class="flow-dialog-section archive-box-section"
    :aria-labelledby="basicTitle"
  >
    <h3 :id="basicTitle">基本信息</h3>
    <div class="archive-box-grid">
      <label
        >档案名称<input v-model="entry.title" required maxlength="512" /></label
      ><label
        >所属部门<select
          v-model="entry.departmentId"
          :disabled="lockDepartmentAndClassification"
          required
          @change="$emit('department-change')"
        >
          <option v-for="d in departments" :key="d.id" :value="d.id">
            {{ d.name }}
          </option>
        </select></label
      ><label
        >档案负责人<select v-model="entry.ownerId" required>
          <option v-for="p in owners" :key="p.id" :value="p.id">
            {{ p.name }}
          </option>
        </select>
      </label>
      <div class="register-carrier">
        <label :for="carrierId">保存类型</label>
        <div class="register-carrier-control">
          <select
            :id="carrierId"
            ref="carrierSelect"
            v-model="entry.carrierType"
            :disabled="editing"
            required
            @change="changeRegistrationCarrier"
          >
            <option value="" disabled>请选择保存类型</option>
            <option
              v-for="(label, code) in registrationCarriers"
              :key="code"
              :value="code"
            >
              {{ entry.carrierType === code ? selectedCarrierLabel : label }}
            </option>
          </select>
          <div
            v-if="paperChoiceOpen"
            class="register-paper-options"
            role="group"
            aria-label="选择纸质类别"
          >
            <span>选择纸质类别</span>
            <button
              v-for="kind in paperKindOptions"
              :key="kind"
              type="button"
              class="secondary"
              @click="choosePaperKind(kind)"
            >
              {{ kind }}
            </button>
          </div>
          <button
            v-else-if="entry.carrierType && entry.carrierType !== 'ELECTRONIC'"
            type="button"
            class="text-button register-change-paper"
            @click="paperChoiceOpen = true"
          >
            {{ paperKind ? "更改纸质类别" : "选择纸质类别" }}
          </button>
        </div>
      </div>
      <label
        >保密级别<select
          v-model="entry.classification"
          :disabled="lockDepartmentAndClassification"
        >
          <option
            v-for="(name, key) in classifications"
            :key="key"
            :value="key"
          >
            {{ name }}
          </option>
        </select></label
      ><label
        >存档日期<input
          v-model="entry.archiveDate"
          required
          type="date" /></label
      ><label
        >有效期<select v-model="entry.retentionCode">
          <option value="05">5年</option>
          <option value="10">10年</option>
          <option value="30">30年</option>
          <option value="YJ">永久</option>
        </select></label
      ><label
        >编号月份<input
          v-model="entry.numberMonth"
          :readonly="editing"
          type="month"
          required
      /></label>
      <div class="register-location-field">
        <span>存放位置</span>
        <ArchiveLocationPicker
          :model-value="entry.extensionValues"
          :fallback="locationFallback"
          :locations="warehouseLocations"
          :clearable="entry.carrierType === 'ELECTRONIC'"
          @update:model-value="Object.assign(entry.extensionValues, $event)"
        />
      </div>
      <slot name="checklist" />
      <CatalogFields
        class="register-basic-fields"
        :fields="basicFields"
        :number-minimum="0"
        v-model="entry.extensionValues"
      />
    </div>
  </section>
  <section
    class="flow-dialog-section archive-box-section"
    :aria-labelledby="originalTitle"
  >
    <details class="register-original">
      <summary :id="originalTitle">原台账信息</summary>
      <div class="flow-dialog-section register-original-fields">
        <CatalogFields
          :fields="originalFields"
          v-model="entry.extensionValues"
        />
        <p v-if="!editing" class="helper">
          原档案编号可留空，系统会分配唯一盒号；原状态和原到期日按来源保留，实际归档须完成审批。
        </p>
      </div>
    </details>
  </section>
  <section class="flow-dialog-section archive-box-section">
    <h3>检索与备注</h3>
    <div class="archive-box-grid">
      <label>安全题名<input v-model="entry.safeTitle" maxlength="512" /></label
      ><label
        >关键词<input
          v-model="entry.keywords"
          placeholder="逗号分隔，可选" /></label
      ><label class="wide-field"
        >备注<textarea v-model="entry.notes" rows="3" maxlength="20000" />
      </label>
    </div>
  </section>
</template>
<style scoped>
.archive-box-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
}
/* 档案盒标签与控件同行，优先于通用弹窗的上置标签样式。 */
section.archive-box-section .archive-box-grid > label,
.register-carrier,
.register-location-field,
:slotted(.register-location-field) {
  display: grid;
  grid-template-columns: var(--entry-label-width, 105px) minmax(0, 1fr);
  align-items: center;
  align-self: start;
  gap: 12px;
  margin: 0;
  font-size: 14px;
}
.archive-box-grid input,
.archive-box-grid select,
.archive-box-grid textarea {
  min-width: 0;
  width: 100%;
  margin: 0;
}
.wide-field {
  grid-column: 1 / -1;
}
:deep(.catalog-fields) {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
  margin: 0;
}
.register-basic-fields {
  display: contents;
}
.register-original > summary {
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface-muted);
  cursor: pointer;
  font-weight: 600;
}
.register-original > summary:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}
.register-original-fields {
  padding-top: 12px;
}
section.archive-box-section :deep(.catalog-fields > label) {
  display: grid;
  grid-template-columns: 105px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  margin: 0;
  font-size: 14px;
}
.register-carrier {
  align-items: start;
}
.register-carrier > label {
  margin: 0;
  padding-top: 10px;
}
.register-paper-options {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface-soft);
}
.register-paper-options button {
  min-height: 32px;
  padding: 4px 10px;
}
.register-change-paper {
  margin-top: 4px;
}
:deep(.catalog-fields small) {
  grid-column: 2;
  margin-top: 0;
}
:deep(.catalog-fields input) {
  min-width: 0;
  margin: 0;
}
@media (max-width: 800px) {
  .archive-box-grid,
  :deep(.catalog-fields) {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
