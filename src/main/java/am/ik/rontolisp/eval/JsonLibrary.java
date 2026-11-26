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
import am.ik.rontolisp.LispTrees;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The JSON library ({@code rontolisp:json-parse} / {@code rontolisp:json-stringify}, and
 * {@code rontolisp:json-span} / {@code rontolisp:json-value} over a document's bytes),
 * implemented once in rontolisp itself ({@code json.lisp} on the classpath) so a single
 * hand-written parser/serializer runs on every backend. The public functions are not
 * plain {@code defun}s: the library defines the fixed-arity internal (double-colon) entry
 * points {@code rontolisp::%json-parse} (one argument),
 * {@code rontolisp::%json-stringify} (one argument) and {@code rontolisp::%json-span} /
 * {@code %json-span-value} (the document and the list of the path's segments), and the
 * public surface is supplied around them by a dispatcher (interpreter) / call-site
 * rewrite (compile path) -- see {@code .kb/json.md}.
 *
 * <p>
 * Consumers:
 * <ul>
 * <li>the interpreter registers variadic dispatcher functions in {@link LispEvaluator}
 * and lazily evaluates {@link #portableForms()} into the global environment on first
 * use;</li>
 * <li>the compile path ({@code RontoLispCli}, the web playground, and tests that drive
 * the compilers directly) calls {@link #process(List, WitExportDirective.Backend)} after
 * user-macro expansion: when the program references the public functions, call sites are
 * rewritten to the fixed-arity helpers and the library definitions plus one-argument
 * {@code #'}-wrapper defuns are prepended. On a wasm-GC target the helpers are the
 * {@code %json-native-*} entry points over the backend's native codec, and the walk the
 * other backends run is left to the library pruner; everywhere else the native entry
 * points are left out.</li>
 * </ul>
 */
public final class JsonLibrary {

	/** The canonical name of the fixed-arity parse helper. */
	public static final String HELPER_PARSE = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
			"%" + LispNames.JSON_PARSE);

	/** The canonical name of the fixed-arity stringify helper. */
	public static final String HELPER_STRINGIFY = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
			"%" + LispNames.JSON_STRINGIFY);

	/**
	 * The canonical name of the span helper: the document and the list of the path's
	 * segments.
	 */
	public static final String HELPER_SPAN = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
			"%" + LispNames.JSON_SPAN);

	/**
	 * The canonical name of the value helper, {@link #HELPER_SPAN}'s arguments: not
	 * {@code %json-value}, which is the parser's.
	 */
	public static final String HELPER_VALUE = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
			"%JSON-SPAN-VALUE");

	/** The wasm-GC parse entry point, over the backend's native parser. */
	static final String NATIVE_PARSE = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, "%JSON-NATIVE-PARSE");

	/** The wasm-GC stringify entry point, over the backend's native serializer. */
	static final String NATIVE_STRINGIFY = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
			"%JSON-NATIVE-STRINGIFY");

	/** The wasm-GC span entry point, over the backend's native scan. */
	static final String NATIVE_SPAN = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, "%JSON-NATIVE-SPAN");

	/** The wasm-GC value entry point, over the native scan and parser. */
	static final String NATIVE_VALUE = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, "%JSON-NATIVE-VALUE");

	// What every wasm-GC-only definition's name starts with.
	private static final String NATIVE_PREFIX = PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG,
			"%JSON-NATIVE-");

	// First-class wrappers so #'rontolisp:json-parse / #'rontolisp:json-stringify
	// work in compiled code (call sites are rewritten away, so these are only
	// reached through (function ...) / symbol-function / funcall). Single arity,
	// like the BuiltinFunctionWrappers entries for optional-argument built-ins.
	private static final String WRAPPER_SOURCE = """
			(defun rontolisp:json-parse (s) (rontolisp::%json-parse s))
			(defun rontolisp:json-stringify (v) (rontolisp::%json-stringify v))
			(defun rontolisp:json-span (document &rest path) (rontolisp::%json-span document path))
			(defun rontolisp:json-value (document &rest path) (rontolisp::%json-span-value document path))
			""";

	// The same wrappers over the wasm-GC entry points.
	private static final String NATIVE_WRAPPER_SOURCE = """
			(defun rontolisp:json-parse (s) (rontolisp::%json-native-parse s))
			(defun rontolisp:json-stringify (v) (rontolisp::%json-native-stringify v))
			(defun rontolisp:json-span (document &rest path) (rontolisp::%json-native-span document path))
			(defun rontolisp:json-value (document &rest path) (rontolisp::%json-native-value document path))
			""";

	@Nullable private static volatile List<LispVal> forms;

	@Nullable private static volatile List<LispVal> portableForms;

	@Nullable private static volatile List<LispVal> wrapperForms;

	@Nullable private static volatile List<LispVal> nativeWrapperForms;

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
					cached = List.copyOf(LispReader.readAllFromString(readSource(), Features.INTERPRETER));
					forms = cached;
				}
			}
		}
		return cached;
	}

	// Every form but the wasm-GC entry points, which call what only that backend has:
	// what the interpreter loads and a target without the codec gets.
	static List<LispVal> portableForms() {
		List<LispVal> cached = portableForms;
		if (cached == null) {
			synchronized (JsonLibrary.class) {
				cached = portableForms;
				if (cached == null) {
					cached = forms().stream().filter(form -> !definesNative(form)).toList();
					portableForms = cached;
				}
			}
		}
		return cached;
	}

	private static boolean definesNative(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons nameCell
				&& nameCell.car() instanceof LispSymbol name && name.name().startsWith(NATIVE_PREFIX);
	}

	// Package-private: LibraryDefunPruner keys the wrapper defuns as prunable too.
	static List<LispVal> wrapperForms() {
		List<LispVal> cached = wrapperForms;
		if (cached == null) {
			synchronized (JsonLibrary.class) {
				cached = wrapperForms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(WRAPPER_SOURCE, Features.INTERPRETER));
					wrapperForms = cached;
				}
			}
		}
		return cached;
	}

	private static List<LispVal> nativeWrapperForms() {
		List<LispVal> cached = nativeWrapperForms;
		if (cached == null) {
			synchronized (JsonLibrary.class) {
				cached = nativeWrapperForms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(NATIVE_WRAPPER_SOURCE, Features.INTERPRETER));
					nativeWrapperForms = cached;
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
	 * {@link #process(List, WitExportDirective.Backend)} for a target without the native
	 * codec: the interpreter's and the JVM's walk.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the JSON library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		return process(program, WitExportDirective.Backend.OTHER);
	}

	/**
	 * The compile-path pre-pass: when the program references one of the library's public
	 * functions (qualified anywhere, or bare while {@code (in-package rontolisp)} is in
	 * effect), rewrites the call sites to the fixed-arity helpers and prepends the
	 * library definitions and the {@code #'} wrapper defuns. A program that does not use
	 * JSON is returned unchanged. A wasm-GC target ({@code WASM_GC},
	 * {@code WASM_COMPONENT}) gets the entry points over the backend's native codec and
	 * scan.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @param backend the target
	 * @return the program with the JSON library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program, WitExportDirective.Backend backend) {
		boolean nativeCodec = backend == WitExportDirective.Backend.WASM_GC
				|| backend == WitExportDirective.Backend.WASM_COMPONENT;
		Walker walker = new Walker(nativeCodec ? NATIVE_PARSE : HELPER_PARSE,
				nativeCodec ? NATIVE_STRINGIFY : HELPER_STRINGIFY, nativeCodec ? NATIVE_SPAN : HELPER_SPAN,
				nativeCodec ? NATIVE_VALUE : HELPER_VALUE);
		List<LispVal> rewritten = new ArrayList<>(program.size());
		for (LispVal form : program) {
			walker.trackTopLevelInPackage(form);
			rewritten.add(walker.rewrite(form));
		}
		if (!walker.found) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(nativeCodec ? forms() : portableForms());
		out.addAll(nativeCodec ? nativeWrapperForms() : wrapperForms());
		out.addAll(rewritten);
		return out;
	}

	private static final class Walker {

		private final String parseHelper;

		private final String stringifyHelper;

		private final String spanHelper;

		private final String valueHelper;

		private boolean found;

		Walker(String parseHelper, String stringifyHelper, String spanHelper, String valueHelper) {
			this.parseHelper = parseHelper;
			this.stringifyHelper = stringifyHelper;
			this.spanHelper = spanHelper;
			this.valueHelper = valueHelper;
		}

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

		// Identity-preserving: a form this walk did not rewrite is handed back as it came
		// in, so its SourceProvenance position survives (see LispCons.rebuilt). The cdr
		// spine is walked in a loop, so a long list costs no stack (LispTrees).
		private LispVal rewrite(LispVal form) {
			return LispTrees.rebuildSpine(form, this::rewriteNode, this::rewrite);
		}

		/** A node the walk ends at, rewritten; {@code null} for an ordinary cell. */
		private @Nullable LispVal rewriteNode(LispVal form) {
			return switch (form) {
				case LispSymbol sym -> {
					if (names(sym.name())) {
						this.found = true;
					}
					yield sym;
				}
				case LispCons cons -> rewriteCons(cons);
				default -> form;
			};
		}

		private @Nullable LispVal rewriteCons(LispCons cons) {
			if (cons.car() instanceof LispSymbol op && LispNames.isQuote(member(op.name()))) {
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
					return rewriteCall(cons, this.parseHelper, 1, 1, false);
				}
				if (matches(op.name(), LispNames.JSON_STRINGIFY)) {
					this.found = true;
					return rewriteCall(cons, this.stringifyHelper, 1, 1, false);
				}
				if (matches(op.name(), LispNames.JSON_SPAN)) {
					this.found = true;
					return rewritePathCall(cons, this.spanHelper);
				}
				if (matches(op.name(), LispNames.JSON_VALUE)) {
					this.found = true;
					return rewritePathCall(cons, this.valueHelper);
				}
			}
			return null;
		}

		// (json-span document segment...) as (helper document (list segment...)): the
		// helpers take the path as one list, so no call site conses a &rest list and the
		// address form's size rides in it as the first segment would.
		private @Nullable LispVal rewritePathCall(LispCons cons, String helperName) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2) {
				// a wrong count stays the public call, as rewriteCall leaves it
				return null;
			}
			LispVal path = LispNil.INSTANCE;
			for (int i = parts.size() - 1; i >= 2; i--) {
				path = new LispCons(rewrite(parts.get(i)), path);
			}
			LispVal list = new LispCons(new LispSymbol(LispNames.LIST), path);
			return new LispCons(new LispSymbol(helperName),
					new LispCons(rewrite(parts.get(1)), new LispCons(list, LispNil.INSTANCE)));
		}

		private @Nullable LispVal rewriteCall(LispCons cons, String helperName, int minArgs, int maxArgs,
				boolean padNil) {
			List<LispVal> parts = cons.toList();
			int argCount = parts.size() - 1;
			if (argCount < minArgs || argCount > maxArgs) {
				// A wrong count stays the public call, which each backend compiles to the
				// interpreter's run-time program-error (compiler/BuiltinCallArity).
				return null;
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
			while (true) {
				switch (form) {
					case LispSymbol sym -> {
						if (names(sym.name())) {
							this.found = true;
						}
					}
					case LispCons cons -> {
						detect(cons.car());
						form = cons.cdr();
						continue;
					}
					default -> {
					}
				}
				return;
			}
		}

		// Whether a symbol is one of the library's public functions.
		private boolean names(String symbolName) {
			return matches(symbolName, LispNames.JSON_PARSE) || matches(symbolName, LispNames.JSON_STRINGIFY)
					|| matches(symbolName, LispNames.JSON_SPAN) || matches(symbolName, LispNames.JSON_VALUE);
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
