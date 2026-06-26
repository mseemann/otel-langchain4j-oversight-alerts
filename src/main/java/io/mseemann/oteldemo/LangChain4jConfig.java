package io.mseemann.oteldemo;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Manual LangChain4j configuration.
 *
 * LangChain4j's auto-config is excluded (spring.autoconfigure.exclude) to avoid a bean-name
 * collision with Spring AI's AnthropicChatModel. The OtelListener is wired directly in the
 * builder here, not through Spring.
 */
@Configuration
public class LangChain4jConfig {

    @Value("${ANTHROPIC_API_KEY}")
    private String apiKey;

    @Bean
    public AnthropicChatModel lc4jChatModel(LangChain4jOtelListener otelListener) {
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName("claude-haiku-4-5-20251001")
                .maxTokens(1024)
                .listeners(List.of(otelListener))
                .build();
    }
}
