<script setup lang="ts">
import MarkdownImageView from "./MarkdownImageView.vue";
import RichTextView from "./RichTextView.vue";
import { type Announcement, portalTime } from "./portal";

defineProps<{ item: Announcement; manage?: boolean }>();
</script>

<template>
  <div class="portal-meta">
    <span class="announcement-owner" :class="{ mine: item.isCreator }">{{ item.isCreator ? "我创建的" : "他人创建的" }}</span>
    <span>{{ item.category }}</span>
    <span>发布人：{{ item.publisherName || "尚未发布" }}</span>
    <span>部门：{{ item.publisherDepartment || "—" }}</span>
    <time>{{ portalTime(item.publishedAt) }}</time>
    <slot />
  </div>
  <article class="portal-letterhead">
    <div class="portal-letter-content">
      <p class="portal-letter-label">{{ item.category }}</p>
      <h1>{{ item.title }}</h1>
      <MarkdownImageView v-if="item.contentFormat === 'MARKDOWN'" class="portal-letter-body"
        :value="item.body ?? ''" :image-keys="item.imageKeys"
        :image-base="`/api/v1/${manage ? 'admin/' : ''}portal/announcements/${item.id}/images`" numbered />
      <RichTextView v-else class="portal-letter-body" :value="item.body" />
      <div class="portal-letter-signature">
        <p>{{ item.publisherDepartment }}</p>
        <time>{{ item.publishedAt ? portalTime(item.publishedAt).slice(0, 10) : "" }}</time>
      </div>
    </div>
  </article>
</template>
