#!/usr/bin/env bash
# -----------------------------------------------------------------------
# Human Oversight: end-to-end integration check
#
# What this test checks (and deliberately does NOT check):
#
#   A) Collector pipeline (otel-collector-config.yml, traces/review-queue):
#      - A span with human_oversight.needs_review=true ends up in
#        review-queue/review-queue.jsonl.
#      - A span with human_oversight.needs_review=false does NOT end up
#        there (counter-check on the OTTL negation in the
#        filter/needs-review processor - see the comment in
#        otel-collector-config.yml).
#      - human_oversight.comment is also masked on the span context
#        (transform/mask-pii, the "context: span" block).
#
#   B) HumanOversightController (a real, running Spring Boot process):
#      - POST /lc4j/human-intervention validates input correctly (400 on
#        an invalid decision).
#      - On valid input: the reviewerHash in the response is exactly
#        SHA-256(reviewer) - cross-checked independently with Python.
#
#   DELIBERATELY NOT checked: whether the resulting human_intervention
#   span also shows up in Jaeger. Reason: that span goes through the
#   normal "traces" pipeline, which has no tail-sampling in THIS repo
#   (unlike the source learning project this is extracted from) - so
#   that part isn't actually at risk here. What IS still probabilistic is
#   the live model call itself (step 3 in the README): this script
#   doesn't touch that path at all, on purpose - it only proves the
#   deterministic parts of the system (pipeline + controller) work,
#   independent of whatever a live Anthropic call happens to produce on
#   a given run.
#
# Requirements:
#   - docker compose up -d (Collector + Jaeger; review-queue/ must be
#     mounted, see docker-compose.yml)
#   - The Spring Boot app is running locally on port 8080
#     (mvn spring-boot:run)
# -----------------------------------------------------------------------
set -uo pipefail   # no -e: exit codes/HTTP codes are evaluated explicitly below

COLLECTOR_URL="http://localhost:4318/v1/traces"
APP_URL="http://localhost:8080"
REVIEW_QUEUE_FILE="./review-queue/review-queue.jsonl"
WAIT_SECONDS=5   # batch.timeout (1s) of the traces/review-queue pipeline + safety margin
                 # (no tail-sampling decision_wait needed - this pipeline has none)

ERRORS=0
fail() { echo "FAIL: $1"; ERRORS=$((ERRORS + 1)); }
ok()   { echo "OK:   $1"; }

# Random IDs for this test run
TRACE_FLAGGED=$(openssl rand -hex 16)
SPAN_FLAGGED=$(openssl rand -hex 8)
TRACE_CLEAN=$(openssl rand -hex 16)
SPAN_CLEAN=$(openssl rand -hex 8)
NOW_NS=$(date +%s%N 2>/dev/null || python3 -c "import time; print(int(time.time() * 1e9))")
END_NS=$((NOW_NS + 50000000))

echo "=== Part A: Collector pipeline (traces/review-queue) ==="
echo "  Flagged trace ID: $TRACE_FLAGGED"
echo "  Clean   trace ID: $TRACE_CLEAN"
echo ""

send_span() {
  local trace_id="$1" span_id="$2" needs_review="$3" anomaly="$4" comment_attr="$5"
  curl -s -o /dev/null -w "  HTTP %{http_code}\n" \
    -X POST "$COLLECTOR_URL" \
    -H "Content-Type: application/json" \
    -d "{
      \"resourceSpans\": [{
        \"resource\": {
          \"attributes\": [{
            \"key\": \"service.name\",
            \"value\": {\"stringValue\": \"human-oversight-test\"}
          }]
        },
        \"scopeSpans\": [{
          \"scope\": {\"name\": \"human-oversight-test\"},
          \"spans\": [{
            \"traceId\": \"$trace_id\",
            \"spanId\": \"$span_id\",
            \"name\": \"GET /lc4j/order-status\",
            \"kind\": 2,
            \"startTimeUnixNano\": \"$NOW_NS\",
            \"endTimeUnixNano\": \"$END_NS\",
            \"status\": {},
            \"attributes\": [
              {\"key\": \"human_oversight.needs_review\", \"value\": {\"boolValue\": $needs_review}},
              {\"key\": \"human_oversight.tool_sequence_anomaly\", \"value\": {\"boolValue\": $anomaly}}
              $comment_attr
            ]
          }]
        }]
      }]
    }"
}

echo "Sending flagged span (needs_review=true, tool_sequence_anomaly=true, comment with PII)..."
send_span "$TRACE_FLAGGED" "$SPAN_FLAGGED" "true" "true" \
  ',{"key": "human_oversight.comment", "value": {"stringValue": "Follow up at pii-test@example.com, please call +1 555 0100"}}'

echo "Sending clean span (needs_review=false)..."
send_span "$TRACE_CLEAN" "$SPAN_CLEAN" "false" "false" ""

echo ""
echo "Waiting ${WAIT_SECONDS}s (batch.timeout of the review-queue pipeline)..."
sleep "$WAIT_SECONDS"

if [ ! -f "$REVIEW_QUEUE_FILE" ]; then
  fail "Review queue file '$REVIEW_QUEUE_FILE' does not exist. Is the stack running? Is ./review-queue/ mounted as a volume (docker-compose.yml)?"
