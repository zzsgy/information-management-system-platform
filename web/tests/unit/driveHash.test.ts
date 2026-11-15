import { describe, it, expect } from "vitest";
import { createHash } from "node:crypto";
import { DriveHash } from "../../src/platform/driveHash";
describe("公盘增量文件摘要", () => {
  // 与独立标准库比较，覆盖SHA256补齐边界、跨上传分片与非ASCII内容。
  for (const length of [0, 1, 55, 56, 63, 64, 65, 1000000, 8388611])
    it(`正确处理${length}字节`, () => {
      const data = Uint8Array.from({ length }, (_, i) => (i * 17 + 93) % 256),
        hash = new DriveHash();
      for (let offset = 0; offset < data.length; offset += 7919)
        hash.update(data.subarray(offset, offset + 7919));
      expect(hash.digest()).toBe(
        createHash("sha256").update(data).digest("hex"),
      );
    });
});
