package com.admin.common.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Explicit out-of-band acknowledgement of a discovered SSH host key. */
@Data
public class VpsFingerprintConfirmDto {

    @NotNull(message = "VPS ID不能为空")
    private Long id;

    @NotBlank(message = "SSH 指纹不能为空")
    @Size(max = 255, message = "SSH 指纹长度异常")
    private String fingerprint;
}
