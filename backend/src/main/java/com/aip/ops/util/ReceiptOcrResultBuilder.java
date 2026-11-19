package com.aip.ops.util;

import com.aip.ops.dto.ReceiptOcrResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 红/蓝双色收据号结果组装（纯函数，便于样例验证）。
 *
 * <p>规则：优先色由配置决定；只有一种颜色候选时直接采用；两种颜色都存在且
 * 不一致时，默认采用配置色并置 {@code needsConfirm=true}，由前端展示两组候选
 * 供员工确认，禁止静默选择。</p>
 */
public final class ReceiptOcrResultBuilder {

    /** 低于该置信度视为可疑：仍回填，但提示人工核对（OCR 偶发 4/6 混淆）。 */
    public static final double MIN_CONFIDENCE = 80;

    private ReceiptOcrResultBuilder() {
    }

    public static ReceiptOcrResult build(List<ReceiptColorDigitExtractor.Candidate> redCandidates,
                                         List<ReceiptColorDigitExtractor.Candidate> blueCandidates,
                                         String preferredColor) {
        String preferred = normalize(preferredColor);
        List<ReceiptColorDigitExtractor.Candidate> red = ReceiptColorDigitExtractor.ranked(redCandidates);
        List<ReceiptColorDigitExtractor.Candidate> blue = ReceiptColorDigitExtractor.ranked(blueCandidates);
        Choice choice = resolve(first(red), first(blue), preferred);
        if (choice == null) {
            return none(preferred, "未识别到收据号，请手动填写");
        }
        boolean exact = isExact(choice.chosen());
        boolean multiple = distinct(red, blue) > 1;
        boolean multipleSameColor = distinct(red) > 1 || distinct(blue) > 1;
        String confidence = exact ? (multiple ? "MEDIUM" : "HIGH") : "LOW";
        return new ReceiptOcrResult(choice.recommended(), choice.redValue(), choice.blueValue(),
                preferred, recommendation(choice), choice.needsConfirm(), exact, multiple,
                confidence, choice.source(), message(choice, exact, multipleSameColor),
                candidates(red, blue, choice, preferred));
    }

    /** 全图 NO. 规则回退：无法区分颜色，只返回单候选。 */
    public static ReceiptOcrResult fallback(ReceiptNoParser.Result parsed, String preferredColor) {
        String preferred = normalize(preferredColor);
        if (parsed == null) {
            return none(preferred, "未定位到 NO. 后的 7 位数字，请手动填写");
        }
        String value = parsed.receiptNo();
        String confidence = parsed.exact() ? (parsed.multiple() ? "MEDIUM" : "HIGH") : "LOW";
        String message;
        if (!parsed.exact()) {
            message = "未区分颜色，识别结果可能不准确，请核对";
        } else if (parsed.multiple()) {
            message = "未区分颜色，按「入账日期 + NO.」识别到多个候选，已填入 " + value + "，请核对";
        } else {
            message = "未区分颜色，按「入账日期 + NO.」识别到 " + value + "，请核对";
        }
        return new ReceiptOcrResult(value, null, null, preferred,
                "未能区分颜色，按 NO. 规则识别", false, parsed.exact(), parsed.multiple(),
                confidence, "FULL", message,
                List.of(new ReceiptOcrResult.Candidate("UNKNOWN", value, "NO.后", 0, true)));
    }

    public static ReceiptOcrResult none(String preferredColor, String message) {
        return new ReceiptOcrResult(null, null, null, normalize(preferredColor),
                "未识别到候选", false, false, false, "LOW", "NONE", message, List.of());
    }

    private static Choice resolve(ReceiptColorDigitExtractor.Candidate red,
                                  ReceiptColorDigitExtractor.Candidate blue, String preferred) {
        String redValue = digits(red);
        String blueValue = digits(blue);
        if (redValue != null && blueValue != null) {
            if (redValue.equals(blueValue)) {
                return new Choice("BOTH", redValue, "BLUE".equals(preferred) ? blue : red,
                        false, redValue, blueValue);
            }
            return new Choice(preferred, "RED".equals(preferred) ? redValue : blueValue,
                    "RED".equals(preferred) ? red : blue, true, redValue, blueValue);
        }
        if (redValue != null) {
            return new Choice("RED", redValue, red, false, redValue, null);
        }
        if (blueValue != null) {
            return new Choice("BLUE", blueValue, blue, false, null, blueValue);
        }
        return null;
    }

