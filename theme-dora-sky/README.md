# Dora Sky — Flux Panel 哆啦蓝主题

> 版本：1.0.0
> 编写日期：2026-09-14
> 状态：独立主题工程，不修改 Flux Panel 原项目文件

## 1. 项目说明

Dora Sky 是为当前 `Flux Panel Enhanced` 编写的一套独立 React/Vite 前端主题。设计采用“哆啦蓝 + 红色铃铛 + 明亮卡片”的视觉语言，保留运维面板所需的信息密度，并对桌面与移动端做响应式处理。

主题目录完全独立：

```text
theme-dora-sky/
├─ src/
│  ├─ api.ts        # Flux Panel 接口适配层
│  ├─ types.ts      # 接口数据类型
│  ├─ format.ts     # 流量、时间等格式化方法
│  ├─ App.tsx       # 登录、布局和业务页面
│  ├─ main.tsx      # React 入口
│  └─ theme.css     # Dora Sky 完整视觉样式
├─ index.html
├─ package.json
├─ tsconfig.json
└─ vite.config.ts
```

它不会导入、覆盖或写入原项目的 `vite-frontend/`、`springboot-backend/`、`go-gost/` 等目录。

## 2. 已实现界面

- 登录页
- 管理后台侧边栏和移动端底部导航
- 系统总览
- 节点状态与 CPU、内存指标
- 隧道列表及多入口数量展示
- 转发列表、流量展示、暂停和恢复
- 用户列表
- 限速规则列表
- 网站单项配置页
- 桌面、平板、手机响应式布局
- 管理员与普通用户菜单隔离

## 3. 与当前项目的接口适配

### 3.1 基本约定

- REST 基址：`/api/v1`
- 请求方式：当前业务接口统一使用 `POST`
- 请求体：JSON
- 响应结构：

```ts
interface ApiResponse<T> {
  code: number;
  msg: string;
  data: T;
}
```

- Token 存储：`localStorage.token`
- 鉴权请求头：

```http
Authorization: <token>
```

注意：当前项目不是 `Bearer <token>` 的浏览器登录形式，主题严格沿用原前端的裸 Token 请求头。

### 3.2 本主题实际使用的接口

| 模块 | 接口 |
| --- | --- |
| 登录 | `POST /api/v1/user/login` |
| 节点 | `POST /api/v1/node/list` |
| 隧道 | `POST /api/v1/tunnel/list` |
| 隧道诊断 | `POST /api/v1/tunnel/diagnose` |
| 转发 | `POST /api/v1/forward/list` |
| 暂停转发 | `POST /api/v1/forward/pause` |
| 恢复转发 | `POST /api/v1/forward/resume` |
| 用户 | `POST /api/v1/user/list` |
| 限速 | `POST /api/v1/speed-limit/list` |
| 读取配置 | `POST /api/v1/config/get` |
| 更新配置 | `POST /api/v1/config/update-single` |

`src/api.ts` 也预留并封装了节点、隧道、转发、用户、限速、验证码和配置的完整 CRUD 接口，便于其他 AI 继续完成编辑弹窗。

### 3.3 已兼容的增强字段

节点：

- `cpuUsage`、`memoryUsage`
- `cpuCores`、`memoryTotal`
- `diskTotal`、`diskUsedPercent`
- `bytesReceived`、`bytesTransmitted`
- `load1`、`tcpConnections`、`udpConnections`
- `agentRss`、`goroutines`、`timestamp`

隧道：

- `entryNodeIds` 多入口节点
- `trafficRatio`
- `protocol`
- `tcpListenAddr`、`udpListenAddr`
- `interfaceName`

转发：

- `strategy`
- `interfaceName`
- `inFlow`、`outFlow`
- `inx`

### 3.4 WebSocket 说明

项目中的 `/system-info` 是 Agent 与后端之间的 WebSocket 通道，并不是浏览器可直接订阅的监控接口。本主题没有错误地连接该地址；目前通过 `/node/list` 取得后端提供的节点状态快照，用户点击“刷新数据”时更新。

