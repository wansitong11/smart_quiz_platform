package interview.guide.modules.resume.service;

/**
 * 页资源代次键的稳定格式。
 */
public final class PaperAssetKey {

  public static final int VERSION_TOKEN_LENGTH = 12;
  public static final String INITIAL_ATTEMPT = "initial";

  private PaperAssetKey() {
  }

  public static String attemptToken(String sourceAttemptId) {
    if (sourceAttemptId == null || sourceAttemptId.isBlank()
        || INITIAL_ATTEMPT.equals(sourceAttemptId)) {
      return INITIAL_ATTEMPT;
    }
    String compact = sourceAttemptId.replace("-", "");
    return compact.length() <= VERSION_TOKEN_LENGTH
        ? compact
        : compact.substring(0, VERSION_TOKEN_LENGTH);
  }

  public static String versioned(String baseKey, String sourceAttemptId) {
    return baseKey + "@" + attemptToken(sourceAttemptId);
  }

  public static String optionTile(String versionedOptionRowKey, String label) {
    return versionedOptionRowKey + ":" + label;
  }
}
