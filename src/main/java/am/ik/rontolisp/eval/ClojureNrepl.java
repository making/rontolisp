package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.clojure.ClojureCompletion;
import am.ik.rontolisp.clojure.ClojureSession;
import am.ik.rontolisp.clojure.ClojureTopLevel;
import am.ik.rontolisp.reader.LispReadException;

/**
 * The Clojure half of an nREPL server ({@code cli/NreplServer}): one lowering and one
 * evaluator every session of the server shares -- the oracle's one runtime, where what
 * one session defines another calls -- and per session the variables a REPL thread binds
 * ({@code *ns*}, {@code *file*}, {@code *1}, {@code *2}, {@code *3}, {@code *e} and the
 * flags {@code clojure.main} binds), which each of its messages runs under, on whichever
 * thread runs it, with {@code *out*} and {@code *err*} writing to the message's own
 * writers. One session's evaluation runs beside another's.
 */
public final class ClojureNrepl {

	private static final String STACK_OVERFLOW = "stack overflow (--stack <MiB> raises the limit)";

	private static final String COMPILER_EXCEPTION = "clojure.lang.Compiler$CompilerException";

	private static final LispSymbol LOWER_AT_RUN_TIME = new LispSymbol("RONTOLISP::%CLOJURE-LOWER-AT-RUN-TIME");

	private static final LispSymbol NS_OBJECT = new LispSymbol("RONTOLISP::%CLOJURE-NS-OBJECT");

	private final LispEvaluator evaluator;

	private final SourceSession source;

	private final ClojureSession clojure;

	private final String nsVariable;

	private final String fileVariable;

	private final List<String> history;

	private final Map<String, LispVal> roots = new LinkedHashMap<>();

	private final String clojureVersion;

	/**
	 * The Clojure half of a server over an evaluator, whose run-time lowering it becomes.
	 * @param evaluator the evaluator every session runs on
	 * @param standards what the code is read against
	 * @param loader where a {@code require} and {@code load-file} read files, or null
	 */
	public ClojureNrepl(LispEvaluator evaluator, SourceStandards standards, @Nullable SourceLoader loader) {
		this.evaluator = evaluator;
		this.source = new SourceSession(SourceLanguage.CLOJURE, standards, loader);
		this.clojure = Objects.requireNonNull(this.source.clojureSession());
		this.source.attach(evaluator);
		this.evaluator.setSourceStandards(standards);
		this.nsVariable = ClojureSession.specialVariable("*ns*");
		this.fileVariable = ClojureSession.specialVariable("*file*");
		this.history = List.of(ClojureSession.specialVariable("*1"), ClojureSession.specialVariable("*2"),
				ClojureSession.specialVariable("*3"));
		List<String> threadBound = this.clojure.threadBoundSpecials();
		// defines them at the root, with the runtimes a first buffer carries
		for (ClojureTopLevel top : this.clojure.read("", null, "user")) {
			for (LispVal form : top.forms()) {
				this.evaluator.eval(form);
			}
		}
		for (String name : threadBound) {
			this.roots.put(name, this.evaluator.eval(new LispSymbol(name)));
		}
		LispVal version = lowerAtRunTime("(clojure.core/str (:major clojure.core/*clojure-version*) \".\""
				+ " (:minor clojure.core/*clojure-version*) \".\" (:incremental clojure.core/*clojure-version*))");
		this.clojureVersion = version instanceof LispString text ? text.value() : "1.12.6";
	}

	/**
	 * The variables one session's thread binds, between its messages.
	 */
	public final class Bindings {

		private final Map<String, LispVal> values;

		private Bindings(Map<String, LispVal> values) {
			this.values = new LinkedHashMap<>(values);
		}

		/**
		 * A copy, which a message runs under and its session adopts once it ends -- an
		 * interrupted one's never.
		 * @return the copy
		 */
		public Bindings copy() {
			return new Bindings(this.values);
		}

		/**
		 * The namespace the session's {@code *ns*} holds.
		 * @return its name
		 */
		public String namespace() {
			return namespaceName(this.values.get(ClojureNrepl.this.nsVariable));
		}

	}

	/**
	 * A fresh session's variables: {@code *ns*} {@code user}, an empty history, the
	 * flags' roots.
	 * @return the bindings
	 */
	public Bindings newBindings() {
		return new Bindings(this.roots);
	}

	/**
	 * What a message's evaluation answers, in order.
	 */
	public interface Reply {

		/**
		 * A form's value.
		 * @param printed the value as {@code pr} prints it
		 * @param namespace the namespace {@code *ns*} holds once the form ran, or null
		 * for a file's load, which answers none
		 */
		void value(String printed, @Nullable String namespace);

