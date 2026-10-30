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
import java.util.Comparator;
import java.util.HashMap;
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

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.compiler.CallSiteWalk;
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
public final class CommonLispSwank {

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

	/**
	 * The Common Lisp half of a server over an evaluator.
	 * @param evaluator the evaluator every request runs on
	 */
	public CommonLispSwank(LispEvaluator evaluator) {
		this.evaluator = evaluator;
		this.resolver = evaluator.packageResolver();
		for (String name : REPL_VARIABLES) {
			if (!evaluator.globalEnvironment().hasBinding(name)) {
				evaluator.globalEnvironment().define(name, LispNil.INSTANCE);
			}
		}
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
		List<T> result = new ArrayList<>(1);
		try {
			this.evaluator.withThreadBindings(streams, () -> result.add(body.get()));
		}
		finally {
			this.evaluator.closeJavaStream(outStream);
			this.evaluator.closeJavaStream(inStream);
			this.resolver.unbindThreadPackage(previous);
		}
		return result.getFirst();
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
		boolean markers = SourceLanguage.usesReadEvalMarkers(code);
		List<LispVal> forms = read(code);
		List<LispVal> values = List.of();
		LispVal last = LispNil.INSTANCE;
		for (LispVal form : forms) {
			last = form;
			values = this.evaluator.evalValues(markers ? this.evaluator.resolveReadTimeEvalInCode(form) : form);
		}
		return new Region(values, last);
	}

	private List<LispVal> read(String code) {
		return SourceLanguage.COMMON_LISP.read(code, this.evaluator.features(), null, this.evaluator.sourceStandards());
	}

	/** The first form of a text, as SWANK's {@code from-string} reads it. */
	private LispVal readFirst(String code) {
		List<LispVal> forms = read(code);
		if (forms.isEmpty()) {
			throw new LispEvalException("end of file on " + LispString.literal(code).print());
		}
		return forms.getFirst();
	}

	private List<LispVal> evalFirstValues(String code) {
		LispVal form = readFirst(code);
		return this.evaluator.evalValues(
				SourceLanguage.usesReadEvalMarkers(code) ? this.evaluator.resolveReadTimeEvalInCode(form) : form);
	}

	/**
	 * Evaluates a REPL input as SWANK's {@code listener-eval}: every form in turn, the
	 * history variables ({@code *} {@code **} {@code ***}, {@code /} {@code //}
	 * {@code ///}, {@code +} {@code ++} {@code +++}) set from the last.
	 * @param code the input
	 * @return the last form's values, as {@code prin1} prints them
	 */
	public List<String> listenerEval(String code) {
		Region region = evalRegion(code);
		Environment global = this.evaluator.globalEnvironment();
		LispVal primary = region.values().isEmpty() ? LispNil.INSTANCE : region.values().getFirst();
		shift(global, "***", "**", "*", primary);
		shift(global, "///", "//", "/", list(region.values()));
		shift(global, "+++", "++", "+", region.last());
		return region.values().stream().map(this::print).toList();
	}

	private static void shift(Environment global, String third, String second, String first, LispVal value) {
		global.define(third, Objects.requireNonNull(global.lookupOrNull(second)));
		global.define(second, Objects.requireNonNull(global.lookupOrNull(first)));
		global.define(first, value);
	}

	/**
	 * Evaluates the first form of a text as SWANK's {@code interactive-eval}, answering
	 * its values for the echo area.
	 * @param code the text
	 * @param lines the echo area's lines
	 * @param width its width
	 * @return the values, as SWANK formats them
	 */
	public String interactiveEval(String code, int lines, int width) {
		return echoArea(evalFirstValues(code), lines, width);
	}

	/**
	 * Evaluates every form of a text as SWANK's {@code interactive-eval-region}.
	 * @param code the text
	 * @param lines the echo area's lines
	 * @param width its width
	 * @return the last form's values, as SWANK formats them
	 */
	public String interactiveEvalRegion(String code, int lines, int width) {
		return echoArea(evalRegion(code).values(), lines, width);
	}

