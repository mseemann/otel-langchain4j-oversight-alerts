package io.mseemann.oteldemo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
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

/**
 * Agentic tool chain for a shipping-status lookup, with five deterministic Human Oversight
 * signals (Art. 14 EU AI Act) layered on top - and nothing else.
 *
 * Flow:
 * [HTTP GET /lc4j/order-status]
 *   |- [chat claude-haiku-...]             <- planning: needs lookup_order
 *   |- [execute_tool lookup_order]          <- tool span, returns a tracking ID
 *   |- [chat claude-haiku-...]             <- planning: needs get_tracking_status (using the ID from step 1)
 *   |- [execute_tool get_tracking_status]   <- tool span
 *   `- [chat claude-haiku-...]             <- final answer
 *
 * The model decides on its own how many tool calls it needs (a generic loop, not a fixed step
 * sequence) - tool 2 isn't fed by the original user input but by the OUTPUT of tool 1, a
 * dependency that's only visible in the trace, never in the request alone.
 *
 * What this endpoint deliberately does NOT have: the customer-facing answer in the success path
 * is whatever the model writes - response.aiMessage().text(), verbatim, unfiltered. There is no
 * Java template standing between the model and the customer, no output guard, no PII tokenizer
 * on the way in, no second classification step deciding what kind of request this even is. The
 * five tagHumanOversightSignals() attributes below give a reviewer excellent visibility into
 * WHETHER something went wrong - they do nothing to PREVENT a wrong answer from reaching the
 * customer in the first place. Observability and containment are two different problems; this
 * endpoint solves only the first one. See the README for what that gap means in practice.
 */
