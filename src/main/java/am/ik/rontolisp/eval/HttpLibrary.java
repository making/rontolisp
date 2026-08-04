package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.ClackEnv;
import am.ik.rontolisp.compiler.FetchResponseShape;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code --component} implementation of BOTH {@code rontolisp:fetch} and
 * {@code rontolisp:http-handler}: ONE Lisp-source library ({@code http.lisp}) over a
 * wit-imported {@code wasi:http@0.3.0} surface ({@code http.wit}), both on the classpath.
 * In 0.3 the outgoing ({@code client.send}) and incoming ({@code handler.handle}) halves
 * share one signature in one package and a symmetric body API, so the 0.2-era fetch.lisp
 * / serve.lisp split is gone. The interpreter and the JVM keep their
 * {@code java.net.http} / JDK {@code HttpServer} implementations; Preview 1 has neither.
 *
 * <p>
 * <strong>It lowers {@code http.lisp}'s own {@code rontolisp:wit-import} directives
 * ITSELF</strong>, calling {@link WitImportDirective#lower}:
 * {@code eval/WitImportInliner} has already run by the time a library is spliced, so
 * directives spliced at the ordinary point would be passed over. Lowering them here
 * produces exactly the {@code %component-import} forms the inliner would have.
 *
 * <p>
 * <strong>The splice follows the reachable half.</strong> The member filter (and the
 * spliced defun set) is computed by a reachability walk from the active roots --
 * {@code rontolisp:fetch} when the program fetches, {@code %serve-handle} when it serves
 * -- so a fetch-only program binds no serve-side member (no {@code task-return}, no
 * {@code handler} machinery) and a serve-only program binds no {@code client.send}. This
 * matters beyond byte-size: the serve-side bindings are only valid inside a serve
 * component, and splicing them into a fetch-only program would be a compile error.
 */
public final class HttpLibrary {

	/**
	 * The core export name of the lifted handler function ({@code handler.handle}).
	 */
	public static final String HANDLE_EXPORT = "handle";

	private static volatile @Nullable List<LispVal> forms;

	private static volatile @Nullable String witText;

	private HttpLibrary() {
	}

	/**
	 * The compile-path splice: when a {@code --component} program references
	 * {@code rontolisp:fetch} and/or uses {@code rontolisp:http-handler}, splice
	 * {@code http.lisp}'s reachable half (its {@code wit-import} directives already
	 * lowered), plus -- when serving -- a {@code %serve-dispatch} bridge to the program's
	 * handler and a {@code rontolisp:wasm-export} of {@code %serve-handle}. A no-op on
	 * every other backend and when the program neither fetches nor serves.
	 * @param program the top-level forms
	 * @param backend the backend being compiled for
	 * @param serve whether this is a {@code rontolisp:http-handler} (serve-mode)
	 * component
	 * @return the program, spliced when applicable
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend, boolean serve) {
		if (backend != WitExportDirective.Backend.WASM_COMPONENT) {
			return program;
		}
		boolean fetch = referencesFetch(program) && !definesFetch(program);
		// Read the :raw-body mode BEFORE the directive forms are dropped/rewritten below.
		boolean bufferBody = usesBufferedBody(program);
		String handler = null;
		List<LispVal> withoutDirective = new ArrayList<>();
		for (LispVal form : program) {
			if (isHttpHandlerForm(form)) {
				if (handler == null) {
					handler = handlerName((LispCons) form);
				}
				// Drop the directive; the handler export replaces it. Only the first
				// http-handler is honored (a component exports one handler).
				continue;
			}
			withoutDirective.add(form);
		}
		if (serve) {
			// A NESTED (rontolisp:http-handler '<literal-name> ...) call -- the
			// clack-handler-rontolisp shim calls the directive inside its run defun --
			// still yields a static handler name: extract it for the export wiring and
			// lower the call site to nil (the host owns the socket; instantiation runs
			// the top-level program, clackup stores its app, and requests arrive
			// through the exported handle).
			String[] nested = new String[1];
			List<LispVal> rewritten = new ArrayList<>(withoutDirective.size());
			for (LispVal form : withoutDirective) {
				rewritten.add(rewriteNestedHandlerCalls(form, nested));
			}
			withoutDirective = rewritten;
			if (handler == null) {
				handler = nested[0];
			}
		}
		else {
			handler = null;
		}
		if (!fetch && handler == null) {
			return program;
		}
		List<LispVal> httpForms = forms();
		// The library's defuns by name, in source order.
		Map<String, LispVal> defunOf = new LinkedHashMap<>();
		for (LispVal form : httpForms) {
			String name = defunName(form);
			if (name != null) {
				defunOf.put(name, form);
			}
		}
		// Reachability from the active roots: the spliced defun set AND the WIT member
		// filter follow it, so the inactive half costs nothing and binds nothing.
		Set<String> roots = new HashSet<>();
		if (fetch) {
			roots.add(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FETCH));
		}
		if (handler != null) {
			roots.add("%SERVE-HANDLE");
		}
		Set<String> reachable = reachableDefuns(defunOf, roots);
		Set<String> members = new HashSet<>();
		for (String name : reachable) {
			collectNames(defunOf.get(name), members);
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal form : httpForms) {
			if (WitImportDirective.isDirective(form)) {
				WitImportDirective.Directive directive = WitImportDirective.parse((LispCons) form);
				out.addAll(WitImportDirective.lower(directive, witText(), directive.path(),
						WitExportDirective.Backend.WASM_COMPONENT, members, members));
				continue;
			}
			String name = defunName(form);
			if (name != null && !reachable.contains(name)) {
				continue;
			}
			out.add(form);
		}
		out.addAll(withoutDirective);
		if (handler != null) {
			// The bridge to the user's handler and the handler export of %serve-handle
			// (one own<request> parameter; the response is delivered mid-task through
			// the task-return built-in, so the core function returns nothing). Appended
			// AFTER the program so a package-qualified nested handler name (the
			// clack-handler-rontolisp shim's %app) resolves against the shim's own
			// defpackage, which the program splices ahead of it.
			// %serve-request-body is the directive's :raw-body mode, frozen at compile
			// time: the default passes rontolisp's asynchronous request stream through
			// untouched (nothing is buffered, the body streams from the host), while
			// :buffered drains it and wraps the text in the synchronously readable Gray
			// stream a Clack application needs. Synthesizing the matching body -- rather
			// than branching on a runtime flag -- is what keeps a default-mode component
			// free of http-server.lisp's buffered-body machinery entirely (the
			// HttpServerLibrary splice filters on the same mode).
			String requestBody = bufferBody ? """
					(rontolisp:async-defun %serve-request-body (%serve-body-stream)
					  (let ((%serve-body-drained (rontolisp:await (rontolisp::%http-drain %serve-body-stream))))
					    (rontolisp::%http-body-stream %serve-body-drained)))
					""" : """
					(rontolisp:async-defun %serve-request-body (%serve-body-stream) %serve-body-stream)
					""";
			out.addAll(LispReader.readAllFromString("""
					(defun %%serve-dispatch (%%serve-req) (%s %%serve-req))
					%s
					(rontolisp:wasm-export '%%serve-handle :as "handle" :params '(:int) :returns :void)
					""".formatted(handler, requestBody), Features.INTERPRETER));
		}
		return out;
	}

	/**
	 * Returns whether any top-level form references the built-in {@code rontolisp:fetch}
	 * (as a call or a first-class value) -- the fetch trigger.
	 * @param program the top-level forms
	 * @return {@code true} when the program references {@code rontolisp:fetch}
	 */
	public static boolean referencesFetch(List<LispVal> program) {
		for (LispVal form : program) {
			if (references(form, LispNames.FETCH)) {
				return true;
			}
		}
		return false;
	}

	private static boolean references(LispVal form, String member) {
		return switch (form) {
			case LispSymbol sym -> namesRontolispMember(sym.name(), member);
			case LispCons cons -> references(cons.car(), member) || references(cons.cdr(), member);
			default -> false;
		};
	}

	// Whether the symbol names the given rontolisp-package member, in any source
	// spelling (rontolisp:fetch, rl:fetch, rontolisp::fetch): this scan runs before
	// PackageResolver normalizes the program, so it must normalize itself --
	// splitQualified resolves the built-in nicknames.
	private static boolean namesRontolispMember(String symbolName, String member) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && member.equals(qn.member());
	}

	// Whether the user program already defines rontolisp:fetch (a defun of it), so the
	// splice does not collide with it -- the dedup guard every library splice carries.
	private static boolean definesFetch(List<LispVal> program) {
		for (LispVal form : program) {
			String name = defunName(form);
			if (name != null && namesRontolispMember(name, LispNames.FETCH)) {
				return true;
			}
		}
		return false;
	}

	private static @Nullable String defunName(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && isDefunHead(head.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		return null;
	}

	// A defun head in any source spelling: bare defun, cl:defun and
	// rontolisp:async-defun, the latter two normalized through splitQualified so the
	// built-in nicknames (rl:async-defun) and the :: spelling count too.
	private static boolean isDefunHead(String name) {
		if (LispNames.DEFUN.equals(name)) {
			return true;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn != null && ((LispNames.CL_PKG.equals(qn.pkg()) && LispNames.DEFUN.equals(qn.member()))
				|| (LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.ASYNC_DEFUN.equals(qn.member())));
	}

	/**
	 * Returns whether the program asks for the buffered request body
	 * ({@code :raw-body :buffered}); the flag decides the synthesized
	 * {@code %serve-request-body} here and the {@code HttpServerLibrary} splice filter.
	 * Delegates to {@link ClackEnv#usesBufferedBody} -- the walker lives in
	 * {@code compiler} because the JVM compiler reads the same flag and
	 * {@code codegen.jvm} must not depend on {@code eval}.
	 * @param program the top-level forms
	 * @return {@code true} when the buffered body was asked for
	 */
	public static boolean usesBufferedBody(List<LispVal> program) {
		return ClackEnv.usesBufferedBody(program);
	}

	private static boolean isHttpHandlerForm(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol sym)) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.HTTP_HANDLER.equals(qn.member());
	}

	// Rewrites every NESTED (rontolisp:http-handler 'name ...) call inside the form to
	// nil, recording the first literal handler name into holder[0]. Quoted data is left
	// untouched; unchanged subtrees keep their identity (no needless rebuild of the
	// whole spliced program).
	private static LispVal rewriteNestedHandlerCalls(LispVal form, String[] holder) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol sym && LispNames.QUOTE.equals(sym.name())) {
			return form;
		}
		if (isHttpHandlerForm(form)) {
			if (holder[0] == null) {
				holder[0] = handlerName(cons);
			}
			return am.ik.rontolisp.LispNil.INSTANCE;
		}
		LispVal car = rewriteNestedHandlerCalls(cons.car(), holder);
		LispVal cdr = rewriteNestedHandlerCalls(cons.cdr(), holder);
		if (car == cons.car() && cdr == cons.cdr()) {
			return form;
		}
		return new LispCons(car, cdr);
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

	// The library defuns reachable from the roots, walking every symbol a reachable
	// body mentions (a (function f) reference counts like a call).
	private static Set<String> reachableDefuns(Map<String, LispVal> defunOf, Set<String> roots) {
		Set<String> reachable = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		for (String root : roots) {
			if (defunOf.containsKey(root)) {
				reachable.add(root);
				queue.add(root);
			}
		}
		while (!queue.isEmpty()) {
			Set<String> mentioned = new HashSet<>();
			collectNames(defunOf.get(queue.remove()), mentioned);
			for (String name : mentioned) {
				if (defunOf.containsKey(name) && reachable.add(name)) {
					queue.add(name);
				}
			}
		}
		return reachable;
	}

	private static void collectNames(@Nullable LispVal form, Set<String> names) {
		switch (form) {
			case LispSymbol sym -> {
				names.add(sym.name());
				// The reader upcases user spellings while WIT member names are
				// lower-kebab:
				// record the lowercase twin too, so the member filter matches every
				// referenced binding (mirrors WitImportInliner.collectNames).
				names.add(sym.name().toLowerCase(java.util.Locale.ROOT));
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				if (qn != null) {
					names.add(qn.member());
					names.add(qn.member().toLowerCase(java.util.Locale.ROOT));
				}
			}
			case LispCons cons -> {
				collectNames(cons.car(), names);
				collectNames(cons.cdr(), names);
			}
			case null, default -> {
			}
		}
	}

	private static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (HttpLibrary.class) {
				cached = forms;
				if (cached == null) {
					// http.lisp plus the plist builder/accessor defuns generated from
					// the http-plist WIT records (the plist shape is derived, not
					// hand-written, on this backend too); the reachability walk drops
					// whichever generated helpers the active half never calls.
					List<LispVal> all = new ArrayList<>(
							LispReader.readAllFromString(readResource("http.lisp"), Features.INTERPRETER));
					all.addAll(
							LispReader.readAllFromString(FetchResponseShape.lispHelpersSource(), Features.INTERPRETER));
					cached = List.copyOf(all);
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String witText() {
		String cached = witText;
		if (cached == null) {
			synchronized (HttpLibrary.class) {
				cached = witText;
				if (cached == null) {
					cached = readResource("http.wit");
					witText = cached;
				}
			}
		}
		return cached;
	}

	private static String readResource(String name) {
		try (InputStream in = HttpLibrary.class.getResourceAsStream(name)) {
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
