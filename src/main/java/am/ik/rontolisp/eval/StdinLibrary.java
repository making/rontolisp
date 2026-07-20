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
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code --component} stdin machinery: a Lisp-source library ({@code stdin.lisp})
 * over a wit-imported {@code wasi:cli/stdin@0.3.0} surface ({@code stdin.wit}), both on
 * the classpath. The interface is part of every GC component variant's fixed WASI
 * surface, so {@code WasmComponentBuilder} binds it FROM the import block's
 * already-declared instance rather than as a user import (the wait.lisp model).
 *
 * <p>
 * Two splice shapes, decided here (it must run AFTER {@link SocketsLibrary}):
 * <ul>
 * <li><strong>sockets.lisp spliced</strong> (the program defines
 * {@code rontolisp::%io-read-line}): its dispatchers fall through to the
 * {@code %stdin-*-or-raw-f} helpers, so this library supplies them -- the real
 * {@code stdin.lisp} machinery, or the {@code stdin-stub.lisp} raw passthroughs under
 * serve mode, whose service world has no stdin at all.</li>
 * <li><strong>stdin-only</strong>: an async program (it references an async form) that
 * reads stdin ({@code read-line}/{@code read-char}/{@code read-byte}) gets
 * {@code stdin.lisp} plus the {@code stdin-dispatch.lisp} dispatchers, so the WASM
 * compiler's I/O rewrite promotes its async-context reads onto awaits -- a pending stdin
 * read suspends the task instead of stalling the instance.</li>
 * </ul>
 * A NON-async program that reads stdin is deliberately left alone: it gains nothing from
 * the migration (the preview1 adapter's blocking stdin branch is already correct
 * sequentially), and leaving it keeps its component byte-identical and its wasmtime flags
 * unchanged. Preview 1 and the interpreter/JVM are untouched on every shape.
 *
 * <p>
 * Like {@link SocketsLibrary}, <strong>it lowers {@code stdin.lisp}'s own
 * {@code rontolisp:wit-import} directive ITSELF</strong> (calling
 * {@link WitImportDirective#lower}): {@code eval/WitImportInliner} has already run by the
 * time a library is spliced.
 */
public final class StdinLibrary {

	// The or-raw helper every splice shape defines exactly once -- the dedup marker.
	private static final String HELPER_MARKER = "%STDIN-READ-LINE-OR-RAW-F";

	// The sockets.lisp dispatch marker: when present, the program only needs the
	// helpers (real or stub), never the stdin-dispatch.lisp dispatchers.
	private static final String IO_MARKER = "%IO-READ-LINE";

	private static final List<String> READ_NAMES = List.of(LispNames.READ_LINE, LispNames.READ_CHAR,
			LispNames.READ_BYTE);

	private static final List<String> ASYNC_MEMBERS = List.of(LispNames.ASYNC_DEFUN, LispNames.ASYNC_LAMBDA,
			LispNames.AWAIT, LispNames.ASYNC, LispNames.SUBTASK_FUTURE_INTERNAL);

	private static volatile @Nullable List<LispVal> forms;

	private static volatile @Nullable List<LispVal> stubForms;

	private static volatile @Nullable List<LispVal> dispatchForms;

	private static volatile @Nullable String witText;

	private StdinLibrary() {
	}

	/**
	 * The compile-path splice; a no-op on every other backend and on every program shape
	 * not listed in the class comment.
	 * @param program the top-level forms (sockets.lisp already spliced when applicable)
	 * @param backend the backend being compiled for
	 * @param serve whether the program compiles in serve mode
	 * ({@code rontolisp:http-handler} under {@code --component}), whose service world has
	 * no stdin
	 * @return the program, spliced when applicable
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend, boolean serve) {
		if (backend != WitExportDirective.Backend.WASM_COMPONENT) {
			return program;
		}
		if (definesInternal(program, HELPER_MARKER)) {
			return program;
		}
		if (definesInternal(program, IO_MARKER)) {
			// sockets.lisp is spliced: supply the or-raw helpers its dispatchers fall
			// through to -- raw passthroughs under serve (no stdin in the service
			// world), the real machinery otherwise.
			return serve ? splicePlain(program, stubForms()) : spliceStdin(program, forms());
		}
		if (serve || !referencesAny(program, LispNames.RONTOLISP_PKG, ASYNC_MEMBERS) || !referencesRead(program)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(dispatchForms());
		out.addAll(program);
		return spliceStdin(out, forms());
	}

	// Splice stdin.lisp, lowering its wit-import directive against stdin.wit with the
	// member filter derived from the library's own forms (the WaitForLibrary model).
	private static List<LispVal> spliceStdin(List<LispVal> program, List<LispVal> stdinForms) {
		Set<String> members = new HashSet<>();
		for (LispVal form : stdinForms) {
			if (!WitImportDirective.isDirective(form)) {
				collectNames(form, members);
			}
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal form : stdinForms) {
			if (WitImportDirective.isDirective(form)) {
				WitImportDirective.Directive directive = WitImportDirective.parse((LispCons) form);
				out.addAll(WitImportDirective.lower(directive, witText(), directive.path(),
						WitExportDirective.Backend.WASM_COMPONENT, members, members));
				continue;
			}
			out.add(form);
		}
		out.addAll(program);
		return out;
	}

	private static List<LispVal> splicePlain(List<LispVal> program, List<LispVal> library) {
		List<LispVal> out = new ArrayList<>(library);
		out.addAll(program);
		return out;
	}

	// Whether the program references any of the given rontolisp-package members, in any
	// source spelling: this scan runs before PackageResolver normalizes the program, so
	// it must normalize itself -- splitQualified resolves the built-in nicknames.
	private static boolean referencesAny(LispVal form, String pkg, List<String> members) {
		return switch (form) {
			case LispSymbol sym -> {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				yield qn != null && pkg.equals(qn.pkg()) && members.contains(qn.member());
			}
			case LispCons cons -> referencesAny(cons.car(), pkg, members) || referencesAny(cons.cdr(), pkg, members);
			default -> false;
		};
	}

	private static boolean referencesAny(List<LispVal> program, String pkg, List<String> members) {
		for (LispVal form : program) {
			if (referencesAny(form, pkg, members)) {
				return true;
			}
		}
		return false;
	}

	// Whether the program references a stdin-capable read built-in
	// (read-line/read-char/read-byte), bare or cl-qualified. A reference aimed at a
	// file or string stream still counts -- the splice is runtime-inert for those
	// (the stdin stream is created lazily, on the first nil-designator read).
	private static boolean referencesRead(List<LispVal> program) {
		for (LispVal form : program) {
			if (referencesRead(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean referencesRead(LispVal form) {
		return switch (form) {
			case LispSymbol sym -> {
				if (READ_NAMES.contains(sym.name())) {
					yield true;
				}
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				yield qn != null && LispNames.CL_PKG.equals(qn.pkg()) && READ_NAMES.contains(qn.member());
			}
			case LispCons cons -> referencesRead(cons.car()) || referencesRead(cons.cdr());
			default -> false;
		};
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

	// Whether the program defines the given rontolisp-internal function (a top-level
	// defun/async-defun of it) -- the splice-shape markers above.
	private static boolean definesInternal(List<LispVal> program, String member) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && isDefunHead(head.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name.name());
				if (qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && member.equals(qn.member())) {
					return true;
				}
			}
		}
		return false;
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
			synchronized (StdinLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readResource("stdin.lisp"), Features.INTERPRETER);
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static List<LispVal> stubForms() {
		List<LispVal> cached = stubForms;
		if (cached == null) {
			synchronized (StdinLibrary.class) {
				cached = stubForms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readResource("stdin-stub.lisp"), Features.INTERPRETER);
					stubForms = cached;
				}
			}
		}
		return cached;
	}

	private static List<LispVal> dispatchForms() {
		List<LispVal> cached = dispatchForms;
		if (cached == null) {
			synchronized (StdinLibrary.class) {
				cached = dispatchForms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readResource("stdin-dispatch.lisp"), Features.INTERPRETER);
					dispatchForms = cached;
				}
			}
		}
		return cached;
	}

	private static String witText() {
		String cached = witText;
		if (cached == null) {
			synchronized (StdinLibrary.class) {
				cached = witText;
				if (cached == null) {
					cached = readResource("stdin.wit");
					witText = cached;
				}
			}
		}
		return cached;
	}

	private static String readResource(String name) {
		try (InputStream in = StdinLibrary.class.getResourceAsStream(name)) {
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
