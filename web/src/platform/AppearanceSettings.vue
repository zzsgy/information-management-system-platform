<script setup lang="ts">
import { ref } from "vue";
import { appearanceThemes, appearanceTheme, selectAppearance } from "./appearance";
const dialog = ref<HTMLDialogElement>();
</script>

<template>
  <button type="button" class="text-button" @click="dialog?.showModal()">外观</button>
  <dialog ref="dialog" class="appearance-dialog" aria-labelledby="appearance-title">
    <header>
      <div><h2 id="appearance-title">外观主题</h2><p>即时应用到整个系统，自动记住当前浏览器的选择。</p></div>
      <button type="button" class="icon-button" aria-label="关闭外观设置" @click="dialog?.close()">×</button>
    </header>
    <div class="appearance-options" role="radiogroup" aria-label="主题配色">
      <label v-for="theme in appearanceThemes" :key="theme.id" class="appearance-option"
        :class="{ selected: appearanceTheme === theme.id }" :data-preview="theme.id"
        :style="{ '--preview-brand': theme.primary, '--preview-soft': theme.soft }">
        <span class="appearance-preview" aria-hidden="true">
          <span class="preview-top"></span><span class="preview-nav"></span>
          <span class="preview-content"><i></i><i></i><i></i><b></b></span>
        </span>
        <span class="appearance-name"><input type="radio" name="appearance-theme" :aria-label="theme.name" :value="theme.id"
          :checked="appearanceTheme === theme.id" @change="selectAppearance(theme.id)" />{{ theme.name }}</span>
        <small>{{ theme.description }}</small>
      </label>
    </div>
    <footer><button type="button" class="primary" @click="dialog?.close()">完成</button></footer>
  </dialog>
</template>

<style scoped>
.appearance-dialog { width: min(720px, calc(100vw - 40px)); max-height: 85vh; overflow: auto; padding: 24px; border: 1px solid var(--line); border-radius: 14px; color: var(--ink); background: var(--surface); }
.appearance-dialog::backdrop { background: #15243c66; }
.appearance-dialog header { display: flex; align-items: start; justify-content: space-between; gap: 20px; margin-bottom: 24px; }
.appearance-dialog h2 { margin: 0 0 6px; font-size: 20px; }
.appearance-dialog p, .appearance-option small { color: var(--text-secondary); font-size: 13px; }
.appearance-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.appearance-option { display: grid; gap: 10px; min-width: 0; padding: 12px; border: 2px solid var(--line); border-radius: 10px; cursor: pointer; }
.appearance-option.selected { border-color: var(--brand); }
.appearance-option:focus-within { outline: 2px solid var(--brand); outline-offset: 3px; }
.appearance-name { display: flex; align-items: center; gap: 8px; color: var(--ink); font-weight: 600; }
.appearance-preview { display: grid; grid-template-columns: 25% 1fr; grid-template-rows: 20px 85px; gap: 8px; padding: 10px; border-radius: 6px; background: #f1f4f8; }
/* 纸色主题的预览同步展示暖背景和金色导航。 */
.appearance-option[data-preview="paper"] .appearance-preview { background: #faf8f5; }
.appearance-option[data-preview="paper"] .preview-top,
.appearance-option[data-preview="paper"] .preview-content { background: #fffdf9; }
.appearance-option[data-preview="paper"] .preview-nav { border-top-color: #b8860b; }
.appearance-option[data-preview="paper"] .preview-content i { background: #f5f0e8; }
.preview-top { grid-column: 1 / -1; border-radius: 3px; background: white; }
.preview-nav { border-top: 12px solid var(--preview-brand); border-radius: 3px; background: var(--preview-soft); }
.preview-content { display: grid; gap: 7px; padding: 9px; border-radius: 3px; background: white; }
.preview-content i { background: #e5eaf0; border-radius: 2px; }
.preview-content b { width: 40%; border-radius: 3px; background: var(--preview-brand); }
.appearance-dialog footer { display: flex; justify-content: flex-end; margin-top: 24px; }
@media (max-width: 520px) { .appearance-options { grid-template-columns: 1fr; } }
</style>
