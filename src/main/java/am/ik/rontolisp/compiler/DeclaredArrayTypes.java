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
 * array), and a character element type is never mapped (a character vector may be a
 * string or a marked general array, two representations).
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
	 * unsigned integer vector of one width, a packed float array, the general boxed
	 * array, or a string.
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
		GENERAL,
		/** A string (rank-1 character array in the string representation). */
		STRING;

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
	 * The declared array kinds of a body's leading {@code (declare (type spec var...))}
	 * forms: variable name to the representation kind its declaration proves. Only
	 * leading declarations count (CL syntax; an optional docstring before or between them
	 * is skipped), only {@code type} specifiers are read, and a variable whose specifier
	 * proves no single representation is simply absent. Later duplicate declarations of
	 * one name keep the FIRST kind (chipz never declares one name twice; disagreeing
	 * declarations prove nothing anyway).
	 * @param bodyExprs the body forms, declarations included
	 * @param registry the registry holding {@code deftype} expansions, or null
	 * @return declared variable name to array kind (empty when none declare one)
	 */
	public static Map<String, Kind> declaredKinds(List<LispVal> bodyExprs, @Nullable ClosRegistry registry) {
		Map<String, Kind> kinds = new LinkedHashMap<>();
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
				Kind kind = kindOfSpec(parts.get(1), registry);
				if (kind == null) {
					continue;
				}
				for (int v = 2; v < parts.size(); v++) {
					if (parts.get(v) instanceof LispSymbol var) {
						kinds.putIfAbsent(var.name(), kind);
					}
				}
			}
		}
		return kinds;
	}

	/**
	 * The array representation kind a type specifier proves, or null when it proves none
	 * (including every non-array specifier). A symbol specifier follows registered
	 * {@code deftype} aliases; an {@code (and ...)} answers the one kind its components
	 * agree on.
	 * @param spec the type specifier as written in the declaration
	 * @param registry the registry holding {@code deftype} expansions, or null
	 * @return the kind, or null
	 */
	@Nullable public static Kind kindOfSpec(@Nullable LispVal spec, @Nullable ClosRegistry registry) {
		LispVal resolved = LispMacroExpander.resolveElementTypeAlias(spec, registry);
		if (resolved instanceof LispSymbol sym) {
			return switch (plainName(sym.name())) {
				case "SIMPLE-VECTOR" -> Kind.GENERAL;
				case "STRING", "SIMPLE-STRING", "BASE-STRING", "SIMPLE-BASE-STRING" -> Kind.STRING;
				default -> null;
			};
		}
		if (!(resolved instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		return switch (plainName(head.name())) {
			case "AND" -> {
				Kind agreed = null;
				for (int i = 1; i < parts.size(); i++) {
					Kind component = kindOfSpec(parts.get(i), registry);
					if (component == null) {
						continue;
					}
					if (agreed != null && agreed != component) {
						yield null;
					}
					agreed = component;
				}
				yield agreed;
			}
			case "SIMPLE-VECTOR" -> Kind.GENERAL;
			case "STRING", "SIMPLE-STRING", "BASE-STRING", "SIMPLE-BASE-STRING" -> Kind.STRING;
			// (vector [elt [len]]) is rank-1 by definition; (simple-array elt (dim)) /
			// (array elt (dim)) must spell ONE dimension for the packed kinds -- an
			// unknown rank could be a rank-n general array of the same element type.
			case "VECTOR" -> parts.size() >= 2 ? elementKind(parts.get(1), registry, true) : null;
			case "ARRAY", "SIMPLE-ARRAY" -> parts.size() >= 2
					? elementKind(parts.get(1), registry, parts.size() >= 3 && isRank1Dims(parts.get(2))) : null;
			default -> null;
		};
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
