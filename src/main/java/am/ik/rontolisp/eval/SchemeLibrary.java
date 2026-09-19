package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.scheme.Scheme;
import org.jspecify.annotations.Nullable;

/**
 * The run-time half of the EXPERIMENTAL Scheme front end ({@code scheme.lisp} on the
 * classpath, plus the forms {@link Scheme#runtimeForms} generates from the front end's
 * tables): the printer behind {@code display}/{@code write}, an {@code equal?} that
 * recurses into vectors, the symbol-name escaping, {@code call/cc}, {@code dynamic-wind}
 * and the evaluator behind {@code eval}. It is Common Lisp source like every other
 * shipped library, so no backend learns a Scheme name ({@code .kb/scheme-frontend.md}).
 *
 * <p>
 * Consumers, the {@link UrlLibrary} shape:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time one of the {@code rontolisp::%scheme-} functions is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the compile path calls {@link #process(List)} inside
 * {@code CompileFrontend.expand}: a program that references one of the functions -- only
 * a lowered Scheme program does -- gets the definitions prepended, and
 * {@link LibraryDefunPruner} drops the ones it does not reach.</li>
 * </ul>
 */
public final class SchemeLibrary {

	/**
	 * The feature {@code scheme.lisp} is read with when the program can make a
	 * bytevector: it selects the printer's {@code #u8(} arm and {@code equal?}'s
	 * bytevector-versus-vector test, so a program that cannot make one carries neither.
	 * The interpreter, which loads the library once for every program, always reads with
	 * it.
	 */
	static final String BYTEVECTORS_FEATURE = "rontolisp-scheme-bytevectors";

	/**
	 * The feature {@code scheme.lisp} is read with when the program uses a port
	 * procedure: it adds the port section, keeps the reader's state in the port being
	 * read, lets {@code parameterize} bind the standard streams for a current port, and
	 * gives the printer its port arm. A program that uses none -- {@code (read)} over
	 * standard input included -- keeps the one stream-keyed pushback cell and its bytes.
	 * The interpreter always reads with it.
	 */
	static final String PORTS_FEATURE = "rontolisp-scheme-ports";

	/**
	 * The feature {@code scheme.lisp} is read with when the program can hold a symbol
	 * {@code write} puts between vertical lines ({@code |foo bar|}): it gives the
	 * printer's symbol arm its {@code write} branch, so a program that cannot hold one
	 * keeps its printer byte for byte. The interpreter always reads with it.
	 */
	static final String BAR_SYMBOLS_FEATURE = "rontolisp-scheme-bar-symbols";

	/**
	 * The feature {@code scheme.lisp} is read with when the program spells
	 * {@code cond-expand} -- as a symbol, quoted data included, or inside a string: it
	 * gives {@code eval} its {@code cond-expand} arm, which no other program can reach,
	 * so a program using {@code eval} without it keeps its bytes. The interpreter always
	 * reads with it.
	 */
	static final String COND_EXPAND_FEATURE = "rontolisp-scheme-cond-expand";

	private static final List<String> ALL_FEATURES = List.of(BYTEVECTORS_FEATURE, PORTS_FEATURE, BAR_SYMBOLS_FEATURE,
			COND_EXPAND_FEATURE);

	private static final Set<String> INTERNING = Set.of("INTERN", "MAKE-SYMBOL");

	private static final Map<String, List<LispVal>> SOURCE_FORMS = new ConcurrentHashMap<>();

	private static final Map<String, Set<String>> BYTEVECTOR_FUNCTIONS = new ConcurrentHashMap<>();

	private static final Map<String, Set<String>> PORT_FUNCTIONS = new ConcurrentHashMap<>();

	private static final Map<String, Set<String>> INTERNING_FUNCTIONS = new ConcurrentHashMap<>();

	private static final Map<String, List<LispVal>> FORMS = new ConcurrentHashMap<>();

	@Nullable private static volatile Set<String> functionNames;

	private SchemeLibrary() {
	}

	/**
	 * Returns the library definitions as the interpreter reads them.
	 * @return the library forms
	 * @see #forms(Features)
	 */
	public static List<LispVal> forms() {
		return forms(Features.INTERPRETER);
	}

