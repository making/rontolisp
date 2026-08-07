package am.ik.rontolisp.format;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Formats Lisp source: re-indents it, re-wraps it to a right margin and normalizes its
 * blank lines, changing nothing else.
 * <p>
 * The rules, in the order they are applied to each form:
 * <ol>
 * <li>If the whole form fits within the margin on one line, it goes on one line. A form
 * holding a {@code ;} comment, a multi-line string, a blank line, or a body of two or
 * more statements never does.</li>
 * <li>Otherwise it breaks according to its operator's {@link Style} (see
 * {@link IndentRules}), which decides how many arguments stay on the operator's line and
 * where the lines after it are indented to.</li>
 * <li>Within a broken form, a body or a run of clauses gets a line per element; arguments
 * and literal elements are PACKED -- each shares the line while it fits and takes one of
 * its own when it does not.</li>
 * </ol>
 * What is deliberately NOT touched: the spelling of any token (case included --
 * {@code Foo} stays {@code Foo}), the contents of strings and block comments, and which
 * comments exist. A comment that started its own line keeps one; one that trailed code
 * stays on that code's line.
 * <p>
 * Blank lines are preserved wherever they appeared, collapsed to exactly one. This is a
 * deliberate divergence from trivial-formatter, which drops blank lines inside a form and
 * forces one between top-level forms: the blank line is the only paragraph break Lisp
 * source has, and re-deciding it is not a layout question the formatter can answer.
 * <p>
 * Formatting is idempotent, and the token stream of the result is identical to the token
 * stream of the input -- both are pinned by {@code LispFormatterTest}.
 */
public final class LispFormatter {

	/** The right margin the formatter wraps to unless another is given. */
	public static final int DEFAULT_WIDTH = 80;

	private static final Set<String> LOOP_CLAUSE_KEYWORDS = Set.of("named", "with", "for", "as", "and", "repeat",
			"initially", "finally", "do", "doing", "return", "collect", "collecting", "append", "appending", "nconc",
			"nconcing", "count", "counting", "sum", "summing", "maximize", "maximizing", "minimize", "minimizing",
			"thereis", "always", "never", "while", "until", "when", "unless", "if", "else", "end");

	private final int width;

	private final StringBuilder out = new StringBuilder();

	private final Map<CstNode, Optional<String>> flatCache = new IdentityHashMap<>();

	private final Map<CstNode, Extent> narrowestCache = new IdentityHashMap<>();

	/** Offsets in {@link #out} at which a trailing comment starts. */
	private final List<Integer> trailingCommentStarts = new ArrayList<>();

	/**
	 * Whether the last thing written was a {@code ;} comment. Nothing may follow one on
	 * the same line -- not even a closing paren -- so every writer checks this first.
	 */
	private boolean afterLineComment;

	private LispFormatter(int width) {
		this.width = width;
	}

	/**
	 * Format the given source to the default margin.
	 * @param source the Lisp source
	 * @return the formatted source, ending in a newline (empty for empty input)
	 * @throws FormatException if the source cannot be read as Lisp
	 */
	public static String format(String source) {
		return format(source, DEFAULT_WIDTH);
	}

	/**
	 * Format the given source.
	 * @param source the Lisp source
	 * @param width the right margin to wrap to
	 * @return the formatted source, ending in a newline (empty for empty input)
	 * @throws FormatException if the source cannot be read as Lisp
	 */
	public static String format(String source, int width) {
		// CRLF and a lone CR are normalized away; the output is LF-only.
		String normalized = source.indexOf('\r') < 0 ? source : source.replace("\r\n", "\n").replace('\r', '\n');
		LispFormatter formatter = new LispFormatter(width);
		formatter.renderTopLevel(new FormatReader(normalized).readAll());
		return formatter.alignTrailingComments();
	}

	private void renderTopLevel(List<CstNode> nodes) {
		boolean first = true;
		for (CstNode node : nodes) {
			if (first) {
				first = false;
			}
			else if (staysOnPreviousLine(node)) {
				if (node instanceof CstNode.LineComment) {
					emitTrailingComment(node);
					continue;
				}
				emit(" ");
			}
			else {
				breakLine(0, node.trivia().blankLineBefore());
			}
			render(node, column(), null, 0);
		}
		if (!this.out.isEmpty()) {
			this.out.append('\n');
		}
	}

