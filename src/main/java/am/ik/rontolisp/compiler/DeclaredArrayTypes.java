package am.ik.rontolisp.compiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * Maps a declared type specifier to the ARRAY REPRESENTATION it pins down, and reads
 * {@code (declare (type spec var...))} forms out of a body head. This is the shared
 * front-end of declaration-driven array-access emission: a backend that knows a value's
 * representation kind at a rank-1 {@code aref}/{@code (setf aref)}/{@code length} site
 * can emit that one representation's accessor with a cheap representation check (a
 * {@code ref.cast} on the wasm-GC backend) instead of the full inline type-dispatch
 * chain.
 *
 * <p>
 * Everything here is deliberately conservative: a specifier that does not PROVE one
 * representation answers null and the site keeps the generic dispatch. In particular a
 * bare {@code vector}/{@code array} proves nothing (a string is a vector too), a packed
 * kind requires an explicitly RANK-1 dimension spec ({@code (simple-array (unsigned-byte
 * 8) (*))} packs, {@code (simple-array (unsigned-byte 8) *)} could be a rank-n general
 * array), and neither a string specifier ({@code simple-string} included) nor a character
 * element type is mapped: a string may be the immutable string representation or a marked
 * general character array ({@code copy-seq}, {@code subseq}, {@code make-string}), two
 * representations.
 *
 * <p>
 * A false declaration is undefined behavior in CL; here it becomes a deterministic
 * {@code ref.cast} trap at the access, never silent wrong data --
 * {@code .kb/declarations-type-checks.md} records that decision.
 */
public final class DeclaredArrayTypes {

	private DeclaredArrayTypes() {
	}

	/**
	 * The array representation a declared type pins down on the wasm-GC backend: a packed
	 * unsigned integer vector of one width, a packed float array, or the general boxed
	 * array.
	 */
	public enum Kind {

		/** A packed {@code (unsigned-byte 8)} vector. */
		U8,
		/** A packed {@code (unsigned-byte 16)} vector. */
		U16,
		/** A packed {@code (unsigned-byte 32)} vector. */
		U32,
		/** A packed float array (either float width; the store dispatches inside). */
		FLOAT,
		/** The general boxed array representation. */
		GENERAL;

		/**
		 * The packed integer element width, or 0 for the non-packed kinds.
		 * @return 8, 16, 32 or 0
		 */
		public int packedIntWidth() {
			return switch (this) {
				case U8 -> 8;
				case U16 -> 16;
				case U32 -> 32;
				default -> 0;
			};
		}

	}

	/**
	 * What a declaration pins a value to: the representation of the array it is, whether
	 * {@code nil} may stand in its place -- {@code (or null (simple-array ...))}, what
	 * the Java front end declares of a variable of an array type -- and the integers a
	 * general array's elements are. An element access reads either the same way: its
	 * checks run ahead of the one representation's read, and they answer {@code nil} as
	 * the generic access does. What reads the kind alone -- a packed vector's
	 * {@code length}, a {@code fill} through the array arm -- takes it only where
	 * {@code nil} cannot be.
	 *
	 * <p>
	 * The elements are what CLHS's {@code type} declaration says of an array type's:
	 * within its scope every element read is of the EXPRESSED element type, not the
	 * upgraded one -- {@code (simple-array (signed-byte 64) (*))} is the general array,
	 * each element an integer of i64. A packed kind's elements are its width's and need
	 * none.
	 *
	 * @param kind the representation of the array
	 * @param orNull whether the value may be {@code nil} instead
	 * @param elements the interval of i64 every element of a {@link Kind#GENERAL} array
	 * is in, or null where the element type proves none
	 */
	public record Pin(Kind kind, boolean orNull, DeclaredIntegerTypes.@Nullable Interval elements) {

		/**
		 * The kind of a value that is surely an array, or null where it may be
		 * {@code nil}.
		 * @return the kind, or null
		 */
		public @Nullable Kind arrayKind() {
			return this.orNull ? null : this.kind;
		}

		/**
		 * What this and another pin of the same value prove together: {@code nil} only
		 * where both admit it, the elements in both intervals; this pin alone where the
		 * two disagree on the kind, which only a false declaration makes.
		 * @param other the other pin, or null
		 * @return the conjunction
		 */
		public Pin and(@Nullable Pin other) {
			if (other == null || other.kind != this.kind) {
				return this;
			}
			return new Pin(this.kind, this.orNull && other.orNull, meet(this.elements, other.elements));
		}

	}

