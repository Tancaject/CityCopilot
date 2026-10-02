package com.citycopilot.agent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationContextFormatterTest {

    @Test
    void formatsConversationHistoryExactly() {
        String context = ConversationContextFormatter.format(
                List.of(
                        new ConversationMessage("user", "第一问"),
                        new ConversationMessage("assistant", "第一答")
                ),
                "继续"
        );

        assertEquals(
                "Conversation history:\nuser: 第一问\nassistant: 第一答\nCurrent user: 继续",
                context
        );
    }

    @Test
    void keepsTheLastSixThousandCharactersOfLongContext() {
        String historicalMessage = "H".repeat(6_000);
        String currentMessage = "CURRENT";
        String fullContext = "Conversation history:\nuser: "
                + historicalMessage
                + "\nCurrent user: "
                + currentMessage;

        String context = ConversationContextFormatter.format(
                List.of(new ConversationMessage("user", historicalMessage)),
                currentMessage
        );

        assertEquals(6_000, context.length());
        assertEquals(fullContext.substring(fullContext.length() - 6_000), context);
    }
}
