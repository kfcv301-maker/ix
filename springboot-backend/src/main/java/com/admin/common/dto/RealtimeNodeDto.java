package com.admin.common.dto;

import lombok.Data;

/** Only the identity and display name needed by the traffic dashboard. */
@Data
public class RealtimeNodeDto {
    private Long id;
    private String name;
}
