package net.ryanh.butler.expr;

/**
 * Raised when an expression cannot be lexed, parsed or evaluated.
 */
public class ExprException extends RuntimeException {

    public ExprException(String message) {
        super(message);
    }
}
