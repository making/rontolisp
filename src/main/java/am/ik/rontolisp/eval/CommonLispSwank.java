package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.compiler.CallSiteWalk;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.reader.LispReader;

/**
 * The Common Lisp half of a SWANK server ({@code cli/SwankServer}): what an editor's
 * requests ask of the interpreter, in the shapes SWANK 2.32 answers them -- the REPL's
 * evaluation and the editor's interactive evaluation, compiling a buffer's form or a file
 * with notes, loading a file, completing a symbol, an operator's argument list, a
 * symbol's description, a macro's expansion.
 *
 * <p>
 * Every request runs on a thread of its own under {@link #serve}, which binds the package
 * the request names to that thread ({@link PackageResolver#bindThreadPackage}): a form is
 * read and run in it, and two requests run at once in two packages, as SWANK's REPL
 * thread and its workers do. The standard streams write to the request's own writer. One
 * evaluator serves every request, so what one defines another calls.
 */
public final class CommonLispSwank implements SwankLisp {

	/** The marker an editor puts where its cursor stands in an {@code autodoc} form. */
	public static final Object CURSOR = new Object() {
		@Override
		public String toString() {
			return "%cursor-marker%";
		}
	};

	/** What a stack overflow reports, as the REPL does. */
	public static final String STACK_OVERFLOW = "stack overflow (--stack <MiB> raises the limit)";

	/** The REPL's history variables, which {@link #listenerEval} sets as SWANK's does. */
	private static final List<String> REPL_VARIABLES = List.of("*", "**", "***", "/", "//", "///", "+", "++", "+++");

	/** The flag letters of a symbol's classification, in SWANK's order. */
	private static final String CLASSIFICATION_LETTERS = "bfgctmspa";

	private final LispEvaluator evaluator;

	private final PackageResolver resolver;

	/** The one-argument printers and helpers, read once in {@code cl-user}. */
	private final Map<String, LispVal> helpers = new ConcurrentHashMap<>();

	private volatile @Nullable Map<String, LispVal> clArglists;

	/** What the compiled code defines and uses, for {@code M-.} and {@code xref}. */
	private final SwankSources sources;

	/**
	 * The Common Lisp half of a server over an evaluator.
	 * @param evaluator the evaluator every request runs on
	 */
	public CommonLispSwank(LispEvaluator evaluator) {
		this.evaluator = evaluator;
		this.resolver = evaluator.packageResolver();
		this.sources = new SwankSources(this, evaluator);
		for (String name : REPL_VARIABLES) {
			if (!evaluator.globalEnvironment().hasBinding(name)) {
				evaluator.globalEnvironment().define(name, LispNil.INSTANCE);
			}
		}
		evaluator.enableDebugging();
	}

	// ------------------------------------------------------------------------------------
	// Packages

