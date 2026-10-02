package com.citycopilot.core.booking;

import com.citycopilot.contract.booking.BookingConfirmationContracts.*;
import com.citycopilot.contract.booking.BookingContracts.*;
import com.citycopilot.contract.booking.PricingContracts.QuoteView;
import com.citycopilot.core.utils.UserHolder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.citycopilot.contract.booking.BookingConfirmationContracts.TYPE;

/** Immutable proposals are separate from approval, so every tool transport shares the same write boundary. */
@Service
public class BookingConfirmationService {
    private static final String PREFIX = "booking:confirmation:";
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
            if redis.call('EXISTS',KEYS[1]) == 0 or
              (redis.call('HGET',KEYS[1],'state') == 'PENDING' and tonumber(redis.call('HGET',KEYS[1],'expiresAt')) <= tonumber(ARGV[4])) then
              redis.call('HSET',KEYS[1],'payload',ARGV[1],'state','PENDING','expiresAt',ARGV[2],'requestHash',ARGV[3])
              redis.call('EXPIRE',KEYS[1],604800)
              return 1
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> DECIDE = new DefaultRedisScript<>("""
            local state=redis.call('HGET',KEYS[1],'state')
            if not state then return -1 end
            if redis.call('HGET',KEYS[1],'requestHash') ~= ARGV[3] then return -4 end
            if state == 'APPROVED' then return 2 end
            if state ~= 'PENDING' then return -2 end
            if tonumber(redis.call('HGET',KEYS[1],'expiresAt')) <= tonumber(ARGV[1]) then return -3 end
            redis.call('HSET',KEYS[1],'state',ARGV[2])
            redis.call('EXPIRE',KEYS[1],604800)
            return 1
            """, Long.class);

    private final BookingPortClient bookings;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public BookingConfirmationService(BookingPortClient bookings, StringRedisTemplate redis, ObjectMapper json) {
        this.bookings = bookings;
        this.redis = redis;
        this.json = json;
    }

    public ConfirmationView propose(ProposalRequest request) {
        String owner = owner();
        if (request == null || request.action() == null || !List.of("HOLD", "CONFIRM", "RESCHEDULE", "CANCEL").contains(request.action())) {
            throw invalid("预约动作无效，请重新选择暂留、确认、改期或取消。");
        }
        if (request.reason() != null && request.reason().length() > 500) throw invalid("取消原因最多500字。");
        String action = request.action();
        BookingView previous = null;
        QuoteView quote = null;
        Instant now = bookings.rules().serverTime();
        Instant expiry = now.plus(Duration.ofMinutes(5));
        Object arguments;
        if (action.equals("HOLD") || action.equals("RESCHEDULE")) {
            if (request.quoteId() == null) throw invalid("请先查询具体时段的报价，再生成确认卡。");
            quote = bookings.quote(request.quoteId());
            if (!owner.equals(quote.userId())) throw forbidden();
            if (!quote.expiresAt().isAfter(now)) throw conflict("报价已经过期，请重新查询价格后确认。");
            expiry = quote.expiresAt().isBefore(expiry) ? quote.expiresAt() : expiry;
        }
        if (!action.equals("HOLD")) {
            if (request.bookingId() == null || request.expectedVersion() == null) throw invalid("请先查询预约，提供预约编号和当前版本。");
            previous = bookings.booking(request.bookingId());
            if (!owner.equals(previous.userId())) throw forbidden();
            if (previous.version() != request.expectedVersion() || !previous.allowedActions().contains(action)) {
                throw conflict("预约状态已变化，请刷新预约后重新选择操作。");
            }
        }
        if (action.equals("HOLD")) {
            if (!"CREATE".equals(quote.purpose()) || request.bookingId() != null || request.expectedVersion() != null) throw invalid("暂留必须使用新预约报价。");
            arguments = new HoldRequest(quote.resourceId(), quote.slotId(), quote.partySize(), quote.quoteId(),
                    quote.source(), quote.impressionId(), quote.requestId());
        } else if (action.equals("RESCHEDULE")) {
            if (!"RESCHEDULE".equals(quote.purpose()) || !Objects.equals(quote.bookingId(), previous.id())
                    || !Objects.equals(quote.expectedBookingVersion(), previous.version())) throw invalid("改期报价与原预约不匹配，请重新报价。");
            arguments = new RescheduleRequest(previous.version(), quote.resourceId(), quote.slotId(), quote.partySize(), quote.quoteId());
        } else {
            if (request.quoteId() != null) throw invalid("确认和取消使用原预约的价格快照，无需新报价。");
            arguments = new ActionRequest(previous.version(), action.equals("CANCEL") ? request.reason() : null, null);
            // HOLD already committed a price promise; the original quote's display expiry no longer applies.
            if (action.equals("CONFIRM") && previous.holdExpiresAt().isBefore(expiry)) expiry = previous.holdExpiresAt();
        }
        String resourceId = quote == null ? previous.resourceId() : quote.resourceId();
        String slotId = quote == null ? previous.slotId() : quote.slotId();
        ResourceView resource = bookings.resource(resourceId);
        SlotView slot = bookings.slot(slotId);
        if (!slot.resourceId().equals(resource.id())) throw conflict("预约时段与资源不匹配，请刷新方案。");
        String bookingId = previous == null ? null : previous.id();
        String argumentsJson = encode(arguments);
        String hash = hash(owner, action, bookingId, argumentsJson, quote, previous);
        // The same owner/action/immutable quote/version always gets the same Commerce operation key, even after a lost response.
        String operationId = "bc-" + hash;
        BoundAction bound = new BoundAction(owner, action, bookingId, argumentsJson, null, expiry,
                resource.name(), resource.timezone(), slot.startAt(), slot.endAt(), quote == null ? previous.partySize() : quote.partySize(), previous, quote);
        bound = bound.withHash(integrity(bound));
        redis.execute(CREATE, List.of(PREFIX + operationId), encode(bound), String.valueOf(expiry.toEpochMilli()),
                bound.requestHash(), String.valueOf(now.toEpochMilli()));
        return status(operationId);
    }

