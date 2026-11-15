<script setup lang="ts">
import { ref, watch } from "vue";
import type { FormInstance } from "element-plus";
import Fields from "./Fields.vue";
import type { Field, Row } from "../lib/model";
/** 弹窗输入、外部提交状态和初始值；失败时由父级保留弹窗。 */
const props = defineProps<{
  title: string;
  fields: Field[];
  initial?: Row;
  busy?: boolean;
}>();
/** 受控开关，避免提交失败后自动丢失用户输入。 */
const open = defineModel<boolean>({ default: false });
/** 只发出验证后的字段白名单。 */
const emit = defineEmits<{ save: [value: Row] }>();
/** 当前编辑副本不直接改动列表行。 */
const model = ref<Row>({});
/** Element 表单实例用于必填校验。 */
const form = ref<FormInstance>();
watch(open, (value) => {
  if (value)
    model.value = Object.fromEntries(
      props.fields.map((field) => [
        field.key,
        props.initial?.[field.key] ??
          field.initial ??
          (field.type === "multi" ? [] : undefined),
      ]),
    );
});
/** 校验失败保留输入和内联提示，不执行写请求。 */
async function submit() {
  if (await form.value?.validate().catch(() => false)) {
    const value = { ...model.value }; // 可空日期与引用字段使用null表示清空，不把空字符串传给整数解析器。
    for (const field of props.fields)
      if (
        ["select", "date"].includes(field.type ?? "") &&
        value[field.key] === ""
      )
        value[field.key] = null;
    emit("save", value);
  }
}
</script>
<template>
  <el-dialog
    v-model="open"
    :title="title"
    width="760px"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    destroy-on-close
  >
    <el-form
      ref="form"
      :model="model"
      label-position="top"
      @submit.prevent="submit"
      ><Fields :fields="fields" :model="model"
    /></el-form>
    <template #footer
      ><el-button :disabled="busy" @click="open = false">取消</el-button
      ><el-button type="primary" :loading="busy" @click="submit"
        >保存</el-button
      ></template
    >
  </el-dialog>
</template>
