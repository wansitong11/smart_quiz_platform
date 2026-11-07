# 新电脑与 AI 连接生产服务器

> 适用场景：换电脑后，需要让人或 AI 通过 SSH 连接深圳生产服务器。
>
> 原则：**每台设备生成自己的私钥，只把公钥加入服务器。私钥不进 Git、不发聊天、不放进项目目录。**

## 一、连接信息

```text
服务器 IP：120.25.211.99
SSH 用户：root
部署目录：/opt/smart-quiz
上线地址：https://quiz.stonev2ray.top
```

服务器上的生产密钥在：

```text
/opt/smart-quiz/deploy/env.prod
```

AI 可以执行部署、查看容器和日志，但**不要读取或输出 `env.prod`、管理员密码、模型 Key、AccessKey**。

## 二、新电脑生成独立密钥

在 PowerShell 中执行：

```powershell
ssh-keygen -t ed25519 -a 100 -C "smart-quiz-deploy-新设备名" -f "$env:USERPROFILE\.ssh\smart-quiz-deploy"
Get-Content "$env:USERPROFILE\.ssh\smart-quiz-deploy.pub"
```

`smart-quiz-deploy` 是私钥，不能外传；`smart-quiz-deploy.pub` 是公钥，可以安全粘贴到聊天或服务器。

如果这台电脑主要给 AI 自动连接，生成时不要设置口令，直接连续回车；私钥依靠文件权限保护。

限制 Windows 私钥权限：

```powershell
icacls "$env:USERPROFILE\.ssh\smart-quiz-deploy" /inheritance:r
icacls "$env:USERPROFILE\.ssh\smart-quiz-deploy" /grant:r "$($env:USERNAME):(R)"
```

## 三、把公钥加入服务器

先把新电脑输出的公钥发给一个仍有服务器权限的人或 AI，由其在服务器追加。

在仍有权限的电脑上登录：

```bash
ssh -i <现有私钥> root@120.25.211.99
```

进入服务器后执行：

```bash
install -d -m 700 /root/.ssh
printf '%s\n' 'ssh-ed25519 AAAA... smart-quiz-deploy-新设备名' >> /root/.ssh/authorized_keys
chmod 600 /root/.ssh/authorized_keys
```

把示例中的整段公钥替换成新电脑 `Get-Content ...\.pub` 的真实输出。公钥可以公开，不要误贴私钥。

## 四、新电脑配置 SSH 别名

编辑：

```powershell
notepad "$env:USERPROFILE\.ssh\config"
```

写入：

```text
Host smart-quiz-prod
    HostName 120.25.211.99
    User root
    IdentityFile ~/.ssh/smart-quiz-deploy
    IdentitiesOnly yes
    ServerAliveInterval 30
```

测试：

```powershell
ssh smart-quiz-prod "cd /opt/smart-quiz && docker compose -f docker-compose.prod.yml -f deploy/docker-compose.4g.yml --env-file deploy/env.prod ps"
```

能列出容器状态，就说明新电脑已经可以连接。

## 五、给其他 AI 的连接约定

可以直接把下面这段交给新电脑上的 AI：

```text
生产服务器 SSH 别名：smart-quiz-prod
部署目录：/opt/smart-quiz
常用命令：
  ssh smart-quiz-prod "cd /opt/smart-quiz && docker compose -f docker-compose.prod.yml -f deploy/docker-compose.4g.yml --env-file deploy/env.prod ps"
  ssh smart-quiz-prod "cd /opt/smart-quiz && docker logs --tail=200 smart-quiz-app"

安全边界：
1. 不读取、输出、复制 deploy/env.prod、管理员密码、模型 Key、AccessKey。
2. 不把私钥、env.prod、密码文件提交到 Git。
3. 修改生产配置前先说明影响面和回滚方式。
4. 不执行 docker compose down -v、删除卷或清理数据库。
```

## 六、设备丢失或不再使用

每台设备的公钥注释要带设备名，例如 `smart-quiz-deploy-office-pc`。需要吊销时，在服务器编辑：

```bash
nano /root/.ssh/authorized_keys
```

删除对应设备那一整行，然后确认权限：

```bash
chmod 700 /root/.ssh
chmod 600 /root/.ssh/authorized_keys
```

不要删除 `authorized_keys` 中的其他设备公钥，否则会导致其他电脑无法登录。

## 七、不要做的事

- 不要把私钥放进 `docs/`、项目仓库、Git、网盘公开链接或聊天记录。
- 不要为了省事让多台电脑共用同一把私钥。
- 不要直接复制现有私钥到新电脑；生成新密钥更容易追踪和吊销。
- 不要把 `.pem` 改名为 `.txt` 后提交，Git 历史仍会保留明文。
