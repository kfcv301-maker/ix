package com.admin.common.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Explicit response shape lets a new Agent safely fall back on old panels. */
@Data
public class FlowBatchResponse {
    private final String type = "flow_batch";
    private List<FlowBatchAck> acknowledged = new ArrayList<>();
}
