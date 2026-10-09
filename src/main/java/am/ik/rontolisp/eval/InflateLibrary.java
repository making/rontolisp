package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The wasm targets' {@code rontolisp::%inflate-new}, {@code %inflate-update} and
 * {@code %inflate-finish}: the Lisp decoder {@code inflate.lisp} on the classpath, which
 * {@code rontolisp.http-client} reads a compressed reply through. The interpreter defines
 * the three natively and the JVM backend calls them on the travelling
 * {@code runtime/RontoInflate}, the same decoder in Java; a wasm module has neither, so
 * this splices the Lisp one -- into a Preview 1 module (a {@code --native} output's) and
 * a {@code --component} alike -- where the program names one of the three, and nowhere
 * else ({@code .kb/fetch-http.md}, "Decompression").
 *
 * <p>
 * The names reach a program through {@code clojure.lisp}, so the pass runs after
 * {@link ClojureLibrary#process}; and before {@link LispPreludeLibrary#process}, which
 * supplies what the decoder is written over. Its definitions are prunable like the other
 * bundled libraries' ({@link LibraryDefunPruner}).
 */
public final class InflateLibrary {

	private static final Set<String> ENTRIES = Set.of(LispNames.INFLATE_NEW_INTERNAL, LispNames.INFLATE_UPDATE_INTERNAL,
			LispNames.INFLATE_FINISH_INTERNAL);

	private static volatile @Nullable List<LispVal> forms;

	private InflateLibrary() {
	}

	/**
	 * The compile-path splice: {@code inflate.lisp} in front of a wasm program that names
	 * one of the three primitives; any other program, and every program compiled for the
	 * JVM, as it is.
	 * @param program the top-level forms
	 * @param backend the backend being compiled for
	 * @return the program, spliced when applicable
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend) {
		if (backend == WitExportDirective.Backend.OTHER || !references(program) || defines(program)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		out.addAll(program);
		return out;
	}

	/**
	 * The library's forms, parsed once.
	 * @return the definitions of {@code inflate.lisp}
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (InflateLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource(), Features.INTERPRETER));
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static boolean references(List<LispVal> program) {
		for (LispVal form : program) {
			if (mentions(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean mentions(LispVal form) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (mentions(cons.car())) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && isEntry(symbol.name());
	}

	// Whether the symbol names one of the three entries, in any spelling: the scan runs
	// before PackageResolver, so it normalizes the qualifier itself.
	private static boolean isEntry(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && ENTRIES.contains(qn.member());
	}

	// Whether the program defines the decoder itself (a program that spliced it already),
	// which the splice must not collide with.
	private static boolean defines(List<LispVal> program) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol name && isEntry(name.name())) {
				return true;
			}
		}
		return false;
	}

	private static String readSource() {
		try (InputStream in = InflateLibrary.class.getResourceAsStream("inflate.lisp")) {
			if (in == null) {
				throw new IllegalStateException("inflate.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
