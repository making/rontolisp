package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's native arm for {@code search} and {@code mismatch}, which are
 * Lisp-source {@code defun}s in {@link LispPreludeLibrary} on every backend.
 *
 * <p>
 * Both prelude bodies read their operands with {@code (elt seq i)} and compare with
 * {@code (funcall test ...)}, so on the tree-walking interpreter each element PAIR costs
 * two {@code elt} calls plus a {@code funcall} -- ~2.5 us -- and {@code search}'s O(n*m)
 * double loop turned a five-character needle in a 46-character string into 104 us a call,
 * 209x {@code (char s 3)}. The same source compiles to 0.9 us (JVM) and 1.4 us (both WASM
 * backends), so the defect is the interpreter's per-node cost multiplied by n*m, not the
 * algorithm. This arm answers the same call in 0.50 us; see
 * {@code .kb/seq-coerce-runtime.md} for the full table.
 *
 * <p>
 * This is a DECLINING fast arm in the shape {@code .kb/binary-sequence-io.md} records: it
 * answers only the calls it can prove answer identically to the prelude body, and returns
 * {@code null} for everything else so the prelude {@code defun} runs unchanged --
 * including for the shapes whose prelude answer is an error, an oddity, or a deliberate
 * CL deviation. Nothing here approximates.
 *
 * <p>
 * What it declines, and why each decline is the prelude's business:
 * <ul>
 * <li><b>{@code :key}</b> (supplied non-nil) and a <b>{@code :test} that is not provably
 * {@code eql} on these elements</b> -- the comparison would have to call back into the
 * evaluator per element, which is the cost this arm exists to avoid.</li>
 * <li><b>A bounding index outside its sequence</b>, or {@code start > end} -- the
 * prelude's {@code elt} decides whether that signals, truncates or answers something odd,
 * and it does so per call shape.</li>
 * <li><b>Anything {@code (length seq)} does not measure the way this arm does</b> -- a
 * dotted list, a rank-2 array, a non-sequence.</li>
 * <li><b>{@code mismatch} with {@code :from-end}</b> -- the prelude accepts the keyword
 * and IGNORES it, scanning forward. That is a deliberate, documented deviation from CLHS
 * (the "Lite" note on the {@code mismatch} reference page); reproducing it here would
 * spread it to a second implementation.</li>
 * <li>An unknown, duplicated or malformed keyword argument.</li>
 * </ul>
 */
final class SequenceScanFast {

	private SequenceScanFast() {
	}

	/**
	 * Answers {@code (search seq1 seq2 ...)} natively, or {@code null} to decline.
	 * @param args the evaluated argument list, exactly as the call supplied it
	 * @return the index of the leftmost (or, under {@code :from-end}, rightmost) match,
	 * {@code nil} when there is none, or {@code null} when this arm declines
	 */
	static @Nullable LispVal search(List<LispVal> args) {
		Call call = parse(args);
		if (call == null) {
			return null;
		}
		int width = call.end1() - call.start1();
		int found = -1;
		// The prelude's outer do loop tests BEFORE the body and stops as soon as a match
		// is in hand unless :from-end asked for the rightmost one.
		for (int pos = call.start2(); pos + width <= call.end2(); pos++) {
			if (found >= 0 && !call.fromEnd()) {
				break;
			}
			boolean matches = true;
			for (int i = 0; i < width && matches; i++) {
				matches = sameElement(call.first(), call.start1() + i, call.second(), pos + i);
			}
			if (matches) {
				found = pos;
			}
		}
		return found < 0 ? LispNil.INSTANCE : new LispInteger(found);
	}

	/**
	 * Answers {@code (mismatch seq1 seq2 ...)} natively, or {@code null} to decline.
	 * @param args the evaluated argument list, exactly as the call supplied it
	 * @return the index into {@code seq1} of the first mismatch, {@code nil} when the two
	 * bounded subsequences are element-for-element equal, or {@code null} when this arm
	 * declines
	 */
	static @Nullable LispVal mismatch(List<LispVal> args) {
		Call call = parse(args);
		// The prelude ignores :from-end rather than scanning backwards; that answer stays
		// its own (see the class comment).
		if (call == null || call.fromEnd()) {
			return null;
		}
		int i = call.start1();
		int j = call.start2();
		while (true) {
			if (i >= call.end1() && j >= call.end2()) {
				return LispNil.INSTANCE;
			}
			if (i >= call.end1() || j >= call.end2()) {
				return new LispInteger(i);
			}
			if (!sameElement(call.first(), i, call.second(), j)) {
				return new LispInteger(i);
			}
			i++;
			j++;
		}
	}

