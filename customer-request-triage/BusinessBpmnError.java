/**
 * Business-domain exception that maps to a BPMN error in Camunda.
 *
 * <p>Thrown from worker routes via the Camel {@code throwException} EIP:
 * <pre>
 * - throwException:
 *     exceptionType: BusinessBpmnError
 *     message: "ERROR_CODE: Human-readable message"
 * </pre>
 *
 * <p>Caught by the declarative {@code onException} handler which reads
 * {@code errorCode} and {@code message} to set Camunda headers.
 */
public class BusinessBpmnError extends RuntimeException {

    private final String errorCode;

    public BusinessBpmnError(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Single-arg constructor used by Camel's throwException EIP.
     * Expects the format {@code "ERROR_CODE: Human-readable message"}.
     */
    public BusinessBpmnError(String codeAndMessage) {
        this(
            codeAndMessage.contains(": ")
                ? codeAndMessage.substring(0, codeAndMessage.indexOf(": "))
                : codeAndMessage,
            codeAndMessage.contains(": ")
                ? codeAndMessage.substring(codeAndMessage.indexOf(": ") + 2)
                : codeAndMessage
        );
    }

    public String getErrorCode() {
        return errorCode;
    }
}
