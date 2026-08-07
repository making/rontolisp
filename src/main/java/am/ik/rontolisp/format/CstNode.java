package am.ik.rontolisp.format;

import java.util.List;

/**
 * A node of the concrete syntax tree the formatter works on.
 * <p>
 * This is deliberately NOT the reader's AST ({@code am.ik.rontolisp.LispVal}). The reader
 * upcases symbol names, folds {@code 1,000} to {@code 1000}, turns {@code 'x} into
 * {@code (quote x)}, evaluates {@code #+}/{@code #-} guards and drops comments outright
 * -- every one of those is a fact about the SOURCE that a formatter must reproduce
 * verbatim. A CST node therefore stores the original text of each token and keeps
 * comments and reader macros as nodes of their own.
 * <p>
 * The one thing a node does not keep is the surrounding whitespace: indentation and line
 * breaks are re-derived by {@link LispFormatter} from {@link IndentRules}. What survives
 * of the original layout is the two bits in {@link Trivia}.
 */
public sealed interface CstNode {

	/**
	 * The whitespace facts about this node's position in the original source.
	 * @return the trivia
	 */
	Trivia trivia();

	/**
	 * A single indivisible token, held exactly as written: a symbol (with its package
	 * prefix, {@code \} escapes and {@code |...|} runs), a number in whatever radix or
	 * grouping it was typed, a string literal including its quotes and escapes, a
	 * {@code #\c} character literal, {@code #*1010}, {@code #P"..."}, {@code #3#} or a
	 * lone {@code .} of a dotted list.
	 *
	 * @param text the verbatim source text of the token
	 * @param trivia the whitespace facts about this node's position
	 */
	record Atom(String text, Trivia trivia) implements CstNode {
	}

	/**
	 * A parenthesized sequence: a plain list or one of the {@code #(} / {@code #S(} /
	 * {@code #2A(} / {@code #f(} / {@code #8@(} literal openers. Always closed by
	 * {@code )}, so only the opener needs storing.
	 *
	 * @param open the verbatim opening delimiter (for example {@code "("} or
	 * {@code "#S("})
	 * @param items the elements, in order, including any comment nodes between them
	 * @param trivia the whitespace facts about this node's position
	 */
	record Listing(String open, List<CstNode> items, Trivia trivia) implements CstNode {
	}

	/**
	 * A reader macro that binds to the single datum after it: {@code '}, {@code `},
	 * {@code ,}, {@code ,@}, {@code #'}, {@code #.}, {@code #3=} and the
	 * {@code #+feature} / {@code #-feature} guards (whose feature expression is folded
	 * into the prefix text, with its internal whitespace collapsed to single spaces).
	 * Printed glued to its datum, so the pair never splits across lines.
	 *
	 * @param prefix the verbatim prefix text, including the trailing space a
	 * {@code #+feature } guard needs
	 * @param datum the datum the prefix applies to
	 * @param trivia the whitespace facts about this node's position
	 */
	record Prefix(String prefix, CstNode datum, Trivia trivia) implements CstNode {
	}

	/**
	 * A {@code ;} comment, running to the end of its line. The text includes the leading
	 * semicolons (their count is a Common Lisp convention the author chose) and has its
	 * trailing whitespace stripped. Because it swallows the rest of the line, nothing can
	 * ever be printed after one -- {@link LispFormatter} always breaks first, including
	 * before a closing paren.
	 *
	 * @param text the verbatim comment text, semicolons included
	 * @param trivia the whitespace facts about this node's position
	 */
	record LineComment(String text, Trivia trivia) implements CstNode {
	}

	/**
	 * A {@code #| ... |#} block comment, nesting included, held verbatim. A single-line
	 * one prints inline like an atom; a multi-line one is emitted as-is (its interior
	 * lines are content, not indentation, so they are never re-indented) and forces every
	 * enclosing form to break.
	 *
	 * @param text the verbatim comment text, delimiters included
	 * @param trivia the whitespace facts about this node's position
	 */
	record BlockComment(String text, Trivia trivia) implements CstNode {
	}

}
