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
 * The {@code --component} implementation of the {@code rontolisp:tcp-*} built-ins: a
 * Lisp-source library ({@code sockets.lisp}) over a wit-imported
 * {@code wasi:sockets/types@0.3.0} surface ({@code sockets.wit}), both on the classpath.
 * The interpreter and the JVM keep their {@code java.net.Socket} implementations; Preview
 * 1 keeps the compile error (no host sockets). This library replaced the hand-written
 * {@code adapter-sockets.wat} -- a component tcp program is the base variant plus one
 * appended user import.
 *
 * <p>
 * The library also carries the {@code %io-*} dispatch defuns the WASM compiler's
 * socket-read/write rewrite targets ({@code read-line}/{@code read-byte}/
 * {@code read-char}/{@code write-line}/{@code write-byte}/{@code close} on a socket
 * handle consume the socket's stream instead of {@code fd_read}/{@code fd_write}), and
 * the {@code %...-future} async internals the async-body promotion awaits.
 *
 * <p>
 * Like {@link HttpLibrary}, <strong>it lowers {@code sockets.lisp}'s own
 * {@code rontolisp:wit-import} directive ITSELF</strong> (calling
 * {@link WitImportDirective#lower}): {@code eval/WitImportInliner} has already run by the
 * time a library is spliced.
 */
public final class SocketsLibrary {

	private static final List<String> TCP_MEMBERS = List.of(LispNames.TCP_CONNECT, LispNames.TCP_LISTEN,
			LispNames.TCP_ACCEPT, LispNames.TCP_LOCAL_PORT, LispNames.TCP_LOCAL_ADDRESS, LispNames.TCP_PEER_ADDRESS,
			LispNames.TCP_PEER_PORT);

	private static volatile @Nullable List<LispVal> forms;

	private static volatile @Nullable String witText;

	private SocketsLibrary() {
	}

	/**
	 * The compile-path splice: when a {@code --component} program references a
	 * {@code rontolisp:tcp-*} built-in (or the usocket package, whose shim rides them --
	 * the usocket splice itself runs later in the pipeline, so its tcp references are not
	 * visible here), splice {@code sockets.lisp} (its {@code wit-import} directive
	 * already lowered). A no-op on every other backend and when the program does not
	 * touch sockets.
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
		if (!referenced || definesTcpConnect(program)) {
			return program;
		}
		List<LispVal> sockForms = forms();
		// The WIT member filter: every name a sockets.lisp defun mentions (the
		// WaitForLibrary derivation).
		Set<String> members = new HashSet<>();
		for (LispVal form : sockForms) {
			if (!WitImportDirective.isDirective(form)) {
				collectNames(form, members);
			}
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal form : sockForms) {
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
			case LispSymbol sym -> namesTcp(sym.name());
			case LispCons cons -> references(cons.car()) || references(cons.cdr());
			default -> false;
		};
	}

	// Whether the symbol names a rontolisp tcp built-in or ANY usocket-package member,
	// in any source spelling: this scan runs before PackageResolver normalizes the
	// program, so it must normalize itself -- splitQualified resolves the built-in
	// nicknames.
	private static boolean namesTcp(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		if (qn == null) {
			return false;
		}
		if (LispNames.USOCKET_PKG.equals(qn.pkg())) {
			return true;
		}
		return LispNames.RONTOLISP_PKG.equals(qn.pkg()) && TCP_MEMBERS.contains(qn.member());
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

	// Whether the user program already defines rontolisp:tcp-connect (a defun of it),
	// so the splice does not collide with it -- the dedup guard every library splice
	// carries.
	private static boolean definesTcpConnect(List<LispVal> program) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && isDefunHead(head.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name.name());
				if (qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg())
						&& LispNames.TCP_CONNECT.equals(qn.member())) {
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
			synchronized (SocketsLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readResource("sockets.lisp"), Features.INTERPRETER);
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String witText() {
		String cached = witText;
		if (cached == null) {
			synchronized (SocketsLibrary.class) {
				cached = witText;
				if (cached == null) {
					cached = readResource("sockets.wit");
					witText = cached;
				}
			}
		}
		return cached;
	}

	private static String readResource(String name) {
		try (InputStream in = SocketsLibrary.class.getResourceAsStream(name)) {
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