    private static String recommendation(Choice choice) {
        if (choice.needsConfirm()) {
            return "默认取" + colorLabel(choice.source());
        }
        if ("BOTH".equals(choice.source())) {
            return "红色与蓝色一致";
        }
        return "仅识别到" + colorLabel(choice.source());
    }

    private static String message(Choice choice, boolean exact, boolean multipleSameColor) {
        StringBuilder sb = new StringBuilder();
        if (choice.needsConfirm()) {
            sb.append("检测到红色收据号").append(choice.redValue())
                    .append("与蓝色收据号").append(choice.blueValue())
                    .append("，当前使用").append(colorLabel(choice.source()))
                    .append("，可点击下方标签切换并确认");
        } else if ("BOTH".equals(choice.source())) {
            sb.append("红色与蓝色数字一致：").append(choice.recommended()).append("，请核对");
        } else if ("RED".equals(choice.source())) {
            sb.append("仅识别到红色收据号").append(choice.recommended()).append("，请核对");
        } else {
            sb.append("仅识别到蓝色收据号").append(choice.recommended()).append("，请核对");
        }
        if (multipleSameColor) {
            sb.append("；同色存在多组数字，已填入第一组");
        }
        if (!exact) {
            sb.append("；识别结果可能不准确，请对照收据手工复核");
        }
        return sb.toString();
    }

    private static List<ReceiptOcrResult.Candidate> candidates(
            List<ReceiptColorDigitExtractor.Candidate> red,
            List<ReceiptColorDigitExtractor.Candidate> blue,
            Choice choice, String preferred) {
        List<ReceiptOcrResult.Candidate> result = new ArrayList<>();
        append(result, red, "RED", choice, preferred);
        append(result, blue, "BLUE", choice, preferred);
        return result;
    }

    private static void append(List<ReceiptOcrResult.Candidate> result,
                               List<ReceiptColorDigitExtractor.Candidate> ranked,
                               String color, Choice choice, String preferred) {
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (ReceiptColorDigitExtractor.Candidate candidate : ranked) {
            if (!seen.add(candidate.digits())) {
                continue;
            }
            index++;
            if (index > 3) {
                break;
            }
            String label = "RED".equals(color) ? "红色" : "蓝色";
            String position = label + "·" + (candidate.upper() ? "上部" : "下部") + "第" + index + "组";
            boolean selected = candidate.digits().equals(choice.recommended())
                    && (color.equals(choice.source())
                    || ("BOTH".equals(choice.source()) && color.equals(preferred)));
            result.add(new ReceiptOcrResult.Candidate(color, candidate.digits(), position,
                    candidate.confidence(), selected));
        }
    }

    private static ReceiptColorDigitExtractor.Candidate first(
            List<ReceiptColorDigitExtractor.Candidate> ranked) {
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    private static String digits(ReceiptColorDigitExtractor.Candidate candidate) {
        return candidate == null ? null : candidate.digits();
    }

    private static boolean isExact(ReceiptColorDigitExtractor.Candidate candidate) {
        return candidate != null && candidate.digits().length() == 7
                && candidate.upper() && candidate.confidence() >= MIN_CONFIDENCE;
    }

    private static int distinct(List<ReceiptColorDigitExtractor.Candidate> candidates) {
        return (int) candidates.stream()
                .map(ReceiptColorDigitExtractor.Candidate::digits).distinct().count();
    }

    private static int distinct(List<ReceiptColorDigitExtractor.Candidate> red,
                                List<ReceiptColorDigitExtractor.Candidate> blue) {
        Set<String> values = new HashSet<>();
        red.forEach(c -> values.add(c.digits()));
        blue.forEach(c -> values.add(c.digits()));
        return values.size();
    }

    private static String normalize(String preferredColor) {
        return "BLUE".equalsIgnoreCase(preferredColor) ? "BLUE" : "RED";
    }

    private static String colorLabel(String color) {
        return "BLUE".equals(color) ? "蓝色" : "红色";
    }

    private record Choice(String source, String recommended,
                          ReceiptColorDigitExtractor.Candidate chosen,
                          boolean needsConfirm, String redValue, String blueValue) {
    }
}