		/**
		 * A failure, whose report the REPL prints is already on {@code *err*}.
		 * @param exClass the class of the throwable, as {@code (str (class e))} spells it
		 * @param rootExClass the class of its root cause, likewise
		 */
		void error(String exClass, String rootExClass);

	}

	/**
	 * Evaluates an {@code eval} message's code on the calling thread, the session's
	 * variables bound around it and {@code *out*}/{@code *err*} writing to the writers:
	 * each form's value answers with the namespace then current, and a form that fails
	 * prints the REPL's report to {@code *err*} and answers its classes, the forms after
	 * it running still, like the oracle's. A text that stops reading runs the forms ahead
	 * of the failure, then reports it; a lowering's refusal stops the message.
	 * @param bindings the session's variables, updated with their values at the end
	 * @param code the code
	 * @param namespace the namespace to evaluate in for this message only, which must
	 * exist ({@link #knowsNamespace}), or null for the session's
	 * @param file what {@code *file*} names for this message, or null
	 * @param out where {@code *out*} writes
	 * @param err where {@code *err*} writes
	 * @param reply what the evaluation answers
	 */
	public void eval(Bindings bindings, String code, @Nullable String namespace, @Nullable String file, Writer out,
			Writer err, Reply reply) {
		Map<String, LispVal> message = new HashMap<>();
		if (namespace != null) {
			message.put(this.nsVariable, namespaceObject(namespace));
		}
		if (file != null) {
			message.put(this.fileVariable, LispString.literal(file));
		}
		run(bindings, message, out, err, () -> {
			Source text = new Source(code, null, null);
			List<ClojureTopLevel> tops = lower(text, err, reply);
			if (tops == null) {
				return;
			}
			for (ClojureTopLevel top : tops) {
				Ran ran = run(top);
				if (ran.failure() != null) {
					fail(ran.failure(), text, top, false, out, err, reply);
				}
				else if (top.echoes()) {
					flush(out, err);
					reply.value(echo(ran.value()), this.evaluator.currentClojureNamespace());
				}
			}
			unreadable(text, out, err, reply);
		});
	}

	/**
	 * Loads a {@code load-file} message's file contents on the calling thread like the
	 * oracle's {@code Compiler/load}: the session's variables bound around it,
	 * {@code *ns*} too, so the file's {@code ns} switches only the load, {@code *file*}
	 * naming the file; the load's value -- its last form's -- answers with no namespace
	 * and goes to the history as one value; the first form that fails, or the place the
	 * text stops reading, ends the load with its report.
	 * @param bindings the session's variables, updated with their values at the end
	 * @param contents the file's contents
	 * @param path the file's path below its source root ({@code *file*}), or null
	 * @param name the file's name, which a failure's report names, or null
	 * @param out where {@code *out*} writes
	 * @param err where {@code *err*} writes
	 * @param reply what the load answers
	 */
	public void loadFile(Bindings bindings, String contents, @Nullable String path, @Nullable String name, Writer out,
			Writer err, Reply reply) {
		Map<String, LispVal> message = new HashMap<>();
		message.put(this.nsVariable, Objects.requireNonNull(bindings.values.get(this.nsVariable)));
		message.put(this.fileVariable, LispString.literal(path == null ? "NO_SOURCE_PATH" : path));
		List<LispVal> before = this.history.stream().map(bindings.values::get).toList();
		LispVal[] loaded = new LispVal[1];
		run(bindings, message, out, err, () -> {
			Source text = new Source(contents, path, name);
			List<ClojureTopLevel> tops = lower(text, err, reply);
			if (tops == null) {
				return;
			}
			LispVal last = LispNil.INSTANCE;
			for (ClojureTopLevel top : tops) {
				Ran ran = run(top);
				if (ran.failure() != null) {
					fail(ran.failure(), text, top, true, out, err, reply);
					return;
				}
				if (top.echoes()) {
					last = ran.value();
				}
			}
			if (!unreadable(text, out, err, reply)) {
				flush(out, err);
				reply.value(echo(last), null);
				loaded[0] = last;
			}
		});
		// one load is one value of the history, the oracle's one eval of Compiler/load
		for (int i = 0; i < this.history.size(); i++) {
			bindings.values.put(this.history.get(i), Objects.requireNonNull(before.get(i)));
		}
		if (loaded[0] != null) {
			bindings.values.put(this.history.get(2), Objects.requireNonNull(before.get(1)));
			bindings.values.put(this.history.get(1), Objects.requireNonNull(before.get(0)));
			bindings.values.put(this.history.get(0), loaded[0]);
		}
	}

	/**
	 * Whether a namespace exists ({@link ClojureSession#knowsNamespace}).
	 * @param name the namespace
	 * @return whether it exists
	 */
	public boolean knowsNamespace(String name) {
		return this.clojure.knowsNamespace(name);
	}