	/**
	 * Writes one node.
	 * @param node the node
	 * @param indent the column the node starts at
	 * @param override the style forced onto it by its parent, or {@code null}
	 * @param closers how many closing parens will be written directly after it. Every
	 * width decision has to reserve room for them: a form that ends a form that ends a
	 * form pays for three characters it never emitted itself, and forgetting that is how
	 * a formatter comes to produce lines exactly one or two columns over the margin.
	 */
	private void render(CstNode node, int indent, @Nullable Style override, int closers) {
		switch (node) {
			case CstNode.Atom atom -> emit(atom.text());
			case CstNode.BlockComment comment -> emit(comment.text());
			case CstNode.LineComment comment -> {
				emit(comment.text());
				this.afterLineComment = true;
			}
			case CstNode.Prefix prefix -> renderPrefix(prefix, indent, override, closers);
			case CstNode.Listing listing -> renderListing(listing, indent, override, closers);
		}
	}

	/**
	 * Writes a reader macro and its datum. {@code '}, {@code `}, {@code ,}, {@code ,@},
	 * {@code #'}, {@code #.} and {@code #n=} are one to three characters wide and must
	 * stay glued to their datum. A {@code #+feature} guard is different: it can be as
	 * wide as {@code #-(or ccl (and ecl little-endian) (and sbcl little-endian))}, and
	 * gluing that to its form starts the form near the margin and pushes every line of it
	 * past. So a guarded form that does not fit on one line gets the line below the
	 * guard, at the guard's own indent -- which is how the idiom is written by hand, and
	 * what trivial-formatter does unconditionally.
	 * @param prefix the prefixed node
	 * @param indent the column the prefix starts at
	 * @param override the style forced onto the datum, or {@code null}
	 * @param closers how many closing parens follow the datum
	 */
	private void renderPrefix(CstNode.Prefix prefix, int indent, @Nullable Style override, int closers) {
		String text = prefix.prefix();
		boolean guard = isFeatureGuard(text);
		String flat = flat(prefix);
		if (guard && (flat == null || !fits(indent, flat.length(), closers))) {
			emit(text.stripTrailing());
			breakLine(indent, false);
			render(prefix.datum(), indent, override, closers);
			return;
		}
		emit(text);
		render(prefix.datum(), indent + text.length(), override, closers);
	}

	private void renderListing(CstNode.Listing listing, int indent, @Nullable Style override, int closers) {
		Style style = override != null ? override : IndentRules.styleFor(listing);
		String flat = flat(listing);
		if (flat != null && fits(indent, flat.length(), closers) && !isStatementSequence(listing, style)) {
			emit(flat);
			return;
		}
		emit(listing.open());
		// Everything nested one level deeper is followed by this listing's own paren too.
		int inner = closers + 1;
		int breakColumn = listing.items().isEmpty() ? indent : switch (style.kind()) {
			case CALL -> renderCall(listing, indent, style, inner);
			case DATA -> renderData(listing, indent, style, inner);
			case DO -> renderIteration(listing, indent, inner);
			case LOOP -> renderLoop(listing, indent, inner);
			case BODY, CLAUSES -> renderBody(listing, indent, style, style.inlineArgs(), style.bodyIndent(), inner);
			case DEFMETHOD ->
				renderBody(listing, indent, style, lambdaListIndex(listing.items()), style.bodyIndent(), inner);
		};
		if (this.afterLineComment) {
			breakLine(breakColumn, false);
		}
		emit(")");
	}

	/**
	 * Writes an operator, its distinguished arguments, and a body one form per line:
	 * {@code (operator arg ... }, then each body form at {@code bodyIndent}.
	 * @param listing the form
	 * @param indent the column the opening paren is at
	 * @param style the style being applied
	 * @param inlineArgs how many arguments stay on the operator's line
	 * @param bodyIndent how far the body is indented from the opening paren
	 * @param inner how many closing parens follow the last item
	 * @return the column the body lines start at
	 */
	private int renderBody(CstNode.Listing listing, int indent, Style style, int inlineArgs, int bodyIndent,
			int inner) {
		List<CstNode> items = listing.items();
		int breakColumn = indent + bodyIndent;
		// A distinguished argument that no longer fits on the operator's line takes a
		// line of
		// its own, indented PAST the body so it still reads as part of the header. Its
		// column
		// on the operator's line depends on how wide the arguments before it were, so
		// without
		// this a wide header -- (multiple-value-bind (value present) (gethash key table)
		// ...)
		// -- would start its last argument near the margin and push everything nested in
		// it
		// beyond.
		int headerColumn = breakColumn + 2;
		int index = 0;
		int inlineEnd = Math.min(items.size(), 1 + inlineArgs);
		boolean moved = false;
		for (; index < inlineEnd; index++) {
			CstNode item = items.get(index);
			if (item instanceof CstNode.LineComment) {
				break; // a comment claims the rest of the line
			}
			Style childStyle = IndentRules.childStyle(style, index);
			int itemClosers = closersAfter(items, index, inner);
			// Only from the SECOND distinguished argument on. The first one sits directly
			// after the operator, already as far left as the form allows, so moving it
			// down
			// buys at most the operator's own width and costs a line -- it would break up
			// (let* (BINDINGS) ...) for one column.
			if (index > 1 && (moved || movesToOwnLine(item, headerColumn, itemClosers))) {
				moved = true;
				breakLine(headerColumn, item.trivia().blankLineBefore());
				render(item, headerColumn, childStyle, itemClosers);
				continue;
			}
			if (index > 0) {
				emit(" ");
			}
			render(item, column(), childStyle, itemClosers);
		}
		Style clauseStyle = style.kind() == Style.Kind.CLAUSES ? style.childStyle() : null;
		layoutRun(items, index, items.size(), breakColumn, false, false, clauseStyle, inner);
		return breakColumn;
	}