	/**
	 * Returns the library definitions as the interpreter reads them, for a program read
	 * against the standards: the run-time table behind {@code eval} follows
	 * {@code --scheme-standard}.
	 * @param standards what the program's source is read against
	 * @return the library forms
	 */
	public static List<LispVal> forms(SourceStandards standards) {
		return forms(Features.INTERPRETER, standards);
	}

	/**
	 * Returns the parsed library definitions for a target. The source is written in
	 * canonical shape (internal double-colon {@code rontolisp::} helpers, bare {@code cl}
	 * names), so it needs no package resolution. It is read with the TARGET's features:
	 * {@code parallel-execute} spawns threads under {@code #+thread-support} and runs its
	 * thunks in order without it. Both branches define the same names, so the name set
	 * does not depend on the target. Parsed once per feature set and cached.
	 * @param features the target backend's reader features
	 * @return the library forms
	 */
	public static List<LispVal> forms(Features features) {
		return forms(features, SourceStandards.DEFAULT);
	}

	private static List<LispVal> forms(Features features, SourceStandards standards) {
		String key = String.join(",", features.names()) + "/" + standards.scheme().optionName();
		return FORMS.computeIfAbsent(key, ignored -> {
			// The forms the scheme package GENERATES from its tables -- the run-time
			// procedure table behind eval, the library-name predicate behind
			// (environment ...) -- follow the source, so each table is spelled once. The
			// interpreter loads the library once for every program, so its table holds
			// every procedure; a compiled program gets one cut to what it spells
			// (process).
			List<LispVal> forms = new ArrayList<>(sourceForms(features.with(ALL_FEATURES)));
			forms.addAll(Scheme.runtimeForms(name -> true, standards.scheme()));
			return List.copyOf(forms);
		});
	}

	/**
	 * Every definition {@code scheme.lisp} can contribute, read both with and without its
	 * feature-selected arms: a compiled program gets the variant its features pick
	 * ({@link #process}), and each variant's definitions must be known to be library
	 * definitions (prunable) whichever one it is.
	 * @return the forms of the variant with every feature, then those of the variant
	 * without any
	 */
	public static List<LispVal> everyVariantForms() {
		List<LispVal> forms = new ArrayList<>(forms());
		forms.addAll(sourceForms(Features.INTERPRETER));
		return List.copyOf(forms);
	}

	private static List<LispVal> sourceForms(Features features) {
		return SOURCE_FORMS.computeIfAbsent(String.join(",", features.names()),
				ignored -> List.copyOf(LispReader.readAllFromString(readSource(), features)));
	}

