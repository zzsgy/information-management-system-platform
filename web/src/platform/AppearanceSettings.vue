<script setup lang="ts">
import { computed, ref, watch } from "vue";
import Icon from "../preview/PlatformIcon.vue";
import { appearanceThemes, appearanceTheme, selectAppearance, appearanceFonts, appearanceFont, selectInterfaceFont } from "./appearance";
/** 主题与字体共用原外观弹窗，切换页签不改变已经选中的偏好。 */
const dialog = ref<HTMLDialogElement>();
const section = ref<"themes" | "fonts">("themes");
const plainThemes = appearanceThemes.filter((theme) => !theme.background);
const pictureThemes = appearanceThemes.filter((theme) => theme.background);
const currentTheme = computed(() => appearanceThemes.find((theme) => theme.id === appearanceTheme.value)!);
const currentFont = computed(() => appearanceFonts.find((font) => font.id === appearanceFont.value)!);
/** 本地字体首次加载时保留系统回退字体，快速切换不会让旧加载结果覆盖新选择。 */
const fontLoading = ref(false), fontError = ref("");
let fontGeneration = 0;
watch(appearanceFont, async () => {
  const generation = ++fontGeneration;
  fontError.value = "";
  fontLoading.value = !!currentFont.value.face;
  if (!currentFont.value.face) return;
  try {
    const faces = await document.fonts.load(`400 16px "${currentFont.value.face}"`, "企业信息化平台");
    if (!faces.length) throw new Error("字体未加载");
  } catch {
    if (generation === fontGeneration) fontError.value = "字体暂未加载，当前使用系统字体。请刷新页面后重试。";
  } finally {
    if (generation === fontGeneration) fontLoading.value = false;
  }
}, { immediate: true });
/** 恢复平台初始外观，仍沿用当前浏览器即时保存的交互方式。 */
function restoreDefaults() {
  selectAppearance("blue");
  selectInterfaceFont("system");
}
</script>

