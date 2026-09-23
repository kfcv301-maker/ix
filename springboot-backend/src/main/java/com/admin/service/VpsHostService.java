package com.admin.service;

import com.admin.common.dto.VpsActionDto;
import com.admin.common.dto.VpsHostDto;
import com.admin.common.dto.VpsHostUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.VpsHost;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface VpsHostService extends IService<VpsHost> {

    R listHosts();

    R createHost(VpsHostDto hostDto);

    R updateHost(VpsHostUpdateDto hostDto);

    R deleteHost(VpsActionDto actionDto);

    R checkHost(VpsActionDto actionDto);

    R resetFingerprint(VpsActionDto actionDto);

    R listAssignableUsers();

    VpsHost findOperableHost(Long userId, boolean administrator, Long hostId);

    boolean canOperate(Long userId, boolean administrator, Long hostId);

    void checkAllActiveHosts();

    void recordSuccessfulFingerprint(VpsHost host, String fingerprint);

    String decryptSshPassword(VpsHost host);

    List<VpsHost> listAccessibleHosts(Long userId, boolean administrator);
}
