package com.admin.common.dto;


import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class LoginDto {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 128, message = "用户名过长")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 1024, message = "密码过长")
    private String password;

    @Size(max = 256, message = "验证码参数过长")
    private String captchaId;


}
