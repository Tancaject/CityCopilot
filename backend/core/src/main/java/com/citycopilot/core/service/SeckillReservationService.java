package com.citycopilot.core.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.citycopilot.core.entity.SeckillReservation;
import com.citycopilot.core.entity.VoucherOrder;
import com.citycopilot.core.mapper.SeckillReservationMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class SeckillReservationService {
    public static final String RESERVED = "RESERVED";
    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String RELEASE_REQUIRED = "RELEASE_REQUIRED";
    public static final String RELEASED = "RELEASED";

    private final SeckillReservationMapper mapper;

    public SeckillReservationService(SeckillReservationMapper mapper) {
        this.mapper = mapper;
    }

    public SeckillReservation ensureReserved(VoucherOrder order) {
        SeckillReservation existing = mapper.selectById(order.getId());
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        SeckillReservation reservation = new SeckillReservation()
                .setOrderId(order.getId())
                .setVoucherId(order.getVoucherId())
                .setUserId(order.getUserId())
                .setStatus(RESERVED)
                .setVersion(0)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        try {
            mapper.insert(reservation);
            return reservation;
        } catch (DuplicateKeyException ignored) {
            return mapper.selectById(order.getId());
        }
    }

    public boolean transition(Long orderId, String expected, String target, String failureCode) {
        return mapper.update(
                null,
                new LambdaUpdateWrapper<SeckillReservation>()
                        .eq(SeckillReservation::getOrderId, orderId)
                        .eq(SeckillReservation::getStatus, expected)
                        .set(SeckillReservation::getStatus, target)
                        .set(SeckillReservation::getFailureCode, failureCode)
                        .set(SeckillReservation::getUpdatedAt, LocalDateTime.now())
                        .setSql("version = version + 1")
        ) == 1;
    }

    public SeckillReservation find(Long orderId) {
        return mapper.selectById(orderId);
    }
}
