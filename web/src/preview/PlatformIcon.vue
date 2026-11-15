<script setup lang="ts">
/** 图标名称与像素尺寸；所有图标继承文字颜色，装饰图标不重复朗读。 */
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 20 });
/** 内网可用的本地线性图标，不依赖远程字体或图片服务。 */
const paths: Record<string, string[]> = {
  /** 新增模块图标使用本地路径，保持原型离线可用。 */
  download: ["M12 3v13m-5-5 5 5 5-5", "M4 16v5h16v-5"],
  edit: ["m15 3 6 6M3 21l5-1L21 7l-5-5L3 15z"],
  move: ["M12 2v20M2 12h20", "m8 6 4-4 4 4M8 18l4 4 4-4M6 8l-4 4 4 4M18 8l4 4-4 4"],
  trash: ["M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"],
  history: ["M3 11a9 9 0 1 1 2 7M3 4v7h7", "M12 7v5l4 2"],
  database: ["M21 6c0 2-4 4-9 4S3 8 3 6s4-4 9-4 9 2 9 4ZM3 6v12c0 2 4 4 9 4s9-2 9-4V6M3 12c0 2 4 4 9 4s9-2 9-4"],
  home: ["m3 10 9-7 9 7", "M5 9v11h5v-6h4v6h5V9"],
  grid: ["M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z"],
  folder: ["M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z", "M3 9h18"],
  archive: ["M3 3h18v5H3zM5 8v13h14V8", "M9 12h6M10 16h4"],
  chart: ["M4 3v17h17", "M8 15v-4M13 15V7M18 15V4"],
  arrow: ["M4 12h15m-5-5 5 5-5 5"],
  chevron: ["m9 5 7 7-7 7"],
  down: ["m6 9 6 6 6-6"],
  search: ["M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16Zm6-2 4 4"],
  bell: ["M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"],
  task: ["M8 4H5a2 2 0 0 0-2 2v14h18V6a2 2 0 0 0-2-2h-3M8 2h8v5H8z", "m7 13 3 3 7-7"],
  message: ["M21 15a3 3 0 0 1-3 3H8l-5 3V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z", "M7 8h10M7 12h7"],
  /** 常用网站的外链标识，提示点击将在新标签页打开。 */
  external: ["M14 3h7v7M21 3 10 14", "M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-5"],
  help: ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Z", "M9.2 8a3 3 0 0 1 5.6 1.5c-.7 1.1-2.8 1.3-2.8 3.5M12 17h.01"],
  user: ["M12 13a5 5 0 1 0 0-10 5 5 0 0 0 0 10ZM3 22v-2a6 6 0 0 1 6-6h6a6 6 0 0 1 6 6v2"],
  lock: ["M6 10h12a2 2 0 0 1 2 2v9H4v-9a2 2 0 0 1 2-2ZM8 10V6a4 4 0 0 1 8 0v4", "M12 14v3"],
  eye: ["M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z", "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z"],
  hide: ["m3 3 18 18M10.5 5.1 12 5c6.5 0 10 7 10 7a18 18 0 0 1-3.2 4M6.3 6.4A22 22 0 0 0 2 12s3.5 7 10 7c1.7 0 3.3-.5 4.6-1.1"],
  check: ["m5 12 4 4L19 6"],
  shield: ["M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6Z", "m8 11 3 3 5-6"],
  file: ["M14 2H4v20h16V8l-6-6ZM14 2v6h6M8 12h8M8 16h6"],
  upload: ["M12 16V3m-5 5 5-5 5 5M4 15v6h16v-6"],
  plus: ["M12 4v16M4 12h16"],
  people: ["M9 13a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM2 21v-2a5 5 0 0 1 5-5h4a5 5 0 0 1 5 5v2M16 5a4 4 0 0 1 0 8M18 15a5 5 0 0 1 4 5v1"],
  clock: ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20ZM12 6v6l4 2"],
  calendar: ["M3 5h18v16H3zM3 10h18M7 2v6M17 2v6M7 14h2M12 14h2M7 17h2"],
  star: ["m12 2 3 6 7 1-5 5 1 7-6-3-6 3 1-7-5-5 7-1Z"],
  menu: ["M4 6h16M4 12h16M4 18h16"],
  close: ["m6 6 12 12M6 18 18 6"],
  sun: ["M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5 19 19M5 19l1.5-1.5M17.5 6.5 19 5"],
  logout: ["M10 3H4v18h6M9 12h13m-5-5 5 5-5 5"],
};
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" class="p-icon">
    <path v-for="(path, index) in paths[name] ?? paths.grid" :key="index" :d="path" />
  </svg>
</template>