	/**
	 * A call this arm has proved it can serve: both operands as indexable element
	 * sources, the four bounding indices resolved, and {@code :from-end}'s truth.
	 */
	private record Call(Elements first, Elements second, int start1, int end1, int start2, int end2, boolean fromEnd) {
	}

	// Reads the call, or answers null to decline it. Everything this arm needs to know is
	// decided here, so both operators share one set of decline rules.
	private static @Nullable Call parse(List<LispVal> args) {
		if (args.size() < 2 || (args.size() - 2) % 2 != 0) {
			return null;
		}
		LispVal start1 = null;
		LispVal end1 = null;
		LispVal start2 = null;
		LispVal end2 = null;
		LispVal test = null;
		LispVal fromEnd = null;
		for (int i = 2; i < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol keyword) || !keyword.isKeyword()) {
				return null;
			}
			LispVal value = args.get(i + 1);
			// A duplicate is legal in CL (the leftmost wins) but pathological; the
			// prelude lambda list may bind it either way, so it is declined, not guessed.
			switch (keyword.name()) {
				case ":START1" -> {
					if (start1 != null) {
						return null;
					}
					start1 = value;
				}
				case ":END1" -> {
					if (end1 != null) {
						return null;
					}
					end1 = value;
				}
				case ":START2" -> {
					if (start2 != null) {
						return null;
					}
					start2 = value;
				}
				case ":END2" -> {
					if (end2 != null) {
						return null;
					}
					end2 = value;
				}
				case ":TEST" -> {
					if (test != null) {
						return null;
					}
					test = value;
				}
				case ":FROM-END" -> {
					if (fromEnd != null) {
						return null;
					}
					fromEnd = value;
				}
				case ":KEY" -> {
					// The prelude's (if key ...) reads nil as "no key", so only a
					// supplied NON-NIL key declines.
					if (!(value instanceof LispNil)) {
						return null;
					}
				}
				default -> {
					return null;
				}
			}
		}
		Elements first = elementsOf(args.get(0));
		Elements second = elementsOf(args.get(1));
		if (first == null || second == null || !servesTest(test, first, second)) {
			return null;
		}
		// A start defaults to 0 only when ABSENT: the prelude's lambda list binds an
		// explicit :start1 nil to nil, whose (+ start1 i) is the prelude's own error. An
		// end is or-defaulted inside the body, so nil there IS the default.
		int s1 = start1 == null ? 0 : boundingIndex(start1);
		int e1 = end1 == null || end1 instanceof LispNil ? first.length() : boundingIndex(end1);
		int s2 = start2 == null ? 0 : boundingIndex(start2);
		int e2 = end2 == null || end2 instanceof LispNil ? second.length() : boundingIndex(end2);
		if (s1 < 0 || e1 < 0 || s2 < 0 || e2 < 0) {
			return null;
		}
		// Out of range is declined whole: what the prelude does there depends on which
		// elt call it reaches first, and that is call-shape specific.
		if (s1 > e1 || e1 > first.length() || s2 > e2 || e2 > second.length()) {
			return null;
		}
		return new Call(first, second, s1, e1, s2, e2, fromEnd != null && !(fromEnd instanceof LispNil));
	}

	// A bounding index as a non-negative int, or -1 for anything else (nil, a float, a
	// bignum, a negative) -- all of which the prelude's arithmetic and elt own.
	private static int boundingIndex(LispVal value) {
		if (value instanceof LispInteger n && n.value() >= 0 && n.value() <= Integer.MAX_VALUE) {
			return (int) n.value();
		}
		return -1;
	}

	// Whether the comparison this arm performs -- eql -- is the one the call asked for.
	// The default (an absent :test) IS eql. A supplied one is served only when it is
	// provably the same predicate on the elements this call will compare: #'eql always,
	// and #'char= when BOTH operands are strings, since every element is then a character
	// and char= on two characters is code-point equality, which is what eql answers for
	// a LispChar. A user function, a lambda, and an explicit :test nil (whose funcall is
	// the prelude's error) all decline.
	private static boolean servesTest(@Nullable LispVal test, Elements first, Elements second) {
		if (test == null) {
			return true;
		}
		if (!(test instanceof LispFunction builtin)) {
			return false;
		}
		return LispNames.EQL.equals(builtin.name())
				|| (LispNames.CHAR_EQ.equals(builtin.name()) && first instanceof Chars && second instanceof Chars);
	}

	// eql on the two elements, without boxing a character pair: LispChar is a record over
	// its code point, so eql on two of them is exactly a code-point comparison.
	private static boolean sameElement(Elements first, int i, Elements second, int j) {
		if (first instanceof Chars a && second instanceof Chars b) {
			return a.string().codePointAt(i) == b.string().codePointAt(j);
		}
		return Environment.isEql(first.at(i), second.at(j));
	}

	/**
	 * A rank-1 sequence read the way {@code (elt seq i)} reads it, over the
	 * {@code (length seq)} elements the prelude's bounds default to.
	 */
	private sealed interface Elements {

		int length();

		LispVal at(int index);

	}

	// A string: (elt s i) lowers to (char s i), which is the i-th CODE POINT.
	private record Chars(LispString string) implements Elements {
		@Override
		public int length() {
			return this.string.length();
		}

		@Override
		public LispVal at(int index) {
			return new LispChar(this.string.codePointAt(index));
		}
	}

	// A proper list, materialized once. (elt l i) lowers to (nth i l), an O(i) walk from
	// the head, so the prelude's inner loop over two lists is O(n^2*m) -- the same defect
	// .kb/sequence-op-runtimes.md records fixing in replace's list source arm.
	private record Items(LispVal[] items) implements Elements {
		@Override
		public int length() {
			return this.items.length;
		}

		@Override
		public LispVal at(int index) {
			return this.items[index];
		}
	}

	// A general rank-1 array: (elt v i) lowers to (aref v i), and a fill pointer bounds
	// (length v) -- which is what effectiveLength answers, and readFlat reads through a
	// displaced view.
	private record General(LispArray array) implements Elements {
		@Override
		public int length() {
			return this.array.effectiveLength();
		}

		@Override
		public LispVal at(int index) {
			return this.array.readFlat(index);
		}
	}

	private record Ints(LispIntVector vector) implements Elements {
		@Override
		public int length() {
			return this.vector.length();
		}

		@Override
		public LispVal at(int index) {
			return new LispInteger(this.vector.elementAt(index));
		}
	}

	private record Floats(LispFloatArray array) implements Elements {
		@Override
		public int length() {
			return this.array.totalSize();
		}

		@Override
		public LispVal at(int index) {
			return this.array.readFlat(index);
		}
	}

	// The value as an indexable element source, or null when (length x) would not measure
	// it the way this arm does: a dotted list, an array of rank 2 or more, a
	// non-sequence.
	private static @Nullable Elements elementsOf(LispVal value) {
		if (value instanceof LispString string) {
			return new Chars(string);
		}
		if (value instanceof LispNil) {
			return new Items(new LispVal[0]);
		}
		if (value instanceof LispCons) {
			List<LispVal> items = new ArrayList<>();
			LispVal cursor = value;
			while (cursor instanceof LispCons cell) {
				items.add(cell.car());
				cursor = cell.cdr();
			}
			if (!(cursor instanceof LispNil)) {
				return null;
			}
			return new Items(items.toArray(new LispVal[0]));
		}
		if (value instanceof LispArray array && array.dimensions().length == 1) {
			return new General(array);
		}
		if (value instanceof LispIntVector vector) {
			return new Ints(vector);
		}
		if (value instanceof LispFloatArray packed && packed.rank() == 1) {
			return new Floats(packed);
		}
		return null;
	}

}
