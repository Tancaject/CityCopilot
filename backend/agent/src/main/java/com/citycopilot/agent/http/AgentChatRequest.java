package com.citycopilot.agent.http;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Agent 对话接口的请求模型。
 *
 * <p>除消息外还携带会话、页面场景和地理位置提示；这些提示只辅助编排，不能替代服务端鉴权。</p>
 */
@Data
@Accessors(chain = true)
public class AgentChatRequest {
    /** 当前用户输入。 */
    private String message;
    /** 调用场景，例如消费者页面或管理端。 */
    private String scene;
    /** 前端维护的会话标识，用于隔离短期记忆。 */
    private String conversationId;
    /** 发起对话时所在的前端路由名。 */
    private String routeName;
    /** 发起对话时所在的前端路径。 */
    private String routePath;
    /** 当前页面关联店铺，用于补全工具参数。 */
    private Long currentShopId;
    /** 用户当前位置经度。 */
    private Double x;
    /** 用户当前位置纬度。 */
    private Double y;
}