	/**
	 * Writes a call: {@code (operator first-argument}, then the remaining arguments under
	 * the first one, with the trailing {@code :key value} pairs kept together and given a
	 * line each.
	 * @param listing the call
	 * @param indent the column the opening paren is at
	 * @param style the style being applied
	 * @param inner how many closing parens follow the last item
	 * @return the column the argument lines start at
	 */
	private int renderCall(CstNode.Listing listing, int indent, Style style, int inner) {
		List<CstNode> items = listing.items();
		render(items.get(0), column(), null, closersAfter(items, 0, inner));
		int breakColumn = argumentColumn(items, indent, inner);
		Style argumentStyle = style.childStyle();
		// Arguments pack: one shares the line when it fits and takes a line of its own
		// when it does not. cond's clauses are the exception -- they are alternatives,
		// and alternatives read as a column.
		layoutRun(items, 1, items.size(), breakColumn, argumentStyle == null, true, argumentStyle, inner);
		return breakColumn;
	}

	/**
	 * Writes a sequence with no operator: every element lines up just inside the opening
	 * delimiter.
	 * @param listing the sequence
	 * @param indent the column the opening delimiter is at
	 * @param style the style being applied
	 * @param inner how many closing parens follow the last element
	 * @return the column the element lines start at
	 */
	private int renderData(CstNode.Listing listing, int indent, Style style, int inner) {
		List<CstNode> items = listing.items();
		int breakColumn = indent + listing.open().length();
		// Elements pack, unless they have a forced shape -- a run of bindings or clauses
		// is a
		// column, a run of literals is a paragraph.
		boolean fill = style.childStyle() == null;
		CstNode first = items.get(0);
		if (first instanceof CstNode.LineComment && !first.trivia().startsLine()) {
			emitTrailingComment(first);
		}
		else if (first instanceof CstNode.LineComment) {
			breakLine(breakColumn, false);
			render(first, breakColumn, null, 0);
		}
		else {
			render(first, breakColumn, IndentRules.childStyle(style, 0), closersAfter(items, 0, inner));
		}
		// The first element was written above, so nothing here is allowed to overrun the
		// margin just to stay on the opening line.
		layoutRun(items, 1, items.size(), breakColumn, fill, false, style.childStyle(), inner);
		return breakColumn;
	}

	/**
	 * Writes {@code do}/{@code do*}: the variable list on the operator's line, the
	 * end-test clause on a line of its own indented past the body, then the body.
	 * @param listing the form
	 * @param indent the column the opening paren is at
	 * @param inner how many closing parens follow the last item
	 * @return the column the body lines start at
	 */
	private int renderIteration(CstNode.Listing listing, int indent, int inner) {
		List<CstNode> items = listing.items();
		int breakColumn = indent + 2;
		int index = 0;
		for (; index < Math.min(items.size(), 2); index++) {
			if (items.get(index) instanceof CstNode.LineComment) {
				break;
			}
			if (index > 0) {
				emit(" ");
			}
			Style childStyle = index == 1 ? Style.data(Style.operands(0, 1)) : null;
			render(items.get(index), column(), childStyle, closersAfter(items, index, inner));
		}
		if (index == 2 && index < items.size() && !(items.get(index) instanceof CstNode.LineComment)) {
			breakLine(indent + 4, items.get(index).trivia().blankLineBefore());
			render(items.get(index), indent + 4, Style.body(0, 1), closersAfter(items, index, inner));
			index++;
		}
		layoutRun(items, index, items.size(), breakColumn, false, false, null, inner);
		return breakColumn;
	}

