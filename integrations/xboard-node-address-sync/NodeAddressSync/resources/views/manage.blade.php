<style>
:host{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;color:#17233b;background:#f3f5fa;font-size:14px}*{box-sizing:border-box}:host{display:block;min-height:100%;}main{max-width:1200px;margin:auto;padding:36px 24px}h1{font-size:28px;margin:0 0 10px}h2{font-size:18px;margin:0 0 18px}p{color:#617086;line-height:1.7}header{margin-bottom:28px;display:flex;justify-content:space-between;gap:20px;align-items:flex-start}.card{background:white;border:1px solid #e0e5ee;border-radius:14px;padding:24px;margin-bottom:20px}.grid{display:grid;grid-template-columns:1.15fr 2fr;gap:20px}.grid .card{margin:0}label{display:block;margin:14px 0 8px}input:not([type=checkbox]),textarea,select{width:100%;border:1px solid #ccd5e4;padding:11px;border-radius:7px;font:inherit}input[type=checkbox]{accent-color:#2563eb;width:16px;height:16px}button{border:1px solid #ccd5e4;border-radius:7px;background:white;padding:9px 14px;font:inherit;cursor:pointer;color:#263956}button.primary{background:#2563eb;border-color:#2563eb;color:white}button:disabled{opacity:.5;cursor:wait}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.muted{color:#78869b;font-size:12px}.warning{color:#a65c00}.badge{border-radius:20px;background:#eef3fc;padding:4px 9px;font-size:12px;white-space:nowrap}table{border-collapse:collapse;width:100%;font-size:13px}th,td{text-align:left;padding:12px 9px;border-bottom:1px solid #edf0f5}th{color:#6e7e94;font-weight:500}.scroll{overflow:auto;max-height:480px}#message{padding:14px 16px;border-radius:8px;background:#e8f0ff;color:#244c92;margin-bottom:18px;white-space:pre-wrap}#message.error{background:#fff0ed;color:#aa3624}.binding{border-top:1px solid #e9edf5;padding:16px 0}.binding:first-child{border:0}.binding strong{display:block;margin-bottom:6px}#app[hidden],#message[hidden],dialog:not([open]){display:none}dialog{border:1px solid #d4dced;border-radius:14px;padding:26px;max-width:680px;width:calc(100% - 32px)}dialog::backdrop{background:#16213788}textarea{height:240px;font-family:monospace;font-size:12px}#search{max-width:260px}.actions{margin-top:20px}@media(max-width:800px){.grid{grid-template-columns:1fr}main{padding:24px 14px}.card{padding:18px}header{flex-direction:column}.scroll{max-height:400px}}
</style>
<main>
<header><div><h1>IP 自动同步</h1><p>选择已授权的转发面板或 DDNS 来源，仅同步明确勾选的节点。关闭页面不影响后台同步。</p><p id="sync-status" class="muted">正在读取同步状态…</p></div><div id="account" hidden><button id="refresh">刷新</button> <button id="back">返回节点管理</button></div></header>
<div id="message" role="status" hidden></div>
<section id="app" hidden>
<div class="card"><h2>允许上报的转发面板</h2><p>从转发面板后台复制固定面板 ID，在这里创建独立连接。名称和网址方便识别，实际授权由面板 ID、连接密钥及下方节点映射共同决定。</p>
<form id="connection-form"><label for="connection-label">连接名称</label><input id="connection-label" required maxlength="100" placeholder="例如：主转发面板">
<label for="connection-panel-id">转发面板 ID</label><input id="connection-panel-id" required maxlength="36" placeholder="转发面板显示的固定 UUID">
<label for="connection-url">转发面板网址</label><input id="connection-url" type="url" required maxlength="255" placeholder="https://relay.example.com">
<label class="row"><input id="connection-enabled" type="checkbox">允许此面板上报</label>
<div class="row actions"><button class="primary" type="submit">保存连接</button><button id="connection-new" type="button">新建连接</button></div></form>
<div id="connections"></div><p class="muted">每个连接最多映射 100 个转发节点。新连接默认暂停；密钥只在创建或轮换时显示，不会下发给节点服务器。</p></div>
<div class="grid">
<div class="card"><h2 id="edit-title">配置同步来源</h2><form id="binding-form">
<label for="label">绑定名称</label><input id="label" placeholder="例如：东京 AWS 01" maxlength="100" required>
<label for="source-mode">地址来源</label><select id="source-mode"><option value="relay">已授权转发面板上报</option><option value="dns">DDNS 权威解析（无需节点代理）</option><option value="agent">旧实例上报（仅兼容原绑定）</option></select>
<div id="relay-fields"><label for="relay-connection">允许上报的面板连接</label><select id="relay-connection" required></select><label for="relay-node-id">对应转发节点 ID</label><input id="relay-node-id" type="number" min="1" max="2147483647" step="1" placeholder="填写转发面板里的节点 ID"><p class="muted">与所选面板中的同一转发节点建立唯一映射；不按名称匹配。配置后在转发面板测试连接并选择此绑定。</p></div>
<div id="dns-fields"><label for="domain">DDNS 源域名</label><input id="domain" placeholder="入口实际更新的 DNS only 域名" maxlength="253">
<label for="record-type">IP 类型</label><select id="record-type"><option value="A">A · IPv4</option><option value="AAAA">AAAA · IPv6</option></select>
<label for="selection">记录选择</label><select id="selection"><option value="single">仅唯一 IP；多 IP 时停止更新</option><option value="lowest">多个 IP 时固定取数值最小者</option></select>
<label class="row"><input id="dns-only" type="checkbox">已确认此域名指向所选入口，未开启 CDN 代理</label>
<p class="muted">每 5 秒评估；两次权威结果一致后检查各节点实际端口，检查通过才更新。解析或连接失败保留旧地址，下轮重试。最多启用 16 组、128 个节点。</p></div>
<div id="agent-fields" hidden><label for="instance">AWS 实例 ID（旧配置）</label><input id="instance" maxlength="64"></div>
<label class="row"><input id="accept-manual" type="checkbox">接受所选节点当前地址为新的保护基线</label><p class="muted">默认保留手工改址保护。仅在确认节点仍属于此入口后勾选。</p>
<label class="row"><input id="enabled" type="checkbox" checked>启用自动同步</label>
<p id="selected">已选择 0 个节点</p><p class="warning muted">连接域名改成 IP 前，请确认 TLS 节点已显式填写证书对应的 SNI / server_name。</p>
<div class="row actions"><button class="primary" type="submit">保存绑定</button><button id="new" type="button">新建绑定</button></div></form>
<h2 style="margin-top:32px">已配置同步来源</h2><div id="bindings"></div></div>
<div class="card"><div class="row" style="justify-content:space-between;margin-bottom:18px"><h2 style="margin:0">勾选要同步的节点</h2><input id="search" placeholder="搜索节点或连接地址"></div><p class="muted">其他服务器已绑定的节点不可重复选择。普通保存不会解除手工改址保护；需要明确勾选接受当前地址。暂停绑定或面板连接可停止对应同步。</p><div class="scroll"><table><thead><tr><th>选择</th><th>节点</th><th>连接地址</th><th>状态</th></tr></thead><tbody id="nodes"></tbody></table></div></div>
</div>
<div class="card" style="margin-top:20px"><h2>最近的地址变更</h2><div class="scroll"><table><thead><tr><th>时间</th><th>节点 ID</th><th>原连接地址</th><th>新连接地址</th></tr></thead><tbody id="history"></tbody></table></div><p class="muted">显示最近 100 条，保存 90 天。连接地址写入后，客户端更新订阅即可获得新 IP。</p></div>
</section>
<dialog id="config-dialog"><h2>服务器上报配置</h2><p>将配置下载到对应的节点服务器。上报密钥仅显示这一次，可在绑定卡片中重新生成。</p><textarea id="config-text" readonly spellcheck="false"></textarea><div class="row actions"><button class="primary" id="download">下载 agent-config.json</button><button id="copy-bootstrap">复制安装命令</button><button id="download-bootstrap">下载换机安装脚本</button><button id="close-config">已保存，关闭</button></div><p class="muted">安装命令和换机脚本已包含此绑定配置，交给自动执行命令的流程保存并运行即可。自动换机需将实例 ID 留空。它们含上报密钥，请保存在自动化系统的私有配置中。</p><p class="muted">全程无交互，自动安装依赖并设置开机自启；每 15 秒读取公网 IP，连续两次一致后同步。</p></dialog>
<dialog id="connection-dialog"><h2>连接凭据 · 仅显示一次</h2><p>将下方机场地址、连接 ID 和密钥填写到对应转发面板的机场连接设置，再测试连接。请保存在私有配置中。</p><textarea id="connection-secret" readonly spellcheck="false"></textarea><div class="row actions"><button id="connection-copy" type="button">复制连接配置</button><button id="connection-close" type="button">已保存，关闭</button></div></dialog>
</main>