	/**
	 * One completion candidate ({@link ClojureCompletion}).
	 *
	 * @param candidate the completed text
	 * @param type {@code function}, {@code macro}, {@code var}, {@code namespace} or
	 * {@code special-form}
	 * @param ns the namespace a var's candidate lives in, or null
	 */
	public record Completion(String candidate, String type, @Nullable String ns) {
	}

	/**
	 * The completions of a prefix in a namespace ({@link ClojureCompletion}).
	 * @param prefix the prefix
	 * @param namespace the namespace
	 * @return the candidates, sorted by their text
	 */
	public List<Completion> completions(String prefix, String namespace) {
		return this.clojure.completions(prefix, namespace)
			.stream()
			.map(completion -> new Completion(completion.candidate(), completion.type(), completion.ns()))
			.toList();
	}

	/**
	 * The Clojure version the front end answers to.
	 * @return the version, {@code 1.12.6}
	 */
	public String clojureVersion() {
		return this.clojureVersion;
	}

	/**
	 * A message's text, and how much of it reads.
	 *
	 * @param code the text
	 * @param path the file it is the contents of, which a reading or lowering failure
	 * names, or null
	 * @param name the file's name, which a run's failure names, or null
	 * @param unread where the text stops reading, or null when the whole of it reads
	 */
	private record Source(String code, @Nullable String path, @Nullable String name,
			ClojureSession.@Nullable ReadFailure unread) {

		Source(String code, @Nullable String path, @Nullable String name) {
			this(code, path, name, ClojureSession.readFailure(code, path));
		}

	}

	/**
	 * One entry once run: its value, or what it threw.
	 *
	 * @param value the value of its last form
	 * @param failure what it threw, or null
	 */
	private record Ran(LispVal value, @Nullable Throwable failure) {
	}

	/**
	 * Runs an entry's forms in order; a failure is kept, a stack overflow's once the
	 * control state is put back. An interrupt and an exit go on.
	 */
	private Ran run(ClojureTopLevel top) {
		LispEvaluator.ControlState before = this.evaluator.controlState();
		try {
			LispVal last = LispNil.INSTANCE;
			for (LispVal form : top.forms()) {
				last = this.evaluator.eval(form);
			}
			return new Ran(last, null);
		}
		catch (LispInterruptSignal | LispExitSignal signal) {
			throw signal;
		}
		catch (RuntimeException ex) {
			return new Ran(LispNil.INSTANCE, ex);
		}
		catch (StackOverflowError ex) {
			before.restore();
			return new Ran(LispNil.INSTANCE, ex);
		}
	}

	/**
	 * The entries of the part of a text that reads, lowered in the namespace {@code *ns*}
	 * holds; a lowering's refusal prints its report, answers its classes and answers
	 * null.
	 */
	private @Nullable List<ClojureTopLevel> lower(Source text, Writer err, Reply reply) {
		ClojureSession.ReadFailure unread = text.unread();
		String readable = unread == null ? text.code() : text.code().substring(0, unread.readable());
		try {
			return this.clojure.read(readable, text.path(), this.evaluator.currentClojureNamespace());
		}
		catch (LispReadException ex) {
			SourceLocation where = ex.location();
			write(err,
					"Syntax error compiling at ("
							+ located(text.path(), where == null ? 1 : where.line(), where == null ? 1 : where.column())
							+ ").\n" + ex.reason() + "\n");
			reply.error("class " + COMPILER_EXCEPTION, "class " + COMPILER_EXCEPTION);
			return null;
		}
	}

	/**
	 * Reports where a text stops reading, once the forms ahead of it ran.
	 * @return whether it stops before its end
	 */
	private boolean unreadable(Source text, Writer out, Writer err, Reply reply) {
		ClojureSession.ReadFailure unread = text.unread();
		if (unread == null) {
			return false;
		}
		LispReadException failure = unread.failure();
		SourceLocation where = failure.location();
		if (where == null) {
			where = SourceLocation.at(null, unread.readable(), text.code());
		}
		write(err, "Syntax error reading source at (" + located(text.path(), where.line(), where.column()) + ").\n"
				+ failure.reason() + "\n");
		flush(out, err);
		reply.error(text.path() == null ? "class clojure.lang.ExceptionInfo" : "class " + COMPILER_EXCEPTION,
				text.path() == null ? "class java.lang.RuntimeException" : "class " + COMPILER_EXCEPTION);
		return true;
	}

