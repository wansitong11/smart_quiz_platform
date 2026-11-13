package interview.guide.modules.knowledgebase.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SectionChunker {
  private static final Encoding ENCODING = Encodings.newLazyEncodingRegistry()
      .getEncoding(EncodingType.CL100K_BASE);
  private static final String BOUNDARIES = "\n。！？；.!?;";

  public List<String> split(String body, int targetTokens, int maxChunks) {
    if (targetTokens <= 0 || maxChunks <= 0) {
      throw new IllegalArgumentException("分块配置必须大于零");
    }
    if (body == null || body.isEmpty()) {
      return List.of();
    }
    List<String> chunks = new ArrayList<>();
    int start = 0;
    while (start < body.length()) {
      int cursor = start;
      int end = start;
      while (cursor < body.length()) {
        int next = cursor + Character.charCount(body.codePointAt(cursor));
        if (ENCODING.countTokensOrdinary(body.substring(start, next)) > targetTokens) {
          if (end == start) {
            end = next;
          }
          break;
        }
        end = next;
        cursor = next;
      }
      int boundary = -1;
      int length = body.codePointCount(start, end);
      int position = start;
      int count = 0;
      while (position < end) {
        int codePoint = body.codePointAt(position);
        position += Character.charCount(codePoint);
        count++;
        if (BOUNDARIES.indexOf(codePoint) >= 0 && count * 2 >= length) {
          boundary = position;
        }
      }
      if (boundary > start) {
        end = boundary;
      }
      chunks.add(body.substring(start, end));
      if (chunks.size() > maxChunks) {
        throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
            "资料分块数量超过上限，请拆分资料后重试");
      }
      start = end;
    }
    return List.copyOf(chunks);
  }
}