	/**
	 * Writes the extended {@code loop}: one line per clause, aligned under the first
	 * clause, with each clause's own subforms packed after its keyword. A {@code loop}
	 * whose second element is not a loop keyword is a simple {@code loop} -- a plain
	 * body.
	 * @param listing the form
	 * @param indent the column the opening paren is at
	 * @param inner how many closing parens follow the last item
	 * @return the column the clause lines start at
	 */
	private int renderLoop(CstNode.Listing listing, int indent, int inner) {
		List<CstNode> items = listing.items();
		render(items.get(0), column(), null, closersAfter(items, 0, inner));
		if (items.size() < 2 || !isLoopKeyword(items.get(1))) {
			layoutRun(items, 1, items.size(), indent + 2, false, false, null, inner);
			return indent + 2;
		}
		int breakColumn = column() + 1;
		int index = 1;
		while (index < items.size()) {
			CstNode head = items.get(index);
			if (head instanceof CstNode.LineComment && !head.trivia().startsLine()) {
				emitTrailingComment(head);
				index++;
				continue;
			}
			if (index == 1 && !this.afterLineComment) {
				emit(" ");
			}
			else {
				breakLine(breakColumn, head.trivia().blankLineBefore());
			}
			render(head, column(), null, closersAfter(items, index, inner));
			// A clause runs up to the next clause keyword; its subforms pack after it.
			int end = index + 1;
			while (end < items.size() && !isLoopKeyword(items.get(end))
					&& !(items.get(end) instanceof CstNode.LineComment)) {
				end++;
			}
			layoutRun(items, index + 1, end, breakColumn + 2, true, true, null, inner);
			index = end;
		}
		return breakColumn;
	}

	/**
	 * Whether a distinguished argument is better off on a line of its own. Only when
	 * moving it there actually earns something: it does not fit where it is, and it does
	 * fit there. An argument too big for either place stays on the operator's line and
	 * breaks inside itself, which keeps {@code (when (and ...) ...)} reading as one
	 * condition instead of stranding {@code when} alone on a line.
	 * @param item the argument
	 * @param headerColumn the column it would move to
	 * @param closers how many closing parens follow it
	 * @return {@code true} if it should move
	 */
	private boolean movesToOwnLine(CstNode item, int headerColumn, int closers) {
		String flat = flat(item);
		return flat != null && !fits(column() + 1, flat.length(), closers)
				&& fits(headerColumn, flat.length(), closers);
	}

	/**
	 * The column a call's arguments line up in: just past the operator, so they sit under
	 * the first one.
	 * <p>
	 * That is the Lisp convention, but it is a convention about reading, and it gives way
	 * when it stops being possible to read: a long operator name, or a call already
	 * nested deep, can leave the alignment column with less room than the arguments need,
	 * and then EVERY argument line overruns the margin with no way to recover. When that
	 * happens and the shallowest column would not, the arguments go there instead. The
	 * condition is written as "would the fallback actually help", so nothing moves unless
	 * moving fixes something.
	 * @param items the call's items, with the operator at index 0
	 * @param indent the column the opening paren is at
	 * @param inner how many closing parens follow the last argument
	 * @return the column to align the arguments in
	 */
	private int argumentColumn(List<CstNode> items, int indent, int inner) {
		int aligned = column() + 1;
		int shallow = indent + 1;
		// With a single argument there is nothing to align it WITH, so the alignment
		// column
		// buys only depth: if the argument does not fit on the operator's line, it is
		// better
		// off starting at the shallowest column, and every call nested inside it inherits
		// the
		// room instead of the deficit.
		if (items.size() == 2) {
			return fits(aligned, flatWidth(items.get(1)), inner) ? aligned : shallow;
		}
		int widest = 0;
		boolean overflows = false;
		for (int index = 1; index < items.size(); index++) {
			boolean pair = pairStartsAt(items, index, items.size());
			int closers = closersAfter(items, pair ? index + 1 : index, inner);
			int unit = pair ? pairWidth(items, index) : flatWidth(items.get(index));
			if (unit >= 0 && fits(shallow, unit, closers)) {
				widest = Math.max(widest, unit + closers);
			}
			else if (unit >= 0) {
				// An argument too wide for either column wraps wherever it starts, so it
				// cannot say which column the OTHERS should get. It does speak for itself
				// -- it is the one that will overrun the margin -- but on the same terms
				// as every other "move it elsewhere" rule here: only when the alignment
				// is what puts it over and the shallow column takes it back under. One
				// merely too wide FLAT is comfortable in either column once broken and
				// gains nothing; one too wide even at the shallow column is not rescued,
				// only spread over more lines that are still over.
				Extent extent = pair ? pairNarrowest(items, index) : narrowest(items.get(index));
				overflows |= !fitsNarrowest(aligned, extent, closers) && fitsNarrowest(shallow, extent, closers);
			}
			if (pair) {
				index++;
			}
		}
		boolean overruns = overflows || (widest > 0 && aligned + widest > this.width);
		return overruns && shallow < aligned ? shallow : aligned;
	}

