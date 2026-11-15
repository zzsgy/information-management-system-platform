/** 本机WSL演练专用桥接：只绑定既定虚拟网卡，只接收该发行版来源，不开放物理局域网。 */
const fs = require('node:fs');
const net = require('node:net');
const config = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (net.isIP(config.hostAddress) !== 4 || net.isIP(config.guestAddress) !== 4 || !config.mappings?.length) throw Error('缺少WSL虚拟网卡地址或端口配置');
for (const mapping of config.mappings) {
  if (![mapping.listenPort, mapping.targetPort].every(port => Number.isInteger(port) && port > 1024 && port < 65536)) throw Error('桥接端口必须处于1025至65535');
  net.createServer(socket => {
    if (socket.remoteAddress !== config.guestAddress) { socket.destroy(); return; }
    const upstream = net.connect(mapping.targetPort, '127.0.0.1');
    socket.pipe(upstream); upstream.pipe(socket);
    socket.on('error', () => upstream.destroy()); upstream.on('error', () => socket.destroy());
    socket.on('close', () => upstream.destroy()); upstream.on('close', () => socket.destroy());
  }).listen(mapping.listenPort, config.hostAddress);
}
fs.writeFileSync(config.pidFile, JSON.stringify({ pid: process.pid, hostAddress: config.hostAddress, guestAddress: config.guestAddress }));