	/**
	 * The package a buffer's package designator names, as SWANK's {@code guess-package}
	 * reads it: a name or nickname in any case, or the designator a buffer's
	 * {@code in-package} spells ({@code :foo}, {@code #:foo}, {@code "FOO"}).
	 * @param designator the designator, or null
	 * @return the package's canonical name, or null when none is named
	 */
	@Override
	public @Nullable String guessPackage(@Nullable String designator) {
		if (designator == null || designator.isEmpty()) {
			return null;
		}
		String found = this.resolver.findPackageName(designator);
		if (found == null) {
			String name = designator;
			if (name.startsWith("#:")) {
				name = name.substring(2);
			}
			else if (name.startsWith(":")) {
				name = name.substring(1);
			}
			if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
				name = name.substring(1, name.length() - 1);
			}
			else {
				name = readerName(name);
			}
			found = name.isEmpty() ? null : this.resolver.findPackageName(name);
		}
		return found == null || "keyword".equals(found) ? null : found;
	}

	/**
	 * A package's name, as {@code package-name} answers it.
	 * @param pkg the package's canonical name
	 * @return the name
	 */
	@Override
	public String packageName(String pkg) {
		return pkg.toUpperCase(Locale.ROOT);
	}

	/**
	 * What a REPL prompt names a package by, as SWANK's
	 * {@code package-string-for-prompt}: {@code CL-USER} for {@code common-lisp-user},
	 * the last component of a dotted name, else the shortest of the name and its
	 * nicknames.
	 * @param pkg the package's canonical name
	 * @return the prompt's name
	 */
	public String prompt(String pkg) {
		String name = packageName(pkg);
		if (LispNames.CL_USER_PKG.equals(name) || "COMMON-LISP-USER".equals(name)) {
			return "CL-USER";
		}
		int dot = name.lastIndexOf('.');
		if (dot > 0 && dot < name.length() - 1) {
			return name.substring(dot + 1);
		}
		String shortest = name;
		for (String nickname : nicknames(pkg)) {
			if (nickname.length() < shortest.length()) {
				shortest = nickname;
			}
		}
		return shortest;
	}

	private List<String> nicknames(String pkg) {
		try {
			return this.resolver.runtimePackageNicknames(pkg)
				.stream()
				.map(nickname -> nickname.toUpperCase(Locale.ROOT))
				.toList();
		}
		catch (RuntimeException ex) {
			return List.of();
		}
	}

	/**
	 * Every package's name, with the nicknames too when asked, as
	 * {@code list-all-package-names} answers.
	 * @param withNicknames whether to list the nicknames
	 * @return the names
	 */
	public List<String> packageNames(boolean withNicknames) {
		Set<String> names = new TreeSet<>();
		for (Map.Entry<String, String> entry : this.resolver.runtimePackageTable().entrySet()) {
			if ("KEYWORD".equals(entry.getValue())) {
				names.add("KEYWORD");
				continue;
			}
			names.add(entry.getValue());
			if (withNicknames && entry.getKey().equals(entry.getKey().toUpperCase(Locale.ROOT))) {
				names.add(entry.getKey());
			}
		}
		return List.copyOf(names);
	}

	/**
	 * The package the calling thread reads and runs in.
	 * @return its canonical name
	 */
	public String currentPackage() {
		return this.resolver.currentPackageName();
	}

	/**
	 * The {@code *features*} list, as keyword names without their colon.
	 * @return the names
	 */
	public List<String> features() {
		List<String> names = new ArrayList<>();
		LispVal features = this.evaluator.globalEnvironment().lookupOrNull(LispNames.FEATURES_VAR);
		for (LispVal entry = features == null ? LispNil.INSTANCE
				: features; entry instanceof LispCons cell; entry = cell.cdr()) {
			if (cell.car() instanceof LispSymbol symbol) {
				names.add(symbol.name().startsWith(":") ? symbol.name().substring(1) : symbol.name());
			}
		}
		return names;
	}

	// ------------------------------------------------------------------------------------
	// A request

	/**
	 * Runs a request's body on the calling thread with its package bound to the thread
	 * and the standard output, error and trace streams writing to {@code out}, standard
	 * input reading from {@code in}.
	 * @param <T> what the body answers
	 * @param pkg the package's canonical name ({@link #guessPackage}), or null for
	 * {@code cl-user}
	 * @param out where the standard output, error and trace streams write
	 * @param in where standard input reads from
	 * @param body the request
	 * @return what the body answered
	 */
	public <T> T serve(@Nullable String pkg, Writer out, Reader in, Supplier<T> body) {
		return serve(pkg, out, in, null, body);
	}

	/**
	 * Runs a request's body as {@link #serve(String, Writer, Reader, Supplier)} does,
	 * with a debugger on the thread: an error no handler handles enters it where it is
	 * signalled, and an interrupt enters it where it stops the evaluation. The thread's
	 * handler and restart stacks are its own -- empty for a request the thread starts
	 * with, the debugged evaluation's for one a debugger level runs -- so two requests on
	 * two threads never see each other's.
	 * @param <T> what the body answers
	 * @param pkg the package's canonical name ({@link #guessPackage}), or null for
	 * {@code cl-user}
	 * @param out where the standard output, error and trace streams write
	 * @param in where standard input reads from
	 * @param debugger what runs a debugger level on the thread, or null for none
	 * @param body the request
	 * @return what the body answered
	 */
	public <T> T serve(@Nullable String pkg, Writer out, Reader in, @Nullable Sldb debugger, Supplier<T> body) {
		Object previous = this.resolver.bindThreadPackage(pkg == null ? LispNames.CL_USER_PKG : pkg);
		LispVal outStream = this.evaluator.openJavaStream(out);
		// the stream table reads lines and characters off a buffered reader
		LispVal inStream = this.evaluator
			.openJavaStream(in instanceof java.io.BufferedReader buffered ? buffered : new java.io.BufferedReader(in));
		Map<String, LispVal> streams = new HashMap<>();
		streams.put(LispNames.STANDARD_OUTPUT_VAR, outStream);
		streams.put(LispNames.ERROR_OUTPUT_VAR, outStream);
		streams.put(LispNames.TRACE_OUTPUT_VAR, outStream);
		streams.put(LispNames.STANDARD_INPUT_VAR, inStream);
		for (String stack : List.of(LispNames.HANDLER_CLUSTERS_VAR, LispNames.RESTART_CLUSTERS_VAR)) {
			LispVal bound = this.evaluator.threadBinding(stack);
			streams.put(stack, bound == null ? LispNil.INSTANCE : bound);
		}
		List<T> result = new ArrayList<>(1);
		try {
			this.evaluator.withThreadBindings(streams, () -> result
				.add(this.evaluator.withDebugger(debugger == null ? null : new ThreadDebugger(debugger), body)));
		}
		finally {
			this.evaluator.closeJavaStream(outStream);
			this.evaluator.closeJavaStream(inStream);
			this.resolver.unbindThreadPackage(previous);
		}
		return result.getFirst();
	}

	/**
	 * Records, from now on, what every file a {@code load} runs on the evaluator defines
	 * and uses, for {@code M-.} and {@code xref}: called when a server starts serving.
	 */
	public void recordLoads() {
		this.evaluator.recordLoadsTo(this.sources);
	}

	/**
	 * Captures the evaluator's control state before a request, so a stack overflow it
	 * survives can put it back ({@link LispEvaluator#controlState}).
	 * @return the state
	 */
	public Runnable controlState() {
		LispEvaluator.ControlState state = this.evaluator.controlState();
		return state::restore;
	}

	/**
	 * What a failed request reports, as SWANK prints the condition it aborted on:
	 * {@code #<TYPE "report">}.
	 * @param failure what the evaluation threw
	 * @return the text
	 */
	public String describeFailure(Throwable failure) {
		if (failure instanceof StackOverflowError) {
			return "#<STACK-OVERFLOW \"" + STACK_OVERFLOW + "\">";
		}
		String type = "SIMPLE-ERROR";
		if (failure instanceof LispEvalException lisp) {
			if (lisp.conditionClassName() != null) {
				type = Objects.requireNonNull(lisp.conditionClassName());
			}
			LispVal condition = lisp.condition();
			if (condition != null) {
				try {
					LispVal name = helper("(lambda (c) (prin1-to-string (type-of c)))", condition);
					if (name instanceof LispString text) {
						type = text.value();
					}
				}
				catch (RuntimeException ex) {
					// the class stays the default
				}
			}
		}
		String message = String.valueOf(failure.getMessage());
		return "#<" + LispSymbol.memberName(type).toUpperCase(Locale.ROOT) + " " + LispString.literal(message).print()
				+ ">";
	}

	// ------------------------------------------------------------------------------------
	// Evaluation

	/**
	 * The forms of a text and the values of its last one, as SWANK's {@code eval-region}
	 * evaluates them one after the other.
	 *
	 * @param values the last form's values
	 * @param last the last form, or nil
	 */
	private record Region(List<LispVal> values, LispVal last) {
	}

	private Region evalRegion(String code) {
		return evalRegion(code, UnaryOperator.identity());
	}

	private Region evalRegion(String code, UnaryOperator<LispVal> rewrite) {
		boolean markers = SourceLanguage.usesReadEvalMarkers(code);
		List<LispVal> forms = read(code).stream().map(CommonLispSwank::located).map(rewrite).toList();
		List<LispVal> values = List.of();
		LispVal last = LispNil.INSTANCE;
		List<LispVal> resolved = new ArrayList<>();
		try {
			for (LispVal form : forms) {
				last = form;
				values = evalForm(form, markers, resolved::add);
			}
		}
		finally {
			this.sources.recordEvaluated(code, resolved);
		}
		return new Region(values, last);
	}

	private List<LispVal> evalForm(LispVal form, boolean markers) {
		return evalForm(form, markers, null);
	}

	/**
	 * Evaluates a top-level form under its {@code (EVAL form)} frame, as SWANK's
	 * {@code eval} shows it in a backtrace. An error that left the form unoffered to the
	 * thread's debugger -- one no frame of it signalled, a variable the form is -- is
	 * offered here.
	 */
	private List<LispVal> evalForm(LispVal form, boolean markers,
			java.util.function.@Nullable Consumer<LispVal> resolvedForm) {
		return this.evaluator.withFrame(list(new LispSymbol(LispNames.EVAL), form), () -> {
			try {
				return this.evaluator.evalValues(markers ? this.evaluator.resolveReadTimeEvalInCode(form) : form,
						resolvedForm);
			}
			catch (LispEvalException ex) {
				throw this.evaluator.atSignalPoint(ex);
			}
		});
	}

	private List<LispVal> read(String code) {
		return SourceLanguage.COMMON_LISP.read(code, this.evaluator.features(), null, this.evaluator.sourceStandards());
	}

	/**
	 * A form the editor sent to evaluate, with every datum's outermost cons a
	 * {@link LocatedCons}, as a named file's read makes them, in no file
	 * ({@link #EDITOR_TEXT}, line 0): the source a debugger's frame waits in
	 * ({@link CallFrame#at}) is a located form, which no macro expansion makes. Shared
	 * structure stays shared.
	 */
	static LispVal located(LispVal form) {
		return located(form, new IdentityHashMap<>());
	}

	private static LispVal located(LispVal form, Map<LispCons, LispCons> copies) {
		if (!(form instanceof LispCons head) || head instanceof LocatedCons) {
			return form;
		}
		LispCons done = copies.get(head);
		if (done != null) {
			return done;
		}
		LispCons first = new LocatedCons(LispNil.INSTANCE, LispNil.INSTANCE, EDITOR_TEXT, 0);
		copies.put(head, first);
		LispCons cell = head;
		LispCons copy = first;
		while (true) {
			copy.setCar(located(cell.car(), copies));
			if (!(cell.cdr() instanceof LispCons next) || next instanceof LocatedCons) {
				copy.setCdr(cell.cdr());
				return first;
			}
			LispCons seen = copies.get(next);
			if (seen != null) {
				copy.setCdr(seen);
				return first;
			}
			LispCons chained = new LispCons(LispNil.INSTANCE, LispNil.INSTANCE);
			copies.put(next, chained);
			copy.setCdr(chained);
			copy = chained;
			cell = next;
		}
	}

	/** The file a form the editor sent is located in ({@link #located}): none. */
	static final String EDITOR_TEXT = "";

	/** The first form of a text, as SWANK's {@code from-string} reads it. */
	private LispVal readFirst(String code) {
		List<LispVal> forms = read(code);
		if (forms.isEmpty()) {
			throw new LispEvalException("end of file on " + LispString.literal(code).print());
		}
		return forms.getFirst();
	}

	private List<LispVal> evalFirstValues(String code) {
		LispVal form = located(readFirst(code));
		List<LispVal> resolved = new ArrayList<>(1);
		try {
			return evalForm(form, SourceLanguage.usesReadEvalMarkers(code), resolved::add);
		}
		finally {
			this.sources.recordEvaluated(code, resolved);
		}
	}

	/**
	 * Evaluates a REPL input as SWANK's {@code listener-eval}: every form in turn, the
	 * history variables ({@code *} {@code **} {@code ***}, {@code /} {@code //}
	 * {@code ///}, {@code +} {@code ++} {@code +++}) set from the last.
	 * @param code the input
	 * @return the last form's values
	 */
	public List<LispVal> listenerEval(String code) {
		return listen(code);
	}

	/** Evaluates a REPL input and sets the history variables from its last form. */
	private List<LispVal> listen(String code) {
		return listen(code, UnaryOperator.identity());
	}

	private List<LispVal> listen(String code, UnaryOperator<LispVal> rewrite) {
		Region region = evalRegion(code, rewrite);
		Environment global = this.evaluator.globalEnvironment();
		LispVal primary = region.values().isEmpty() ? LispNil.INSTANCE : region.values().getFirst();
		shift(global, "***", "**", "*", primary);
		shift(global, "///", "//", "/", list(region.values()));
		shift(global, "+++", "++", "+", region.last());
		return region.values();
	}

	/**
	 * A REPL value as Slynk's mREPL presents it ({@code make-results}).
	 *
	 * @param presented the value as {@code present-for-emacs} prints it: a number with
	 * its other notations
	 * @param copy what reads back as the value, for a symbol ({@code 'name}, downcased)
	 * or a number, else null
	 * @param value the value
	 */
	public record ReplValue(String presented, @Nullable String copy, LispVal value) {
	}

	/**
	 * Evaluates a REPL input as Slynk's mREPL ({@code mrepl-eval}): every form in turn,
	 * the history variables set from the last. {@code #vN:M} reads as the history's entry
	 * {@code N}'s value {@code M}, {@code #vN} as all its values (Slynk's
	 * {@code backreference-reader}).
	 * @param code the input
	 * @param history the REPL's history: each input's values that answered any
	 * @return the last form's values
	 */
	public List<ReplValue> mreplEval(String code, List<List<LispVal>> history) {
		Map<String, LispVal> references = new HashMap<>();
		String text = backreferences(code, history, references);
		List<ReplValue> out = new ArrayList<>();
		for (LispVal value : listen(text,
				references.isEmpty() ? UnaryOperator.identity() : form -> substitute(form, references))) {
			out.add(new ReplValue(presentForEmacs(value), copyForm(value), value));
		}
		return out;
	}

	/** The name a {@code #v} reference reads as until its form takes its place. */
	private static final String BACKREFERENCE = "%SLY-BACKREFERENCE-";

	/**
	 * A REPL input with each {@code #vN[:M]} outside strings, comments, escaped names and
	 * character names made a symbol, the form taking its place kept by the symbol's name:
	 * {@code 'value}, {@code (values-list '(values...))}, or the error Slynk's assertion
	 * signals for an entry the history does not have.
	 */
	private static String backreferences(String code, List<List<LispVal>> history, Map<String, LispVal> forms) {
		StringBuilder out = new StringBuilder(code.length());
		int n = code.length();
		int i = 0;
		while (i < n) {
			char c = code.charAt(i);
			if (c == '#' && i + 2 < n && Character.toLowerCase(code.charAt(i + 1)) == 'v'
					&& Character.isDigit(code.charAt(i + 2))) {
				int j = digits(code, i + 2);
				int entry = Integer.parseInt(code.substring(i + 2, j));
				Integer value = null;
				if (j + 1 < n && code.charAt(j) == ':' && Character.isDigit(code.charAt(j + 1))) {
					int k = digits(code, j + 1);
					value = Integer.parseInt(code.substring(j + 1, k));
					j = k;
				}
				String name = BACKREFERENCE + forms.size() + "%";
				forms.put(name, backreference(history, entry, value));
				out.append(' ').append(name).append(' ');
				i = j;
				continue;
			}
			int end = switch (c) {
				case '"', '|' -> closing(code, i + 1, c);
				case ';' -> {
					int newline = code.indexOf('\n', i);
					yield newline < 0 ? n : newline;
				}
				case '#' -> {
					char next = i + 1 < n ? code.charAt(i + 1) : ' ';
					if (next == '|') {
						int close = code.indexOf("|#", i + 2);
						yield close < 0 ? n : close + 2;
					}
					if (next == '\\') {
						int j = Math.min(n, i + 3);
						while (j < n && !Character.isWhitespace(code.charAt(j))
								&& "()\"';`,".indexOf(code.charAt(j)) < 0) {
							j++;
						}
						yield j;
					}
					yield i + 1;
				}
				default -> i + 1;
			};
			out.append(code, i, end);
			i = end;
		}
		return out.toString();
	}

	private static int digits(String code, int from) {
		int i = from;
		while (i < code.length() && Character.isDigit(code.charAt(i))) {
			i++;
		}
		return i;
	}

	/** Past the delimiter that closes a string or an escaped name. */
	private static int closing(String code, int from, char delimiter) {
		for (int i = from; i < code.length(); i++) {
			char c = code.charAt(i);
			if (c == '\\') {
				i++;
			}
			else if (c == delimiter) {
				return i + 1;
			}
		}
		return code.length();
	}

	/** Slynk's {@code mrepl-get-object-from-history} as a form. */
	private static LispVal backreference(List<List<LispVal>> history, int entry, @Nullable Integer value) {
		if (entry >= history.size()) {
			return list(new LispSymbol(LispNames.ERROR),
					LispString.literal("Illegal history entry " + entry + " for " + history.size() + "-long history"));
		}
		List<LispVal> values = history.get(entry);
		if (value == null) {
			return list(new LispSymbol("VALUES-LIST"), quoted(list(values)));
		}
		if (value >= values.size()) {
			return list(new LispSymbol(LispNames.ERROR),
					LispString.literal("History entry " + entry + " is only " + values.size() + " elements long."));
		}
		return quoted(values.get(value));
	}

	private static LispVal quoted(LispVal value) {
		return list(new LispSymbol(LispNames.QUOTE), value);
	}

	/** A form with each backreference symbol made the form it stands for. */
	private static LispVal substitute(LispVal form, Map<String, LispVal> forms) {
		if (form instanceof LispSymbol symbol) {
			String name = symbol.name();
			int at = name.indexOf(BACKREFERENCE);
			LispVal found = at < 0 ? null : forms.get(name.substring(at));
			return found != null ? found : form;
		}
		if (form instanceof LispCons cons) {
			LispVal car = substitute(cons.car(), forms);
			LispVal cdr = substitute(cons.cdr(), forms);
			return car == cons.car() && cdr == cons.cdr() ? form : new LispCons(car, cdr);
		}
		return form;
	}

	private @Nullable String copyForm(LispVal value) {
		return switch (value) {
			case LispNil nil -> "'nil";
			case LispTrue t -> "'t";
			case LispSymbol symbol -> (symbol.isKeyword() ? "" : "'") + print(symbol).toLowerCase(Locale.ROOT);
			case LispInteger i -> print(i);
			case LispBigInteger i -> print(i);
			case LispRatio r -> print(r);
			case LispDouble d -> print(d);
			default -> null;
		};
	}

	/** Slynk's {@code present-for-emacs} over its {@code *echo-number-alist*}. */
	private String presentForEmacs(LispVal value) {
		List<String> extras = new ArrayList<>();
		BigInteger integer = integer(value);
		if (integer != null) {
			int bits = integer.bitLength();
			extras.add(bits + " bit" + (bits == 1 ? "" : "s"));
			if (integer.signum() >= 0 && bits <= 64) {
				extras.add("#x" + integer.toString(16).toUpperCase(Locale.ROOT));
			}
			if (integer.signum() >= 0 && bits <= 8) {
				extras.add("#o" + integer.toString(8));
				extras.add("#b" + integer.toString(2));
			}
		}
		else if (value instanceof LispRatio || value instanceof LispDouble) {
			if (value instanceof LispRatio
					&& helper("(lambda (r) (format nil \"~F\" r))", value) instanceof LispString f) {
				extras.add(f.value());
			}
			// format-as-percentage-maybe: when 0 < |f| < 2, (* f 100) by ~2,'0d
			LispVal percent = helper("(lambda (f) (when (< 0 (abs f) 2) (* f 100)))", value);
			if (!(percent instanceof LispNil)) {
				String digits = print(percent);
				extras.add((digits.length() < 2 ? "0" + digits : digits) + "%");
			}
		}
		String printed = print(value);
		return extras.isEmpty() ? printed : printed + " (" + String.join(", ", extras) + ")";
	}

	private static void shift(Environment global, String third, String second, String first, LispVal value) {
		global.define(third, Objects.requireNonNull(global.lookupOrNull(second)));
		global.define(second, Objects.requireNonNull(global.lookupOrNull(first)));
		global.define(first, value);
	}

	/** How an echo area shows a request's values. */
	public enum Echo {

		/**
		 * SWANK's {@code format-values-for-echo-area}: {@code => 5 (3 bits, #x5, #o5,
		 * #b101)}, a ratio with its float, nothing for no value, cut to the echo area.
		 */
		SWANK,

		/** micros's: SWANK's uncut, {@code ; No value} for no value. */
		MICROS,

		/**
		 * Slynk's {@code echo-for-emacs}: no arrow, {@code ; No value}, a number with its
		 * other notations ({@code present-for-emacs}), the values on lines of their own
		 * when one spans lines.
		 */
		SLYNK

	}

	/**
	 * Evaluates the first form of a text, or every form of a region, as
	 * {@code interactive-eval} and {@code interactive-eval-region} do, answering its
	 * values for the echo area.
	 * @param code the text
	 * @param region whether to evaluate every form, answering the last one's values
	 * @param echo how the values are shown
	 * @param lines SWANK's echo area's lines
	 * @param width its width
	 * @return the values, as the echo area shows them
	 */
	public String interactiveEval(String code, boolean region, Echo echo, int lines, int width) {
		return echo(region ? evalRegion(code).values() : evalFirstValues(code), echo, lines, width);
	}

	/**
	 * Values and how the echo area shows them.
	 *
	 * @param values the values
	 * @param echo the text
	 */
	public record Echoed(List<LispVal> values, String echo) {
	}

	/**
	 * Evaluates the first form of a text as micros's {@code pretty-eval}: its values,
	 * which Lem may inspect later, and SWANK's echo of them.
	 * @param code the text
	 * @return the values and their echo
	 */
	public Echoed prettyEval(String code) {
		List<LispVal> values = evalFirstValues(code);
		return new Echoed(values, echo(values, Echo.MICROS, 0, 0));
	}

	/**
	 * Evaluates the first form of a text with its standard output captured, as
	 * {@code eval-and-grab-output}.
	 * @param code the text
	 * @param echo how the values are shown: Slynk's echo area, else one per line
	 * @return the output, then the values
	 */
	public List<String> evalAndGrabOutput(String code, Echo echo) {
		StringWriter output = new StringWriter();
		List<LispVal> values = capturing(output, () -> evalFirstValues(code));
		return List.of(output.toString(), echo == Echo.SLYNK ? echo(values, echo, 0, 0)
				: String.join("\n", values.stream().map(this::print).toList()));
	}

	/**
	 * Evaluates the first form of a text with its output captured, as SWANK's
	 * {@code pprint-eval}: the output, then each value on a line of its own.
	 * @param code the text
	 * @return the output and the values
	 */
	public String pprintEval(String code) {
		StringWriter output = new StringWriter();
		List<LispVal> values = capturing(output, () -> evalFirstValues(code));
		StringBuilder text = new StringBuilder(output.toString());
		if (values.isEmpty()) {
			text.append("; No value");
		}
		for (LispVal value : values) {
			text.append('\n').append(print(value)).append('\n');
		}
		return text.toString();
	}

	private <T> T capturing(Writer output, Supplier<T> body) {
		LispVal stream = this.evaluator.openJavaStream(output);
		List<T> result = new ArrayList<>(1);
		try {
			this.evaluator.withThreadBindings(
					new HashMap<>(Map.of(LispNames.STANDARD_OUTPUT_VAR, stream, LispNames.TRACE_OUTPUT_VAR, stream)),
					() -> result.add(body.get()));
		}
		finally {
			this.evaluator.closeJavaStream(stream);
		}
		return result.getFirst();
	}

	/**
	 * Evaluates a {@code defvar} with its variable unbound first, as SWANK's
	 * {@code re-evaluate-defvar} ({@code C-M-x} on a {@code defvar}).
	 * @param code the {@code defvar} form
	 * @return its value, as {@code prin1} prints it
	 */
	public String reEvaluateDefvar(String code) {
		LispVal form = located(readFirst(code));
		if (!(form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.DEFVAR.equals(head.name().toUpperCase(Locale.ROOT)) && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol)) {
			throw new LispEvalException("Not a DEFVAR form: " + code);
		}
		// a defvar of a variable made unbound first assigns its value: a defparameter
		return print(first(evalForm(new LispCons(new LispSymbol("DEFPARAMETER"), cons.cdr()), false)));
	}

	private static LispVal first(List<LispVal> values) {
		return values.isEmpty() ? LispNil.INSTANCE : values.getFirst();
	}

	/**
	 * Loads a file, as SWANK's {@code load-file}.
	 * @param filename the file
	 * @return what {@code load} answered, as {@code prin1} prints it
	 */
	public String loadFile(String filename) {
		return print(first(evalForm(list(new LispSymbol(LispNames.LOAD), LispString.literal(filename)), false)));
	}

	/**
	 * Values as SWANK's {@code format-values-for-echo-area} formats them: {@code => }
	 * then the values, an integer with its bits and radixes, a ratio with its float,
	 * truncated to the echo area.
	 */
	private String echo(List<LispVal> values, Echo echo, int maxLines, int width) {
		if (echo == Echo.SLYNK) {
			if (values.size() == 1) {
				return presentForEmacs(values.getFirst());
			}
			List<String> printed = values.stream().map(this::print).toList();
			return values.isEmpty() ? "; No value"
					: String.join(printed.stream().anyMatch(text -> text.indexOf('\n') >= 0) ? "\n" : ", ", printed);
		}
		String output;
		if (values.isEmpty()) {
			// SWANK's cond answers "; No value" without writing it, micros writes it
			output = echo == Echo.MICROS ? "; No value" : "";
		}
		else if (values.size() == 1 && integer(values.getFirst()) instanceof BigInteger i) {
			int bits = i.bitLength();
			output = "=> " + i + " (" + bits + " bit" + (bits == 1 ? "" : "s") + ", #x"
					+ i.toString(16).toUpperCase(Locale.ROOT) + ", #o" + i.toString(8) + ", #b" + i.toString(2) + ")";
		}
		else if (values.size() == 1 && values.getFirst() instanceof LispRatio ratio) {
			LispVal flonum = helper("(lambda (r) (format nil \"~F\" r))", ratio);
			output = "=> " + print(ratio) + " (" + (flonum instanceof LispString text ? text.value() : "") + ")";
		}
		else {
			output = "=> " + String.join(", ", values.stream().map(this::print).toList());
		}
		return echo == Echo.SWANK ? truncateForEchoArea(output, maxLines, width) : output;
	}

	private static @Nullable BigInteger integer(LispVal value) {
		return switch (value) {
			case LispInteger i -> BigInteger.valueOf(i.value());
			case LispBigInteger big -> big.value();
			default -> null;
		};
	}

	/** SWANK's truncation of an echo area text to its lines and width. */
	static String truncateForEchoArea(String output, int maxLines, int width) {
		int maxChars = maxLines * width;
		int lines = (int) output.chars().filter(c -> c == '\n').count();
		if (output.length() <= maxChars && lines <= maxChars) {
			return output;
		}
		StringBuilder out = new StringBuilder();
		int linesLeft = maxLines;
		int charsLeft = maxChars;
		int start = 0;
		while (true) {
			int newline = output.indexOf('\n', start);
			int end = newline < 0 ? output.length() : newline;
			int lineLength = end - start;
			if (lineLength > charsLeft || linesLeft == 1) {
				if (start == 0) {
					out.append(output, 0, Math.max(0, charsLeft - 25));
				}
				out.append(" ... ").append(output.substring(Math.max(0, output.length() - 20)));
				return out.toString();
			}
			out.append(output, start, newline < 0 ? end : newline + 1);
			linesLeft--;
			charsLeft -= lineLength;
			if (newline < 0) {
				return out.toString();
			}
			start = newline + 1;
		}
	}

	// ------------------------------------------------------------------------------------
	// Compilation

	/**
	 * A compiler note.
	 *
	 * @param message the text
	 * @param severity {@code error}, {@code read-error}, {@code warning},
	 * {@code style-warning} or {@code note}
	 * @param location where, or null when nowhere in particular
	 */
	public record Note(String message, String severity, @Nullable SwankLocation location) {
	}

	/**
	 * A compilation's outcome.
	 *
	 * @param notes its notes
	 * @param success whether it succeeded: nothing failed to read or run
	 * @param seconds how long it took
	 */
	public record Compilation(List<Note> notes, boolean success, double seconds) {
	}

	/**
	 * Compiles a buffer's text as SWANK's {@code compile-string-for-emacs}
	 * ({@code C-c C-c}): every form of it run in turn, a warning it signals that no
	 * handler muffles a note at its form, the first failure a note that ends the run, the
	 * place the text stops reading a note, and a call of a function nothing defines a
	 * style warning at the call.
	 * @param code the text
	 * @param buffer the buffer's name
	 * @param start the buffer position the text starts at
	 * @return the outcome
	 */
	public Compilation compileString(String code, String buffer, int start) {
		return compileString(code, buffer, start, null);
	}

	/**
	 * Compiles a buffer's text as {@link #compileString(String, String, int)} does, the
	 * definitions it makes found at the buffer visiting a file.
	 * @param code the text
	 * @param buffer the buffer's name
	 * @param start the buffer position the text starts at
	 * @param file the file the buffer visits, or null
	 * @return the outcome
	 */
	public Compilation compileString(String code, String buffer, int start, @Nullable String file) {
		return compile(code, offset -> new SwankLocation.Buffer(buffer, start, offset), true,
				file == null ? offset -> new SwankLocation.Buffer(buffer, start, offset)
						: offset -> new SwankLocation.BufferAndFile(buffer, file, start, offset),
				true);
	}

	/**
	 * Compiles a file as SWANK's {@code compile-file-for-emacs} ({@code C-c C-k}). There
	 * is no file compiler: loading it is compiling it, so a load takes the notes
	 * {@link #compileString} takes, at file positions; a compile without the load only
	 * reads it, a note where it stops reading.
	 * @param filename the file
	 * @param load whether to load it
	 * @return the outcome
	 */
	public Compilation compileFile(String filename, boolean load) {
		String code;
		try {
			code = Files.readString(Path.of(filename), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			return new Compilation(List.of(new Note("Can't read " + filename + ": " + ex.getMessage(), "error", null)),
					false, 0.0);
		}
		java.util.function.IntFunction<SwankLocation> place = offset -> new SwankLocation.File(filename, offset + 1);
		return compile(code, place, load, place, false);
	}

	private Compilation compile(String code, java.util.function.IntFunction<SwankLocation> place, boolean run,
			java.util.function.IntFunction<SwankLocation> definitionPlace, boolean buffer) {
		// compiling is evaluating, and a failure is the compilation's note, as SBCL's
		// compiler takes an error: no debugger stops it
		return this.evaluator.withoutDebugger(() -> compileNoting(code, place, run, definitionPlace, buffer));
	}

	private Compilation compileNoting(String code, java.util.function.IntFunction<SwankLocation> place, boolean run,
			java.util.function.IntFunction<SwankLocation> definitionPlace, boolean buffer) {
		long started = System.nanoTime();
		List<Note> notes = new ArrayList<>();
		LispReader.ReadPrefix read = SourceLanguage.COMMON_LISP.readUntilError(code, this.evaluator.features(), null,
				this.evaluator.sourceStandards(), null);
		List<Integer> offsets = SwankSources.topLevelOffsets(code, false);
		boolean aligned = offsets.size() >= read.forms().size();
		boolean markers = SourceLanguage.usesReadEvalMarkers(code);
		boolean success = true;
		List<LispVal> resolved = new ArrayList<>();
		List<Integer> starts = new ArrayList<>();
		for (int i = 0; run && i < read.forms().size(); i++) {
			int offset = aligned ? offsets.get(i) : 0;
			LispVal form = located(read.forms().get(i));
			try {
				this.evaluator.reportingWarningsTo(
						(text, style) -> notes
							.add(new Note(text, style ? "style-warning" : "warning", place.apply(offset))),
						() -> this.evaluator
							.evalTopLevel(markers ? this.evaluator.resolveReadTimeEvalInCode(form) : form, each -> {
								resolved.add(each);
								starts.add(offset);
							}));
			}
			catch (LispInterruptSignal | LispExitSignal signal) {
				throw signal;
			}
			catch (RuntimeException ex) {
				notes.add(new Note(String.valueOf(ex.getMessage()), "error", place.apply(offset)));
				success = false;
				break;
			}
		}
		LispReadException unread = read.error();
		if (unread != null && success) {
			SourceLocation where = unread.location();
			int offset = where == null ? code.length() : offsetOf(code, where.line(), where.column());
			notes.add(new Note(unread.reason(), "read-error", place.apply(Math.min(offset, code.length()))));
			success = false;
		}
		List<String> undefined = this.sources
			.record(new SwankSources.Compiled(code, resolved, starts, definitionPlace::apply, buffer));
		if (success) {
			for (String name : undefined) {
				int at = callOffset(code, LispSymbol.memberName(name));
				notes.add(new Note("undefined function: " + qualifiedName(name), "style-warning",
						at < 0 ? null : place.apply(at)));
			}
		}
		return new Compilation(List.copyOf(notes), success, (System.nanoTime() - started) / 1e9);
	}

	/** The character offset of a 1-based line and column in a text. */
	private static int offsetOf(String code, int line, int column) {
		int offset = 0;
		for (int l = 1; l < line && offset < code.length(); l++) {
			int newline = code.indexOf('\n', offset);
			if (newline < 0) {
				return code.length();
			}
			offset = newline + 1;
		}
		return Math.min(code.length(), offset + Math.max(0, column - 1));
	}

	private static boolean delimiter(char c) {
		return Character.isWhitespace(c) || c == '(' || c == ')' || c == '"' || c == ';' || c == '\'';
	}

	/** Where the first call of a function by its member name starts in a text, or -1. */
	private static int callOffset(String code, String member) {
		String lower = code.toLowerCase(Locale.ROOT);
		String name = member.toLowerCase(Locale.ROOT);
		int from = 0;
		while (true) {
			int open = lower.indexOf('(', from);
			if (open < 0) {
				return -1;
			}
			int start = open + 1;
			int end = start;
			while (end < lower.length() && !delimiter(lower.charAt(end))) {
				end++;
			}
			String token = lower.substring(start, end);
			int colon = token.lastIndexOf(':');
			if ((colon < 0 ? token : token.substring(colon + 1)).equals(name)) {
				return open;
			}
			from = start;
		}
	}

	/** A canonical spelling qualified with its home package, as SBCL names a symbol. */
	private String qualifiedName(String name) {
		if (name.startsWith(":") || name.startsWith("#:")) {
			return name;
		}
		String home = this.resolver.symbolPackageName(name);
		String member = LispSymbol.memberName(name);
		if (home == null) {
			return "#:" + member;
		}
		boolean external = !name.contains("::") && name.contains(":");
		return packageName(home) + (external || LispNames.CL_PKG.equalsIgnoreCase(home) ? ":" : "::") + member;
	}

	// ------------------------------------------------------------------------------------
	// Symbols

	/** The name the Lisp reader makes of a symbol token: upcased unless escaped. */
	private static String readerName(String token) {
		StringBuilder out = new StringBuilder(token.length());
		boolean bars = false;
		for (int i = 0; i < token.length(); i++) {
			char c = token.charAt(i);
			if (c == '|') {
				bars = !bars;
			}
			else if (c == '\\' && i + 1 < token.length()) {
				out.append(token.charAt(++i));
			}
			else {
				out.append(bars ? c : Character.toUpperCase(c));
			}
		}
		return out.toString();
	}

	/**
	 * A symbol token's parts, as SWANK's {@code tokenize-symbol}: the package before the
	 * first colon (null without one), the name after the last, and whether it is internal
	 * (any count of colons but one).
	 */
	private record Token(String name, @Nullable String pkg, boolean internal) {

		static Token of(String text) {
			int first = text.indexOf(':');
			int last = text.lastIndexOf(':');
			long colons = text.chars().filter(c -> c == ':').count();
			return new Token(last < 0 ? text : text.substring(last + 1), first < 0 ? null : text.substring(0, first),
					colons != 1);
		}

	}

	/**
	 * The canonical spelling of the symbol a token names in the calling thread's package,
	 * found without interning it, or null when no such symbol is accessible.
	 */
	private @Nullable String findSymbol(String text) {
		Token token = Token.of(text);
		String name = readerName(token.name());
		if (name.isEmpty()) {
			return null;
		}
		String pkg = token.pkg() == null ? currentPackage()
				: token.pkg().isEmpty() ? "keyword" : this.resolver.findPackageName(readerName(token.pkg()));
		if (pkg == null) {
			return null;
		}
		if ("keyword".equals(pkg)) {
			return ":" + name;
		}
		try {
			PackageResolver.Accessible found = this.resolver.accessible(pkg, name, this.evaluator::definedInImage);
			return found == null ? null : found.spelling();
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	/**
	 * Every symbol accessible in a package (only its externals when asked), each member
	 * name mapped to its canonical spelling ({@link #accessibleSymbols}).
	 */
	private Map<String, String> symbolsIn(String pkg, boolean externalOnly) {
		Map<String, String> symbols = new TreeMap<>();
		for (Map.Entry<String, PackageResolver.Accessible> entry : accessibleSymbols(pkg).entrySet()) {
			if (!externalOnly || LispNames.STATUS_EXTERNAL.equals(entry.getValue().status())) {
				symbols.put(entry.getKey(), entry.getValue().spelling());
			}
		}
		return symbols;
	}

	/**
	 * Every symbol accessible in a package with its status, by member name: the
	 * registry's entries, and the image's definitions homed there, since a definition
	 * interns its name.
	 * @param pkg the package's canonical name
	 * @return the symbols, in member name order
	 */
	@Override
	public Map<String, PackageResolver.Accessible> accessibleSymbols(String pkg) {
		Map<String, PackageResolver.Accessible> symbols = new TreeMap<>();
		if ("keyword".equals(pkg)) {
			return symbols;
		}
		try {
			for (PackageResolver.Accessible entry : this.resolver.accessibleEntries(pkg)) {
				symbols.put(LispSymbol.memberName(entry.spelling()), entry);
			}
		}
		catch (RuntimeException ex) {
			return symbols;
		}
		Environment global = this.evaluator.globalEnvironment();
		Set<String> defined = new LinkedHashSet<>(global.functionNames());
		defined.addAll(global.variableNames());
		defined.addAll(this.evaluator.userMacroNames());
		String upper = packageName(pkg);
		boolean clUser = LispNames.CL_USER_PKG.equals(upper);
		for (String spelling : defined) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(spelling);
			if (qn == null) {
				if (clUser && !spelling.startsWith(":") && !spelling.startsWith("#:")
						&& !PackageRegistry.isClMemberName(spelling)) {
					symbols.putIfAbsent(spelling, new PackageResolver.Accessible(spelling, LispNames.STATUS_INTERNAL));
				}
				continue;
			}
			String home = this.resolver.findPackageName(qn.pkg());
			if (home != null && packageName(home).equals(upper)) {
				symbols.putIfAbsent(qn.member(), new PackageResolver.Accessible(spelling,
						spelling.contains("::") ? LispNames.STATUS_INTERNAL : LispNames.STATUS_EXTERNAL));
			}
		}
		return symbols;
	}

	/**
	 * A symbol's classification, as SWANK's {@code symbol-classification-string}: a
	 * letter per property -- bound, fbound, generic function, class, type, macro, special
	 * operator, package, accessor -- or a dash.
	 */
	@Override
	public String classification(String spelling) {
		char[] flags = "---------".toCharArray();
		String member = LispSymbol.memberName(spelling);
		boolean cl = PackageRegistry.isClMemberName(spelling);
		if (this.evaluator.bound(spelling)) {
			flag(flags, 'b');
		}
		if (this.evaluator.fbound(spelling)) {
			flag(flags, 'f');
			if (this.evaluator.closRegistry().generics().containsKey(ClosRegistry.normalize(spelling))) {
				flag(flags, 'g');
			}
		}
		if (this.evaluator.closRegistry().findClass(spelling) != null) {
			flag(flags, 'c');
		}
		if (this.evaluator.isUserMacro(spelling) || cl && PackageRegistry.runtimeMacroNames().contains(member)) {
			flag(flags, 'm');
		}
		if (cl && PackageRegistry.ansiSpecialOperatorNames().contains(member)) {
			flag(flags, 's');
		}
		if (!spelling.startsWith(":") && this.resolver.findPackageName(member) != null) {
			flag(flags, 'p');
		}
		return new String(flags);
	}

	private static void flag(char[] flags, char letter) {
		flags[CLASSIFICATION_LETTERS.indexOf(letter)] = letter;
	}

	/**
	 * One completion candidate.
	 *
	 * @param candidate the completed text
	 * @param flags the symbol's classification, or {@code -------p-} for a package
	 * @param qualified the symbol's qualified name, or null for a package
	 */
	public record Completion(String candidate, String flags, @Nullable String qualified) {
	}

	/** What a completion request's input names: the package part, the name part. */
	private record CompletionInput(String name, @Nullable String pkgName, @Nullable String pkg, boolean internal) {
	}

	private CompletionInput completionInput(String text, @Nullable String defaultPackage) {
		Token token = Token.of(text);
		String pkg;
		if (token.pkg() == null) {
			String guessed = guessPackage(defaultPackage);
			pkg = guessed != null ? guessed : currentPackage();
		}
		else if (token.pkg().isEmpty()) {
			pkg = "keyword";
		}
		else {
			pkg = this.resolver.findPackageName(readerName(token.pkg()));
		}
		return new CompletionInput(token.name(), token.pkg(), pkg, token.internal());
	}

	/**
	 * The symbols whose name starts with a prefix, as SWANK's {@code simple-completions}.
	 * @param prefix the prefix, perhaps package-qualified
	 * @param defaultPackage the buffer's package
	 * @return the candidates, sorted by their text
	 */
	public List<Completion> simpleCompletions(String prefix, @Nullable String defaultPackage) {
		CompletionInput input = completionInput(prefix, defaultPackage);
		List<Completion> out = new ArrayList<>();
		if (input.pkg() != null) {
			boolean external = input.pkgName() != null && !input.internal();
			for (Map.Entry<String, String> entry : symbolsIn(input.pkg(), external).entrySet()) {
				String text = entry.getKey().toLowerCase(Locale.ROOT);
				if (text.regionMatches(true, 0, input.name(), 0, input.name().length())) {
					out.add(new Completion(untokenize(input, text), classification(entry.getValue()),
							qualifiedName(entry.getValue())));
				}
			}
		}
		out.sort(Comparator.comparing(Completion::candidate));
		return out;
	}

	/**
	 * The symbols and packages whose name the input is a compound prefix of, a prefix per
	 * hyphen-separated part ({@code m-v-b} completes {@code multiple-value-bind}), as
	 * SWANK's {@code swank-c-p-c} {@code completions}.
	 * @param text the input, perhaps package-qualified
	 * @param defaultPackage the buffer's package
	 * @return the candidates, sorted by their text
	 */
	public List<Completion> compoundCompletions(String text, @Nullable String defaultPackage) {
		CompletionInput input = completionInput(text, defaultPackage);
		boolean upper = input.name().chars().anyMatch(Character::isUpperCase)
				&& input.name().chars().noneMatch(Character::isLowerCase);
		List<Completion> out = new ArrayList<>();
		if (input.pkg() != null) {
			boolean external = input.pkgName() != null && !input.internal();
			for (Map.Entry<String, String> entry : symbolsIn(input.pkg(), external).entrySet()) {
				String name = upper ? entry.getKey() : entry.getKey().toLowerCase(Locale.ROOT);
				if (compoundPrefix(input.name(), name, "-")) {
					out.add(new Completion(untokenize(input, name), classification(entry.getValue()),
							qualifiedName(entry.getValue())));
				}
			}
		}
		if (input.pkgName() == null) {
			for (String pkgName : packageNames(true)) {
				String candidate = (upper ? pkgName : pkgName.toLowerCase(Locale.ROOT)) + ":";
				if (compoundPrefix(input.name(), candidate, ".-")) {
					out.add(new Completion(candidate, "-------p-", null));
				}
			}
		}
		out.sort(Comparator.comparing(Completion::candidate));
		return out;
	}

	/** SWANK's {@code make-compound-prefix-matcher}, case-insensitively. */
	static boolean compoundPrefix(String prefix, String target, String delimiters) {
		int t = 0;
		for (int i = 0; i < prefix.length(); i++) {
			char c = prefix.charAt(i);
			if (t >= target.length()) {
				return false;
			}
			if (delimiters.indexOf(c) >= 0) {
				t = target.indexOf(c, t);
				if (t < 0) {
					return false;
				}
			}
			else if (Character.toLowerCase(c) != Character.toLowerCase(target.charAt(t))) {
				return false;
			}
			t++;
		}
		return true;
	}

	private static String untokenize(CompletionInput input, String name) {
		if (input.pkgName() == null) {
			return name;
		}
		return input.pkgName() + (input.internal() && !input.pkgName().isEmpty() ? "::" : ":") + name;
	}

	/**
	 * A fuzzy completion candidate.
	 *
	 * @param candidate the completed text
	 * @param flags the symbol's classification
	 * @param qualified the symbol's qualified name
	 * @param chunks each matched run: its offset and its text
	 * @param score how well it matches
	 */
	public record FuzzyCompletion(String candidate, String flags, String qualified,
			List<Map.Entry<Integer, String>> chunks, double score) {
	}

	/**
	 * The symbols whose name holds the input's letters in order, best match first, as
	 * SWANK's {@code swank-fuzzy}: each match scored as it scores one (a letter at the
	 * start or after a hyphen counts most, a run more than its letters).
	 * @param text the input, perhaps package-qualified
	 * @param defaultPackage the buffer's package
	 * @param limit how many to answer, 0 for all
	 * @return the candidates
	 */
	public List<FuzzyCompletion> fuzzyCompletions(String text, @Nullable String defaultPackage, int limit) {
		CompletionInput input = completionInput(text, defaultPackage);
		List<FuzzyCompletion> out = new ArrayList<>();
		if (input.pkg() == null || input.name().isEmpty()) {
			return out;
		}
		boolean external = input.pkgName() != null && !input.internal();
		String typed = input.name().toLowerCase(Locale.ROOT);
		for (Map.Entry<String, String> entry : symbolsIn(input.pkg(), external).entrySet()) {
			String name = entry.getKey().toLowerCase(Locale.ROOT);
			Fuzzy best = Fuzzy.best(typed, name);
			if (best != null) {
				out.add(new FuzzyCompletion(untokenize(input, name), classification(entry.getValue()),
						qualifiedName(entry.getValue()), best.chunks(), best.score()));
			}
		}
		out.sort(Comparator.comparingDouble(FuzzyCompletion::score)
			.reversed()
			.thenComparing(FuzzyCompletion::candidate));
		return limit > 0 && out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
	}

	/** One way the letters of an input match a name, and its score. */
	private record Fuzzy(List<Map.Entry<Integer, String>> chunks, double score) {

		/** The best-scoring match of {@code typed} in {@code full}, or null. */
		static @Nullable Fuzzy best(String typed, String full) {
			int at = 0;
			for (int i = 0; i < typed.length() && at >= 0; i++) {
				at = full.indexOf(typed.charAt(i), at);
				at = at < 0 ? -1 : at + 1;
			}
			if (at < 0) {
				// not a subsequence: no way to take the letters in order
				return null;
			}
			List<List<Map.Entry<Integer, String>>> all = new ArrayList<>();
			matches(typed, 0, full, 0, new ArrayList<>(), all, new int[] { 4096 });
			Fuzzy best = null;
			for (List<Map.Entry<Integer, String>> chunks : all) {
				double score = score(chunks, typed, full);
				if (best == null || score > best.score()) {
					best = new Fuzzy(chunks, score);
				}
			}
			return best;
		}

		// every way to take the typed letters as runs of the name, longest runs first,
		// bounded in matches and steps so a long name cannot explode the search
		private static void matches(String typed, int ti, String full, int fi, List<Map.Entry<Integer, String>> chunks,
				List<List<Map.Entry<Integer, String>>> out, int[] steps) {
			if (out.size() >= 64 || --steps[0] < 0) {
				return;
			}
			if (ti == typed.length()) {
				out.add(List.copyOf(chunks));
				return;
			}
			for (int start = full.indexOf(typed.charAt(ti), fi); start >= 0; start = full.indexOf(typed.charAt(ti),
					start + 1)) {
				int length = 0;
				while (ti + length < typed.length() && start + length < full.length()
						&& typed.charAt(ti + length) == full.charAt(start + length)) {
					length++;
				}
				for (int take = length; take >= 1; take--) {
					chunks.add(Map.entry(start, full.substring(start, start + take)));
					matches(typed, ti + take, full, start + take, chunks, out, steps);
					chunks.removeLast();
				}
			}
		}

		// SWANK's score-completion
		private static double score(List<Map.Entry<Integer, String>> chunks, String typed, String full) {
			double total = 0;
			for (Map.Entry<Integer, String> chunk : chunks) {
				double previous = 0;
				for (int k = 0; k < chunk.getValue().length(); k++) {
					int pos = chunk.getKey() + k;
					double base = charScore(pos, full);
					double value = k == 0 ? base : Math.max(base, previous * 0.85 + Math.pow(1.2, k));
					total += value;
					previous = value;
				}
			}
			return total + 10.0 / (1 + full.length() - typed.length());
		}

		private static double charScore(int pos, String full) {
			String separators = "-/.:";
			if (pos == 0) {
				return 10;
			}
			if (pos == 1 && "*+-%&?".indexOf(full.charAt(0)) >= 0) {
				return 10;
			}
			if (separators.indexOf(full.charAt(pos)) >= 0) {
				return 1;
			}
			if (separators.indexOf(full.charAt(pos - 1)) >= 0) {
				return 8;
			}
			if (pos == full.length() - 1) {
				return 6;
			}
			if (pos == full.length() - 2 && "*+-%&?".indexOf(full.charAt(full.length() - 1)) >= 0) {
				return 6;
			}
			return 1;
		}

	}

	/**
	 * A flex completion candidate, as Slynk's {@code flex-completions} lists it.
	 *
	 * @param text the completed text
	 * @param score how well it matches
	 * @param chunks each matched run of the symbol's name: its offset and its text
	 * @param classification what the symbol names ({@code fn}, {@code macro}, {@code var}
	 * ...), comma-separated
	 */
	public record FlexCompletion(String text, float score, List<Map.Entry<Integer, String>> chunks,
			String classification) {
	}

	/**
	 * One symbol the pattern flex-matches: the name it matched, the indexes, the score.
	 */
	private record FlexMatch(String string, String spelling, List<Integer> indexes, float score) {
	}

	/**
	 * The symbols whose name holds the pattern's characters in order, as Slynk's
	 * {@code flex-completions} finds them: those accessible in the package, then the
	 * external symbols of the packages it does not use qualified by each of their names
	 * ({@code pkg:name}), best score first, then -- for a pattern with {@code ::} --
	 * every symbol qualified by its home package.
	 * @param pattern what the user typed
	 * @param defaultPackage the buffer's package
	 * @param limit how many to answer beyond the first
	 * @return the candidates
	 */
	public List<FlexCompletion> flexCompletions(String pattern, @Nullable String defaultPackage, int limit) {
		String guessed = guessPackage(defaultPackage);
		String home = packageName(guessed != null ? guessed : currentPackage());
		String upper = pattern.toUpperCase(Locale.ROOT);
		int firstColon = upper.indexOf(':');
		boolean twoColons = firstColon >= 0 && firstColon + 1 < upper.length() && upper.charAt(firstColon + 1) == ':';
		List<FlexMatch> external = new ArrayList<>();
		List<FlexMatch> internal = new ArrayList<>();
		if (firstColon < 0) {
			for (Map.Entry<String, String> entry : symbolsIn(home, false).entrySet()) {
				flexCollect(upper, entry.getKey().toUpperCase(Locale.ROOT), entry.getValue(), external);
			}
		}
		if (firstColon != 0 || twoColons) {
			Map<String, List<String>> uses = this.resolver.runtimePackageUseTable();
			List<String> homeUses = uses.getOrDefault(home, List.of());
			for (String pkg : uses.keySet()) {
				if ("KEYWORD".equals(pkg)
						|| !twoColons && (pkg.equals(home) || firstColon < 0 && homeUses.contains(pkg))) {
					continue;
				}
				List<String> names = new ArrayList<>(nicknames(pkg));
				names.add(pkg);
				names.sort(Comparator.comparingInt(String::length));
				for (Map.Entry<String, String> entry : symbolsIn(pkg, !twoColons).entrySet()) {
					String symbolHome = this.resolver.symbolPackageName(entry.getValue());
					String homeName = symbolHome == null ? null : packageName(symbolHome);
					// every symbol at its home; an external one not inherited from a used
					// package
					if (twoColons ? !pkg.equals(homeName) : firstColon < 0 && homeUses.contains(homeName)) {
						continue;
					}
					for (String name : names) {
						flexCollect(upper, name + (twoColons ? "::" : ":") + entry.getKey().toUpperCase(Locale.ROOT),
								entry.getValue(), twoColons ? internal : external);
					}
				}
			}
		}
		Comparator<FlexMatch> byScore = Comparator.comparingDouble(FlexMatch::score)
			.reversed()
			.thenComparing(FlexMatch::string);
		external.sort(byScore);
		internal.sort(byScore);
		external.addAll(internal);
		boolean upcase = pattern.chars().allMatch(Character::isUpperCase);
		List<FlexCompletion> out = new ArrayList<>();
		for (FlexMatch match : external.subList(0, Math.min(external.size(), limit + 1))) {
			List<Map.Entry<Integer, String>> chunks = new ArrayList<>();
			for (int index : match.indexes()) {
				Map.Entry<Integer, String> last = chunks.isEmpty() ? null : chunks.getLast();
				if (last != null && last.getKey() + last.getValue().length() == index) {
					chunks.set(chunks.size() - 1,
							Map.entry(last.getKey(), last.getValue() + match.string().charAt(index)));
				}
				else {
					chunks.add(Map.entry(index, String.valueOf(match.string().charAt(index))));
				}
			}
			out.add(new FlexCompletion(upcase ? match.string() : match.string().toLowerCase(Locale.ROOT), match.score(),
					chunks, readablyClassify(classification(match.spelling()))));
		}
		return out;
	}

	/** Slynk's {@code collect-if-matches}: the pattern's characters found in order. */
	private static void flexCollect(String pattern, String string, String spelling, List<FlexMatch> into) {
		List<Integer> indexes = new ArrayList<>(pattern.length());
		int from = 0;
		for (int i = 0; i < pattern.length(); i++) {
			int pos = string.indexOf(pattern.charAt(i), from);
			if (pos < 0) {
				return;
			}
			indexes.add(pos);
			from = pos + 1;
		}
		into.add(new FlexMatch(string, spelling, indexes, flexScore(string, indexes, pattern)));
	}

	/** Slynk's {@code flex-score}, a qualified name's two halves scored apart. */
	static float flexScore(String string, List<Integer> indexes, String pattern) {
		int patternColon = pattern.indexOf(':');
		if (patternColon > 0) {
			int colonAt = indexes.get(patternColon);
			List<Integer> after = new ArrayList<>();
			for (int index : indexes.subList(patternColon + 1, indexes.size())) {
				after.add(index - colonAt);
			}
			return flexScore1(colonAt, indexes.subList(0, patternColon)) / 2
					+ flexScore1(string.length() - colonAt, after) / 2;
		}
		int stringColon = string.indexOf(':');
		if (stringColon >= 0 && !indexes.isEmpty() && stringColon < indexes.getFirst()) {
			// as if the qualifier were half as long: a match on the name counts more
			int adjust = stringColon / 2;
			return flexScore1(string.length() - adjust, indexes.stream().map(index -> index - adjust).toList());
		}
		return flexScore1(string.length(), indexes);
	}

	private static float flexScore1(int length, List<Integer> indexes) {
		float gaps = 0;
		int previous = -1;
		for (int i = 0; i <= indexes.size(); i++) {
			int next = i < indexes.size() ? indexes.get(i) : length;
			gaps += (float) Math.pow(next - previous - 1, 1.5);
			previous = next;
		}
		return indexes.size() / (length * (1 + gaps));
	}

	/**
	 * Slynk's {@code readably-classify} of a SWANK classification string: the generic
	 * function, package, special operator, macro, class, type, function and variable it
	 * names, a function left out when it is a generic function or a macro.
	 */
	private static String readablyClassify(String flags) {
		List<String> out = new ArrayList<>();
		String[][] order = { { "g", "generic-fn" }, { "p", "pak" }, { "s", "special-op" }, { "m", "macro" },
				{ "c", "cla" }, { "t", "type" }, { "f", "fn" }, { "b", "var" } };
		boolean dropFunction = flags.indexOf('g') >= 0 || flags.indexOf('m') >= 0;
		for (String[] each : order) {
			if (flags.contains(each[0]) && !(dropFunction && "f".equals(each[0]))) {
				out.add(each[1]);
			}
		}
		return String.join(",", out);
	}

	// ------------------------------------------------------------------------------------
	// Argument lists and documentation

	/**
	 * An operator's argument list, as SWANK's {@code operator-arglist}:
	 * {@code (mapcar FUNCTION LIST &REST MORE-LISTS)}, or null when unknown.
	 * @param name the operator's name as written
	 * @return the text
	 */
	public @Nullable String operatorArglist(String name) {
		String spelling = findSymbol(name);
		LispVal arglist = spelling == null ? null : arglist(spelling);
		if (arglist == null) {
			return null;
		}
		StringBuilder text = new StringBuilder("(").append(name);
		for (LispVal param = arglist; param instanceof LispCons cell; param = cell.cdr()) {
			text.append(' ').append(arglistText(cell.car(), true));
		}
		return text.append(')').toString();
	}

	/**
	 * The lambda list of what a canonical name defines: the one its definition wrote, the
	 * desugared one of a function value, or the standard one of a built-in.
	 */
	@Override
	public @Nullable LispVal arglist(String spelling) {
		LispEvaluator.Definition definition = this.evaluator.definition(spelling);
		if (definition != null) {
			return definition.lambdaList();
		}
		LispVal function = this.evaluator.globalEnvironment().lookupFunctionOrNull(spelling);
		if (function instanceof LispLambda lambda) {
			List<LispVal> params = new ArrayList<>(lambda.params());
			if (lambda.rest() != null) {
				params.add(new LispSymbol("&REST"));
				params.add(Objects.requireNonNull(lambda.rest()));
			}
			return list(params);
		}
		if (PackageRegistry.isClMemberName(spelling)) {
			return clArglists().get(spelling);
		}
		return null;
	}

	private Map<String, LispVal> clArglists() {
		Map<String, LispVal> table = this.clArglists;
		if (table != null) {
			return table;
		}
		Map<String, LispVal> loaded = new HashMap<>();
		try (var in = CommonLispSwank.class.getResourceAsStream("cl-arglists.txt")) {
			if (in != null) {
				String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				for (String line : text.split("\n")) {
					int space = line.indexOf(' ');
					if (space > 0 && !line.startsWith(";")) {
						List<LispVal> forms = LispReader
							.readPrefixFromString(line.substring(space + 1), this.evaluator.features(), null)
							.forms();
						if (!forms.isEmpty()) {
							loaded.put(line.substring(0, space).toUpperCase(Locale.ROOT), forms.getFirst());
						}
					}
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		this.clArglists = Map.copyOf(loaded);
		return Objects.requireNonNull(this.clArglists);
	}

	/**
	 * A lambda list item as an argument list shows it: names only, downcased unless
	 * asked.
	 */
	@Override
	public String arglistText(LispVal arglist) {
		return arglistText(arglist, true);
	}

	static String arglistText(LispVal item, boolean upcase) {
		return switch (item) {
			case LispSymbol symbol -> {
				String member = LispSymbol.memberName(symbol.name());
				yield upcase ? member.toUpperCase(Locale.ROOT) : member.toLowerCase(Locale.ROOT);
			}
			case LispCons cons -> {
				StringBuilder text = new StringBuilder("(");
				LispVal rest = cons;
				boolean first = true;
				while (rest instanceof LispCons cell) {
					if (!first) {
						text.append(' ');
					}
					text.append(arglistText(cell.car(), upcase));
					first = false;
					rest = cell.cdr();
				}
				if (!(rest instanceof LispNil)) {
					text.append(" . ").append(arglistText(rest, upcase));
				}
				yield text.append(')').toString();
			}
			case LispNil nil -> upcase ? "NIL" : "nil";
			default -> item.print();
		};
	}

	/**
	 * What an editor shows of the form its cursor is in.
	 *
	 * @param text the argument list with the parameter at the cursor marked, or a
	 * variable's value
	 * @param cacheable whether the editor may keep it
	 */
	public record Autodoc(String text, boolean cacheable) {
	}

	/**
	 * The argument list of the innermost operator around the cursor that has one, its
	 * parameter at the cursor marked {@code ===> name <===}, or the value of the variable
	 * the cursor is on, as SWANK's {@code swank-arglists} {@code autodoc}.
	 * @param rawForm the form the cursor is in: each element its text, a nested form a
	 * list, {@link #CURSOR} where the cursor stands
	 * @return what to show, or null when nothing is known
	 */
	public @Nullable Autodoc autodoc(List<?> rawForm) {
		List<List<?>> path = new ArrayList<>();
		List<Integer> positions = new ArrayList<>();
		if (!locate(rawForm, path, positions)) {
			return null;
		}
		List<?> innermost = path.getLast();
		int cursor = positions.getLast();
		if (cursor >= 2 && innermost.get(cursor - 1) instanceof String atCursor && !atCursor.isEmpty()) {
			Autodoc variable = variableAt(atCursor);
			if (variable != null) {
				return variable;
			}
		}
		for (int depth = path.size() - 1; depth >= 0; depth--) {
			List<?> form = path.get(depth);
			if (form.isEmpty() || !(form.getFirst() instanceof String operator) || operator.isEmpty()) {
				continue;
			}
			String spelling = findSymbol(operator);
			LispVal arglist = spelling == null ? null : arglist(spelling);
			if (arglist == null) {
				continue;
			}
			// the argument the cursor is in, and what the cursor is in within it
			int index = depth == path.size() - 1 ? cursor - 1 : positions.get(depth);
			List<?> deeper = depth == path.size() - 1 ? null : path.get(depth + 1);
			Object highlight = highlighted(arglist, form, index, deeper);
			StringBuilder text = new StringBuilder("(").append(operator.toLowerCase(Locale.ROOT));
			appendArglist(text, arglist, highlight);
			return new Autodoc(text.append(')').toString(), true);
		}
		return null;
	}

	private static boolean locate(List<?> form, List<List<?>> path, List<Integer> positions) {
		for (int i = 0; i < form.size(); i++) {
			Object element = form.get(i);
			if (element == CURSOR) {
				path.add(form);
				positions.add(i);
				return true;
			}
			if (element instanceof List<?> nested) {
				int size = path.size();
				path.add(form);
				positions.add(i);
				if (locate(nested, path, positions)) {
					return true;
				}
				while (path.size() > size) {
					path.removeLast();
					positions.removeLast();
				}
			}
		}
		return false;
	}

	private @Nullable Autodoc variableAt(String text) {
		String spelling = findSymbol(text);
		if (spelling == null || spelling.startsWith(":") || "T".equals(spelling) || "NIL".equals(spelling)
				|| !this.evaluator.bound(spelling) || LispNames.PACKAGE_VAR.equals(spelling)) {
			return null;
		}
		LispVal value = this.evaluator.globalEnvironment().lookupOrNull(spelling);
		if (value == null) {
			return null;
		}
		String shown = LispSymbol.memberName(spelling).toUpperCase(Locale.ROOT) + " => " + print(value);
		return new Autodoc(shown.length() > 75 ? shown.substring(0, 75) + ".." : shown, false);
	}

	/**
	 * The lambda list item the cursor's argument binds: by position for the required and
	 * optional parameters, the {@code &rest} one past them, a keyword parameter by the
	 * keyword before the value (or the one being typed); inside a destructuring
	 * parameter, the item within it.
	 */
	private @Nullable Object highlighted(LispVal arglist, List<?> form, int index, @Nullable List<?> deeper) {
		if (index < 1) {
			return null;
		}
		List<LispVal> required = new ArrayList<>();
		List<LispVal> optional = new ArrayList<>();
		List<LispVal> keys = new ArrayList<>();
		LispVal rest = null;
		String mode = "";
		for (LispVal item = arglist; item instanceof LispCons cell; item = cell.cdr()) {
			if (cell.car() instanceof LispSymbol symbol && LispSymbol.memberName(symbol.name()).startsWith("&")) {
				mode = LispSymbol.memberName(symbol.name()).toUpperCase(Locale.ROOT);
				continue;
			}
			switch (mode) {
				case "" -> required.add(cell.car());
				case "&OPTIONAL" -> optional.add(cell.car());
				case "&REST", "&BODY" -> rest = rest == null ? cell.car() : rest;
				case "&KEY" -> keys.add(cell.car());
				default -> {
				}
			}
		}
		int arg = index - 1;
		if (arg < required.size()) {
			LispVal item = required.get(arg);
			if (item instanceof LispCons pattern && deeper != null) {
				Object inner = highlighted(pattern, prepend(deeper), positionIn(deeper) + 1, null);
				return inner != null ? inner : item;
			}
			return item;
		}
		arg -= required.size();
		if (arg < optional.size()) {
			return optional.get(arg);
		}
		arg -= optional.size();
		if (!keys.isEmpty()) {
			int at = index;
			String keyword = arg % 2 == 0 ? (form.get(at) instanceof String typed ? typed : null)
					: (form.get(at - 1) instanceof String previous ? previous : null);
			if (keyword != null && keyword.startsWith(":")) {
				for (LispVal key : keys) {
					if (keyName(key).equalsIgnoreCase(keyword.substring(1))) {
						return key;
					}
				}
			}
			return rest;
		}
		return rest;
	}

	private static List<Object> prepend(List<?> deeper) {
		List<Object> form = new ArrayList<>();
		form.add("");
		form.addAll(deeper);
		return form;
	}

	private static int positionIn(List<?> deeper) {
		for (int i = 0; i < deeper.size(); i++) {
			if (deeper.get(i) == CURSOR) {
				return Math.max(0, i - 1);
			}
			if (deeper.get(i) instanceof List<?>) {
				return i;
			}
		}
		return deeper.size();
	}

	private static String keyName(LispVal key) {
		LispVal name = key instanceof LispCons cons ? cons.car() : key;
		if (name instanceof LispCons pair) {
			name = pair.car();
		}
		return name instanceof LispSymbol symbol ? LispSymbol.memberName(symbol.name()) : "";
	}

	private static void appendArglist(StringBuilder text, LispVal arglist, @Nullable Object highlight) {
		LispVal rest = arglist;
		while (rest instanceof LispCons cell) {
			text.append(' ');
			LispVal item = cell.car();
			if (item == highlight) {
				text.append("===> ").append(arglistText(item, false)).append(" <===");
			}
			else if (item instanceof LispCons nested && contains(nested, highlight)) {
				StringBuilder inner = new StringBuilder();
				appendArglist(inner, nested, highlight);
				text.append('(').append(inner.substring(1)).append(')');
			}
			else {
				text.append(arglistText(item, false));
			}
			rest = cell.cdr();
		}
		if (!(rest instanceof LispNil)) {
			text.append(" . ").append(arglistText(rest, false));
		}
	}

	private static boolean contains(LispCons list, @Nullable Object item) {
		for (LispVal rest = list; rest instanceof LispCons cell; rest = cell.cdr()) {
			if (cell.car() == item || cell.car() instanceof LispCons nested && contains(nested, item)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A symbol's description, as SWANK's {@code describe-symbol} prints it: its name,
	 * then what it names -- a function or macro with its lambda list and documentation, a
	 * variable with its value, a class, a package.
	 * @param name the symbol's name as written
	 * @return the description
	 * @throws LispEvalException when no such symbol is accessible
	 */
	@Override
	public String describeSymbol(String name) {
		String spelling = findSymbolOrLose(name);
		String member = LispSymbol.memberName(spelling).toUpperCase(Locale.ROOT);
		StringBuilder text = new StringBuilder(qualifiedName(spelling)).append("\n  [symbol]\n");
		if (this.evaluator.bound(spelling) && !spelling.startsWith(":")) {
			LispVal value = this.evaluator.globalEnvironment().lookupOrNull(spelling);
			text.append('\n')
				.append(member)
				.append(" names a ")
				.append(this.evaluator.isSpecialVariable(spelling) ? "special " : "")
				.append("variable:\n  Value: ")
				.append(value == null ? "unbound" : print(value))
				.append('\n');
		}
		describeOperator(text, spelling, member);
		if (this.evaluator.closRegistry().findClass(spelling) != null) {
			text.append('\n').append(member).append(" names a class.\n");
		}
		if (this.resolver.findPackageName(member) != null && !spelling.startsWith(":")) {
			text.append('\n').append(member).append(" names a package.\n");
		}
		return text.toString();
	}

	private void describeOperator(StringBuilder text, String spelling, String member) {
		if (!this.evaluator.fbound(spelling)) {
			return;
		}
		boolean cl = PackageRegistry.isClMemberName(spelling);
		String kind;
		if (cl && PackageRegistry.ansiSpecialOperatorNames().contains(member)) {
			kind = "a special operator";
		}
		else if (this.evaluator.isUserMacro(spelling) || cl && PackageRegistry.runtimeMacroNames().contains(member)) {
			kind = "a macro";
		}
		else if (this.evaluator.closRegistry().generics().containsKey(ClosRegistry.normalize(spelling))) {
			kind = "a generic function";
		}
		else {
			kind = "a function";
		}
		text.append('\n').append(member).append(" names ").append(kind).append(":\n");
		LispVal arglist = arglist(spelling);
		if (arglist != null) {
			text.append("  Lambda-list: ").append(arglistText(arglist, true)).append('\n');
		}
		LispEvaluator.Definition definition = this.evaluator.definition(spelling);
		if (definition != null && definition.documentation() != null) {
			text.append("  Documentation:\n");
			for (String line : Objects.requireNonNull(definition.documentation()).split("\n")) {
				text.append("    ").append(line).append('\n');
			}
		}
	}

	/**
	 * What a symbol names, in Markdown, as micros's {@code hover-symbol} answers Lem's
	 * describe: a {@code ## Variable} section with its value, a {@code ## Function} one
	 * with its signature in a code block and its documentation string, separated by a
	 * rule.
	 * @param name the symbol's name as written
	 * @return the description, or null when no such symbol is accessible
	 */
	public @Nullable String hoverSymbol(String name) {
		String spelling = findSymbol(name);
		return spelling == null ? null : describeInMarkdown(spelling);
	}

	private String describeInMarkdown(String spelling) {
		List<String> sections = new ArrayList<>();
		if (this.evaluator.bound(spelling) && !spelling.startsWith(":")) {
			LispVal value = this.evaluator.globalEnvironment().lookupOrNull(spelling);
			sections.add("## Variable\nValue: " + (value == null ? "unbound" : print(value)) + "\n");
		}
		if (this.evaluator.fbound(spelling)) {
			StringBuilder text = new StringBuilder("## Function\n```lisp\n").append(signature(spelling))
				.append("\n```\n");
			LispEvaluator.Definition definition = this.evaluator.definition(spelling);
			if (definition != null && definition.documentation() != null) {
				text.append(definition.documentation()).append('\n');
			}
			sections.add(text.toString());
		}
		return String.join("----------\n", sections).stripTrailing();
	}

	/**
	 * micros's {@code symbol-signature}: the operator and its argument list, downcased.
	 */
	private String signature(String spelling) {
		LispVal arglist = arglist(spelling);
		String name = LispSymbol.memberName(spelling).toLowerCase(Locale.ROOT);
		if (arglist == null) {
			return "(" + name + " . not-available)";
		}
		// the argument list's own parenthesis opens the signature
		return arglist instanceof LispNil ? "(" + name + ")"
				: "(" + name + " " + arglistText(arglist, false).substring(1);
	}

	/**
	 * A completion as micros's {@code micros/lsp-api:completions} lists it for Lem.
	 *
	 * @param label the completed text
	 * @param chunks each matched run: its offset and its text
	 * @param kind what the symbol names ({@code function}, {@code macro} ...),
	 * comma-separated
	 * @param signature the operator and its argument list
	 * @param documentation its description in Markdown ({@link #hoverSymbol})
	 */
	public record LspCompletion(String label, List<Map.Entry<Integer, String>> chunks, String kind,
			@Nullable String signature, String documentation) {
	}

	/**
	 * The fuzzy completions of a text as micros's {@code micros/lsp-api:completions}
	 * describes them: the first hundred, each with its kind, signature and documentation.
	 * @param text the input, perhaps package-qualified
	 * @param defaultPackage the buffer's package
	 * @return the completions
	 */
	public List<LspCompletion> lspCompletions(String text, @Nullable String defaultPackage) {
		List<LspCompletion> out = new ArrayList<>();
		// micros's parse-classification-string reads the flags' first eight places so
		String[] kinds = { "variable", "function", "generic-function", "type", "class", "macro", "special-operator",
				"package" };
		for (FuzzyCompletion each : fuzzyCompletions(text, defaultPackage, 100)) {
			List<String> kind = new ArrayList<>();
			for (int i = 0; i < kinds.length; i++) {
				if (each.flags().charAt(i) != '-') {
					kind.add(kinds[i]);
				}
			}
			String spelling = findSymbol(each.qualified());
			out.add(new LspCompletion(each.candidate(), each.chunks(), String.join(", ", kind),
					spelling == null ? null : signature(spelling),
					spelling == null ? "" : describeInMarkdown(spelling)));
		}
		return out;
	}

	/**
	 * Defines {@code micros:load-file}, the one function of Lem's server Lem calls in a
	 * form of its own ({@code C-c C-l}): the file loaded, {@code load}'s value
	 * {@code prin1}'d.
	 */
	public void defineMicrosLoadFile() {
		Object previous = this.resolver.bindThreadPackage(LispNames.CL_USER_PKG);
		try {
			for (LispVal form : read("""
					(defpackage :micros (:use :cl) (:export #:load-file))
					(defun micros:load-file (filename) (prin1-to-string (load filename)))
					""")) {
				this.evaluator.eval(form);
			}
		}
		finally {
			this.resolver.unbindThreadPackage(previous);
		}
	}

	/**
	 * The description of what a symbol names as an operator, as SWANK's
	 * {@code describe-function}.
	 * @param name the symbol's name as written
	 * @return the description
	 */
	public String describeFunction(String name) {
		String spelling = findSymbolOrLose(name);
		if (!this.evaluator.fbound(spelling)) {
			throw new LispEvalException("The function " + qualifiedName(spelling) + " is undefined.");
		}
		StringBuilder text = new StringBuilder();
		describeOperator(text, spelling, LispSymbol.memberName(spelling).toUpperCase(Locale.ROOT));
		return text.substring(1);
	}

	/**
	 * A symbol's documentation, as SWANK's {@code documentation-symbol} prints it.
	 * @param name the symbol's name as written
	 * @return the documentation, or that there is none
	 */
	public String documentationSymbol(String name) {
		String spelling = findSymbol(name);
		if (spelling == null) {
			return "No such symbol, " + name + ".";
		}
		String member = LispSymbol.memberName(spelling).toUpperCase(Locale.ROOT);
		LispEvaluator.Definition definition = this.evaluator.definition(spelling);
		String doc = definition == null ? null : definition.documentation();
		StringBuilder text = new StringBuilder("Documentation for the symbol ").append(member).append(":\n\n");
		if (doc == null) {
			return text.append("Not documented.").toString();
		}
		LispVal arglist = arglist(spelling);
		return text.append("Function:\n Arglist: ")
			.append(arglist == null ? "NIL" : arglistText(arglist, true))
			.append("\n\n ")
			.append(doc)
			.toString();
	}

	private String findSymbolOrLose(String name) {
		String spelling = findSymbol(name);
		if (spelling == null) {
			throw new LispEvalException("Unknown symbol: " + name + " [in " + packageName(currentPackage()) + "]");
		}
		return spelling;
	}

	// ------------------------------------------------------------------------------------
	// Macroexpansion

	/** Which expansion {@link #macroexpand} makes. */
	public enum Expansion {

		/** One step of the form's own macro ({@code macroexpand-1}). */
		ONCE,

		/** The form's own macro until it is none ({@code macroexpand}). */
		REPEATEDLY,

		/** Every macro in the form, user and built-in ({@code macroexpand-all}). */
		ALL

	}

	/**
	 * Expands a form, as SWANK's {@code swank-macroexpand-1}, {@code swank-macroexpand}
	 * and {@code swank-macroexpand-all}; the expansion is printed as {@code prin1} prints
	 * it.
	 * @param code the form
	 * @param expansion which expansion
	 * @return the expansion
	 */
	public String macroexpand(String code, Expansion expansion) {
		LispVal form = this.evaluator.resolvePackages(readFirst(code));
		LispVal expanded = switch (expansion) {
			case ONCE -> this.evaluator.macroexpand1(form);
			case REPEATEDLY -> this.evaluator.macroexpand(form);
			case ALL -> expandAll(form);
		};
		return print(expanded);
	}

	private LispVal expandAll(LispVal form) {
		LispVal expanded = this.evaluator.macroexpand(form);
		return CallSiteWalk.walk(expanded, new CallSiteWalk.Rewriter() {
			@Override
			public @Nullable LispVal call(LispCons call, LispSymbol head) {
				LispVal once = CommonLispSwank.this.evaluator.macroexpand(call);
				return once == call ? null : CallSiteWalk.walk(once, this);
			}
		});
	}

	/**
	 * What {@code swank-macrostep:macro-form-p} answers.
	 *
	 * @param kind {@code macro}, {@code compiler-macro}, or null when the form is neither
	 * @param error why the text did not read, or null
	 */
	public record MacroForm(@Nullable String kind, @Nullable String error) {
	}

	/**
	 * What {@code swank-macrostep:macrostep-expand-1} answers.
	 *
	 * @param expansion the expansion, printed
	 * @param subforms the macro forms in the expansion, where each starts in the text
	 * @param error why there is no expansion, or null
	 */
	public record MacroStep(String expansion, List<Subform> subforms, @Nullable String error) {
	}

	/**
	 * A macro form in a {@link MacroStep}'s expansion.
	 *
	 * @param operator its operator, printed
	 * @param kind {@code macro} or {@code compiler-macro}
	 * @param start where it starts in the printed expansion
	 */
	public record Subform(String operator, String kind, int start) {
	}

	/**
	 * Whether a form is a macro form, as {@code swank-macrostep:macro-form-p}: a list
	 * headed by a symbol that {@code macroexpand-1} expands, or, when asked, that a
	 * compiler macro expands.
	 * @param code the form
	 * @param compilerMacros whether a compiler macro counts
	 * @return the answer
	 */
	public MacroForm macroForm(String code, boolean compilerMacros) {
		LispVal form;
		try {
			form = this.evaluator.resolvePackages(readFirst(code));
		}
		catch (RuntimeException ex) {
			return new MacroForm(null, "Read error: " + ex.getMessage());
		}
		return new MacroForm(macroKind(form, compilerMacros), null);
	}

	/**
	 * Expands a form once, as {@code swank-macrostep:macrostep-expand-1}, with where each
	 * macro form of the expansion starts in its printed text.
	 * @param code the form
	 * @param compilerMacros whether a compiler macro expands it when no macro does
	 * @return the expansion, or why there is none
	 */
	public MacroStep macrostepExpand(String code, boolean compilerMacros) {
		LispVal form = this.evaluator.resolvePackages(readFirst(code));
		String kind = macroKind(form, compilerMacros);
		if (kind == null) {
			return new MacroStep("", List.of(),
					compilerMacros ? "Not a macro or compiler-macro form" : "Not a macro form");
		}
		LispVal expansion = expandOnce(form, kind);
		Set<LispVal> tree = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		collectConses(expansion, tree);
		List<Map.Entry<LispCons, String>> found = new ArrayList<>();
		CallSiteWalk.walk(expansion, new CallSiteWalk.Rewriter() {
			@Override
			public @Nullable LispVal call(LispCons call, LispSymbol head) {
				String each = macroKind(call, compilerMacros);
				if (each == null) {
					return null;
				}
				if (tree.contains(call)) {
					found.add(Map.entry(call, each));
				}
				CallSiteWalk.walk(expandOnce(call, each), this);
				return call;
			}
		});
		String text = print(expansion);
		List<Subform> subforms = new ArrayList<>();
		int cursor = 1;
		for (Map.Entry<LispCons, String> each : found) {
			int at = text.indexOf(print(each.getKey()), cursor);
			if (at >= 0) {
				subforms.add(new Subform(print(each.getKey().car()), each.getValue(), at));
				cursor = at + 1;
			}
		}
		return new MacroStep(text, subforms, null);
	}

	private @Nullable String macroKind(LispVal form, boolean compilerMacros) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return null;
		}
		if (!PackageRegistry.ansiSpecialOperatorNames().contains(LispSymbol.memberName(head.name()))
				&& this.evaluator.macroexpand1(cons) != cons) {
			return "macro";
		}
		return compilerMacros && this.evaluator.expandCompilerMacro(cons) != cons ? "compiler-macro" : null;
	}

	private LispVal expandOnce(LispVal form, String kind) {
		return "macro".equals(kind) ? this.evaluator.macroexpand1(form)
				: this.evaluator.expandCompilerMacro((LispCons) form);
	}

	private static void collectConses(LispVal value, Set<LispVal> into) {
		for (LispVal rest = value; rest instanceof LispCons cell && into.add(cell); rest = cell.cdr()) {
			collectConses(cell.car(), into);
		}
	}

	// ------------------------------------------------------------------------------------
	// Definitions and cross references

	/**
	 * Where what a name names is defined, as SWANK's {@code find-definitions-for-emacs}.
	 * @param name the name as the editor sent it
	 * @return each definition's description and place
	 */
	public List<SwankSources.Entry> findDefinitions(String name) {
		return this.sources.findDefinitions(name);
	}

	/**
	 * The uses of a name, as SWANK's {@code xref}.
	 * @param type the kind of use, without its colon ({@code calls}, {@code references}
	 * ...)
	 * @param name the name as the editor sent it
	 * @return each use's description and place, or null when the kind is not answered
	 */
	public @Nullable List<SwankSources.Entry> xref(String type, String name) {
		return this.sources.xref(type, name);
	}

	/**
	 * A new inspector, whose state is the editor's to keep.
	 * @return the inspector
	 */
	public SwankInspector newInspector() {
		return new SwankInspector(this, this.evaluator, this.sources);
	}

	/** The canonical spelling of the symbol a name names, without interning it. */
	@Override
	public @Nullable String symbolNamed(String text) {
		return findSymbol(text);
	}

	/**
	 * The canonical spellings a symbol token may have in the calling thread's package:
	 * bare in {@code cl-user}, else qualified internal, then external.
	 */
	@Override
	public List<String> spellingsOf(String text) {
		Token token = Token.of(text);
		String name = readerName(token.name());
		String pkg = token.pkg() == null ? currentPackage()
				: token.pkg().isEmpty() ? "keyword" : this.resolver.findPackageName(readerName(token.pkg()));
		if (name.isEmpty() || pkg == null) {
			return List.of();
		}
		if ("keyword".equals(pkg)) {
			return List.of(":" + name);
		}
		String upper = packageName(pkg);
		if (LispNames.CL_USER_PKG.equals(upper)) {
			return List.of(name);
		}
		return List.of(upper + "::" + name, upper + ":" + name);
	}

	/** A member name spelled in the package a canonical spelling is homed in. */
	@Override
	public String homedLike(String spelling, String member) {
		int colon = spelling.indexOf(':');
		return colon > 0 ? spelling.substring(0, spelling.lastIndexOf(':') + 1) + member : member;
	}

	/** A text read as one form, its symbols resolved in the calling thread's package. */
	@Override
	public LispVal readResolved(String code) {
		return this.evaluator.resolvePackages(readFirst(code));
	}

	// ------------------------------------------------------------------------------------
	// Helpers

	/**
	 * A value as {@code prin1} prints it in the calling thread's package.
	 * @param value the value
	 * @return the text
	 */
	@Override
	public String print(LispVal value) {
		return this.evaluator.prin1ToStringRouted(value);
	}

	/**
	 * Evaluates a form a request sends that names no SWANK function, as SWANK's
	 * {@code eval-for-emacs} evaluates any form.
	 * @param form the form
	 * @return its value
	 */
	public LispVal evaluate(LispVal form) {
		return first(evalForm(form, false));
	}

	/**
	 * Sets the REPL's history variables to nil, as SWANK's {@code clear-repl-variables}.
	 */
	public void clearReplVariables() {
		for (String name : REPL_VARIABLES) {
			this.evaluator.globalEnvironment().define(name, LispNil.INSTANCE);
		}
	}

	// ------------------------------------------------------------------------------------
	// The debugger (SLDB)

	/** What an interrupt's debugger level reports, as SWANK's {@code simple-break}. */
	public static final String INTERRUPT_MESSAGE = "Interrupt from Emacs";

	/**
	 * The width a frame's or a local's text is cut at, SWANK's backtrace right margin.
	 */
	private static final int FRAME_WIDTH = 200;

	/** Prints a frame's call under SWANK's backtrace print length and level. */
	private static final String FRAME_PRINTER = "(lambda (x) (let ((*print-length* 6) (*print-level* 4))"
			+ " (prin1-to-string x)))";

	/**
	 * The restart each thread's {@code throw-to-toplevel} invokes ({@link #withRestart}),
	 * SWANK's {@code *sldb-quit-restart*}.
	 */
	private final ThreadLocal<@Nullable LispVal> quitRestart = new ThreadLocal<>();

	/** What runs SLDB on the thread a condition was signalled on ({@link #serve}). */
	public interface Sldb {

		/**
		 * Runs a debugger level over a condition, answering the editor's requests on this
		 * thread until a restart transfers control out of it. Returning declines: the
		 * condition goes on as if no debugger were there.
		 * @param debugging the condition, its restarts and its frames
		 */
		void enter(Debugging debugging);

		/**
		 * What an interrupt's debugger level reports: the editor names itself (SLIME's
		 * {@code Interrupt from Emacs}, SLY's {@code Interrupt from client}, Lem's
		 * {@code Interrupt from Editor}).
		 * @return the message
		 */
		default String interruptMessage() {
			return INTERRUPT_MESSAGE;
		}

	}

	/**
	 * Runs the body with a restart established on the calling thread, as
	 * {@code restart-case} establishes one: the debugger lists it, {@code find-restart}
	 * finds it, and invoking it -- by the debugger, or by name -- leaves the body for
	 * {@code invoked}.
	 * @param <T> what the body answers
	 * @param name the restart's name, a canonical symbol spelling
	 * @param report its report
	 * @param quit whether it is what {@code throw-to-toplevel} invokes, listed with a
	 * {@code *}
	 * @param body the body
	 * @param invoked what answers once the restart is invoked
	 * @return what the body or {@code invoked} answered
	 */
	public <T extends @Nullable Object> T withRestart(String name, String report, boolean quit, Supplier<T> body,
			Supplier<T> invoked) {
		LispVal tag = new LispCons(new LispSymbol(LispNames.RESTART_RECORD_TAG), LispNil.INSTANCE);
		LispVal record = list(new LispSymbol(LispNames.RESTART_RECORD_TAG), new LispSymbol(name),
				new LispFunction(LispNames.INVOKE_RESTART, args -> {
					throw new ThrowSignal(tag, args.isEmpty() ? LispNil.INSTANCE : args.getFirst());
				}), LispString.literal(report), LispNil.INSTANCE, LispNil.INSTANCE);
		LispVal saved = restartStack();
		this.evaluator.setSpecialValue(LispNames.RESTART_CLUSTERS_VAR, new LispCons(list(record), saved));
		LispVal savedQuit = this.quitRestart.get();
		if (quit) {
			this.quitRestart.set(record);
		}
		try {
			return this.evaluator.catchingOutside(tag, body);
		}
		catch (ThrowSignal signal) {
			if (signal.tag() != tag) {
				throw signal;
			}
			return invoked.get();
		}
		finally {
			this.evaluator.setSpecialValue(LispNames.RESTART_CLUSTERS_VAR, saved);
			if (quit) {
				this.quitRestart.set(savedQuit);
			}
		}
	}

	private LispVal restartStack() {
		LispVal stack = this.evaluator.specialValue(LispNames.RESTART_CLUSTERS_VAR);
		return stack == null ? LispNil.INSTANCE : stack;
	}

	/**
	 * Invokes the calling thread's quit restart, as SWANK's {@code throw-to-toplevel}:
	 * the one {@link #withRestart} marked, else the outermost restart. Leaves by its
	 * transfer of control.
	 * @return the text SWANK answers when no restart is active
	 */
	public String throwToToplevel() {
		LispVal restart = this.quitRestart.get();
		if (restart == null) {
			List<LispVal> restarts = activeRestarts();
			restart = restarts.isEmpty() ? null : restarts.getLast();
		}
		if (restart == null) {
			return "Restart not active [NIL]";
		}
		return print(invoke(restart, LispNil.INSTANCE));
	}

	/**
	 * Runs the body under a frame on the calling thread's stack, as a debugger's
	 * backtrace shows it: what a request evaluates.
	 * @param <T> what the body answers
	 * @param call the call the frame shows
	 * @param body the body
	 * @return what it answered
	 */
	public <T extends @Nullable Object> T withFrame(LispVal call, Supplier<T> body) {
		return this.evaluator.withFrame(call, body);
	}

	/**
	 * Whether a Java exception leaving a request is a transfer of control -- a restart's
	 * invocation, a {@code throw}, a {@code return-from} -- that a frame further out
	 * takes, rather than the request's failure.
	 * @param ex the exception
	 * @return whether it transfers control
	 */
	public boolean isTransfer(RuntimeException ex) {
		return LispEvaluator.isControlTransfer(ex);
	}

	/**
	 * Stops the calling thread where an interrupt is pending for it, as its next function
	 * call would -- into its debugger: what a wait an interrupt woke checks.
	 */
	public void checkInterrupt() {
		this.evaluator.checkInterrupt();
	}

	/** The calling thread's restarts, innermost first, as {@code compute-restarts}. */
	private List<LispVal> activeRestarts() {
		List<LispVal> restarts = new ArrayList<>();
		for (LispVal cluster = restartStack(); cluster instanceof LispCons cell; cluster = cell.cdr()) {
			for (LispVal entry = cell.car(); entry instanceof LispCons each; entry = each.cdr()) {
				restarts.add(each.car());
			}
		}
		return restarts;
	}

	/**
	 * A restart record's slot ({@code (%restart name invoker report interactive test)}).
	 */
	private static LispVal slot(LispVal record, int index) {
		LispVal rest = record;
		for (int i = 0; i < index && rest instanceof LispCons cell; i++) {
			rest = cell.cdr();
		}
		return rest instanceof LispCons cell ? cell.car() : LispNil.INSTANCE;
	}

	/**
	 * Invokes a restart with an argument list; answers only what a restart-bind's
	 * returns.
	 */
	private LispVal invoke(LispVal restart, LispVal args) {
		return this.evaluator.applyGlobal(slot(restart, 2), List.of(args));
	}

	/** A restart's name as SWANK lists it: {@code ~a} of the symbol. */
	private static String restartName(LispVal restart) {
		return slot(restart, 1) instanceof LispSymbol name ? LispSymbol.memberName(name.name())
				: slot(restart, 1).display();
	}

	/** The evaluator's debugger seam over a thread's SLDB. */
	private final class ThreadDebugger implements Debugger {

		private final Sldb sldb;

		ThreadDebugger(Sldb sldb) {
			this.sldb = sldb;
		}

		@Override
		public void debug(LispVal condition, String message, @Nullable Environment env, @Nullable CallFrame operator) {
			enter(new Debugging(condition, message, true, env, operator));
		}

		@Override
		public boolean interrupted() {
			// SWANK's simple-break: a condition no handler sees, under a CONTINUE
			// restart that goes on with the evaluation
			String message = this.sldb.interruptMessage();
			LispVal condition = CommonLispSwank.this.evaluator.simpleError(message);
			return withRestart(LispNames.CONTINUE, "Continue from break.", false, () -> {
				enter(new Debugging(condition, message, false, null, null));
				return false;
			}, () -> true);
		}

		private void enter(Debugging debugging) {
			CommonLispSwank.this.evaluator.withHandlerCasesIsolated(() -> this.sldb.enter(debugging));
		}

	}

	/**
	 * One frame of a debugger level's backtrace, taken where the condition was signalled.
	 *
	 * @param call what a marker or a built-in's frame shows, or null for a function's
	 * @param lambda the function, or null
	 * @param args the function's arguments
	 * @param entry the function's call scope, or null
	 * @param scope the frame's innermost scope, where its locals are and an expression
	 * evaluates, or null for none
	 * @param live the function's activation on the stack, to restart or return from, or
	 * null
	 * @param forms the forms the frame stepped onto last, newest first: the newest of the
	 * source is the one it waits in
	 * @param catches the tags of its active {@code catch} forms, innermost first
	 */
	private record Frame(@Nullable LispVal call, @Nullable LispLambda lambda, List<LispVal> args,
			@Nullable Environment entry, @Nullable Environment scope, @Nullable CallFrame live, List<LispVal> forms,
			List<LispVal> catches) {
	}

	/**
	 * One local variable of a frame, as SWANK's {@code frame-locals-and-catch-tags} lists
	 * it.
	 *
	 * @param name the variable's name, as {@code prin1} prints it
	 * @param id its number among the frame's variables of that name
	 * @param value its value on one line
	 */
	public record Local(String name, int id, String value) {
	}

	/**
	 * A debugger level's view of the condition it was entered for, taken where it was
	 * signalled, before anything unwound: the condition SLDB shows, the restarts then
	 * active and the frames then waiting -- and what SLDB asks of them, a restart's
	 * invocation, a frame's locals, an evaluation in a frame.
	 */
	public final class Debugging {

		private final LispVal condition;

		private final String message;

		private final boolean signalled;

		private final String pkg;

		private final List<LispVal> restarts;

		private final @Nullable LispVal quit;

		private final List<Frame> frames;

		Debugging(LispVal condition, String message, boolean signalled, @Nullable Environment env,
				@Nullable CallFrame operator) {
			this.condition = condition;
			this.message = message;
			this.signalled = signalled;
			this.pkg = currentPackage();
			this.restarts = activeRestarts();
			this.quit = CommonLispSwank.this.quitRestart.get();
			this.frames = frames(env, operator);
		}

		/**
		 * The frames from the signal point down, each function's with its innermost
		 * scope: the signal's for the innermost, the scope the next one in was called
		 * from for each further out. A marker's top-level form has the scope its own
		 * bindings made, the {@code let} of an {@code (EVAL (LET ...))}.
		 */
		private List<Frame> frames(@Nullable Environment env, @Nullable CallFrame operator) {
			List<Frame> out = new ArrayList<>();
			Environment inner = env;
			for (CallFrame each = operator != null ? operator
					: CommonLispSwank.this.evaluator.topFrame(); each != null; each = each.parent) {
				LispLambda lambda = each.lambda();
				Environment entry = each.entry();
				if (each == operator) {
					// a built-in's call is the form its caller waits in
					CallFrame caller = each.parent;
					out.add(new Frame(each.call, null, List.of(), null, null, null,
							caller == null ? List.of() : caller.recentForms(), List.of()));
				}
				else if (lambda == null || entry == null) {
					out.add(new Frame(each.call, null, List.of(), null, inner, null, each.recentForms(),
							each.catchTags()));
					// what ran below a marker ran in no scope of what runs above it
					inner = null;
				}
				else {
					out.add(new Frame(null, lambda, List.copyOf(each.args()), entry,
							inner != null && descends(inner, entry) ? inner : entry, each.restartable() ? each : null,
							each.recentForms(), each.catchTags()));
					inner = each.caller;
				}
			}
			return out;
		}

		/**
		 * The condition's report.
		 * @return the text
		 */
		public String message() {
			return this.message;
		}

		/**
		 * Whether the condition was signalled, which handlers then saw, rather than an
		 * interrupt's, which SWANK hands its debugger directly.
		 * @return whether it was signalled
		 */
		public boolean signalled() {
			return this.signalled;
		}

		/**
		 * The condition's type, as {@code ~S} of {@code type-of} prints it.
		 * @return the type's name
		 */
		public String conditionType() {
			return inPackage(() -> {
				LispVal type = helper("(lambda (c) (prin1-to-string (type-of c)))", this.condition);
				return type instanceof LispString text ? text.value() : "SIMPLE-ERROR";
			});
		}

		/**
		 * What a request the condition aborted reports, as SWANK prints the condition:
		 * {@code #<TYPE "report">}.
		 * @return the text
		 */
		public String describe() {
			return "#<" + conditionType() + " " + LispString.literal(this.message).print() + ">";
		}

		/**
		 * The restarts active where the condition was signalled, innermost first, each
		 * its name -- {@code *} before the quit restart's -- and its report.
		 * @return the name and report pairs
		 */
		public List<List<String>> restarts() {
			List<List<String>> out = new ArrayList<>(this.restarts.size());
			for (LispVal restart : this.restarts) {
				out.add(List.of((restart == this.quit ? "*" : "") + restartName(restart), report(restart)));
			}
			return out;
		}

		/**
		 * A restart's report, as {@code princ} prints the restart: its report function's
		 * output, a report string as written ({@code restart-case}'s, and the format
		 * control of a {@code with-simple-restart} or a {@code cerror}, whose arguments
		 * the restart does not keep), or its name.
		 */
		private String report(LispVal restart) {
			LispVal report = slot(restart, 3);
			return inPackage(() -> switch (report) {
				case LispNil nil -> restartName(restart);
				case LispString text -> text.value();
				default -> {
					try {
						yield helper("(lambda (f) (with-output-to-string (s) (funcall f s)))",
								report) instanceof LispString done ? done.value() : "";
					}
					catch (RuntimeException ex) {
						yield "<<error printing restart>>";
					}
				}
			});
		}

		/**
		 * Invokes the restart at an index of the list, as SWANK's
		 * {@code invoke-nth-restart}: its {@code :interactive} function supplies the
		 * arguments. Leaves by the restart's transfer of control.
		 * @param index the index
		 * @return what a {@code restart-bind} function returned, or null when no restart
		 * is at the index
		 */
		public @Nullable LispVal invokeNth(int index) {
			if (index < 0 || index >= this.restarts.size()) {
				return null;
			}
			LispVal restart = this.restarts.get(index);
			LispVal interactive = slot(restart, 4);
			LispVal args = interactive == LispNil.INSTANCE ? LispNil.INSTANCE
					: CommonLispSwank.this.evaluator.applyGlobal(interactive, List.of());
			return invoke(restart, args);
		}

		/**
		 * Invokes the first listed restart of a name, as SWANK's {@code sldb-abort} and
		 * {@code sldb-continue}. Leaves by the restart's transfer of control.
		 * @param name the restart's name
		 * @return what a {@code restart-bind} function returned, or null when no restart
		 * has the name
		 */
		public @Nullable LispVal invokeNamed(String name) {
			for (LispVal restart : this.restarts) {
				if (restartName(restart).equals(name)) {
					return invoke(restart, LispNil.INSTANCE);
				}
			}
			return null;
		}

		/**
		 * How many frames the backtrace has.
		 * @return the count
		 */
		public int frameCount() {
			return this.frames.size();
		}

		/**
		 * A frame's call on one line, as SWANK's {@code backtrace} prints it.
		 * @param index the frame's index, 0 the innermost
		 * @return the text
		 */
		public String frame(int index) {
			Frame frame = this.frames.get(index);
			LispVal call = frame.call();
			if (call == null) {
				LispLambda lambda = Objects.requireNonNull(frame.lambda());
				LispVal head = lambda.name() != null ? new LispSymbol(Objects.requireNonNull(lambda.name()))
						: list(new LispSymbol(LispNames.LAMBDA), lambdaList(lambda));
				call = new LispCons(head, list(frame.args()));
			}
			LispVal printed = call;
			return frameLine(inPackage(() -> {
				try {
					return helper(FRAME_PRINTER, printed) instanceof LispString text ? text.value() : "";
				}
				catch (RuntimeException ex) {
					return "[error printing frame]";
				}
			}));
		}

		/**
		 * A frame's local variables: its parameters and the variables bound in it where
		 * it is waiting, outermost binding first, as SWANK's
		 * {@code frame-locals-and-catch-tags} lists them.
		 * @param index the frame's index
		 * @return the locals
		 */
		public List<Local> locals(int index) {
			Map<String, Integer> seen = new HashMap<>();
			List<Local> locals = new ArrayList<>();
			for (Map.Entry<String, LispVal> each : localValues(index)) {
				int id = seen.merge(each.getKey(), 1, Integer::sum) - 1;
				locals.add(inPackage(
						() -> new Local(print(new LispSymbol(each.getKey())), id, valueLine(each.getValue()))));
			}
			return locals;
		}

		/**
		 * A frame's local variable's value, as SWANK's {@code frame-var-value}.
		 * @param index the frame's index
		 * @param var the variable's index among {@link #locals}
		 * @return the value, or null when there is no such variable
		 */
		public @Nullable LispVal localValue(int index, int var) {
			if (index < 0 || index >= this.frames.size()) {
				return null;
			}
			List<Map.Entry<String, LispVal>> values = localValues(index);
			return var >= 0 && var < values.size() ? values.get(var).getValue() : null;
		}

		/** A frame's locals by name and value, in {@link #locals}'s order. */
		private List<Map.Entry<String, LispVal>> localValues(int index) {
			Frame frame = this.frames.get(index);
			Environment entry = frame.entry();
			Environment scope = frame.scope();
			if (scope == null) {
				return List.of();
			}
			// a function's scopes up to its call scope, a top-level form's up to the
			// global one, which holds no local
			List<Environment> chain = new ArrayList<>();
			for (Environment each = scope; each != null && each.parentScope() != null; each = each.parentScope()) {
				chain.add(each);
				if (each == entry) {
					break;
				}
			}
			List<Map.Entry<String, LispVal>> values = new ArrayList<>();
			for (int i = chain.size() - 1; i >= 0; i--) {
				Environment each = chain.get(i);
				for (String name : each.variableNames()) {
					LispVal value = each.lookupOrNull(name);
					if (value == null || value == Environment.SPECIAL || name.startsWith("__")) {
						continue;
					}
					values.add(Map.entry(name, value));
				}
			}
			return values;
		}

		/**
		 * The tags of the {@code catch} forms active in a frame, innermost first, as
		 * SWANK's {@code frame-locals-and-catch-tags} prints them.
		 * @param index the frame's index
		 * @return the printed tags
		 */
		public List<String> catchTags(int index) {
			List<String> tags = new ArrayList<>();
			for (LispVal tag : this.frames.get(index).catches()) {
				tags.add(inPackage(() -> print(tag)));
			}
			return tags;
		}

		/**
		 * The condition the level was entered for, as SWANK's
		 * {@code inspect-current-condition} inspects it.
		 * @return the condition
		 */
		public LispVal condition() {
			return this.condition;
		}

		/**
		 * Whether a frame is a function's activation the debugger can restart or return
		 * from, which SWANK's backtrace marks {@code (:restartable t)}.
		 * @param index the frame's index
		 * @return whether it is
		 */
		public boolean restartable(int index) {
			return this.frames.get(index).live() != null;
		}

		/**
		 * Calls a frame's function again with its arguments, as SWANK's
		 * {@code restart-frame}: every frame further in unwinds, the frame's activation
		 * ends with the new call's values. Leaves by that transfer of control.
		 * @param index the frame's index
		 * @return SWANK's text for a frame it cannot restart
		 */
		public String restartFrame(int index) {
			CallFrame live = liveFrame(index);
			if (live == null) {
				return "Cannot restart frame: " + frameOrNil(index);
			}
			LispLambda lambda = Objects.requireNonNull(live.lambda());
			List<LispVal> args = List.copyOf(live.args());
			throw new FrameSignal(live, () -> CommonLispSwank.this.evaluator.applyGlobal(lambda, args));
		}

		/**
		 * Returns from a frame the values of an expression evaluated in it, as SWANK's
		 * {@code sldb-return-from-frame}. Leaves by that transfer of control.
		 * @param index the frame's index
		 * @param code the expression
		 * @return SWANK's text for a frame it cannot return from: the list of its
		 * message, printed
		 */
		public String returnFromFrame(int index, String code) {
			CallFrame live = liveFrame(index);
			if (live == null) {
				return print(list(LispString.literal("Cannot return from frame: " + frameOrNil(index))));
			}
			List<LispVal> values = evalIn(code, index, null);
			Environment global = CommonLispSwank.this.evaluator.globalEnvironment();
			throw new FrameSignal(live, () -> {
				if (values.isEmpty()) {
					global.publishSpill(LispMacroExpander.MV_ZERO_VALUES);
					return LispNil.INSTANCE;
				}
				global.publishSpill(list(values.subList(1, values.size())));
				return values.getFirst();
			});
		}

		private @Nullable CallFrame liveFrame(int index) {
			return index >= 0 && index < this.frames.size() ? this.frames.get(index).live() : null;
		}

		private String frameOrNil(int index) {
			return index >= 0 && index < this.frames.size() ? frame(index) : "NIL";
		}

		/**
		 * The value of an expression evaluated in a frame, as SWANK's
		 * {@code inspect-in-frame} inspects it: the primary value.
		 * @param code the expression
		 * @param index the frame's index
		 * @return the value
		 */
		public LispVal valueInFrame(String code, int index) {
			List<LispVal> values = evalIn(code, index, null);
			return values.isEmpty() ? LispNil.INSTANCE : values.getFirst();
		}

		/**
		 * Where the form a frame waits in is, as SWANK's {@code frame-source-location}:
		 * the newest form it stepped onto that the walk of a compiled or loaded text
		 * placed, or that was read from a file (a form a macro expansion made is
		 * neither); for a function with no text, its form printed with the newest of its
		 * forms marked.
		 * @param index the frame's index
		 * @return the place, or null for none
		 */
		public SwankSources.@Nullable Place source(int index) {
			if (index < 0 || index >= this.frames.size()) {
				return null;
			}
			Frame frame = this.frames.get(index);
			SwankSources.Place place = CommonLispSwank.this.sources.placeOf(frame.forms());
			if (place != null) {
				return place;
			}
			LispLambda lambda = frame.lambda();
			if (lambda == null) {
				return null;
			}
			LispVal whole = new LispCons(new LispSymbol(LispNames.LAMBDA),
					new LispCons(lambdaList(lambda), list(lambda.body())));
			LispVal marked = marking(whole, frame.forms());
			String printed = inPackage(() -> print(marked)).replace("(" + HERE + " ", "(#:" + HERE + " ");
			return new SwankSources.Place(new SwankLocation.SourceForm(printed), null);
		}

		/**
		 * The package a frame's expressions read in, as SWANK's
		 * {@code frame-package-name}: the package the debugged request ran in.
		 * @param index the frame's index
		 * @return the package's name
		 */
		public String framePackage(int index) {
			return packageName(this.pkg);
		}

		/**
		 * Evaluates an expression in a frame -- its locals in reach -- as SWANK's
		 * {@code eval-string-in-frame}, answering its values for the echo area.
		 * @param code the expression
		 * @param index the frame's index
		 * @param pkg the package it reads in, or null for the frame's
		 * @param lines the echo area's lines
		 * @param width its width
		 * @param echo how the echo area shows the values
		 * @return the values, as SWANK formats them
		 */
		public String evalInFrame(String code, int index, @Nullable String pkg, int lines, int width, Echo echo) {
			return echo(evalIn(code, index, pkg), echo, lines, width);
		}

		/**
		 * Evaluates an expression in a frame, as SWANK's
		 * {@code pprint-eval-string-in-frame}: each value on a line of its own.
		 * @param code the expression
		 * @param index the frame's index
		 * @param pkg the package it reads in, or null for the frame's
		 * @return the values
		 */
		public String pprintEvalInFrame(String code, int index, @Nullable String pkg) {
			List<LispVal> values = evalIn(code, index, pkg);
			StringBuilder text = new StringBuilder();
			if (values.isEmpty()) {
				text.append("; No value");
			}
			for (LispVal value : values) {
				text.append(print(value)).append('\n');
			}
			return text.toString();
		}

		private List<LispVal> evalIn(String code, int index, @Nullable String pkg) {
			String found = pkg == null ? null : guessPackage(pkg);
			Environment scope = index >= 0 && index < this.frames.size() ? this.frames.get(index).scope() : null;
			Object previous = CommonLispSwank.this.resolver.bindThreadPackage(found == null ? this.pkg : found);
			try {
				LispVal form = readFirst(code);
				return CommonLispSwank.this.evaluator.evalValuesIn(form,
						scope == null ? CommonLispSwank.this.evaluator.globalEnvironment() : scope);
			}
			finally {
				CommonLispSwank.this.resolver.unbindThreadPackage(previous);
			}
		}

		/** Runs a printing in the package the debugged request ran in. */
		private <T> T inPackage(Supplier<T> body) {
			Object previous = CommonLispSwank.this.resolver.bindThreadPackage(this.pkg);
			try {
				return body.get();
			}
			finally {
				CommonLispSwank.this.resolver.unbindThreadPackage(previous);
			}
		}

	}

	/**
	 * The marker SBCL wraps around the form a frame waits in, in a printed source form.
	 */
	private static final String HERE = "***HERE***";

	/**
	 * A tree with the first of some conses it holds, by identity, wrapped in
	 * {@code (***HERE*** form)}; the tree itself when it holds none.
	 */
	private static LispVal marking(LispVal tree, List<LispVal> candidates) {
		Set<LispVal> conses = Collections.newSetFromMap(new IdentityHashMap<>());
		collectConses(tree, conses);
		for (LispVal candidate : candidates) {
			if (conses.contains(candidate)) {
				return marking(tree, candidate, Collections.newSetFromMap(new IdentityHashMap<>()));
			}
		}
		return tree;
	}

	private static LispVal marking(LispVal tree, LispVal target, Set<LispVal> seen) {
		if (tree == target) {
			return list(new LispSymbol(HERE), target);
		}
		List<LispVal> items = new ArrayList<>();
		boolean changed = false;
		LispVal rest = tree;
		while (rest instanceof LispCons cell && seen.add(cell)) {
			LispVal item = marking(cell.car(), target, seen);
			changed |= item != cell.car();
			items.add(item);
			rest = cell.cdr();
			if (rest == target) {
				break;
			}
		}
		if (!changed) {
			return tree;
		}
		LispVal out = rest;
		for (int i = items.size() - 1; i >= 0; i--) {
			out = new LispCons(items.get(i), out);
		}
		return out;
	}

	/** Whether a scope is the other or lies inside it. */
	private static boolean descends(Environment scope, Environment ancestor) {
		for (Environment each = scope; each != null; each = each.parentScope()) {
			if (each == ancestor) {
				return true;
			}
		}
		return false;
	}

	/** A value on one line, as SWANK's {@code to-line} prints a local's. */
	private String valueLine(LispVal value) {
		String text = print(value);
		int newline = text.indexOf('\n');
		if (newline >= 0) {
			text = text.substring(0, newline) + " ..";
		}
		return text.length() > FRAME_WIDTH ? text.substring(0, FRAME_WIDTH) + ".." : text;
	}

	/**
	 * A frame's printed call on one line, as SWANK's backtrace printer cuts it: at the
	 * first line break, {@code " .."} and the lists it was inside closed, then at the
	 * right margin, {@code ".."}.
	 * @param printed the printed call
	 * @return the line
	 */
	static String frameLine(String printed) {
		String line = printed;
		int newline = printed.indexOf('\n');
		if (newline >= 0) {
			String head = printed.substring(0, newline);
			line = head + " .." + ")".repeat(openLists(head));
		}
		return line.length() > FRAME_WIDTH ? line.substring(0, FRAME_WIDTH) + ".." : line;
	}

	/** How many lists a printed prefix leaves open, strings and escapes skipped. */
	private static int openLists(String text) {
		int depth = 0;
		boolean string = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (string) {
				if (c == '\\') {
					i++;
				}
				else if (c == '"') {
					string = false;
				}
			}
			else if (c == '"') {
				string = true;
			}
			else if (c == '\\') {
				i++;
			}
			else if (c == '(') {
				depth++;
			}
			else if (c == ')' && depth > 0) {
				depth--;
			}
		}
		return depth;
	}

	/** A function's lambda list, as a frame of it shows it. */
	private static LispVal lambdaList(LispLambda lambda) {
		List<LispVal> params = new ArrayList<>(lambda.params());
		if (lambda.rest() != null) {
			params.add(new LispSymbol(LispNames.LAMBDA_REST));
			params.add(Objects.requireNonNull(lambda.rest()));
		}
		return list(params);
	}

	/** Calls a one-argument lambda, read once in {@code cl-user}, on a value. */
	@Override
	public LispVal helper(String source, LispVal argument) {
		LispVal function = this.helpers.computeIfAbsent(source, text -> {
			Object previous = this.resolver.bindThreadPackage(LispNames.CL_USER_PKG);
			try {
				return this.evaluator.eval(read(text).getFirst());
			}
			finally {
				this.resolver.unbindThreadPackage(previous);
			}
		});
		// a helper's failure is its caller's to answer: no debugger stops it
		return this.evaluator.withoutDebugger(() -> this.evaluator.applyGlobal(function, List.of(argument)));
	}

	private static LispVal list(List<LispVal> items) {
		LispVal list = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			list = new LispCons(items.get(i), list);
		}
		return list;
	}

	private static LispVal list(LispVal... items) {
		return list(List.of(items));
	}

}
