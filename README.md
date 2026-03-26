# Customer Request Triage — Apache Camel + Camunda 8 SaaS

A demo application that shows how **Apache Camel** acts as the
integration backbone for a **Camunda 8 SaaS** business process.

A customer request (free-text message) is submitted via a web form.
Camel starts a BPMN process instance, triages the message into a
structured classification (intent, urgency, entities), and the BPMN
gateway routes the request to the appropriate handler — all
implemented as Camel routes using only standard YAML DSL (no
Groovy/scripting).

When `OPENAI_API_KEY` is set, the triage uses `camel-openai` for
intelligent LLM-based classification. Without it, a simple
keyword-based fallback is used so the demo runs without any external
AI service.

## What this demonstrates

| Camel capability | How it is used |
|---|---|
| **REST DSL** | `POST /api/start` starts the process and `GET /api/config` exposes UI config |
| **Content-based routing** | `choice` + `contains` / `regex` for keyword classification; BPMN gateway routes on `intent` |
| **camel-openai** | Classifies unstructured customer text into structured JSON (intent, urgency, orderId) |
| **Declarative error handling** | `onException` maps `BusinessBpmnError` → BPMN error, generic `Exception` → job fail/retry |
| **throwException EIP** | Validates input and throws business errors — no scripting needed |
| **setBody + unmarshal** | Builds JSON output variables from headers, converts to Map for Camunda |
| **Bean integration** | `CamundaSaas.java` wraps the Camunda Java client as a `@BindToRegistry` bean |

## Architecture

```
┌──────────┐  GET /api/config    ┌─────────────────────────────────────┐
│  Browser │ ──────────────────►│  Apache Camel (REST DSL)            │
│  (UI)    │ ◄──────────────────│  returns Operate base URL           │
│          │                    │                                     │
│          │  POST /api/start   │  ┌──────────────────────────────┐  │
│          │ ──────────────────►│  │  Worker Routes               │  │
│          │ ◄──────────────────│  │  triage-request              │  │
│          │  processInstanceKey│  │   ├─ validate                │  │
└──────────┘                    │  │   ├─ openai or fallback      │  │
        │                       │  │   └─ completeJob             │  │
        │ Open Operate link     │  │  handle-cancel               │  │
        ▼                       │  │  handle-refund               │  │
┌───────────────────────────┐   │  │  handle-other                │  │
│ Camunda Operate           │   │  └──────────┬───────────────────┘  │
└───────────────────────────┘   │             │ completeJob /         │
                                │             │ throwError / failJob  │
                                │  ┌──────────▼──────┐              │
                                │  │  CamundaSaas    │  (bean)      │
                                │  │  Java client    │              │
                                └──┴─────────────────┴──────────────┘
                                          │
                                          ▼
                                 ┌─────────────────┐
                                 │ Camunda 8 SaaS  │
                                 │ (Zeebe engine)  │
                                 └─────────────────┘
```

## Prerequisites

- **Java 17+**
- **Camel JBang** — install with `jbang app install camel@apache/camel`
- A **Camunda 8 SaaS** cluster (credentials are in
  `application-dev.properties`)
- *(Optional)* For LLM-based triage, set these environment variables:
  - `OPENAI_API_KEY` — API key (OpenAI or compatible provider)
  - `OPENAI_BASE_URL` — e.g. `https://api.openai.com/v1` or a local
    Ollama URL like `http://localhost:11434/v1`
  - `OPENAI_MODEL` — model name, e.g. `gpt-5`

  Without these, the app uses keyword-based classification.

## Deploy the BPMN model

The BPMN process definition is in `customer-request-triage.bpmn`.
Deploy it **before** starting the Camel app:

1. Open [Camunda Web Modeler](https://modeler.cloud.camunda.io/) or
   Camunda Desktop Modeler.
2. Import `customer-request-triage.bpmn`.
3. Deploy to your cluster.

## Run the app

```bash
camel run --source-dir=customer-request-triage
```

The app starts on **http://localhost:8080**:

- `index.html` is served as a static page (via
  `camel.server.staticEnabled=true`)
- REST endpoints are available at `/api/start` and `/api/config`
- Camunda job workers are registered on startup

## Using the UI

1. Open **http://localhost:8080** in your browser.
2. Select a **Customer Tier** (Standard / Premium / Enterprise).
3. Enter a **Customer Message** describing the request, for example:
   - *"I'd like to cancel my order #ORD-12345."*
   - *"I want a refund for order #ORD-99999."*
   - *"Your service is terrible, I waited 3 weeks!"*
4. Click **Start Process**.
5. Copy the returned **process instance key** or use the generated
   **Operate** link to inspect the instance in Camunda Operate.

## Test scenarios

### Happy path — cancellation
- **Message:** *"Please cancel order #ORD-100"*
- **Expected:** Classified as `CANCEL_ORDER` → `handle-cancel`
  worker runs → cancellation confirmed.

### Happy path — refund with order ID (requires OpenAI)
- **Message:** *"I need a refund for order #ORD-200"*
- **Expected:** LLM extracts `orderId=ORD-200` → `handle-refund`
  completes successfully.

### Error path — refund without order ID
- **Message:** *"I want my money back!"*
- **Expected:** `intent=REFUND` but no `orderId` extracted →
  `BusinessBpmnError(MISSING_ORDER_ID)` → BPMN boundary error event
  on the refund task.

### Error path — empty message
- Submit with an **empty** Customer Message.
- **Expected:** `BusinessBpmnError(INVALID_REQUEST)` → BPMN boundary
  error event on the triage task.

### Fallback mode (no OpenAI)
- Run without setting `OPENAI_API_KEY`.
- **Expected:** Log says *"OpenAI not configured — using keyword-based
  classification"*. Messages containing "cancel" route to
  `handle-cancel`; "refund" or "money back" route to `handle-refund`;
  everything else routes to `handle-other`. Note: the keyword
  classifier does not extract `orderId`, so refund requests will
  trigger the `MISSING_ORDER_ID` error — demonstrating why the LLM
  classifier is valuable.

## Error handling

Two declarative layers are configured in `workers.camel.yaml` via
`routeConfiguration`:

| Exception | Camel action | Camunda outcome |
|---|---|---|
| `BusinessBpmnError` | Sets `CamundaErrorCode` / `CamundaErrorMessage`, calls `throwError` | BPMN boundary error event catches it; process follows the error path |
| Any other `Exception` | Sets `CamundaErrorMessage`, calls `failJob` | Job retries are decremented; after exhaustion Camunda creates an incident |

Validation errors use the standard `throwException` EIP — no scripting
needed:

```yaml
- choice:
    when:
      - simple: "${body[orderId]} == null or ${body[orderId]} == ''"
        steps:
          - throwException:
              exceptionType: BusinessBpmnError
              message: "MISSING_ORDER_ID: Cannot process refund without an order ID"
```

## Files

| File | Purpose |
|---|---|
| `CamundaSaas.java` | Bean wrapping the Camunda 8 Java client |
| `BusinessBpmnError.java` | Domain exception → BPMN error (supports `throwException` EIP) |
| `start-process.camel.yaml` | REST DSL, startup worker registration, process start, and UI config routes |
| `classify.camel.yaml` | Triage worker split into validation, classifier selection, OpenAI, fallback, and completion routes |
| `workers.camel.yaml` | Handler workers (`handle-cancel`, `handle-refund`, `handle-other`) + declarative error handling |
| `customer-request-triage.bpmn` | BPMN process model (deploy manually) |
| `index.html` | Web UI (form + process instance key + Operate link) |
| `application-dev.properties` | Configuration (Camunda + OpenAI) |
