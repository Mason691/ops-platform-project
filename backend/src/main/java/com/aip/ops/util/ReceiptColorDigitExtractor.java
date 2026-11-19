package com.aip.ops.util;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 收据彩色数字提取（纯 JDK 图像处理，便于单测与复用）。
 *
 * <p>业务背景：收据号通常位于「入账日期」后的 {@code NO.} 附近，实际收据上
 * 可能同时印有红色与蓝色两组 7 位数字。灰度 OCR 对彩色数字对比度不足，
 * Tesseract 常整段漏识，因此按颜色分别生成黑字白底掩膜，再交给 Tesseract
 * 做数字白名单识别，由上层决定默认采用哪一组。</p>
 *
 * <p>阈值来自生产收据实测：红字核心 RGB 约 (232,76,61)，抗锯齿边缘
 * R 仍明显高于 G/B；蓝字核心 B 分量显著高于 R/G。R≥100 且
 * R-max(G,B)≥30 可保留完整红字笔画；B≥80 且 B-max(R,G)≥25 可保留蓝字，
 * 同时避开黑字（B 低）、灰底（三通道接近）和木纹桌面等偏色背景。</p>
 */
public final class ReceiptColorDigitExtractor {

    /** 收据号可能存在的两种印刷颜色。 */
    public enum Color {
        RED, BLUE
    }

    /** 红色判定：R 分量明显高于 G/B 的最小差值。 */
    static final int RED_DOMINANCE = 30;
    /** 红色判定：R 分量下限，过滤暗红背景噪点。 */
    static final int RED_MIN = 100;
    /** 蓝色判定：B 分量明显高于 R/G 的最小差值。 */
    static final int BLUE_DOMINANCE = 25;
    /** 蓝色判定：B 分量下限，过滤黑字与暗色背景。 */
    static final int BLUE_MIN = 80;
    /** 收据号通常位于图片上部；低于该比例视为位置可疑（如底部红章流水号）。 */
    static final double UPPER_REGION_RATIO = 0.72;

    private ReceiptColorDigitExtractor() {
    }

    /**
     * 生成指定颜色的掩膜：目标颜色像素为黑，其余为白。
     * 按行处理，避免原图像素数组占满内存。
     */
    public static BufferedImage buildMask(BufferedImage source, Color color) {
        if (source == null || color == null) {
            return null;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        int[] row = new int[width];
        int[] maskRow = new int[width];
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int rgb = row[x];
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                maskRow[x] = isTargetColor(r, g, b, color) ? 0 : 255;
            }
            mask.getRaster().setPixels(0, y, width, 1, maskRow);
        }
        return mask;
    }

    private static boolean isTargetColor(int r, int g, int b, Color color) {
        if (color == Color.BLUE) {
            return b >= BLUE_MIN && (b - Math.max(r, g)) >= BLUE_DOMINANCE;
        }
        return r >= RED_MIN && (r - Math.max(g, b)) >= RED_DOMINANCE;
    }

    /**
     * 解析 Tesseract TSV，保留 6~8 位纯数字词。
     * 少于 6 位/多于 8 位（如印章上的 13 位流水号）不视为收据号。
     */
    public static List<Candidate> parseTsv(String tsv, int imageHeight) {
        List<Candidate> result = new ArrayList<>();
        if (tsv == null || tsv.isBlank()) {
            return result;
        }
        for (String line : tsv.split("\\R")) {
            String[] cols = line.split("\\t", -1);
            if (cols.length < 12 || !"5".equals(cols[0].trim())) {
                continue;
            }
            String text = cols[11].trim();
            if (!text.matches("\\d{6,8}")) {
                continue;
            }
            try {
                int left = Integer.parseInt(cols[6].trim());
                int top = Integer.parseInt(cols[7].trim());
                int width = Integer.parseInt(cols[8].trim());
                int height = Integer.parseInt(cols[9].trim());
                double confidence = Double.parseDouble(cols[10].trim());
                boolean upper = imageHeight <= 0
                        || top + height <= imageHeight * UPPER_REGION_RATIO;
                result.add(new Candidate(text, left, top, width, height, confidence, upper));
            } catch (NumberFormatException ignored) {
                // 表头/空行/异常行直接跳过
            }
        }
        return result;
    }

    /**
     * 候选排序：上部区域优先 → 7 位优先 → 置信度高优先 → 靠上/靠右优先。
     * 上部优先于位数，避免把底部红/蓝章里的 7 位片段误当收据号。
     */
    public static List<Candidate> ranked(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Candidate> upper = candidates.stream().filter(Candidate::upper).toList();
        List<Candidate> pool = upper.isEmpty() ? candidates : upper;
        List<Candidate> exact = pool.stream().filter(c -> c.digits().length() == 7).toList();
        List<Candidate> finalPool = exact.isEmpty() ? pool : exact;
        return finalPool.stream()
                .sorted(Comparator.comparingDouble(Candidate::confidence).reversed()
                        .thenComparingInt(Candidate::top)
                        .thenComparing(Comparator.comparingInt(Candidate::left).reversed()))
                .toList();
    }

    /** 选取最佳候选；无候选时返回 null。 */
    public static Candidate choose(List<Candidate> candidates) {
        return ranked(candidates).stream().findFirst().orElse(null);
    }

    /**
     * 彩色数字候选。
     *
     * @param digits     识别出的数字串（6~8 位）
     * @param left/top   掩膜中的词框（Tesseract TSV）
     * @param confidence OCR 置信度
     * @param upper      是否位于图片上部（收据号常规位置）
     */
    public record Candidate(String digits, int left, int top, int width, int height,
                            double confidence, boolean upper) {
    }
}
