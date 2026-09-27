import os
import platform
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import inch
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.colors import HexColor
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak, KeepTogether
from reportlab.lib.enums import TA_LEFT, TA_CENTER, TA_JUSTIFY

def register_cjk_font():
    system = platform.system()
    if system == "Windows":
        font_paths = [
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/simhei.ttf",
        ]
    elif system == "Darwin":
        font_paths = [
            "/System/Library/Fonts/PingFang.ttc",
            "/System/Library/Fonts/STHeiti Medium.ttc",
        ]
    else:
        font_paths = [
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        ]

    for font_path in font_paths:
        if os.path.exists(font_path):
            try:
                pdfmetrics.registerFont(TTFont("CJKFont", font_path, subfontIndex=0))
                return "CJKFont"
            except:
                continue
    raise Exception("No CJK font found")

def get_professional_styles(cjk_font='CJKFont'):
    PRIMARY_COLOR = HexColor('#1a365d')
    ACCENT_COLOR = HexColor('#2b6cb0')
    return {
        'title': ParagraphStyle(
            'Title', fontName=cjk_font, fontSize=22, leading=30,
            textColor=PRIMARY_COLOR, spaceAfter=16, alignment=1, wordWrap='CJK'
        ),
        'subtitle': ParagraphStyle(
            'Subtitle', fontName=cjk_font, fontSize=12, leading=16,
            textColor=HexColor('#4a5568'), spaceAfter=20, alignment=1, wordWrap='CJK'
        ),
        'h1': ParagraphStyle(
            'H1', fontName=cjk_font, fontSize=16, leading=24,
            textColor=PRIMARY_COLOR, spaceBefore=20, spaceAfter=10, wordWrap='CJK'
        ),
        'h2': ParagraphStyle(
            'H2', fontName=cjk_font, fontSize=14, leading=20,
            textColor=ACCENT_COLOR, spaceBefore=14, spaceAfter=6, wordWrap='CJK'
        ),
        'body': ParagraphStyle(
            'Body', fontName=cjk_font, fontSize=11, leading=20,
            textColor=HexColor('#2d3748'), spaceBefore=0, spaceAfter=10,
            firstLineIndent=22, wordWrap='CJK', alignment=TA_JUSTIFY
        ),
        'caption': ParagraphStyle(
            'Caption', fontName=cjk_font, fontSize=9, leading=12,
            textColor=HexColor('#718096'), alignment=1, spaceBefore=6, spaceAfter=12, wordWrap='CJK'
        ),
        'meta': ParagraphStyle(
            'Meta', fontName=cjk_font, fontSize=10, leading=14,
            textColor=HexColor('#666666'), alignment=0, spaceAfter=8, wordWrap='CJK'
        ),
    }