若将来需要浏览器实时图表，建议后端另增只读监控接口，或让前端按固定周期轮询 `/node/list`。

## 4. 本地运行

要求：Node.js 18 或更高版本。

```bash
cd H:/ny/flux-panel/theme-dora-sky
npm install
npm run dev
```

默认访问：`http://localhost:5178`

开发服务器默认把 `/api` 转发到：

```text
http://localhost:6365
```

如果后端地址不同，可在 PowerShell 中运行：

```powershell
$env:VITE_PROXY_TARGET='http://你的后端地址:6365'
npm run dev
```

## 5. 生产构建

```bash
npm run build
```

构建产物生成在：

```text
theme-dora-sky/dist/
```

如主题和后端使用相同域名，保持默认相对路径即可。如需跨域独立部署，可在构建前设置：

```powershell
$env:VITE_API_BASE='https://panel.example.com'
npm run build
```

最终请求地址将是：

```text
https://panel.example.com/api/v1/...
```

跨域部署时，后端或反向代理必须允许对应来源和 `Authorization` 请求头。

## 6. 集成到原项目的建议

交给其他 AI 集成时建议采用以下两种方案之一。

### 方案 A：独立替换前端

1. 保留本目录作为新前端工程。
2. 构建本主题。
3. 调整前端 Dockerfile 或 Nginx，使其发布本主题的 `dist/`。
4. 不修改 Spring Boot 和数据库。

优点：隔离最清楚，回滚简单。

### 方案 B：迁移视觉到原前端

1. 把 `theme.css` 的设计变量和组件样式迁入原前端。
2. 复用原项目已有表单、弹窗和验证码实现。
3. 将本主题的总览、导航和列表视觉逐页替换。
4. 保留原项目的 `src/api/index.ts` 与权限逻辑。

优点：可以完整保留原前端所有编辑能力和移动 App WebView 逻辑。

## 7. 当前完成范围和后续事项

本版本定位为“可以运行、接口对齐、可供继续集成的完整主题骨架”。已经实现读取和核心运行控制；为了避免在不了解最终交互要求时替用户决定复杂表单，以下功能留给后续 AI：

- 节点创建、编辑、删除弹窗
- 节点安装命令及 Cloudflare DDNS 参数弹窗
- 隧道创建、编辑、用户授权弹窗
- 转发创建、编辑、拖拽排序和强制删除入口
- 用户创建、配额编辑、重置流量
- 限速规则创建和编辑
- 验证码轨迹组件
- 修改密码页面
- App WebView 的面板地址桥接

所有对应 API 已在 `src/api.ts` 中封装，后续实现无需改变接口层。

## 8. 安全和兼容注意事项

1. 不要把节点密钥、Cloudflare Token 或用户密码写入前端日志。
2. 删除、强制删除等危险操作必须保留二次确认。
3. `role_id === 0` 才是管理员；普通用户不可显示管理员菜单。
4. 401 响应会清理登录信息并回到登录页。
5. 不要使用原前端中存在、但当前后端 Controller 未提供的 `/node/check-status` 和 `/tunnel/get`。
6. 当前登录页以无验证码模式提交空 `captchaId`；若后台启用验证码，应接入原项目的滑块轨迹组件后再用于生产。
7. 接口可能返回数组，也可能由分页对象的 `records`/`list` 包裹，`unwrapList` 已兼容这三种形式。

## 9. 视觉规范

- 主色：`#1599e6`
- 深蓝：`#0869b4`
- 强调红：`#ef4d55`
- 铃铛黄：`#ffd95a`
- 背景：`#eef8ff`
- 卡片：`#ffffff`
- 圆角：12–28px
- 字体：Nunito、Noto Sans SC、系统字体回退

如果生产环境不能访问 Google Fonts，可删除 `theme.css` 第一行的字体导入；界面会自动回退到系统字体，不影响布局和功能。
