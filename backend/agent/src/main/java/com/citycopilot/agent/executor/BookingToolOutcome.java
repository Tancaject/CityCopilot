package com.citycopilot.agent.executor;

import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.contract.booking.BookingConfirmationContracts.ConfirmationView;
import java.util.Map;
import java.util.Set;
import static com.citycopilot.contract.booking.BookingConfirmationContracts.*;

/** Booking tools prepare Core-owned approvals; executors must stop instead of interpreting a card as a completed write. */
final class BookingToolOutcome {
    static final String MODEL_GUIDANCE = """
            When searching for new booking options or availability, first use search_bookable_options with the user's stated constraints.
            For an existing booking, use get_my_bookings or the specific confirmation, reschedule or cancellation proposal tool instead of a new search.
            Missing dates, times, people or ambiguous total/per-person budgets must remain omitted so Core returns structured clarifications; do not invent them.
            Never turn a displayed quote or availability search into a successful booking claim.
            hold_booking, confirm_booking, reschedule_booking and cancel_booking only return user review cards. Present the card and stop.
            """;
    private static final Set<String> PROPOSALS = Set.of("hold_booking", "confirm_booking", "reschedule_booking", "cancel_booking");
    private BookingToolOutcome() {}

    static boolean isProposal(ToolMetadata tool) {
        return tool != null && TOOL_SCOPE.equals(tool.scope()) && PROPOSALS.contains(tool.name());
    }
    static String stopMessage(ToolResult result) {
        if (!result.ok()) return null;
        String state;
        if (result.data() instanceof ConfirmationView card) state = card.state();
        else if (result.data() instanceof Map<?, ?> card && TYPE.equals(card.get("type")) && card.get("operationId") instanceof String) {
            state = String.valueOf(card.get("state"));
        } else return null;
        return switch (state) {
            case "PENDING" -> "已生成预约确认卡，请核对后在卡片中批准。本次工具调用尚未执行预约操作。";
            case "APPROVED" -> "原操作已获批准，结果尚未确定。请使用确认卡查询或重试原操作，不要重新创建预约。";
            case "SUCCEEDED" -> "该操作已有成功结果，请在确认卡中查看预约状态。";
            case "FAILED" -> "该操作未成功，请查看确认卡中的服务端原因。";
            default -> "该确认卡已失效或撤回，请查看卡片并重新查询具体方案。";
        };
    }
}
