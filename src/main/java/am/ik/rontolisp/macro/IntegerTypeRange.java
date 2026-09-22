package am.ik.rontolisp.macro;

import java.math.BigInteger;
import java.util.List;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The closed interval of integers an integer type specifier denotes: {@code bit},
 * {@code unsigned-byte}, {@code signed-byte}, {@code integer} and the compounds
 * {@code (unsigned-byte n)}, {@code (signed-byte n)}, {@code (integer lo hi)} and
 * {@code (mod n)}. A null bound is unbounded on that side. Shared by {@code subtypep}'s
 * interval rule and the stream element-type classification ({@link StreamElementType}),
 * so the two read one table.
 *
 * @param lo the least member, or null when unbounded below
 * @param hi the greatest member, or null when unbounded above
 */
public record IntegerTypeRange(@Nullable BigInteger lo, @Nullable BigInteger hi) {

	/**
	 * The interval a specifier denotes, or null when it is not one of the integer
	 * specifiers above (a malformed one included: {@code (unsigned-byte 0)},
	 * {@code (mod 0)}, a non-integer bound).
	 * @param spec the unquoted type specifier
	 * @return the interval, or null
	 */
	public static @Nullable IntegerTypeRange of(LispVal spec) {
		if (spec instanceof LispSymbol sym) {
			return switch (plainName(sym)) {
				case "BIT" -> new IntegerTypeRange(BigInteger.ZERO, BigInteger.ONE);
				case "UNSIGNED-BYTE" -> new IntegerTypeRange(BigInteger.ZERO, null);
				case "SIGNED-BYTE", "INTEGER" -> new IntegerTypeRange(null, null);
				default -> null;
			};
		}
		if (!(spec instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		String name = plainName(head);
		switch (name) {
			case "UNSIGNED-BYTE", "SIGNED-BYTE" -> {
				if (parts.size() == 1 || (parts.size() == 2 && isStar(parts.get(1)))) {
					return of(head);
				}
				BigInteger bits = parts.size() == 2 ? integerOf(parts.get(1)) : null;
				if (bits == null || bits.signum() <= 0 || bits.bitLength() > 31) {
					return null;
				}
				int n = bits.intValue();
				if ("UNSIGNED-BYTE".equals(name)) {
					return new IntegerTypeRange(BigInteger.ZERO, BigInteger.ONE.shiftLeft(n).subtract(BigInteger.ONE));
				}
				BigInteger half = BigInteger.ONE.shiftLeft(n - 1);
				return new IntegerTypeRange(half.negate(), half.subtract(BigInteger.ONE));
			}
			case "MOD" -> {
				BigInteger n = parts.size() == 2 ? integerOf(parts.get(1)) : null;
				return n == null || n.signum() <= 0 ? null
						: new IntegerTypeRange(BigInteger.ZERO, n.subtract(BigInteger.ONE));
			}
			case "INTEGER" -> {
				if (parts.size() > 3) {
					return null;
				}
				Bound lo = parts.size() > 1 ? bound(parts.get(1), true) : Bound.UNBOUNDED;
				Bound hi = parts.size() > 2 ? bound(parts.get(2), false) : Bound.UNBOUNDED;
				if (lo == null || hi == null) {
					return null;
				}
				return new IntegerTypeRange(lo.value(), hi.value());
			}
			default -> {
				return null;
			}
		}
	}

	/**
	 * An interval COVERING the specifier: {@link #of}, plus an {@code (or ...)} of
	 * integer types folded to the hull of its branches. Sound only where a larger
	 * interval is safe -- the SUB side of a containment test, or the widest element a
	 * stream must hold -- never as the super.
	 * @param spec the unquoted type specifier
	 * @return the covering interval, or null
	 */
	public static @Nullable IntegerTypeRange covering(LispVal spec) {
		if (spec instanceof LispCons cons && cons.car() instanceof LispSymbol head && cons.isProperList()
				&& "OR".equals(plainName(head))) {
			List<LispVal> parts = cons.toList();
			IntegerTypeRange hull = new IntegerTypeRange(BigInteger.ONE, BigInteger.ZERO);
			for (int i = 1; i < parts.size(); i++) {
				IntegerTypeRange branch = covering(parts.get(i));
				if (branch == null) {
					return null;
				}
				hull = hull.hull(branch);
			}
			return hull;
		}
		return of(spec);
	}

	/**
	 * Whether no integer lies in the interval ({@code (integer 5 4)}).
	 * @return true for the empty interval
	 */
	public boolean isEmpty() {
		return this.lo != null && this.hi != null && this.lo.compareTo(this.hi) > 0;
	}

	/**
	 * Whether every member of {@code other} is a member of this interval.
	 * @param other the candidate sub-interval
	 * @return true when {@code other} is contained
	 */
	public boolean contains(IntegerTypeRange other) {
		if (other.isEmpty()) {
			return true;
		}
		boolean loOk = this.lo == null || (other.lo != null && this.lo.compareTo(other.lo) <= 0);
		boolean hiOk = this.hi == null || (other.hi != null && this.hi.compareTo(other.hi) >= 0);
		return loOk && hiOk;
	}

	/**
	 * The smallest interval covering both.
	 * @param other the other interval
	 * @return the hull
	 */
	public IntegerTypeRange hull(IntegerTypeRange other) {
		if (other.isEmpty()) {
			return this;
		}
		if (isEmpty()) {
			return other;
		}
		BigInteger l = this.lo == null || other.lo == null ? null : this.lo.min(other.lo);
		BigInteger h = this.hi == null || other.hi == null ? null : this.hi.max(other.hi);
		return new IntegerTypeRange(l, h);
	}

	private record Bound(@Nullable BigInteger value) {
		static final Bound UNBOUNDED = new Bound(null);

	}

	/**
	 * An {@code (integer ...)} bound: an integer, {@code (k)} exclusive, or {@code *}.
	 */
	private static @Nullable Bound bound(LispVal val, boolean lower) {
		if (isStar(val)) {
			return Bound.UNBOUNDED;
		}
		BigInteger inclusive = integerOf(val);
		if (inclusive != null) {
			return new Bound(inclusive);
		}
		if (val instanceof LispCons excl && excl.isProperList() && excl.toList().size() == 1) {
			BigInteger k = integerOf(excl.car());
			if (k != null) {
				return new Bound(lower ? k.add(BigInteger.ONE) : k.subtract(BigInteger.ONE));
			}
		}
		return null;
	}

	private static boolean isStar(LispVal val) {
		return val instanceof LispSymbol sym && "*".equals(plainName(sym));
	}

	private static @Nullable BigInteger integerOf(LispVal val) {
		if (val instanceof LispInteger i) {
			return BigInteger.valueOf(i.value());
		}
		if (val instanceof LispBigInteger b) {
			return b.value();
		}
		return null;
	}

	static String plainName(LispSymbol sym) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return qn == null ? sym.name() : qn.member();
	}

}