	/**
	 * Reports a failed form: the oracle's REPL report of the condition {@code *e} holds,
	 * located at the form, on {@code *err*}, then its classes -- a file's load the
	 * oracle's {@code Compiler$CompilerException} wrapping it.
	 */
	private void fail(Throwable failure, Source text, ClojureTopLevel top, boolean load, Writer out, Writer err,
			Reply reply) {
		int line = top.location() == null ? 1 : Objects.requireNonNull(top.location()).line();
		String exClass;
		String rootClass;
		String report;
		if (failure instanceof StackOverflowError) {
			exClass = "java.lang.StackOverflowError";
			rootClass = exClass;
			report = "Execution error (StackOverflowError) at (" + located(text.name(), line, 0) + ").\n"
					+ STACK_OVERFLOW + "\n";
		}
		else {
			String described;
			try {
				described = lowerAtRunTime(describing(line, text.name())) instanceof LispString string ? string.value()
						: "";
			}
			catch (RuntimeException ex) {
				described = "";
			}
			String[] parts = described.split("\n", 3);
			if (parts.length == 3) {
				exClass = parts[0];
				rootClass = parts[1];
				report = parts[2];
			}
			else {
				exClass = "java.lang.RuntimeException";
				rootClass = exClass;
				report = "Execution error at (" + located(text.name(), line, 0) + ").\n" + failure.getMessage() + "\n";
			}
		}
		if (load) {
			exClass = COMPILER_EXCEPTION;
			rootClass = COMPILER_EXCEPTION;
		}
		write(err, report);
		flush(out, err);
		reply.error("class " + exClass, "class " + rootClass);
	}

	/**
	 * The Clojure code describing the condition {@code *e} holds, one per line: its
	 * class, its root cause's, then the report the oracle's REPL prints of it, at the
	 * line of the form that failed, in the file of that name ({@code REPL} without one)
	 * where the condition names no place of its own.
	 */
	private static String describing(int line, @Nullable String name) {
		String place = ":clojure.error/line " + line
				+ (name == null ? "" : " :clojure.error/source " + LispString.literal(name).print());
		return "(clojure.core/str (clojure.core/name (clojure.core/class *e)) \"\\n\""
				+ " (clojure.core/name (clojure.core/class (clojure.main/root-cause *e))) \"\\n\""
				+ " (clojure.main/ex-str (clojure.core/merge {" + place + "}"
				+ " (clojure.main/ex-triage (clojure.core/Throwable->map *e)))))";
	}

	/** A report's place: the file, {@code REPL} without one, the line, the column. */
	private static String located(@Nullable String file, int line, int column) {
		return (file == null ? "REPL" : file) + ":" + line + (column > 0 ? ":" + column : "");
	}

	/**
	 * Runs a message's body under the session's variables and the message's own --
	 * {@code *out*}, {@code *err*} and the rest the message binds, which end with it.
	 */
	private void run(Bindings bindings, Map<String, LispVal> message, Writer out, Writer err, Runnable body) {
		LispVal outStream = this.evaluator.openJavaStream(out);
		LispVal errStream = this.evaluator.openJavaStream(err);
		Map<String, LispVal> own = new HashMap<>(message);
		own.put(LispNames.STANDARD_OUTPUT_VAR, outStream);
		own.put(LispNames.ERROR_OUTPUT_VAR, errStream);
		try {
			this.evaluator.withThreadBindings(bindings.values, () -> this.evaluator.withThreadBindings(own, body));
		}
		finally {
			flush(out, err);
			this.evaluator.closeJavaStream(outStream);
			this.evaluator.closeJavaStream(errStream);
		}
	}

	/**
	 * Lowers Clojure code as the program runs, in the namespace {@code *ns*} holds, and
	 * evaluates it: no history records it.
	 */
	private LispVal lowerAtRunTime(String code) {
		return this.evaluator.eval(list(LOWER_AT_RUN_TIME, LispString.literal("load-string"),
				list(new LispSymbol(LispNames.LIST), LispString.literal(code))));
	}

	private LispVal namespaceObject(String name) {
		return this.evaluator.eval(list(NS_OBJECT, LispString.literal(name)));
	}

	private String echo(LispVal value) {
		String printed = this.source.echo(value, this.evaluator);
		return printed == null ? "nil" : printed;
	}

	/**
	 * The name of a namespace object {@code (:C%NS-OBJECT "name")}, {@code user} else.
	 */
	private static String namespaceName(@Nullable LispVal ns) {
		if (ns instanceof LispCons cons && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispString name) {
			return name.value();
		}
		return "user";
	}

	private static void write(Writer writer, String text) {
		try {
			writer.write(text);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void flush(Writer out, Writer err) {
		try {
			err.flush();
			out.flush();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static LispVal list(LispVal... items) {
		LispVal list = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			list = new LispCons(items[i], list);
		}
		return list;
	}

}
