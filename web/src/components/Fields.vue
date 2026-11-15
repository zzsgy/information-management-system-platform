<script setup lang="ts">
import type { Field, Row } from "../lib/model";
/** 表单模型由父级拥有，组件仅通过控件修改声明过的字段。 */
defineProps<{ fields: Field[]; model: Row }>();
</script>
<template>
  <div class="form-grid">
    <el-form-item
      v-for="field in fields"
      :key="field.key"
      :label="field.label"
      :prop="field.key"
      :rules="
        field.required
          ? [
              {
                required: true,
                message: `请填写${field.label}`,
                trigger: 'change',
              },
            ]
          : []
      "
      :class="{ wide: field.type === 'textarea' }"
    >
      <el-select
        v-if="field.type === 'select' || field.type === 'multi'"
        v-model="model[field.key]"
        :multiple="field.type === 'multi'"
        filterable
        clearable
        :aria-label="field.label"
      >
        <el-option
          v-for="option in field.options"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <el-input-number
        v-else-if="field.type === 'number'"
        v-model="model[field.key]"
        :min="field.min ?? 0"
        :max="field.max ?? 2147483647"
        :precision="0"
        :aria-label="field.label"
      />
      <el-date-picker
        v-else-if="field.type === 'date'"
        v-model="model[field.key]"
        value-format="YYYY-MM-DD"
        type="date"
        :aria-label="field.label"
        clearable
      />
      <el-input
        v-else
        v-model="model[field.key]"
        :type="
          field.type === 'textarea'
            ? 'textarea'
            : field.type === 'password'
              ? 'password'
              : 'text'
        "
        :rows="4"
        :maxlength="field.length ?? (field.type === 'textarea' ? 10000 : 300)"
        :show-password="field.type === 'password'"
        :aria-label="field.label"
        :autocomplete="field.type === 'password' ? 'new-password' : 'off'"
      />
      <small v-if="field.help" class="help">{{ field.help }}</small>
    </el-form-item>
  </div>
</template>
