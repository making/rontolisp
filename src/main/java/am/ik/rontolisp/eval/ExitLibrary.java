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
 * The WASM implementation of {@code %host-exit} -- the host call behind
 * {@code uiop:quit}: a Lisp-source library ({@code exit.lisp}) that binds
 * {@code wasi_snapshot_preview1}'s {@code proc_exit} on Preview 1 and wit-imported
 * {@code wasi:cli/exit@0.3.0}'s {@code exit-with-code} under {@code --component}
 * ({@code exit.wit}), both on the classpath. The interpreter raises
 * {@link LispExitSignal} and the JVM backend emits {@code System.exit}.
 *
 * <p>
 * <b>Nothing fixed grows.</b> Preview 1 binds the import under the primitive's OWN name,
 * so no adapter entry point and no tenth {@code wasi_snapshot_preview1} slot is involved;
 * the component binds an interface the fixed import block does NOT declare, so it is an
 * appended user import like {@code wasi:sockets} or {@code wasi:http}. A program that
 * never quits therefore compiles to the same bytes as before, on both backends.
 *
 * <p>
 * Like {@link EnvironmentLibrary}, <strong>it lowers {@code exit.lisp}'s own
 * {@code rontolisp:wit-import} directive ITSELF</strong> (calling
 * {@link WitImportDirective#lower}): {@code eval/WitImportInliner} has already run by the
 * time a library is spliced. The Preview 1 arm's {@code rontolisp:wasm-import} needs no
 * such treatment -- the WASM compiler reads the directives out of the whole program it is
 * handed, and the splice is part of that program.
 */
public final class ExitLibrary {

	// The read forms per FEATURE SET: the resource carries both arms behind
	// #+rontolisp-component, so which one survives is decided by the reader, and a
	// single cache would hand the Preview 1 arm to a component compile.
	private static final java.util.Map<String, List<LispVal>> FORMS = new java.util.concurrent.ConcurrentHashMap<>();

	private static volatile @Nullable String witText;

	private ExitLibrary() {
	}

	/**
	 * The compile-path splice: when a WASM program references {@code %host-exit} (which
	 * the spliced {@code uiop:quit} definition does), splice {@code exit.lisp} read with
	 * the target's feature set -- its {@code wit-import} directive already lowered under
	 * {@code --component}. A no-op on the interpreter and the JVM, and when the program
	 * never quits.
	 * @param program the top-level forms
	 * @param backend the backend being compiled for
	 * @param features the target's reader features, which select the arm ({@code
	 * :rontolisp-component}) and identify a reactor
	 * @return the program, spliced when applicable
	 * @throws UnsupportedOperationException when a {@code --no-wasi} / {@code --no-gc}
	 * reactor quits: it owns no WASI world, so there is no host process to end
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend, Features features) {
		if (backend == WitExportDirective.Backend.OTHER) {
			return program;
		}
		boolean referenced = false;
		for (LispVal form : program) {
			if (references(form)) {
				referenced = true;
				break;
			}
		}
		if (!referenced || definesExit(program)) {
			return program;
		}
		if (features.names().contains("rontolisp-reactor")) {
			throw new UnsupportedOperationException(
					"uiop:quit needs a host exit call, and a --no-wasi / --no-gc module owns no WASI world:"
							+ " it is a reactor whose entry points are exports a host calls, so there is no process of"
							+ " its own to end. Return from the export instead.");
		}
		List<LispVal> exitForms = forms(features);
		List<LispVal> out = new ArrayList<>();
		// The WIT member filter: every name an exit.lisp defun mentions (one binding,
		// exit-with-code -- derived like EnvironmentLibrary's, not hardcoded).
		Set<String> members = new HashSet<>();
		for (LispVal form : exitForms) {
			if (!WitImportDirective.isDirective(form)) {
				collectNames(form, members);
			}
		}
		for (LispVal form : exitForms) {
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
			case LispSymbol sym -> LispNames.HOST_EXIT.equals(sym.name());
			case LispCons cons -> references(cons.car()) || references(cons.cdr());
			default -> false;
		};
	}

	// A defun head in any source spelling: bare defun, cl:defun and
	// rontolisp:async-defun (mirrors EnvironmentLibrary.isDefunHead).
	private static boolean isDefunHead(String name) {
		if (LispNames.DEFUN.equals(name)) {
			return true;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn != null && ((LispNames.CL_PKG.equals(qn.pkg()) && LispNames.DEFUN.equals(qn.member()))
				|| (LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.ASYNC_DEFUN.equals(qn.member())));
	}

	// Whether the program already provides %host-exit itself -- a defun of it, or a
	// rontolisp:wasm-import binding it (the Preview 1 arm's own shape, which is what
	// makes a second splice a no-op).
	private static boolean definesExit(List<LispVal> program) {
		for (LispVal form : program) {
			if (!(form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& cons.cdr() instanceof LispCons rest)) {
				continue;
			}
			if ((isDefunHead(head.name()) || isWasmImportHead(head.name())) && namesHostExit(unquote(rest.car()))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isWasmImportHead(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.WASM_IMPORT.equals(qn.member());
	}

	// A defun writes the name bare; a wasm-import directive writes it quoted.
	private static LispVal unquote(LispVal form) {
		if (form instanceof LispCons quoted && quoted.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& quoted.cdr() instanceof LispCons inner) {
			return inner.car();
		}
		return form;
	}

	private static boolean namesHostExit(LispVal form) {
		return form instanceof LispSymbol sym && LispNames.HOST_EXIT.equals(sym.name());
	}

	private static void collectNames(@Nullable LispVal form, Set<String> names) {
		switch (form) {
			case LispSymbol sym -> {
				names.add(sym.name());
				// The reader upcases user spellings while WIT member names are
				// lower-kebab: record the lowercase twin too (mirrors
				// WitImportInliner.collectNames).
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

	private static List<LispVal> forms(Features features) {
		return FORMS.computeIfAbsent(String.join(",", features.names()),
				ignored -> LispReader.readAllFromString(readResource("exit.lisp"), features));
	}

	private static String witText() {
		String cached = witText;
		if (cached == null) {
			synchronized (ExitLibrary.class) {
				cached = witText;
				if (cached == null) {
					cached = readResource("exit.wit");
					witText = cached;
				}
			}
		}
		return cached;
	}

	private static String readResource(String name) {
		try (InputStream in = ExitLibrary.class.getResourceAsStream(name)) {
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
