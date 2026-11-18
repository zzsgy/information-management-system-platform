<script setup lang="ts">
import { computed } from "vue";
import { reportMarkup, richMarkup } from "./formattedText";

const props = defineProps<{ value?: string | null; html?: string | null }>();
const markup = computed(() => props.html ? reportMarkup(props.html) : richMarkup(props.value ?? ""));
</script>

<template>
  <div v-if="markup !== undefined" class="rich-text" v-html="markup" />
  <div v-else class="rich-text">{{ value }}</div>
</template>

<style scoped>
.rich-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.rich-text :deep(p),
.rich-text :deep(ol),
.rich-text :deep(ul) {
  margin-block: 0 0.5em;
}
.rich-text :deep(ol),
.rich-text :deep(ul) {
  padding-inline-start: 1.6em;
}
</style>
