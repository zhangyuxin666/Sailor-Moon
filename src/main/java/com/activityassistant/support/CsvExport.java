package com.activityassistant.support;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;

/** RFC 4180 CSV; only actual Number values bypass spreadsheet-formula protection. */
public final class CsvExport {
    private CsvExport() {}

    public static byte[] bytes(List<String> headers, List<? extends List<?>> rows) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        append(csv, headers);
        for (List<?> row : rows) {
            if (row.size() != headers.size()) throw new IllegalArgumentException("CSV column count mismatch");
            append(csv, row);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void append(StringBuilder csv, List<?> row) {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) csv.append(',');
            csv.append('"').append(cell(row.get(i)).replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    public static String cell(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (value instanceof Number || value instanceof Boolean) return text;
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        int start = 0;
        while (start < normalized.length()) {
            int point = normalized.codePointAt(start);
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)
                    && !Character.isISOControl(point) && Character.getType(point) != Character.FORMAT) break;
            start += Character.charCount(point);
        }
        if (start < normalized.length()) {
            int first = normalized.codePointAt(start);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == 0x2212)
                return "'" + text;
        }
        return text;
    }
}
