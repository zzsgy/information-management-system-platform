<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { formattedText, reportMarkup, richMarkup } from "./formattedText";
import { vNumbered } from "./meetingNumbered";

/** 编辑器复用原正文字段长度，格式标记也计入保存上限。 */
const props = withDefaults(defineProps<{
  label: string;
  limit?: number;
  rows?: number;
  placeholder?: string;
  required?: boolean;
  disabled?: boolean;
}>(), { rows: 4 });
const model = defineModel<string>({ default: "" });
const editor = ref<HTMLElement>();
const validator = ref<HTMLTextAreaElement>();
const attempted = ref(false);
const tools = [
  ["bold", "加粗", "B"],
  ["italic", "斜体", "I"],
  ["underline", "下划线", "U"],
  ["insertUnorderedList", "项目列表", "•"],
  ["insertOrderedList", "编号列表", "1."],
] as const;

/** 外部清空或加载另一条记录时才写 DOM，避免用户输入时光标跳动。 */
function updateEditor() {
  const element = editor.value;
  if (!element || formattedText(element.innerText, element.innerHTML) === model.value) return;
  const markup = richMarkup(model.value);
  if (markup === undefined) element.textContent = model.value;
  else element.innerHTML = markup;
}
onMounted(updateEditor);
watch(model, updateEditor, { flush: "post" });

function updateModel() {
  if (editor.value) model.value = formattedText(editor.value.innerText, editor.value.innerHTML);
}
/** 会前材料同时保存完整纯文本，供纪要自动汇总。 */
function read() {
  return {
    text: editor.value?.innerText.trim() ?? "",
    html: reportMarkup(editor.value?.innerHTML ?? ""),
  };
}
defineExpose({ read });
function format(command: string) {
  editor.value?.focus();
  document.execCommand(command);
  updateModel();
}
function paste(event: ClipboardEvent) {
  const markup = reportMarkup(event.clipboardData?.getData("text/html") ?? "");
  if (markup) document.execCommand("insertHTML", false, markup);
  else document.execCommand("insertText", false, event.clipboardData?.getData("text/plain") ?? "");
  updateModel();
}
function focusInvalid(event: Event) {
  event.preventDefault();
  attempted.value = true;
  editor.value?.focus();
}
watch(model, (value) => validator.value?.setCustomValidity(
  props.limit && value.length > props.limit ? `正文及格式超过 ${props.limit} 字符` : "",
), { flush: "post" });
</script>

<template>
  <div class="rich-field">
    <span class="rich-label">{{ label }}</span>
    <div class="rich-editor">
    <div class="rich-toolbar" role="toolbar" :aria-label="label + '格式'">
      <button
        v-for="[command, name, glyph] in tools"
        :key="command"
        :class="command"
        type="button"
        :title="name"
        :aria-label="name"
        :disabled="disabled"
        @mousedown.prevent
        @click="format(command)"
      >{{ glyph }}</button>
    </div>
    <div
      ref="editor"
      v-numbered
      class="rich-input"
      :contenteditable="!disabled"
      :style="{ minHeight: (rows * 1.5 + 1.5) + 'em' }"
      role="textbox"
      :aria-label="label"
      aria-multiline="true"
      :aria-required="required || undefined"
      :aria-disabled="disabled || undefined"
      :data-placeholder="placeholder"
      @input="updateModel"
      @paste.prevent="paste"
      @drop.prevent
    />
    <textarea
      ref="validator" class="rich-validator" tabindex="-1" aria-hidden="true"
      :value="model" :required="required" :disabled="disabled" @invalid="focusInvalid"
    />
    <p v-if="limit && model.length > limit" class="form-error" role="alert">
      正文及格式超过 {{ limit }} 字符，请缩短后保存。
    </p>
    <p v-else-if="attempted && required && !model.trim()" class="form-error" role="alert">
      请填写{{ label }}。
    </p>
    </div>
  </div>
</template>

<style scoped>
.rich-field {
  min-width: 0;
  display: grid;
  gap: 6px;
}
.rich-label {
  font-size: 13px;
  font-weight: 500;
}
.rich-editor {
  min-width: 0;
  position: relative;
  border: 1px solid var(--line, #d6e0e7);
  border-radius: 8px;
  background: var(--surface);
  color: var(--ink);
}
.rich-editor:focus-within {
  border-color: var(--brand, #00828a);
}
.rich-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 5px 8px;
  border-bottom: 1px solid var(--line, #d6e0e7);
  background: var(--surface-muted, #f4f7f9);
  border-radius: 8px 8px 0 0;
}
.rich-toolbar button {
  min-width: 32px;
  min-height: 30px;
  padding: 2px 6px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: inherit;
}
.rich-toolbar button:hover:not(:disabled),
.rich-toolbar button:focus-visible {
  border-color: var(--line, #d6e0e7);
  background: var(--surface);
}
.bold { font-weight: 700; }
.italic { font-style: italic; }
.underline { text-decoration: underline; }
.rich-input {
  padding: 10px 12px;
  outline: none;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.rich-input:empty::before {
  content: attr(data-placeholder);
  color: var(--text-secondary);
  pointer-events: none;
}
textarea.rich-validator {
  position: absolute;
  width: 1px;
  height: 1px;
  min-height: 0;
  opacity: 0;
  padding: 0;
  pointer-events: none;
}
.rich-editor .form-error {
  margin: 0;
  padding: 0 12px 8px;
}
</style>