	/**
	 * Writes {@code items[from..to)} after whatever is already on the current line.
	 * @param items the enclosing listing's items
	 * @param from the first index to write
	 * @param to one past the last index to write
	 * @param breakColumn the column a new line is indented to
	 * @param fill whether several items may share a line
	 * @param firstOnCurrentLine whether item {@code from} continues the current line even
	 * when it does not fit -- true only for a call's first argument, where
	 * {@code (some-function (a-long-argument that-overflows))} has to break INSIDE the
	 * argument rather than leave the operator alone on its line
	 * @param childStyle the style to force onto each item, or {@code null}
	 * @param inner how many closing parens follow the last item
	 */
	private void layoutRun(List<CstNode> items, int from, int to, int breakColumn, boolean fill,
			boolean firstOnCurrentLine, @Nullable Style childStyle, int inner) {
		for (int index = from; index < to; index++) {
			CstNode item = items.get(index);
			boolean comment = item instanceof CstNode.LineComment;
			if (comment && !item.trivia().startsLine() && !this.afterLineComment) {
				emitTrailingComment(item);
				continue;
			}
			boolean pair = !comment && pairStartsAt(items, index, to);
			int closers = closersAfter(items, pair ? index + 1 : index, inner);
			int unitWidth = pair ? pairWidth(items, index) : flatWidth(item);
			boolean sameLine;
			if (this.afterLineComment || comment || item.trivia().blankLineBefore()) {
				sameLine = false;
			}
			else if (pair) {
				// One option per line: a column of options reads far better than a
				// paragraph of them. Only the first argument shares the operator's line,
				// and on the same terms as any other first argument.
				sameLine = index == from && firstOnCurrentLine
						&& (fits(column() + 1, unitWidth, closers) || breakColumn >= column() + 1);
			}
			else if (index == from && firstOnCurrentLine) {
				// The first argument keeps the operator company so the operator is never
				// stranded alone on a line -- unless it has to break anyway AND the break
				// column is further left, in which case company costs it room it needs.
				sameLine = fits(column() + 1, unitWidth, closers) || breakColumn >= column() + 1;
			}
			else if (!fill) {
				sameLine = false;
			}
			else {
				sameLine = fits(column() + 1, unitWidth, closers);
			}
			if (sameLine) {
				emit(" ");
			}
			else {
				breakLine(breakColumn, item.trivia().blankLineBefore());
			}
			render(item, column(), pair ? null : childStyle, pair ? 0 : closers);
			if (pair) {
				renderPairValue(items.get(index + 1), breakColumn, childStyle, closers);
				index++;
			}
		}
	}

	/**
	 * Writes the value half of a {@code :key value} pair, which has just had its key
	 * written.
	 * <p>
	 * It stays on the key's line when it fits -- that is the whole point of the pairing.
	 * When it does not, the choice is between a line of its own and breaking inside
	 * itself on the key's line, and the test is whether a line of its own would ACTUALLY
	 * hold it: a plist value like
	 * {@code :depends-on ("alexandria" "bordeaux-threads" ...)} is far too wide either
	 * way, so it belongs beside its key and wraps under its own first element, while a
	 * value that does fit on the next line reads better there than split open.
	 * @param value the value node
	 * @param breakColumn the column a new line is indented to
	 * @param childStyle the style to force onto it, or {@code null}
	 * @param closers how many closing parens follow it
	 */
	private void renderPairValue(CstNode value, int breakColumn, @Nullable Style childStyle, int closers) {
		int column = column() + 1;
		boolean beside = fits(column, flatWidth(value), closers)
				|| (fits(column, firstLineWidth(value), 0) && column <= this.width / 2);
		if (beside) {
			emit(" ");
			render(value, column, childStyle, closers);
			return;
		}
		breakLine(breakColumn, false);
		render(value, breakColumn, childStyle, closers);
	}

	/**
	 * The width of the SHORTEST first line the node can be written on: its opening
	 * delimiters plus its first element, all the way down. It is what answers "can this
	 * even START here", which is a different question from whether it fits -- a plist
	 * value like {@code ("alexandria" "bordeaux-threads" ...)} never fits on one line
	 * anywhere, but it begins in thirteen columns and so belongs beside its key, wrapping
	 * under its own first element.
	 * @param node the node
	 * @return the width of its shortest possible first line
	 */
	private int firstLineWidth(CstNode node) {
		return switch (node) {
			case CstNode.Atom atom -> atom.text().length();
			case CstNode.LineComment comment -> comment.text().length();
			case CstNode.BlockComment comment -> comment.text().length();
			case CstNode.Prefix prefix -> prefix.prefix().length() + firstLineWidth(prefix.datum());
			case CstNode.Listing listing ->
				listing.open().length() + (listing.items().isEmpty() ? 1 : firstLineWidth(listing.items().get(0)));
		};
	}

