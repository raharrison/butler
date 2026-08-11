package net.ryanh.butler.expr;

import net.ryanh.butler.util.Durations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns expression source into tokens.
 */
final class Lexer {

    private static final Map<String, Token.Kind> KEYWORDS = Map.of(
            "and", Token.Kind.AND,
            "or", Token.Kind.OR,
            "not", Token.Kind.NOT,
            "matches", Token.Kind.MATCHES,
            "contains", Token.Kind.CONTAINS,
            "true", Token.Kind.TRUE,
            "false", Token.Kind.FALSE,
            "null", Token.Kind.NULL);

    private final String src;
    private int i;

    Lexer(String src) {
        this.src = src;
    }

    static List<Token> tokenize(String src) {
        return new Lexer(src).run();
    }

    private List<Token> run() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (i >= src.length()) {
                out.add(new Token(Token.Kind.EOF, "", null));
                return out;
            }
            out.add(next());
        }
    }

    private void skipWhitespace() {
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
            i++;
        }
    }

    private Token next() {
        char c = src.charAt(i);

        // Two-character operators first, so "<=" never lexes as "<" then "=".
        if (i + 1 < src.length()) {
            String two = src.substring(i, i + 2);
            Token.Kind k = switch (two) {
                case "==" -> Token.Kind.EQ;
                case "!=" -> Token.Kind.NE;
                case "<=" -> Token.Kind.LE;
                case ">=" -> Token.Kind.GE;
                default -> null;
            };
            if (k != null) {
                i += 2;
                return new Token(k, two, null);
            }
        }

        switch (c) {
            case '<' -> {
                i++;
                return new Token(Token.Kind.LT, "<", null);
            }
            case '>' -> {
                i++;
                return new Token(Token.Kind.GT, ">", null);
            }
            case '(' -> {
                i++;
                return new Token(Token.Kind.LPAREN, "(", null);
            }
            case ')' -> {
                i++;
                return new Token(Token.Kind.RPAREN, ")", null);
            }
            case '[' -> {
                i++;
                return new Token(Token.Kind.LBRACKET, "[", null);
            }
            case ']' -> {
                i++;
                return new Token(Token.Kind.RBRACKET, "]", null);
            }
            case ',' -> {
                i++;
                return new Token(Token.Kind.COMMA, ",", null);
            }
            case '.' -> {
                i++;
                return new Token(Token.Kind.DOT, ".", null);
            }
            case '\'', '"' -> {
                return string(c);
            }
            case '=' -> throw new ExprException("use '==' for comparison, not '='");
            case '&' -> throw new ExprException("use 'and', not '&&'");
            case '|' -> throw new ExprException("use 'or', not '||'");
            case '!' -> throw new ExprException("use 'not', not '!'");
            default -> { /* fall through */ }
        }

        if (Character.isDigit(c)) {
            return number();
        }
        if (Character.isLetter(c) || c == '_') {
            return identifier();
        }
        throw new ExprException("unexpected character '" + c + "'");
    }

    /**
     * A double-quoted string takes escapes; a single-quoted one is raw, as in YAML, which is what
     * keeps a regex readable: {@code match(stdout, 'v?(\d+\.\d+\.\d+)', 1)}.
     */
    private Token string(char quote) {
        i++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= src.length()) {
                throw new ExprException("unterminated string");
            }
            char c = src.charAt(i);
            if (c == quote) {
                i++;
                return new Token(Token.Kind.STRING, sb.toString(), sb.toString());
            }
            if (quote == '"' && c == '\\' && i + 1 < src.length()) {
                char esc = src.charAt(i + 1);
                sb.append(switch (esc) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '\\' -> '\\';
                    case '\'' -> '\'';
                    case '"' -> '"';
                    default -> throw new ExprException("unknown escape \"\\" + esc + "\"");
                });
                i += 2;
                continue;
            }
            sb.append(c);
            i++;
        }
    }

    private Token number() {
        int start = i;
        while (i < src.length() && Character.isDigit(src.charAt(i))) {
            i++;
        }

        // A unit suffix immediately after the digits makes this a duration, not a number.
        int afterDigits = i;
        String unit = readUnit();
        if (unit != null) {
            String digits = src.substring(start, afterDigits);
            try {
                Duration d = Durations.of(Long.parseLong(digits), unit);
                return new Token(Token.Kind.DURATION, src.substring(start, i), d);
            } catch (IllegalArgumentException e) {
                throw new ExprException("duration " + digits + unit + " is too large");
            }
        }

        boolean isDouble = false;
        if (i < src.length() && src.charAt(i) == '.'
                && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1))) {
            isDouble = true;
            i++;
            while (i < src.length() && Character.isDigit(src.charAt(i))) {
                i++;
            }
        }
        String text = src.substring(start, i);
        // An if/else, not a ternary: a ternary over Double and Long applies binary numeric
        // promotion and boxes both branches as Double.
        Object value;
        if (isDouble) {
            value = Double.valueOf(text);
        } else {
            value = Long.valueOf(text);
        }
        return new Token(Token.Kind.NUMBER, text, value);
    }

    /**
     * Consumes a duration unit if one follows the digits directly; otherwise consumes nothing.
     */
    private String readUnit() {
        int save = i;
        int j = i;
        while (j < src.length() && Character.isLetter(src.charAt(j))) {
            j++;
        }
        if (j == i) {
            return null;
        }
        String word = src.substring(i, j);
        if (Durations.isUnit(word)) {
            i = j;
            return word;
        }
        i = save;
        return null;
    }

    private Token identifier() {
        int start = i;
        while (i < src.length()
                && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
            i++;
        }
        String word = src.substring(start, i);
        Token.Kind kw = KEYWORDS.get(word);
        if (kw == null) {
            return new Token(Token.Kind.IDENT, word, null);
        }
        Object value = switch (kw) {
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            default -> null;
        };
        return new Token(kw, word, value);
    }
}
