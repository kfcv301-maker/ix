旧实例上报模式历史说明；以下限制不适用于 2.0 DNS 模式。

# 节点公网 IP 同步 1.2.1

适配目标：Xboard `v20260618-8e4864b`，源码提交 `8e4864b4c7f6240e3ef08ecd7b59447e5d9dd363`，后台前端子模块 `ef5f43da335092cbff8fdf0ad7ff9b4d92d7d0d7`。

后台入口：**节点管理 → IP 自动同步**。节点管理页右上角也有同名按钮。点击后在后台内容区域打开新增的节点同步页面，沿用当前管理员登录状态；原后台侧栏保留。无需填写第二次账号密码。

1.1.0 从原来的独立管理页改为后台集成页面，保留 1.0.0 的绑定、上报密钥、变更记录和服务器脚本配置。

1.1.1 为上报脚本设置 `Xboard-Node-Address-Sync/1.1.1` 客户端标识，修复本次部署中 Cloudflare 对默认 Python 请求标识的 1010 拦截。已安装服务器需更新 `report_ip.py` 并重启上报服务；原配置继续有效。

1.1.2 将新下载配置的检测间隔缩短为 15 秒；后台同步页面每 10 秒刷新一次最新地址和上报时间，保留未保存的服务器名称、实例 ID 和节点勾选。配置弹窗打开或页面不可见时暂停自动刷新，离开页面时停止定时器。已有服务器配置里的 `interval: 60` 需改为 `15` 并重启上报服务才会加速，面板升级不会改动服务器上的配置或轮换密钥。

1.2.0 新增无人值守新机部署：配置弹窗可复制一条安装命令，或下载带配置的自包含换机脚本。通用 `agent/bootstrap.sh` 也可直接接收配置文件/标准输入。自动安装依赖、备份已有配置、更新同一个 systemd 服务并等待新的面板上报确认，支持重复执行。自动换机时请将实例 ID 留空，并在旧机终止或停止上报后执行新机初始化。

1.2.1 增加 GitHub 一键安装参数：`--panel`、`--binding-id`、`--token`（或 `--token-file`），支持 `curl ... | bash -s -- ...`。通用脚本不包含密钥；换机参数生成的配置不固定 AWS 实例 ID，继续复用原绑定。GitHub 仓库入口见交付包的 `GitHub一键安装说明.md`。

## 功能

- 勾选节点，为一台服务器创建绑定；同一服务器的多个协议节点可以一起同步。
- AWS 节点本机读取 IMDSv2 的 `public-ipv4`，公网 IP 连续两次一致后上报。
- 仅修改已绑定节点的 `v2_server.host` 和正常保存产生的 `updated_at`。保留端口、SNI、协议配置、倍率、用户组及节点 ID。
- 每台服务器有独立上报密钥，数据库只保存密钥哈希。支持暂停、编辑、解除绑定、重新生成密钥和变更记录。
- IP 相同只更新心跳；拒绝内网/无效 IP、实例 ID 不符、旧时间戳和错误密钥。
- 节点被手工改过地址或被删除时，整个绑定停止更新，避免部分覆盖。管理员确认后重新保存绑定即可继续。

## 安装面板插件

1. 在 Xboard 后台的插件管理上传 `node-address-sync-1.1.0.zip`，安装并启用；已有 1.0.0 时直接上传升级。
2. 如使用 Octane 常驻进程，按现有部署方式重载 Xboard 工作进程，使新增类和路由可用。例如在实际 Xboard 目录运行 `php artisan octane:reload`；Docker 部署请在实际 Xboard 容器内运行对应命令。
3. 刷新已登录的后台，进入“节点管理”，点击“IP 自动同步”。
4. 填写服务器名称，建议填写 AWS 实例 ID；勾选这台服务器对应的节点并保存。
5. 下载页面生成的 `agent-config.json`。此配置只用于该服务器，不是面板管理员密钥。

插件通过 Xboard 的后台视图加载扩展脚本，无需覆盖后台模板或编译后的前端文件。管理请求直接复用后台的 `XBOARD_ACCESS_TOKEN`，仍由 Xboard 的管理员中间件验证权限；不会传递密钥到 URL，也不会创建第二份登录会话。后台入口页面支持刷新、浏览器前进/后退和“返回节点管理”。

旧的 `/node-address-sync` 书签会转到面板配置的后台路径，再打开集成页面。若后台尚未登录，会显示 Xboard 原有登录流程。插件无需 composer 安装额外生产依赖。禁用后刷新后台，入口会消失；上报接口和管理接口立即拒绝请求。

## 安装 AWS 服务器上报脚本

把交付包里的 `agent/` 目录和该服务器对应的 `agent-config.json` 上传到 AWS **节点服务器**，在该目录运行：

```bash
sudo bash install.sh /实际路径/agent-config.json
systemctl status xboard-node-address-sync
journalctl -u xboard-node-address-sync --since '10 minutes ago'
```

需要使用 systemd 的 Linux、最终 Python 3.8+；缺少 Python 或 CA 证书时，1.2.0 安装器会通过 apt-get/dnf/yum 非交互安装。脚本独立运行，不安装第三方 Python 包，不打开监听端口，不操作 AWS IP、节点服务、防火墙或其他服务。服务文件名称为 `xboard-node-address-sync.service`。安装命令仅在收到安装后新的面板确认时返回 0；等待超时返回 2，服务继续后台重试。

默认每 15 秒检查一次；新 IP 至少连续两次一致才提交，所以从 AWS 元数据能读到新 IP 后，预计约 15–30 秒加接口耗时同步。AWS 地址替换过程、元数据暂时返回 404、网络重试以及客户端订阅更新可能增加总等待时间。初次安装也需要两次观察。服务器必须能通过 HTTPS 访问面板；IMDSv2 必须可从宿主机访问，建议将脚本装在宿主机而不是容器内。

