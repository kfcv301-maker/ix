package com.admin.service.impl;

import com.admin.common.dto.VpsActionDto;
import com.admin.common.dto.VpsDeploymentDto;
import com.admin.common.dto.VpsDeploymentTaskView;
import com.admin.common.lang.R;
import com.admin.common.utils.HttpContextUtils;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.User;
import com.admin.entity.VpsDeploymentTask;
import com.admin.entity.VpsHost;
import com.admin.mapper.UserMapper;
import com.admin.mapper.VpsDeploymentTaskMapper;
import com.admin.service.VpsDeploymentService;
import com.admin.service.VpsHostService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.BeanUtils;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Creates only reviewed deployment tasks; it deliberately accepts no shell command text. */
@Service
public class VpsDeploymentServiceImpl implements VpsDeploymentService {

    private static final int ACTIVE_STATUS = 1;
    private static final String PENDING = "pending";
    private static final String RUNNING = "running";
    private static final String BACKEND_INSTALL = "backend";

    @Resource
    private VpsHostService vpsHostService;

    @Resource
    private VpsDeploymentTaskMapper taskMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private VpsDeploymentExecutor deploymentExecutor;

    @Override
    public R createTask(VpsDeploymentDto deploymentDto) {
        Actor actor = currentActor();
        VpsHost host = vpsHostService.findOperableHost(actor.userId, actor.administrator, deploymentDto.getId());
        if (host == null) return R.err(403, "没有在此 VPS 上执行部署任务的权限");
        if (!vpsHostService.isFingerprintVerified(host)) {
            return R.err("请先检测并确认 SSH 主机指纹，再安装后端");
        }

        Long concurrent = taskMapper.selectCount(new QueryWrapper<VpsDeploymentTask>()
                .eq("vps_id", host.getId())
                .eq("status", ACTIVE_STATUS)
                .in("task_status", PENDING, RUNNING));
        if (concurrent != null && concurrent > 0) return R.err("此 VPS 已有部署任务正在运行，请等待完成后再试");

        long now = System.currentTimeMillis();
        VpsDeploymentTask task = new VpsDeploymentTask();
        task.setVpsId(host.getId());
        task.setRequestedByUserId(actor.userId);
        // The browser no longer chooses a template. Never permit an old client
        // to turn this action into Docker-only or full-panel installation.
        task.setTaskType(BACKEND_INSTALL);
        task.setTaskStatus(PENDING);
        task.setActiveLock("vps:" + host.getId());
        task.setOutputLog("任务已排队，等待 SSH 连接…\n");
        task.setCreatedTime(now);
        task.setUpdatedTime(now);
        task.setStatus(ACTIVE_STATUS);
        try {
            if (taskMapper.insert(task) <= 0) return R.err("创建部署任务失败");
        } catch (DuplicateKeyException exception) {
            return R.err("此 VPS 已有部署任务正在运行，请等待完成后再试");
        }
        try {
            deploymentExecutor.execute(task.getId());
        } catch (TaskRejectedException exception) {
            taskMapper.update(null, new UpdateWrapper<VpsDeploymentTask>().eq("id", task.getId())
                    .set("task_status", "failed")
                    .set("output_log", "任务队列已满，请稍后重试。\n")
                    .set("finished_time", System.currentTimeMillis())
                    .set("updated_time", System.currentTimeMillis())
                    .set("active_lock", null));
            return R.err("部署任务队列繁忙，请稍后重试");
        }
        return R.ok(toView(task, loadUserNames(Collections.singletonList(task))));
    }

    @Override
    public R listTasks(VpsActionDto actionDto) {
        Actor actor = currentActor();
        VpsHost host = vpsHostService.findOperableHost(actor.userId, actor.administrator, actionDto.getId());
        if (host == null) return R.err(403, "没有查看此 VPS 部署任务的权限");
        List<VpsDeploymentTask> tasks = taskMapper.selectList(new QueryWrapper<VpsDeploymentTask>()
                .eq("vps_id", host.getId())
                .eq("status", ACTIVE_STATUS)
                .orderByDesc("created_time")
                .last("LIMIT 30"));
        Map<Long, String> names = loadUserNames(tasks);
        return R.ok(tasks.stream().map(task -> toView(task, names)).collect(Collectors.toList()));
    }

    private Actor currentActor() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        return new Actor(JwtUtil.getUserIdFromToken(token), Objects.equals(JwtUtil.getRoleIdFromToken(token), 0));
    }

    private VpsDeploymentTaskView toView(VpsDeploymentTask task, Map<Long, String> userNames) {
        VpsDeploymentTaskView view = new VpsDeploymentTaskView();
        BeanUtils.copyProperties(task, view, "status");
        view.setRequestedByUserName(userNames.get(task.getRequestedByUserId()));
        return view;
    }

    private Map<Long, String> loadUserNames(List<VpsDeploymentTask> tasks) {
        List<Long> ids = tasks.stream().map(VpsDeploymentTask::getRequestedByUserId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) return Collections.emptyMap();
        Map<Long, String> names = new HashMap<>();
        for (User user : userMapper.selectBatchIds(ids)) names.put(user.getId(), user.getUser());
        return names;
    }

    private static final class Actor {
        private final Long userId;
        private final boolean administrator;

        private Actor(Long userId, boolean administrator) {
            this.userId = userId;
            this.administrator = administrator;
        }
    }
}
