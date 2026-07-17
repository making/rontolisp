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
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
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
		if (!serve) {
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
			roots.add("%serve-handle");
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
		if (handler != null) {
			// The bridge to the user's handler and the handler export of %serve-handle
			// (one own<request> parameter; the response is delivered mid-task through
			// the task-return built-in, so the core function returns nothing).
			out.addAll(LispReader.readAllFromString("""
					(defun %%serve-dispatch (%%serve-req) (%s %%serve-req))
					(rontolisp:wasm-export '%%serve-handle :as "handle" :params '(:int) :returns :void)
					""".formatted(handler)));
		}
		out.addAll(withoutDirective);
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
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				if (qn != null) {
					names.add(qn.member());
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
					cached = LispReader.readAllFromString(readResource("http.lisp"));
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
