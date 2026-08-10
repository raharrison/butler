package net.ryanh.butler.expr;

/**
 * A lexed token. {@code value} is populated for literals only.
 */
record Token(Token.Kind kind, String text, Object value) {

    enum Kind {
        IDENT, STRING, NUMBER, DURATION,
        TRUE, FALSE, NULL,
        AND, OR, NOT, MATCHES, CONTAINS,
        EQ, NE, LT, LE, GT, GE,
        LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, DOT,
        EOF
    }

    boolean is(Kind k) {
        return kind == k;
    }
}
