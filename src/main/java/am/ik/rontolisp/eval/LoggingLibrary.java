package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.rontolisp.compiler.WitSource;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The host's log a program's records go to under {@code --wasi-logging}:
 * {@code %host-log}, a Lisp-source library ({@code logging.lisp}) binding
 * {@code wasi:logging/logging@0.1.0-draft}'s {@code log} ({@code logging.wit}), both on
 * the classpath: a component's import, or a {@code --no-wasi} module's through the
 * canonical ABI, which the generated glue answers on the console. The Java front end's
 * root logger calls it where the build hands the records to the host
 * ({@code java.util.logging.HostHandler}, {@code .kb/java-frontend.md},
 * "java.util.logging"), and no other build reaches it.
 *
 * <p>
 * <b>Nothing fixed grows.</b> The interface is an appended user import, as
 * {@code wasi:cli/exit} is ({@link ExitLibrary}): a program that never reaches the host's
 * log compiles to the bytes it did. Like {@link ExitLibrary}, it lowers
 * {@code logging.lisp}'s own {@code rontolisp:wit-import} directive itself:
 * {@code eval/WitImportInliner} has already run by the time a library is spliced.
 */
public final class LoggingLibrary {

	// The read forms per feature set: the resource's forms are behind
	// #+rontolisp-component, so the reader decides what a target gets.
	private static final Map<String, List<LispVal>> FORMS = new ConcurrentHashMap<>();

	private static volatile @Nullable String witText;

	private LoggingLibrary() {
	}

	/**
	 * The compile-path splice: when a WASM program references {@code %host-log}, splice
	 * {@code logging.lisp} read with the target's feature set, its {@code wit-import}
	 * directive lowered as the target imports it -- a component's import, a
	 * {@code --no-wasi} module's canonical one. A no-op on the interpreter and the JVM,
	 * and when the program never reaches the host's log.
	 * @param program the top-level forms
	 * @param backend the backend being compiled for
	 * @param features the target's reader features, which select the arm
	 * @return the program, spliced when applicable
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend, Features features) {
		if (backend == WitExportDirective.Backend.OTHER || !references(program)) {
			return program;
		}
		List<LispVal> library = forms(features);
		// the members bound: the names logging.lisp's definitions mention
		Set<String> members = new HashSet<>();
		for (LispVal form : library) {
			if (!WitImportDirective.isDirective(form)) {
				collectNames(form, members);
			}
		}
		// a component imports the interface; a --no-wasi module, whose host is the glue,
		// through the canonical ABI, the level's enum being no member of the flat set
		boolean component = features.contains(Features.COMPONENT);
		List<LispVal> out = new ArrayList<>();
		for (LispVal form : library) {
			if (WitImportDirective.isDirective(form)) {
				WitImportDirective.Directive directive = WitImportDirective.parse((LispCons) form);
				out.addAll(WitImportDirective.lower(directive, WitSource.file(directive.path(), witText()),
						component ? WitExportDirective.Backend.WASM_COMPONENT : WitExportDirective.Backend.WASM_GC,
						members, members, component ? null : new WitImportDirective.CanonicalCore(members)));
				continue;
			}
			out.add(form);
		}
		out.addAll(program);
		return out;
	}

	/** Whether a form of the program names {@code %host-log}. */
	private static boolean references(List<LispVal> program) {
		for (LispVal form : program) {
			if (references(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean references(@Nullable LispVal form) {
		while (true) {
			switch (form) {
				case LispSymbol sym -> {
					return LispNames.HOST_LOG.equals(sym.name());
				}
				case LispCons cons -> {
					if (references(cons.car())) {
						return true;
					}
					form = cons.cdr();
				}
				case null, default -> {
					return false;
				}
			}
		}
	}

	private static void collectNames(@Nullable LispVal form, Set<String> names) {
		while (true) {
			switch (form) {
				case LispSymbol sym -> {
					// the reader upcases a spelling, and a WIT member is lower-kebab: the
					// lowercase twin too (as ExitLibrary collects them)
					names.add(sym.name());
					names.add(sym.name().toLowerCase(Locale.ROOT));
					PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
					if (qn != null) {
						names.add(qn.member());
						names.add(qn.member().toLowerCase(Locale.ROOT));
					}
					return;
				}
				case LispCons cons -> {
					collectNames(cons.car(), names);
					form = cons.cdr();
				}
				case null, default -> {
					return;
				}
			}
		}
	}

	private static List<LispVal> forms(Features features) {
		return FORMS.computeIfAbsent(String.join(",", features.names()),
				ignored -> List.copyOf(LispReader.readAllFromString(readResource("logging.lisp"), features)));
	}

	private static String witText() {
		String cached = witText;
		if (cached == null) {
			synchronized (LoggingLibrary.class) {
				cached = witText;
				if (cached == null) {
					cached = readResource("logging.wit");
					witText = cached;
				}
			}
		}
		return cached;
	}

	private static String readResource(String name) {
		try (InputStream in = LoggingLibrary.class.getResourceAsStream(name)) {
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
