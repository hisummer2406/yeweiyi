package com.sansen.yeweiyi.logic.thingsboard;

/**
 * 一次 Provisioning 的结果。REJECTED 是云端明确拒绝（编号未登记/已注册过），重试也不会变；
 * TIMEOUT/NETWORK_ERROR/MALFORMED 是临时问题，调用方按退避重试。
 */
public final class ProvisionResult {

    public enum Outcome {
        SUCCESS, REJECTED, MALFORMED, TIMEOUT, NETWORK_ERROR
    }

    private final Outcome outcome;
    private final String accessToken;
    private final String errorMessage;

    private ProvisionResult(Outcome outcome, String accessToken, String errorMessage) {
        this.outcome = outcome;
        this.accessToken = accessToken;
        this.errorMessage = errorMessage;
    }

    public static ProvisionResult success(String accessToken) {
        return new ProvisionResult(Outcome.SUCCESS, accessToken, null);
    }

    public static ProvisionResult failure(Outcome outcome, String errorMessage) {
        if (outcome == Outcome.SUCCESS) {
            throw new IllegalArgumentException("失败结果不能是 SUCCESS");
        }
        return new ProvisionResult(outcome, null, errorMessage);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    /** 仅 SUCCESS 时非空。 */
    public String getAccessToken() {
        return accessToken;
    }

    /** 云端返回的 errorMsg 或本地失败原因，可能为 null。 */
    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        // 不打印 token 明文，避免凭据进日志。
        return "ProvisionResult{outcome=" + outcome + ", accessToken=" +
                (accessToken == null ? "null" : "[REDACTED]") + ", errorMessage='" + errorMessage + "'}";
    }
}
