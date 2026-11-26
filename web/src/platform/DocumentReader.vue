<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import Icon from "../preview/PlatformIcon.vue";
import { ApiError, pageImage } from "./api";

const props = defineProps<{
  title: string;
  version: number;
  pageCount: number;
  pagePath: string;
  loading: boolean;
  loadingMessage?: string;
  retryPageErrors?: boolean;
}>();
const emit = defineEmits<{ close: []; error: [message: string] }>();
const viewport = ref<HTMLElement>(),
  closeButton = ref<HTMLButtonElement>();
const currentPage = ref(1),
  scale = ref(window.matchMedia("(max-width: 760px)").matches ? "width" : "100"),
  viewportWidth = ref(1000);
const images = ref<Record<number, string>>({}),
  ratios = ref<Record<number, string>>({}),
  pageErrors = ref<Record<number, string>>({});
const width = computed(() =>
  scale.value === "width"
    ? viewportWidth.value
    : (816 * Number(scale.value)) / 100,
);
const pages = new Map<number, HTMLElement>(),
  nearby = new Set<number>(),
  controllers = new Map<number, AbortController>();
const pending: number[] = [];
let observer: IntersectionObserver,
  resize: ResizeObserver,
  active = true,
  running = 0,
  frame = 0;
let previousFocus: HTMLElement | null,
  previousOverflow = "";

/** 按可见范围串行取页，给预览准备保留渲染名额；离开后释放正文。 */
function drain() {
  while (active && running < 1 && pending.length) {
    // 先取当前阅读页，预取页让出网络和后台渲染名额。
    const current = pending.indexOf(currentPage.value);
    const number = current >= 0 ? pending.splice(current, 1)[0] : pending.shift()!;
    if (!nearby.has(number) || images.value[number] || controllers.has(number))
      continue;
    delete pageErrors.value[number];
    const controller = new AbortController();
    controllers.set(number, controller);
    running++;
    void pageImage(`${props.pagePath}/${number}`, controller.signal)
      .then((blob) => {
        if (active && nearby.has(number) && !controller.signal.aborted)
          images.value[number] = URL.createObjectURL(blob);
      })
      .catch((failure) => {
        if (active && !controller.signal.aborted) {
          const message = (failure as Error).message;
          const retryable = failure instanceof ApiError &&
            ["NETWORK_UNAVAILABLE", "READING_BUSY", "RENDER_TIMEOUT"].includes(failure.code);
          if (props.retryPageErrors && retryable) pageErrors.value[number] = message;
          else emit("error", message);
        }
      })
      .finally(() => {
        controllers.delete(number);
        running--;
        drain();
      });
  }
}
function observePages() {
  if (!observer) return;
  observer.disconnect();
  for (const element of pages.values()) observer.observe(element);
}
function pageElement(element: unknown, number: number) {
  if (element instanceof HTMLElement) pages.set(number, element);
  else pages.delete(number);
}
function updatePage() {
  if (frame) return;
  frame = requestAnimationFrame(() => {
    frame = 0;
    if (!viewport.value) return;
    const line =
      viewport.value.getBoundingClientRect().top +
      Math.min(200, viewport.value.clientHeight / 3);
    let number = 1;
    for (const [index, element] of pages) {
      if (element.getBoundingClientRect().top > line) break;
      number = index;
    }
    currentPage.value = number;
  });
}
/** 页码输入是唯一的数值入口；定位后仍可继续上下滚动。 */
function jump(event: Event) {
  const input = event.target as HTMLInputElement;
  const number = Math.max(
    1,
    Math.min(
      props.pageCount,
      Math.round(Number(input.value) || currentPage.value),
    ),
  );
  input.value = String(number);
  pages.get(number)?.scrollIntoView({ block: "start" });
  currentPage.value = number;
}
async function zoom(value: string) {
  const number = currentPage.value;
  scale.value = value;
  await nextTick();
  pages.get(number)?.scrollIntoView({ block: "start" });
}
function stepZoom(direction: number) {
  const current =
    scale.value === "width" ? (width.value / 816) * 100 : Number(scale.value);
  void zoom(
    String(
      Math.max(
        50,
        Math.min(200, Math.round(current / 25) * 25 + direction * 25),
      ),
    ),
  );
}
function dimensions(event: Event, number: number) {
  const image = event.target as HTMLImageElement;
  ratios.value[number] = `${image.naturalWidth} / ${image.naturalHeight}`;
}
function keys(event: KeyboardEvent) {
  if (event.key === "Escape") emit("close");
  if (event.key !== "Tab") return;
  const elements = [
    ...(event.currentTarget as HTMLElement).querySelectorAll<HTMLElement>(
      'button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex="0"]',
    ),
  ];
  const first = elements[0],
    last = elements.at(-1);
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first?.focus();
  }
}
watch(
  () => props.pageCount,
  async () => {
    await nextTick();
    observePages();
  },
);
onMounted(() => {
  previousFocus = document.activeElement as HTMLElement;
  previousOverflow = document.body.style.overflow;
  document.body.style.overflow = "hidden";
  closeButton.value?.focus();
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        const number = Number((entry.target as HTMLElement).dataset.page);
        if (entry.isIntersecting) {
          nearby.add(number);
          pending.push(number);
        } else {
          nearby.delete(number);
          controllers.get(number)?.abort();
          if (images.value[number]) {
            URL.revokeObjectURL(images.value[number]);
            delete images.value[number];
          }
        }
      }
      drain();
    },
    { root: viewport.value, rootMargin: "600px 0px" },
  );
  // 内容盒已经扣除桌面和窄屏各自的内边距，“适合宽度”直接使用正文可用空间。
  resize = new ResizeObserver(([entry]) => {
    viewportWidth.value = entry.contentRect.width;
  });
  resize.observe(viewport.value!);
  observePages();
});
onUnmounted(() => {
  active = false;
  observer.disconnect();
  resize.disconnect();
  cancelAnimationFrame(frame);
  for (const controller of controllers.values()) controller.abort();
  for (const url of Object.values(images.value)) URL.revokeObjectURL(url);
  document.body.style.overflow = previousOverflow;
  if (previousFocus?.isConnected) previousFocus.focus();
});
</script>

