<script setup lang="ts">
import Icon from "../preview/PlatformIcon.vue";
/** 各业务列表复用无数据提示，保留当前页面真实的可见范围和可操作建议。 */
withDefaults(defineProps<{ title: string; hint?: string; icon?: string }>(), {
  icon: "file",
});
</script>
<template>
  <div class="empty-state-content" role="status">
    <span class="empty-state-symbol"><Icon :name="icon" :size="24" /></span>
    <p>{{ title }}</p>
    <small v-if="hint">{{ hint }}</small>
  </div>
</template>
<style scoped>
.empty-state-content {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 8px;
  padding: 28px 16px;
  min-height: 120px;
  color: var(--text-secondary);
  text-align: center;
}
.empty-state-symbol {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  color: #95a8b7;
  background: var(--surface-muted);
  border-radius: 12px;
  margin-bottom: 2px;
}
.empty-state-content p {
  font-size: 13px;
  line-height: 1.6;
  margin: 0;
}
.empty-state-content small {
  color: #8394a1;
  font-size: 12px;
  line-height: 1.6;
}
</style>
