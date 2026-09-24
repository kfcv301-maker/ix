# 公网 IP 面板的 HTTPS

Let's Encrypt 从 2026 年起支持公网 IPv4/IPv6 证书。IP 证书属于 shortlived 档，有效期约六天，因此必须验证自动续期。以下示例假设宿主机 Nginx 把请求转发到仅监听本机的面板前端 `127.0.0.1:6366`。

1. 安装 Certbot 5.4 或更新版本。建议使用独立虚拟环境与独立的配置、工作和日志目录，以免系统里已有的 Certbot 续期任务使用旧版本尝试续订 IP 证书。
2. 在 Nginx 的 80 端口为 `/.well-known/acme-challenge/` 设置 webroot；检查从公网可以读取该目录中的测试文件。
3. 申请证书，把 `PANEL_IP` 换成真实公网 IP：

   ```sh
   certbot certonly --config-dir /etc/letsencrypt-flux-ip \
     --work-dir /var/lib/letsencrypt-flux-ip \
     --logs-dir /var/log/letsencrypt-flux-ip \
     --preferred-profile shortlived --webroot -w /var/www/flux-acme \
     --ip-address PANEL_IP --cert-name flux-panel-ip \
     --non-interactive --agree-tos --register-unsafely-without-email
   ```

4. 让 Nginx 的 443 站点使用 `/etc/letsencrypt-flux-ip/live/flux-panel-ip/fullchain.pem` 和 `privkey.pem`，再把普通请求代理到 `127.0.0.1:6366`。80 端口只保留 ACME 验证并把其他请求重定向至 HTTPS。若同机已有其他 HTTPS 站点，先检查 443 默认站点，避免影响它们的 SNI 路由。
5. 用 systemd timer 至少每天运行一次同一版本 Certbot 的 `renew`，仅在证书更新后重载 Nginx。执行 `certbot renew --dry-run`，并从另一台机器验证证书链、80→443 跳转及面板 API/WebSocket。

订阅 API 的 `token` 是只读凭据，但仍不应进入访问日志。宿主机与前端容器的 Nginx 都应对 `/api/v1/open_api/sub_store` 关闭访问日志。

参考：[Let's Encrypt 官方 IP 证书与 Certbot 指南](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)。