<template>
  <section
    class="document-reader"
    role="dialog"
    aria-modal="true"
    :aria-label="`文档阅读器：${title}`"
    @keydown="keys"
  >
    <header class="reader-heading">
      <span class="reader-file-icon"><Icon name="file" :size="25" /></span>
      <div class="reader-title">
        <h2 :title="title">{{ title }}</h2>
        <span>文档阅读器 <i>·</i> V{{ version }}</span>
      </div>
      <button
        ref="closeButton"
        class="secondary reader-close"
        @click="emit('close')"
      >
        <Icon name="close" :size="18" />关闭阅读
      </button>
    </header>
    <div class="reader-toolbar" aria-label="阅读工具栏">
      <span class="reader-mode"><Icon name="file" :size="16" />连续阅读</span>
      <div class="reader-position">
        <span>页码</span
        ><input
          :value="currentPage"
          aria-label="跳转页码"
          type="number"
          min="1"
          :max="pageCount || 1"
          :disabled="!pageCount"
          @change="jump"
          @keydown.enter="($event.target as HTMLInputElement).blur()"
        /><span>/ {{ pageCount || "…" }}</span>
      </div>
      <div class="reader-zoom">
        <button
          class="reader-tool"
          aria-label="缩小"
          :disabled="loading || scale === '50'"
          @click="stepZoom(-1)"
        >
          −
        </button>
        <select
          aria-label="缩放比例"
          :value="scale"
          @change="zoom(($event.target as HTMLSelectElement).value)"
        >
          <option value="width">适合宽度</option>
          <option
            v-for="value in [50, 75, 100, 125, 150, 175, 200]"
            :key="value"
            :value="String(value)"
          >
            {{ value }}%
          </option>
          <option
            v-if="
              scale !== 'width' &&
              ![50, 75, 100, 125, 150, 175, 200].includes(Number(scale))
            "
            :value="scale"
          >
            {{ scale }}%
          </option>
        </select>
        <button
          class="reader-tool"
          aria-label="放大"
          :disabled="loading || scale === '200'"
          @click="stepZoom(1)"
        >
          +
        </button>
      </div>
    </div>
    <main
      ref="viewport"
      class="reader-viewport"
      tabindex="0"
      aria-label="文档连续阅读区"
      @scroll.passive="updatePage"
    >
      <div v-if="loading" class="reader-loading" role="status">
        <span class="reader-spinner" />{{ loadingMessage ?? "正在准备文档…" }}
      </div>
      <div v-else class="reader-pages" :style="{ width: `${width}px` }">
        <figure
          v-for="number in pageCount"
          :key="number"
          :ref="(element) => pageElement(element, number)"
          :data-page="number"
          class="reader-page"
          :aria-label="`第${number}页`"
          :style="{ aspectRatio: ratios[number] || '210 / 297' }"
        >
          <img
            v-if="images[number]"
            :src="images[number]"
            :alt="`第${number}页`"
            draggable="false"
            @load="dimensions($event, number)"
          />
          <div v-else-if="pageErrors[number]" class="reader-page-loading reader-page-error" role="status">
            <span>{{ pageErrors[number] }}</span>
            <button class="secondary" @click="pending.unshift(number); drain()">重试这一页</button>
          </div>
          <span v-else class="reader-page-loading">第 {{ number }} 页 · 正在加载</span>
          <figcaption>{{ number }} / {{ pageCount }}</figcaption>
        </figure>
      </div>
    </main>
    <footer class="reader-status">
      <span>上下滚动，连续浏览文档</span
      ><span>{{
        pageCount ? `第 ${currentPage} 页，共 ${pageCount} 页` : "正在准备"
      }}</span>
    </footer>
  </section>
