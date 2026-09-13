# Flux Panel Enhanced

基于 [bqlpfy/flux-panel](https://github.com/bqlpfy/flux-panel) 继续维护的哆啦A梦转发面板魔改版。原版的节点、用户、隧道和端口转发功能都保留了，这里主要补节点重装恢复、监控和安装部署这些实际使用中容易出问题的地方。

## 这版改了什么

- 节点重装或重新上线后，会把数据库里已启用的转发重新下发，不用再手动编辑保存一遍。
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

### 安装节点 Agent

在面板的节点管理页面复制安装命令即可。手动安装格式如下，其中 `面板地址` 应是节点能够访问的地址，端口通常为后端端口 `6365`：

```bash
curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/install.sh | sudo bash -s -- --server '面板地址:6365' --secret '节点密钥'
```

脚本会根据服务器架构下载 `amd64` 或 `arm64` Agent，并在写入 `/etc/flux-panel-agent/gost` 前校验 SHA-256。它会创建 `flux-panel-agent.service` 并立即启动；重新安装 Agent 会短暂重启该节点进程，但不会修改面板数据库中的节点与转发记录。

节点安装时会先检测内核版本、CPU、内存、默认出口网卡以及 BBR/FQ 支持，再做 TCP 调优，最后才下载和启动 Agent。调优写入 `/etc/sysctl.d/99-flux-panel-network.conf`：接收/发送缓存上限为 16 MB、自动接收缓存、MTU 探测、TCP Fast Open、禁用空闲慢启动、`somaxconn=16384`、`tcp_max_syn_backlog=8192`、`netdev_max_backlog=8192`。支持 BBR/FQ 的节点启用 BBR/FQ；旧内核缺少其中某项时脚本会自动保留可用算法、跳过不支持的参数，Agent 仍会继续安装。需要跳过调优可加 `--skip-tcp-tuning`：

```bash
curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/install.sh | sudo bash -s -- --server '面板地址:6365' --secret '节点密钥' --skip-tcp-tuning
```

## 节点硬件信息说明

面板前端只能展示 Agent 上报的数据。旧版 Agent 只会上报 CPU/内存使用率和流量计数；要显示 CPU 核数、内存总量及硬盘容量，需要升级为包含硬件采集字段的 Agent。升级 Agent 会短暂重启节点侧进程，但不应修改面板数据库中的节点与转发记录。

## 上游项目与许可

本项目基于 [bqlpfy/flux-panel](https://github.com/bqlpfy/flux-panel) 进行二次开发，并依赖 [go-gost/gost](https://github.com/go-gost/gost) 和 [go-gost/x](https://github.com/go-gost/x)。请遵守仓库中的许可证及相关上游项目许可证。

## 使用边界

本项目仅限在你拥有授权的服务器与网络环境中使用。使用者须自行负责部署安全、账号保护、数据备份与所在地法律合规。
