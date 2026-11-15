<script setup lang="ts">
import { ref } from "vue";
import { api, query } from "../lib/api";
import { ElMessage } from "element-plus";
import type { Page, Row } from "../lib/model";
/** 所选档案主键列表，不接受自由文本伪造档案名称。 */
const selected = defineModel<number[]>({ default: () => [] });
/** 搜索返回的权限过滤结果；追加缓存防止已选标签变成编号。 */
const rows = ref<Row[]>([]);
/** 搜索允许并发只读请求，但只接受最后一次关键词的响应。 */
const busy = ref(false);
let generation = 0;
/** 按名称或编号查询前100条；用户继续输入可缩小范围。 */
async function search(keyword: string) {
  const requestGeneration = ++generation; // 本次关键词的顺序号，避免慢响应覆盖新搜索。
  busy.value = true;
  try {
    const page = await api<Page>(
      "/archives" + query({ keyword, page_size: 100 }),
    );
    if (requestGeneration !== generation) return;
    rows.value = [
      ...rows.value.filter((row) => selected.value.includes(row.id)),
      ...page.items.filter((row) => !selected.value.includes(row.id)),
    ];
  } catch (error) {
    if (requestGeneration === generation)
      ElMessage.error(error instanceof Error ? error.message : "查询失败");
  } finally {
    if (requestGeneration === generation) busy.value = false;
  }
}
</script>
<template>
  <el-select
    v-model="selected"
    multiple
    filterable
    remote
    :remote-method="search"
    :loading="busy"
    placeholder="输入档案名称或编号搜索"
    aria-label="选择档案"
    @focus="search('')"
    ><el-option
      v-for="row in rows"
      :key="row.id"
      :value="row.id"
      :label="`${row.archive_no} · ${row.archive_name}`"
  /></el-select>
</template>
