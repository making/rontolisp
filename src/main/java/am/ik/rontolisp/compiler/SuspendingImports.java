package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;

/**
 * The build-time answers a {@code (rontolisp:wasm-import ... :async t)} declaration makes
 * possible ({@code .kb/wasm-import.md}). The option says the host function may SUSPEND (a
 * {@code WebAssembly.Suspending}-wrapped import under JSPI): the call then answers a
 * future that {@code rontolisp:await} resolves, and -- since the compiler emits nothing
 * for the suspension itself -- the BUILD is the only place that can state what the host
 * now owes. Two facts follow from the declaration, both computed over the same load-path
 * walk {@link NoWasiLoadPathRefusals} runs:
 *
 * <ul>
 * <li><strong>Which exports can reach a suspending import</strong> ({@link #reaches},
 * asked of each export's target): a suspending import may only be called on a stack
 * entered through {@code WebAssembly.promising}, so the host must know which exports to
 * wrap. The walk follows calls, not values -- {@link #anyTakenAsValue} is the
 * conservative fallback that widens the answer to every export when an import escapes as
 * {@code #'name}.</li>
 * <li><strong>Whether the load path reaches one</strong> ({@link #onLoadPath}): an ERROR,
 * not a line -- {@code _initialize} runs on a stack no {@code promising} entered, a
 * suspension there is a TRAP naming nobody, and unlike {@code --host-fetch} (where a
 * synchronous host is an equally valid implementation) the program has DECLARED that the
 * host may suspend.</li>
 * </ul>
 */
public final class SuspendingImports {

	private SuspendingImports() {
	}

	/**
	 * The {@code :async t} host imports the program declares, in declaration order.
	 * @param program the resolved, flattened top-level forms
	 * @return Lisp name -> {@code module.field} (the host's spelling of the import)
	 */
	public static Map<String, String> declared(List<LispVal> program) {
		Map<String, String> imports = new LinkedHashMap<>();
		for (LispVal form : program) {
			if (WasmImportDirective.isImportForm(form)) {
				WasmImportDirective directive = WasmImportDirective.parse((LispCons) form);
				if (directive.async()) {
					imports.put(directive.name(), directive.module() + "." + directive.field());
				}
			}
		}
		return imports;
	}

	/**
	 * The error messages for every {@code :async t} import the LOAD PATH reaches -- empty
	 * when it reaches none. A handler does not silence one: a suspension is a trap, not a
	 * condition.
	 * @param program the resolved, flattened top-level forms
	 * @param suspendingImports the {@code :async t} import names ({@link #declared})
	 * @return one message per reached import, position prefix included
	 */
	public static List<String> onLoadPath(List<LispVal> program, Set<String> suspendingImports) {
		List<String> messages = new ArrayList<>();
		NoWasiLoadPathRefusals.walk(program, false, false, suspendingImports, null).values().forEach(found -> {
			if (found.kind() == NoWasiLoadPathRefusals.Kind.SUSPEND) {
				messages.add(SourceProvenance.prefix(found.site()) + found.operator()
						+ " is declared :async t (the host may suspend) and is reachable from a top-level form ("
						+ found.origin() + "), so it can run while the module LOADS -- on a stack no"
						+ " WebAssembly.promising entered, where a suspension TRAPS and the host sees only"
						+ " RuntimeError naming nobody. Move the call out of the load path, or drop :async t"
						+ " if this host answers synchronously");
			}
		});
		return messages;
	}

	/**
	 * Whether the given function can reach an {@code :async t} import through calls --
	 * the question the host's {@code WebAssembly.promising} wrapping depends on, asked of
	 * each export's target.
	 * @param program the resolved, flattened top-level forms
	 * @param suspendingImports the {@code :async t} import names ({@link #declared})
	 * @param function the (export target) function name
	 * @return {@code true} when a call chain from the function reaches an import
	 */
	public static boolean reaches(List<LispVal> program, Set<String> suspendingImports, String function) {
		return reaches(program, suspendingImports, false, function);
	}

	/**
	 * Whether the given function can reach an import the HOST may suspend -- the
	 * {@code :async t} ones, and under {@code --host-fetch} also {@code env.fetch}, whose
	 * suspension the flag rather than a declaration allows for (the lowering leaves the
	 * import plain, because a load-path fetch is a WARNING there and an {@code :async t}
	 * one is an error; the build's own {@code --host-fetch} obligation line already names
	 * the same {@code promising} requirement).
	 * @param program the resolved, flattened top-level forms
	 * @param suspendingImports the {@code :async t} import names ({@link #declared})
	 * @param hostFetch whether {@code --host-fetch} routes {@code rontolisp:fetch}
	 * @param function the (export target) function name
	 * @return {@code true} when a call chain from the function reaches such an import
	 */
	public static boolean reaches(List<LispVal> program, Set<String> suspendingImports, boolean hostFetch,
			String function) {
		return NoWasiLoadPathRefusals.walk(program, false, hostFetch, true, suspendingImports, function)
			.values()
			.stream()
			.anyMatch(found -> found.kind() == NoWasiLoadPathRefusals.Kind.SUSPEND
					|| (hostFetch && found.kind() == NoWasiLoadPathRefusals.Kind.FETCH));
	}

	/**
	 * Whether any {@code :async t} import escapes as a VALUE ({@code #'name}) anywhere --
	 * the walk follows calls only, so an escaped import can be reached by whoever
	 * received it and the per-export answer widens to "any export".
	 * @param program the resolved, flattened top-level forms
	 * @param suspendingImports the {@code :async t} import names ({@link #declared})
	 * @return {@code true} when an import is taken as a value
	 */
	public static boolean anyTakenAsValue(List<LispVal> program, Set<String> suspendingImports) {
		return anyTakenAsValue(program, suspendingImports, false);
	}

	/**
	 * Whether any function that can reach an import the host may suspend escapes as a
	 * VALUE ({@code #'name}) anywhere in the program -- the import itself, or anything
	 * that calls one. The per-export walk is seeded from that export's own bodies, so a
	 * value handed over somewhere ELSE (a {@code defvar} of {@code #'helper} the export
	 * later {@code funcall}s) is invisible to it, and under-reporting there is a missing
	 * {@code WebAssembly.promising} rather than a wasted one -- so it widens the answer
	 * to "any export" instead.
	 * @param program the resolved, flattened top-level forms
	 * @param suspendingImports the {@code :async t} import names ({@link #declared})
	 * @param hostFetch whether {@code --host-fetch} routes {@code rontolisp:fetch}
	 * @return {@code true} when such a value escapes
	 */
	public static boolean anyTakenAsValue(List<LispVal> program, Set<String> suspendingImports, boolean hostFetch) {
		Set<String> escaping = new LinkedHashSet<>();
		program.forEach(form -> collectFunctionValues(form, escaping));
		for (String name : escaping) {
			if (suspendingImports.contains(name) || reaches(program, suspendingImports, hostFetch, name)) {
				return true;
			}
		}
		return false;
	}

	// Every name written as #'name anywhere in a form, quoted data included: a quote does
	// not stop a program from later applying what it holds, and this is the conservative
	// side of the question.
	private static void collectFunctionValues(LispVal form, Set<String> into) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head && LispNames.FUNCTION.equals(head.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			into.add(name.name());
			return;
		}
		collectFunctionValues(cons.car(), into);
		collectFunctionValues(cons.cdr(), into);
	}

}