	/**
	 * The shape of a node's NARROWEST rendering, both measured from the column it starts
	 * in: how wide its widest line is, and how wide its last line is. The two are
	 * separate questions because everything written after the node -- the closing parens
	 * of every form it ends -- lands on the last line and nowhere else, and charging them
	 * to the widest line is how a two-column error creeps in.
	 *
	 * @param widest the width of its widest line
	 * @param last the width of its last line
	 */
	private record Extent(int widest, int last) {
	}

	/**
	 * How narrow a node can be made: the shape it has once it has been broken as far as
	 * this formatter can break it. Where {@link #flatWidth} answers "does it fit on one
	 * line", this answers "is there a line it fits on at all" -- which is what tells an
	 * argument too wide for its column because of how far it is INDENTED from one too
	 * wide because of what it contains. An unbreakable 54-column string overruns from any
	 * deep column; a {@code (prog1 (schar ...) (incf ...))} clause fits in every column
	 * once it breaks.
	 * <p>
	 * Both numbers are under-estimates: each element is charged the shallowest offset any
	 * style could give it, one column past the opening paren, where the real style may
	 * indent it further. No rendering is narrower than this, and nothing may treat it as
	 * a rendering that exists.
	 * @param node the node
	 * @return a lower bound on the shape of its narrowest rendering
	 */
	private Extent narrowest(CstNode node) {
		Extent cached = this.narrowestCache.get(node);
		if (cached == null) {
			cached = computeNarrowest(node);
			this.narrowestCache.put(node, cached);
		}
		return cached;
	}

	private Extent computeNarrowest(CstNode node) {
		return switch (node) {
			// Only a node that HAS a one-line rendering is ever measured this way, so a
			// token here is a single line by construction; the split is for safety.
			case CstNode.Atom atom -> lineExtent(atom.text());
			case CstNode.LineComment comment -> lineExtent(comment.text());
			case CstNode.BlockComment comment -> lineExtent(comment.text());
			case CstNode.Prefix prefix -> {
				Extent datum = narrowest(prefix.datum());
				// A #+feature guard is the one prefix that can hand its datum the whole
				// line below; every other one shifts the datum right by its own width.
				yield isFeatureGuard(prefix.prefix())
						? new Extent(Math.max(prefix.prefix().stripTrailing().length(), datum.widest()), datum.last())
						: new Extent(prefix.prefix().length() + datum.widest(),
								prefix.prefix().length() + datum.last());
			}
			case CstNode.Listing listing -> {
				List<CstNode> items = listing.items();
				int open = listing.open().length();
				if (items.isEmpty()) {
					yield new Extent(open + 1, open + 1);
				}
				int widest = open;
				for (int index = 0; index < items.size(); index++) {
					// The first element always follows the opener; every other one may
					// start as far left as one column past it.
					int offset = index == 0 ? open : 1;
					widest = Math.max(widest, offset + narrowest(items.get(index)).widest());
				}
				int last = (items.size() == 1 ? open : 1) + narrowest(items.getLast()).last() + 1;
				yield new Extent(Math.max(widest, last), last);
			}
		};
	}

	/**
	 * Whether a node's narrowest rendering fits at a column: no line of it over the
	 * margin, and room left on its last line for the closing parens that follow it.
	 * @param column the column it would start at
	 * @param extent the shape of its narrowest rendering
	 * @param closers how many closing parens follow it
	 * @return {@code true} if some rendering of it could fit there
	 */
	private boolean fitsNarrowest(int column, Extent extent, int closers) {
		return column + extent.widest() <= this.width && column + extent.last() + closers <= this.width;
	}

	private static Extent lineExtent(String text) {
		int first = text.indexOf('\n');
		return first < 0 ? new Extent(text.length(), text.length())
				: new Extent(first, text.length() - text.lastIndexOf('\n') - 1);
	}

	private static boolean isFeatureGuard(String prefix) {
		return prefix.startsWith("#+") || prefix.startsWith("#-");
	}

	/**
	 * Whether a {@code :key value} pair starts at the given index. A pair is laid out as
	 * one unit -- it starts a line of its own even in a packed run, because a column of
	 * options reads far better than a paragraph of them. A comment in either half
	 * disables the grouping, since a comment ends its line.
	 * @param items the enclosing listing's items
	 * @param index the candidate key's index
	 * @param to one past the last index being laid out
	 * @return {@code true} if the two items should be written as one unit
	 */
	private static boolean pairStartsAt(List<CstNode> items, int index, int to) {
		return index + 1 < to && isKeyword(items.get(index)) && !(items.get(index + 1) instanceof CstNode.LineComment);
	}

	/**
	 * The width of a node's one-line rendering, or -1 when it has none.
	 * @param node the node
	 * @return the width, or -1
	 */
	private int flatWidth(CstNode node) {
		String flat = flat(node);
		return flat != null ? flat.length() : -1;
	}

