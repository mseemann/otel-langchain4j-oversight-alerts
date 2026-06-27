package io.mseemann.oteldemo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agentic tool chain for a shipping-status lookup, with six Human Oversight signals (Art. 14 EU
 * AI Act) layered on top - five deterministic, plus a second model call (judgeConfidence())
 * scoring the first answer's confidence as an additional, parallel signal. See the README for
 * the full rationale and trade-offs.
 *
 * Flow:
 * [HTTP GET /lc4j/order-status]
 *   |- [chat claude-haiku-...]             <- planning: needs lookup_order
 *   |- [execute_tool lookup_order]          <- tool span, returns a tracking ID
 *   |- [chat claude-haiku-...]             <- planning: needs get_tracking_status
 *   |- [execute_tool get_tracking_status]   <- tool span
 *   |- [chat claude-haiku-...]             <- final answer
 *   `- [chat claude-haiku-...]             <- judge: scores the final answer (success path only)
 *
 * Deliberately missing: no template, output guard, or PII filter stands between the model and
 * the customer-facing answer (response.aiMessage().text(), verbatim). The six signals make a bad
 * answer visible after the fact; they don't prevent it - see the README for what that gap means
 * in practice.
 */
@RestController
@RequestMapping("/lc4j")
public class LangChain4jController {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jController.class);
    private static final int MAX_TOOL_STEPS = 4;

    // Human Oversight (Art. 14): six signals on /lc4j/order-status, five deterministic plus
    // judgeConfidence() as an additional model-based one - see the README for the full rationale.

    // The only tool order that's actually correct here: get_tracking_status needs the tracking
    // ID that only lookup_order produces.
    private static final List<String> CANONICAL_TOOL_SEQUENCE = List.of("lookup_order", "get_tracking_status");

    // Plain substring search, no regex/NLP. False positives are accepted over false negatives
    // (see README) - e.g. an answer paraphrasing "distribution center" gets flagged anyway.
    private static final List<String> UNCERTAINTY_MARKERS = List.of(
            "not sure", "uncertain", "presumably", "possibly", "perhaps",
            "probably", "could not", "cannot determine", "no information",
            "unfortunately", "unclear", "don't know", "hard to say");

    private static final List<String> STATUS_KEYWORDS = List.of(
            "on its way", "delivered", "in_transit", "transit", "distribution center",
            "shipped", "delivery", "shipment", "tracking");

    // Deliberately conservative starting point, not empirically derived (no real traffic here).
    private static final int CONFIDENCE_REVIEW_THRESHOLD = 70;

    private static final Pattern SCORE_PATTERN = Pattern.compile("\\d+");

    private final AnthropicChatModel chatModel;
    private final Tracer tracer;
    // Deliberately not injected as a Spring bean: no ObjectMapper bean is available in this
    // context - a local instance is enough for plain argument parsing.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LangChain4jController(AnthropicChatModel chatModel, OpenTelemetry openTelemetry) {
        this.chatModel = chatModel;
        this.tracer = openTelemetry.getTracer("otel-langchain4j-demo");
    }

    // Generic agent loop (not fixed steps) - the model decides how many tool calls it needs.
    // flagForReview/userComment let the end user flag their own request, independent of the
    // automatic signals in tagHumanOversightSignals().
    @GetMapping("/order-status")
    public String orderStatus(
            @RequestParam(defaultValue = "ORD-4711") String orderNumber,
            @RequestParam(defaultValue = "false") boolean flagForReview,
            @RequestParam(defaultValue = "") String userComment) {
        log.info("lc4j order-status called for orderNumber={}, flagForReview={}", orderNumber, flagForReview);

        ToolSpecification lookupOrderTool = ToolSpecification.builder()
                .name("lookup_order")
                .description("Determines the tracking ID for an order number")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("order_number", "The order number, e.g. ORD-4711")
                        .required(List.of("order_number"))
                        .build())
                .build();

        ToolSpecification trackingStatusTool = ToolSpecification.builder()
                .name("get_tracking_status")
                .description("Determines the current shipping status for a tracking ID")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("tracking_id", "The tracking ID of the package")
                        .required(List.of("tracking_id"))
                        .build())
                .build();

        List<ToolSpecification> tools = List.of(lookupOrderTool, trackingStatusTool);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from(
                "Where is my order " + orderNumber + "? First determine the tracking ID, then " +
                "use it to look up the shipping status. Use the available tools."));

        // Logs the ACTUALLY executed tool sequence, regardless of whether/how often the model
        // deviates from the expected plan.
        List<String> executedToolSequence = new ArrayList<>();

        for (int step = 0; step < MAX_TOOL_STEPS; step++) {
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(tools)
                    .build();

            ChatResponse response = chatModel.chat(request);

            if (response.finishReason() != FinishReason.TOOL_EXECUTION) {
                String finalAnswer = response.aiMessage().text();
                tagHumanOversightSignals(orderNumber, executedToolSequence, finalAnswer, false, flagForReview, userComment);
                return finalAnswer;
            }

            messages.add(response.aiMessage());
            for (ToolExecutionRequest toolCall : response.aiMessage().toolExecutionRequests()) {
                executedToolSequence.add(toolCall.name());
                String result = executeTool(toolCall);
                messages.add(ToolExecutionResultMessage.from(toolCall, result));
            }
        }

        String fallbackAnswer = "Could not determine the delivery status (too many tool calls).";
        // MAX_TOOL_STEPS exceeded is itself an anomaly - flagged for review like the success path.
        tagHumanOversightSignals(orderNumber, executedToolSequence, fallbackAnswer, true, flagForReview, userComment);
        return fallbackAnswer;
    }

    // Tags the current span (the HTTP root span) with all six Human Oversight signals; see the
    // constants above for what each one checks. needsReview is their OR - any one is enough.
    private void tagHumanOversightSignals(
            String orderNumber,
            List<String> executedToolSequence,
            String finalAnswer,
            boolean fallbackTriggered,
            boolean userFlagged,
            String userComment) {
        Span span = Span.current();

        boolean sequenceAnomaly = !executedToolSequence.equals(CANONICAL_TOOL_SEQUENCE);
        span.setAttribute(AttributeKey.stringKey("human_oversight.tool_sequence_actual"),
                String.join(",", executedToolSequence));
        span.setAttribute(AttributeKey.booleanKey("human_oversight.tool_sequence_anomaly"), sequenceAnomaly);

        boolean uncertaintyDetected = containsAny(finalAnswer, UNCERTAINTY_MARKERS);
        boolean statusKeywordMissing = !containsAny(finalAnswer, STATUS_KEYWORDS);

        span.setAttribute(AttributeKey.booleanKey("human_oversight.uncertainty_detected"), uncertaintyDetected);
        span.setAttribute(AttributeKey.booleanKey("human_oversight.status_keyword_missing"), statusKeywordMissing);
        span.setAttribute(AttributeKey.booleanKey("human_oversight.fallback_triggered"), fallbackTriggered);
        span.setAttribute(AttributeKey.booleanKey("human_oversight.user_flagged"), userFlagged);
        span.setAttribute(AttributeKey.stringKey("human_oversight.user_comment"),
                userComment == null ? "" : userComment);

        // -1 = no score: judge skipped (fallback path) or judge call failed (judgeFailed below).
        boolean judgeFailed = false;
        boolean lowConfidence = false;
        int confidenceScore = -1;
        if (!fallbackTriggered) {
            try {
                confidenceScore = judgeConfidence(orderNumber, finalAnswer);
                lowConfidence = confidenceScore < CONFIDENCE_REVIEW_THRESHOLD;
            } catch (Exception e) {
                judgeFailed = true;
            }
        }
        span.setAttribute(AttributeKey.longKey("human_oversight.judge_confidence_score"), confidenceScore);
        span.setAttribute(AttributeKey.booleanKey("human_oversight.judge_low_confidence"), lowConfidence);
        span.setAttribute(AttributeKey.booleanKey("human_oversight.judge_failed"), judgeFailed);

        boolean needsReview = sequenceAnomaly || uncertaintyDetected || statusKeywordMissing
                || fallbackTriggered || userFlagged || lowConfidence || judgeFailed;
        span.setAttribute(AttributeKey.booleanKey("human_oversight.needs_review"), needsReview);

        if (needsReview) {
            log.info(
                    "Trace flagged for human review (traceId={}, tool_sequence_anomaly={}, uncertainty_detected={}, " +
                    "status_keyword_missing={}, fallback_triggered={}, user_flagged={}, judge_low_confidence={}, " +
                    "judge_failed={})",
                    span.getSpanContext().getTraceId(), sequenceAnomaly, uncertaintyDetected,
                    statusKeywordMissing, fallbackTriggered, userFlagged, lowConfidence, judgeFailed);
        }
    }

    private static boolean containsAny(String text, List<String> needles) {
        String lower = text.toLowerCase(Locale.ENGLISH);
        return needles.stream().anyMatch(lower::contains);
    }

    // Second chatModel call, scores the first answer 0-100. Plain text reply, not a tool/JSON
    // schema - simplest possible LLM-as-judge, not a production-grade one.
    private int judgeConfidence(String orderNumber, String finalAnswer) {
        ChatRequest judgeRequest = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(
                                "Rate the quality of an automatically generated answer on a scale "
                                + "from 0 (very uncertain/likely wrong) to 100 (very "
                                + "confident/correct). Reply with ONLY the number."),
                        UserMessage.from(
                                "Order number: " + orderNumber + "\n"
                                + "Generated answer: " + finalAnswer + "\n\n"
                                + "How confident are you that this answer is correct and "
                                + "complete? Reply with only the number 0-100.")))
                .build();

        String raw = chatModel.chat(judgeRequest).aiMessage().text();
        return parseScoreOrThrow(raw);
    }

    private static int parseScoreOrThrow(String raw) {
        Matcher matcher = SCORE_PATTERN.matcher(raw);
        if (!matcher.find()) {
            throw new IllegalStateException("Judge did not return a parseable number: " + raw);
        }
        return Math.clamp(Integer.parseInt(matcher.group()), 0, 100);
    }

    private String executeTool(ToolExecutionRequest toolCall) {
        return switch (toolCall.name()) {
            case "lookup_order" -> executeLookupOrder(toolCall);
            case "get_tracking_status" -> executeTrackingStatus(toolCall);
            default -> throw new IllegalArgumentException("Unknown tool: " + toolCall.name());
        };
    }

    private String executeLookupOrder(ToolExecutionRequest toolCall) {
        String orderNumber = parseArguments(toolCall).get("order_number");
        String trackingId = "TRK-" + orderNumber.replaceAll("[^0-9]", "");

        Span span = tracer.spanBuilder("execute_tool lookup_order")
                .setAttribute(AttributeKey.stringKey("gen_ai.operation.name"), "execute_tool")
                .setAttribute(AttributeKey.stringKey("gen_ai.tool.name"),      "lookup_order")
                .setAttribute(AttributeKey.stringKey("gen_ai.tool.call.id"),   toolCall.id())
                .setAttribute(AttributeKey.stringKey("tool.input.order_number"), orderNumber)
                .setAttribute(AttributeKey.booleanKey("agent.action.irreversible"), false)
                .startSpan();

        try (var ignored = span.makeCurrent()) {
            String result = String.format("{\"tracking_id\":\"%s\"}", trackingId);
            span.setAttribute(AttributeKey.stringKey("tool.output"), result);
            return result;
        } finally {
            span.end();
        }
    }

    private String executeTrackingStatus(ToolExecutionRequest toolCall) {
        String trackingId = parseArguments(toolCall).get("tracking_id");

        Span span = tracer.spanBuilder("execute_tool get_tracking_status")
                .setAttribute(AttributeKey.stringKey("gen_ai.operation.name"), "execute_tool")
                .setAttribute(AttributeKey.stringKey("gen_ai.tool.name"),      "get_tracking_status")
                .setAttribute(AttributeKey.stringKey("gen_ai.tool.call.id"),   toolCall.id())
                .setAttribute(AttributeKey.stringKey("tool.input.tracking_id"), trackingId)
                .setAttribute(AttributeKey.booleanKey("agent.action.irreversible"), false)
                .startSpan();

        try (var ignored = span.makeCurrent()) {
            String result = String.format(
                    "{\"tracking_id\":\"%s\",\"status\":\"in_transit\",\"location\":\"Hamburg distribution center\",\"estimated_delivery\":\"2026-06-21\"}",
                    trackingId);
            span.setAttribute(AttributeKey.stringKey("tool.output"), result);
            return result;
        } finally {
            span.end();
        }
    }

    private static final TypeReference<Map<String, String>> TOOL_ARGS_TYPE = new TypeReference<>() {};

    private Map<String, String> parseArguments(ToolExecutionRequest toolCall) {
        try {
            return objectMapper.readValue(toolCall.arguments(), TOOL_ARGS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not parse tool arguments: " + toolCall.arguments(), e);
        }
    }
}
