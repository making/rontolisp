package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code usocket} package (a usocket-compatible API shim over the
 * {@code rontolisp:tcp-*} built-ins), implemented once in rontolisp itself
 * ({@code usocket.lisp} on the classpath) so a single hand-written implementation runs on
 * every backend -- the {@code linalg}/{@code vec} pattern: plain {@code defun}s (keyword
 * parameters desugar through {@code LambdaLists}), no call-site rewriting. The four
 * {@code with-*} convenience macros are built-in {@code LispMacroExpander} expansions,
 * not library defuns.
 *
 * <p>
 * Consumers:
 * <ul>
 * <li>the interpreter lazily evaluates {@link #forms()} into the global environment the
 * first time a {@code usocket:}-qualified function or variable is resolved (see
 * {@code LispEvaluator});</li>
 * <li>the compile path ({@code RontoLispCli}, the web playground, and tests that drive
 * the compilers directly) calls {@link #process(List)} after user-macro expansion: when
 * the program references the {@code usocket} package (qualified anywhere, or bare
 * exported names while {@code (in-package usocket)} is in effect), the library
 * definitions are prepended;</li>
 * <li>the built-in ASDF system {@code "usocket"} ({@code BuiltinSystems}) splices
 * {@link #forms()} for {@code asdf:load-system}/{@code ql:quickload}/{@code :depends-on}
 * requests; {@link #process(List)} skips a program that already carries the definitions
 * (the dedup guard).</li>
 * </ul>
 */
public final class UsocketLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private UsocketLibrary() {
	}

	/**
	 * Returns the parsed library definitions (the {@code usocket:} defuns/defparameters
	 * and their {@code usocket::%usock-} helpers). The source is written in canonical
	 * shape (external single-colon public names, internal double-colon helpers, bare
	 * {@code cl} names), so it needs no package resolution and re-resolving it is a
	 * no-op. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (UsocketLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readSource());
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = UsocketLibrary.class.getResourceAsStream("usocket.lisp")) {
			if (in == null) {
				throw new IllegalStateException("usocket.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references the {@code usocket} package: any
	 * {@code usocket:}/{@code usocket::} qualified name.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name is usocket-qualified
	 */
	public static boolean isUsocketQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.USOCKET_PKG.equals(qn.pkg());
	}

	/**
	 * The compile-path pre-pass: when the program references the {@code usocket} package
	 * (a {@code usocket:}/{@code usocket::} qualified symbol anywhere, or a bare exported
	 * name while {@code (in-package usocket)} is in effect), prepends the library
	 * definitions. A program that does not use usocket -- or that already carries the
	 * definitions because the {@code LoadInliner} satisfied a built-in-system request for
	 * {@code "usocket"} -- is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the usocket library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		Walker walker = new Walker();
		for (LispVal form : program) {
			walker.trackTopLevelInPackage(form);
			walker.detect(form);
		}
		if (!walker.found || definesSocketConnect(program)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		out.addAll(program);
		return out;
	}

	/**
	 * Returns whether the program already contains a top-level
	 * {@code (defun usocket:socket-connect ...)} -- the marker that the library was
	 * already spliced (by the {@code LoadInliner} built-in-system hook), so
	 * {@link #process(List)} must not prepend a second copy.
	 */
	private static boolean definesSocketConnect(List<LispVal> program) {
		String qualified = PackageRegistry.qualify(LispNames.USOCKET_PKG, LispNames.USOCKET_SOCKET_CONNECT);
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.DEFUN.equals(op.name()) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol defName && qualified.equals(defName.name())) {
				return true;
			}
		}
		return false;
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
					if (isUsocketQualified(sym.name()) || (LispNames.USOCKET_PKG.equals(this.currentPackage)
							&& PackageRegistry.usocketExportedNames().contains(sym.name()))) {
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
