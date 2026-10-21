package com.admin.controller;


import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.SubscriptionTokenService;
import com.admin.common.security.LoginAttemptLimiter;
import com.admin.common.utils.HttpContextUtils;
import com.admin.entity.User;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@RequestMapping("/api/v1/user")
public class UserController extends BaseController {

    @Resource
    private SubscriptionTokenService subscriptionTokenService;

    @Resource
    private LoginAttemptLimiter loginAttemptLimiter;

    @LogAnnotation
    @PostMapping("/login")
    public R login(@Validated @RequestBody LoginDto loginDto) {
        if (!loginAttemptLimiter.tryAcquire(loginDto.getUsername(),
                HttpContextUtils.getHttpServletRequest().getRemoteAddr())) {
            var attributes = (org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes != null && attributes.getResponse() != null) {
                attributes.getResponse().setStatus(429);
                attributes.getResponse().setHeader("Retry-After", "60");
            }
            return R.err(429, "登录请求过于频繁，请稍后重试");
        }
        return userService.login(loginDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/create")
    public R create(@Validated @RequestBody UserDto userDto) {
        return userService.createUser(userDto);
    }


    @LogAnnotation
    @RequireRole
    @PostMapping("/list")
    public R readAll(@RequestBody(required = false) UserListQueryDto queryDto) {
        return userService.getAllUsers(queryDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R update(@Validated @RequestBody UserUpdateDto userUpdateDto) {
        return userService.updateUser(userUpdateDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return userService.deleteUser(id);
    }

    @LogAnnotation
    @PostMapping("/package")
    public R getUserPackageInfo() {
        return userService.getUserPackageInfo();
    }

    @LogAnnotation
    @PostMapping("/subscription-token")
    public R subscriptionToken() {
        User user = userService.getById(JwtUtil.getUserIdFromToken());
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) return R.err(403, "账号不可用");
        return R.ok(Map.of("user", user.getUser(), "token", subscriptionTokenService.issue(user)));
    }

    @LogAnnotation
    @PostMapping("/updatePassword")
    public R updatePassword(@Validated @RequestBody ChangePasswordDto changePasswordDto) {
        return userService.updatePassword(changePasswordDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/reset")
    public R reset(@Validated @RequestBody ResetFlowDto resetFlowDto) {
        return userService.reset(resetFlowDto);
    }



}
