package com.aip.ops.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 收据号解析（纯函数，便于单测与复用）。
 *
 * <p>规则：定位「入账日期」后的 {@code NO.}，取紧随其后的 7 位数字。
 * OCR 可能把 NO 识别成 N0、在数字间插入空格，或把全角数字原样返回，
 * 这里统一 NFKC 归一化并允许数字间空白。位数不足/超出时仍返回候选，
 * 由调用方提示「识别结果可能不准确」。</p>
 */
public final class ReceiptNoParser {

    private static final Pattern NO_PATTERN = Pattern.compile(
            "(?i)N\\s*[O0][\\s.。:：]*([0-9](?:\\s*[0-9]){5,7})");
    /** 需求锚点：优先「入账日期」；缺失时才退化为通用日期词。 */
    private static final String[] PRIMARY_ANCHORS = {"入账日期"};
    private static final String[] FALLBACK_ANCHORS = {"入账", "日期", "DATE"};

    private ReceiptNoParser() {
    }

    /** @return null=未识别到；否则返回候选与是否精确 7 位/是否多候选。 */
    public static Result parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        int anchor = firstAnchorIndex(normalized, PRIMARY_ANCHORS);
        if (anchor < 0) {
            anchor = firstAnchorIndex(normalized, FALLBACK_ANCHORS);
        }
        Matcher matcher = NO_PATTERN.matcher(normalized);
        List<Candidate> candidates = new ArrayList<>();
        while (matcher.find()) {
            String digits = matcher.group(1).replaceAll("\\s+", "");
            if (digits.length() >= 6 && digits.length() <= 8) {
                candidates.add(new Candidate(digits, matcher.start() >= anchor));
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        List<Candidate> exact = candidates.stream()
                .filter(c -> c.digits().length() == 7)
                .toList();
        List<Candidate> pool = exact.isEmpty() ? candidates : exact;
        Candidate chosen = pool.stream()
                .filter(Candidate::afterAnchor)
                .findFirst()
                .orElse(pool.get(0));
        // 多候选提示按全部候选（含 6/8 位）计算，避免漏报
        boolean multiple = candidates.size() > 1;
        return new Result(chosen.digits(), chosen.digits().length() == 7, multiple);
    }

    private static int firstAnchorIndex(String text, String[] anchors) {
        int best = -1;
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        for (String anchor : anchors) {
            int index = "DATE".equals(anchor)
                    ? upper.indexOf(anchor)
                    : text.indexOf(anchor);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private record Candidate(String digits, boolean afterAnchor) {
    }

    public record Result(String receiptNo, boolean exact, boolean multiple) {
    }
}