	/**
	 * Evaluates the first form of a text with its standard output captured, as SWANK's
	 * {@code eval-and-grab-output}.
	 * @param code the text
	 * @return the output, then the values one per line
	 */
	public List<String> evalAndGrabOutput(String code) {
		StringWriter output = new StringWriter();
		List<LispVal> values = capturing(output, () -> evalFirstValues(code));
		return List.of(output.toString(), String.join("\n", values.stream().map(this::print).toList()));
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
		LispVal form = readFirst(code);
		if (!(form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.DEFVAR.equals(head.name().toUpperCase(Locale.ROOT)) && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol)) {
			throw new LispEvalException("Not a DEFVAR form: " + code);
		}
		// a defvar of a variable made unbound first assigns its value: a defparameter
		return print(this.evaluator.eval(new LispCons(new LispSymbol("DEFPARAMETER"), cons.cdr())));
	}

	/**
	 * Loads a file, as SWANK's {@code load-file}.
	 * @param filename the file
	 * @return what {@code load} answered, as {@code prin1} prints it
	 */
	public String loadFile(String filename) {
		return print(this.evaluator.eval(list(new LispSymbol(LispNames.LOAD), LispString.literal(filename))));
	}

	/**
	 * Values as SWANK's {@code format-values-for-echo-area} formats them: {@code => }
	 * then the values, an integer with its bits and radixes, a ratio with its float,
	 * truncated to the echo area.
	 */
	private String echoArea(List<LispVal> values, int maxLines, int width) {
		String output;
		if (values.isEmpty()) {
			// SWANK's cond answers "; No value" without writing it
			output = "";
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
		return truncateForEchoArea(output, maxLines, width);
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
	public record Note(String message, String severity, @Nullable Location location) {
	}

	/** Where a note points. */
	public sealed interface Location {

		/**
		 * A place in an editor buffer.
		 *
		 * @param buffer the buffer's name
		 * @param start the buffer position the compiled text starts at
		 * @param offset the offset within the compiled text
		 */
		record Buffer(String buffer, int start, int offset) implements Location {
		}

		/**
		 * A place in a file.
		 *
		 * @param file the file
		 * @param position the 1-based character position
		 */
		record File(String file, int position) implements Location {
		}

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
		return compile(code, offset -> new Location.Buffer(buffer, start, offset), true);
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
		return compile(code, offset -> new Location.File(filename, offset + 1), load);
	}

	private Compilation compile(String code, java.util.function.IntFunction<Location> place, boolean run) {
		long started = System.nanoTime();
		List<Note> notes = new ArrayList<>();
		LispReader.ReadPrefix read = SourceLanguage.COMMON_LISP.readUntilError(code, this.evaluator.features(), null,
				this.evaluator.sourceStandards(), null);
		List<Integer> offsets = topLevelOffsets(code);
		boolean aligned = offsets.size() >= read.forms().size();
		boolean markers = SourceLanguage.usesReadEvalMarkers(code);
		boolean success = true;
		List<LispVal> resolved = new ArrayList<>();
		for (int i = 0; run && i < read.forms().size(); i++) {
			int offset = aligned ? offsets.get(i) : 0;
			LispVal form = read.forms().get(i);
			try {
				this.evaluator.reportingWarningsTo(
						(text, style) -> notes
							.add(new Note(text, style ? "style-warning" : "warning", place.apply(offset))),
						() -> this.evaluator.evalTopLevel(
								markers ? this.evaluator.resolveReadTimeEvalInCode(form) : form, resolved::add));
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
		if (success) {
			for (String name : undefinedFunctions(resolved)) {
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

	/**
	 * Where each top-level datum of a text starts: whitespace, comments, strings and
	 * character literals skipped, a quote or other prefix belonging to the datum it
	 * prefixes. A top-level reader conditional answers no offsets, since whether its
	 * datum reads depends on the features: the caller then places every note at the
	 * start.
	 */
	static List<Integer> topLevelOffsets(String code) {
		List<Integer> offsets = new ArrayList<>();
		int depth = 0;
		boolean prefixed = false;
		int i = 0;
		int n = code.length();
		while (i < n) {
			char c = code.charAt(i);
			if (c == ';') {
				while (i < n && code.charAt(i) != '\n') {
					i++;
				}
				continue;
			}
			if (c == '#' && i + 1 < n && code.charAt(i + 1) == '|') {
				int nesting = 1;
				i += 2;
				while (i < n && nesting > 0) {
					if (code.startsWith("|#", i)) {
						nesting--;
						i += 2;
					}
					else if (code.startsWith("#|", i)) {
						nesting++;
						i += 2;
					}
					else {
						i++;
					}
				}
				continue;
			}
			if (Character.isWhitespace(c)) {
				i++;
				continue;
			}
			if (c == ')') {
				depth = Math.max(0, depth - 1);
				i++;
				continue;
			}
			if (depth == 0 && !prefixed) {
				offsets.add(i);
			}
			prefixed = false;
			if (c == '\'' || c == '`' || c == ',') {
				prefixed = true;
				i += c == ',' && i + 1 < n && code.charAt(i + 1) == '@' ? 2 : 1;
				continue;
			}
			if (c == '#' && i + 1 < n) {
				char dispatch = code.charAt(i + 1);
				if ((dispatch == '+' || dispatch == '-') && depth == 0) {
					return List.of();
				}
				if (dispatch == '\\') {
					i += 3;
					while (i < n && !delimiter(code.charAt(i))) {
						i++;
					}
					continue;
				}
				// #'f, #(...), #.(...), #:g, #x1F ...: the datum after the dispatch
				prefixed = true;
				i += dispatch == '(' ? 1 : 2;
				continue;
			}
			if (c == '(') {
				depth++;
				i++;
				continue;
			}
			if (c == '"') {
				i++;
				while (i < n && code.charAt(i) != '"') {
					i += code.charAt(i) == '\\' ? 2 : 1;
				}
				i++;
				continue;
			}
			while (i < n && !delimiter(code.charAt(i))) {
				if (code.charAt(i) == '|') {
					i++;
					while (i < n && code.charAt(i) != '|') {
						i++;
					}
				}
				i += i < n && code.charAt(i) == '\\' ? 2 : 1;
			}
		}
		return offsets;
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

	/**
	 * The functions resolved forms call that nothing defines once they ran, in the order
	 * first called: a call is a list in a code position ({@link CallSiteWalk}) headed by
	 * a symbol that names no function, macro or special operator and binds no local
	 * function; a {@code #'name} counts too. A built-in macro whose arguments the walk
	 * does not know is not looked into, nor is a user macro's call, whose expander the
	 * walk would run a second time.
	 */
	private List<String> undefinedFunctions(List<LispVal> forms) {
		Set<String> found = new LinkedHashSet<>();
		Undefined walk = new Undefined(found, Set.of());
		for (LispVal form : forms) {
			CallSiteWalk.walk(form, walk);
		}
		return List.copyOf(found);
	}

	/** The macros whose every argument is a form, which the walk may look into. */
	private static final Set<String> FORM_MACROS = Set.of(LispNames.WHEN, LispNames.UNLESS, LispNames.AND, LispNames.OR,
			LispNames.PROG1, LispNames.PROG2, "RETURN", LispNames.DEFVAR, "DEFPARAMETER", "DEFCONSTANT",
			LispNames.IGNORE_ERRORS, LispNames.TIME, LispNames.LOCALLY, LispNames.MULTIPLE_VALUE_LIST,
			LispNames.MULTIPLE_VALUE_CALL, LispNames.NTH_VALUE, LispNames.MULTIPLE_VALUE_PROG1,
			LispNames.WITH_STANDARD_IO_SYNTAX, LispNames.PSETQ, LispNames.ERROR, LispNames.CERROR, LispNames.WARN,
			LispNames.SIGNAL, LispNames.FORMAT, LispNames.MAKE_INSTANCE, LispNames.SLOT_VALUE, LispNames.TYPEP,
			LispNames.COMPLEMENT, LispNames.MAKE_CONDITION, LispNames.EVAL_WHEN);

	/**
	 * The built-in operators whose shape {@link CallSiteWalk} knows, or whose arguments
	 * are all forms: the walk looks into these and no other built-in operator.
	 */
	private static final Set<String> WALKED_OPERATORS = walkedOperators();

	private static Set<String> walkedOperators() {
		Set<String> names = new java.util.HashSet<>(FORM_MACROS);
		names.addAll(List.of(LispNames.IF, LispNames.PROGN, LispNames.SETQ, LispNames.BLOCK, LispNames.RETURN_FROM,
				LispNames.CATCH, LispNames.THROW, LispNames.UNWIND_PROTECT, "TAGBODY", "PROGV",
				LispNames.LOAD_TIME_VALUE, LispNames.QUOTE, LispNames.DEFMACRO, LispNames.DECLARE, LispNames.DEFUN,
				LispNames.LAMBDA, LispNames.DEFMETHOD, LispNames.LET, LispNames.LET_STAR, LispNames.PROG,
				LispNames.PROG_STAR, LispNames.SYMBOL_MACROLET, LispNames.HANDLER_BIND, LispNames.RESTART_BIND,
				LispNames.DO, LispNames.DO_STAR, LispNames.DOLIST, LispNames.DOTIMES, LispNames.DO_SYMBOLS,
				LispNames.DO_EXTERNAL_SYMBOLS, LispNames.DO_ALL_SYMBOLS, LispNames.WITH_OPEN_FILE,
				LispNames.WITH_OPEN_STREAM, LispNames.WITH_OUTPUT_TO_STRING, LispNames.WITH_INPUT_FROM_STRING,
				LispNames.MULTIPLE_VALUE_BIND, LispNames.MULTIPLE_VALUE_SETQ, LispNames.DESTRUCTURING_BIND,
				LispNames.WITH_SLOTS, LispNames.WITH_ACCESSORS, LispNames.THE, LispNames.CASE, LispNames.ECASE,
				LispNames.CCASE, LispNames.TYPECASE, LispNames.ETYPECASE, LispNames.CTYPECASE, LispNames.HANDLER_CASE,
				LispNames.RESTART_CASE, LispNames.COND, LispNames.SETF, LispNames.PSETF, LispNames.INCF, LispNames.DECF,
				LispNames.POP, LispNames.REMF, LispNames.PUSH, LispNames.PUSHNEW, LispNames.ROTATEF, LispNames.SHIFTF,
				LispNames.DEFCLASS, LispNames.DEFINE_CONDITION, LispNames.DEFSTRUCT, LispNames.DEFGENERIC,
				LispNames.LOOP));
		return Set.copyOf(names);
	}

	/** The walk {@link #undefinedFunctions} takes, under the local functions in scope. */
	private final class Undefined implements CallSiteWalk.Rewriter {

		private final Set<String> found;

		private final Set<String> local;

		Undefined(Set<String> found, Set<String> local) {
			this.found = found;
			this.local = local;
		}

		@Override
		public @Nullable LispVal call(LispCons form, LispSymbol head) {
			String name = head.name();
			if (name.startsWith(":") || this.local.contains(name)) {
				return null;
			}
			String member = LispSymbol.memberName(name).toUpperCase(Locale.ROOT);
			boolean builtin = PackageRegistry.specialOperatorNames().contains(name);
			if (builtin && LispNames.FUNCTION.equals(member)) {
				if (form.cdr() instanceof LispCons arg && arg.car() instanceof LispSymbol fn) {
					check(fn.name());
				}
				return null;
			}
			if (builtin && (LispNames.FLET.equals(member) || LispNames.LABELS.equals(member)
					|| LispNames.MACROLET.equals(member))) {
				return localFunctions(form, !LispNames.FLET.equals(member));
			}
			if (CommonLispSwank.this.evaluator.isUserMacro(name)) {
				// expanding it again would run its expander a second time
				return form;
			}
			if (builtin) {
				return WALKED_OPERATORS.contains(member) ? null : form;
			}
			check(name);
			return null;
		}

		private LispVal localFunctions(LispCons form, boolean recursive) {
			Set<String> names = new LinkedHashSet<>(this.local);
			if (form.cdr() instanceof LispCons rest) {
				for (LispVal def = rest.car(); def instanceof LispCons cell; def = cell.cdr()) {
					if (cell.car() instanceof LispCons binding && binding.car() instanceof LispSymbol name) {
						names.add(name.name());
					}
				}
				Undefined inner = new Undefined(this.found, names);
				for (LispVal def = rest.car(); def instanceof LispCons cell; def = cell.cdr()) {
					if (cell.car() instanceof LispCons binding && binding.cdr() instanceof LispCons lambda) {
						CallSiteWalk.lambda(new LispCons(new LispSymbol(LispNames.LAMBDA), lambda),
								recursive ? inner : this);
					}
				}
				CallSiteWalk.arguments(rest.cdr(), inner);
			}
			return form;
		}

		private void check(String name) {
			if (name.startsWith(":") || this.local.contains(name) || PackageRegistry.isClSymbol(name)
					|| PackageRegistry.isClMemberName(name) || LispSymbol.memberName(name).startsWith("%")
					|| CommonLispSwank.this.evaluator.fbound(name)) {
				return;
			}
			this.found.add(name);
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
	 * name mapped to its canonical spelling: the registry's entries, and the image's
	 * definitions homed there, since a definition interns its name.
	 */
	private Map<String, String> symbolsIn(String pkg, boolean externalOnly) {
		Map<String, String> symbols = new TreeMap<>();
		if ("keyword".equals(pkg)) {
			return symbols;
		}
		try {
			for (PackageResolver.Accessible entry : this.resolver.accessibleEntries(pkg)) {
				if (!externalOnly || LispNames.STATUS_EXTERNAL.equals(entry.status())) {
					symbols.put(LispSymbol.memberName(entry.spelling()), entry.spelling());
				}
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
				if (clUser && !externalOnly && !spelling.startsWith(":") && !spelling.startsWith("#:")
						&& !PackageRegistry.isClMemberName(spelling)) {
					symbols.putIfAbsent(spelling, spelling);
				}
				continue;
			}
			String home = this.resolver.findPackageName(qn.pkg());
			if (home != null && packageName(home).equals(upper)) {
				boolean external = !spelling.contains("::");
				if (!externalOnly || external) {
					symbols.putIfAbsent(qn.member(), spelling);
				}
			}
		}
		return symbols;
	}

	/**
	 * A symbol's classification, as SWANK's {@code symbol-classification-string}: a
	 * letter per property -- bound, fbound, generic function, class, type, macro, special
	 * operator, package, accessor -- or a dash.
	 */
	private String classification(String spelling) {
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
	private @Nullable LispVal arglist(String spelling) {
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
	private static String arglistText(LispVal item, boolean upcase) {
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

	// ------------------------------------------------------------------------------------
	// Helpers

	/**
	 * A value as {@code prin1} prints it in the calling thread's package.
	 * @param value the value
	 * @return the text
	 */
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
		return this.evaluator.eval(form);
	}

	/**
	 * Sets the REPL's history variables to nil, as SWANK's {@code clear-repl-variables}.
	 */
	public void clearReplVariables() {
		for (String name : REPL_VARIABLES) {
			this.evaluator.globalEnvironment().define(name, LispNil.INSTANCE);
		}
	}

	/** Calls a one-argument lambda, read once in {@code cl-user}, on a value. */
	private LispVal helper(String source, LispVal argument) {
		LispVal function = this.helpers.computeIfAbsent(source, text -> {
			Object previous = this.resolver.bindThreadPackage(LispNames.CL_USER_PKG);
			try {
				return this.evaluator.eval(read(text).getFirst());
			}
			finally {
				this.resolver.unbindThreadPackage(previous);
			}
		});
		return this.evaluator.applyGlobal(function, List.of(argument));
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
