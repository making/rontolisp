package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The compile-path answer to a HOST-DRIVEN REACTOR served by {@code clack:clackup}: turns
 * the {@code rontolisp::%http-reactor} marker a Clack handler backend's {@code run}
 * carries into the {@code rontolisp:wasm-export} the host actually calls. Two backends
 * carry it: {@code clack-handler-cloudflare-workers} on every WASM compile (that backend
 * IS the reactor shape, on every target), and {@code clack-handler-rontolisp} under
 * {@code #+rontolisp-reactor} ({@code --no-wasi} / {@code --no-gc}) -- both naming the
 * ONE shared dispatcher, {@code rontolisp::%http-reactor-dispatch}
 * ({@code http-reactor.lisp}), so a program that splices both shims yields two IDENTICAL
 * markers and first-wins below is not a choice.
 *
 * <p>
 * A reactor's entry point is an EXPORT, and {@link LispNames#WASM_EXPORT} needs a literal
 * quoted name at compile time -- so a program whose whole body is
 * {@code (clack:clackup #'app :server :rontolisp)} can no longer declare it: the name
 * only exists inside the handler backend, and the decision to export at all is taken at
 * run time, when clackup applies {@code run}. This pass reads the decision out of the
 * source instead: the shim's {@code run} contains
 * {@code (rontolisp::%http-reactor 'rontolisp::%http-reactor-dispatch "handle-request")},
 * which is lowered to {@code nil} (nothing to do at run time; the app store around it
 * stays) and answered with
 *
 * <pre>{@code
 * (defun %reactor-dispatch (%reactor-json) (rontolisp::%http-reactor-dispatch %reactor-json))
 * (rontolisp:wasm-export '%reactor-dispatch :as "handle-request" :params '(:string) :returns :string)
 * }</pre>
 *
 * appended AFTER the program, so a package-qualified dispatcher name resolves against the
 * spliced definitions whatever package the program ended in.
 *
 * <p>
 * The precedent is exact and deliberate: {@link HttpLibrary} reads the
 * {@code rontolisp:http-handler} directive NESTED in the {@code clack-handler-rontolisp}
 * shim's {@code run} the same way, for the same reason. It is a SEPARATE marker rather
 * than an overload of that directive because {@code http-handler} means "bind a socket"
 * on every other backend, which is precisely what a reactor does not do.
 *
 * <p>
 * A no-op on the interpreter and the JVM backend -- there the shims never even read the
 * marker ({@code #+rontolisp-wasm} / {@code #+rontolisp-reactor}) because the host calls
 * the dispatcher as an ordinary function.
 */
public final class HttpReactorInliner {

	/** The synthesized bridge defun the export names. */
	static final String DISPATCH_BRIDGE = "%REACTOR-DISPATCH";

	private HttpReactorInliner() {
	}

	/**
	 * Lowers every {@code rontolisp::%http-reactor} marker to {@code nil} and appends the
	 * bridge + {@code wasm-export} the first one asks for. Returns the program unchanged
	 * on a non-WASM backend and when no marker is present.
	 * @param program the top-level forms
	 * @param backend the backend being compiled for
	 * @return the program, with the reactor export synthesized when applicable
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend) {
		if (backend == WitExportDirective.Backend.OTHER) {
			return program;
		}
		String[] marker = new String[2];
		List<LispVal> rewritten = new ArrayList<>(program.size());
		for (LispVal form : program) {
			rewritten.add(lower(form, marker));
		}
		if (marker[0] == null) {
			return program;
		}
		if (declaresExport(program, marker[1])) {
			// The program already exports that name itself -- the pre-clackup shape,
			// where the user writes the wasm-export and calls `handle` from it. It has
			// taken the entry point over, so there is nothing to synthesize; adding the
			// bridge anyway would emit a module with a DUPLICATE export name, which no
			// engine will compile. The marker still has to go (nothing defines it).
			return rewritten;
		}
		List<LispVal> out = new ArrayList<>(rewritten);
		// The envelope is a JSON request string in, a JSON response string out -- the
		// handler backend's documented API, so the boundary types are fixed here rather
		// than being another thing the marker carries.
		out.addAll(LispReader.readAllFromString("""
				(defun %s (%%reactor-json) (%s %%reactor-json))
				(rontolisp:wasm-export '%s :as "%s" :params '(:string) :returns :string)
				""".formatted(DISPATCH_BRIDGE, marker[0], DISPATCH_BRIDGE, marker[1]), Features.INTERPRETER));
		return out;
	}

	// Rewrites every NESTED (rontolisp::%http-reactor 'name "export") call inside the
	// form to nil, recording the first one's arguments into holder. Quoted data is left
	// untouched; unchanged subtrees keep their identity (no needless rebuild).
	private static LispVal lower(LispVal form, String[] holder) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol sym && LispNames.QUOTE.equals(sym.name())) {
			return form;
		}
		if (isMarker(cons)) {
			if (holder[0] == null) {
				parse(cons, holder);
			}
			return LispNil.INSTANCE;
		}
		LispVal car = lower(cons.car(), holder);
		LispVal cdr = lower(cons.cdr(), holder);
		if (car == cons.car() && cdr == cons.cdr()) {
			return form;
		}
		return new LispCons(car, cdr);
	}

	// Whether a top-level rontolisp:wasm-export directive already claims the export
	// name. The name derivation mirrors WasmExportCompiler.Decl: the :as alias when
	// present, otherwise the lowercased unqualified member of the quoted Lisp name --
	// duplicated rather than imported because `eval` must not depend on `codegen.wasm`.
	private static boolean declaresExport(List<LispVal> program, String exportName) {
		for (LispVal form : program) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)) {
				continue;
			}
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
			if (qn == null || !LispNames.RONTOLISP_PKG.equals(qn.pkg()) || !LispNames.WASM_EXPORT.equals(qn.member())) {
				continue;
			}
			if (exportName.equals(declaredExportName(cons))) {
				return true;
			}
		}
		return false;
	}

	private static @Nullable String declaredExportName(LispCons directive) {
		List<LispVal> args = directive.toList();
		for (int i = 2; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof LispSymbol key && key.isKeyword() && ":AS".equalsIgnoreCase(key.name())
					&& args.get(i + 1) instanceof LispString alias) {
				return alias.value();
			}
		}
		if (args.size() < 2 || !(args.get(1) instanceof LispCons quote) || !(quote.car() instanceof LispSymbol q)
				|| !LispNames.QUOTE.equals(q.name()) || !(quote.cdr() instanceof LispCons rest)
				|| !(rest.car() instanceof LispSymbol name)) {
			return null;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name.name());
		return (qn == null ? name.name() : qn.member()).toLowerCase(Locale.ROOT);
	}

	private static boolean isMarker(LispCons cons) {
		if (!(cons.car() instanceof LispSymbol sym)) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.HTTP_REACTOR.equals(qn.member());
	}

	// (rontolisp::%http-reactor 'dispatch "export-name") -> holder = {dispatch name,
	// export name}. Both arguments are literals by contract: the whole point of the
	// marker is that the export is decided at compile time.
	private static void parse(LispCons cons, String[] holder) {
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException(LispNames.HTTP_REACTOR
					+ " expects a quoted dispatcher name and an export name, got: " + cons.print());
		}
		if (!(args.get(1) instanceof LispCons quote && quote.car() instanceof LispSymbol q
				&& LispNames.QUOTE.equals(q.name()) && quote.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol name)) {
			throw new UnsupportedOperationException(LispNames.HTTP_REACTOR
					+ " expects a quoted dispatcher name (e.g. 'dispatch), got: " + args.get(1).print());
		}
		if (!(args.get(2) instanceof LispString exportName)) {
			throw new UnsupportedOperationException(
					LispNames.HTTP_REACTOR + " expects a literal string export name, got: " + args.get(2).print());
		}
		holder[0] = name.name();
		holder[1] = exportName.value();
	}

}
