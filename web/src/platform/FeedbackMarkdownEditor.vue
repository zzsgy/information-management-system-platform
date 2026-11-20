<script setup lang="ts">
import { nextTick, ref, useId } from "vue";
import FeedbackMarkdownView from "./FeedbackMarkdownView.vue";
import { referencedImages, type FeedbackImageDraft } from "./feedbackMarkdown";

const props = defineProps<{
  label: string;
  limit: number;
  rows: number;
  placeholder?: string;
  disabled?: boolean;
}>();
const content = defineModel<string>({ required: true });
const images = defineModel<FeedbackImageDraft[]>("images", { required: true });
const preparing = defineModel<boolean>("preparing", { required: true });
const editorId = useId();
const editor = ref<HTMLTextAreaElement>();
const chooser = ref<HTMLInputElement>();
const previewing = ref(false);
const error = ref("");
const maxImages = 3;
const maxImageBytes = 2 * 1024 * 1024;

/** 预览使用浏览器读取的图片，不在提交前上传。 */
function readImage(file: File): Promise<FeedbackImageDraft> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () =>
      resolve({ id: crypto.randomUUID(), dataUrl: reader.result as string });
    reader.onerror = () => reject(new Error("无法读取截图，请重试"));
    reader.readAsDataURL(file);
  });
}

/** 在光标处插入 Markdown 图片引用，拒绝会超出总量或正文长度的操作。 */
async function addImages(files: File[]) {
  if (!files.length || props.disabled || preparing.value) return;
  error.value = "";
  const start = editor.value?.selectionStart ?? content.value.length;
  const end = editor.value?.selectionEnd ?? start;
  const before = content.value.slice(0, start);
  const after = content.value.slice(end);
  const kept = referencedImages(before + after, images.value);
  if (kept.length + files.length > maxImages) {
    error.value = "最多可添加 3 张截图";
    return;
  }
  if (
    files.some(
      (file) =>
        !["image/png", "image/jpeg"].includes(file.type) ||
        file.size > maxImageBytes,
    )
  ) {
    error.value = "请添加不超过 2 MB 的 PNG 或 JPEG 图片";
    return;
  }
  preparing.value = true;
  try {
    const added = await Promise.all(files.map(readImage));
    const markup = added
      .map(
        ({ id }, i) => `![截图 ${kept.length + i + 1}](feedback-image/${id})`,
      )
      .join("\n\n");
    const block = (before ? "\n\n" : "") + markup + "\n\n";
    const value = before + block + after;
    if (value.length > props.limit) {
      error.value = "正文已接近长度上限，请精简后再添加截图";
      return;
    }
    images.value = [...kept, ...added];
    content.value = value;
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    preparing.value = false;
    await nextTick();
    editor.value?.focus();
    if (!error.value) {
      // 截图引用插入后，光标留在引用后、原有后续正文前。
      const caret = content.value.length - after.length;
      editor.value?.setSelectionRange(caret, caret);
    }
  }
}

function paste(event: ClipboardEvent) {
  const files = Array.from(event.clipboardData?.files ?? []).filter((file) =>
    file.type.startsWith("image/"),
  );
  if (!files.length) return;
  event.preventDefault();
  void addImages(files);
}

function selectFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  input.value = "";
  void addImages(files);
}
</script>

<template>
  <div class="feedback-editor">
    <label :for="editorId">{{ label }}</label>
    <div class="feedback-editor-box">
      <div class="feedback-editor-toolbar">
        <button
          type="button"
          class="text-button"
          :aria-pressed="!previewing"
          :disabled="preparing"
          @click="previewing = false"
        >
          编辑
        </button>
        <button
          type="button"
          class="text-button"
          :aria-pressed="previewing"
          :disabled="preparing"
          @click="previewing = true"
        >
          预览
        </button>
        <button
          type="button"
          class="text-button feedback-add-image"
          :disabled="disabled || preparing"
          @click="
            previewing = false;
            chooser?.click();
          "
        >
          添加截图
        </button>
        <input
          ref="chooser"
          type="file"
          accept="image/png,image/jpeg"
          multiple
          hidden
          @change="selectFiles"
        />
      </div>
      <textarea
        v-show="!previewing"
        :id="editorId"
        ref="editor"
        v-model="content"
        :rows="rows"
        :maxlength="limit"
        :placeholder="placeholder"
        :disabled="disabled || preparing"
        :aria-describedby="editorId + '-help'"
        @paste="paste"
      />
      <div v-if="previewing" class="feedback-editor-preview">
        <FeedbackMarkdownView
          v-if="content.trim()"
          :value="content"
          :images="images"
        />
        <p v-else class="muted">还没有内容可预览。</p>
      </div>
    </div>
    <p :id="editorId + '-help'" class="muted feedback-editor-help">
      支持 Markdown 和直接粘贴截图，最多 3 张 PNG 或 JPEG，每张不超过 2 MB。
    </p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
.feedback-editor > label {
  display: block;
  margin-bottom: 6px;
}
.feedback-editor-box {
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}
.feedback-editor-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 9px;
  border-bottom: 1px solid var(--line);
  background: var(--surface-muted);
}
.feedback-editor-toolbar button {
  min-height: 32px;
  padding: 4px 9px;
  border-radius: 5px;
}
.feedback-editor-toolbar button[aria-pressed="true"] {
  color: var(--ink);
  font-weight: 600;
  background: var(--surface);
}
.feedback-add-image {
  margin-left: auto;
}
.feedback-editor textarea {
  display: block;
  width: 100%;
  padding: 12px;
  border: 0;
  border-radius: 0;
  font: inherit;
  line-height: 1.6;
  resize: vertical;
}
.feedback-editor-preview {
  min-height: 160px;
  padding: 12px;
}
.feedback-editor-help {
  margin-block: 6px 0;
  font-size: 13px;
}
</style>
