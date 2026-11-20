<script setup lang="ts">
import { computed } from "vue";
import {
  renderMarkdownWithImages,
  type MarkdownImageDraft,
} from "./markdownImages";

const props = defineProps<{
  value: string;
  images?: MarkdownImageDraft[];
  imageBase?: string;
  imageKeys?: string[];
  numbered?: boolean;
}>();

/** 预览本地图片，已上传的图片从正文对应的鉴权地址读取。 */
const rendered = computed(() =>
  renderMarkdownWithImages(
    props.value,
    (image) => {
      return (
        props.images?.find(({ id }) => id === image)?.dataUrl ??
        (props.imageBase &&
        (!props.imageKeys || props.imageKeys.includes(image))
          ? props.imageBase + "/" + image
          : undefined)
      );
    },
    props.numbered,
  ),
);
</script>

<template>
  <div
    class="markdown-with-images"
    :class="{ 'markdown-outline': numbered }"
    v-html="rendered"
  />
</template>

<style scoped>
.markdown-with-images {
  line-height: 1.65;
  overflow-wrap: anywhere;
}
.markdown-with-images :deep(:first-child) {
  margin-top: 0;
}
.markdown-with-images :deep(:last-child) {
  margin-bottom: 0;
}
.markdown-with-images :deep(img) {
  max-width: 100%;
  height: auto;
  border: 1px solid var(--line);
  border-radius: 6px;
  vertical-align: middle;
}
.markdown-outline :deep(ol) {
  padding-inline-start: 2.6em;
}
.markdown-outline :deep(ol > li::marker) {
  content: counters(list-item, ".") ". ";
}
.markdown-with-images :deep(pre) {
  padding: 0.75em 1em;
  border-radius: 6px;
  background: var(--surface-muted);
  overflow: auto;
}
.markdown-with-images :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
}
.markdown-with-images :deep(th),
.markdown-with-images :deep(td) {
  border: 1px solid var(--line);
  padding: 0.35em 0.65em;
}
</style>
