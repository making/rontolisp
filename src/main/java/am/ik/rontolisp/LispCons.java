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
	private String render(boolean escape) {
		if (!RenderCycleGuard.enter(this)) {
			return "#";
		}
		try {
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
