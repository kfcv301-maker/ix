package com.admin.common.dto;

import lombok.Data;

/**
 * Server-side user-list filter.  Bounds are enforced in the service so older
 * clients that omit all fields remain compatible.
 */
@Data
public class UserListQueryDto {
    private Integer current;
    private Integer size;
    private String keyword;
}
