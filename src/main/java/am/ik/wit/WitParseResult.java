package am.ik.wit;

/**
 * A parsed WIT document together with the source positions of its items.
 *
 * @param document the parsed document
 * @param locations the position of every item in the document, by identity
 * @see WitParser#parseLocated(String)
 */
public record WitParseResult(WitDocument document, WitLocations locations) {
}