	/**
	 * How many closing parens are written directly after the item at the given index: the
	 * enclosing listing's own, plus everything the enclosing listing itself closes, but
	 * only for the LAST item -- any earlier one is followed by a space and more content.
	 * @param items the enclosing listing's items
	 * @param index the item's index
	 * @param inner the count that follows the last item
	 * @return the count that follows this item
	 */
	private static int closersAfter(List<CstNode> items, int index, int inner) {
		return index == items.size() - 1 ? inner : 0;
	}

	/**
	 * Whether a one-line rendering of the given width fits at a column, with room left
	 * for the closing parens that follow it.
	 * @param indent the column it would start at
	 * @param flatWidth the width of the one-line rendering
	 * @param closers how many closing parens follow it
	 * @return {@code true} if it fits
	 */
	private boolean fits(int indent, int flatWidth, int closers) {
		return flatWidth >= 0 && indent + flatWidth + closers <= this.width;
	}

	/**
	 * Whether this form's trailing arguments are two or more STATEMENTS, in which case
	 * they get a line each however short they are and the form never collapses onto one
	 * line. The rule is the Lisp reading of what every formatter of a C-like language
	 * does with {@code if (x) { a(); b(); }}: a sequence performed in order is a sequence
	 * of lines. It is also what keeps this formatter's output stable against the margin
	 * -- a two-form body does not silently join when a rename makes it two characters
	 * shorter.
	 * @param listing the form
	 * @param style the style it will be laid out with
	 * @return {@code true} if the form must break
	 */
	private static boolean isStatementSequence(CstNode.Listing listing, Style style) {
		if (!style.statements()) {
			return false;
		}
		if (style.kind() == Style.Kind.DO) {
			// (do (bindings) (end-test result ...) body ...) -- three distinct parts, and
			// the layout is what tells them apart. One with a body is never a one-liner
			// however short it is; one without has nothing to separate.
			return listing.items().size() > 3;
		}
		int inlineArgs = style.kind() == Style.Kind.DEFMETHOD ? lambdaListIndex(listing.items()) : style.inlineArgs();
		return listing.items().size() - 1 - inlineArgs >= 2;
	}

	/**
	 * The one-line rendering of a node, or {@code null} when it has none: a {@code ;}
	 * comment ends its line, a multi-line string or block comment carries its own line
	 * breaks, and a blank line the author left inside a form is preserved rather than
	 * flattened away. Any of the three makes every enclosing form break too.
	 * @param node the node
	 * @return the one-line rendering, or {@code null}
	 */
	private @Nullable String flat(CstNode node) {
		Optional<String> cached = this.flatCache.get(node);
		if (cached == null) {
			cached = Optional.ofNullable(computeFlat(node));
			this.flatCache.put(node, cached);
		}
		return cached.orElse(null);
	}

	private @Nullable String computeFlat(CstNode node) {
		return switch (node) {
			case CstNode.LineComment _ -> null;
			case CstNode.Atom atom -> atom.text().indexOf('\n') < 0 ? atom.text() : null;
			case CstNode.BlockComment comment -> comment.text().indexOf('\n') < 0 ? comment.text() : null;
			case CstNode.Prefix prefix -> {
				String datum = flat(prefix.datum());
				yield datum != null ? prefix.prefix() + datum : null;
			}
			case CstNode.Listing listing -> {
				// A form that must break has no one-line form AT ALL, not merely one this
				// caller declines to use: otherwise an enclosing form that does fit would
				// flatten it from above, and (defun f (x) (when x (a) (b))) would
				// collapse
				// whole even though its own when may not.
				if (isStatementSequence(listing, IndentRules.styleFor(listing))) {
					yield null;
				}
				StringBuilder flat = new StringBuilder(listing.open());
				boolean first = true;
				for (CstNode item : listing.items()) {
					String itemFlat = flat(item);
					if (itemFlat == null || item.trivia().blankLineBefore()) {
						yield null;
					}
					if (!first) {
						flat.append(' ');
					}
					flat.append(itemFlat);
					first = false;
				}
				yield flat.append(')').toString();
			}
		};
	}

	/**
	 * The one-line width of the {@code :key value} pair starting at the given index, or
	 * -1 when either half has no one-line form.
	 * @param items the call's items
	 * @param index the key's index
	 * @return the pair's width, or -1
	 */
	private int pairWidth(List<CstNode> items, int index) {
		String key = flat(items.get(index));
		String value = flat(items.get(index + 1));
		return key != null && value != null ? key.length() + 1 + value.length() : -1;
	}

	/**
	 * The narrowest the {@code :key value} pair starting at the given index can be made.
	 * The value goes below its key, at the key's own column, when it will not fit beside
	 * it, so the pair is as wide as its wider half and ends where its value ends.
	 * @param items the call's items
	 * @param index the key's index
	 * @return a lower bound on the shape of the pair's narrowest rendering
	 */
	private Extent pairNarrowest(List<CstNode> items, int index) {
		Extent key = narrowest(items.get(index));
		Extent value = narrowest(items.get(index + 1));
		return new Extent(Math.max(key.widest(), value.widest()), value.last());
	}

