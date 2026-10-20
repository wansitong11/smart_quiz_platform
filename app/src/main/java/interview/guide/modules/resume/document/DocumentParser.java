package interview.guide.modules.resume.document;

/**
 * 把上传文件解析成"页 + 文本行 + 资源"。
 */
public interface DocumentParser {

  boolean supports(String contentType, String filename);

  ParsedDocument parse(byte[] bytes, String filename, String contentType);
}
