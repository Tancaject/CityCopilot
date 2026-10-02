package com.citycopilot.eval;

import com.citycopilot.agent.router.AgentRouter;
import com.citycopilot.agent.router.Mode;

import java.util.ArrayList;
import java.util.List;

/**
 * 使用生产 {@link AgentRouter} 和确定性关键词规则的离线预测器。
 *
 * <p>它不调用外部模型，适合 CI 快速回归路由模式和基础工具映射。</p>
 */
final class HeuristicEvalPredictor implements EvalPredictor {
    /** 与 Agent 生产代码共享的 REACT/PLAN_EXECUTE 分类器。 */
    private final AgentRouter router = new AgentRouter();

    /** 根据路由模式和输入关键词生成可重复的离线预测。 */
    @Override
    public EvalPrediction predict(EvalCase evalCase) {
        Mode mode = router.classify(evalCase.input());
        List<String> tools = predictTools(evalCase.input(), mode);
        String prefix = mode == Mode.PLAN_EXECUTE ? "计划工具" : "工具";
        String answer = prefix + ": " + String.join(", ", tools) + " | " + safeInput(evalCase);
        return new EvalPrediction(mode.name(), tools, answer, null, null);
    }

    /** @return 非空用户输入，供报告关键词断言使用 */
    private String safeInput(EvalCase evalCase) {
        return evalCase.input() == null ? "" : evalCase.input();
    }

    /**
     * @param input 用户输入
     * @param mode 已分类的执行模式
     * @return 规则预测的工具名列表
     */
    private List<String> predictTools(String input, Mode mode) {
        String text = input == null ? "" : input;
        String normalized = text.toLowerCase();
        List<String> tools = new ArrayList<>();
        if (mode == Mode.PLAN_EXECUTE) {
            // PLAN_EXECUTE 允许一个意图映射多个连续工具，因此各规则独立累加。
            if (text.contains("下单")) {
                tools.add("search_nearby_shops");
                tools.add("list_vouchers_by_shop");
                tools.add("place_seckill_order");
            }
            if (normalized.contains("place order")) {
                tools.add("place_seckill_order");
            }
            if (text.contains("支付") || normalized.contains("pay")) {
                tools.add("pay_order");
            }
            if (text.contains("退款") || normalized.contains("refund")) {
                tools.add("refund_order");
            }
            if (text.contains("取消") || normalized.contains("cancel order")) {
                tools.add("cancel_order");
            }
            if (text.contains("给这篇笔记点赞") || text.contains("点赞这篇")) {
                tools.add("like_blog");
            }
            if (text.contains("关注这个用户") || text.contains("取消关注") || text.contains("取关")) {
                tools.add("set_follow_user");
            }
            if (text.contains("批量地理编码") || normalized.contains("start batch")) {
                tools.add("start_batch_geocode");
            }
            if (text.contains("重试失败") || normalized.contains("retry failed")) {
                tools.add("retry_geo_batch_failed_items");
            }
            return tools;
        }
        if (normalized.contains("nearby") || normalized.contains("hotpot")) {
            tools.add("search_nearby_shops");
        } else if (text.contains("订单")) {
            tools.add("get_my_orders");
        } else if (text.contains("店铺详情")) {
            tools.add("get_shop_detail");
        } else if (text.contains("店铺分类") || text.contains("分类")) {
            tools.add("list_shop_types");
        } else if (text.contains("优惠券") || text.contains("券") || normalized.contains("coupon") || normalized.contains("voucher")) {
            tools.add("list_vouchers_by_shop");
        } else if (text.contains("热门") || text.contains("探店")) {
            tools.add("list_hot_blogs");
        } else if (text.contains("笔记详情")) {
            tools.add("get_blog_detail");
        } else if (text.contains("谁点赞")) {
            tools.add("list_blog_likes");
        } else if (text.contains("关注动态")) {
            tools.add("get_follow_feed");
        } else if (text.contains("是否关注")) {
            tools.add("check_follow_user");
        } else if (text.contains("共同关注")) {
            tools.add("list_common_follows");
        } else if (text.contains("地址") || text.contains("经纬度")) {
            tools.add("geocode_address");
        } else if (normalized.contains("poi") || text.contains("搜索高德")) {
            tools.add("search_geo_poi");
        } else if (text.contains("地理任务列表")) {
            tools.add("list_geo_batch_jobs");
        } else if (text.contains("地理") || text.contains("高德")) {
            tools.add("get_geo_provider_status");
        } else if (text.contains("审计保留")) {
            tools.add("get_audit_retention_status");
        } else if (text.contains("审计")) {
            tools.add("query_audit_logs");
        } else if (text.contains("运营") || containsWord(normalized, "ops")) {
            tools.add("ops_summary");
        } else {
            tools.add("search_nearby_shops");
        }
        return tools;
    }

    /** @return 文本是否包含指定的完整英文单词 */
    private boolean containsWord(String text, String word) {
        return text != null && text.matches(".*\\b" + word + "\\b.*");
    }
}