<template>
  <button type="button" class="text-button" @click="dialog?.showModal()">外观</button>
  <dialog ref="dialog" class="appearance-dialog" aria-labelledby="appearance-title">
    <header>
      <div><span class="appearance-eyebrow">让工作空间更合心意</span><h2 id="appearance-title">外观设置</h2><p>主题与字体即时生效，自动记住当前浏览器的选择。</p></div>
      <button type="button" class="icon-button" aria-label="关闭外观设置" @click="dialog?.close()">×</button>
    </header>
    <nav class="appearance-tabs" aria-label="外观设置分类">
      <button type="button" :class="{ active: section === 'themes' }" :aria-pressed="section === 'themes'" @click="section = 'themes'"><Icon name="sun" :size="17" />主题风格</button>
      <button type="button" :class="{ active: section === 'fonts' }" :aria-pressed="section === 'fonts'" @click="section = 'fonts'"><span class="font-tab-icon" aria-hidden="true">Aa</span>界面字体</button>
    </nav>
    <div v-show="section === 'themes'" class="appearance-body">
      <section class="appearance-section" aria-labelledby="picture-themes-title">
        <div class="section-heading"><h3 id="picture-themes-title">图片主题</h3><span>背景与配色成套切换</span></div>
        <div class="appearance-options picture-options" role="radiogroup" aria-label="图片主题">
          <label v-for="theme in pictureThemes" :key="theme.id" class="appearance-option picture-option" :class="{ selected: appearanceTheme === theme.id }">
            <span class="picture-preview" :class="{ 'dark-preview': theme.dark }" :style="{ backgroundImage: `url('${theme.background}')`, '--preview-brand': theme.primary, '--preview-soft': theme.soft }" aria-hidden="true">
              <span class="picture-window"><i></i><span><b></b><b></b><b></b></span></span>
              <span class="picture-check" v-if="appearanceTheme === theme.id"><Icon name="check" :size="16" /></span>
            </span>
            <span class="appearance-name"><input type="radio" name="appearance-theme" :aria-label="theme.name" :value="theme.id" :checked="appearanceTheme === theme.id" @change="selectAppearance(theme.id)" />{{ theme.name }}</span>
            <small>{{ theme.description }}</small>
          </label>
        </div>
      </section>
      <section class="appearance-section" aria-labelledby="plain-themes-title">
        <div class="section-heading"><h3 id="plain-themes-title">经典配色</h3><span>简洁、专注的日常选择</span></div>
        <div class="appearance-options plain-options" role="radiogroup" aria-label="经典配色">
          <label v-for="theme in plainThemes" :key="theme.id" class="appearance-option plain-option" :class="{ selected: appearanceTheme === theme.id }" :style="{ '--preview-brand': theme.primary, '--preview-soft': theme.soft }">
            <span class="plain-swatch" aria-hidden="true"><i></i><b></b></span>
            <span class="appearance-name"><input type="radio" name="appearance-theme" :aria-label="theme.name" :value="theme.id" :checked="appearanceTheme === theme.id" @change="selectAppearance(theme.id)" />{{ theme.name }}</span>
          </label>
        </div>
      </section>
    </div>
    <div v-show="section === 'fonts'" class="appearance-body">
      <div class="section-heading"><h3>选择界面字体</h3><span>独立于主题，只调整界面文字显示</span></div>
      <div class="appearance-options font-options" role="radiogroup" aria-label="界面字体">
        <label v-for="font in appearanceFonts" :key="font.id" class="appearance-option font-option" :class="{ selected: appearanceFont === font.id }">
          <span class="appearance-name"><input type="radio" name="appearance-font" :aria-label="font.name" :checked="appearanceFont === font.id" :value="font.id" @change="selectInterfaceFont(font.id)" />{{ font.name }}<small v-if="font.license" class="font-license-badge">免费商用</small></span>
          <small>{{ font.description }}</small>
        </label>
      </div>
      <section class="font-sample" aria-label="字体效果预览">
        <span>实时预览 · {{ currentFont.name }}</span>
        <strong>让工作井然有序，让阅读赏心悦目。</strong>
        <p>企业信息化平台 · 资料共享与协作</p>
        <p>Aa Bb Cc &nbsp; 0123456789 &nbsp; ，。！？</p>
      </section>
      <p class="font-load-status" role="status" aria-live="polite">{{ fontError || (fontLoading ? '正在应用字体，请稍候…' : '选择的字体已应用到界面，原文件与打印版式保持不变。') }}</p>
      <div class="font-licenses"><span>内置字体授权：</span><a v-for="font in appearanceFonts.filter((item) => item.license)" :key="font.id" :href="font.license" target="_blank" rel="noopener">{{ font.name }} · OFL 1.1 ↗</a></div>
    </div>
    <footer><span class="appearance-current">{{ currentTheme.name }}<span> / </span>{{ currentFont.name }}</span><button type="button" class="secondary" @click="restoreDefaults">恢复默认</button><button type="button" class="primary" @click="dialog?.close()">完成</button></footer>
  </dialog>
</template>

