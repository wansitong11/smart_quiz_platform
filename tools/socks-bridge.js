#!/usr/bin/env node
/**
 * SSH ProxyCommand 用的 SOCKS5 桥接。
 *
 * 背景：本机到阿里云香港某些 IP 段的路由不通（国际节点可正常访问），
 * 但本机跑着 v2rayN（SOCKS5 在 127.0.0.1:10808），于是把 SSH 流量绕进代理。
 *
 * 用法（由 ssh 自动调用）：
 *   ssh -o ProxyCommand="node socks-bridge.js %h %p" user@host
 *
 * 可用环境变量覆盖代理地址：SOCKS_HOST / SOCKS_PORT
 */

const net = require('node:net');

const targetHost = process.argv[2];
const targetPort = Number(process.argv[3]);

if (!targetHost || !Number.isInteger(targetPort)) {
  process.stderr.write('socks-bridge: 用法 node socks-bridge.js <host> <port>\n');
  process.exit(1);
}

const proxyHost = process.env.SOCKS_HOST || '127.0.0.1';
const proxyPort = Number(process.env.SOCKS_PORT || 10808);

const socket = net.connect(proxyPort, proxyHost);
socket.setNoDelay(true);
process.stdout.on('error', () => {});
process.stdin.on('error', () => {});
socket.on('error', () => process.exit(1));
socket.on('close', () => process.exit(0));

let phase = 'GREETING';
let buffer = Buffer.alloc(0);

socket.on('connect', () => {
  socket.write(Buffer.from([0x05, 0x01, 0x00])); // SOCKS5，无认证
});

socket.on('data', (chunk) => {
  if (phase === 'DATA') {
    process.stdout.write(chunk);
    return;
  }

  buffer = Buffer.concat([buffer, chunk]);

  if (phase === 'GREETING') {
    if (buffer.length < 2) return;
    if (buffer[0] !== 0x05 || buffer[1] !== 0x00) process.exit(1);
    buffer = buffer.subarray(2);

    const addr = Buffer.from(targetHost, 'ascii');
    const request = Buffer.concat([
      Buffer.from([0x05, 0x01, 0x00, 0x03, addr.length]),
      addr,
      Buffer.from([(targetPort >> 8) & 0xff, targetPort & 0xff]),
    ]);
    socket.write(request);
    phase = 'CONNECT';
    return;
  }

  if (phase === 'CONNECT') {
    if (buffer.length < 5) return;
    if (buffer[1] !== 0x00) process.exit(1);

    const atyp = buffer[3];
    let total;
    if (atyp === 0x01) total = 4 + 4 + 2;
    else if (atyp === 0x04) total = 4 + 16 + 2;
    else total = 4 + 1 + buffer[4] + 2;
    if (buffer.length < total) return;

    const leftover = buffer.subarray(total);
    phase = 'DATA';
    if (leftover.length > 0) process.stdout.write(leftover);
    process.stdin.pipe(socket);
  }
});
