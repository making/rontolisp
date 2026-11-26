package am.ik.rontolisp.macro;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.ArrayElementTypes;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The arrays an array type specifier denotes: the names {@code array},
 * {@code simple-array}, {@code vector}, {@code simple-vector}, {@code string},
 * {@code simple-string}, {@code base-string}, {@code simple-base-string},
 * {@code bit-vector}, {@code simple-bit-vector} and their compounds. What
 * {@code subtypep} compares for two of them, the twin of {@code %subtypep-array} over a
 * run-time specifier.
 *
 * @param element the upgraded element code ({@link ArrayElementTypes}), {@link #ANY} for
 * {@code *}
 * @param dimensions each dimension, {@link #ANY} for {@code *}, or null for any rank
 * @param simple whether the specifier denotes simple arrays alone
 */
record ArrayTypeShape(int element, @Nullable List<Integer> dimensions, boolean simple) {

	/** An unspecified element type or dimension. */
	static final int ANY = -1;

	private static final List<Integer> ONE_DIMENSION = List.of(ANY);

	/**
	 * The shape a specifier denotes, or null when it is no array specifier.
	 * @param spec the unquoted type specifier
	 * @param element what an element type upgrades to, over the program's deftypes
	 * @return the shape, or null
	 */
	static @Nullable ArrayTypeShape of(LispVal spec, java.util.function.ToIntFunction<LispVal> element) {
		if (spec instanceof LispSymbol sym) {
			return named(standardName(sym));
		}
		if (!(spec instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return null;
		}
		String name = standardName(head);
		ArrayTypeShape named = named(name);
		if (named == null) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		int arguments = parts.size() - 1;
		switch (name) {
			case "ARRAY", "SIMPLE-ARRAY" -> {
				if (arguments > 2) {
					return null;
				}
				List<Integer> dimensions = arguments == 2 ? dimensions(parts.get(2)) : null;
				return arguments == 2 && dimensions == null && !isStar(parts.get(2)) ? null : new ArrayTypeShape(
						arguments >= 1 ? elementCode(parts.get(1), element) : ANY, dimensions, named.simple());
			}
			case "VECTOR" -> {
				if (arguments > 2) {
					return null;
				}
				Integer size = arguments == 2 ? size(parts.get(2)) : Integer.valueOf(ANY);
				return size == null ? null : new ArrayTypeShape(
						arguments >= 1 ? elementCode(parts.get(1), element) : ANY, List.of(size), false);
			}
			default -> {
				if (arguments > 1) {
					return null;
				}
				Integer size = arguments == 1 ? size(parts.get(1)) : Integer.valueOf(ANY);
				return size == null ? null : new ArrayTypeShape(named.element(), List.of(size), named.simple());
			}
		}
	}

	/**
	 * Whether every array {@code other} denotes is one this shape denotes.
	 * @param other the candidate sub-shape
	 * @return true when {@code other} is contained
	 */
	boolean contains(ArrayTypeShape other) {
		if ((this.simple && !other.simple) || (this.element != ANY && this.element != other.element)) {
			return false;
		}
		List<Integer> mine = this.dimensions;
		if (mine == null) {
			return true;
		}
		List<Integer> theirs = other.dimensions;
		if (theirs == null || theirs.size() != mine.size()) {
			return false;
		}
		for (int i = 0; i < mine.size(); i++) {
			if (mine.get(i) != ANY && !mine.get(i).equals(theirs.get(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether every array the shape denotes has one dimension.
	 * @return true for a vector
	 */
	boolean isVector() {
		return this.dimensions != null && this.dimensions.size() == 1;
	}

	private static @Nullable ArrayTypeShape named(String name) {
		return switch (name) {
			case "ARRAY" -> new ArrayTypeShape(ANY, null, false);
			case "SIMPLE-ARRAY" -> new ArrayTypeShape(ANY, null, true);
			case "VECTOR" -> new ArrayTypeShape(ANY, ONE_DIMENSION, false);
			case "SIMPLE-VECTOR" -> new ArrayTypeShape(ArrayElementTypes.T, ONE_DIMENSION, true);
			case "STRING", "BASE-STRING" -> new ArrayTypeShape(ArrayElementTypes.CHARACTER, ONE_DIMENSION, false);
			case "SIMPLE-STRING", "SIMPLE-BASE-STRING" ->
				new ArrayTypeShape(ArrayElementTypes.CHARACTER, ONE_DIMENSION, true);
			case "BIT-VECTOR" -> new ArrayTypeShape(ArrayElementTypes.BIT, ONE_DIMENSION, false);
			case "SIMPLE-BIT-VECTOR" -> new ArrayTypeShape(ArrayElementTypes.BIT, ONE_DIMENSION, true);
			default -> null;
		};
	}

	private static int elementCode(LispVal spec, java.util.function.ToIntFunction<LispVal> element) {
		return isStar(spec) ? ANY : element.applyAsInt(spec);
	}

	/**
	 * An array specifier's dimensions: a rank, or a list of sizes; null for anything
	 * else.
	 */
	private static @Nullable List<Integer> dimensions(LispVal spec) {
		if (spec instanceof LispInteger rank && rank.value() >= 0 && rank.value() <= 7) {
			List<Integer> any = new ArrayList<>();
			for (long i = 0; i < rank.value(); i++) {
				any.add(ANY);
			}
			return any;
		}
		if (spec instanceof LispNil) {
			return List.of();
		}
		if (!(spec instanceof LispCons list) || !list.isProperList()) {
			return null;
		}
		List<Integer> sizes = new ArrayList<>();
		for (LispVal dimension : list.toList()) {
			Integer size = size(dimension);
			if (size == null) {
				return null;
			}
			sizes.add(size);
		}
		return sizes;
	}

	private static @Nullable Integer size(LispVal spec) {
		if (isStar(spec)) {
			return ANY;
		}
		return spec instanceof LispInteger size && size.value() >= 0 && size.value() <= Integer.MAX_VALUE
				? Integer.valueOf((int) size.value()) : null;
	}

	private static boolean isStar(LispVal val) {
		return val instanceof LispSymbol sym && "*".equals(standardName(sym));
	}

	/** The member of a symbol of {@code cl}, the empty name for another package's. */
	private static String standardName(LispSymbol sym) {
		PackageRegistry.QualifiedName qualified = PackageRegistry.splitQualified(sym.name());
		if (qualified == null) {
			return sym.name();
		}
		return "COMMON-LISP".equals(qualified.pkg()) ? qualified.member() : "";
	}

}
