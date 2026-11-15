import { describe, expect, it } from "vitest";
import {
  archiveLocation,
  matchChecklistFiles,
} from "../../src/platform/archiveChecklist";
const lines = [
  { id: "one", itemNo: 1, title: "合同甲" },
  { id: "ten", itemNo: 10, title: "合同乙.docx" },
];
describe("电子清单文件对应", () => {
  it("序号前导零、中文分隔符和WPS生成的Office扩展名对应真实条目", () => {
    expect(
      matchChecklistFiles(["001_合同甲.docx", "10、合同乙.pdf"], lines).map(
        (x) => x.recordId,
      ),
    ).toEqual(["one", "ten"]);
  });
  it("数字开头的合同名称优先按完整标题匹配，不能错当序号", () => {
    expect(
      matchChecklistFiles(
        ["2026年度合同.pdf"],
        [{ id: "year", itemNo: 2, title: "2026年度合同" }],
      )[0].recordId,
    ).toBe("year");
  });
  it("序号名称矛盾不猜测，同名与重复上传不自动分配", () => {
    expect(matchChecklistFiles(["1_合同乙.pdf"], lines)[0].recordId).toBe("");
    expect(
      matchChecklistFiles(["合同甲.pdf", "合同甲.docx"], lines).every(
        (x) => !x.recordId,
      ),
    ).toBe(true);
    expect(
      matchChecklistFiles(
        ["合同甲.pdf"],
        [...lines, { id: "duplicate", itemNo: 3, title: "合同甲" }],
      )[0].recordId,
    ).toBe("");
  });
  it("存放位置带单位组合且兼容历史位置", () => {
    expect(
      archiveLocation({ cabinetNo: "13", columnNo: "A", layerNo: "3" }),
    ).toBe("13柜 / A列 / 3层");
    expect(archiveLocation({}, "原库房")).toBe("原库房");
  });
});
