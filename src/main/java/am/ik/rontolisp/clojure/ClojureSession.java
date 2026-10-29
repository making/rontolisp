package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * An interactive Clojure session: the global scope a whole-file lowering rebuilds per
 * file, kept across the buffers a REPL reads one at a time. What a session lowers
 * differently from a file is nothing but the scope lifetime: every buffer declares its
 * own top-level {@code def}/{@code defn} names into the session's globals first, so a
 * later buffer may call what an earlier one defined.
 */
public final class ClojureSession {

	private final ClojureLowering lowering = new ClojureLowering();

	private final ClojureJavaClasses javaClasses;

	private final ClojureRunTime runTime;

	/** A session that reads no files: a project {@code require} is refused by name. */
	public ClojureSession() {
		this(ClojureFiles.NONE);
	}

	/**
	 * A session whose {@code require}s load project namespaces through the files, from
	 * the source path of the working directory.
	 * @param files where the namespace files are read from
	 */
	public ClojureSession(ClojureFiles files) {
		this.lowering.sourcePath = new ClojureSourcePath(files, null);
		this.lowering.files = files;
		this.javaClasses = files.javaClasses();
		this.runTime = new ClojureRunTime(this.lowering, this.javaClasses);
		this.lowering.referReplRequires();
	}

	/**
	 * Reads and lowers one buffer. A reader conditional reads, as the oracle's REPL reads
	 * with {@code {:read-cond :allow}}.
	 * @param source the typed text: any number of datums
	 * @return one entry per top-level datum, in order
	 */
	public List<ClojureTopLevel> read(String source) {
		// one lowering at a time: a thread an earlier input started may hand the
		// lowering a verb as it runs (ClojureRunTime)
		synchronized (this.lowering) {
			return ClojureHostClasses.lowering(this.javaClasses,
					() -> this.lowering.interact(new ClojureReader(source, null, true), false));
		}
	}

	/**
	 * Reads and lowers one buffer in a namespace, the way an nREPL session's message
	 * reads: the session's {@code *ns*} is its own, so the buffer lowers in the namespace
	 * the caller names (what the buffer switches to stays the lowering's until the next
	 * read names its own), and every datum's entry echoes, an {@code ns} datum's nil too,
	 * as the oracle answers one value per form.
	 * @param source the text: any number of datums
	 * @param file the file the text is the contents of, for a refusal's position, or null
	 * @param namespace the namespace the buffer lowers in
	 * @return one entry per top-level datum, in order, runtimes ahead
	 */
	public List<ClojureTopLevel> read(String source, @Nullable String file, String namespace) {
		synchronized (this.lowering) {
			return ClojureHostClasses.lowering(this.javaClasses, () -> {
				this.lowering.currentNs = namespace;
				return this.lowering.interact(new ClojureReader(source, file, true), true);
			});
		}
	}

	/**
	 * What reading a text fails with, before anything lowers: a datum the reader refuses
	 * or one the text ends inside. The oracle's nREPL runs the forms ahead of it, then
	 * reports it as a reading failure (a lowering's refusal is a compiling one).
	 *
	 * @param failure the failure
	 * @param readable how much of the text reads: the offset past its last datum ahead of
	 * the failure
	 */
	public record ReadFailure(LispReadException failure, int readable) {
	}

	/**
	 * What reading a text fails with ({@link ReadFailure}).
	 * @param source the text
	 * @param file the file the text is the contents of, or null
	 * @return the failure, or null when the whole text reads
	 */
	public static @Nullable ReadFailure readFailure(String source, @Nullable String file) {
		ClojureReader reader = new ClojureReader(source, file, true);
		int readable = 0;
		try {
			while (reader.readTopLevel() != null) {
				readable = reader.position();
			}
			return null;
		}
		catch (LispReadException ex) {
			return new ReadFailure(ex, readable);
		}
	}

	/**
	 * The special variables a REPL thread binds for its session -- {@code *ns*},
	 * {@code *file*}, the history ({@code *1}, {@code *2}, {@code *3}, {@code *e}) and
	 * the flags {@code clojure.main} binds ({@code *print-length*},
	 * {@code *warn-on-reflection*} ...) -- each as the name of the variable it lowers to:
	 * what each session of an nREPL server keeps between its messages, like the oracle's.
	 * The next buffer read defines the ones no buffer defined yet.
	 * @return the variables' names
	 */
	public List<String> threadBoundSpecials() {
		synchronized (this.lowering) {
			List<String> names = new ArrayList<>();
			for (String name : ClojureCoreSpecials.replThreadBound()) {
				this.lowering.usedSpecials.add(name);
				names.add(ClojureCoreSpecials.required(name).symbol().name());
			}
			return names;
		}
	}

