package com.worta.tanks;

import java.util.*;

/** Small strict JSON codec shared by the native server and its JVM tests. */
final class Json {
    static Map<String, Object> object(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("طلب غير صالح");
        return (Map<String, Object>) value;
    }

    static String text(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item instanceof String ? (String) item : "";
    }

    static int number(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof Number)) throw new IllegalArgumentException("رقم غير صالح");
        double number = ((Number) item).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || Math.abs(number) > Integer.MAX_VALUE)
            throw new IllegalArgumentException("رقم غير صالح");
        return (int) number;
    }

    static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.value(0);
        parser.space();
        if (parser.pos != text.length()) throw new IllegalArgumentException("JSON trailing data");
        return value;
    }

    static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : ((String) value).toCharArray()) {
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        else out.append(c);
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Number) {
            if (!Double.isFinite(((Number) value).doubleValue())) throw new IllegalArgumentException("Nonfinite JSON");
            return value.toString();
        }
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            List<String> items = new ArrayList<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet())
                items.add(stringify(e.getKey().toString()) + ":" + stringify(e.getValue()));
            return "{" + String.join(",", items) + "}";
        }
        if (value instanceof Iterable) {
            List<String> items = new ArrayList<>();
            for (Object item : (Iterable<?>) value) items.add(stringify(item));
            return "[" + String.join(",", items) + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static final class Parser {
        final String source;
        int pos;
        Parser(String source) { this.source = source; }
        void space() { while (pos < source.length() && " \t\r\n".indexOf(source.charAt(pos)) >= 0) pos++; }
        char take() {
            if (pos >= source.length()) throw new IllegalArgumentException("Incomplete JSON");
            return source.charAt(pos++);
        }
        void expect(char c) { space(); if (take() != c) throw new IllegalArgumentException("Invalid JSON"); }
        Object value(int depth) {
            if (depth > 40) throw new IllegalArgumentException("JSON nesting");
            space();
            if (pos >= source.length()) throw new IllegalArgumentException("Incomplete JSON");
            char c = source.charAt(pos);
            if (c == '"') return string();
            if (c == '{') {
                pos++;
                Map<String, Object> result = new LinkedHashMap<>();
                space();
                if (pos < source.length() && source.charAt(pos) == '}') { pos++; return result; }
                do {
                    space();
                    String key = string();
                    expect(':');
                    result.put(key, value(depth + 1));
                    space();
                    c = take();
                    if (c == '}') return result;
                    if (c != ',') throw new IllegalArgumentException("Invalid object");
                } while (true);
            }
            if (c == '[') {
                pos++;
                List<Object> result = new ArrayList<>();
                space();
                if (pos < source.length() && source.charAt(pos) == ']') { pos++; return result; }
                do {
                    result.add(value(depth + 1));
                    space();
                    c = take();
                    if (c == ']') return result;
                    if (c != ',') throw new IllegalArgumentException("Invalid array");
                } while (true);
            }
            for (String literal : new String[]{"true", "false", "null"}) {
                if (source.startsWith(literal, pos)) {
                    pos += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            int start = pos;
            while (pos < source.length() && "-+0123456789.eE".indexOf(source.charAt(pos)) >= 0) pos++;
            String number = source.substring(start, pos);
            if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
                throw new IllegalArgumentException("Invalid number");
            double result = Double.parseDouble(number);
            if (!Double.isFinite(result)) throw new IllegalArgumentException("Invalid number");
            return result;
        }
        String string() {
            if (take() != '"') throw new IllegalArgumentException("Invalid string");
            StringBuilder result = new StringBuilder();
            while (true) {
                char c = take();
                if (c == '"') return result.toString();
                if (c < 32) throw new IllegalArgumentException("Invalid string");
                if (c != '\\') { result.append(c); continue; }
                char escaped = take();
                switch (escaped) {
                    case '"': case '\\': case '/': result.append(escaped); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u':
                        if (pos + 4 > source.length()) throw new IllegalArgumentException("Invalid escape");
                        result.append((char) Integer.parseInt(source.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: throw new IllegalArgumentException("Invalid escape");
                }
            }
        }
    }
}
