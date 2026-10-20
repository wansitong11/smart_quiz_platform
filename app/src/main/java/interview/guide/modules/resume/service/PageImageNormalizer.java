package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 页图规范化：EXIF 方向摆正、缩放、JPEG 压缩。
 * <p>
 * 目标长边 2000px、质量 0.85：既能看清小字，又不至于让单页图片 token 失控。
 * </p>
 */
@Component
public class PageImageNormalizer {

  private static final int MAX_EDGE = 2000;
  private static final float JPEG_QUALITY = 0.85f;

  /** 上传图片（JPG/PNG）→ 方向正确、尺寸受控的 JPEG。 */
  public byte[] normalizeUploadedImage(byte[] original) {
    BufferedImage image = readImage(original);
    int orientation = readExifOrientation(original);
    BufferedImage oriented = applyOrientation(image, orientation);
    return toJpeg(oriented);
  }

  /** 任意 BufferedImage（PDF 渲染结果）→ 缩放后的 JPEG。 */
  public byte[] toJpeg(BufferedImage image) {
    BufferedImage scaled = scaleToMaxEdge(image);
    BufferedImage rgb = new BufferedImage(scaled.getWidth(), scaled.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = rgb.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.setColor(java.awt.Color.WHITE);
      graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
      graphics.drawImage(scaled, 0, 0, null);
    } finally {
      graphics.dispose();
    }
    return encodeJpeg(rgb);
  }

  private BufferedImage readImage(byte[] bytes) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
      if (image == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "无法读取图片内容，请上传 JPG 或 PNG");
      }
      return image;
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "读取图片失败，请确认文件未损坏");
    }
  }

  private BufferedImage scaleToMaxEdge(BufferedImage image) {
    int width = image.getWidth();
    int height = image.getHeight();
    int longEdge = Math.max(width, height);
    if (longEdge <= MAX_EDGE) {
      return image;
    }
    double ratio = (double) MAX_EDGE / longEdge;
    int targetWidth = Math.max(1, (int) Math.round(width * ratio));
    int targetHeight = Math.max(1, (int) Math.round(height * ratio));
    BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = scaled.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
    } finally {
      graphics.dispose();
    }
    return scaled;
  }

  private byte[] encodeJpeg(BufferedImage image) {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "当前运行环境不支持 JPEG 编码");
    }
    ImageWriter writer = writers.next();
    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
         ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(imageOut);
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(JPEG_QUALITY);
      writer.write(null, new IIOImage(image, null, null), param);
      return out.toByteArray();
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "页图编码失败");
    } finally {
      writer.dispose();
    }
  }

  // ==================== EXIF 方向 ====================

  private BufferedImage applyOrientation(BufferedImage image, int orientation) {
    if (orientation <= 1 || orientation > 8) {
      return image;
    }
    int width = image.getWidth();
    int height = image.getHeight();
    boolean swap = orientation >= 5;
    int targetWidth = swap ? height : width;
    int targetHeight = swap ? width : height;
    BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = target.createGraphics();
    try {
      AffineTransform transform = new AffineTransform();
      switch (orientation) {
        case 2 -> {
          transform.translate(width, 0);
          transform.scale(-1, 1);
        }
        case 3 -> {
          transform.translate(width, height);
          transform.rotate(Math.PI);
        }
        case 4 -> {
          transform.translate(0, height);
          transform.scale(1, -1);
        }
        case 5 -> {
          transform.rotate(Math.PI / 2);
          transform.scale(1, -1);
        }
        case 6 -> {
          transform.translate(height, 0);
          transform.rotate(Math.PI / 2);
        }
        case 7 -> {
          transform.translate(height, width);
          transform.rotate(-Math.PI / 2);
          transform.scale(1, -1);
        }
        case 8 -> {
          transform.translate(0, width);
          transform.rotate(-Math.PI / 2);
        }
        default -> {
        }
      }
      graphics.drawImage(image, transform, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }

  /**
   * 最小 EXIF 解析：只读 JPEG APP1 里的 Orientation(0x0112)。
   * 不引第三方库，解析失败一律按"正常方向"处理。
   */
  int readExifOrientation(byte[] jpeg) {
    if (jpeg == null || jpeg.length < 4
        || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
      return 1;
    }
    int index = 2;
    while (index + 4 <= jpeg.length) {
      if ((jpeg[index] & 0xFF) != 0xFF) {
        index++;
        continue;
      }
      int marker = jpeg[index + 1] & 0xFF;
      if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
        index += 2;
        continue;
      }
      if (marker == 0xDA || marker == 0xD9) {
        return 1;
      }
      int length = ((jpeg[index + 2] & 0xFF) << 8) | (jpeg[index + 3] & 0xFF);
      if (length < 2) {
        return 1;
      }
      if (marker == 0xE1 && length >= 10
          && jpeg[index + 4] == 'E' && jpeg[index + 5] == 'x'
          && jpeg[index + 6] == 'i' && jpeg[index + 7] == 'f'
          && jpeg[index + 8] == 0 && jpeg[index + 9] == 0) {
        return parseTiffOrientation(jpeg, index + 10, Math.min(jpeg.length, index + 2 + length));
      }
      index += 2 + length;
    }
    return 1;
  }

  private int parseTiffOrientation(byte[] data, int start, int end) {
    if (start + 8 > end) {
      return 1;
    }
    boolean littleEndian;
    if (data[start] == 'I' && data[start + 1] == 'I') {
      littleEndian = true;
    } else if (data[start] == 'M' && data[start + 1] == 'M') {
      littleEndian = false;
    } else {
      return 1;
    }
    int ifdOffset = readInt(data, start + 4, littleEndian);
    int ifdStart = start + ifdOffset;
    if (ifdStart < start || ifdStart + 2 > end) {
      return 1;
    }
    int entries = readShort(data, ifdStart, littleEndian);
    for (int i = 0; i < entries; i++) {
      int entry = ifdStart + 2 + i * 12;
      if (entry + 12 > end) {
        break;
      }
      int tag = readShort(data, entry, littleEndian);
      if (tag == 0x0112) {
        return readShort(data, entry + 8, littleEndian);
      }
    }
    return 1;
  }

  private int readShort(byte[] data, int offset, boolean littleEndian) {
    if (offset + 2 > data.length) {
      return 0;
    }
    int b0 = data[offset] & 0xFF;
    int b1 = data[offset + 1] & 0xFF;
    return littleEndian ? (b1 << 8) | b0 : (b0 << 8) | b1;
  }

  private int readInt(byte[] data, int offset, boolean littleEndian) {
    if (offset + 4 > data.length) {
      return 0;
    }
    int b0 = data[offset] & 0xFF;
    int b1 = data[offset + 1] & 0xFF;
    int b2 = data[offset + 2] & 0xFF;
    int b3 = data[offset + 3] & 0xFF;
    return littleEndian
        ? (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
        : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
  }
}
