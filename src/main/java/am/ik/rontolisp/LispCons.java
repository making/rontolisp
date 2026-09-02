package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A mutable cons cell holding a car and cdr pair.
 */
public final class LispCons implements LispVal {

	private LispVal car;

	private LispVal cdr;

	/**
	 * Create a new cons cell.
	 * @param car the first element
	 * @param cdr the rest of the list
	 */
	public LispCons(LispVal car, LispVal cdr) {
		this.car = car;
		this.cdr = cdr;
	}

	/**
	 * Returns the first element.
	 * @return the car
	 */
	public LispVal car() {
		return this.car;
	}

	/**
	 * Returns the rest of the list.
	 * @return the cdr
	 */
	public LispVal cdr() {
		return this.cdr;
	}

	/**
	 * Destructively replaces the car of this cons cell.
	 * @param car the new car value
	 */
	public void setCar(LispVal car) {
		this.car = car;
	}

	/**
	 * Destructively replaces the cdr of this cons cell.
	 * @param cdr the new cdr value
	 */
	public void setCdr(LispVal cdr) {
		this.cdr = cdr;
	}

	/**
	 * The cons an AST pass should return after walking {@code original}: {@code original}
	 * itself when the walk produced the very same car and cdr, a fresh cell otherwise.
	 *
	 * <p>
	 * <b>Every frontend AST pass must go through this (or the same check by hand).</b>
	 * {@link SourceProvenance} keys a form's source position on cons IDENTITY, so
	 * rebuilding a cons the pass did not actually change erases its position -- and
	 * because a rebuilt parent forces rebuilt children, one gratuitous copy in the middle
	 * of the pipeline drops the position of the whole program below the top level. It is
	 * also free performance: a pass that changes nothing then allocates nothing. See
	 * {@code .kb/source-positions.md}.
	 * @param original the cons that was walked
	 * @param car the walked car
	 * @param cdr the walked cdr
	 * @return {@code original} when nothing changed, otherwise a new cons of the two
	 */
	public static LispCons rebuilt(LispCons original, LispVal car, LispVal cdr) {
		return car == original.car() && cdr == original.cdr() ? original : new LispCons(car, cdr);
	}

	/**
	 * The proper list an AST pass should return after walking {@code original}
	 * element-wise: {@code original} itself when {@code elements} is exactly what it
	 * already held, a fresh list otherwise. The identity rule of {@link #rebuilt} applies
	 * to a pass that walks through {@link #toList()} just as much.
	 * @param original the proper list that was walked
	 * @param elements the walked elements
	 * @return {@code original} when nothing changed, otherwise a new proper list
	 */
	public static LispVal rebuiltList(LispCons original, List<LispVal> elements) {
		LispVal current = original;
		int i = 0;
		while (current instanceof LispCons cons && i < elements.size()) {
			if (cons.car() != elements.get(i++)) {
				break;
			}
			current = cons.cdr();
		}
		if (i == elements.size() && current instanceof LispNil) {
			return original;
		}
		LispVal result = LispNil.INSTANCE;
		for (int k = elements.size() - 1; k >= 0; k--) {
			result = new LispCons(elements.get(k), result);
		}
		return result;
	}

	/**
	 * Returns whether this cons cell chain is a proper list, i.e. the chain of cdrs ends
	 * in nil rather than a dotted tail.
	 * @return true when the list is nil-terminated
	 */
	public boolean isProperList() {
		LispVal current = this;
		while (current instanceof LispCons cons) {
			current = cons.cdr();
		}
		return current instanceof LispNil;
	}

	/**
	 * The element count of this chain when it is a proper list, {@code -1} when it ends
	 * in a dotted tail. One walk answering both what {@link #isProperList()} answers and
	 * the size a caller about to collect the elements should allocate -- the evaluator's
	 * per-form check plus its argument collection used to walk twice.
	 * @return the element count, or {@code -1} for an improper list
	 */
	public int properLength() {
		int length = 0;
		LispVal current = this;
		while (current instanceof LispCons cons) {
			length++;
			current = cons.cdr();
		}
		return current instanceof LispNil ? length : -1;
	}

	/**
	 * Converts this cons cell chain into a Java list. Assumes proper list (terminated by
	 * nil).
	 * @return the elements as a list
	 */
	public List<LispVal> toList() {
		List<LispVal> result = new ArrayList<>();
		LispVal current = this;
		while (current instanceof LispCons cons) {
			result.add(cons.car());
			current = cons.cdr();
		}
		return result;
	}

