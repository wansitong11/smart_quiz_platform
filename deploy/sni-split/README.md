# 443 端口 SNI 分流：让应用和现有 Xray 共存

## 原理

```
浏览器 / 代理客户端
        |
        | TCP 443
        v
  [sni-split: nginx stream, ssl_preread]
        |                          |
   SNI = quiz.stonev2ray.top    其它 SNI（sto1.stonev2ray.top 等）
        |                          |
        v                          v
  127.0.0.1:8443             127.0.0.1:10443
  [应用网关 nginx]             [Xray]
```

nginx 只读 ClientHello 里的 SNI，**不解密、不终止 TLS**。所以 Xray 的证书、TLS 指纹、
XTLS 行为都不变，客户端配置一行都不用改。多出来的只是一段回环跳转，延迟在微秒级。

## 阶段 0：勘察（只读，不动任何东西）

```bash
# Xray 是 systemd 还是容器
systemctl list-units --type=service --state=running --no-pager | grep -Ei 'x-ui|xray'
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}'

# 443 的 TCP / UDP 占用（重点看有没有 UDP 443）
sudo ss -lntup | grep ':443'

# 应用要用的回环端口是否空闲
sudo ss -lntp | grep -E ':(8443|8080)\b'

# 是否有公网 IPv6（决定分流器要不要 listen [::]:443）
ip -6 addr show scope global

# 面板证书文件在哪（通配符证书 *.stonev2ray.top）
sudo ls -l /root/cert/ 2>/dev/null
sudo grep -rE '"certificateFile"|"keyFile"' /usr/local/x-ui/bin/config.json 2>/dev/null
```

## 阶段 1：部署应用栈（不碰 443，零影响）

```bash
# 1. 安装 Docker（已装可跳过）
curl -fsSL https://get.docker.com | sh
systemctl enable --now docker

# 2. 拉代码
sudo mkdir -p /opt/smart-quiz && sudo chown "$USER" /opt/smart-quiz
git clone https://github.com/wansitong11/smart_quiz_platform.git /opt/smart-quiz
cd /opt/smart-quiz

# 3. 生成密钥并填进 env.prod（把 CHANGE_ME_* 全部替换掉）
openssl rand -base64 48 | tr -d '/+=' | head -c 44
cp deploy/env.prod.example deploy/env.prod
vim deploy/env.prod
chmod 600 deploy/env.prod

# 4. 把面板那张通配符证书放到应用能看到的位置
#    （路径以阶段 0 查到的为准，下面按 3x-ui 常见路径举例）
mkdir -p /opt/smart-quiz/certs
sudo cp /root/cert/*/fullchain.pem /opt/smart-quiz/certs/fullchain.pem
sudo cp /root/cert/*/privkey.pem   /opt/smart-quiz/certs/privkey.pem
sudo chmod 644 /opt/smart-quiz/certs/*

# 5. 启动（网关只绑 127.0.0.1:8443 和 127.0.0.1:8080，公网 443 完全不受影响）
docker compose -f docker-compose.prod.yml --env-file deploy/env.prod up -d --build
docker compose -f docker-compose.prod.yml --env-file deploy/env.prod ps
```

验证应用本身（在服务器上执行）：

```bash
curl -k -I --resolve quiz.stonev2ray.top:8443:127.0.0.1 https://quiz.stonev2ray.top:8443/
# 期望：HTTP/2 200
```

## 阶段 2：干跑分流器（仍然不碰 443）

```bash
sudo mkdir -p /opt/sni-split/logs && sudo chown -R "$USER" /opt/sni-split
cp -r /opt/smart-quiz/deploy/sni-split/. /opt/sni-split/
cd /opt/sni-split

# 干跑：监听 9443，xray_backend 仍指向现状 127.0.0.1:443
SNI_SPLIT_CONF=nginx.dryrun.conf docker compose up -d
docker compose logs --tail 20
```

验证两条链路都通：

```bash
# A. 代理流量：用真实 SNI 连，应该拿到 Xray 的通配符证书
openssl s_client -connect 127.0.0.1:9443 -servername sto1.stonev2ray.top </dev/null 2>/dev/null \
  | openssl x509 -noout -subject -dates
# 期望：subject=CN = stonev2ray.top，并且不含报错

# B. 应用流量：应该返回应用首页
curl -k -I --resolve quiz.stonev2ray.top:9443:127.0.0.1 https://quiz.stonev2ray.top:9443/
# 期望：HTTP/2 200

# C. 看分流日志，确认 SNI 识别正确
tail -n 20 /opt/sni-split/logs/sni-access.log
```

A 和 B 都通过，说明"nginx 转发对 Xray 完全透明"，可以进入切换。

## 阶段 3：正式切换（有几秒中断，是正常现象）

```bash
# 1) 面板里把入站端口 443 改成 10443，监听 IP 填 127.0.0.1，保存
#    （3x-ui 会自动重启 Xray；这一步执行后 443 会短暂无人监听）

# 2) 立刻把分流器切到正式配置（监听 443）
cd /opt/sni-split
docker compose down
SNI_SPLIT_CONF= docker compose up -d      # 不加变量 = 用默认的 nginx.conf
docker compose logs --tail 20
```

验证：

```bash
# 应用（从你自己的电脑访问）
curl -I https://quiz.stonev2ray.top

# 代理：客户端断开重连一次，确认能正常上网

# 确认 443 现在是分流器在监听
sudo ss -lntp | grep ':443'
```

## 回滚（一条命令的事）

```bash
# 1) 停掉分流器，把 443 释放出来
cd /opt/sni-split && docker compose down

# 2) 面板里把入站端口改回 443、监听 IP 清空，保存
```

## 踩坑对照表

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 分流器起不来，日志报 `bind() to 0.0.0.0:443 failed` | 443 还被 Xray 占着 | 先在面板把 Xray 改到 10443 |
| 分流器起不来，报 `bind() to [::]:443 failed` | 服务器没有公网 IPv6，却开了 `listen [::]:443` | 注释掉那一行 |
| 代理连不上，应用正常 | `map` 里的域名写错，或 `xray_backend` 端口不对 | 看 `/opt/sni-split/logs/sni-access.log` 里的 `sni=` 和 `upstream=` |
| 应用返回 502 | 应用网关没在 `127.0.0.1:8443` 上 | `docker compose -f docker-compose.prod.yml ps` 查看 gateway，并确认 `ss -lntp \| grep 8443` |
| 代理用一会儿就断 | `proxy_timeout` 太小 | 本仓库配置已设为 `1h` |
| 应用发版后代理闪断 | 分流器和应用在同一套 compose 里 | 本方案已把分流器做成独立项目；应用发版不会重启它 |
