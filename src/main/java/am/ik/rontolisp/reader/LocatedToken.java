package am.ik.rontolisp.reader;

/**
 * A {@link Token} paired with the character offset at which it starts in the source, so
 * the parser can report a line/column for a read error. Offsets are resolved to
 * line/column lazily by {@link am.ik.rontolisp.SourceLocation#at} only when an error is
 * raised.
 *
 * @param token the token
 * @param offset the 0-based character offset of the token's start in the source
 */
public record LocatedToken(Token token, int offset) {

}