配置保存在 `/etc/xboard-node-address-sync/agent-config.json`，权限为 0600。运行程序位于 `/opt/xboard-node-address-sync/`，观察状态位于 `/var/lib/xboard-node-address-sync/`。

重新生成配置后，旧上报密钥立即失效。替换节点服务器上的配置并重新运行安装命令即可。

## 无 systemd 时

将脚本和配置放到合适位置，并由 root 的 cron 每分钟调用一次；配置中的 `state_file` 必须是该用户可写的持久路径：

```cron
* * * * * /usr/bin/python3 /实际路径/report_ip.py --config /实际路径/agent-config.json
```

同一配置不要同时启用 cron 和 systemd。Linux 文件锁会阻止两次调用同时写入。

## 与已有换 IP 系统对接

默认方案不需要从 aws.sb 抓取账号数据。原系统仍负责换 IP，本脚本负责发现结果并同步到面板。

若换 IP 系统支持回调，也可把配置的 `mode` 改为 `manual`、`stable_samples` 改为 `1`，在原系统确认新 IP 绑定完成、节点服务已恢复后运行：

```bash
python3 report_ip.py --config /实际路径/agent-config.json --ip "$NEW_PUBLIC_IPV4" --instance-id "$INSTANCE_ID"
```

该命令不放在每分钟定时任务里；由原换 IP 系统触发。也可以直接调用下面的接口。Bearer 密钥从配置文件读取，不应写进公开链接或日志。

```text
POST /api/v1/node-address-sync/report
Authorization: Bearer <本服务器的上报密钥>
Content-Type: application/json

{
  "binding_id": "管理页生成的绑定 UUID",
  "ip": "新的公网 IPv4",
  "instance_id": "i-...",
  "observed_at": 服务器当前 Unix 秒时间戳
}
```

成功响应的 `data.status` 为 `updated`、`unchanged` 或相同请求重试的 `duplicate`。接口只接受绑定范围内的操作，调用方不能传入任意节点 ID。允许过去 300 秒和未来 60 秒的时间偏差；同一绑定更早的上报不会把 IP 改回去。服务器需正常校时。

## 使用限制

- 此版本同步公网 IPv4，不创建 DNS 记录，不支持 IPv6 同步。
- 后台入口适配上述版本的 Hash 路由、`main#content` 布局和登录存储格式。如果替换后台主题、修改登录存储方式或升级到不同前端版本，需要重新验证入口兼容性。
- 只知道新 IP 的数值无法完成同步，还必须确认它对应哪台服务器。绑定 UUID 和独立密钥负责这种对应关系，实例 ID 可提供额外校验。
- AWS `public-ipv4` 返回实例对应的公网 IPv4，绑定 EIP 时返回 EIP。多网卡、辅助私网地址绑定的额外 EIP、中转入口或 NAT 出口不一定对应这里的主公网 IPv4，这些拓扑应使用原系统的明确 IP 回调。
- 若原系统销毁旧实例并新建实例，将本版本的安装命令加入原系统的新机执行流程，沿用同一逻辑绑定和配置。自动换机时实例 ID 留空；旧实例必须停止上报，避免两台同时写同一组节点。安装器不会自行触发新实例创建或修改原系统的命令流程。
- 域名改为 IP 时，TLS 证书通常仍对应原域名，须先在节点协议配置中显式填写对应 SNI / server_name。插件不会替你更换证书或关闭证书验证。页面会提示常见的缺少 SNI 情况，最终仍需按实际客户端测试。
- 面板更新后，用户客户端更新订阅才能拿到新地址。插件无法主动改写客户端已经下载的订阅。
- 此插件不检测是否被墙，也不申请或替换公网 IP；这些工作继续由现有系统完成。
- 暂停或解除绑定不会恢复旧地址。可根据历史记录在 Xboard 节点页面恢复地址；卸载会移除插件绑定和历史表，保留节点当前地址，卸载前请导出需要的历史数据。

## 排查

- HTTP 401：上报密钥错误或已重新生成。
- HTTP 403：插件未启用、绑定暂停、实例 ID 不符；管理接口则可能没有管理员权限。
- HTTP 409：节点已删除、地址被手工修改，或提交了旧时间戳。刷新管理页面并确认实际地址后，重新保存该绑定。
- HTTP 422：非公网 IPv4、时间偏差过大或请求字段不合法。
- IMDS 不可访问：确认从 EC2 宿主机运行，且实例元数据服务已启用；读取失败会保留面板原地址，下一轮重试。
- HTTPS 失败：检查节点服务器到面板域名的连接和证书。脚本不绕过 TLS 验证、不跟随重定向、不使用环境代理。
- 同步时间不代表节点已验证可用。自动模式验证 IP 来源及稳定性，未进行真实代理协议连接检测；故障期间原有脚本重试并保留地址。

## 源码依据

- [目标版本 Server 模型](https://github.com/cedar2025/Xboard/blob/8e4864b4c7f6240e3ef08ecd7b59447e5d9dd363/app/Models/Server.php)
- [目标版本节点管理](https://github.com/cedar2025/Xboard/blob/8e4864b4c7f6240e3ef08ecd7b59447e5d9dd363/app/Http/Controllers/V2/Admin/Server/ManageController.php)
- [目标版本插件管理器](https://github.com/cedar2025/Xboard/blob/8e4864b4c7f6240e3ef08ecd7b59447e5d9dd363/app/Services/Plugin/PluginManager.php)
- [AWS 公网 IP 元数据说明](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/working-with-ip-addresses.html)

验证报告见交付目录的 `VERIFICATION.md`。
