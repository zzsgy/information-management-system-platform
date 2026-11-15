import { DriveHash } from "./driveHash";
/** 文件摘要在Worker分片计算，上传和目录交互不会因大文件占用主线程。 */
self.onmessage = async (event: MessageEvent<File>) => {
  try {
    const file = event.data,
      hash = new DriveHash();
    for (let offset = 0; offset < file.size; offset += 8 * 1024 * 1024) {
      hash.update(
        new Uint8Array(
          await file.slice(offset, offset + 8 * 1024 * 1024).arrayBuffer(),
        ),
      );
      self.postMessage({
        progress: Math.min(
          100,
          Math.round(((offset + 8 * 1024 * 1024) / file.size) * 100),
        ),
      });
    }
    self.postMessage({ hash: hash.digest() });
  } catch {
    self.postMessage({ error: "文件摘要计算失败，请重新选择文件。" });
  }
};
