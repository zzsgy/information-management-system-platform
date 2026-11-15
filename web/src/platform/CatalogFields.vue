<script setup lang="ts">
/** 目录字段只负责输入呈现，类型、必填及关键性最终由服务端模板核验。 */
export interface CatalogField {
  /** 跨版本稳定的字段代码。 */
  code: string;
  /** 中文显示名称。 */
  name: string;
  /** 服务端允许的输入类型。 */
  type: string;
  /** 启用时是否必须填写。 */
  required: boolean;
  /** 修改后是否需要关键字段复核。 */
  critical: boolean;
  /** 是否展示在目录维护列表。 */
  visible: boolean;
  /** 是否允许在维护范围内参与检索。 */
  searchable: boolean;
  /** 停用后只保留历史值。 */
  enabled: boolean;
  /** 同模板内的显示顺序。 */
  order: number;
  /** 枚举字段允许选用的值。 */
  options: string[];
}
const props = defineProps<{
  /** 当前版本的字段定义。 */
  fields: CatalogField[];
  modelValue: Record<string, unknown>;
  /** 盒登记或维护时隐藏重复保存类型，按真实保存形式显示纸质标签。 */
  paperCarrier?: string;
  /** 登记表单的数值下限，其他模板仍按原规则编辑。 */
  numberMinimum?: number;
}>();
const emit = defineEmits<{ "update:modelValue": [Record<string, unknown>] }>();
/** 保留停用字段的旧值，只更新用户正在编辑的一个字段。 */
function update(field: CatalogField, event: Event) {
  const input = event.target as HTMLInputElement;
  let value: unknown = input.value;
  if (field.type === "NUMBER")
    value = input.value === "" ? null : Number(input.value);
  if (field.type === "BOOLEAN")
    value = input.value === "" ? null : input.value === "true";
  emit("update:modelValue", { ...props.modelValue, [field.code]: value });
}
/** 标签属于盒的固定业务字段，不受可配置模板增删影响。 */
function togglePaperTag(tag: string, event: Event) {
  const tags = (props.modelValue.paperTags as string[] | undefined) || [];
  const checked = (event.target as HTMLInputElement).checked;
  emit("update:modelValue", {
    ...props.modelValue,
    paperTags: checked ? [...tags, tag] : tags.filter((value) => value !== tag),
  });
}
</script>
<template>
  <div class="form-grid catalog-fields">
    <fieldset
      v-if="paperCarrier && paperCarrier !== 'ELECTRONIC'"
      class="paper-tags"
    >
      <legend>纸质标签（必选，可多选）</legend>
      <label v-for="tag in ['纸质原件', '纸质复印件']" :key="tag"
        ><input
          type="checkbox"
          :checked="
            ((modelValue.paperTags as string[] | undefined) || []).includes(tag)
          "
          @change="togglePaperTag(tag, $event)"
        />{{ tag }}</label
      >
    </fieldset>
    <label
      v-for="field in fields.filter(
        (field) => !paperCarrier || field.code !== 'preservationType',
      )"
      :key="field.code"
      >{{ field.name
      }}<select
        v-if="field.type === 'SELECT' || field.type === 'BOOLEAN'"
        :value="modelValue[field.code] ?? ''"
        :disabled="!field.enabled"
        @change="update(field, $event)"
      >
        <option value="">请选择</option>
        <template v-if="field.type === 'BOOLEAN'"
          ><option value="true">是</option>
          <option value="false">否</option></template
        >
        <option
          v-for="option in field.options"
          v-else
          :key="option"
          :value="option"
        >
          {{ option }}
        </option></select
      ><input
        v-else
        :value="modelValue[field.code] ?? ''"
        :type="
          field.type === 'DATE'
            ? 'date'
            : field.type === 'NUMBER'
              ? 'number'
              : 'text'
        "
        :disabled="!field.enabled"
        :min="field.type === 'NUMBER' ? numberMinimum : undefined"
        :maxlength="2000"
        @input="update(field, $event)"
      /><small v-if="field.required || field.critical || !field.enabled"
        >{{ field.required ? "必填 · " : ""
        }}{{ field.critical ? "关键字段，修改需复核" : ""
        }}{{ !field.enabled ? " · 已停用，保留历史值" : "" }}</small
      ></label
    >
  </div>
</template>
<style scoped>
.catalog-fields {
  margin: 20px 0;
}
.catalog-fields label {
  display: grid;
  gap: 7px;
}
.catalog-fields small {
  font-weight: 400;
  color: var(--ink);
}
.catalog-fields input,
.catalog-fields select {
  min-width: 0;
  width: 100%;
}
.paper-tags {
  grid-column: 1 / -1;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 12px;
}
.paper-tags label {
  display: inline-flex;
  align-items: center;
  margin-right: 24px;
}
.paper-tags input {
  width: auto;
}
</style>
