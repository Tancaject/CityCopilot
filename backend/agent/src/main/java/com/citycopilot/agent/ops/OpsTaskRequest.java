package com.citycopilot.agent.ops;

import java.util.Map;

/**
 * 旧版运维任务的内部请求。
 *
 * @param type 任务类型，如地理校准、订单异常扫描或日报
 * @param payload 任务参数
 */
public record OpsTaskRequest(String type, Map<String, Object> payload) {
}