<style scoped>
.appearance-dialog { width: min(900px, calc(100vw - 40px)); max-height: 90vh; overflow: auto; padding: 0; border: 1px solid var(--line); border-radius: 16px; color: var(--ink); background: var(--surface); box-shadow: 0 24px 80px #071b3b33; white-space: normal; }
.appearance-dialog::backdrop { background: #15243c66; }
.appearance-dialog header { display: flex; align-items: start; justify-content: space-between; gap: 20px; padding: 24px 28px 20px; }
.appearance-dialog header > div { min-width: 0; }
.appearance-dialog header > button { flex-shrink: 0; }
.appearance-eyebrow { display: block; margin-bottom: 6px; font-size: 11px; letter-spacing: 1px; color: var(--brand); }
.appearance-dialog h2 { margin: 0 0 6px; font-size: 20px; }
.appearance-dialog p, .appearance-option small { color: var(--text-secondary); font-size: 13px; }
.appearance-tabs { display: flex; gap: 6px; padding: 0 28px; border-bottom: 1px solid var(--line); }
.appearance-tabs button { display: inline-flex; align-items: center; gap: 8px; padding: 11px 16px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--text-secondary); font-size: 13px; }
.appearance-tabs button.active { color: var(--brand); border-bottom-color: var(--brand); background: var(--brand-soft); border-radius: 6px 6px 0 0; }
.font-tab-icon { font-weight: 600; }
.appearance-body { padding: 20px 28px 24px; }
.appearance-section + .appearance-section { margin-top: 24px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.section-heading h3 { margin: 0; font-size: 14px; font-weight: 600; }
.section-heading > span { color: var(--text-secondary); font-size: 12px; }
.appearance-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.appearance-option { display: grid; gap: 8px; min-width: 0; padding: 12px; margin: 0; border: 1px solid var(--line); border-radius: 10px; cursor: pointer; background: var(--surface); }
.appearance-option.selected { border-color: var(--brand); }
.appearance-option:focus-within { outline: 2px solid var(--brand); outline-offset: 3px; }
.appearance-name { display: flex; align-items: center; gap: 8px; color: var(--ink); font-weight: 600; }
.appearance-name input { flex-shrink: 0; }
.picture-preview { position: relative; display: grid; place-items: center; height: 112px; border-radius: 6px; background-size: cover; background-position: center; overflow: hidden; }
.picture-window { display: grid; grid-template-rows: 10px 1fr; gap: 6px; width: 57%; height: 65px; padding: 6px; border: 1px solid #fff8; border-radius: 5px; background: #ffffffce; box-shadow: 0 5px 16px #18303b20; }
.picture-window > i { background: var(--preview-brand); border-radius: 2px; }
.picture-window > span { display: flex; gap: 5px; }
.picture-window b { flex: 1; border-radius: 3px; background: var(--preview-soft); }
.dark-preview .picture-window { background: #101e32e8; border-color: #7b9cbd55; }
.picture-check { position: absolute; right: 8px; top: 8px; display: grid; place-items: center; width: 24px; height: 24px; color: white; background: #244045; border-radius: 50%; }
.plain-options { grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.plain-option { padding: 10px; }
.plain-option .appearance-name { gap: 5px; font-size: 12px; }
.plain-swatch { display: flex; height: 26px; overflow: hidden; border-radius: 5px; background: var(--preview-soft); }
.plain-swatch i { width: 35%; background: var(--preview-brand); }
.plain-swatch b { flex: 1; }
.font-option { padding: 16px; }
.appearance-option .font-license-badge { margin-left: auto; padding: 2px 6px; border-radius: 4px; color: var(--brand); background: var(--brand-soft); font-size: 10px; font-weight: 400; }
.font-sample { display: grid; gap: 8px; margin-top: 20px; padding: 24px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface-muted); }
.font-sample > span { margin-bottom: 6px; color: var(--text-secondary); font-size: 12px; }
.font-sample > strong { font-size: 22px; font-weight: 600; }
.font-sample p { font-size: 16px; color: var(--ink); }
.appearance-dialog .font-load-status { min-height: 22px; margin-top: 12px; font-size: 12px; }
.font-licenses { display: flex; flex-wrap: wrap; gap: 8px 14px; margin-top: 12px; color: var(--text-secondary); font-size: 11px; }
.font-licenses a { text-decoration: underline; text-underline-offset: 3px; }
.appearance-dialog footer { position: sticky; bottom: 0; display: flex; align-items: center; justify-content: flex-end; gap: 10px; padding: 16px 28px; border-top: 1px solid var(--line); background: var(--surface); }
.appearance-current { margin-right: auto; color: var(--text-secondary); font-size: 12px; }
.appearance-current > span { margin: 0 6px; opacity: 0.5; }
@media (max-width: 700px) { .plain-options { grid-template-columns: repeat(3, minmax(0, 1fr)); } .appearance-dialog header, .appearance-body { padding: 20px; } }
@media (max-width: 520px) { .picture-options, .font-options { grid-template-columns: 1fr; } }
</style>
