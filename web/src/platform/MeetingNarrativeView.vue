<script setup lang="ts">
import MarkdownImageView from "./MarkdownImageView.vue";
import RichTextView from "./RichTextView.vue";
import { meetingMaterialImageBase, type MeetingMaterial } from "./meeting";

defineProps<{ meetingId: string; material: MeetingMaterial }>();
</script>

<template>
  <MarkdownImageView
    v-if="material.data.markdown !== undefined"
    :value="material.data.markdown"
    :image-base="meetingMaterialImageBase(meetingId, material)"
    :image-keys="material.data.imageKeys"
    numbered
  />
  <RichTextView v-else :value="material.data.text" :html="material.data.html" />
</template>
