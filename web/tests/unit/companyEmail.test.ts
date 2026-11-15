import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { effectScope, ref, type EffectScope, type Ref } from "vue";
import {
  companyEmailFromName,
  useCompanyEmail,
} from "../../src/platform/companyEmail";

describe("新增人员公司邮箱建议", () => {
  it.each([
    ["赵振帅", "zhenshuai.zhao@biolink.com"],
    ["何子慧", "zihui.he@biolink.com"],
    ["李鹏霞", "pengxia.li@biolink.com"],
    ["张三", "san.zhang@biolink.com"],
    ["欧阳明", "ming.ouyang@biolink.com"],
    ["司马光", "guang.sima@biolink.com"],
    ["单乐乐", "lele.shan@biolink.com"],
    ["吕明", "ming.lv@biolink.com"],
    [" 赵 振帅 ", "zhenshuai.zhao@biolink.com"],
  ])("%s 按名、姓顺序生成邮箱", (name, expected) => {
    expect(companyEmailFromName(name)).toBe(expected);
  });

  it.each(["", "赵", "欧阳", "John Smith", "赵123", "赵🙂"])(
    "不完整或无法转换的姓名 %s 保留手动填写",
    (name) => expect(companyEmailFromName(name)).toBe(""),
  );
});

describe("新增人员邮箱联动", () => {
  let scope: EffectScope;
  let name: Ref<string>;
  let enabled: Ref<boolean>;
  let field: ReturnType<typeof useCompanyEmail>;
  beforeEach(() => {
    scope = effectScope();
    name = ref("");
    enabled = ref(true);
    field = scope.run(() => useCompanyEmail(name, enabled))!;
  });
  afterEach(() => scope.stop());

  it("姓名更改立即填入邮箱", () => {
    name.value = "何子慧";
    expect(field.email.value).toBe("zihui.he@biolink.com");
  });
  it("修正姓名、清空手填邮箱后，失焦恢复自动生成", () => {
    name.value = "BLK27016";
    field.edit("he");
    name.value = "何子慧";
    expect(field.email.value).toBe("he");
    field.edit("");
    field.fill();
    expect(field.email.value).toBe("zihui.he@biolink.com");
    expect(field.edited.value).toBe(false);
  });
  it("非空手填邮箱保留，明确重新生成时才替换", () => {
    name.value = "何子慧";
    field.edit("custom.he@biolink.com");
    name.value = "赵振帅";
    field.fill();
    expect(field.email.value).toBe("custom.he@biolink.com");
    field.fill(true);
    expect(field.email.value).toBe("zhenshuai.zhao@biolink.com");
  });
  it("新开表单恢复自动模式", () => {
    name.value = "赵振帅";
    field.edit("custom.zhao@biolink.com");
    name.value = "";
    field.reset("");
    name.value = "何子慧";
    expect(field.email.value).toBe("zihui.he@biolink.com");
  });
  it("编辑已有账号始终保留原邮箱", () => {
    enabled.value = false;
    field.reset("existing@biolink.com");
    name.value = "何子慧";
    field.fill(true);
    expect(field.email.value).toBe("existing@biolink.com");
  });
});
