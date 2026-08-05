package com.citycopilot.agent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreferenceVocabularyTest {

    @Test
    void recognizesPrefixAndSuffixCategoryExclusions() {
        assertEquals(Set.of("火锅"), PreferenceVocabulary.categoriesIn("附近不吃火锅").excluded());
        assertEquals(Set.of("火锅"), PreferenceVocabulary.categoriesIn("火锅不要").excluded());
        assertEquals(Set.of("海鲜"), PreferenceVocabulary.categoriesIn("海鲜过敏").excluded());
    }

    @Test
    void laterPositiveCategoryCorrectsEarlierExclusion() {
        var selection = PreferenceVocabulary.categoriesIn("先不要火锅，但今天还是想吃火锅");

        assertEquals(List.of("火锅"), selection.included());
        assertEquals(Set.of(), selection.excluded());
    }

    @Test
    void neutralLaterMentionDoesNotOverrideAnExclusion() {
        var selection = PreferenceVocabulary.categoriesIn("海鲜过敏，但附近海鲜店多吗");

        assertEquals(List.of(), selection.included());
        assertEquals(Set.of("海鲜"), selection.excluded());
    }
}
