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
 * The {@code --component} implementation of the built-in {@code rontolisp:fetch}: a
 * Lisp-source library ({@code fetch.lisp}) over a wit-imported {@code wasi:http} /
 * {@code wasi:io} surface ({@code fetch.wit}), both on the classpath. The interpreter and
 * the JVM keep their {@code java.net.http} fetch; Preview 1 has none; {@code --no-gc}
 * rejects {@code wit-import}. So this splice fires on ONE path -- a {@code --component},
 * non-serve program that references {@code rontolisp:fetch}.
 *
 * <p>
 * <strong>It lowers {@code fetch.lisp}'s own {@code rontolisp:wit-import} directives
 * ITSELF</strong>, calling {@link WitImportDirective#lower}, which is why the ordering
 * trap does not bite: {@code eval/WitImportInliner} runs before every library splice, so
 * a {@code fetch.lisp} spliced at the ordinary point would carry directives the inliner
 * has already passed. Lowering them here produces exactly the {@code %component-import}
 * forms that inliner would have, and those survive the rest of the pipeline unchanged.
 * The WIT text comes from the classpath (not {@code SourceLoader}), like every built-in
 * library.
 *
 * <p>
 * <strong>Serve + fetch splices this too.</strong> A {@code rontolisp:http-handler}
 * program that also fetches carries BOTH {@code serve.lisp} (via {@link ServeLibrary})
 * and {@code fetch.lisp}. Both bind {@code wasi:http/types} / {@code wasi:io}, in
 * different {@code %}-packages, and the two bindings are merged into one component-level
 * import downstream ({@code WasmComponentImportCompiler.mergeByIface}), so there is no
 * collision and no hand-written adapter -- serve.lisp handles the incoming half over
 * {@code wasi:http/incoming-handler}, fetch.lisp the outgoing half over
 * {@code wasi:http/outgoing-handler}.
 */
public final class FetchLibrary {

	private static volatile @Nullable List<LispVal> forms;

	private static volatile @Nullable String witText;

	private FetchLibrary() {
	}

	/**
	 * The compile-path splice: when a {@code --component} program references
	 * {@code rontolisp:fetch}, prepend {@code fetch.lisp} with its {@code wit-import}
	 * directives already lowered. This fires whether or not the program also serves (a
	 * served handler that fetches gets both this and {@link ServeLibrary}). A no-op on
	 * every other backend, and when the program does not fetch (so a non-fetching
	 * component is byte-identical to one built before this library existed) or already
	 * defines {@code rontolisp:fetch} itself.
	 * @param program the top-level forms
	 * @param backend the backend being compiled for
	 * @return the program, spliced when applicable
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend) {
		if (backend != WitExportDirective.Backend.WASM_COMPONENT) {
			return program;
		}
		if (!referencesFetch(program) || definesFetch(program)) {
			return program;
		}
		List<LispVal> fetchForms = forms();
		// The members fetch.lisp itself references, used as BOTH the member filter and
		// the
		// drop filter for the lowering: fetch.lisp is the sole caller of the bound
		// interfaces, so this is exactly the "members the program references" the
		// --component
		// path prunes by, and the drops it names (output-stream, input-stream, ...).
		Set<String> members = referencedNames(fetchForms);
		List<LispVal> out = new ArrayList<>();
		for (LispVal form : fetchForms) {
			if (WitImportDirective.isDirective(form)) {
				WitImportDirective.Directive directive = WitImportDirective.parse((LispCons) form);
				out.addAll(WitImportDirective.lower(directive, witText(), directive.path(),
						WitExportDirective.Backend.WASM_COMPONENT, members, members));
			}
			else {
				out.add(form);
			}
		}
		out.addAll(program);
		return out;
	}

	/**
	 * Returns whether any top-level form references the built-in {@code rontolisp:fetch}
	 * (as a call or a first-class value) -- the trigger for splicing {@code fetch.lisp}.
	 * @param program the top-level forms
	 * @return {@code true} when the program references {@code rontolisp:fetch}
	 */
	public static boolean referencesFetch(List<LispVal> program) {
		String fetch = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FETCH);
		for (LispVal form : program) {
			if (references(form, fetch)) {
				return true;
			}
		}
		return false;
	}

	private static boolean references(LispVal form, String name) {
		return switch (form) {
			case LispSymbol sym -> name.equals(sym.name());
			case LispCons cons -> references(cons.car(), name) || references(cons.cdr(), name);
			default -> false;
		};
	}

	// Whether the user program already defines rontolisp:fetch (a defun of it), so the
	// splice does not collide with it -- the dedup guard every library splice carries.
	private static boolean definesFetch(List<LispVal> program) {
		String fetch = PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FETCH);
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol name && fetch.equals(name.name())) {
				return true;
			}
		}
		return false;
	}

	// Every symbol name mentioned in fetch.lisp (its own wit-import directives excluded),
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
			synchronized (FetchLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readResource("fetch.lisp"));
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String witText() {
		String cached = witText;
		if (cached == null) {
			synchronized (FetchLibrary.class) {
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
		try (InputStream in = FetchLibrary.class.getResourceAsStream(name)) {
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
