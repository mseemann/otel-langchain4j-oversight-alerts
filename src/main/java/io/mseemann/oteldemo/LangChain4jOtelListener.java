package io.mseemann.oteldemo;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Scope;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manual OTel instrumentation for LangChain4j - no official OTel module exists
 * for Spring Boot (langchain4j/langchain4j#2331), so ChatModelListener's three
 * callbacks (onRequest/onResponse/onError) are all there is to build spans and
 * attributes from.
 */
@Component
public class LangChain4jOtelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jOtelListener.class);

    // attributes() map keys - how the span/scope survive between callbacks
    private static final String SPAN_KEY  = "otel.span";
    private static final String SCOPE_KEY = "otel.scope";

    // GenAI Semantic Conventions attribute keys
    private static final AttributeKey<String> GEN_AI_SYSTEM        = AttributeKey.stringKey("gen_ai.system");
    private static final AttributeKey<String> GEN_AI_OPERATION     = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<String> GEN_AI_FINISH_REASON = AttributeKey.stringKey("gen_ai.response.finish_reason");
    private static final AttributeKey<Long>   GEN_AI_INPUT_TOKENS  = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long>   GEN_AI_OUTPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<String> CONTENT_KEY          = AttributeKey.stringKey("content");

    private final Tracer tracer;

    public LangChain4jOtelListener(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("otel-langchain4j-demo");
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        ChatRequest request = requestContext.chatRequest();
        String model = request.parameters().modelName();

        Span span = tracer.spanBuilder("chat " + model)
                .setAttribute(GEN_AI_SYSTEM,        "anthropic")
                .setAttribute(GEN_AI_OPERATION,     "chat")
                .setAttribute(GEN_AI_REQUEST_MODEL, model != null ? model : "unknown")
                .startSpan();

        // Makes this span "current" for this thread, so nested tool spans attach to it
        Scope scope = span.makeCurrent();

        requestContext.attributes().put(SPAN_KEY,  span);
        requestContext.attributes().put(SCOPE_KEY, scope);

        // Prompt messages as span events
        request.messages().forEach(message -> {
            String role = message.type().name().toLowerCase();
            String eventName = "gen_ai." + role + ".message";
            String text = extractText(message);
            if (text != null) {
                span.addEvent(eventName, Attributes.of(CONTENT_KEY, text));
            }
        });

        log.debug("LangChain4j span started: {}", span.getSpanContext().getSpanId());
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        Span  span  = (Span)  responseContext.attributes().get(SPAN_KEY);
        Scope scope = (Scope) responseContext.attributes().get(SCOPE_KEY);
        if (span == null) return;

        ChatResponse response = responseContext.chatResponse();
        TokenUsage   usage    = response.tokenUsage();

        if (response.finishReason() != null) {
            span.setAttribute(GEN_AI_FINISH_REASON, response.finishReason().name());
        }
        if (usage != null) {
            span.setAttribute(GEN_AI_INPUT_TOKENS,  (long) usage.inputTokenCount());
            span.setAttribute(GEN_AI_OUTPUT_TOKENS, (long) usage.outputTokenCount());
        }

        String responseText = response.aiMessage().text();
        if (responseText != null) {
            span.addEvent("gen_ai.assistant.message", Attributes.of(CONTENT_KEY, responseText));
        }

        closeAndEnd(span, scope);
        log.debug("LangChain4j span ended: {}", span.getSpanContext().getSpanId());
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        Span  span  = (Span)  errorContext.attributes().get(SPAN_KEY);
        Scope scope = (Scope) errorContext.attributes().get(SCOPE_KEY);
        if (span == null) return;

        span.recordException(errorContext.error());
        span.setStatus(StatusCode.ERROR, errorContext.error().getMessage());
        closeAndEnd(span, scope);
    }

    private String extractText(dev.langchain4j.data.message.ChatMessage message) {
        return switch (message) {
            case UserMessage   um -> um.singleText();
            case SystemMessage sm -> sm.text();
            case AiMessage     am -> am.text();
            default -> null;
        };
    }

    private void closeAndEnd(Span span, Scope scope) {
        if (scope != null) scope.close();
        span.end();
    }
}
