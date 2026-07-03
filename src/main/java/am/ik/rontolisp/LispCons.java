package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
		StringBuilder sb = new StringBuilder("(");
		LispVal current = this;
		boolean first = true;
		while (current instanceof LispCons cons) {
			if (!first) {
				sb.append(' ');
			}
			sb.append(cons.car().print());
			current = cons.cdr();
			first = false;
		}
		if (!(current instanceof LispNil)) {
			sb.append(" . ").append(current.print());
		}
		sb.append(')');
		return sb.toString();
	}

	@Override
	public String display() {
		StringBuilder sb = new StringBuilder("(");
		LispVal current = this;
		boolean first = true;
		while (current instanceof LispCons cons) {
			if (!first) {
				sb.append(' ');
			}
			sb.append(cons.car().display());
			current = cons.cdr();
			first = false;
		}
		if (!(current instanceof LispNil)) {
			sb.append(" . ").append(current.display());
		}
		sb.append(')');
		return sb.toString();
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
