package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code --component} implementation of {@code rontolisp:http-handler}'s HTTP glue: a
 * Lisp-source library ({@code serve.lisp}) over a wit-imported {@code wasi:http/types} /
 * {@code wasi:io} surface (reusing {@code fetch.wit}), both on the classpath. It replaces
 * the hand-written serve adapter ({@code adapter-http-server.wat}) -- the mirror of
 * {@link FetchLibrary}: fetch IMPORTS {@code wasi:http/outgoing-handler}, serve EXPORTS
 * {@code wasi:http/incoming-handler}, and both drive {@code wasi:http/types} from Lisp.
 *
 * <p>
 * The interpreter and the JVM keep their JDK {@code HttpServer}; Preview 1 cannot serve.
 * So this splice fires on ONE path -- a {@code --component} program using
 * {@code rontolisp:http-handler}.
 *
 * <p>
 * <strong>It lowers {@code serve.lisp}'s own {@code rontolisp:wit-import} directives
 * ITSELF</strong>, calling {@link WitImportDirective#lower}, exactly like
 * {@link FetchLibrary}: {@code eval/WitImportInliner} has already run by the time a
 * library is spliced, so a {@code serve.lisp} spliced at the ordinary point would carry
 * directives the inliner has already passed. Lowering them here produces exactly the
 * {@code %component-import} forms that inliner would have.
 *
 * <p>
 * The user's handler is reached through {@code %serve-dispatch}, a one-line bridge this
 * class synthesizes from the {@code (rontolisp:http-handler 'name)} directive. The
 * {@code %serve-handle} defun is exported as the
 * {@code wasi:http/incoming-handler#handle} function through a
 * {@code rontolisp:wasm-export} of two handle-carrying {@code :int} params (a resource
 * handle boxes/unboxes exactly as an {@code :int}); the serve component builder lifts
 * that core wrapper against the {@code own<incoming-request>} /
 * {@code own<response-outparam>} function type, aliased out of the wit-imported
 * {@code wasi:http/types} instance.
 */
public final class ServeLibrary {

	/**
	 * The core export name of the lifted handle wrapper (the incoming-handler function).
	 */
	public static final String HANDLE_EXPORT = "handle";

	private static volatile @Nullable List<LispVal> forms;

	private static volatile @Nullable String witText;

	private ServeLibrary() {
	}

	/**
	 * The compile-path splice: when a {@code --component} program uses
	 * {@code rontolisp:http-handler}, replace the directive with {@code serve.lisp} (its
	 * {@code wit-import} directives already lowered), a {@code %serve-dispatch} bridge to
	 * the program's handler, and a {@code rontolisp:wasm-export} of
	 * {@code %serve-handle}. A no-op on every other backend and when the program does not
	 * serve.
	 * @param program the top-level forms
	 * @param backend the backend being compiled for
	 * @param serve whether this is a {@code rontolisp:http-handler} (serve-mode)
	 * component
	 * @return the program, spliced when applicable
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend, boolean serve) {
		if (backend != WitExportDirective.Backend.WASM_COMPONENT || !serve) {
			return program;
		}
		String handler = null;
		List<LispVal> withoutDirective = new ArrayList<>();
		for (LispVal form : program) {
			if (isHttpHandlerForm(form)) {
				if (handler == null) {
					handler = handlerName((LispCons) form);
				}
				// Drop the directive; the incoming-handler export replaces it. Only the
				// first http-handler is honored (a component exports one
				// incoming-handler).
				continue;
			}
			withoutDirective.add(form);
		}
		if (handler == null) {
			return program;
		}
		List<LispVal> serveForms = forms();
		// The members serve.lisp itself references, used as BOTH the member filter and
		// the
		// drop filter for the lowering (the fetch.lisp pattern): serve.lisp is the sole
		// caller of the bound interfaces.
		Set<String> members = referencedNames(serveForms);
		List<LispVal> out = new ArrayList<>();
		for (LispVal form : serveForms) {
			if (WitImportDirective.isDirective(form)) {
				WitImportDirective.Directive directive = WitImportDirective.parse((LispCons) form);
				out.addAll(WitImportDirective.lower(directive, witText(), directive.path(),
						WitExportDirective.Backend.WASM_COMPONENT, members, members));
			}
			else {
				out.add(form);
			}
		}
		// The bridge to the user's handler and the incoming-handler export of
		// %serve-handle.
		out.addAll(LispReader.readAllFromString(bridgeForms(handler)));
		out.addAll(withoutDirective);
		return out;
	}

	// (defun %serve-dispatch (r) (HANDLER r)) -- serve.lisp calls this; plus the
	// wasm-export
	// of %serve-handle as the incoming-handler#handle function. %serve-handle takes two
	// resource handles (own<incoming-request>, own<response-outparam>); a handle boxes
	// and
	// unboxes exactly as an :int, so the core wrapper's declared param types are :int,
	// and
	// the serve component builder lifts it against the own<> function type from the WIT.
	private static String bridgeForms(String handler) {
		return """
				(defun %%serve-dispatch (%%serve-req) (%s %%serve-req))
				(rontolisp:wasm-export '%%serve-handle :as "handle" :params '(:int :int) :returns :void)
				""".formatted(handler);
	}

	private static boolean isHttpHandlerForm(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol sym)) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.HTTP_HANDLER.equals(qn.member());
	}

	// Extracts the handler function name from (rontolisp:http-handler 'name [port]).
	private static String handlerName(LispCons form) {
		List<LispVal> args = form.toList();
		if (args.size() < 2) {
			throw new UnsupportedOperationException(
					LispNames.HTTP_HANDLER + " expects a quoted handler name, got: " + form.print());
		}
		LispVal ref = args.get(1);
		// 'name reads as (quote name).
		if (ref instanceof LispCons quote && quote.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& quote.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		throw new UnsupportedOperationException(LispNames.HTTP_HANDLER
				+ " expects a quoted handler name (e.g. 'handle) when compiling to a component, got: " + ref.print());
	}

	// Every symbol name mentioned in serve.lisp (its own wit-import directives excluded),
	// plus the member part of each qualified name -- the set the lowering prunes by.
	private static Set<String> referencedNames(List<LispVal> program) {
		Set<String> names = new HashSet<>();
		for (LispVal form : program) {
			if (!WitImportDirective.isDirective(form)) {
				collectNames(form, names);
			}
		}
		return names;
	}

	private static void collectNames(LispVal form, Set<String> names) {
		switch (form) {
			case LispSymbol sym -> {
				names.add(sym.name());
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				if (qn != null) {
					names.add(qn.member());
				}
			}
			case LispCons cons -> {
				collectNames(cons.car(), names);
				collectNames(cons.cdr(), names);
			}
			default -> {
			}
		}
	}

	private static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (ServeLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readResource("serve.lisp"));
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String witText() {
		String cached = witText;
		if (cached == null) {
			synchronized (ServeLibrary.class) {
				cached = witText;
				if (cached == null) {
					cached = readResource("fetch.wit");
					witText = cached;
				}
			}
		}
		return cached;
	}

	private static String readResource(String name) {
		try (InputStream in = ServeLibrary.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException(name + " is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
