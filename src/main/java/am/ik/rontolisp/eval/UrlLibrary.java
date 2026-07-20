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
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The URL / query-string library ({@code rontolisp:url-decode} / {@code url-encode} /
 * {@code query-params} / {@code query-param} / {@code url-path} / {@code url-query}),
 * implemented once in rontolisp itself ({@code url.lisp} on the classpath) so a single
 * hand-written implementation runs on every backend. Like {@link LinalgLibrary} (and
 * unlike {@link JsonLibrary}), every public entry point is a plain fixed-arity
 * {@code defun}, so no call-site rewriting is needed.
 *
 * <p>
 * Consumers:
 * <ul>
 * <li>the interpreter lazily evaluates {@link #forms()} into the global environment the
 * first time one of the public functions is resolved (see
 * {@code LispEvaluator#resolveFunction});</li>
 * <li>the compile path ({@code RontoLispCli}, the web playground, and tests that drive
 * the compilers directly) calls {@link #process(List)} after user-macro expansion: when
 * the program references any of the public functions (qualified anywhere, or bare while
 * {@code (in-package rontolisp)} is in effect), the library definitions are
 * prepended.</li>
 * </ul>
 */
public final class UrlLibrary {

	/** The names of the public functions, unqualified. */
	private static final Set<String> URL_FUNCTIONS = Set.of(LispNames.URL_DECODE, LispNames.URL_ENCODE,
			LispNames.QUERY_PARAMS, LispNames.QUERY_PARAM, LispNames.URL_PATH, LispNames.URL_QUERY);

	@Nullable private static volatile List<LispVal> forms;

	private UrlLibrary() {
	}

	/**
	 * Returns the parsed library definitions (the public {@code rontolisp:} defuns and
	 * their {@code rontolisp::%url-} helpers). The source is written in canonical shape
	 * (external single-colon public names, internal double-colon helpers, bare {@code cl}
	 * names), so it needs no package resolution and re-resolving it is a no-op. Parsed
	 * once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (UrlLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readSource(), Features.INTERPRETER);
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = UrlLibrary.class.getResourceAsStream("url.lisp")) {
			if (in == null) {
				throw new IllegalStateException("url.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references one of the URL-library public
	 * functions: a {@code rontolisp:}/{@code rontolisp::} qualified spelling of one of
	 * the exported names.
	 * @param symbolName the symbol name as written (or canonical)
	 * @return {@code true} when the name is a URL-library function
	 */
	public static boolean isUrlFunction(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && URL_FUNCTIONS.contains(qn.member());
	}

	/**
	 * The compile-path pre-pass: when the program references any URL-library function (a
	 * {@code rontolisp:}/{@code rontolisp::} qualified spelling anywhere, or a bare
	 * exported name while {@code (in-package rontolisp)} is in effect), prepends the
	 * library definitions. A program that does not use the library is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the URL library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		Walker walker = new Walker();
		for (LispVal form : program) {
			walker.trackTopLevelInPackage(form);
			walker.detect(form);
		}
		if (!walker.found) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		out.addAll(program);
		return out;
	}

	private static final class Walker {

		private boolean found;

		private String currentPackage = LispNames.CL_USER_PKG;

		private void trackTopLevelInPackage(LispVal form) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.IN_PACKAGE.equals(member(op.name())) && cons.cdr() instanceof LispCons argCell) {
				String name = switch (argCell.car()) {
					case LispSymbol sym -> sym.isKeyword() ? sym.name().substring(1) : sym.name();
					case LispString str -> str.value();
					default -> this.currentPackage;
				};
				this.currentPackage = PackageRegistry.canonicalBuiltinName(name);
			}
		}

		private static String member(String name) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
			return qn == null ? name : qn.member();
		}

		private void detect(LispVal form) {
			if (this.found) {
				return;
			}
			switch (form) {
				case LispSymbol sym -> {
					if (isUrlFunction(sym.name()) || (LispNames.RONTOLISP_PKG.equals(this.currentPackage)
							&& URL_FUNCTIONS.contains(sym.name()))) {
						this.found = true;
					}
				}
				case LispCons cons -> {
					detect(cons.car());
					detect(cons.cdr());
				}
				default -> {
				}
			}
		}

	}

}