    public ConfirmationView status(String operationId) {
        Stored stored = load(operationId);
        if (stored.state().equals("APPROVED") && stored.result() == null) {
            try {
                CommandView command = bookings.command(operationId);
                if (command != null && command.result() != null) {
                    saveResult(operationId, command.result());
                    stored = new Stored(stored.bound(), stored.state(), command.result());
                }
            } catch (RestClientResponseException error) {
                if (error.getStatusCode().value() != 404) throw error;
            } catch (BookingPortClient.Unavailable ignored) {
                // The approval and original key remain available while Commerce cannot answer the result query.
            }
        }
        return view(operationId, stored);
    }

    public ConfirmationView approve(String operationId, String requestHash) {
        Stored stored = load(operationId);
        if (!Objects.equals(stored.bound().requestHash(), requestHash)) throw conflict("确认内容已更新，请刷新卡片并重新核对后批准。");
        if (stored.result() != null) return view(operationId, stored);
        long decision = redis.execute(DECIDE, List.of(PREFIX + operationId),
                String.valueOf(Instant.now().toEpochMilli()), "APPROVED", requestHash);
        if (decision < 0) throw conflict(decision == -3 ? "确认卡已过期，请重新查询方案。" : "该确认卡已撤回或失效，请重新选择操作。");
        BoundAction bound = stored.bound();
        MutationResult result;
        try {
            result = switch (bound.action()) {
                case "HOLD" -> bookings.hold(decode(bound.argumentsJson(), HoldRequest.class), operationId);
                case "CONFIRM" -> bookings.confirm(bound.bookingId(), decode(bound.argumentsJson(), ActionRequest.class), operationId);
                case "CANCEL" -> bookings.cancel(bound.bookingId(), decode(bound.argumentsJson(), ActionRequest.class), operationId);
                case "RESCHEDULE" -> bookings.reschedule(bound.bookingId(), decode(bound.argumentsJson(), RescheduleRequest.class), operationId);
                default -> throw new IllegalStateException("Unsupported stored booking action");
            };
        } catch (BookingPortClient.Unavailable error) {
            return view(operationId, new Stored(bound, "APPROVED", null));
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().is5xxServerError() || error.getStatusCode().value() == 408 || error.getStatusCode().value() == 429) {
                return view(operationId, new Stored(bound, "APPROVED", null));
            }
            var body = parseError(error);
            result = body;
        }
        if (result == null) throw new IllegalStateException("Commerce booking mutation returned no result");
        saveResult(operationId, result);
        return view(operationId, new Stored(bound, "APPROVED", result));
    }

    public ConfirmationView decline(String operationId) {
        Stored stored = load(operationId);
        Long decision = redis.execute(DECIDE, List.of(PREFIX + operationId), String.valueOf(Instant.now().toEpochMilli()), "DECLINED", stored.bound().requestHash());
        if (decision != null && decision == 2) throw conflict("操作已获批准，不能撤回确认卡。请查询结果，需要取消预约时单独确认取消。");
        return status(operationId);
    }

    private Stored load(String operationId) {
        String owner = owner();
        if (operationId == null || !operationId.matches("bc-[a-f0-9]{64}")) throw invalid("操作编号无效。");
        Map<Object, Object> fields = redis.opsForHash().entries(PREFIX + operationId);
        if (fields.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "确认记录已失效。若此前已经批准，可在我的预约中使用原操作编号查询结果。");
        BoundAction bound = decode(String.valueOf(fields.get("payload")), BoundAction.class);
        if (!owner.equals(bound.ownerId())) throw forbidden();
        String actual = hash(bound.ownerId(), bound.action(), bound.bookingId(), bound.argumentsJson(), bound.quote(), bound.previous());
        if (!integrity(bound).equals(bound.requestHash()) || !operationId.equals("bc-" + actual)) throw conflict("确认记录完整性检查失败，请重新查询预约。");
        MutationResult result = fields.get("result") == null ? null : decode(String.valueOf(fields.get("result")), MutationResult.class);
        return new Stored(bound, String.valueOf(fields.get("state")), result);
    }

    private ConfirmationView view(String operationId, Stored stored) {
        BoundAction b = stored.bound();
        Instant now = Instant.now();
        String state = stored.result() != null ? (stored.result().httpStatus() < 400 ? "SUCCEEDED" : "FAILED")
                : stored.state().equals("PENDING") && !b.expiresAt().isAfter(now) ? "EXPIRED" : stored.state();
        String message = switch (state) {
            case "PENDING" -> "请核对资源、时段、人数和价格，再明确批准本次操作。";
            case "APPROVED" -> "操作已获批准，结果尚未确定。请查询结果或使用此卡重试原操作，不要重新创建预约。";
            case "EXPIRED" -> "确认卡已过期，尚未执行。请重新查询方案。";
            case "DECLINED" -> "已撤回确认卡，未执行预约操作。";
            default -> stored.result().message();
        };
        return new ConfirmationView(TYPE, operationId, b.action(), state, b.requestHash(), b.expiresAt(), now,
                b.bookingId(), b.resourceName(), b.timezone(), b.startAt(), b.endAt(), b.partySize(),
                b.previous(), b.quote(), stored.result(), message, "/booking/confirm/" + operationId);
    }

    private void saveResult(String operationId, MutationResult result) {
        redis.opsForHash().putIfAbsent(PREFIX + operationId, "result", encode(result));
    }
    private MutationResult parseError(RestClientResponseException error) {
        try {
            var body = json.readTree(error.getResponseBodyAsString());
            BookingView booking = body.hasNonNull("booking") ? json.treeToValue(body.get("booking"), BookingView.class) : null;
            return new MutationResult(body.path("code").asText("BOOKING_REJECTED"), error.getStatusCode().value(),
                    body.path("message").asText("预约服务拒绝本次操作，请刷新预约后查看原因。"), booking);
        } catch (JacksonException malformed) {
            throw new IllegalStateException("Commerce booking error violated its JSON contract", malformed);
        }
    }
    private String hash(String owner, String action, String bookingId, String argumentsJson, QuoteView quote, BookingView previous) {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("ownerId", owner); fingerprint.put("action", action); fingerprint.put("bookingId", bookingId);
        fingerprint.put("arguments", argumentsJson); fingerprint.put("quote", quote);
        fingerprint.put("priceSnapshot", previous == null ? null : previous.priceSnapshot());
        return digest(encode(fingerprint));
    }
    private String integrity(BoundAction bound) {
        return digest(encode(bound.withHash(null)));
    }
    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException error) { throw new IllegalStateException("Cannot serialize booking confirmation", error); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JacksonException error) { throw new IllegalStateException("Stored booking confirmation is invalid", error); }
    }
    private String owner() {
        var user = UserHolder.getUser();
        if (user == null || user.getId() == null || user.getId() <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请登录后处理自己的预约。");
        return user.getId().toString();
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "只能处理本人创建的预约确认卡。"); }
    public record BoundAction(String ownerId, String action, String bookingId, String argumentsJson, String requestHash,
                              Instant expiresAt, String resourceName, String timezone, Instant startAt, Instant endAt,
                              int partySize, BookingView previous, QuoteView quote) {
        BoundAction withHash(String hash) {
            return new BoundAction(ownerId, action, bookingId, argumentsJson, hash, expiresAt, resourceName, timezone,
                    startAt, endAt, partySize, previous, quote);
        }
    }
    private record Stored(BoundAction bound, String state, MutationResult result) {}
}
