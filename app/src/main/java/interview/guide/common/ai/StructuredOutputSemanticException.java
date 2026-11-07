package interview.guide.common.ai;

/**
 * 结构化输出已成功解析，但业务语义不满足调用方要求。
 */
final class StructuredOutputSemanticException extends RuntimeException {

    private final String retryInstruction;

    StructuredOutputSemanticException(String retryInstruction) {
        super(retryInstruction);
        this.retryInstruction = retryInstruction;
    }

    String retryInstruction() {
        return retryInstruction;
    }
}
