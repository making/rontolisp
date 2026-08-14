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
import am.ik.rontolisp.UiopExports;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code --component} implementation of {@code uiop:getenv}: a Lisp-source library
 * ({@code environment.lisp}) over a wit-imported {@code wasi:cli/environment@0.3.0}
 * surface ({@code environment.wit}), both on the classpath. The interpreter and the JVM
 * keep {@code System.getenv}; Preview 1 keeps the {@code environ_sizes_get} /
 * {@code environ_get} buffer scan ({@code _getenv}, see
 * {@code WasmGetenvRuntimeBuilder}), which only a preview1 host can fill.
 *
 * <p>
 * ONE binding serves every component variant. On the base / sockets variants
 * {@code WasmComponentBuilder} lowers {@code get-environment} FROM the import block,
 * which already declares the interface (a second import of the same interface name would
 * be invalid); a SERVE component's block declares no environment interface -- the
 * wasi:http service world carries none, which is why its preview1 bridge answers
 * {@code environ_*} with a zero environment -- so there the same binding is an appended
 * user import, and that is what makes {@code wasmtime serve --env FOO=bar} readable from
 * a served handler.
 *
 * <p>
 * Like {@link WaitForLibrary}, <strong>it lowers {@code environment.lisp}'s own
 * {@code rontolisp:wit-import} directive ITSELF</strong> (calling
 * {@link WitImportDirective#lower}): {@code eval/WitImportInliner} has already run by the
 * time a library is spliced.
 */
public final class EnvironmentLibrary {

	private static volatile @Nullable List<LispVal> forms;

	private static volatile @Nullable String witText;

	private EnvironmentLibrary() {
	}

	/**
	 * The compile-path splice: when a {@code --component} program references
	 * {@code uiop:getenv}, splice {@code environment.lisp} (its {@code wit-import}
	 * directive already lowered). A no-op on every other backend and when the program
	 * does not read the environment.
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
		if (!referenced || definesGetenv(program)) {
			return program;
		}
		List<LispVal> envForms = forms();
		// The WIT member filter: every name an environment.lisp defun mentions (one
		// binding, get-environment -- derived like WaitForLibrary's, not hardcoded).
		Set<String> members = new HashSet<>();
		for (LispVal form : envForms) {
			if (!WitImportDirective.isDirective(form)) {
				collectNames(form, members);
			}
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal form : envForms) {
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
			case LispSymbol sym -> namesGetenv(sym.name());
			case LispCons cons -> references(cons.car()) || references(cons.cdr());
			default -> false;
		};
	}

	// Whether the symbol names uiop:getenv, in any source spelling (uiop:getenv,
	// uiop::getenv, and the home sub-package's uiop/os:getenv): this scan runs before
	// PackageResolver normalizes the program, so it must normalize itself, but a spliced
	// library source is already canonical. There is deliberately no cl:getenv alias to
	// recognize (.kb/time-environment-builtins.md).
	private static boolean namesGetenv(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && UiopExports.denotes(qn.pkg(), qn.member(), LispNames.GETENV);
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

	// Whether the user program already defines uiop:getenv (a defun of it), so the
	// splice does not collide with it -- the dedup guard every library splice carries.
	private static boolean definesGetenv(List<LispVal> program) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && isDefunHead(head.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
					&& namesGetenv(name.name())) {
				return true;
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
				// matches every referenced binding (mirrors
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

	private static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (EnvironmentLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readResource("environment.lisp"), Features.INTERPRETER);
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String witText() {
		String cached = witText;
		if (cached == null) {
			synchronized (EnvironmentLibrary.class) {
				cached = witText;
				if (cached == null) {
					cached = readResource("environment.wit");
					witText = cached;
				}
			}
		}
		return cached;
	}

	private static String readResource(String name) {
		try (InputStream in = EnvironmentLibrary.class.getResourceAsStream(name)) {
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
