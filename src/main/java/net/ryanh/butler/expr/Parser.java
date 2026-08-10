package net.ryanh.butler.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive descent over the grammar in DESIGN.md §4.
 *
 * <pre>
 * expr := or
 * or   := and ( 'or' and )*
 * and  := not ( 'and' not )*
 * not  := 'not' not | cmp
 * cmp  := term ( ('=='|'!='|'&lt;'|'&lt;='|'&gt;'|'&gt;='|'matches'|'contains') term )?
 * term := literal | call | path | '(' expr ')'
 * </pre>
 */
final class Parser {

    private final List<Token> tokens;
    private int i;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    static Node parse(String source) {
        Parser p = new Parser(Lexer.tokenize(source));
        Node n = p.expr();
        Token t = p.peek();
        if (!t.is(Token.Kind.EOF)) {
            throw new ExprException("unexpected \"" + t.text() + "\" after end of expression");
        }
        return n;
    }

    private Node expr() {
        return or();
    }

    private Node or() {
        Node left = and();
        while (peek().is(Token.Kind.OR)) {
            advance();
            left = new Node.Bin(Node.Op.OR, left, and());
        }
        return left;
    }

    private Node and() {
        Node left = not();
        while (peek().is(Token.Kind.AND)) {
            advance();
            left = new Node.Bin(Node.Op.AND, left, not());
        }
        return left;
    }

    private Node not() {
        if (peek().is(Token.Kind.NOT)) {
            advance();
            return new Node.Not(not());
        }
        return cmp();
    }

    private Node cmp() {
        Node left = term();
        Node.Op op = switch (peek().kind()) {
            case EQ -> Node.Op.EQ;
            case NE -> Node.Op.NE;
            case LT -> Node.Op.LT;
            case LE -> Node.Op.LE;
            case GT -> Node.Op.GT;
            case GE -> Node.Op.GE;
            case MATCHES -> Node.Op.MATCHES;
            case CONTAINS -> Node.Op.CONTAINS;
            default -> null;
        };
        if (op == null) {
            return left;
        }
        advance();
        return new Node.Bin(op, left, term());
    }

    private Node term() {
        Token t = peek();
        switch (t.kind()) {
            case STRING, NUMBER, DURATION, TRUE, FALSE -> {
                advance();
                return new Node.Lit(t.value());
            }
            case NULL -> {
                advance();
                return new Node.Lit(null);
            }
            case LPAREN -> {
                advance();
                Node inner = expr();
                expect(Token.Kind.RPAREN, ")");
                return inner;
            }
            case IDENT -> {
                return identifierTerm();
            }
            case EOF -> throw new ExprException("expression ended unexpectedly");
            default -> throw new ExprException("unexpected \"" + t.text() + "\"");
        }
    }

    private Node identifierTerm() {
        Token first = advance();
        if (peek().is(Token.Kind.LPAREN)) {
            return call(first);
        }
        return path(first);
    }

    private Node call(Token name) {
        advance(); // (
        List<Node> args = new ArrayList<>();
        if (!peek().is(Token.Kind.RPAREN)) {
            args.add(expr());
            while (peek().is(Token.Kind.COMMA)) {
                advance();
                args.add(expr());
            }
        }
        expect(Token.Kind.RPAREN, ")");
        if (!Functions.exists(name.text())) {
            throw new ExprException(
                    "unknown function \"" + name.text() + "\"" + Functions.suggest(name.text()));
        }
        Functions.checkArity(name.text(), args.size());
        return new Node.Call(name.text(), args);
    }

    private Node path(Token first) {
        List<Node.Seg> segs = new ArrayList<>();
        segs.add(Node.Seg.name(first.text()));
        while (true) {
            if (peek().is(Token.Kind.DOT)) {
                advance();
                Token n = peek();
                if (!n.is(Token.Kind.IDENT)) {
                    throw new ExprException("expected a name after '.'");
                }
                advance();
                segs.add(Node.Seg.name(n.text()));
            } else if (peek().is(Token.Kind.LBRACKET)) {
                advance();
                Token n = peek();
                if (!n.is(Token.Kind.NUMBER) || !(n.value() instanceof Long)) {
                    throw new ExprException("expected an integer index");
                }
                advance();
                segs.add(Node.Seg.index(((Long) n.value()).intValue()));
                expect(Token.Kind.RBRACKET, "]");
            } else {
                return new Node.Var(List.copyOf(segs));
            }
        }
    }

    private Token peek() {
        return tokens.get(i);
    }

    private Token advance() {
        return tokens.get(i++);
    }

    private void expect(Token.Kind kind, String what) {
        Token t = peek();
        if (!t.is(kind)) {
            throw new ExprException("expected \"" + what + "\" but found \""
                    + (t.is(Token.Kind.EOF) ? "end of expression" : t.text()) + "\"");
        }
        advance();
    }
}