</template>

<style scoped>
.document-reader {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  flex-direction: column;
  background: var(--canvas);
  color: var(--ink);
}
.reader-heading {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 28px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
}
.reader-file-icon {
  width: 44px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: var(--brand-soft);
  color: var(--brand);
  flex-shrink: 0;
}
.reader-title {
  flex: 1;
  min-width: 0;
}
.reader-title h2 {
  margin: 0 0 4px;
  font-size: 17px;
  font-weight: 600;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.reader-title span {
  color: var(--text-secondary);
  font-size: 12px;
}
.reader-title i {
  margin: 0 7px;
  font-style: normal;
}
.reader-close {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.reader-toolbar {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8px 24px;
  padding: 9px 24px;
  background: var(--surface-muted);
  border-bottom: 1px solid var(--line);
  font-size: 13px;
}
.reader-mode,
.reader-position,
.reader-zoom {
  display: flex;
  align-items: center;
  gap: 10px;
}
.reader-mode {
  color: var(--text-secondary);
}
.reader-position {
  margin: 0;
}
.reader-toolbar .reader-position {
  display: flex;
  align-items: center;
}
.reader-position input {
  width: 62px;
  min-width: 0;
  padding: 6px;
  text-align: center;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--surface);
  height: 32px;
}
.reader-zoom {
  padding-left: 24px;
  border-left: 1px solid var(--line);
  gap: 4px;
}
.reader-zoom select {
  height: 32px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--surface);
  padding: 0 10px;
  color: inherit;
}
.reader-tool {
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  font-size: 21px;
  cursor: pointer;
  color: inherit;
}
.reader-tool:hover {
  background: var(--brand-soft);
}
.reader-tool:disabled {
  opacity: 0.35;
  cursor: default;
}
.reader-viewport {
  flex: 1;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  padding: 32px 40px 48px;
  scroll-padding-top: 28px;
  outline-offset: -3px;
}
.reader-pages {
  margin: 0 auto;
  max-width: none;
}
.reader-page {
  position: relative;
  width: 100%;
  margin: 0 0 36px;
  background: white;
  box-shadow: 0 2px 9px #23384b20;
  scroll-margin-top: 0;
}
.reader-page img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.reader-page-error {
  align-content: center;
  gap: 12px;
  padding: 20px;
  text-align: center;
}
.reader-page-loading {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: #475569;
  font-size: 14px;
}
.reader-page figcaption {
  position: absolute;
  top: 100%;
  width: 100%;
  padding-top: 8px;
  text-align: center;
  font-size: 11px;
  color: var(--text-secondary);
}
.reader-loading {
  min-height: 50vh;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--text-secondary);
}
.reader-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--line);
  border-top-color: var(--brand);
  border-radius: 50%;
  animation: reader-spin 1s linear infinite;
}
@media (prefers-reduced-motion: reduce) {
  .reader-spinner { animation: none; }
}
@keyframes reader-spin {
  to {
    transform: rotate(360deg);
  }
}
.reader-status {
  padding: 8px 28px;
  display: flex;
  justify-content: space-between;
  background: var(--surface-muted);
  border-top: 1px solid var(--line);
  color: var(--text-secondary);
  font-size: 12px;
}
@media (max-width: 760px) {
  .reader-heading {
    padding: 12px 16px;
  }
  .reader-toolbar {
    gap: 14px;
    padding-inline: 12px;
  }
  .reader-mode {
    display: none;
  }
  .reader-zoom {
    padding-left: 14px;
  }
  .reader-viewport {
    padding-inline: 24px;
  }
  .reader-close {
    padding-inline: 10px;
  }
}
</style>
