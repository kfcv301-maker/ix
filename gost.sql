-- phpMyAdmin SQL Dump
-- version 5.2.0
-- https://www.phpmyadmin.net/
--
-- 主机： localhost
-- 生成日期： 2025-08-14 21:52:52
-- 服务器版本： 5.7.40-log
-- PHP 版本： 7.4.33

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

--
-- 数据库： `gost`
--

-- --------------------------------------------------------

--
-- 表的结构 `forward`
--

CREATE TABLE `forward` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `user_name` varchar(100) NOT NULL,
  `name` varchar(100) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `in_port` int(10) NOT NULL,
  `out_port` int(10) DEFAULT NULL,
  `remote_addr` longtext NOT NULL,
  `vps_host_id` bigint(20) DEFAULT NULL,
  `strategy` varchar(100) NOT NULL DEFAULT 'fifo',
  `interface_name` varchar(200) DEFAULT NULL,
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `flow_grace_until` bigint(20) DEFAULT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL,
  `inx` int(10) NOT NULL DEFAULT '0'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- Durable pause retries. A forward remains active in the database until all
-- of its GOST endpoints have acknowledged the pause command.
--

CREATE TABLE `forward_pause_task` (
  `id` bigint(20) NOT NULL,
  `forward_id` bigint(20) NOT NULL,
  `task_status` varchar(16) NOT NULL,
  `attempts` int(10) NOT NULL DEFAULT '0',
  `last_error` varchar(500) DEFAULT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL DEFAULT '1'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- Durable per-endpoint cleanup/pause/resume acknowledgements. A forward stays
-- visible and billable until every affected GOST endpoint confirms the state.
--

CREATE TABLE `forward_sync_task` (
  `id` bigint(20) NOT NULL,
  `operation_id` varchar(48) NOT NULL,
  `forward_id` bigint(20) NOT NULL,
  `node_id` bigint(20) NOT NULL,
  `endpoint` varchar(16) NOT NULL,
  `operation` varchar(16) NOT NULL,
  `service_name` varchar(160) NOT NULL,
  `target_status` int(10) NOT NULL,
  `task_status` varchar(16) NOT NULL,
  `attempts` int(10) NOT NULL DEFAULT '0',
  `next_retry_time` bigint(20) NOT NULL,
  `last_error` varchar(500) DEFAULT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL DEFAULT '1'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- Atomic node-port claims. The unique key prevents two concurrent forwarding
-- creates from selecting the same port on the same Agent.
--

CREATE TABLE `forward_port_reservation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `node_id` bigint(20) NOT NULL,
  `port` int(10) NOT NULL,
  `forward_id` bigint(20) NOT NULL,
  `endpoint` varchar(16) NOT NULL,
  `created_time` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_forward_port_reservation_node_port` (`node_id`,`port`),
  KEY `idx_forward_port_reservation_forward` (`forward_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `node`
--

CREATE TABLE `node` (
  `id` int(10) NOT NULL,
  `name` varchar(100) NOT NULL,
  `secret` varchar(100) NOT NULL,
  `ip` longtext,
  `server_ip` varchar(100) NOT NULL,
  `port_sta` int(10) NOT NULL,
  `port_end` int(10) NOT NULL,
  `version` varchar(100) DEFAULT NULL,
  `http` int(10) NOT NULL DEFAULT '0',
  `tls` int(10) NOT NULL DEFAULT '0',
  `socks` int(10) NOT NULL DEFAULT '0',
  `tcp_tuning_profile` varchar(20) NOT NULL DEFAULT 'balanced',
  `tcp_tuning_auto_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `tcp_tuning_profile_min` varchar(20) DEFAULT NULL,
  `tcp_tuning_profile_max` varchar(20) DEFAULT NULL,
  `ddns_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `ddns_token` longtext DEFAULT NULL,
  `ddns_record_name` varchar(253) DEFAULT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `speed_limit`
--

CREATE TABLE `speed_limit` (
  `id` int(10) NOT NULL,
  `name` varchar(100) NOT NULL,
  `speed` int(10) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `tunnel_name` varchar(100) NOT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `statistics_flow`
--

CREATE TABLE `statistics_flow` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `flow` bigint(20) NOT NULL,
  `total_flow` bigint(20) NOT NULL,
  `time` varchar(100) NOT NULL,
  `created_time` bigint(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- Last accepted sequence for each node/service traffic reporter. This bounds
-- idempotency storage to active services instead of one row per report.
--

CREATE TABLE `flow_report_cursor` (
  `node_id` bigint(20) NOT NULL,
  `service_name` varchar(160) NOT NULL,
  `session_id` varchar(96) NOT NULL,
  `session_started_at` bigint(20) NOT NULL,
  `last_sequence` bigint(20) NOT NULL DEFAULT '0',
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `tunnel`
--

CREATE TABLE `tunnel` (
  `id` int(10) NOT NULL,
  `name` varchar(100) NOT NULL,
  `traffic_ratio` decimal(10,1) NOT NULL DEFAULT '1.0',
  `in_node_id` int(10) NOT NULL,
  `in_ip` varchar(100) NOT NULL,
  `out_node_id` int(10) NOT NULL,
  `out_ip` varchar(100) NOT NULL,
  `type` int(10) NOT NULL,
  `protocol` varchar(10) NOT NULL DEFAULT 'tls',
  `flow` int(10) NOT NULL,
  `tcp_listen_addr` varchar(100) NOT NULL DEFAULT '[::]',
  `udp_listen_addr` varchar(100) NOT NULL DEFAULT '[::]',
  `interface_name` varchar(200) DEFAULT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 多入口隧道关联。旧隧道会在后端启动时自动以原 in_node_id 回填。
--
CREATE TABLE `tunnel_entry_node` (
  `id` bigint(20) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `node_id` int(10) NOT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) DEFAULT NULL,
  UNIQUE KEY `uk_tunnel_entry_node` (`tunnel_id`,`node_id`),
  KEY `idx_tunnel_entry_node_node` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 管理员维护的隧道解析域名池。仅决定面板显示/用户入口分配，
-- 不创建或修改 DDNS、DNS 记录及节点配置。
--
CREATE TABLE `tunnel_entry_domain` (
  `id` bigint(20) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `domain` varchar(253) NOT NULL,
  `is_default` tinyint(1) NOT NULL DEFAULT '0',
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL DEFAULT '1',
  UNIQUE KEY `uk_tunnel_entry_domain` (`tunnel_id`,`domain`),
  KEY `idx_tunnel_entry_domain_tunnel` (`tunnel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `user`
--

CREATE TABLE `user` (
  `id` int(10) NOT NULL,
  `user` varchar(100) NOT NULL,
  `pwd` varchar(100) NOT NULL,
  `token_version` int(10) NOT NULL DEFAULT '0',
  `role_id` int(10) NOT NULL,
  `exp_time` bigint(20) NOT NULL,
  `flow` bigint(20) NOT NULL,
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `flow_reset_time` bigint(20) NOT NULL,
  `num` int(10) NOT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 转存表中的数据 `user`
--

INSERT INTO `user` (`id`, `user`, `pwd`, `token_version`, `role_id`, `exp_time`, `flow`, `in_flow`, `out_flow`, `flow_reset_time`, `num`, `created_time`, `updated_time`, `status`) VALUES
(1, 'admin_user', '3c85cdebade1c51cf64ca9f3c09d182d', 0, 0, 2727251700000, 99999, 0, 0, 1, 99999, 1748914865000, 1754011744252, 1);

-- --------------------------------------------------------

--
-- 表的结构 `user_tunnel`
--

CREATE TABLE `user_tunnel` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `speed_id` int(10) DEFAULT NULL,
  `num` int(10) NOT NULL,
  `flow` bigint(20) NOT NULL,
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `flow_reset_time` bigint(20) NOT NULL,
  `exp_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL,
  `entry_address_mode` varchar(16) NOT NULL DEFAULT 'NONE',
  `entry_domain_id` bigint(20) DEFAULT NULL,
  KEY `idx_user_tunnel_entry_domain` (`entry_domain_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `vite_config`
--

CREATE TABLE `vite_config` (
  `id` int(10) NOT NULL,
  `name` varchar(200) NOT NULL,
  `value` varchar(200) NOT NULL,
  `time` bigint(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- VPS 托管：SSH 凭据仅保存 AES-GCM 密文，浏览器接口不会返回该字段。
-- USER 来源归用户自己与全部管理员管理；ADMIN 来源可分配给一个用户使用。
--
CREATE TABLE `vps_host` (
  `id` bigint(20) NOT NULL,
  `name` varchar(100) NOT NULL,
  `host` varchar(255) NOT NULL,
  `ssh_port` int(10) NOT NULL DEFAULT '22',
  `ssh_username` varchar(100) NOT NULL,
  `ssh_password` longtext NOT NULL,
  `origin` varchar(16) NOT NULL,
  `created_by_user_id` bigint(20) DEFAULT NULL,
  `owner_user_id` bigint(20) DEFAULT NULL,
  `assigned_user_id` bigint(20) DEFAULT NULL,
  `remark` varchar(1000) DEFAULT NULL,
  `ssh_fingerprint` varchar(255) DEFAULT NULL,
  `health_status` varchar(32) NOT NULL DEFAULT 'unknown',
  `last_check_time` bigint(20) DEFAULT NULL,
  `last_check_message` varchar(500) DEFAULT NULL,
  `last_latency_ms` bigint(20) DEFAULT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL DEFAULT '1'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `vps_deployment_task` (
  `id` bigint(20) NOT NULL,
  `vps_id` bigint(20) NOT NULL,
  `requested_by_user_id` bigint(20) NOT NULL,
  `task_type` varchar(64) NOT NULL,
  `task_status` varchar(32) NOT NULL,
  `output_log` mediumtext DEFAULT NULL,
  `started_time` bigint(20) DEFAULT NULL,
  `finished_time` bigint(20) DEFAULT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL DEFAULT '1'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 转存表中的数据 `vite_config`
--

INSERT INTO `vite_config` (`id`, `name`, `value`, `time`) VALUES
(1, 'app_name', 'Lunaris Relay', 1755147963000);

--
-- 转储表的索引
--

--
-- 表的索引 `forward`
--
ALTER TABLE `forward`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_forward_user_tunnel` (`user_id`,`tunnel_id`),
  ADD KEY `idx_forward_tunnel` (`tunnel_id`),
  ADD KEY `idx_forward_vps_host` (`vps_host_id`);

--
-- 表的索引 `forward_pause_task`
--
ALTER TABLE `forward_pause_task`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_forward_pause_task_forward` (`forward_id`),
  ADD KEY `idx_forward_pause_task_state` (`task_status`,`updated_time`);

--
-- 表的索引 `forward_sync_task`
--
ALTER TABLE `forward_sync_task`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uk_forward_sync_endpoint` (`operation_id`,`node_id`,`endpoint`),
  ADD KEY `idx_forward_sync_ready` (`task_status`,`next_retry_time`),
  ADD KEY `idx_forward_sync_node_ready` (`node_id`,`task_status`,`next_retry_time`),
  ADD KEY `idx_forward_sync_forward` (`forward_id`,`created_time`);

--
-- 表的索引 `node`
--
ALTER TABLE `node`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_node_secret` (`secret`);

--
-- 表的索引 `speed_limit`
--
ALTER TABLE `speed_limit`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `statistics_flow`
--
ALTER TABLE `statistics_flow`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_statistics_flow_user_id` (`user_id`,`id`),
  ADD KEY `idx_statistics_flow_created` (`created_time`);

--
-- 表的索引 `flow_report_cursor`
--
ALTER TABLE `flow_report_cursor`
  ADD PRIMARY KEY (`node_id`,`service_name`),
  ADD KEY `idx_flow_report_cursor_updated` (`updated_time`);

--
-- 表的索引 `tunnel`
--
ALTER TABLE `tunnel`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `tunnel_entry_node`
--
ALTER TABLE `tunnel_entry_node`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `tunnel_entry_domain`
--
ALTER TABLE `tunnel_entry_domain`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `user`
--
ALTER TABLE `user`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `user_tunnel`
--
ALTER TABLE `user_tunnel`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_user_tunnel_user_tunnel` (`user_id`,`tunnel_id`);

--
-- 表的索引 `vite_config`
--
ALTER TABLE `vite_config`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `name` (`name`);

ALTER TABLE `vps_host`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_vps_host_creator` (`created_by_user_id`),
  ADD KEY `idx_vps_host_owner` (`owner_user_id`),
  ADD KEY `idx_vps_host_assigned` (`assigned_user_id`),
  ADD KEY `idx_vps_host_status` (`status`);

ALTER TABLE `vps_deployment_task`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_vps_task_vps` (`vps_id`),
  ADD KEY `idx_vps_task_status` (`task_status`);

--
-- 在导出的表使用AUTO_INCREMENT
--

--
-- 使用表AUTO_INCREMENT `forward`
--
ALTER TABLE `forward`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `forward_pause_task`
--
ALTER TABLE `forward_pause_task`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `forward_sync_task`
--
ALTER TABLE `forward_sync_task`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `node`
--
ALTER TABLE `node`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `speed_limit`
--
ALTER TABLE `speed_limit`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `statistics_flow`
--
ALTER TABLE `statistics_flow`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `tunnel`
--
ALTER TABLE `tunnel`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `tunnel_entry_node`
--
ALTER TABLE `tunnel_entry_node`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `tunnel_entry_domain`
--
ALTER TABLE `tunnel_entry_domain`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `user`
--
ALTER TABLE `user`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `user_tunnel`
--
ALTER TABLE `user_tunnel`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `vite_config`
--
ALTER TABLE `vite_config`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

ALTER TABLE `vps_host`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

ALTER TABLE `vps_deployment_task`
  MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;
COMMIT;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
