package io.mseemann.oteldemo;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * Human Oversight (Art. 14 EU AI Act): records a reviewer's decision as a span in the SAME
 * trace it reviews, via SpanContext.createFromRemoteParent(...) - so Jaeger correlates the
 * intervention with the original decision via trace ID, no separate audit log needed. See the
 * README for the full rationale.
 *
 * The reviewer name is SHA-256 hashed before being stored (data minimization).
 */
@RestController
@RequestMapping("/lc4j")
public class HumanOversightController {

    private static final Logger log = LoggerFactory.getLogger(HumanOversightController.class);
    private static final Set<String> VALID_DECISIONS = Set.of("approved", "overridden", "escalated");

    private final Tracer tracer;

    public HumanOversightController(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("otel-langchain4j-demo");
    }

    public record InterventionRequest(
            String traceId,
            String spanId,
            String reviewer,
            String decision,
            String comment) {
    }

    public record InterventionResponse(String traceId, String interventionSpanId, String reviewerHash) {
    }

    /**
     * Records a human intervention on an already existing trace.
     *
     * traceId/spanId come from the review queue (see otel-collector-config.yml, the
     * traces/review-queue pipeline) or directly from Jaeger - the reviewer copies them from the
     * trace shown there.
     *
     * Example:
     * curl -X POST http://localhost:8080/lc4j/human-intervention \
     *   -H "Content-Type: application/json" \
     *   -d '{"traceId":"<32-hex-chars>","spanId":"<16-hex-chars>",
     *        "reviewer":"jane.doe","decision":"overridden",
     *        "comment":"Tracking ID corrected manually, tool had processed a typo in the order format."}'
     */
    @PostMapping("/human-intervention")
    public ResponseEntity<?> recordIntervention(@RequestBody InterventionRequest body) {
        if (body.traceId() == null || body.traceId().length() != 32) {
            return ResponseEntity.badRequest().body(
                    "traceId must be the 32-character hex trace ID from Jaeger.");
        }
        if (body.spanId() == null || body.spanId().length() != 16) {
            return ResponseEntity.badRequest().body(
                    "spanId must be the 16-character hex span ID of the affected span from Jaeger.");
        }
        if (body.decision() == null || !VALID_DECISIONS.contains(body.decision())) {
            return ResponseEntity.badRequest().body("decision must be one of " + VALID_DECISIONS + ".");
        }
        if (body.reviewer() == null || body.reviewer().isBlank()) {
            return ResponseEntity.badRequest().body("reviewer must not be blank.");
        }

        SpanContext parentContext = SpanContext.createFromRemoteParent(
                body.traceId(),
                body.spanId(),
                TraceFlags.getSampled(),
                TraceState.getDefault());

        if (!parentContext.isValid()) {
            return ResponseEntity.badRequest().body("traceId/spanId is not a valid trace-context combination.");
        }

        Context context = Context.root().with(Span.wrap(parentContext));
        String reviewerHash = sha256(body.reviewer());

        Span interventionSpan = tracer.spanBuilder("human_intervention")
                .setParent(context)
                .setAttribute(AttributeKey.stringKey("human_oversight.reviewer_hash"), reviewerHash)
                .setAttribute(AttributeKey.stringKey("human_oversight.decision"), body.decision())
                // Goes through the same mask-pii pipeline as every span (otel-collector-config.yml).
                .setAttribute(AttributeKey.stringKey("human_oversight.comment"),
                        body.comment() == null ? "" : body.comment())
                .startSpan();
        interventionSpan.end();

        log.info("Human intervention recorded: traceId={}, decision={}, reviewerHash={}",
                body.traceId(), body.decision(), reviewerHash);

        return ResponseEntity.ok(new InterventionResponse(
                body.traceId(),
                interventionSpan.getSpanContext().getSpanId(),
                reviewerHash));
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // Practically unreachable (SHA-256 is on every standard JVM); fail loud rather than
            // ever writing the plain-text reviewer name to a span attribute.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
