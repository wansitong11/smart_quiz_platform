package interview.guide.infrastructure.export;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.PracticeAnswerEntity;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * PDF导出服务
 * PDF Export Service for paper extraction reports and practice reports
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfExportService {
    
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DeviceRgb HEADER_COLOR = new DeviceRgb(41, 128, 185);
    private static final DeviceRgb SECTION_COLOR = new DeviceRgb(52, 73, 94);
    
    private final ObjectMapper objectMapper;
    
    /**
     * 创建支持中文的字体
     */
    private PdfFont createChineseFont() {
        try (var fontStream = getClass().getClassLoader().getResourceAsStream("fonts/ZhuqueFangsong-Regular.ttf")) {
            if (fontStream != null) {
                byte[] fontBytes = fontStream.readAllBytes();
                log.debug("使用项目内嵌字体: fonts/ZhuqueFangsong-Regular.ttf");
                return PdfFontFactory.createFont(fontBytes, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED);
            }

            log.error("未找到字体文件: fonts/ZhuqueFangsong-Regular.ttf");
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "字体文件缺失，请联系管理员");
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("创建中文字体失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "创建字体失败: " + e.getMessage());
        }
    }
    
    /**
     * 清理文本中可能导致字体问题的字符
     */
    private String sanitizeText(String text) {
        if (text == null) return "";
        // 移除可能导致问题的特殊字符（如 emoji）
        return text.replaceAll("[\\p{So}\\p{Cs}]", "").trim();
    }
    
    /**
     * 导出试卷解析报告为 PDF
     */
    public byte[] exportPaperAnalysis(PracticePaperEntity paper, ResumeDetailDTO.AnalysisHistoryDTO analysis) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);
        
        // 使用支持中文的字体
        PdfFont font = createChineseFont();
        document.setFont(font);
        
        // 标题
        Paragraph title = new Paragraph("试卷解析报告")
            .setFontSize(24)
            .setBold()
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(HEADER_COLOR);
        document.add(title);
        
        // 基本信息
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle("基本信息"));
        document.add(new Paragraph("文件名: " + paper.getOriginalFilename()));
        document.add(new Paragraph("上传时间: " + 
            (paper.getUploadedAt() != null ? DATE_FORMAT.format(paper.getUploadedAt()) : "未知")));
        
        // 题量与卷面总分
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle("试卷概览"));
        Paragraph scoreP = new Paragraph("题目数量: " + valueOrZero(analysis.questionCount())
            + " 道    卷面总分: " + valueOrZero(analysis.totalScore()) + " 分")
            .setFontSize(18)
            .setBold()
            .setFontColor(HEADER_COLOR);
        document.add(scoreP);
        
        // 知识板块分布
        if (analysis.topics() != null && !analysis.topics().isEmpty()) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("知识板块分布"));
            Table scoreTable = new Table(UnitValue.createPercentArray(new float[]{2, 1}))
                .useAllAvailableWidth();
            for (String topic : analysis.topics()) {
                String[] parts = topic.split("\\|");
                scoreTable.addCell(new Cell().add(new Paragraph(sanitizeText(parts[0]))));
                scoreTable.addCell(new Cell().add(new Paragraph(
                    parts.length > 2 ? sanitizeText(parts[1] + " / " + parts[2]) : "")));
            }
            document.add(scoreTable);
        }
        
        // 解析摘要
        if (analysis.summary() != null) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("解析摘要"));
            document.add(new Paragraph(sanitizeText(analysis.summary())));
        }
        
        // 解析告警
        if (analysis.warnings() != null && !analysis.warnings().isEmpty()) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("解析告警"));
            for (String warning : analysis.warnings()) {
                document.add(new Paragraph("• " + sanitizeText(warning)));
            }
        }
        
        document.close();
        return baos.toByteArray();
    }

    private int valueOrZero(Integer value) {
        return value != null ? value : 0;
    }
    
    /**
     * 导出面试报告为PDF
     */
    public byte[] exportInterviewReport(PracticeSessionEntity session) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);
        
        // 使用支持中文的字体
        PdfFont font = createChineseFont();
        document.setFont(font);
        
        // 标题
        Paragraph title = new Paragraph("模拟面试报告")
            .setFontSize(24)
            .setBold()
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(HEADER_COLOR);
        document.add(title);
        
        // 基本信息
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle("面试信息"));
        document.add(new Paragraph("会话ID: " + session.getSessionId()));
        document.add(new Paragraph("题目数量: " + session.getTotalQuestions()));
        document.add(new Paragraph("面试状态: " + getStatusText(session.getStatus())));
        document.add(new Paragraph("开始时间: " + 
            (session.getCreatedAt() != null ? DATE_FORMAT.format(session.getCreatedAt()) : "未知")));
        if (session.getCompletedAt() != null) {
            document.add(new Paragraph("完成时间: " + DATE_FORMAT.format(session.getCompletedAt())));
        }
        
        // 总分
        if (session.getOverallScore() != null) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("综合评分"));
            Paragraph scoreP = new Paragraph("总分: " + session.getOverallScore() + " / 100")
                .setFontSize(18)
                .setBold()
                .setFontColor(getScoreColor(session.getOverallScore()));
            document.add(scoreP);
        }
        
        // 总体评价
        if (session.getOverallFeedback() != null) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("总体评价"));
            document.add(new Paragraph(sanitizeText(session.getOverallFeedback())));
        }
        
        // 优势
        if (session.getStrengthsJson() != null) {
            try {
                List<String> strengths = objectMapper.readValue(session.getStrengthsJson(),
                        new TypeReference<>() {
                        });
                if (!strengths.isEmpty()) {
                    document.add(new Paragraph("\n"));
                    document.add(createSectionTitle("表现优势"));
                    for (String s : strengths) {
                        document.add(new Paragraph("• " + sanitizeText(s)));
                    }
                }
            } catch (Exception e) {
                log.error("解析优势JSON失败: sessionId={}", session.getSessionId(), e);
            }
        }
        
        // 改进建议
        if (session.getImprovementsJson() != null) {
            try {
                List<String> improvements = objectMapper.readValue(session.getImprovementsJson(),
                        new TypeReference<>() {
                        });
                if (!improvements.isEmpty()) {
                    document.add(new Paragraph("\n"));
                    document.add(createSectionTitle("改进建议"));
                    for (String s : improvements) {
                        document.add(new Paragraph("• " + sanitizeText(s)));
                    }
                }
            } catch (Exception e) {
                log.error("解析改进建议JSON失败: sessionId={}", session.getSessionId(), e);
            }
        }
        
        // 问答详情
        List<PracticeAnswerEntity> answers = session.getAnswers();
        if (answers != null && !answers.isEmpty()) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("问答详情"));

            PaperTree paperTree = parsePaperTree(session.getPaperJson());
            String currentSectionPath = null;
            java.util.Set<String> printedMaterials = new java.util.HashSet<>();

            for (PracticeAnswerEntity answer : answers) {
                int index = answer.getQuestionIndex();
                if (paperTree != null) {
                    String sectionPath = paperTree.sectionPathOf(index);
                    if (!sectionPath.isBlank() && !sectionPath.equals(currentSectionPath)) {
                        currentSectionPath = sectionPath;
                        document.add(new Paragraph("\n" + sanitizeText(sectionPath))
                            .setBold()
                            .setFontSize(13)
                            .setFontColor(SECTION_COLOR));
                    }
                    for (PaperNode material : paperTree.materialsOf(index)) {
                        if (!printedMaterials.add(material.id())) {
                            continue;
                        }
                        if (material.title() != null && !material.title().isBlank()) {
                            document.add(new Paragraph(sanitizeText(material.title())).setBold().setFontSize(10));
                        }
                        document.add(new Paragraph(sanitizeText(material.content())).setFontSize(9));
                    }
                }
                document.add(new Paragraph("\n"));
                document.add(new Paragraph("问题 " + (index + 1) +
                    " [" + (answer.getCategory() != null ? answer.getCategory() : "综合") + "]")
                    .setBold()
                    .setFontSize(12));
                document.add(new Paragraph("Q: " + sanitizeText(answer.getQuestion())));
                document.add(new Paragraph("A: " + sanitizeText(answer.getUserAnswer() != null ? answer.getUserAnswer() : "未回答")));
                document.add(new Paragraph("得分: " + answer.getScore() + "/100")
                    .setFontColor(getScoreColor(answer.getScore())));
                if (answer.getFeedback() != null) {
                    document.add(new Paragraph("评价: " + sanitizeText(answer.getFeedback()))
                        .setItalic());
                }
                if (answer.getReferenceAnswer() != null) {
                    document.add(new Paragraph("参考答案: " + sanitizeText(answer.getReferenceAnswer()))
                        .setFontColor(new DeviceRgb(39, 174, 96)));
                }
            }
        }
        
        document.close();
        return baos.toByteArray();
    }

    /**
     * 读取会话的试卷层级树；结构缺失或非法时返回 null，退回平铺导出。
     */
    private PaperTree parsePaperTree(String paperJson) {
        if (paperJson == null || paperJson.isBlank()) {
            return null;
        }
        try {
            return PaperTree.parse(objectMapper, paperJson);
        } catch (Exception e) {
            log.warn("试卷结构解析失败，PDF 按平铺导出: {}", e.getMessage());
            return null;
        }
    }
    
    private Paragraph createSectionTitle(String title) {
        return new Paragraph(title)
            .setFontSize(14)
            .setBold()
            .setFontColor(SECTION_COLOR)
            .setMarginTop(10);
    }
    
    private void addScoreRow(Table table, String dimension, int score, int maxScore) {
        table.addCell(new Cell().add(new Paragraph(dimension)));
        table.addCell(new Cell().add(new Paragraph(score + " / " + maxScore)
            .setFontColor(getScoreColor(score * 100 / maxScore))));
    }
    
    private DeviceRgb getScoreColor(int score) {
        if (score >= 80) return new DeviceRgb(39, 174, 96);   // 绿色
        if (score >= 60) return new DeviceRgb(241, 196, 15);  // 黄色
        return new DeviceRgb(231, 76, 60);                    // 红色
    }
    
    private String getStatusText(PracticeSessionEntity.SessionStatus status) {
        return switch (status) {
            case CREATED -> "已创建";
            case IN_PROGRESS -> "进行中";
            case COMPLETED -> "已完成";
            case EVALUATED -> "已评估";
        };
    }
}
