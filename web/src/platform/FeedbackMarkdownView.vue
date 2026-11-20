<script setup lang="ts">
import { computed } from "vue";
import {
  renderFeedbackMarkdown,
  type FeedbackImageDraft,
} from "./feedbackMarkdown";

const props = defineProps<{
  value: string;
  images?: FeedbackImageDraft[];
  feedbackId?: string;
  messageId?: string;
}>();

/** 提交前预览本地图片，详情只从对应反馈下的鉴权接口取图。 */
const rendered = computed(() =>
  renderFeedbackMarkdown(props.value, (image) => {
    if (props.feedbackId && props.messageId)
      return `/api/v1/portal/feedback/${props.feedbackId}/images/${props.messageId}/${image}`;
    return props.images?.find(({ id }) => id === image)?.dataUrl;
  }),
);
</script>

<template>
  <div class="feedback-markdown" v-html="rendered" />
</template>

<style scoped>
.feedback-markdown {
  line-height: 1.65;
  overflow-wrap: anywhere;
}
.feedback-markdown :deep(:first-child) {
  margin-top: 0;
}
.feedback-markdown :deep(:last-child) {
  margin-bottom: 0;
}
.feedback-markdown :deep(img) {
  max-width: 100%;
  height: auto;
  border: 1px solid var(--line);
  border-radius: 6px;
  vertical-align: middle;
}
.feedback-markdown :deep(pre) {
  padding: 0.75em 1em;
  border-radius: 6px;
  background: var(--surface-muted);
  overflow: auto;
}
.feedback-markdown :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
}
.feedback-markdown :deep(th),
.feedback-markdown :deep(td) {
  border: 1px solid var(--line);
  padding: 0.35em 0.65em;
}
</style>
