# Lunaris → NodeAddressSync 开发契约（2026-11-07）

开发分支：`feat/optional-airport-sync-20261107`。仅开发与隔离验证；没有部署或修改真实绑定。

## 范围

- 每个逻辑转发节点有独立管理员开关，默认关闭；明确填写接收端 HTTPS 地址、binding_id 和独立密钥。普通用户无读写权限。
- 机场接收端仍由 NodeAddressSync 1.1.2 的显式绑定控制目标集合，只更新 host；不得按名称匹配或修改端口、SNI、倍率、权限。
- 固定安装命令与 URL 保持不变，Agent 二进制不变。原生 install-script/{id} 在原 Agent 安装后追加可选采集器；install-config/{id} 仅在勾选 AWS 模式时携带可校验的采集器资源，稳定 node_install.sh 剥离该资源后再交给旧版 Agent 安装器，随后安装采集器。未勾选/旧面板保持原行为。

## 来源与限制

旧 Agent 指标未携带公网 IP；现网 AWS1 的面板连接实际使用 IPv6，经 Cloudflare。不能由该连接推导公网 IPv4，不能用 DDNS 代替可信上报。提供两个显式来源模式：

- `aws-imds`：可选 Python 采集器使用 IMDSv2 获取 public-ipv4、instance-id、内核 boot_id，每十秒先向面板确认仍启用，再凭既有节点 secret 上报。机场密钥完全不下发。GET/POST `/api/v1/node/address-observation` 均由节点密钥独立认证，校验账号可用与精确密钥。GET 返回 `{code:0,data:{enabled,installEnabled,sourceMode}}`（明确勾选但暂时阻断时 installEnabled=true，允许新机先安装timer等待修复；enabled=false时不采样、不上报）；POST `{ip,instanceId,observedAt,bootId}`，回执 `{code:0,data:{accepted:true,ip}}` 只代表面板受理；管理员看到机场回执才显示同步完成。
- `peer`：使用当前认证 WebSocket 的可信公网 IPv4 和新鲜心跳。运维来源配置默认为 off；只有验证代理链及出口等于入口后才启用 direct/trusted-proxy。读取 Spring forwarded-header 包装前的原始请求，右向左剥离明确配置的可信代理；IPv6/未知来源不发送。此模式不覆盖当前 AWS1。

每个逻辑节点至少两次不同的新鲜样本、跨度至少十秒；面板五秒调度。健康小批量正常链路目标六十秒内，非外部故障 SLA。独立于 IP 历史两分钟分类窗口，历史计数和原转发业务不变。

AWS 采集器仅支持 systemd + Python3 的 HTTPS 面板（443），适合 Debian12。新增机器自动随原生安装链安装；已有机器开启后仍须单独补装该轻量组件，保存设置不会安装或重启 Agent。采集器需既有 config.json 为 root 拥有且0600；不擅自改配置权限。停用/解绑不访问 IMDS、不 POST，已装 timer 保留轮询以支持重新启用。旧面板404不安装新组件。

## 接收契约

`POST https://<管理员明确配置主机>/api/v1/node-address-sync/report`

`Authorization: Bearer <独立绑定密钥>`

```json
{"binding_id":"<明确绑定UUID>","ip":"<可信公网IPv4>","observed_at":1800000000}
```

observed_at 为实际心跳观测 UTC 秒，重试同一事件保持不变。实例锁定的绑定不适用于不断更换云实例：面板不伪造 instance_id，不绕过 403；需要接收端明确使用无实例锁定的逻辑绑定。

仅 HTTP 200 且 data.status 为 updated/unchanged/duplicate 且 data.ip 等于发送 IP 才确认成功。401/403/409/422 阻止继续自动覆盖并显示管理员处理状态；429/5xx/网络故障退避重试。错误响应正文不写日志或展示，避免反射密钥。

## 持久性及安全

每个节点一条持久化最新待投递状态，状态与设置以数据库行锁串行化，防重复并发发送；新地址取代旧地址，过期事件重新等待新鲜确认，服务重启从持久状态恢复。禁用/解绑返回成功后不再发出新请求；已经被接收端接受的请求不可撤回。

密钥用面板 JWT_SECRET 及节点ID/接收地址/binding_id派生的 AES-GCM 加密，浏览器仅接收 hasToken，不返回密文或明文；不进入节点配置、安装命令或 Git。接收地址限制 HTTPS 固定路径、443、无用户信息/查询/片段；DNS 结果全量校验并固定到本次连接，拒绝内网/保留地址，禁止重定向/系统代理/自动重试，保留 TLS 主机名校验并限制超时和响应大小。

## 验收

必须分别覆盖默认关闭、非管理员禁止、首次认证连接、旧版安装链不变、不同心跳稳定确认、换机重连、旧连接撤销、持久化重启恢复、禁用/解绑、换绑定不能沿用旧密钥、过期/乱序、人工修改409、DNS重绑定/重定向、无密钥回显。已完成隔离的新目录安装、真实独立子进程重启、真实 systemd-analyze 单位语法检查；HTTP/systemctl 执行采用 fixture。未在新 AWS 实例安装整个桥接链，也未切换真实绑定；不得将隔离验收冒充生产成功。


## 当前验证与发布边界

- Java 全量430项通过（含27项新增测试），覆盖真实H2事务、持久重启、并发禁用、绑定唯一性、受理回执和目的地址校验。
- 54项前端现有测试、生产构建通过；本机浏览器1440/390宽度通过默认关闭、AWS模式保存、保存后密钥清空、解绑、普通用户不可见，无页面运行时异常。
- 稳定入口8项、旧安装器11项通过；采集器Linux隔离16项通过（包含阻断期间首装后自动恢复）；接收端生产1.1.2精确源码的18项隔离语义检查通过。
- 默认不启用任何真实绑定。发布前需协调同一绑定唯一写源、备份、面板与固定Git入口同步发布、已有机器只补装采集器而不重装Agent，并做单节点首报/接收回执/真实host独立回读。
- 本次不更新 main、不部署面板、不停旧 reporter、不重启节点。完整新机自动安装尚需独立上线验收，公开固定URL在开发分支提交后仍指向原 main。

采集器源码与后端 Docker 上下文内资源须保持逐字节一致；修改后运行 `python tests/airport_observer_packaging_test.py`。已有节点可从管理员同步设置下载校验过的纯采集脚本，执行 `sudo python3 airport_address_reporter.py --install --root /etc/flux-panel-agent`，该动作不安装或重启 Agent。

节点观察 POST 在 JSON 解析前限制 2KiB（含 chunked），bootId 必须为规范 UUID。出站 HTTP 使用 Apache 2026-10-12 发布的稳定 HttpClient 5.6.5 及配套 HttpCore 5.4.4，显式覆盖旧 Boot BOM；[官方发布说明](https://hc.apache.org/news.html)。
