package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
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

import org.jspecify.annotations.Nullable;

/**
 * Expands a top-level
 * {@code (rontolisp:wit-import "kv.wit" :interface "..." :package kv)} directive on the
 * compile path: the WIT interface is read and the directive is replaced by the forms that
 * bind it -- {@code rontolisp:wasm-import} directives on Preview 1 WASM, and ordinary
 * {@code defun}s dispatching through the interface's provider on the interpreter and the
 * JVM. The sibling of {@link WitExportInliner}, on the import side.
 *
 * <p>
 * Unlike the export inliner it runs <strong>early</strong> -- straight after {@code load}
 * inlining and <em>before</em> {@link UserMacroExpander}. It has to: the names it binds
 * live in a package the WIT names ({@code :package kv}), so the
 * {@code (defpackage kv ...)} it synthesizes must exist before any pass resolves
 * {@code kv:get} in the user's source, and {@code UserMacroExpander} resolves every
 * top-level form through its own {@code PackageResolver}. It also needs nothing that
 * macro expansion produces (a {@code wit-import} is checked against a WIT file, not
 * against the program), which is exactly the opposite of {@link WitExportInliner}, whose
 * contract check must see every {@code defun}.
 *
 * <p>
 * The WIT text is read through a {@link SourceLoader}, the same indirection {@code load}
 * uses, so a front-end with no filesystem works: the browser playground backs it with its
 * map of uploaded files.
 *
 * <p>
 * {@code rontolisp:wit-provide} is meaningful only where a provider is dispatched -- the
 * interpreter and the JVM. On the WASM backends the host supplies the imports, so a
 * top-level provider binding is dropped here rather than failing to compile, and one
 * source runs on every backend.
 */
public final class WitImportInliner {

	private WitImportInliner() {
	}

	/**
	 * Returns whether the program contains a top-level {@code rontolisp:wit-import}
	 * directive.
	 * @param program the top-level forms
	 * @return {@code true} if the program binds a WIT interface
	 */
	public static boolean usesWitImport(List<LispVal> program) {
		return program.stream().anyMatch(WitImportDirective::isDirective);
	}

	/**
	 * Returns a copy of {@code program} with every {@code rontolisp:wit-import} directive
	 * replaced by the forms that bind its interface for the given backend.
	 * @param program the top-level forms (after {@code load} inlining, before user-macro
	 * expansion)
	 * @param baseDir the directory of the source file (relative WIT paths resolve against
	 * it), or {@code null} for the working directory
	 * @param backend the backend being compiled for
	 * @param loader reads the WIT text for a resolved path
	 * ({@link SourceLoader#fileSystem()} on the compile path; the playground's
	 * uploaded-file map in the browser)
	 * @return the rewritten program, or {@code program} itself when it binds no interface
	 */
	public static List<LispVal> inline(List<LispVal> program, @Nullable String baseDir,
			WitExportDirective.Backend backend, SourceLoader loader) {
		return inline(program, baseDir, backend, loader, true);
	}

	/**
	 * Like {@link #inline(List, String, WitExportDirective.Backend, SourceLoader)}, with
	 * control over member pruning on the {@code --component} backend: there
	 * {@code --optimize}'s core tree shaker is skipped by design, so a {@code wit-import}
	 * binds only the interface functions the program references (a textual reachability
	 * judgment, the {@code LibraryDefunPruner} convention) unless pruning is disabled
	 * ({@code --no-prune} / {@code --dynamic}). The other backends always bind every
	 * function (Preview 1 byte-identity; the tree shaker handles it).
	 * @param program the top-level forms
	 * @param baseDir the directory of the source file, or {@code null}
	 * @param backend the backend being compiled for
	 * @param loader reads the WIT text for a resolved path
	 * @param pruneMembers whether a component build binds only the referenced members
	 * @return the rewritten program, or {@code program} itself when it binds no interface
	 */
	public static List<LispVal> inline(List<LispVal> program, @Nullable String baseDir,
			WitExportDirective.Backend backend, SourceLoader loader, boolean pruneMembers) {
		boolean wasm = backend == WitExportDirective.Backend.WASM_GC
				|| backend == WitExportDirective.Backend.WASM_COMPONENT
				|| backend == WitExportDirective.Backend.WASM_NO_GC;
		if (!usesWitImport(program) && !(wasm && bindsProvider(program))) {
			return program;
		}
		Set<String> memberFilter = backend == WitExportDirective.Backend.WASM_COMPONENT && pruneMembers
				? referencedNames(program) : null;
		List<LispVal> result = new ArrayList<>(program.size());
		for (LispVal form : program) {
			if (WitImportDirective.isDirective(form)) {
				WitImportDirective.Directive directive = WitImportDirective.parse((LispCons) form);
				String path = SourceLoader.resolve(baseDir, directive.path());
				result.addAll(WitImportDirective.lower(directive, read(loader, path), path, backend, memberFilter));
			}
			else if (wasm && isProviderBinding(form)) {
				// The host is the provider on the WASM backends; a binding is inert
				// there,
				// not an error, so a program written against one WIT runs unchanged.
				continue;
			}
			else {
				result.add(form);
			}
		}
		return result;
	}

	// Every symbol name the program mentions anywhere (conservatively: quoted data
	// included), plus the member part of each qualified spelling, so a directive can ask
	// "does the program reference this interface function" by its bare member name.
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

	private static String read(SourceLoader loader, String path) {
		try {
			return loader.load(path);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("rontolisp:wit-import: cannot read WIT file " + path, ex);
		}
	}

	private static boolean bindsProvider(List<LispVal> program) {
		return program.stream().anyMatch(WitImportInliner::isProviderBinding);
	}

	private static boolean isProviderBinding(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol sym) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.WIT_PROVIDE.equals(qn.member());
		}
		return false;
	}

}
