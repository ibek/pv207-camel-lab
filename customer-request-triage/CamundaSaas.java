import java.io.Closeable;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.camel.BindToRegistry;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.PropertyInject;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.DeploymentEvent;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.worker.JobWorker;

/**
 * Camel bean that wraps the Camunda 8 SaaS Java Client, replacing the zeebe
 * component with direct API calls.
 *
 * <p><b>Job worker pattern:</b> call {@link #registerWorker(String)} to
 * subscribe to a job type.  Activated jobs are forwarded to
 * {@code seda:worker-<jobType>} with the variables as body and job metadata
 * in {@code Camunda*} headers.  Complete/fail the job from the consuming
 * route via {@link #completeJob(Exchange)} or {@link #failJob(Exchange)}.
 *
 * <p><b>Run with Camel JBang:</b>
 * <pre>
 * camel run \
 *   --deps=io.camunda:camunda-client-java:8.8.4 \
 *   custom.camel.yaml CamundaSaas.java
 * </pre>
 */
@BindToRegistry("camunda-saas")
public class CamundaSaas implements CamelContextAware, Closeable {

    @PropertyInject("camunda.cluster-id")
    private String clusterId;

    @PropertyInject("camunda.client-id")
    private String clientId;

    @PropertyInject("camunda.client-secret")
    private String clientSecret;

    @PropertyInject(value = "camunda.region", defaultValue = "bru-2")
    private String region;

    private CamundaClient client;
    private CamelContext camelContext;
    private ProducerTemplate producerTemplate;
    private final List<JobWorker> workers = new CopyOnWriteArrayList<>();

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    @Override
    public CamelContext getCamelContext() {
        return camelContext;
    }

    public synchronized CamundaClient getClient() {
        if (client == null) {
            client = CamundaClient.newCloudClientBuilder()
                    .withClusterId(clusterId)
                    .withClientId(clientId)
                    .withClientSecret(clientSecret)
                    .withRegion(region)
                    .build();
        }
        return client;
    }

    private ProducerTemplate getProducerTemplate() {
        if (producerTemplate == null) {
            producerTemplate = camelContext.createProducerTemplate();
        }
        return producerTemplate;
    }

    // ─── Job Worker ──────────────────────────────────────────────

    /**
     * Register a job worker.  Activated jobs are forwarded to
     * {@code seda:worker-<jobType>} with variables as body and metadata headers.
     */
    public void registerWorker(String jobType) {
        registerWorker(jobType, "seda:worker-" + jobType);
    }

    /**
     * Register a job worker that forwards activated jobs to a custom endpoint.
     */
    public void registerWorker(String jobType, String endpoint) {
        JobWorker worker = getClient().newWorker()
                .jobType(jobType)
                .handler((jobClient, job) -> {
                    Map<String, Object> headers = new HashMap<>();
                    headers.put("CamundaJobKey", job.getKey());
                    headers.put("CamundaBpmnProcessId", job.getBpmnProcessId());
                    headers.put("CamundaProcessInstanceKey", job.getProcessInstanceKey());
                    headers.put("CamundaElementId", job.getElementId());
                    headers.put("CamundaElementInstanceKey", job.getElementInstanceKey());
                    headers.put("CamundaRetries", job.getRetries());
                    headers.put("CamundaDeadline", job.getDeadline());
                    headers.put("CamundaType", job.getType());

                    getProducerTemplate().sendBodyAndHeaders(
                            endpoint, job.getVariablesAsMap(), headers);
                })
                .open();

        workers.add(worker);
    }

    // ─── Complete / Fail / Error ─────────────────────────────────

    /**
     * Complete the current job.  Reads {@code CamundaJobKey} from exchange
     * header.  If the body is a {@link Map} it is sent back as output
     * variables.
     */
    @SuppressWarnings("unchecked")
    public void completeJob(Exchange exchange) {
        long jobKey = requireHeader(exchange, "CamundaJobKey", Long.class);
        Object body = exchange.getMessage().getBody();

        var cmd = getClient().newCompleteCommand(jobKey);
        if (body instanceof Map) {
            cmd.variables((Map<String, Object>) body);
        }
        cmd.send().join();
    }

    /**
     * Fail the current job.  Reads {@code CamundaJobKey} and
     * {@code CamundaRetries} from headers.  Retries are decremented by 1
     * (minimum 0).  An optional {@code CamundaErrorMessage} header provides
     * the error message.
     */
    public void failJob(Exchange exchange) {
        long jobKey = requireHeader(exchange, "CamundaJobKey", Long.class);
        int retries = exchange.getMessage().getHeader("CamundaRetries", 0, Integer.class);
        String errorMessage = exchange.getMessage().getHeader(
                "CamundaErrorMessage", "Job failed", String.class);

        getClient().newFailCommand(jobKey)
                .retries(Math.max(retries - 1, 0))
                .errorMessage(errorMessage)
                .send()
                .join();
    }