	private static boolean isKeyword(CstNode node) {
		return node instanceof CstNode.Atom atom && atom.text().length() > 1 && atom.text().charAt(0) == ':';
	}

	/**
	 * The index of a {@code defmethod}'s specialized lambda list: the first list argument
	 * at or after index 2, so that both {@code (defmethod foo ((x t)) ...)} and
	 * {@code (defmethod foo :around ((x t)) ...)} keep it on the first line. Index 1 is
	 * skipped because the name itself may be a list, as in {@code (setf foo)}.
	 * @param items the form's items
	 * @return the number of arguments that stay on the first line
	 */
	private static int lambdaListIndex(List<CstNode> items) {
		for (int index = 2; index < items.size(); index++) {
			if (items.get(index) instanceof CstNode.Listing) {
				return index;
			}
		}
		return 2;
	}

	private static boolean isLoopKeyword(CstNode node) {
		if (!(node instanceof CstNode.Atom atom)) {
			return false;
		}
		String name = atom.text().toLowerCase(Locale.ROOT);
		return LOOP_CLAUSE_KEYWORDS.contains(name.startsWith(":") ? name.substring(1) : name);
	}

	private boolean staysOnPreviousLine(CstNode node) {
		return !this.afterLineComment && !node.trivia().startsLine()
				&& (node instanceof CstNode.LineComment || node instanceof CstNode.BlockComment);
	}

	/**
	 * Writes a comment that trails code on the same line, one space after it, and
	 * remembers where it began so {@link #alignTrailingComments()} can line it up with
	 * its neighbours.
	 * @param comment the comment
	 */
	private void emitTrailingComment(CstNode comment) {
		emit(" ");
		this.trailingCommentStarts.add(this.out.length());
		render(comment, column(), null, 0);
	}

	/**
	 * Lines up the trailing comments of every run of consecutive lines that has one,
	 * exactly as gofmt does. The alignment an author writes by hand is real information
	 * -- a column of comments annotating a column of forms -- but it cannot survive
	 * re-indentation, so the formatter re-establishes it instead of preserving it.
	 * <p>
	 * This runs on the finished text rather than during emission because the target
	 * column is not known until every line of the run has been written. It stays
	 * idempotent: the code before each comment is unchanged by a second pass, so the same
	 * runs get the same target column.
	 * @return the formatted text
	 */
	private String alignTrailingComments() {
		if (this.trailingCommentStarts.isEmpty()) {
			return this.out.toString();
		}
		String text = this.out.toString();
		int count = this.trailingCommentStarts.size();
		int[] line = new int[count];
		int[] column = new int[count];
		int lineNumber = 0;
		int lineStart = 0;
		int next = 0;
		for (int index = 0; index < text.length() && next < count; index++) {
			if (index == this.trailingCommentStarts.get(next)) {
				line[next] = lineNumber;
				column[next] = index - lineStart;
				next++;
			}
			if (text.charAt(index) == '\n') {
				lineNumber++;
				lineStart = index + 1;
			}
		}
		// A run is a maximal set of consecutive lines that all end in a trailing comment;
		// every comment in it moves out to the widest one's column.
		int[] target = new int[count];
		for (int start = 0; start < count;) {
			int end = start + 1;
			while (end < count && line[end] == line[end - 1] + 1) {
				end++;
			}
			int widest = 0;
			for (int index = start; index < end; index++) {
				widest = Math.max(widest, column[index]);
			}
			for (int index = start; index < end; index++) {
				target[index] = widest;
			}
			start = end;
		}
		StringBuilder aligned = new StringBuilder(text.length() + count * 4);
		int copied = 0;
		for (int index = 0; index < count; index++) {
			int start = this.trailingCommentStarts.get(index);
			aligned.append(text, copied, start).append(" ".repeat(target[index] - column[index]));
			copied = start;
		}
		return aligned.append(text, copied, text.length()).toString();
	}

	private void emit(String text) {
		this.out.append(text);
		this.afterLineComment = false;
	}

	private void breakLine(int column, boolean blankLineBefore) {
		while (!this.out.isEmpty() && this.out.charAt(this.out.length() - 1) == ' ') {
			this.out.setLength(this.out.length() - 1);
		}
		this.out.append('\n');
		if (blankLineBefore) {
			this.out.append('\n');
		}
		this.out.append(" ".repeat(column));
		this.afterLineComment = false;
	}

	private int column() {
		return this.out.length() - this.out.lastIndexOf("\n") - 1;
	}

}