	/**
	 * The declared array pins of a body's leading {@code (declare (type spec var...))}
	 * forms: variable name to the representation (and whether {@code nil} may stand in
	 * for it) its declaration proves. Only leading declarations count (CL syntax; an
	 * optional docstring before or between them is skipped), only {@code type} specifiers
	 * are read, and a variable whose specifier proves no single representation is simply
	 * absent. Later duplicate declarations of one name keep the FIRST pin (chipz never
	 * declares one name twice; disagreeing declarations prove nothing anyway).
	 * @param bodyExprs the body forms, declarations included
	 * @param registry the registry holding {@code deftype} expansions, or null
	 * @return declared variable name to its pin (empty when none declare one)
	 */
	public static Map<String, Pin> declared(List<LispVal> bodyExprs, @Nullable ClosRegistry registry) {
		Map<String, Pin> pins = new LinkedHashMap<>();
		for (LispVal form : bodyExprs) {
			if (form instanceof LispString) {
				continue;
			}
			if (!(form instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)
					|| !LispNames.DECLARE.equals(plainName(head.name()))) {
				break;
			}
			List<LispVal> specifiers = cons.toList();
			for (int i = 1; i < specifiers.size(); i++) {
				if (!(specifiers.get(i) instanceof LispCons specifier) || !specifier.isProperList()
						|| !(specifier.car() instanceof LispSymbol specHead)
						|| !"TYPE".equals(plainName(specHead.name()))) {
					continue;
				}
				List<LispVal> parts = specifier.toList();
				if (parts.size() < 3) {
					continue;
				}
				Pin pin = pinOfSpec(parts.get(1), registry);
				if (pin == null) {
					continue;
				}
				for (int v = 2; v < parts.size(); v++) {
					if (parts.get(v) instanceof LispSymbol var) {
						pins.putIfAbsent(var.name(), pin);
					}
				}
			}
		}
		return pins;
	}

	/**
	 * {@link #declared} of a function body's head, and of the head of the sole
	 * {@code %fn-block}/{@code block} the body's last form wraps around the rest, the
	 * shape a {@code defun}'s body is lowered to
	 * ({@link DeclaredIntegerTypes#functionBody}'s twin). The caller removes special
	 * variable names.
	 * @param bodyExprs the function body forms as lowered
	 * @param registry the registry holding {@code deftype} expansions, or null
	 * @return declared variable name to its pin (empty when none declare one)
	 */
	public static Map<String, Pin> functionBody(List<LispVal> bodyExprs, @Nullable ClosRegistry registry) {
		Map<String, Pin> pins = new LinkedHashMap<>(declared(bodyExprs, registry));
		for (int i = 0; i < bodyExprs.size(); i++) {
			LispVal form = bodyExprs.get(i);
			if (form instanceof LispString || am.ik.rontolisp.SpecialDeclarations.isDeclaration(form)) {
				continue;
			}
			if (i == bodyExprs.size() - 1 && form instanceof LispCons wrapper && wrapper.isProperList()
					&& wrapper.car() instanceof LispSymbol head && (LispNames.FN_BLOCK_INTERNAL.equals(head.name())
							|| LispNames.BLOCK.equals(plainName(head.name())))
					&& wrapper.toList().size() >= 3) {
				List<LispVal> wrapped = wrapper.toList();
				declared(wrapped.subList(2, wrapped.size()), registry).forEach(pins::putIfAbsent);
			}
			break;
		}
		return pins;
	}