	@Override
	public String print() {
		return render(true);
	}

	@Override
	public String display() {
		return render(false);
	}

	// Renders the chain as a list. Two cycle defenses, shared discipline with the
	// instance and array renderers (RenderCycleGuard, .kb/pretty-printer.md): the chain
	// HEAD opens one render frame, so a car that reaches back to a list still being
	// rendered -- and the frame past the depth cap -- prints as "#", the *print-level*
	// cutoff marker; and the CDR chain, which is walked iteratively and so never
	// re-enters this method, is pre-scanned for a cycle (Floyd), the second arrival at
	// the cycle-start cell printing as the improper tail " . #" -- every element exactly
	// once, then the marker.
	//
	// A two-element (QUOTE x) / (FUNCTION x) list is the one shape the printer
	// abbreviates, to 'x / #'x (CLHS 22.1.3.7 permits this unconditionally; the reader
	// already reads the abbreviation back into exactly this shape). It still opens
	// this cell's render frame first -- the abbreviation branch below runs INSIDE the
	// guarded section, not before it, so a self-referential (QUOTE x) with x == this
	// still hits the guard on the recursive print of x rather than looping forever;
	// `.kb/defmacro-backquote.md`'s backquote expansion never reaches this shape since
	// it lowers to list/append at READ TIME, so that deviation from CLHS is untouched.
	private String render(boolean escape) {
		if (!RenderCycleGuard.enter(this)) {
			return "#";
		}
		try {
			String abbrevPrefix = abbreviationPrefix();
			if (abbrevPrefix != null) {
				LispVal quoted = ((LispCons) this.cdr).car();
				return abbrevPrefix + (escape ? quoted.print() : quoted.display());
			}
			LispCons stop = cycleStart();
			StringBuilder sb = new StringBuilder("(");
			LispVal current = this;
			boolean first = true;
			boolean seenStop = false;
			while (current instanceof LispCons cons) {
				if (cons == stop) {
					if (seenStop) {
						return sb.append(" . #)").toString();
					}
					seenStop = true;
				}
				if (!first) {
					sb.append(' ');
				}
				sb.append(escape ? cons.car().print() : cons.car().display());
				current = cons.cdr();
				first = false;
			}
			if (!(current instanceof LispNil)) {
				sb.append(" . ").append(escape ? current.print() : current.display());
			}
			sb.append(')');
			return sb.toString();
		}
		finally {
			RenderCycleGuard.exit();
		}
	}

	// "'" for a (QUOTE x) cell, "#'" for a (FUNCTION x) cell, null for anything else --
	// in particular a THREE-OR-MORE element list ((QUOTE A B) prints in full) and an
	// improper one ((QUOTE . A) likewise).
	private @Nullable String abbreviationPrefix() {
		if (!(this.car instanceof LispSymbol sym) || !(this.cdr instanceof LispCons rest)
				|| !(rest.cdr() instanceof LispNil)) {
			return null;
		}
		if (sym.name().equals(LispNames.QUOTE)) {
			return "'";
		}
		if (sym.name().equals(LispNames.FUNCTION)) {
			return "#'";
		}
		return null;
	}

	// Floyd's cycle detection over the cdr chain: answers the cell where the cycle
	// begins, or null for a terminating chain. Constant space, so a million-element
	// proper list costs one extra traversal and no allocation.
	private @Nullable LispCons cycleStart() {
		LispVal slow = this;
		LispVal fast = this;
		while (true) {
			if (!(fast instanceof LispCons fc) || !(fc.cdr() instanceof LispCons fcc)) {
				return null;
			}
			fast = fcc.cdr();
			slow = ((LispCons) slow).cdr();
			if (slow == fast) {
				break;
			}
		}
		LispVal p = this;
		while (p != slow) {
			p = ((LispCons) p).cdr();
			slow = ((LispCons) slow).cdr();
		}
		return (LispCons) p;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof LispCons other)) {
			return false;
		}
		return Objects.equals(this.car, other.car) && Objects.equals(this.cdr, other.cdr);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.car, this.cdr);
	}

	@Override
	public String toString() {
		return "LispCons[car=" + this.car + ", cdr=" + this.cdr + "]";
	}

}
