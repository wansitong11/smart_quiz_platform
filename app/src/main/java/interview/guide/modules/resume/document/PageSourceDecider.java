package interview.guide.modules.resume.document;

import interview.guide.modules.resume.model.PageSource;

import java.util.regex.Pattern;

/**
 * 页源判定：文字层够不够可信，不足以承载整页内容时改走视觉识别。
 */
public final class PageSourceDecider {

  private static final Pattern QUESTION_NO = Pattern.compile("^\\s*\\d{1,3}\\s*[.．、)）]\\s*");

  private PageSourceDecider() {
  }

  public static PageSource decide(String text, double imageCoverage) {
    if (text == null || text.isBlank()) {
      return PageSource.IMAGE;
    }
    int effective = 0;
    int questionLines = 0;
    for (String line : text.split("\n")) {
      int lineChars = 0;
      for (int i = 0; i < line.length(); i++) {
        char ch = line.charAt(i);
        if (Character.isLetterOrDigit(ch)) {
          lineChars++;
        }
      }
      effective += lineChars;
      if (QUESTION_NO.matcher(line).find()) {
        questionLines++;
      }
    }
    // 大图 + 薄文字层：扫描件常见的"标题/页码文字层"
    if (imageCoverage > 0.5 && effective < 150) {
      return PageSource.IMAGE;
    }
    if (effective < 120 && questionLines == 0) {
      return PageSource.IMAGE;
    }
    return PageSource.TEXT;
  }
}
