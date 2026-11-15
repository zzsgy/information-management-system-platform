/** 局域网测试专用Office TCP桥：保留TLS/WebSocket，仅接受配置网段，不改变WSL与回调端口。 */
"use strict";
const fs = require("node:fs"),
  net = require("node:net"),
  assert = require("node:assert/strict");
const config = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const [network, prefixText] = config.allowedSubnet.split("/"),
  prefix = Number(prefixText);
assert.ok(
  net.isIPv4(config.listenAddress) &&
    net.isIPv4(network) &&
    Number.isInteger(prefix) &&
    prefix >= 1 &&
    prefix <= 32,
  "必须提供局域网IPv4地址和明确网段",
);
assert.equal(config.listenPort, 18457, "局域网桥仅服务Office端口");
/** IPv4转换用于一次性配置核对和连接来源判断，不在数据流中重复解析。 */
const addressNumber = (value) =>
  value.split(".").reduce((number, part) => (number << 8) | Number(part), 0) >>>
  0;
const mask = (0xffffffff << (32 - prefix)) >>> 0,
  allowed = (addressNumber(network) & mask) >>> 0;
assert.equal(
  (addressNumber(config.listenAddress) & mask) >>> 0,
  allowed,
  "监听地址必须属于允许网段",
);
const server = net.createServer((socket) => {
  if ((addressNumber(socket.remoteAddress) & mask) >>> 0 !== allowed) {
    socket.destroy();
    return;
  }
  const upstream = net.connect(18457, "127.0.0.1");
  socket.pipe(upstream);
  upstream.pipe(socket);
  socket.on("error", () => upstream.destroy());
  upstream.on("error", () => socket.destroy());
  socket.on("close", () => upstream.destroy());
  upstream.on("close", () => socket.destroy());
});
server.listen(config.listenPort, config.listenAddress, () => {
  fs.writeFileSync(
    config.pidFile,
    JSON.stringify({
      pid: process.pid,
      listenAddress: config.listenAddress,
      listenPort: config.listenPort,
      allowedSubnet: config.allowedSubnet,
    }),
  );
});