def main():
    register_cjk_font()
    styles = get_professional_styles('CJKFont')

    output_path = "docs/test-papers/搜狐文章-小学作文写作技巧.pdf"
    doc = SimpleDocTemplate(
        output_path,
        pagesize=A4,
        leftMargin=0.8*inch,
        rightMargin=0.8*inch,
        topMargin=0.8*inch,
        bottomMargin=0.8*inch,
    )

    story = []

    # Title
    story.append(Paragraph("小学语文之小学作文写作", styles['title']))
    story.append(Paragraph("1~6年级各类作文写作技巧讲解，直击孩子写作误区", styles['subtitle']))
    story.append(Paragraph("来源：搜狐网（家长成长社）　|　发布时间：2021-08-29", styles['meta']))
    story.append(Spacer(1, 10))

    # Introduction
    story.append(Paragraph(
        "作文作为整个学生生涯都是很重要的，在小学阶段正是对作文打基础的最好时间。"
        "如何写好小学作文，让孩子能对作文有一个清晰的概念。从此不再对作文为难，不凑字数，不东凑西拼。",
        styles['body']
    ))

    story.append(Paragraph(
        "小学作文按照小学阶段来划分的话，小学1、2年级可以称之为写话课，1~2年级着重于打基础，"
        "积累字词，学会组词和写通顺的句子，以及了解写作的格式规范，如标点符号怎么打，"
        "各种标点符号什么含义。然后开始进行简单地看图写话，描写生活中的一些常见场景。",
        styles['body']
    ))

    story.append(Paragraph(
        "看图写一句话、一段话。此时，最主要的要求是，看懂图画，把话写清楚、写通顺、写具体。"
        "当然，到了小学3年级是作文的起步阶段，才算刚刚迈入写作的门槛，"
        "同时也是检验和提升他们对语文认知的重要过程。到了3年级逐渐开始各种类型的写作，"
        "写人、记事、写景、写事物等等。能够把一件事写清楚、把一个人写出特点、"
        "把一种物品、一处景物描写一个大概特点，初步具有想象力。",
        styles['body']
    ))

    story.append(Paragraph(
        "整理了小学作文的一些写作技巧和写作资料，供大家参考！",
        styles['body']
    ))

    story.append(Spacer(1, 16))

    # Grade 1-2
    story.append(Paragraph("一、小学一二年级作文要求", styles['h1']))
    story.append(Paragraph(
        "1~2年级是作文的启蒙阶段，主要目标是培养写作兴趣和基本表达能力。这个阶段的重点包括：",
        styles['body']
    ))
    story.append(Paragraph(
        "• 认识并正确使用常用标点符号（逗号、句号、问号、感叹号等）<br/>"
        "• 学会组词造句，把句子写完整、写通顺<br/>"
        "• 能够看懂简单的图画，并根据图画写一两句话<br/>"
        "• 描写生活中常见的简单场景，如家人、动物、植物等<br/>"
        "• 培养观察能力，鼓励说真话、写真事",
        styles['body']
    ))

    story.append(Paragraph("【写作技巧】", styles['h2']))
    story.append(Paragraph(
        "① 多积累好词好句。可以通过朗读课文、背诵儿歌等方式，丰富词汇量。<br/>"
        "② 从说完整的话开始。家长可以先让孩子口头描述，再慢慢写下来。<br/>"
        "③ 重视看图写话训练。引导孩子按照一定的顺序观察图画（从上到下、从左到右），"
        "把看到的内容有条理地写出来。<br/>"
        "④ 鼓励写日记。哪怕每天只写一两句话，坚持下来也能看到明显进步。",
        styles['body']
    ))

    # Grade 3-4
    story.append(Paragraph("二、小学三四年级作文要求", styles['h1']))
    story.append(Paragraph(
        "3~4年级是作文的起步阶段，从写话过渡到完整的篇章写作。"
        "这个阶段开始接触不同类型的作文：写人、记事、写景、状物等。",
        styles['body']
    ))
    story.append(Paragraph(
        "• 能够把一件事按照起因、经过、结果的顺序写清楚<br/>"
        "• 写出人物的外貌、语言、动作、神态等基本特点<br/>"
        "• 描写一处景物或一种物品，抓住其主要特征<br/>"
        "• 初步学习分段写作，段落之间有逻辑联系<br/>"
        "• 字数要求一般在300字左右",
        styles['body']
    ))

    story.append(Paragraph("【写作技巧】", styles['h2']))
    story.append(Paragraph(
        "① 写人作文：抓住人物的一两个突出特点，用具体事例来表现，避免面面俱到。<br/>"
        "② 记事作文：按照时间顺序或事情发展顺序来写，重点写清楚事情的经过，"
        "并写出自己的真实感受。<br/>"
        "③ 写景作文：运用多种感官（视觉、听觉、嗅觉等）进行描写，"
        "适当使用比喻、拟人等修辞手法。<br/>"
        "④ 状物作文：仔细观察物品的形状、颜色、质地等，""可以适当联想，写出物品的特点和用途。<br/>"
        "⑤ 多阅读优秀范文，学习别人的写作方法和表达技巧。",
        styles['body']
    ))

    # Grade 5-6
    story.append(Paragraph("三、小学五六年级作文要求", styles['h1']))
    story.append(Paragraph(
        "5~6年级是作文的提升阶段，要求能够写内容具体、条理清楚、感情真实的作文。"
        "这个阶段更加注重写作技巧和表达能力的培养。",
        styles['body']
    ))
    story.append(Paragraph(
        "• 能够根据题目要求，选择合适的材料，中心明确<br/>"
        "• 文章结构完整，详略得当，重点突出<br/>"
        "• 语言生动形象，能够运用多种修辞手法<br/>"
        "• 写出真情实感，表达自己的观点和态度<br/>"
        "• 字数要求一般在400~500字左右",
        styles['body']
    ))

    story.append(Paragraph("【写作技巧】", styles['h2']))
    story.append(Paragraph(
        "① 审题立意：认真分析题目，明确写作要求和中心思想，避免偏题跑题。<br/>"
        "② 选材典型：选择最能表现中心思想的材料，舍弃无关或关系不大的内容。<br/>"
        "③ 结构安排：学会使用总—分—总结构，开头点题，中间展开，结尾升华。<br/>"
        "④ 细节描写：通过动作、语言、心理、神态等细节描写，让人物和事件更加生动。<br/>"
        "⑤ 修改润色：写完后多读几遍，检查语句是否通顺、有无错别字、标点是否正确。",
        styles['body']
    ))

    # Common mistakes
    story.append(Paragraph("四、小学生作文常见误区", styles['h1']))
    story.append(Paragraph(
        "• <b>凑字数：</b>为了凑够字数，重复啰嗦或写无关内容。应学会围绕中心展开，内容充实自然。<br/>"
        "• <b>假大空：</b>说空话、套话，缺乏真情实感。应写自己亲身经历或真实感受。<br/>"
        "• <b>没有条理：</b>想到哪里写到哪里，结构混乱。应学会列提纲，合理安排材料。<br/>"
        "• <b>语言贫乏：</b>词汇量不足，句子单调。应平时多积累好词好句，多阅读。<br/>"
        "• <b>不写细节：</b>只写大概，缺乏具体描写。应学会抓住细节，用生动的语言描绘。",
        styles['body']
    ))

    # Tips
    story.append(Paragraph("五、家长辅导建议", styles['h1']))
    story.append(Paragraph(
        "① 不要急于让孩子动笔，先引导他们想清楚要写什么、怎么写。<br/>"
        "② 多带孩子观察生活，积累写作素材。大自然、家庭生活、校园活动都是好题材。<br/>"
        "③ 尊重孩子的原创，不要代笔或过度修改，保护孩子的写作积极性。<br/>"
        "④ 鼓励孩子大声朗读自己的作文，在读的过程中发现问题。<br/>"
        "⑤ 建立'好词好句本'，让孩子把看到、听到的精彩表达记录下来。",
        styles['body']
    ))

    story.append(Spacer(1, 20))
    story.append(Paragraph(
        "— 本文整理自搜狐网《家长成长社》文章，仅供学习参考 —",
        styles['caption']
    ))

    doc.build(story)
    print(f"PDF generated: {output_path}")

if __name__ == "__main__":
    main()
