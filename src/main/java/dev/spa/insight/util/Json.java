package dev.spa.insight.util;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部ライブラリに依存しない最小のJSON入出力。
 * 書き出しはエクスポート用、読み込みはサーバーの stats / advancements の解析に使う。
 */
public final class Json {

    private Json() {
    }

    public static Map<String, Object> obj() {
        return new LinkedHashMap<>();
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out, -1, 0);
        return out.toString();
    }

    public static String writePretty(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out, 2, 0);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder out, int indent, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue() ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                out.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1.0E15) {
                out.append((long) d);
            } else {
                out.append(d);
            }
        } else if (value instanceof Number n) {
            out.append(n.toString());
        } else if (value instanceof Map<?, ?> map) {
            writeObject((Map<String, Object>) map, out, indent, depth);
        } else if (value instanceof Iterable<?> iterable) {
            writeArray(iterable, out, indent, depth);
        } else if (value instanceof Object[] array) {
            writeArray(List.of(array), out, indent, depth);
        } else {
            writeString(value.toString(), out);
        }
    }

    private static void writeObject(Map<String, Object> map, StringBuilder out, int indent, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, indent, depth + 1);
            writeString(entry.getKey(), out);
            out.append(':');
            if (indent >= 0) {
                out.append(' ');
            }
            writeValue(entry.getValue(), out, indent, depth + 1);
        }
        newline(out, indent, depth);
        out.append('}');
    }

    private static void writeArray(Iterable<?> values, StringBuilder out, int indent, int depth) {
        java.util.Iterator<?> iterator = values.iterator();
        if (!iterator.hasNext()) {
            out.append("[]");
            return;
        }
        out.append('[');
        boolean first = true;
        while (iterator.hasNext()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, indent, depth + 1);
            writeValue(iterator.next(), out, indent, depth + 1);
        }
        newline(out, indent, depth);
        out.append(']');
    }

    private static void newline(StringBuilder out, int indent, int depth) {
        if (indent < 0) {
            return;
        }
        out.append('\n');
        for (int i = 0; i < indent * depth; i++) {
            out.append(' ');
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    public static Object parse(Reader reader) throws IOException {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            builder.append(buffer, 0, read);
        }
        return parse(builder.toString());
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    public static long asLong(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    public static String asString(Object value, String fallback) {
        return value instanceof String s ? s : fallback;
    }

    private static final class Parser {

        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private Object readValue() {
            if (index >= text.length()) {
                throw new IllegalArgumentException("JSONが途中で終わっています: index=" + index);
            }
            char c = text.charAt(index);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            index++;
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == '}') {
                index++;
                return map;
            }
            while (index < text.length()) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("オブジェクトの区切りが不正です: " + c);
                }
            }
            throw new IllegalArgumentException("オブジェクトが閉じられていません");
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            index++;
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == ']') {
                index++;
                return list;
            }
            while (index < text.length()) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("配列の区切りが不正です: " + c);
                }
            }
            throw new IllegalArgumentException("配列が閉じられていません");
        }

        private String readString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c != '\\') {
                    builder.append(c);
                    continue;
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        builder.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("不正なエスケープです: \\" + escaped);
                }
            }
            throw new IllegalArgumentException("文字列が閉じられていません");
        }

        private Object readBoolean() {
            if (text.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("真偽値として解釈できません: index=" + index);
        }

        private Object readNull() {
            if (text.startsWith("null", index)) {
                index += 4;
                return null;
            }
            throw new IllegalArgumentException("nullとして解釈できません: index=" + index);
        }

        private Object readNumber() {
            int start = index;
            while (index < text.length() && "+-0123456789.eE".indexOf(text.charAt(index)) >= 0) {
                index++;
            }
            String raw = text.substring(start, index);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("数値として解釈できません: index=" + start);
            }
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                try {
                    return Long.valueOf(raw);
                } catch (NumberFormatException ignored) {
                    // 桁あふれは倍精度で受ける。
                }
            }
            return Double.valueOf(raw);
        }

        private char next() {
            if (index >= text.length()) {
                throw new IllegalArgumentException("JSONが途中で終わっています");
            }
            return text.charAt(index++);
        }

        private void expect(char expected) {
            char c = next();
            if (c != expected) {
                throw new IllegalArgumentException("'" + expected + "' を期待しましたが '" + c + "' でした");
            }
        }
    }
}