    /**
     * Throw a BPMN error on the current job.  Reads {@code CamundaJobKey},
     * {@code CamundaErrorCode}, and optionally {@code CamundaErrorMessage}
     * from headers.
     */
    public void throwError(Exchange exchange) {
        long jobKey = requireHeader(exchange, "CamundaJobKey", Long.class);
        String errorCode = requireHeader(exchange, "CamundaErrorCode", String.class);
        String errorMessage = exchange.getMessage().getHeader(
                "CamundaErrorMessage", "", String.class);

        getClient().newThrowErrorCommand(jobKey)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .send()
                .join();
    }

    // ─── Process Instance ────────────────────────────────────────

    /**
     * Start a new process instance.  Accepts a body that is either:
     * <ul>
     *   <li>A {@link Map} with keys {@code bpmnProcessId} (or
     *       {@code process_id}) and optional {@code variables} map.</li>
     *   <li>A JSON string with the same structure.</li>
     * </ul>
     * Falls back to the {@code CamundaBpmnProcessId} header.
     *
     * @return a Map with {@code processInstanceKey}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> startProcess(Exchange exchange) {
        Object body = exchange.getMessage().getBody();
        Map<String, Object> bodyMap = toMap(exchange, body);

        String bpmnProcessId = null;
        Map<String, Object> variables = Collections.emptyMap();

        if (bodyMap != null) {
            Object id = bodyMap.getOrDefault("bpmnProcessId", bodyMap.get("process_id"));
            if (id != null) {
                bpmnProcessId = id.toString();
            }
            Object vars = bodyMap.get("variables");
            if (vars instanceof Map) {
                variables = (Map<String, Object>) vars;
            }
        }

        if (bpmnProcessId == null || bpmnProcessId.isBlank()) {
            bpmnProcessId = exchange.getMessage().getHeader(
                    "CamundaBpmnProcessId", String.class);
        }

        if (bpmnProcessId == null || bpmnProcessId.isBlank()) {
            throw new IllegalArgumentException(
                    "bpmnProcessId required: set body.bpmnProcessId, "
                    + "body.process_id, or CamundaBpmnProcessId header");
        }

        // If route provides the process id header and body is already a variables map,
        // use the body directly as process variables.
        if (variables.isEmpty() && bodyMap != null && bodyMap.get("variables") == null) {
            variables = bodyMap;
        }

        ProcessInstanceEvent event = getClient().newCreateInstanceCommand()
                .bpmnProcessId(bpmnProcessId)
                .latestVersion()
                .variables(variables)
                .send()
                .join();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processInstanceKey", event.getProcessInstanceKey());
        return result;
    }

    // ─── Message ─────────────────────────────────────────────────

    /**
     * Publish a BPMN message.  Reads {@code CamundaMessageName} and
     * {@code CamundaCorrelationKey} from headers.  If the body is a
     * {@link Map}, it is sent as message variables.
     */
    @SuppressWarnings("unchecked")
    public void publishMessage(Exchange exchange) {
        String messageName = requireHeader(exchange, "CamundaMessageName", String.class);
        String correlationKey = exchange.getMessage().getHeader(
                "CamundaCorrelationKey", "", String.class);
        Object body = exchange.getMessage().getBody();

        var cmd = getClient().newPublishMessageCommand()
                .messageName(messageName)
                .correlationKey(correlationKey);
        if (body instanceof Map) {
            cmd.variables((Map<String, Object>) body);
        }
        cmd.send().join();
    }

    // ─── Lifecycle ───────────────────────────────────────────────

    @Override
    public synchronized void close() {
        for (JobWorker worker : workers) {
            if (!worker.isClosed()) {
                worker.close();
            }
        }
        workers.clear();
        if (producerTemplate != null) {
            try { producerTemplate.close(); } catch (Exception ignored) { }
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private <T> T requireHeader(Exchange exchange, String name, Class<T> type) {
        T value = exchange.getMessage().getHeader(name, type);
        if (value == null) {
            throw new IllegalArgumentException("Header '" + name + "' is required");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Exchange exchange, Object body) {
        if (body instanceof Map) {
            return (Map<String, Object>) body;
        }
        return exchange.getContext().getTypeConverter()
                .tryConvertTo(Map.class, exchange, body);
    }
}