	/**
	 * The name of the variable a {@code clojure.core} special lowers to.
	 * @param name the special's name ({@code *ns*}, {@code *file*} ...)
	 * @return the variable's name
	 */
	public static String specialVariable(String name) {
		return ClojureCoreSpecials.required(name).symbol().name();
	}

	/**
	 * Whether a namespace exists where the session lowers: one an {@code ns} or
	 * {@code in-ns} created, a library required, a namespace loaded at startup -- what
	 * the oracle's {@code find-ns} finds.
	 * @param name the namespace
	 * @return whether it exists
	 */
	public boolean knowsNamespace(String name) {
		synchronized (this.lowering) {
			return this.lowering.knownNamespaceNames().contains(name);
		}
	}

	/**
	 * The completions of a prefix typed in a namespace ({@link ClojureCompletion}).
	 * @param prefix the prefix
	 * @param namespace the namespace
	 * @return the candidates, sorted by their text
	 */
	public List<ClojureCompletion> completions(String prefix, String namespace) {
		synchronized (this.lowering) {
			return ClojureCompletion.of(this.lowering, prefix, namespace);
		}
	}

	/**
	 * The session's lowering as the inputs run: what {@code eval}, a computed
	 * {@code resolve} or {@code require} and the rest of the lowering verbs over computed
	 * arguments hand it lowers like one more input, in the namespace {@code *ns*} holds.
	 * @return the run-time half of the session
	 */
	public ClojureRunTime runTime() {
		return this.runTime;
	}

	/**
	 * Who evaluates one macro application in the macro-time environment, kept across the
	 * buffers a session reads one at a time, so a macro defined in one buffer expands in
	 * a later one.
	 * @param macroEvaluator the evaluator, or null for none
	 */
	public void setMacroEvaluator(@Nullable ClojureMacroEvaluator macroEvaluator) {
		this.lowering.setMacroEvaluator(macroEvaluator);
	}

	/**
	 * The host boundary {@code rontolisp.wasm} and {@code rontolisp.wit} lower against,
	 * kept across the session's buffers.
	 * @param boundary the boundary
	 */
	public void setBoundary(ClojureBoundary boundary) {
		this.lowering.boundary = boundary;
	}

	/**
	 * Whether the text is a complete datum sequence, as opposed to one the reader ran out
	 * of input in the middle of: an open list, vector or map, a string, or a dispatch
	 * prefix still waiting for its datum. Text that is complete but WRONG answers
	 * {@code true}: it must reach {@link #read} to be reported.
	 * @param source the typed text so far
	 * @return {@code false} when more input is needed
	 */
	public static boolean isComplete(String source) {
		int depth = 0;
		boolean inString = false;
		boolean inComment = false;
		for (int i = 0; i < source.length(); i++) {
			char c = source.charAt(i);
			if (inComment) {
				if (c == '\n') {
					inComment = false;
				}
				continue;
			}
			if (inString) {
				if (c == '\\' && i + 1 < source.length()) {
					i++;
				}
				else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '\\') {
				// a character literal's first character, whatever it is: \( is no
				// bracket, \; no comment, \" no string
				i++;
			}
			else if (c == ';') {
				inComment = true;
			}
			else if (c == '"') {
				inString = true;
			}
			else if (c == '(' || c == '[' || c == '{') {
				depth++;
			}
			else if (c == ')' || c == ']' || c == '}') {
				depth--;
			}
		}
		if (inString) {
			return false;
		}
		if (depth > 0) {
			return false;
		}
		// A trailing dispatch prefix (`'`, `` ` ``, `~`, `~@`, `@`, `^`, `#'`, `#_`,
		// `#(`) still waits for its datum, and so does a trailing discard (the oracle's
		// REPL reads on past it).
		try {
			ClojureReader reader = new ClojureReader(source, null, true);
			reader.readAll();
			return !reader.endsInDiscard();
		}
		catch (LispReadException ex) {
			String message = ex.getMessage();
			return message == null || (!message.contains("unexpected end of input")
					&& !message.contains("unclosed form") && !message.contains("unterminated")
					&& !message.contains("truncated") && !message.contains("expected a form"));
		}
	}

}
