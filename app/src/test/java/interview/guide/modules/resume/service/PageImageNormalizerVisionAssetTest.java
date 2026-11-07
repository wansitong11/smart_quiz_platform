package interview.guide.modules.resume.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("题目图片视觉预处理")
class PageImageNormalizerVisionAssetTest {

  private final PageImageNormalizer normalizer = new PageImageNormalizer();

  @Test
  @DisplayName("小尺寸题干图会放大到视觉模型可读尺寸")
  void shouldUpscaleSmallStemImage() {
    BufferedImage source = new BufferedImage(80, 100, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = source.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 80, 100);
    graphics.setColor(Color.BLACK);
    graphics.drawRect(20, 20, 40, 50);
    graphics.dispose();

    BufferedImage prepared = normalizer.upscaleVisionAsset(source);

    assertThat(prepared.getWidth()).isEqualTo(240);
    assertThat(prepared.getHeight()).isEqualTo(300);
  }

  @Test
  @DisplayName("图片预处理器只放大，不向内裁切图形")
  void shouldNeverCropFigure() {
    BufferedImage source = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = source.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 600, 400);
    graphics.setColor(Color.BLACK);
    graphics.drawRect(60, 20, 360, 120);
    graphics.dispose();

    BufferedImage prepared = normalizer.upscaleVisionAsset(source);

    assertThat(prepared.getWidth()).isEqualTo(source.getWidth());
    assertThat(prepared.getHeight()).isEqualTo(source.getHeight());
  }
}