	/**
	 * The array representation a type specifier proves, and whether it admits {@code nil}
	 * beside it, or null when it proves none (including every non-array specifier). A
	 * symbol specifier follows registered {@code deftype} aliases; an {@code (and ...)}
	 * answers the one kind its components agree on, admitting {@code nil} unless one of
	 * them excludes it; an {@code (or ...)} of {@code null} and array types of one kind
	 * answers that kind with {@code nil}.
	 * @param spec the type specifier as written in the declaration
	 * @param registry the registry holding {@code deftype} expansions, or null
	 * @return the pin, or null
	 */
	@Nullable public static Pin pinOfSpec(@Nullable LispVal spec, @Nullable ClosRegistry registry) {
		LispVal resolved = LispMacroExpander.resolveElementTypeAlias(spec, registry);
		if (resolved instanceof LispSymbol sym) {
			return "SIMPLE-VECTOR".equals(plainName(sym.name())) ? new Pin(Kind.GENERAL, false, null) : null;
		}
		if (!(resolved instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		switch (plainName(head.name())) {
			case "AND" -> {
				// every component holds: the one kind they agree on, nil only where none
				// excludes it, the elements in every interval given
				Pin agreed = null;
				for (int i = 1; i < parts.size(); i++) {
					Pin component = pinOfSpec(parts.get(i), registry);
					if (component == null) {
						continue;
					}
					if (agreed != null && agreed.kind() != component.kind()) {
						return null;
					}
					agreed = agreed == null ? component : agreed.and(component);
				}
				return agreed;
			}
			case "OR" -> {
				// one branch holds: nil, or an array of the one kind every other branch
				// proves, its elements in the hull of theirs
				Pin agreed = null;
				boolean orNull = false;
				for (int i = 1; i < parts.size(); i++) {
					LispVal branch = parts.get(i);
					if (branch instanceof LispNil) {
						// the empty type adds nothing
						continue;
					}
					if (branch instanceof LispSymbol n && "NULL".equals(plainName(n.name()))) {
						orNull = true;
						continue;
					}
					Pin component = pinOfSpec(branch, registry);
					if (component == null || agreed != null && agreed.kind() != component.kind()) {
						return null;
					}
					agreed = agreed == null ? component : new Pin(agreed.kind(), agreed.orNull() || component.orNull(),
							hull(agreed.elements(), component.elements()));
				}
				return agreed == null ? null : new Pin(agreed.kind(), agreed.orNull() || orNull, agreed.elements());
			}
			case "SIMPLE-VECTOR" -> {
				return new Pin(Kind.GENERAL, false, null);
			}
			// (vector [elt [len]]) is rank-1 by definition; (simple-array elt (dim)) /
			// (array elt (dim)) must spell ONE dimension for the packed kinds -- an
			// unknown rank could be a rank-n general array of the same element type.
			case "VECTOR" -> {
				return parts.size() >= 2 ? arrayPin(parts.get(1), registry, true) : null;
			}
			case "ARRAY", "SIMPLE-ARRAY" -> {
				return parts.size() >= 2
						? arrayPin(parts.get(1), registry, parts.size() >= 3 && isRank1Dims(parts.get(2))) : null;
			}
			default -> {
				return null;
			}
		}
	}

	// The pin of an array type of an element type: its kind, and for the general kind
	// the interval its elements are in.
	@Nullable private static Pin arrayPin(LispVal elementSpec, @Nullable ClosRegistry registry, boolean rank1) {
		Kind kind = elementKind(elementSpec, registry, rank1);
		if (kind == null) {
			return null;
		}
		return new Pin(kind, false, kind == Kind.GENERAL ? DeclaredIntegerTypes.of(elementSpec, registry) : null);
	}

	private static DeclaredIntegerTypes.@Nullable Interval meet(DeclaredIntegerTypes.@Nullable Interval a,
			DeclaredIntegerTypes.@Nullable Interval b) {
		if (a == null || b == null) {
			return a == null ? b : a;
		}
		long lo = Math.max(a.lo(), b.lo());
		long hi = Math.min(a.hi(), b.hi());
		// an empty meet proves nothing
		return lo <= hi ? new DeclaredIntegerTypes.Interval(lo, hi) : null;
	}

	private static DeclaredIntegerTypes.@Nullable Interval hull(DeclaredIntegerTypes.@Nullable Interval a,
			DeclaredIntegerTypes.@Nullable Interval b) {
		return a == null || b == null ? null
				: new DeclaredIntegerTypes.Interval(Math.min(a.lo(), b.lo()), Math.max(a.hi(), b.hi()));
	}

	// The kind an ELEMENT type contributes: a packed width only when the surrounding
	// specifier proved rank 1 (packed vectors are rank-1 by construction); the boxed
	// element types prove GENERAL at any rank (the general accessor is
	// rank-representation-agnostic); a character element proves nothing (string vs
	// marked general char vector); `*` proves nothing.
	@Nullable private static Kind elementKind(LispVal elementSpec, @Nullable ClosRegistry registry, boolean rank1) {
		LispVal resolved = LispMacroExpander.resolveElementTypeAlias(elementSpec, registry);
		if (resolved instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head) {
			List<LispVal> parts = cons.toList();
			if ("UNSIGNED-BYTE".equals(plainName(head.name())) && parts.size() == 2
					&& parts.get(1) instanceof LispInteger width) {
				if (!rank1) {
					return null;
				}
				return switch ((int) width.value()) {
					case 8 -> Kind.U8;
					case 16 -> Kind.U16;
					case 32 -> Kind.U32;
					default -> Kind.GENERAL;
				};
			}
			// Any other compound element type (signed-byte, (integer lo hi), ...) keeps
			// the general boxed representation on every backend.
			return Kind.GENERAL;
		}
		if (resolved instanceof LispTrue) {
			// (vector t): the reader's T is no symbol, and no element type says "boxed"
			// more plainly.
			return Kind.GENERAL;
		}
		if (resolved instanceof LispSymbol sym) {
			return switch (plainName(sym.name())) {
				case "DOUBLE-FLOAT", "SINGLE-FLOAT", "SHORT-FLOAT", "LONG-FLOAT" -> rank1 ? Kind.FLOAT : null;
				case "CHARACTER", "BASE-CHAR", "STANDARD-CHAR", "EXTENDED-CHAR" -> null;
				case "*" -> null;
				// FIXNUM, T, BIT, INTEGER, ... all keep the general boxed
				// representation.
				default -> Kind.GENERAL;
			};
		}
		return null;
	}

	// Whether a dimension spec spells exactly one dimension: (d) with d an integer or *.
	private static boolean isRank1Dims(LispVal dims) {
		return dims instanceof LispCons cons && cons.cdr() instanceof LispNil && (cons.car() instanceof LispInteger
				|| (cons.car() instanceof LispSymbol d && "*".equals(plainName(d.name()))));
	}

	private static String plainName(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
