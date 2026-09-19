# Flux Panel Enhanced

基于 [bqlpfy/flux-panel](https://github.com/bqlpfy/flux-panel) 继续维护的哆啦A梦转发面板魔改版。原版的节点、用户、隧道和端口转发功能都保留了，这里主要补节点重装恢复、监控和安装部署这些实际使用中容易出问题的地方。

## 这版改了什么

- 节点重装或重新上线后，会把数据库里已启用的转发重新下发，不用再手动编辑保存一遍。
- 隧道支持多个入口节点：新建时填写一个对外域名，一条转发会同步部署到全部入口；将该域名的多条 A/AAAA 记录解析到不同入口即可做容灾或分流。入口节点用于下发规则，不读取节点管理中的管理 IP。
- 调整节点上线、协议配置和 GOST 服务同步逻辑，减少面板状态和实际服务状态不一致。
- 节点页增加 CPU、内存、上下行的实时小曲线和展开曲线。
- Agent 会上报 CPU 核数、内存总量和硬盘容量，方便看节点配置。
- 面板和节点都提供从本仓库安装的一键脚本。

## 功能概览

- TCP / UDP 端口转发与隧道转发
- 节点、用户、隧道及转发规则管理
- 转发限速、配额与流量统计
- 节点在线状态与系统资源监控
- 基于 GOST 的转发服务管理

## 项目结构

| 目录 | 说明 |
| --- | --- |
| `springboot-backend/` | 面板后端与 WebSocket 节点通信服务 |
| `vite-frontend/` | React / Vite 前端 |
| `go-gost/` | 节点 Agent 与 GOST 相关代码 |
| `docker-compose.local.yml` | 本地构建用 Compose 示例 |

## 本地构建

```bash
git clone git@github.com:kfcv301-maker/ix.git
cd ix
docker compose -f docker-compose.local.yml build
```

正式环境部署前，请自行准备数据库、反向代理、域名和安全配置。

## 一键安装

以下命令会下载本仓库的脚本；面板端从源码构建 Docker 镜像，首次构建需要一些时间。请在你拥有授权的 Linux 服务器上以 root 或 sudo 执行。

### 安装面板

```bash
curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/panel_install.sh | sudo bash
```

默认使用前端端口 `6366`、后端端口 `6365`，数据库密码与 JWT 密钥在服务器本机的 `/opt/flux-panel-enhanced/.env` 自动生成，脚本不会把它们上传到 GitHub。需要自定义端口时：

```bash
curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/panel_install.sh | sudo env FRONTEND_PORT=8080 BACKEND_PORT=6365 bash
```

更新面板（保留数据库卷与 `.env`）：

```bash
curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/panel_install.sh | sudo bash -s -- update
```

### 从原版哆啦A梦面板升级

原版 `bqlpfy/flux-panel` 的数据库放在 Docker 卷 `mysql_data` 中。下面的迁移脚本会先在新版目录创建一份仅留在服务器本机的 SQL 备份，再复用这个数据卷和原 `.env` 启动新版；节点、用户、隧道、转发、流量和密钥不需要导入或重新创建。原面板目录和原容器不会被删除；如果新版后端未通过检查，脚本会自动恢复原容器。

在原版面板的安装目录运行：

```bash
curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/upgrade_from_original.sh | sudo bash
```

原版不在当前目录时，填写其目录：

```bash
curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/upgrade_from_original.sh | sudo bash -s -- --source-dir /path/to/original-panel
```

先只检查兼容性、不做任何修改可加 `--dry-run`。迁移完成后，后续更新仍使用上面的 `panel_install.sh ... update` 命令；它会保留原数据库卷和 `.env`。

### 安装节点 Agent

在面板的节点管理页面复制安装命令即可。安装命令会自动带入当前面板域名和该节点独立密钥，无须设置或暴露后端固定端口：

```bash
curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/install.sh | sudo bash -s -- --panel 'https://panel.example.com' --token '节点独立密钥'
```

脚本会根据服务器架构下载 `amd64` 或 `arm64` Agent，并在写入 `/etc/flux-panel-agent/gost` 前校验 SHA-256。它会创建 `flux-panel-agent.service` 并立即启动；重新安装 Agent 会短暂重启该节点进程，但不会修改面板数据库中的节点与转发记录。

安装命令自动读取管理员当前浏览器的面板域名。例如从 `https://panel.example.com` 打开面板时，Agent 使用 `wss://panel.example.com/system-info` 保持通信，并通过 `https://panel.example.com/flow/*` 上报数据；不再要求管理员在网站配置填写 IP 或后端端口。新 Agent 将独立节点密钥放在 `Authorization: Bearer` 请求头中；后端仍接受旧 Agent 的 URL 密钥参数，已有节点无需重装。域名的反向代理须把 `/system-info`（WebSocket）、`/flow/upload` 与 `/flow/config` 转到后端，并使用有效 HTTPS 证书。

节点安装时会先检测内核版本、CPU、内存、默认出口网卡以及 BBR/FQ 支持，再做 TCP 调优，最后才下载和启动 Agent。调优写入 `/etc/sysctl.d/99-flux-panel-network.conf`，不需要额外确认。面板生成安装命令时可选下面四档；未传档位的旧命令仍会按机器配置自动选择。

| 档位 | 建议机器配置 | 单连接收发缓存上限 | 接入 / SYN / 收包队列 |
| --- | --- | --- | --- |
| `tiny` | 内存低于 512 MB | 4 MB | 2048 / 1024 / 2048 |
| `small` | 512 MB 至 1 GB 内存 | 8 MB | 4096 / 2048 / 4096 |
| `balanced` | 内存低于 2 GB、单核，或无法读到内存 | 12 MB | 8192 / 4096 / 4096 |
| `standard` | 至少 2 GB 内存、至少双核 | 16 MB | 16384 / 8192 / 8192 |

所有档位都会开启接收缓存自动调节、MTU 探测、TCP Fast Open，并关闭空闲慢启动。`standard` 正好使用你给出的最大参数，其余档位只会往下收缩，不会超出 16 MB、16384、8192 的上限。支持 BBR/FQ 的节点启用 BBR/FQ；旧内核缺少其中某项时脚本会自动保留可用算法、跳过不支持的参数，Agent 仍会继续安装。需要跳过调优可加 `--skip-tcp-tuning`：

```bash
curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/install.sh | sudo bash -s -- --panel 'https://panel.example.com' --token '节点独立密钥' --skip-tcp-tuning
```

### Cloudflare DDNS（可选）

在节点管理中点击“安装”，开启 Cloudflare DDNS 后填写 API Token 和完整记录域名即可生成一条命令。首次成功生成后，这两个字段会作为该节点的安装预设保存在当前管理员浏览器；下次打开同一节点会自动带回，不写入面板数据库。安装完成及每次开机后的首次任务都会核验 Cloudflare 记录，仅在不一致时更新；节点运行期间每 1 分钟检查公网 IPv4/IPv6，地址变化时才调用 Cloudflare 更新记录。每种记录类型只能有一条，若同一个 DDNS 域名存在多条 A 或 AAAA 记录，脚本会拒绝修改以避免误改。AWS 换机后重跑同一条命令即可恢复。检测到 IPv6 时会创建或更新同名 AAAA 记录；没有可用 IPv6 时不会创建、修改或删除 AAAA 记录。Token 需要 Cloudflare 的 `Zone:Read` 与 `DNS:Edit` 权限。

节点每次连接或重连面板后都会立即上报当前 GOST 配置，面板据此补回重装/重启后确实缺失的转发服务；十分钟一次的周期上报仍保留作漂移校验。这样无需为了恢复规则而手工编辑保存转发。

手动命令格式：

```bash
curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/install.sh | sudo bash -s -- --panel 'https://panel.example.com' --token '节点独立密钥' --cf-api-token 'Cloudflare_API_Token' --cf-record 'node.example.com'
```

## 节点硬件信息说明

面板前端只能展示 Agent 上报的数据。旧版 Agent 只会上报 CPU/内存使用率和流量计数；要显示 CPU 核数、内存总量及硬盘容量，需要升级为包含硬件采集字段的 Agent。升级 Agent 会短暂重启节点侧进程，但不应修改面板数据库中的节点与转发记录。

## 上游项目与许可

本项目基于 [bqlpfy/flux-panel](https://github.com/bqlpfy/flux-panel) 进行二次开发，并依赖 [go-gost/gost](https://github.com/go-gost/gost) 和 [go-gost/x](https://github.com/go-gost/x)。请遵守仓库中的许可证及相关上游项目许可证。

## 使用边界

本项目仅限在你拥有授权的服务器与网络环境中使用。使用者须自行负责部署安全、账号保护、数据备份与所在地法律合规。
