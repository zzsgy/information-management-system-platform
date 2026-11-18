<script setup lang="ts">
import { ref, watch } from "vue";
import { api } from "./api";
import type { MeetingPerson } from "./meeting";

const props = defineProps<{
  departmentId?: string | null;
  selectedName?: string | null;
  label: string;
}>();
const assignee = defineModel<string>({ required: true });
const candidates = ref<MeetingPerson[]>([]);
const loading = ref(false);
const error = ref("");

/** 责任人名单按配合部门取全量分页，切换后忽略上一部门的请求结果。 */
watch(() => props.departmentId, async (department, _, onCleanup) => {
  candidates.value = [];
  error.value = "";
  loading.value = !!department;
  if (!department) return;
  const request = new AbortController();
  onCleanup(() => request.abort());
  try {
    const people: MeetingPerson[] = [];
    let hasNext = true;
    for (let page = 1; hasNext; page++) {
      const params = new URLSearchParams({ departmentId: department, page: String(page) });
      const result = await api<{ items: MeetingPerson[]; hasNext: boolean }>(
        "/meetings/people?" + params, { signal: request.signal },
      );
      people.push(...result.items);
      hasNext = result.hasNext;
    }
    if (!request.signal.aborted) candidates.value = people;
  } catch (e) {
    if (!request.signal.aborted) error.value = (e as Error).message;
  } finally {
    if (!request.signal.aborted) loading.value = false;
  }
}, { immediate: true });
</script>

<template>
  <select v-model="assignee" :aria-label="label" :disabled="!departmentId || loading || !!error">
    <option value="">{{ !departmentId ? "请先选择配合部门" : loading ? "正在加载" : !candidates.length ? "暂无可选员工" : "选择责任人" }}</option>
    <option v-if="assignee && !candidates.some((person) => person.id === assignee)"
      :value="assignee" disabled>{{ selectedName || "原责任人" }}</option>
    <option v-for="person in candidates" :key="person.id" :value="person.id">
      {{ person.name }} · {{ person.employeeNo }}
    </option>
  </select>
  <small v-if="error" role="alert">{{ error }}</small>
</template>
