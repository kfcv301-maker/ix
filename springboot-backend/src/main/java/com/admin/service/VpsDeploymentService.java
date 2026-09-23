package com.admin.service;

import com.admin.common.dto.VpsActionDto;
import com.admin.common.dto.VpsDeploymentDto;
import com.admin.common.lang.R;

public interface VpsDeploymentService {

    R createTask(VpsDeploymentDto deploymentDto);

    R listTasks(VpsActionDto actionDto);
}
