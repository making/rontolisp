package am.ik.rontolisp.codegen.wasm;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.compiler.DeclaredArrayTypes;

/**
 * What a binder of lexical names saves before its body: the facts
 * {@link WasmLispCompiler.Ctx} keeps per NAME about the variables in scope, beside
 * {@code Ctx.locals} itself. Each map is keyed on names and consulted by every reader
 * before or beside {@code Ctx.locals}, so a new binding that leaves an outer one's entry
 * in place is read as the outer binding: its raw i64 slot, its declared range or array
 * kind, its cell.
 *
 * <p>
 * {@link #shadow} removes the names from every one of them for the body; {@link #restore}
 * puts the outer scope back. A binder then registers what its own bindings are.
 * {@link WasmLetCompiler}, which registers most of these itself, does the same removal
 * inline.
 */
record WasmShadowedNames(Map<String, WasmIntFusionCompiler.RawLocal> rawLocals,
		Map<String, WasmIntFusionCompiler.LocalIntLambda> localIntLambdas,
		Map<String, WasmIntFusionCompiler.Range> declaredIntegers, Map<String, DeclaredArrayTypes.Pin> declaredArrays,
		Set<String> arrayLocals, Set<String> boxedVars) {

	/**
	 * Removes {@code names} from every name-keyed fact of {@code ctx}, a map copied only
	 * when it holds one of them.
	 * @param names the names the binder binds
	 * @param ctx the compile context
	 * @return the outer scope's facts, for {@link #restore}
	 */
	static WasmShadowedNames shadow(Collection<String> names, WasmLispCompiler.Ctx ctx) {
		WasmShadowedNames saved = new WasmShadowedNames(ctx.rawLocals, ctx.localIntLambdas, ctx.declaredIntegers,
				ctx.declaredArrays, ctx.arrayLocals, ctx.boxedVars);
		ctx.rawLocals = without(ctx.rawLocals, names);
		ctx.localIntLambdas = without(ctx.localIntLambdas, names);
		ctx.declaredIntegers = without(ctx.declaredIntegers, names);
		ctx.declaredArrays = without(ctx.declaredArrays, names);
		ctx.arrayLocals = without(ctx.arrayLocals, names);
		ctx.boxedVars = without(ctx.boxedVars, names);
		return saved;
	}

	/**
	 * Puts the outer scope's facts back.
	 * @param ctx the compile context
	 */
	void restore(WasmLispCompiler.Ctx ctx) {
		ctx.rawLocals = this.rawLocals;
		ctx.localIntLambdas = this.localIntLambdas;
		ctx.declaredIntegers = this.declaredIntegers;
		ctx.declaredArrays = this.declaredArrays;
		ctx.arrayLocals = this.arrayLocals;
		ctx.boxedVars = this.boxedVars;
	}

	private static <V> Map<String, V> without(Map<String, V> map, Collection<String> names) {
		if (names.stream().noneMatch(map::containsKey)) {
			return map;
		}
		Map<String, V> copy = new HashMap<>(map);
		copy.keySet().removeAll(names);
		return copy;
	}

	private static Set<String> without(Set<String> set, Collection<String> names) {
		if (names.stream().noneMatch(set::contains)) {
			return set;
		}
		Set<String> copy = new HashSet<>(set);
		copy.removeAll(names);
		return copy;
	}

}
