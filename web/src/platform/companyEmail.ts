import { pinyin } from "pinyin-pro";
import { computed, ref, watch, type Ref } from "vue";

const compoundSurnames = new Set(
  (
    "欧阳 太史 端木 上官 司马 东方 独孤 南宫 万俟 闻人 夏侯 诸葛 尉迟 公羊 " +
    "赫连 澹台 皇甫 宗政 濮阳 公冶 太叔 申屠 公孙 慕容 仲孙 钟离 长孙 宇文 " +
    "司徒 鲜于 司空 闾丘 子车 亓官 司寇 巫马 公西 颛孙 壤驷 公良 漆雕 乐正 " +
    "宰父 谷梁 拓跋 夹谷 轩辕 令狐 段干 百里 呼延 东郭 南门 羊舌 微生 西门 " +
    "公户 公玉 公仪 梁丘 公仲 公上 公门 公山 公坚 左丘 公伯 公祖 第五 公乘 " +
    "贯丘 公皙 南荣 东里 东宫 仲长 子书 子桑 即墨 达奚 褚师 纳兰"
  ).split(" "),
);

/** 仅生成新增表单的建议值；姓名不完整或无法转为邮箱字母时由经办人填写。 */
export function companyEmailFromName(value: string): string {
  const name = value.replace(/\s+/g, "");
  if (!/^\p{Script=Han}{2,}$/u.test(name)) return "";
  const surnameLength = compoundSurnames.has(name.slice(0, 2)) ? 2 : 1;
  const parts = pinyin(name, {
    toneType: "none",
    type: "array",
    surname: "head",
    v: true,
  });
  const surname = parts.slice(0, surnameLength).join("");
  const givenName = parts.slice(surnameLength).join("");
  if (!/^[a-z]+$/.test(surname) || !/^[a-z]+$/.test(givenName)) return "";
  return `${givenName}.${surname}@biolink.com`;
}

/** 新增表单的邮箱联动状态；清空手填值后恢复自动模式，已有账号不参与联动。 */
export function useCompanyEmail(name: Ref<string>, enabled: Ref<boolean>) {
  const email = ref("");
  const edited = ref(false);
  const suggestion = computed(() => companyEmailFromName(name.value));

  function fill(force = false) {
    if (!enabled.value || (!force && edited.value && email.value.trim()))
      return;
    edited.value = false;
    email.value = suggestion.value;
  }
  function edit(value: string) {
    email.value = value;
    edited.value = value.trim().length > 0;
  }
  function reset(value: string) {
    edited.value = false;
    email.value = value;
  }
  // 姓名提交后立即更新，失焦/聚焦时也调用同一逻辑补齐空邮箱。
  watch(name, () => fill(), { flush: "sync" });
  return { email, edited, suggestion, fill, edit, reset };
}
