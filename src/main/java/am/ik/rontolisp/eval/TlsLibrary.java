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
 * The {@code --component} implementation of the CLIENT-side TLS built-ins
 * ({@code rontolisp:tls-connect} / {@code rontolisp:tls-upgrade}): a Lisp-source library
 * ({@code tls.lisp}) over a wit-imported {@code wasi:tls@0.3.0-draft} surface
 * ({@code tls.wit}), both on the classpath. The interpreter and the JVM keep their
 * {@code SSLSocket} implementations; Preview 1 keeps the compile error (no wasi:tls host
 * API there). The {@code tls-listen} family has NO variant here on purpose: the wasi:tls
 * proposal defines no server interface, so TLS servers are interpreter/JVM only.
 *
 * <p>
 * tls.lisp rides sockets.lisp's entry table and {@code %sock} bindings, so this splice
 * must run BEFORE {@link SocketsLibrary#process}: the spliced forms reference
 * {@code rontolisp:tcp-connect}, which is what makes the sockets trigger fire for a
 * program that only names the tls built-ins.
 *
 * <p>
 * Like {@link SocketsLibrary}, <strong>it lowers {@code tls.lisp}'s own
 * {@code rontolisp:wit-import} directives ITSELF</strong> (calling
 * {@link WitImportDirective#lower}): {@code eval/WitImportInliner} has already run by the
 * time a library is spliced. The trigger is textual, so a component program whose
 * tls-upgrade call sites are dead code (an https-free cl+ssl consumer) still imports
 * {@code wasi:tls} and needs {@code -S tls=y} on the run command -- the sockets
 * precedent, with a run-flag consequence ({@code .kb/tcp-sockets.md}).
 */
public final class TlsLibrary {

	private static final List<String> TLS_CLIENT_MEMBERS = List.of(LispNames.TLS_CONNECT, LispNames.TLS_UPGRADE);

	private static volatile @Nullable List<LispVal> forms;

	private static volatile @Nullable String witText;

	private TlsLibrary() {
	}

	/**
	 * The compile-path splice: when a {@code --component} program references
	 * {@code rontolisp:tls-connect} or {@code rontolisp:tls-upgrade}, splice
	 * {@code tls.lisp} (its {@code wit-import} directives already lowered). A no-op on
	 * every other backend and when the program does not touch client TLS.
	 * @param program the top-level forms
	 * @param backend the backend being compiled for
	 * @return the program, spliced when applicable
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend) {
		if (backend != WitExportDirective.Backend.WASM_COMPONENT) {
			return program;
		}
		boolean referenced = false;
		for (LispVal form : program) {
			if (references(form)) {
				referenced = true;
				break;
			}
		}
		if (!referenced || definesTlsUpgrade(program)) {
			return program;
		}
		List<LispVal> tlsForms = forms();
		// The WIT member filter: every name a tls.lisp defun mentions (the
		// SocketsLibrary derivation).
		Set<String> members = new HashSet<>();
		for (LispVal form : tlsForms) {
			if (!WitImportDirective.isDirective(form)) {
				collectNames(form, members);
			}
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal form : tlsForms) {
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

	private static boolean references(LispVal form) {
		return switch (form) {
			case LispSymbol sym -> namesClientTls(sym.name());
			case LispCons cons -> references(cons.car()) || references(cons.cdr());
			default -> false;
		};
	}

	// Whether the symbol names a rontolisp CLIENT tls built-in, in any source spelling:
	// this scan runs before PackageResolver normalizes the program, so it must normalize
	// itself -- splitQualified resolves the built-in nicknames. The tls-listen family is
	// deliberately NOT a trigger (no server interface exists in the proposal; those call
	// sites stay the WasmExprCompiler compile error).
	private static boolean namesClientTls(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && TLS_CLIENT_MEMBERS.contains(qn.member());
	}

	// A defun head in any source spelling: bare defun, cl:defun and
	// rontolisp:async-defun (mirrors SocketsLibrary).
	private static boolean isDefunHead(String name) {
		if (LispNames.DEFUN.equals(name)) {
			return true;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn != null && ((LispNames.CL_PKG.equals(qn.pkg()) && LispNames.DEFUN.equals(qn.member()))
				|| (LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.ASYNC_DEFUN.equals(qn.member())));
	}

	// Whether the user program already defines rontolisp:tls-upgrade (a defun of it), so
	// the splice does not collide with it -- the dedup guard every library splice
	// carries.
	private static boolean definesTlsUpgrade(List<LispVal> program) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && isDefunHead(head.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name.name());
				if (qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg())
						&& LispNames.TLS_UPGRADE.equals(qn.member())) {
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
				// The reader upcases user spellings while WIT member names are
				// lower-kebab: record the lowercase twin too, so the member filter
				// matches every referenced binding (mirrors WitImportInliner).
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
			synchronized (TlsLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readResource("tls.lisp"), Features.INTERPRETER);
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String witText() {
		String cached = witText;
		if (cached == null) {
			synchronized (TlsLibrary.class) {
				cached = witText;
				if (cached == null) {
					cached = readResource("tls.wit");
					witText = cached;
				}
			}
		}
		return cached;
	}

	private static String readResource(String name) {
		try (InputStream in = TlsLibrary.class.getResourceAsStream(name)) {
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
