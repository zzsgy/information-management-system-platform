/**
 * 扫描接收参考代理（Node 22+）：node scan-agent.cjs <私有配置.json> <受控批次清单.json>。
 * 配置字段：baseUrl（HTTPS）、serviceId、keyFile（私有密钥绝对路径）、caFile（可选企业 CA）、
 * stagingRoot（批准暂存绝对目录）、journalRoot（受控回执绝对目录）。
 * 清单字段：manifestId（每轮作业固定 UUID）、deviceId、batchId、items[{itemId,source,receivedPages}]。
 * source 只能是 stagingRoot 内相对路径。断线重试复用清单和日志，不复制到其它目录，不自动删原件。
 */
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const https = require("node:https");
/** 服务端固定分片大小为 8 MiB，单文件最多 1 GiB。 */
const PART_SIZE = 8 * 1024 * 1024;
/** UUID 标识不能用路径或任意字符串代替。 */
function uuid(value) { if (typeof value !== "string" || !/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(value)) throw new Error("作业标识格式错误"); return value; }
/** 只接收约定字段，防止拼错配置被静默忽略。 */
function only(value, keys) { if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).some(key => !keys.includes(key))) throw new Error("配置或清单含不支持的字段"); }
/** 对字节计算签名输入，不输出业务正文。 */
function hash(bytes) { return crypto.createHash("sha256").update(bytes).digest("hex"); }
/** 流式文件摘要不随整文件大小占用内存。 */
async function fileHash(file) { const digest = crypto.createHash("sha256"); for await (const block of fs.createReadStream(file)) digest.update(block); return digest.digest("hex"); }
/** 所有本地状态在原受控目录原子写入，未知结果不会丢失稳定操作号。 */
function persist(file, value) { const temporary = file + "." + crypto.randomUUID() + ".tmp"; const descriptor = fs.openSync(temporary, "wx", 0o600); try { fs.writeFileSync(descriptor, JSON.stringify(value, null, 2)); fs.fsyncSync(descriptor); } finally { fs.closeSync(descriptor); } fs.renameSync(temporary, file); }
/** 严格验证目录边界和实际路径，不跟随指向其它目录的链接。 */
function within(root, relative) { if (typeof relative !== "string" || path.isAbsolute(relative)) throw new Error("扫描文件必须使用受控相对路径"); const target = path.resolve(root, relative); if (!target.startsWith(root + path.sep)) throw new Error("扫描文件超出批准暂存目录"); const actual = fs.realpathSync(target); if (actual !== target || !fs.statSync(actual).isFile()) throw new Error("扫描文件不是当前目录内普通文件"); return actual; }
/** 校验 HTTPS 证书和服务器身份；不支持忽略证书错误的运行参数。 */
function request(config, secret, method, route, body = Buffer.alloc(0), extra = {}) {
  const time = String(Math.floor(Date.now() / 1000)), nonce = crypto.randomUUID(), digest = hash(body);
  const signature = crypto.createHmac("sha256", secret).update([method, route, time, nonce, digest].join("\n")).digest("hex");
  return new Promise((resolve, reject) => {
    const call = https.request(new URL(route, config.baseUrl), { method, ca: config.caFile ? fs.readFileSync(config.caFile) : undefined, rejectUnauthorized: true, timeout: 60000,
      headers: { "X-Service-Id": config.serviceId, "X-Service-Time": time, "X-Service-Nonce": nonce, "X-Service-Signature": signature, "X-Content-Sha256": digest, "Content-Length": body.length, "Content-Type": "application/json", ...extra } }, response => {
      const parts = []; let length = 0;
      response.on("data", chunk => { length += chunk.length; if (length > 2 * 1024 * 1024) { response.destroy(); reject(new Error("回执超过大小上限")); } else parts.push(chunk); });
      response.on("error", reject); response.on("end", () => { try { const result = JSON.parse(Buffer.concat(parts).toString("utf8")); if (response.statusCode !== 200 || result.code !== "OK") { const error = new Error(result.message || "扫描请求未完成"); error.code = result.code; reject(error); } else resolve(result.data); } catch (error) { reject(error); } });
    });
    call.on("timeout", () => call.destroy(new Error("连接超时，保留原暂存与重试记录"))); call.on("error", reject); call.end(body);
  });
}
/** 每轮清单串行接收，控制内存和服务器负载；失败项保留并继续登记其它项结果。 */
async function run(configFile, manifestFile) {
  const config = JSON.parse(fs.readFileSync(configFile, "utf8")), manifest = JSON.parse(fs.readFileSync(manifestFile, "utf8"));
  only(config, ["baseUrl", "serviceId", "keyFile", "caFile", "stagingRoot", "journalRoot"]); only(manifest, ["manifestId", "deviceId", "batchId", "items"]);
  const url = new URL(config.baseUrl); if (url.protocol !== "https:" || url.username || url.password || url.pathname !== "/" || url.search || url.hash) throw new Error("接收地址必须是无凭据及查询参数的 HTTPS 站点根地址");
  uuid(config.serviceId); uuid(manifest.manifestId); uuid(manifest.deviceId); uuid(manifest.batchId);
  for (const key of ["keyFile", "stagingRoot", "journalRoot"]) if (!path.isAbsolute(config[key])) throw new Error("私有配置必须使用绝对路径");
  const root = fs.realpathSync(config.stagingRoot), journalRoot = fs.realpathSync(config.journalRoot), secret = fs.readFileSync(config.keyFile, "utf8").trim(); if (secret.length < 43) throw new Error("扫描服务密钥配置无效");
  if (!Array.isArray(manifest.items) || !manifest.items.length || manifest.items.length > 50 || new Set(manifest.items.map(item => item.itemId)).size !== manifest.items.length) throw new Error("清单必须为 1 至 50 个不重复扫描项");
  const manifestHash = hash(Buffer.from(JSON.stringify(manifest))), journalFile = path.join(journalRoot, manifest.manifestId + ".json");
  // 同一清单只允许一个本机进程写回执；崩溃遗留锁须人工核查，不能猜测超时后抢占。
  const lockFile = journalFile + ".lock", lock = fs.openSync(lockFile, "wx", 0o600);
  try {
  fs.writeFileSync(lock, JSON.stringify({ pid: process.pid, startedAt: new Date().toISOString() }));
  fs.fsyncSync(lock);
  const journal = fs.existsSync(journalFile) ? JSON.parse(fs.readFileSync(journalFile, "utf8")) : { manifestHash, items: {} };
  if (journal.manifestHash !== manifestHash) throw new Error("同一轮清单不能改写；返工请新建清单标识并保留旧记录");
  const json = (method, route, body) => request(config, secret, method, route, Buffer.from(JSON.stringify(body)));
  let failed = 0;
  for (const item of manifest.items) {
    only(item, ["itemId", "source", "receivedPages"]); uuid(item.itemId); if (!Number.isSafeInteger(item.receivedPages) || item.receivedPages < 1 || item.receivedPages > 10000) throw new Error("请填写实际扫描页数");
    const current = journal.items[item.itemId] ||= { operationId: crypto.randomUUID(), eventId: crypto.randomUUID(), receiptKey: crypto.randomUUID(), state: "PENDING" };
    persist(journalFile, journal);
    try {
      const file = within(root, item.source), size = fs.statSync(file).size; if (!file.toLowerCase().endsWith(".pdf") || size < 1 || size > 1024 ** 3) throw new Error("扫描文件必须是 1 GiB 内 PDF");
      const digest = await fileHash(file); if (current.sha256 && current.sha256 !== digest) throw new Error("本轮扫描原件已被替换；保留原日志并按新轮次办理"); current.sha256 = digest; persist(journalFile, journal);
      if (!current.uploadId) { const result = await json("POST", "/internal/archive/scan-upload-sessions", { operationId: current.operationId, batchId: manifest.batchId, itemId: item.itemId, originalName: path.basename(file), expectedSize: size, expectedSha256: digest, expectedMediaType: "application/pdf" }); current.uploadId = result.id; persist(journalFile, journal); }
      const route = "/internal/archive/scan-upload-sessions/" + current.uploadId;
      let status = await request(config, secret, "GET", route);
      if (status.state === "REJECTED") throw new Error("文件检测拒绝，请人工处理，不自动转存或删除");
      if (status.state === "OPEN") {
        const descriptor = fs.openSync(file, "r");
        try { for (let number = 1; number <= Math.ceil(size / PART_SIZE); number++) { const offset = (number - 1) * PART_SIZE, data = Buffer.alloc(Math.min(PART_SIZE, size - offset)); let read = 0; while (read < data.length) { const count = fs.readSync(descriptor, data, read, data.length - read, offset + read); if (!count) throw new Error("扫描文件在读取期间改变"); read += count; }
          const prior = status.parts.find(part => part.partNo === number); if (prior) { if (prior.sha256 !== hash(data) || prior.byteSize !== data.length) throw new Error("已接收分片与本地原件不一致"); continue; }
          await request(config, secret, "PUT", route + "/parts/" + number, data, { "Content-Type": "application/octet-stream", "Content-Range": `bytes ${offset}-${offset + data.length - 1}/${size}` });
        } } finally { fs.closeSync(descriptor); }
      }
      if (status.state !== "COMPLETE") { await json("POST", route + "/complete", { partCount: Math.ceil(size / PART_SIZE), finalSha256: digest }); for (let poll = 0; poll < 60; poll++) { status = await request(config, secret, "GET", route); if (["COMPLETE", "REJECTED"].includes(status.state) || status.jobState === "FAILED") break; await new Promise(resolve => setTimeout(resolve, 1000)); } }
      if (status.state !== "COMPLETE") throw new Error("检测尚未完成或失败，请查看批次并重试；原暂存已保留");
      current.receipt = await json("POST", "/internal/archive/scan-receipts", { eventId: current.eventId, deviceId: manifest.deviceId, batchId: manifest.batchId, itemId: item.itemId, uploadSessionId: current.uploadId, receiptKey: current.receiptKey, receivedPages: item.receivedPages, sha256: digest }); current.state = "RECEIVED_AWAITING_QUALITY"; current.lastError = null; current.updatedAt = new Date().toISOString(); persist(journalFile, journal);
    } catch (error) { failed++; current.state = "RETRY_REQUIRED"; current.lastError = error.code || "RECEIVE_NOT_COMPLETED"; current.updatedAt = new Date().toISOString(); persist(journalFile, journal); console.error("扫描项 " + item.itemId + " 未完成：" + (error.code || "请查看受控重试记录")); }
  }
  console.log(`本轮 ${manifest.items.length} 项，${failed} 项待处理；全部原暂存文件保留，接收成功项仍待人工质检。`); return failed ? 1 : 0;
  } finally { fs.closeSync(lock); fs.unlinkSync(lockFile); }
}
if (require.main === module) { if (process.argv.length !== 4) { console.error("用法：node scan-agent.cjs <私有配置.json> <受控批次清单.json>"); process.exitCode = 2; } else run(process.argv[2], process.argv[3]).then(code => process.exitCode = code).catch(() => { console.error("扫描配置或清单校验失败；原文件与既有日志保留。"); process.exitCode = 1; }); }
module.exports = { run, within, uuid };