	private static String readSource() {
		try (InputStream in = SchemeLibrary.class.getResourceAsStream("scheme.lisp")) {
			if (in == null) {
				throw new IllegalStateException("scheme.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the name is one of the functions {@code scheme.lisp} defines.
	 * @param symbolName the symbol name, in its canonical spelling
	 * @return {@code true} when the library defines the function
	 */
	public static boolean isSchemeFunction(String symbolName) {
		Set<String> names = functionNames;
		if (names == null) {
			names = new HashSet<>();
			for (LispVal form : forms()) {
				if (form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
						&& rest.car() instanceof LispSymbol name) {
					names.add(name.name());
				}
			}
			functionNames = names;
		}
		return names.contains(symbolName);
	}

	/**
	 * {@link #process(List, Features, SourceStandards)} for the interpreter's features
	 * and the default standards.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		return process(program, Features.INTERPRETER, SourceStandards.DEFAULT);
	}

	/**
	 * The compile-path pre-pass: prepends the library definitions, read for the target,
	 * when the program references one of its functions. A program that does not is
	 * returned unchanged.
	 *
	 * <p>
	 * The run-time procedure table behind {@code eval} is generated for THIS program: it
	 * holds the procedures whose (mangled) names the program spells, as a symbol anywhere
	 * in it -- quoted data included -- or inside a string literal. A datum a compiled
	 * program can hand {@code eval} is built from those, and the whole table would reach
	 * every helper there is ({@code SchemeBuiltins.runtimeForms}); the compiled name
	 * registry behind Common Lisp's {@code eval} draws the same line
	 * ({@code .kb/eval-runtime.md}).
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @param features the target backend's reader features
	 * @param standards what the program's source was read against: the run-time table
	 * follows {@code --scheme-standard}
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program, Features features, SourceStandards standards) {
		for (LispVal form : program) {
			if (references(form)) {
				Set<String> symbols = new HashSet<>();
				List<String> strings = new ArrayList<>();
				for (LispVal spelled : program) {
					collectSpellings(spelled, symbols, strings);
				}
				Predicate<String> spelled = name -> symbols.contains(name)
						|| strings.stream().anyMatch(string -> string.contains(name));
				List<LispVal> generated = Scheme.runtimeForms(spelled, standards.scheme());
				List<String> selected = new ArrayList<>();
				if (makesBytevectors(program, features) || makesBytevectors(generated, features)) {
					selected.add(BYTEVECTORS_FEATURE);
				}
				if (makesPorts(program, features) || makesPorts(generated, features)) {
					selected.add(PORTS_FEATURE);
				}
				if (makesBarSymbols(program, features) || makesBarSymbols(generated, features)) {
					selected.add(BAR_SYMBOLS_FEATURE);
				}
				if (spelled.test("cond-expand")) {
					selected.add(COND_EXPAND_FEATURE);
				}
				List<LispVal> out = new ArrayList<>(
						sourceForms(selected.isEmpty() ? features : features.with(selected)));
				out.addAll(generated);
				out.addAll(program);
				return out;
			}
		}
		return program;
	}

	/**
	 * Whether the forms can make a bytevector: they spell one (a {@code #u8(...)}
	 * literal, the {@code (unsigned-byte 8)} element type, the UTF-8 encoder) or call a
	 * library function that can, directly or through the functions it calls --
	 * {@code read}, whose reader knows {@code #u8(}, included. Derived from the library
	 * source rather than listed, so a new constructor needs no second edit.
	 * @param forms the program's forms, or the forms generated for it
	 * @param features the target backend's reader features
	 * @return {@code true} when the printer needs its {@code #u8(} arm
	 */
	static boolean makesBytevectors(List<LispVal> forms, Features features) {
		Set<String> makers = bytevectorFunctions(features);
		for (LispVal form : forms) {
			if (spellsBytevector(form) || callsAny(form, makers)) {
				return true;
			}
		}
		return false;
	}

	// The library functions that can make a bytevector: those that spell one, then every
	// function calling one of those, to a fixpoint. Read WITHOUT the bytevector feature,
	// so the printer's arm does not count as making one, and WITH the ports feature, so
	// get-output-bytevector does.
	private static Set<String> bytevectorFunctions(Features features) {
		return BYTEVECTOR_FUNCTIONS.computeIfAbsent(String.join(",", features.names()),
				ignored -> functionsReaching(sourceForms(features.with(List.of(PORTS_FEATURE))),
						SchemeLibrary::spellsBytevector));
	}

	/**
	 * Whether the forms can hold a symbol {@code write} puts between vertical lines: they
	 * quote one ({@code '|foo bar|}, or inside a vector literal) or can intern any name
	 * -- {@code string->symbol}, {@code read}, whatever calls them, {@code intern}
	 * itself. Derived from the library source, like {@link #makesBytevectors}.
	 * @param forms the program's forms, or the forms generated for it
	 * @param features the target backend's reader features
	 * @return {@code true} when the printer needs its vertical-line arm
	 */
	static boolean makesBarSymbols(List<LispVal> forms, Features features) {
		Set<String> interning = INTERNING_FUNCTIONS.computeIfAbsent(String.join(",", features.names()),
				ignored -> functionsReaching(sourceForms(features.with(List.of(PORTS_FEATURE))),
						body -> callsAny(body, INTERNING)));
		for (LispVal form : forms) {
			if (callsAny(form, INTERNING) || callsAny(form, interning) || quotesBarSymbol(form, false)) {
				return true;
			}
		}
		return false;
	}

	private static boolean quotesBarSymbol(LispVal form, boolean quoted) {
		return switch (form) {
			case LispSymbol symbol -> quoted && Scheme.writtenWithVerticalLines(symbol.name());
			case LispCons cons -> {
				if (!quoted && cons.car() instanceof LispSymbol head && "QUOTE".equals(head.name())
						&& cons.cdr() instanceof LispCons quotation) {
					yield quotesBarSymbol(quotation.car(), true);
				}
				LispVal rest = cons;
				while (rest instanceof LispCons cell) {
					if (quotesBarSymbol(cell.car(), quoted)) {
						yield true;
					}
					rest = cell.cdr();
				}
				yield quotesBarSymbol(rest, quoted);
			}
			case LispArray array -> Arrays.stream(array.data()).anyMatch(element -> quotesBarSymbol(element, true));
			default -> false;
		};
	}

	// The library functions whose bodies pass the test, then every function calling one
	// of those, to a fixpoint.
	private static Set<String> functionsReaching(List<LispVal> source, Predicate<LispVal> test) {
		Map<String, LispVal> bodies = new HashMap<>();
		for (LispVal form : source) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && "DEFUN".equals(head.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				bodies.put(name.name(), rest.cdr());
			}
		}
		Set<String> reaching = new HashSet<>();
		bodies.forEach((name, body) -> {
			if (test.test(body)) {
				reaching.add(name);
			}
		});
		boolean grew = true;
		while (grew) {
			grew = false;
			for (Map.Entry<String, LispVal> entry : bodies.entrySet()) {
				if (!reaching.contains(entry.getKey()) && callsAny(entry.getValue(), reaching)) {
					reaching.add(entry.getKey());
					grew = true;
				}
			}
		}
		return Set.copyOf(reaching);
	}

	/**
	 * Whether the forms use ports: they call a library function that exists only under
	 * {@link #PORTS_FEATURE} -- every port procedure's helper, the current ports
	 * included. Derived from the library source (the functions the feature adds), so a
	 * new port helper needs no list edit.
	 * @param forms the program's forms, or the forms generated for it
	 * @param features the target backend's reader features
	 * @return {@code true} when the program needs the library's port section
	 */
	static boolean makesPorts(List<LispVal> forms, Features features) {
		Set<String> portFunctions = PORT_FUNCTIONS.computeIfAbsent(String.join(",", features.names()), ignored -> {
			Set<String> names = definedFunctions(sourceForms(features.with(List.of(PORTS_FEATURE))));
			names.removeAll(definedFunctions(sourceForms(features)));
			return Set.copyOf(names);
		});
		for (LispVal form : forms) {
			if (callsAny(form, portFunctions)) {
				return true;
			}
		}
		return false;
	}

	private static Set<String> definedFunctions(List<LispVal> forms) {
		Set<String> names = new HashSet<>();
		for (LispVal form : forms) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && "DEFUN".equals(head.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				names.add(name.name());
			}
		}
		return names;
	}

	private static boolean spellsBytevector(LispVal form) {
		return switch (form) {
			case LispIntVector vector -> vector.width() == 8;
			case LispSymbol symbol -> LispNames.STRING_TO_OCTETS.equals(LispSymbol.memberName(symbol.name()));
			case LispCons cons -> {
				if (LispNames.unsignedByteWidth(cons) == 8) {
					yield true;
				}
				LispVal rest = cons;
				while (rest instanceof LispCons cell) {
					if (spellsBytevector(cell.car())) {
						yield true;
					}
					rest = cell.cdr();
				}
				yield spellsBytevector(rest);
			}
			case LispArray array -> Arrays.stream(array.data()).anyMatch(SchemeLibrary::spellsBytevector);
			default -> false;
		};
	}

	private static boolean callsAny(LispVal form, Set<String> names) {
		return switch (form) {
			case LispSymbol symbol -> names.contains(symbol.name());
			case LispCons cons -> {
				LispVal rest = cons;
				while (rest instanceof LispCons cell) {
					if (callsAny(cell.car(), names)) {
						yield true;
					}
					rest = cell.cdr();
				}
				yield callsAny(rest, names);
			}
			default -> false;
		};
	}

	private static void collectSpellings(LispVal form, Set<String> symbols, List<String> strings) {
		switch (form) {
			case LispSymbol symbol -> symbols.add(symbol.name());
			case LispString string -> strings.add(string.value());
			case LispCons cons -> {
				LispVal rest = cons;
				while (rest instanceof LispCons cell) {
					collectSpellings(cell.car(), symbols, strings);
					rest = cell.cdr();
				}
				collectSpellings(rest, symbols, strings);
			}
			case LispArray array -> {
				for (LispVal element : array.data()) {
					collectSpellings(element, symbols, strings);
				}
			}
			default -> {
			}
		}
	}

	private static boolean references(LispVal form) {
		LispVal rest = form;
		while (rest instanceof LispCons cons) {
			if (references(cons.car())) {
				return true;
			}
			rest = cons.cdr();
		}
		return rest instanceof LispSymbol symbol && isSchemeFunction(symbol.name());
	}

}
