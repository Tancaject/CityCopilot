package com.citycopilot.agent.memory;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplicitPreferenceExtractorTest {
    private final ExplicitPreferenceExtractor extractor = new ExplicitPreferenceExtractor();

    @Test
    void extractsOnlyWhitelistedDurablePreferences() {
        ExplicitPreferenceExtractor.Extraction extraction = extractor.extract(
                "请记住，以后默认我喜欢火锅和安静的店，人均100元以内，评分4.5以上，3公里以内，有券优先"
        );
        Map<PreferenceType, String> values = extraction.preferences().stream()
                .collect(Collectors.toMap(ExplicitPreference::type, ExplicitPreference::value));

        assertEquals("火锅", values.get(PreferenceType.PREFERRED_CATEGORY));
        assertEquals("安静", values.get(PreferenceType.PREFERRED_AMBIENCE));
        assertEquals("100", values.get(PreferenceType.MAX_AVG_PRICE));
        assertEquals("4.5", values.get(PreferenceType.MIN_SCORE));
        assertEquals("3000", values.get(PreferenceType.MAX_DISTANCE_METERS));
        assertEquals("true", values.get(PreferenceType.PREFER_VOUCHERS));
        assertTrue(extraction.memoryOnly());
    }

    @Test
    void ignoresOneOffRequestsAndSensitiveFreeText() {
        assertFalse(extractor.extract("这次预算100，今天想吃火锅，推荐附近的店").hasMutation());
        assertFalse(extractor.extract("请记住我的手机号和支付密码").hasMutation());
        assertFalse(extractor.extract("请记住我不喜欢火锅").hasMutation());
    }

    @Test
    void recognizesClearAndMixedQueryCommands() {
        ExplicitPreferenceExtractor.Extraction clear = extractor.extract("清除我的所有长期偏好");
        assertTrue(clear.clearAll());
        assertTrue(clear.memoryOnly());

        ExplicitPreferenceExtractor.Extraction mixed = extractor.extract(
                "请记住以后人均80元以内，并推荐附近的咖啡店"
        );
        assertTrue(mixed.hasMutation());
        assertFalse(mixed.memoryOnly());
    }
}
