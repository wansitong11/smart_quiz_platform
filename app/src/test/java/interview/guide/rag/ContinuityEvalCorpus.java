package interview.guide.rag;

import java.util.ArrayList;
import java.util.List;

final class ContinuityEvalCorpus {
  record Rule(String subject, String condition, String requirement) { }

  static final List<Rule> RULES = List.of(
      new Rule("易碎玻璃杯", "外箱破损", "补充外箱照片"),
      new Rule("冷藏酸奶", "到货温度超过八摄氏度", "补充温度记录"),
      new Rule("定制书架", "安装孔位偏差超过五毫米", "补充孔位测量图"),
      new Rule("儿童安全座椅", "安全扣无法闭合", "补充安全扣视频"),
      new Rule("无线耳机", "左耳无法充电", "补充充电盒照片"),
      new Rule("户外帐篷", "支架接头断裂", "补充接头近照"),
      new Rule("陶瓷花盆", "盆底出现裂纹", "补充盆底照片"),
      new Rule("电动牙刷", "开机后无法震动", "补充开机视频"),
      new Rule("便携投影仪", "画面出现持续亮点", "补充投影画面照片"),
      new Rule("旅行行李箱", "拉杆无法收回", "补充拉杆演示视频"),
      new Rule("不锈钢保温壶", "瓶盖密封圈脱落", "补充瓶盖照片"),
      new Rule("办公显示器", "屏幕出现纵向黑线", "补充屏幕照片"));

  static final List<String> TOPICS = List.of("图书借阅", "工位预约", "会议室登记", "设备维修",
      "访客登记", "培训报名", "实验室申请", "活动签到", "门禁补办", "档案借阅", "物品寄存", "场地预约");
  static final List<String> CONTEXTS = List.of("纸质图书的按时归还比例", "共享工位的使用完成比例",
      "小会议室的预约兑现比例", "电脑维修的一次修复比例", "访客预约的实际到访比例", "线上培训的按时完成比例",
      "实验台申请的按期使用比例", "周末活动的实际签到比例", "临时门禁卡的按时归还比例", "纸质档案的按时归还比例",
      "寄存柜的按期清空比例", "室内场地的预约兑现比例");
  static final List<String> FACTS = List.of("每周一开放预约", "最多提前七天申请", "每次使用不超过两小时",
      "申请后一个工作日内确认", "每人每月最多申请三次", "需使用本人账号提交", "周日不受理申请",
      "取消需提前一天通知", "每次可预约一个名额", "确认邮件保留三十天", "节假日暂停办理", "现场核验有效证件");

  static String conditionFixture() {
    StringBuilder text = new StringBuilder("# 售后材料规则\n");
    for (int index = 0; index < RULES.size(); index++) {
      Rule rule = RULES.get(index);
      text.append("\n## ").append(rule.subject()).append("\n")
          .append("关于").append(rule.subject()).append("的售后申请，当出现").append(rule.condition())
          .append("时需要走特殊审核。\n")
          .append("受理人员应核实申请编号、提交时间和商品信息，确认材料清晰后登记处理。".repeat(25))
          .append("\n在上述特殊审核中，申请人必须").append(rule.requirement()).append("后才能继续处理。\n");
    }
    return text.toString();
  }

  static String referenceFixture() {
    StringBuilder text = new StringBuilder();
    for (int index = 0; index < TOPICS.size(); index++) {
      text.append("\n").append(TOPICS.get(index)).append("统计说明：过去三年，")
          .append(CONTEXTS.get(index)).append("持续下降。\n")
          .append("统计人员核对每期记录，排除重复申请和取消记录，再对有效数据进行汇总。".repeat(25))
          .append("\n因此，").append(TOPICS.get(index))
          .append("的该比例应按年度单独复核，不能与其他业务的完成比例混为一谈。\n");
    }
    return text.toString();
  }

  static String factsFixture() {
    StringBuilder text = new StringBuilder("# 办理须知\n");
    for (int index = 0; index < TOPICS.size(); index++) {
      text.append("\n## ").append(TOPICS.get(index)).append("\n")
          .append(TOPICS.get(index)).append("的办理规则：").append(FACTS.get(index)).append("。\n");
    }
    return text.toString();
  }

  static List<RagEvalSample> samples() {
    List<RagEvalSample> samples = new ArrayList<>();
    for (int index = 0; index < 12; index++) {
      Rule rule = RULES.get(index);
      samples.add(sample("condition", index, rule.subject() + "出现" + rule.condition() + "时，特殊审核需要什么材料？",
          "continuity-conditions.md", List.of(rule.condition(), rule.requirement()), false));
      samples.add(sample("reference", index, TOPICS.get(index) + "统计中，过去三年下降的比例具体指什么？",
          "continuity-reference.txt", List.of(CONTEXTS.get(index), TOPICS.get(index) + "的该比例应按年度单独复核"), false));
      samples.add(sample("fact", index, TOPICS.get(index) + "有哪些办理规则？",
          "continuity-facts.md", List.of(FACTS.get(index)), false));
      samples.add(sample("outside", index, TOPICS.get(index) + "的违规罚款金额是多少元？",
          "continuity-facts.md", List.of(), true));
    }
    return List.copyOf(samples);
  }

  private static RagEvalSample sample(String category, int index, String question, String fixture,
                                      List<String> anchors, boolean reject) {
    String id = category + "-" + String.format("%02d", index + 1);
    var evidence = new ArrayList<RagEvalSample.Evidence>();
    for (int anchorIndex = 0; anchorIndex < anchors.size(); anchorIndex++) {
      evidence.add(new RagEvalSample.Evidence(id + "-" + (anchorIndex + 1), anchors.get(anchorIndex)));
    }
    return new RagEvalSample(id, question, List.of(), fixture, List.copyOf(evidence), reject,
        List.of(category), "acceptance", true);
  }
}