else
  RESULT=$(python3 - "$REVIEW_QUEUE_FILE" "$TRACE_FLAGGED" "$TRACE_CLEAN" <<'PYEOF'
import json, sys

path, trace_flagged, trace_clean = sys.argv[1], sys.argv[2], sys.argv[3]

seen_trace_ids = set()
comment_value = None

with open(path, "r", encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            batch = json.loads(line)
        except json.JSONDecodeError:
            continue  # possibly an incomplete last line due to rotation/flush
        for rs in batch.get("resourceSpans", []):
            for ss in rs.get("scopeSpans", []):
                for span in ss.get("spans", []):
                    trace_id = span.get("traceId", "")
                    seen_trace_ids.add(trace_id)
                    if trace_id == trace_flagged:
                        for attr in span.get("attributes", []):
                            if attr.get("key") == "human_oversight.comment":
                                comment_value = attr.get("value", {}).get("stringValue")

flagged_present = trace_flagged in seen_trace_ids
clean_present = trace_clean in seen_trace_ids

print(f"FLAGGED_PRESENT={flagged_present}")
print(f"CLEAN_PRESENT={clean_present}")
print(f"COMMENT_VALUE={comment_value}")
PYEOF
)
  echo "$RESULT"
  echo ""

  if echo "$RESULT" | grep -q "FLAGGED_PRESENT=True"; then
    ok "Flagged span (needs_review=true) is in the review queue."
  else
    fail "Flagged span is missing from the review queue - check filter/needs-review and the file/review-queue exporter."
  fi

  if echo "$RESULT" | grep -q "CLEAN_PRESENT=False"; then
    ok "Clean span (needs_review=false) is NOT in the review queue."
  else
    fail "Clean span is WRONGLY in the review queue - check the OTTL negation in filter/needs-review (should be '!= true', not '== true')."
  fi

  COMMENT_LINE=$(echo "$RESULT" | grep "COMMENT_VALUE=")
  if echo "$COMMENT_LINE" | grep -q "pii-test@example.com"; then
    fail "human_oversight.comment is NOT masked - the email is still visible in plain text: $COMMENT_LINE"
  elif echo "$COMMENT_LINE" | grep -q "\[email\]"; then
    ok "human_oversight.comment is masked (email -> [email])."
  else
    fail "Could not evaluate human_oversight.comment (attribute possibly not found on the expected span): $COMMENT_LINE"
  fi
fi

echo ""
echo "=== Part B: HumanOversightController (a real, running Spring Boot process) ==="
echo ""

if ! curl -s -o /dev/null --connect-timeout 3 "$APP_URL/lc4j/human-intervention"; then
  echo "FAIL: App not reachable at $APP_URL - skipping Part B."
  echo "  (The Spring Boot app must be running locally, e.g. 'mvn spring-boot:run')"
  ERRORS=$((ERRORS + 1))
else
  echo "Test B1: invalid decision -> expecting HTTP 400"
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$APP_URL/lc4j/human-intervention" \
    -H "Content-Type: application/json" \
    -d "{\"traceId\":\"$TRACE_FLAGGED\",\"spanId\":\"$SPAN_FLAGGED\",\"reviewer\":\"qa-test-script\",\"decision\":\"invalid-value\",\"comment\":\"\"}")
  if [ "$HTTP_CODE" = "400" ]; then
    ok "Invalid decision is rejected with HTTP 400."
  else
    fail "Invalid decision returned HTTP $HTTP_CODE instead of 400."
  fi

  echo ""
  echo "Test B2: valid intervention request -> expecting HTTP 200 + correct reviewerHash"
  REVIEWER="qa-test-script"
  EXPECTED_HASH=$(python3 -c "import hashlib; print(hashlib.sha256('$REVIEWER'.encode('utf-8')).hexdigest())")

  RESPONSE=$(curl -s -X POST "$APP_URL/lc4j/human-intervention" \
    -H "Content-Type: application/json" \
    -d "{\"traceId\":\"$TRACE_FLAGGED\",\"spanId\":\"$SPAN_FLAGGED\",\"reviewer\":\"$REVIEWER\",\"decision\":\"approved\",\"comment\":\"Logged automatically by the test script.\"}")

  echo "  Response: $RESPONSE"

  RETURNED_TRACE_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('traceId',''))" 2>/dev/null)
  RETURNED_HASH=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('reviewerHash',''))" 2>/dev/null)
  RETURNED_SPAN_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('interventionSpanId',''))" 2>/dev/null)

  if [ "$RETURNED_TRACE_ID" = "$TRACE_FLAGGED" ]; then
    ok "Response echoed back the correct traceId."
  else
    fail "Response traceId ('$RETURNED_TRACE_ID') does not match the sent traceId ('$TRACE_FLAGGED')."
  fi

  if [ "${#RETURNED_SPAN_ID}" = "16" ]; then
    ok "interventionSpanId has the expected length (16 hex characters)."
  else
    fail "interventionSpanId ('$RETURNED_SPAN_ID') does not have the expected length of 16 hex characters."
  fi

  if [ "$RETURNED_HASH" = "$EXPECTED_HASH" ]; then
    ok "reviewerHash matches SHA-256('$REVIEWER') - data minimization works correctly."
  else
    fail "reviewerHash ('$RETURNED_HASH') does not match the locally computed SHA-256 ('$EXPECTED_HASH')."
  fi
fi

echo ""
if [ "$ERRORS" -eq 0 ]; then
  echo "All human-oversight checks passed."
  exit 0
else
  echo "$ERRORS check(s) failed."
  exit 1
fi
