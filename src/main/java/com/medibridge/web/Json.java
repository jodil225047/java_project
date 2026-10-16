package com.medibridge.web;

import com.medibridge.util.ServiceException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A tiny JSON writer and parser, so the project needs no external library. */
public final class Json {
    private Json() {
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(o, sb);
        return sb.toString();
    }

    private static void write(Object o, StringBuilder sb) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String s) {
            string(s, sb);
        } else if (o instanceof Boolean || o instanceof Integer || o instanceof Long) {
            sb.append(o);
        } else if (o instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (o instanceof Enum<?> e) {
            string(e.name(), sb);
        } else if (o instanceof Instant i) {
            string(i.toString(), sb);
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                string(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) sb.append(',');
                first = false;
                write(x, sb);
            }
            sb.append(']');
        } else {
            string(o.toString(), sb);
        }
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ parsing

    /** Parses JSON into Map / List / String / Double / Boolean / null. Throws a 400 on bad input. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) throw p.error("Unexpected trailing characters");
        return v;
    }

    private static final class Parser {
        private final String t;
        private int pos;

        Parser(String t) {
            this.t = t;
        }

        ServiceException error(String msg) {
            return new ServiceException(400, "Invalid JSON: " + msg);
        }

        void skipWs() {
            while (pos < t.length() && Character.isWhitespace(t.charAt(pos))) pos++;
        }

        Object value(int depth) {
            if (depth > 20) throw error("nesting too deep");
            if (pos >= t.length()) throw error("unexpected end");
            char c = t.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    return number();
            }
        }

        Object literal(String word, Object val) {
            if (!t.startsWith(word, pos)) throw error("unexpected token");
            pos += word.length();
            return val;
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') throw error("expected string key");
                String k = string();
                skipWs();
                if (peek() != ':') throw error("expected ':'");
                pos++;
                skipWs();
                m.put(k, value(depth + 1));
                skipWs();
                char c = peek();
                pos++;
                if (c == '}') return m;
                if (c != ',') throw error("expected ',' or '}'");
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value(depth + 1));
                skipWs();
                char c = peek();
                pos++;
                if (c == ']') return l;
                if (c != ',') throw error("expected ',' or ']'");
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            pos++; // opening quote
            while (true) {
                if (pos >= t.length()) throw error("unterminated string");
                char c = t.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= t.length()) throw error("bad escape");
                    char e = t.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > t.length()) throw error("bad unicode escape");
                            try {
                                sb.append((char) Integer.parseInt(t.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("bad unicode escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Double number() {
            int start = pos;
            while (pos < t.length() && "+-0123456789.eE".indexOf(t.charAt(pos)) >= 0) pos++;
            if (start == pos) throw error("unexpected character '" + t.charAt(pos) + "'");
            try {
                return Double.parseDouble(t.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("bad number");
            }
        }

        char peek() {
            if (pos >= t.length()) throw error("unexpected end");
            return t.charAt(pos);
        }
    }
}
