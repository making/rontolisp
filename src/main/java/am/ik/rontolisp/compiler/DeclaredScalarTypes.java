package am.ik.rontolisp.compiler;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * Reads SCALAR type declarations out of a body head, the sibling of
 * {@link DeclaredArrayTypes} for {@code (declare (type double-float x y))}. The one kind
 * carried today is the float family: rontolisp has exactly one float representation
 * ({@code double-float}, {@code single-float}, {@code short-float}, {@code long-float}
 * and {@code float} all name the same type, {@code .kb/declarations-type-checks.md}), so
 * a declared float variable's value -- when the declaration is true -- is always a boxed
 * {@code Double} on the JVM backend, which is what lets the emitters route it onto the
 * unboxed IEEE path and keep it in a raw {@code double} slot.
 *
 * <p>
 * A false declaration is undefined behavior in CL; a backend that trusts one turns it
 * into a deterministic error at the site the representation is read or written (the JVM's
 * {@code checkcast}, the wasm-GC {@code ref.cast}), never a silently coerced or wrong
 * value -- {@code .kb/declarations-type-checks.md} records that decision.
 *
 * <p>
 * Integer declarations ({@code fixnum}, {@code (integer lo hi)}) are deliberately NOT
 * read: the JVM integer story infers its raw representation from initializers and
 * assignments ({@code .kb/jvm-int-fusion.md}) and was measured at SBCL parity without
 * being told (`.kb/jvm-double-arithmetic.md`, the 2026-08-29 premise measurement).
 */
public final class DeclaredScalarTypes {

	private DeclaredScalarTypes() {
	}

	/**
	 * The variable names a body's leading {@code (declare (type spec var...))} forms
	 * declare to be floats (any of the four float type names or bare {@code float},
	 * bounded compounds included, resolved through registered {@code deftype} aliases).
	 * Only leading declarations count (CL syntax; an optional docstring before or between
	 * them is skipped), only {@code type} specifiers are read.
	 * @param bodyExprs the body forms, declarations included
	 * @param registry the registry holding {@code deftype} expansions, or null
	 * @return the declared float variable names (empty when none declare one)
	 */
	public static Set<String> declaredDoubles(List<LispVal> bodyExprs, @Nullable ClosRegistry registry) {
		Set<String> names = new LinkedHashSet<>();
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
				if (parts.size() < 3 || !isFloatSpec(parts.get(1), registry)) {
					continue;
				}
				for (int v = 2; v < parts.size(); v++) {
					if (parts.get(v) instanceof LispSymbol var) {
						names.add(var.name());
					}
				}
			}
		}
		return names;
	}

	/**
	 * The declared float names of a FUNCTION body: the body-head declarations plus the
	 * ones sitting behind the sole trailing {@code %fn-block}/{@code (block name ...)}
	 * wrapper {@code LambdaLists} and the flet lowering produce -- the same walk the wasm
	 * backend's {@code WasmArrayCompiler.functionBodyDeclaredKinds} does for the array
	 * kinds. The caller removes special variable names (it owns the scope).
	 * @param bodyExprs the function body forms as lowered
	 * @param registry the registry holding {@code deftype} expansions, or null
	 * @return the declared float variable names (empty when none declare one)
	 */
	public static Set<String> functionBodyDeclaredDoubles(List<LispVal> bodyExprs, @Nullable ClosRegistry registry) {
		Set<String> names = new LinkedHashSet<>(declaredDoubles(bodyExprs, registry));
		for (int i = 0; i < bodyExprs.size(); i++) {
			LispVal form = bodyExprs.get(i);
			if (form instanceof LispString || isDeclareForm(form)) {
				continue;
			}
			if (i == bodyExprs.size() - 1 && form instanceof LispCons wrapper && wrapper.isProperList()
					&& wrapper.car() instanceof LispSymbol head && (LispNames.FN_BLOCK_INTERNAL.equals(head.name())
							|| LispNames.BLOCK.equals(plainName(head.name())))
					&& wrapper.toList().size() >= 3) {
				List<LispVal> wrapped = wrapper.toList();
				names.addAll(declaredDoubles(wrapped.subList(2, wrapped.size()), registry));
			}
			break;
		}
		return names;
	}

	private static boolean isDeclareForm(LispVal form) {
		return form instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head
				&& LispNames.DECLARE.equals(plainName(head.name()));
	}

	/**
	 * Whether a type specifier proves the float type: one of the four float type names or
	 * bare {@code float} as a symbol, or the same names as a bounded compound
	 * ({@code (double-float 0.0d0 *)}). A symbol specifier follows registered
	 * {@code deftype} aliases. Anything else -- including {@code real} and
	 * {@code number}, which admit integers -- proves nothing.
	 * @param spec the type specifier as written in the declaration
	 * @param registry the registry holding {@code deftype} expansions, or null
	 * @return true when the specifier names the float type
	 */
	public static boolean isFloatSpec(@Nullable LispVal spec, @Nullable ClosRegistry registry) {
		LispVal resolved = LispMacroExpander.resolveElementTypeAlias(spec, registry);
		if (resolved instanceof LispSymbol sym) {
			return isFloatTypeName(sym.name());
		}
		if (resolved instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head) {
			return isFloatTypeName(head.name());
		}
		return false;
	}

	private static boolean isFloatTypeName(String name) {
		return switch (plainName(name)) {
			case "DOUBLE-FLOAT", "SINGLE-FLOAT", "SHORT-FLOAT", "LONG-FLOAT", "FLOAT" -> true;
			default -> false;
		};
	}

	private static String plainName(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
