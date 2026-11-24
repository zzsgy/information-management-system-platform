<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { api, setProfile, type Profile } from "./api";
import { usageBlob, type ExportUsage } from "./exports";

/** 页面只接收不透明使用任务号，版本、批准页和份数完全由服务器清单决定。 */
const usage = new URLSearchParams(location.search).get("usage") || "",
  manifest = ref<ExportUsage | null>(null),
  images = ref<string[]>([]),
  landscape = ref<boolean[]>([]),
  ready = ref(false),
  busy = ref(false),
  error = ref(""),
  note = ref("正在验证批准并加载页面…");
let alive = true,
  timer: ReturnType<typeof setInterval> | undefined;
/** 撤权、到期或组件卸载后立即撤销Blob引用并移除页面内容。 */
function clear() {
  ready.value = false;
  images.value.forEach((url) => URL.revokeObjectURL(url));
  images.value = [];
}
async function validate() {
  const current = await api<ExportUsage>(
    `/archive/print-requests/${usage}`,
    {},
    false,
  );
  if (current.state !== "READY") throw new Error("打印副本尚未就绪");
  return current;
}
/** 不并发解码全部页面；服务端最多50个打印输出页，限制浏览器内存。 */
async function load() {
  try {
    if (!/^[0-9a-f-]{36}$/.test(usage)) throw new Error("打印任务无效");
    setProfile(await api<Profile>("/auth/me", {}, false));
    manifest.value = await validate();
    if (manifest.value.pageCount > 50)
      throw new Error("打印页数超过当前电脑端上限，请缩小申请范围");
    for (let page = 1; page <= manifest.value.pageCount && alive; page++) {
      const blob = await usageBlob(
        `/archive/print-requests/${usage}/pages/${page}`,
        crypto.randomUUID(),
      );
      if (!alive) return;
      const url = URL.createObjectURL(blob);
      images.value.push(url);
      note.value = `正在准备 ${page} / ${manifest.value.pageCount} 页`;
    }
    await validate();
    if (!alive) return;
    ready.value = true;
    note.value = "批准份数已展开到页面。请在系统打印对话框中选择1份。";
    timer = setInterval(() => {
      validate().catch((failure) => {
        clear();
        error.value = (failure as Error).message;
        note.value = "授权已失效，页面已关闭。";
        if (timer) clearInterval(timer);
      });
    }, 4000);
  } catch (failure) {
    clear();
    error.value = (failure as Error).message;
    note.value = "无法取得当前授权打印内容。";
  }
}
/** 仅声明已调用浏览器打印流程；无法确认是否出纸，取消或另存均不能自动返还额度。 */
async function print() {
  busy.value = true;
  error.value = "";
  try {
    await validate();
    await Promise.all(
      Array.from(document.querySelectorAll(".paper img")).map((img) =>
        (img as HTMLImageElement).decode(),
      ),
    );
    window.print();
    await feedback("SUBMITTED");
    note.value = "已提交客户端打印流程，实际出纸结果未核验。";
  } catch (failure) {
    error.value = (failure as Error).message;
    clear();
  } finally {
    busy.value = false;
  }
}
/** 客户端反馈单独幂等，不触发重新生成、不修改原批准额度。 */
async function feedback(result: string) {
  await api(`/archive/print-requests/${usage}/feedback`, {
    method: "POST",
    headers: { "X-Operation-Id": crypto.randomUUID() },
    body: JSON.stringify({
      feedback: result,
      submittedAt: new Date().toISOString(),
    }),
  });
}
onMounted(load);
onUnmounted(() => {
  alive = false;
  if (timer) clearInterval(timer);
  clear();
});
</script>

<template>
  <header class="print-toolbar">
    <div>
      <span>档案 · 授权使用副本</span>
      <h1>受批打印</h1>
      <p>{{ note }}</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
    </div>
    <div class="print-actions">
      <span v-if="manifest">{{ manifest.pageCount }} 个输出页 · {{ manifest.unwatermarked ? "已批准无水印" : "本人水印" }}</span
      ><button :disabled="!ready || busy" @click="print">提交打印</button>
    </div>
  </header>
  <main :class="{ authorized: ready }">
    <figure
      v-for="(image, index) in images"
      :key="image"
      class="paper"
      :class="{ landscape: landscape[index] }"
      :style="{ page: landscape[index] ? 'landscape' : 'portrait' }"
    >
      <img
        :src="image"
        :alt="`授权打印第${index + 1}页`"
        @load="
          landscape[index] =
            ($event.target as HTMLImageElement).naturalWidth >
            ($event.target as HTMLImageElement).naturalHeight
        "
      />
      <figcaption>{{ index + 1 }} / {{ manifest?.pageCount }}</figcaption>
    </figure>
  </main>
</template>

<style>
* {
  box-sizing: border-box;
}
body {
  margin: 0;
  background: var(--canvas);
  color: var(--ink);
  font: 14px/1.6 var(--interface-font-family);
  min-width: 0;
}
.print-toolbar {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  padding: 16px 28px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  box-shadow: var(--shadow-panel);
}
.print-toolbar span {
  font-size: 12px;
  color: var(--text-secondary);
}
.print-toolbar h1 {
  font-size: 22px;
  margin: 5px 0;
}
.print-toolbar p {
  margin: 8px 0 0;
  color: var(--text-secondary);
}
.print-actions {
  display: flex;
  gap: 24px;
  align-items: center;
}
button {
  background: var(--brand);
  border: 0;
  color: var(--brand-contrast);
  font: inherit;
  font-weight: 600;
  padding: 9px 18px;
  border-radius: var(--radius-control);
  cursor: pointer;
}
button:disabled {
  opacity: 0.45;
  cursor: default;
}
.error {
  color: var(--danger) !important;
}
.paper {
  margin: 28px auto;
  width: min(800px, calc(100% - 28px));
  background: white;
  color-scheme: light;
  color: #111;
  font-family: var(--font-system);
  padding: 0;
  box-shadow: 0 6px 24px #1c3f3518;
}
.paper img {
  width: 100%;
  height: auto;
  display: block;
}
.paper figcaption {
  text-align: center;
  padding: 10px;
  color: #475569;
}
main:not(.authorized) {
  visibility: hidden;
}
@media screen and (max-width: 640px) {
  .print-toolbar { position: static; flex-wrap: wrap; gap: 12px; padding: 16px; }
  .print-actions { flex-wrap: wrap; gap: 12px; }
}
@media print {
  body {
    color: black;
    font-family: var(--font-system);
    background: white;
    min-width: 0;
  }
  .print-toolbar,
  figcaption {
    display: none !important;
  }
  .paper {
    margin: 0;
    width: 100%;
    box-shadow: none;
    break-after: page;
    break-inside: avoid;
  }
  .paper:last-child {
    break-after: auto;
  }
  .paper img {
    width: 100%;
    max-height: 270mm;
    object-fit: contain;
  }
  .paper.landscape img {
    max-height: 190mm;
  }
  main:not(.authorized) {
    display: none;
  }
  @page {
    size: A4;
    margin: 10mm;
  }
  @page portrait {
    size: A4 portrait;
    margin: 10mm;
  }
  @page landscape {
    size: A4 landscape;
    margin: 10mm;
  }
}
</style>
