package com.citycopilot.core.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisGeoCoordinatesTest {

    @ParameterizedTest
    @MethodSource("validCoordinates")
    void acceptsRedisGeoBoundaries(Double longitude, Double latitude) {
        assertTrue(RedisGeoCoordinates.isValid(longitude, latitude));
    }

    @ParameterizedTest
    @MethodSource("invalidCoordinates")
    void rejectsMissingNonFiniteAndOutOfRangeCoordinates(Double longitude, Double latitude) {
        assertFalse(RedisGeoCoordinates.isValid(longitude, latitude));
    }

    private static Stream<Arguments> validCoordinates() {
        return Stream.of(
                Arguments.of(-180D, -85.05112878D),
                Arguments.of(0D, 0D),
                Arguments.of(180D, 85.05112878D)
        );
    }

    private static Stream<Arguments> invalidCoordinates() {
        return Stream.of(
                Arguments.of(null, 0D),
                Arguments.of(0D, null),
                Arguments.of(Double.NaN, 0D),
                Arguments.of(0D, Double.NaN),
                Arguments.of(Double.NEGATIVE_INFINITY, 0D),
                Arguments.of(0D, Double.POSITIVE_INFINITY),
                Arguments.of(-180.00000001D, 0D),
                Arguments.of(180.00000001D, 0D),
                Arguments.of(0D, -85.05112879D),
                Arguments.of(0D, 85.05112879D)
        );
    }
}
