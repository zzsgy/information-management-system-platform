<script setup lang="ts">
import { ref } from "vue";
import Icon from "./PlatformIcon.vue";

/** 原生弹窗提供键盘焦点限制和 Esc 关闭能力，仅承载反馈界面预览。 */
const dialog = ref<HTMLDialogElement | null>(null);
/** 当前会话中的反馈类型选项，不对应后台分类配置。 */
const feedbackTypes = ["使用建议", "问题反馈", "其他"];
/** 用户在本地预览中选择的反馈分类，初始为使用建议。 */
const feedbackType = ref("使用建议");
/** 输入内容仅保存在当前页面内存中，刷新页面即清除。 */
const feedbackContent = ref("");
/** 预览提交后的提示；明确告知尚未发送，避免产生真实提交的误解。 */
const submissionHint = ref("");

/** 打开表单时保留本次页面中的输入，仅重置上一次操作提示。 */
function open() {
  submissionHint.value = "";
  dialog.value?.showModal();
}

/** 关闭表单后浏览器会将焦点返回首页的意见反馈按钮。 */
function close() {
  dialog.value?.close();
}

/** 只演示提交按钮的反馈，不调用接口、不保存记录，也不显示虚假的成功状态。 */
function previewSubmission() {
  submissionHint.value = "当前为界面预览，反馈尚未发送。";
}

/** 仅向父组件开放显示表单的方法，不暴露用户输入内容。 */
defineExpose({ open });
</script>

<template>
  <dialog ref="dialog" class="p-feedback-dialog" aria-labelledby="feedback-title" aria-describedby="feedback-intro" @click="(event) => { if (event.target === dialog) close(); }">
    <button class="p-icon-button p-feedback-close" aria-label="关闭意见反馈" @click="close"><Icon name="close" /></button>
    <div class="p-feedback-heading"><span class="p-feedback-symbol"><Icon name="message" :size="25" /></span><div><h2 id="feedback-title">意见反馈</h2><p id="feedback-intro">你的建议，让工作平台更好用。</p></div></div>
    <form class="p-feedback-form" @submit.prevent="previewSubmission">
      <fieldset><legend>反馈类型</legend><div class="p-feedback-types"><label v-for="type in feedbackTypes" :key="type"><input v-model="feedbackType" type="radio" name="feedback-type" :value="type" /><span>{{ type }}</span></label></div></fieldset>
      <label for="feedback-content" class="p-feedback-label">意见内容</label>
      <textarea id="feedback-content" v-model="feedbackContent" autofocus required maxlength="1000" rows="6" placeholder="说说遇到的问题，或者你希望改进的地方…" @input="submissionHint = ''" />
      <div class="p-feedback-caption"><span>界面预览 · 内容不会提交</span><span>{{ feedbackContent.length }} / 1000</span></div>
      <p v-if="submissionHint" class="p-feedback-status" role="status">{{ submissionHint }}</p>
      <div class="p-feedback-footer"><button type="button" class="p-feedback-cancel" @click="close">取消</button><button type="submit" class="p-feedback-submit" :disabled="!feedbackContent.trim()">提交反馈<Icon name="arrow" :size="17" /></button></div>
    </form>
  </dialog>
</template>

<style scoped>
/* 反馈弹窗延续首页的绿色视觉体系；窄屏保持边距并允许内容纵向滚动。 */
.p-feedback-dialog { width: min(520px, calc(100% - 36px)); max-height: calc(100dvh - 36px); margin: auto; padding: 30px; border: 1px solid #d6e5dc; border-radius: 18px; background: #fff; color: #2e453b; box-shadow: 0 24px 90px #172e2838; }
.p-feedback-dialog::backdrop { background: #173d305c; backdrop-filter: blur(3px); }
.p-feedback-close { position: absolute; top: 12px; right: 12px; }
.p-feedback-heading { display: flex; align-items: center; gap: 15px; margin: 8px 0 28px; }
.p-feedback-symbol { display: grid; place-items: center; flex-shrink: 0; width: 50px; height: 50px; border-radius: 14px; color: #267657; background: #e9f3ed; }
.p-feedback-heading h2 { font-size: 22px; font-weight: 600; }
.p-feedback-heading p { margin-top: 7px; color: #71877a; font-size: 12px; line-height: 1.6; }
.p-feedback-form fieldset { margin: 0 0 23px; padding: 0; border: 0; }
.p-feedback-form legend, .p-feedback-label { display: block; margin-bottom: 12px; font-size: 13px; font-weight: 600; }
.p-feedback-types { display: flex; gap: 10px; }
.p-feedback-types label { position: relative; flex: 1; cursor: pointer; }
.p-feedback-types input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.p-feedback-types span { display: block; padding: 10px 6px; border: 1px solid #e1e9e3; border-radius: 8px; text-align: center; font-size: 12px; color: #718479; }
.p-feedback-types input:checked + span { border-color: #81b59a; background: #edf6ef; color: #256a4d; font-weight: 600; }
.p-feedback-types input:focus-visible + span { outline: 3px solid #75b5a6; outline-offset: 3px; }
.p-feedback-form textarea { display: block; width: 100%; min-height: 150px; padding: 13px; resize: vertical; border: 1px solid #dce5df; border-radius: 9px; font: inherit; font-size: 13px; line-height: 1.8; color: #304e3d; background: #fbfdfb; }
.p-feedback-form textarea::placeholder { color: #91a095; }
.p-feedback-form textarea:focus-visible { outline: 2px solid #81b59a; outline-offset: 2px; }
.p-feedback-caption { display: flex; justify-content: space-between; gap: 10px; margin-top: 10px; font-size: 10px; color: #84978a; }
.p-feedback-status { margin-top: 15px; padding: 10px 12px; border-radius: 7px; background: #f2f6ee; color: #60734d; font-size: 12px; line-height: 1.6; }
.p-feedback-footer { display: flex; justify-content: flex-end; gap: 12px; margin-top: 27px; }
.p-feedback-cancel, .p-feedback-submit { display: inline-flex; align-items: center; justify-content: center; gap: 12px; padding: 11px 19px; border: 1px solid #dce5df; border-radius: 8px; background: #fff; font-size: 13px; }
.p-feedback-submit { border-color: #267657; background: #267657; color: #fff; }
.p-feedback-submit:hover:not(:disabled) { background: #1c6146; }
.p-feedback-submit:disabled { opacity: .45; cursor: not-allowed; }
@media (max-width: 760px) { .p-feedback-dialog { padding: 24px 20px; }.p-feedback-heading { gap: 12px; }.p-feedback-heading h2 { font-size: 20px; }.p-feedback-heading p { font-size: 11px; }.p-feedback-symbol { width: 44px; height: 44px; }.p-feedback-types { gap: 7px; } }
</style>
