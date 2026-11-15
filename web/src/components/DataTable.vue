<script setup lang="ts">
import type { Column, Row } from "../lib/model";
import { display } from "../lib/model";
/** 通用列表展示配置，权限操作由父级插槽定义。 */
defineProps<{ rows: Row[]; columns: Column[]; loading?: boolean }>();
</script>
<template>
  <el-table
    :data="rows"
    v-loading="loading"
    stripe
    empty-text="暂无记录"
    class="data-table"
  >
    <el-table-column
      v-for="column in columns"
      :key="column.key"
      :prop="column.key"
      :label="column.label"
      :min-width="column.width ?? 130"
      show-overflow-tooltip
    >
      <template #default="{ row }"
        ><el-tag
          v-if="column.key === 'status' || column.key === 'account_status'"
          effect="plain"
          >{{ display(row[column.key]) }}</el-tag
        ><span v-else>{{ display(row[column.key]) }}</span></template
      >
    </el-table-column>
    <el-table-column
      v-if="$slots.default"
      label="操作"
      min-width="200"
      fixed="right"
      ><template #default="{ row }"><slot :row="row" /></template
    ></el-table-column>
  </el-table>
</template>
