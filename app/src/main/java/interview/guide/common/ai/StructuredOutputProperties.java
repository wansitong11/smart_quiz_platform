package interview.guide.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class StructuredOutputProperties {

    private int structuredMaxAttempts = 2;
    private boolean structuredIncludeLastError = true;
    private boolean structuredRetryUseRepairPrompt = true;
    private boolean structuredRetryAppendStrictJsonInstruction = true;
    private int structuredErrorMessageMaxLength = 200;
    private boolean structuredMetricsEnabled = true;
    private boolean structuredSchemaValidationEnabled = true;
    private int structuredRetryBackoffBaseMs = 1000;
    private int structuredRetryBackoffMaxMs = 5000;
    private int structuredRetryBackoffJitterMs = 500;
    private int structuredRateLimitBackoffBaseMs = 5000;
    private int structuredRateLimitBackoffMaxMs = 20000;
    private int structuredRateLimitBackoffJitterMs = 1000;
    private int structuredRateLimitMaxWaitMs = 30000;
}
