/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