@RestController
@RequestMapping("/lc4j")
public class LangChain4jController {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jController.class);
    private static final int MAX_TOOL_STEPS = 4;

    // -----------------------------------------------------------------------
    // Human Oversight (Art. 14): signals on /lc4j/order-status.
    //
    // Deliberately NO LLM judge (an earlier version of this class had a second chatModel call
    // that scored the first answer) - a model scoring its own answer is itself just another AI
    // system with the same uncertainties, and chat completions don't expose a native
    // confidence/logprob signal anyway. All three text-based signals below are purely
    // deterministic: a fixed expected-vs-actual comparison, or a fixed word list visible directly
    // in the code - no model call, no learning, and any reviewer can see exactly WHY an answer
    // was flagged.
    //
    // CANONICAL_TOOL_SEQUENCE: the only tool order that's actually correct for this task -
    // get_tracking_status needs the tracking ID that only lookup_order produces, so that's also
    // the only sequence that makes sense.
    //
    // UNCERTAINTY_MARKERS / STATUS_KEYWORDS: plain substring search, not regex/NLP, deliberately
    // kept simple. Trade-off: false positives are possible (e.g. an answer that paraphrases
    // "distribution center" instead of naming it literally would be wrongly flagged as
    // incomplete) - that's accepted here, because a missed real failure case (a false negative)
    // is the much more expensive problem for Human Oversight.
    //
    // The fourth and fifth signals (fallbackTriggered, userFlagged) don't come from text
    // analysis but directly from the call site: fallbackTriggered from the tool loop in
    // orderStatus() (MAX_TOOL_STEPS exceeded), userFlagged from the end user themselves via the
    // "flagForReview" request parameter - the human whose request is being handled can flag their
    // own request for review directly, independent of whether any automatic signal finds
    // anything unusual at all.
    // -----------------------------------------------------------------------
    private static final List<String> CANONICAL_TOOL_SEQUENCE = List.of("lookup_order", "get_tracking_status");

    private static final List<String> UNCERTAINTY_MARKERS = List.of(
            "not sure", "uncertain", "presumably", "possibly", "perhaps",
            "probably", "could not", "cannot determine", "no information",
            "unfortunately", "unclear", "don't know", "hard to say");

    private static final List<String> STATUS_KEYWORDS = List.of(
            "on its way", "delivered", "in_transit", "transit", "distribution center",
            "shipped", "delivery", "shipment", "tracking");

    private final AnthropicChatModel chatModel;
    private final Tracer tracer;
    // Deliberately not injected as a Spring bean: no ObjectMapper bean is available in this
    // context - a local instance is enough for plain argument parsing.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LangChain4jController(AnthropicChatModel chatModel, OpenTelemetry openTelemetry) {
        this.chatModel = chatModel;
        this.tracer = openTelemetry.getTracer("otel-langchain4j-demo");
    }

    // -----------------------------------------------------------------------
    // Two tools, the second needs the result of the first. A generic agent loop instead of fixed
    // steps, because the model itself decides how many tool calls it needs.
    //
    // flagForReview/userComment: the end user can flag their own request as "please take a
    // look", independent of the automatic signals in tagHumanOversightSignals(). Deliberately a
    // separate, additional path rather than a replacement for automatic detection: users often
    // notice things (e.g. "this is definitely the wrong order") that purely text-based heuristics
    // can't capture, and conversely the heuristic catches anomalies a user without domain
    // knowledge wouldn't recognize as such.
    // -----------------------------------------------------------------------
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
                tagHumanOversightSignals(executedToolSequence, finalAnswer, false, flagForReview, userComment);
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
        // Too many tool calls is itself already an anomaly - this path gets flagged for review
        // too, not just the success case. fallbackTriggered=true is passed explicitly here rather
        // than derived from the tool sequence - at this point the loop knows for certain that
        // MAX_TOOL_STEPS was exceeded.
        tagHumanOversightSignals(executedToolSequence, fallbackAnswer, true, flagForReview, userComment);
        return fallbackAnswer;
    }

    // -----------------------------------------------------------------------
    // Human Oversight (Art. 14): five signals on the current span (Span.current() - here the
    // HTTP root span from Spring's auto-instrumentation, the same implicit context propagation
    // that executeLookupOrder/executeTrackingStatus rely on). All five are purely deterministic
    // or passed directly by the caller - no second LLM call, no "judge", see the constants
    // comment above for the rationale.
    //
    // 1. tool_sequence_anomaly: expected-vs-actual comparison against CANONICAL_TOOL_SEQUENCE.
    // 2. uncertainty_detected: substring search for UNCERTAINTY_MARKERS in the answer.
    // 3. status_keyword_missing: substring search for STATUS_KEYWORDS, negated (true = NONE of
    //    the expected status words were found in the answer).
    // 4. fallback_triggered: passed in from orderStatus(), true when MAX_TOOL_STEPS was reached
    //    before the model produced a final answer.
    // 5. user_flagged: set by the end user themselves via the "flagForReview" request parameter -
    //    independent of all four other signals.
    //
    // needsReview is the OR of all five - for Human Oversight, a false positive (reviewed
    // unnecessarily) is the much smaller problem than a false negative (missed), see the
    // constants comment above.
    // -----------------------------------------------------------------------
    private void tagHumanOversightSignals(
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

        boolean needsReview = sequenceAnomaly || uncertaintyDetected || statusKeywordMissing
                || fallbackTriggered || userFlagged;
        span.setAttribute(AttributeKey.booleanKey("human_oversight.needs_review"), needsReview);

        if (needsReview) {
            log.info(
                    "Trace flagged for human review (traceId={}, tool_sequence_anomaly={}, uncertainty_detected={}, " +
                    "status_keyword_missing={}, fallback_triggered={}, user_flagged={})",
                    span.getSpanContext().getTraceId(), sequenceAnomaly, uncertaintyDetected,
                    statusKeywordMissing, fallbackTriggered, userFlagged);
        }
    }

    // Deliberately a plain substring search (lowercase, no regex/NLP) - see the constants comment
    // above for the false-positive/false-negative trade-off.
    private static boolean containsAny(String text, List<String> needles) {
        String lower = text.toLowerCase(Locale.ENGLISH);
        return needles.stream().anyMatch(lower::contains);
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
