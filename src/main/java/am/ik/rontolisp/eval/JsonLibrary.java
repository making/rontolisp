package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The JSON library ({@code rontolisp:json-parse} / {@code rontolisp:json-stringify}),
 * implemented once in rontolisp itself ({@code json.lisp} on the classpath) so a single
 * hand-written parser/serializer runs on every backend. The public functions cannot be
 * plain {@code defun}s because user lambda lists have no {@code &optional} yet
 * ({@code .todo/31}): the fixed-arity entry points are the internal (double-colon)
 * symbols {@code rontolisp::%json-parse} (two arguments) and
 * {@code rontolisp::%json-stringify} (one argument).
 *
 * <p>
 * Consumers:
 * <ul>
 * <li>the interpreter registers variadic dispatcher functions in {@link LispEvaluator}
 * and lazily evaluates {@link #forms()} into the global environment on first use;</li>
 * <li>the compile path ({@code RontoLispCli}, the web playground, and tests that drive
 * the compilers directly) calls {@link #process(List)} after user-macro expansion: when
 * the program references the public functions, call sites are rewritten to the
 * fixed-arity helpers (a one-argument {@code json-parse} call gets {@code nil} appended)
 * and the library definitions plus one-argument {@code #'}-wrapper defuns are
 * prepended.</li>
 * </ul>
 */
public final class JsonLibrary {

	/** The canonical name of the fixed-arity parse helper. */
	public static final String HELPER_PARSE = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
			"%" + LispNames.JSON_PARSE);

	/** The canonical name of the fixed-arity stringify helper. */
	public static final String HELPER_STRINGIFY = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
			"%" + LispNames.JSON_STRINGIFY);

	private static final String QUALIFIED_PARSE = PackageRegistry.qualify(LispNames.RONTOLISP_PKG,
			LispNames.JSON_PARSE);

	private static final String QUALIFIED_STRINGIFY = PackageRegistry.qualify(LispNames.RONTOLISP_PKG,
			LispNames.JSON_STRINGIFY);

	// First-class wrappers so #'rontolisp:json-parse / #'rontolisp:json-stringify
	// work in compiled code (call sites are rewritten away, so these are only
	// reached through (function ...) / symbol-function / funcall). Single arity,
	// like the BuiltinFunctionWrappers entries for optional-argument built-ins.
	private static final String WRAPPER_SOURCE = """
			(defun rontolisp:json-parse (s) (rontolisp::%json-parse s nil))
			(defun rontolisp:json-stringify (v) (rontolisp::%json-stringify v))
			""";

	@Nullable private static volatile List<LispVal> forms;

	@Nullable private static volatile List<LispVal> wrapperForms;

	private JsonLibrary() {
	}

	/**
	 * Returns the parsed library definitions ({@code %json-*} helper defuns). The source
	 * is written in canonical shape (internal {@code rontolisp::%json-*} names, bare
	 * {@code cl} names), so it needs no package resolution and re-resolving it is a
	 * no-op. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (JsonLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readSource());
					forms = cached;
				}
			}
		}
		return cached;
	}

	// Package-private: LibraryDefunPruner keys the wrapper defuns as prunable too.
	static List<LispVal> wrapperForms() {
		List<LispVal> cached = wrapperForms;
		if (cached == null) {
			synchronized (JsonLibrary.class) {
				cached = wrapperForms;
				if (cached == null) {
					cached = LispReader.readAllFromString(WRAPPER_SOURCE);
					wrapperForms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = JsonLibrary.class.getResourceAsStream("json.lisp")) {
			if (in == null) {
				throw new IllegalStateException("json.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * The compile-path pre-pass: when the program references {@code rontolisp:json-parse}
	 * or {@code rontolisp:json-stringify} (qualified anywhere, or bare while
	 * {@code (in-package rontolisp)} is in effect), rewrites the call sites to the
	 * fixed-arity helpers and prepends the library definitions and the {@code #'} wrapper
	 * defuns. A program that does not use JSON is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the JSON library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		Walker walker = new Walker();
		List<LispVal> rewritten = new ArrayList<>(program.size());
		for (LispVal form : program) {
			walker.trackTopLevelInPackage(form);
			rewritten.add(walker.rewrite(form));
		}
		if (!walker.found) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		out.addAll(wrapperForms());
		out.addAll(rewritten);
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

		private LispVal rewrite(LispVal form) {
			return switch (form) {
				case LispSymbol sym -> {
					if (matches(sym.name(), LispNames.JSON_PARSE) || matches(sym.name(), LispNames.JSON_STRINGIFY)) {
						this.found = true;
					}
					yield sym;
				}
				case LispCons cons -> rewriteCons(cons);
				default -> form;
			};
		}

		private LispVal rewriteCons(LispCons cons) {
			if (cons.car() instanceof LispSymbol op && LispNames.QUOTE.equals(member(op.name()))) {
				// Quoted data is never rewritten, but a mention still triggers the
				// splice (e.g. (symbol-function 'rontolisp:json-parse)).
				detect(cons.cdr());
				return cons;
			}
			if (cons.car() instanceof LispSymbol op && LispNames.FUNCTION.equals(member(op.name()))
					&& cons.cdr() instanceof LispCons nameCell && nameCell.car() instanceof LispSymbol) {
				// #'rontolisp:json-parse names the one-argument wrapper defun; the
				// name is not a call, so it must not be arity-rewritten.
				detect(nameCell.car());
				return cons;
			}
			if (cons.car() instanceof LispSymbol op) {
				if (matches(op.name(), LispNames.JSON_PARSE)) {
					this.found = true;
					return rewriteCall(cons, QUALIFIED_PARSE, HELPER_PARSE, 1, 2, true);
				}
				if (matches(op.name(), LispNames.JSON_STRINGIFY)) {
					this.found = true;
					return rewriteCall(cons, QUALIFIED_STRINGIFY, HELPER_STRINGIFY, 1, 1, false);
				}
			}
			LispVal car = rewrite(cons.car());
			LispVal cdr = rewrite(cons.cdr());
			return new LispCons(car, cdr);
		}

		private LispVal rewriteCall(LispCons cons, String publicName, String helperName, int minArgs, int maxArgs,
				boolean padNil) {
			List<LispVal> parts = cons.toList();
			int argCount = parts.size() - 1;
			if (argCount < minArgs || argCount > maxArgs) {
				throw new LispEvalException(publicName + " expects "
						+ (minArgs == maxArgs ? String.valueOf(minArgs) : minArgs + " or " + maxArgs)
						+ " arguments, got " + argCount);
			}
			List<LispVal> out = new ArrayList<>(maxArgs + 1);
			out.add(new LispSymbol(helperName));
			for (int i = 1; i < parts.size(); i++) {
				out.add(rewrite(parts.get(i)));
			}
			if (padNil && argCount < maxArgs) {
				out.add(LispNil.INSTANCE);
			}
			LispVal result = LispNil.INSTANCE;
			for (int i = out.size() - 1; i >= 0; i--) {
				result = new LispCons(out.get(i), result);
			}
			return result;
		}

		private void detect(LispVal form) {
			switch (form) {
				case LispSymbol sym -> {
					if (matches(sym.name(), LispNames.JSON_PARSE) || matches(sym.name(), LispNames.JSON_STRINGIFY)) {
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

		private boolean matches(String symbolName, String publicName) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
			if (qn != null) {
				// Both spellings name the same symbol; the resolver later rejects a
				// single-colon reference to a non-external member, not this pre-pass.
				return LispNames.RONTOLISP_PKG.equals(qn.pkg()) && publicName.equals(qn.member());
			}
			return LispNames.RONTOLISP_PKG.equals(this.currentPackage) && symbolName.equals(publicName);
		}

	}

}
