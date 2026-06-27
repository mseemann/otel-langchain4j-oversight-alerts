# Six Signals, No Guardrail

*(Working title — companion article in the "AI Observability & Compliance for Enterprise Java" series, not yet published. Link added here once it's live.)*

Companion repo for the article that asks what Human Oversight (Art. 14 EU AI Act) looks like when it's added as pure *observability* — six signals layered on top of an agentic tool chain — with no guardrail anywhere near the customer-facing answer.

Builds on [otel-langchain4j-toolchain-costs](https://github.com/mseemann/otel-langchain4j-toolchain-costs): same `/order-status` endpoint, same two-tool chain (`lookup_order` → `get_tracking_status`, the second fed by the first's output). What's new here is `tagHumanOversightSignals()` — six span attributes that tell a reviewer *whether* something looks wrong with a given trace: an unexpected tool order, hedging language in the answer, missing delivery-status vocabulary, a fallback after too many tool calls, the customer flagging their own request, or a second model call scoring the first answer's confidence. Five of the six are plain deterministic checks — fixed word lists, a fixed expected sequence, no model call involved. The sixth, `judgeConfidence()`, is exactly the thing the other five avoid: a second LLM call judging the first one's answer. It's included anyway, as an additional signal running in parallel to the deterministic ones, not a replacement for them — see the article for why both are worth having side by side.

What none of that changes: the success-path answer returned to the customer is `response.aiMessage().text()` — the model's free text, verbatim, unfiltered. There is no Java template standing between the model and the customer, no output guard, no input sanitization on a customer note. The signals make a bad answer *visible after the fact*. They do nothing to stop it from reaching the customer first. That gap — and what it would take to close it — is the next repo in this series, [otel-langchain4j-human-oversight](https://github.com/mseemann/otel-langchain4j-human-oversight), which replaces this exact endpoint with a forced three-way router.

## Prerequisites

- Java 21
- Maven
- Docker + Docker Compose
- An [Anthropic API key](https://console.anthropic.com/) — the demo calls the real Claude Haiku model, twice per successful request now (the answer, then the judge scoring it); cost per test run is still a few cents

## Getting started

### 1. Start the observability stack

```bash
docker compose up -d
```

Brings up the OTel Collector, Jaeger, Prometheus, and Alertmanager.

### 2. Start the Spring Boot app

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run
```

### 3. Call the endpoint

```bash
# Plain lookup — the baseline case
curl "http://localhost:8080/lc4j/order-status?orderNumber=ORD-4711"

# The one signal you can force deterministically: the customer flags their own request
curl "http://localhost:8080/lc4j/order-status?orderNumber=ORD-4711&flagForReview=true" \
  --data-urlencode "userComment=This is definitely the wrong delivery address" -G
```

The other five signals — `tool_sequence_anomaly`, `uncertainty_detected`, `status_keyword_missing`, `fallback_triggered`, `judge_low_confidence`/`judge_failed` — aren't reachable through a request parameter. They depend on what the model actually does and writes (and, for the judge, on what a second model call makes of it), which is exactly the point of this repo: nothing here constrains the model's behavior, so nothing here can *guarantee* triggering them either. Calling the endpoint repeatedly with unusual `orderNumber` values (e.g. a non-numeric one) and watching the traces in Jaeger is the honest way to explore them — some calls will look completely unremarkable, some won't.

### 4. Prove the pipeline and alerts fire, without depending on the model

```bash
./test-human-oversight.sh
```

Injects synthetic OTLP spans directly into the collector with `human_oversight.needs_review`/`human_oversight.tool_sequence_anomaly` already set, and exercises `/lc4j/human-intervention` against the running app. This checks the part of the system that's deterministic by construction — the masking transform, the review-queue filter, the controller's validation and hashing — independent of whatever a live model call happens to produce on a given run.

### 5. Submit a human-intervention decision

Copy `traceId`/`spanId` from a flagged trace in Jaeger or from `review-queue/review-queue.jsonl`, then:

```bash
curl -X POST http://localhost:8080/lc4j/human-intervention \
  -H "Content-Type: application/json" \
  -d '{"traceId":"<32-hex-chars>","spanId":"<16-hex-chars>",
       "reviewer":"jane.doe","decision":"overridden",
       "comment":"Re-ran the lookup manually, tool sequence in the trace looked wrong."}'
```

### 6. Open the UIs

- Jaeger: http://localhost:16686 → service `otel-langchain4j-demo` → Find Traces
- Prometheus: http://localhost:9090 → Alerts
- Alertmanager: http://localhost:9093

## What you'll see in Jaeger

Each call to `/order-status` produces a `chat` span per planning/final step plus one `execute_tool` span per tool call (`lookup_order`, `get_tracking_status`) — anywhere from three spans (clean run) up to the `MAX_TOOL_STEPS` ceiling if the model loops, plus one more `chat` span for the judge call on the success path. The root span carries the full `human_oversight.*` attribute set: `tool_sequence_actual`, `tool_sequence_anomaly`, `uncertainty_detected`, `status_keyword_missing`, `fallback_triggered`, `user_flagged`, `user_comment`, `judge_confidence_score`, `judge_low_confidence`, `judge_failed`, `needs_review`.

A `human_intervention` span recorded via step 5 attaches to the *same* trace it reviews — same trace ID, no separate audit log to cross-reference.

## What you'll see in Prometheus & Alertmanager

Two alerts, both in the `human-oversight` group (`prometheus-rules.yml`), both sourced from the spanmetrics connector in `otel-collector-config.yml`:

| Alert | Fires on | Severity | `for:` |
|---|---|---|---|
| `UnexpectedToolSequenceDetected` | `tool_sequence_anomaly=true` | warning | 0m |
| `HumanReviewQueueGrowing` | sustained `needs_review=true` inflow | warning | 30m |

`UnexpectedToolSequenceDetected` fires with no delay — a deterministic sequence mismatch is already the relevant event, not noise to confirm over a window. `HumanReviewQueueGrowing` measures sustained *inflow*, not a provably growing *backlog depth* — there's no second counter for completed reviews to subtract against (see the comment in `prometheus-rules.yml`).

Alertmanager routes `domain: human-oversight` into a faster-grouped sub-route (`alertmanager.yml`) — locally everything still lands in the same logging-only receiver, but the routing itself mirrors how a real deployment would split a compliance signal from a general ops channel.

## Project structure

- `LangChain4jController.java` — `/order-status`: the agentic tool-call loop and `tagHumanOversightSignals()`
- `HumanOversightController.java` — `/human-intervention`: records a reviewer decision as a span on the original trace, via `SpanContext.createFromRemoteParent(...)`
- `otel-collector-config.yml` — PII-masking transform, the `traces/review-queue` pipeline (file-based review queue), and the spanmetrics dimensions the alerts query
- `prometheus-rules.yml` / `alertmanager.yml` — the `human-oversight` alert group and its routing
- `test-human-oversight.sh` — deterministic verification of the collector pipeline and the controller, independent of live model output
- `review-queue/` — bind-mounted target for the `file/review-queue` exporter; `review-queue.jsonl` appears here once a trace gets flagged

## More context

The background on why LangChain4j needs manual instrumentation on Spring Boot at all is covered in [otel-langchain4j-springboot](https://github.com/mseemann/otel-langchain4j-springboot) and its article. This repo's own article covers what six observability signals — five deterministic, one a second model call judging the first — can and cannot do for Art. 14 compliance when the thing they're observing — a free-text answer formulated by the model — is itself never constrained.

## License

MIT – use the code however you like.

---

**Author:** Michael Seemann · [GitHub](https://github.com/mseemann) · [Medium](https://medium.com/@mseemann.io) · [LinkedIn](https://www.linkedin.com/in/michael-seemann-1478563bb/)
