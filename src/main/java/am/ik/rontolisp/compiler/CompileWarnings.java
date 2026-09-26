package am.ik.rontolisp.compiler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * Where a backend emits a compile-time WARNING -- a diagnostic that is printed rather
 * than thrown, so it never reaches the compile boundary's failure decoration
 * ({@code RontoLispCli.locateCompileFailure}) -- and where the compile boundary learns
 * how many of them were about the program's own source, which is what
 * {@code --warnings-as-errors} fails the compile on.
 *
 * <p>
 * <b>Two kinds of line.</b> A {@link #warn warning} is about a form of the program: it is
 * placed at that form ({@code file:line:column: warning: ...}) and it may be counted. A
 * {@link #note} states what the build did or what the host now owes (the {@code :async t}
 * and {@code --host-fetch} obligations, a missing JDK): it is printed verbatim and never
 * counted, because there is nothing in the source to change.
 *
 * <p>
 * <b>Which warnings count</b> ({@link #startCounting}): those placed in the program's own
 * source -- a unit the source-language seam read
 * ({@link SourceProvenance#programSourceLocation}) -- and not in a file the boundary
 * calls a dependency's (a system a dist installed). A spliced library is read past the
 * seam, so its warnings are printed and never counted; a warning about a form a macro
 * built is placed, and judged, at the innermost located form around it
 * ({@link SourceProvenance#enterForm}).
 *
 * <p>
 * <b>Why this is not just {@code System.err.println}.</b> A backend may compile the same
 * program more than once and keep only the last result: {@code JvmLispCompiler} gates its
 * runtime helper GROUPS on a scan of the source program, checks the prediction against
 * the emitted bytecode, and re-runs the whole compile with the mispredicted group forced
 * on. Every warning of the discarded attempt had already been printed, so one
 * undefined-function call site warned TWICE for one compile. A warning belongs to the
 * attempt that SHIPS, which is only known once the attempt finishes -- so an attempt
 * buffers its warnings here and flushes them (printing and counting) when it is the one
 * that produced the output; a discarded attempt's warnings are neither.
 *
 * <p>
 * <b>Buffering is opt-in, per thread.</b> Without an open attempt {@link #warn} prints
 * straight through, which is what every other caller (the {@code --no-gc} backend, which
 * never re-runs a compile) wants and keeps its output byte-identical; the GC WASM backend
 * re-runs a compile to outline an oversized function and opens attempts like the JVM
 * backend. Within an attempt the lines are deduplicated, so a call site reached twice by
 * one attempt's own passes says it once.
 */
public final class CompileWarnings {

	/**
	 * The lines of the in-flight attempt, each mapped to whether it counts, or
	 * {@code null} when not buffering.
	 */
	private static final ThreadLocal<@Nullable Map<String, Boolean>> PENDING = new ThreadLocal<>();

	/** The count of the compile in flight on this thread, or {@code null}. */
	private static final ThreadLocal<@Nullable Counter> COUNTER = new ThreadLocal<>();

	/** What {@link #startCounting} opened: the dependency test and the count so far. */
	private static final class Counter {

		final Predicate<String> dependency;

		int count;

		Counter(Predicate<String> dependency) {
			this.dependency = dependency;
		}

	}

	private CompileWarnings() {
	}

	/**
	 * Emits a compile-time warning about a form of the program: buffered when an attempt
	 * is open on this thread (see {@link #startAttempt()}), printed to {@code System.err}
	 * otherwise, as {@code file:line:column: warning: text} when the form (or the located
	 * form around it) has a position in a named file.
	 * @param subject the form the warning is about, or {@code null} when it is about no
	 * one form
	 * @param text the warning, without the {@code warning: } label or a position
	 */
	public static void warn(@Nullable LispVal subject, String text) {
		SourceLocation location = SourceProvenance.warningLocation(subject);
		String line = (location == null ? "" : location.prefix()) + "warning: " + text;
		emit(line, counts(subject));
	}

	/**
	 * Emits a line that states what the build did or what the host now owes, rather than
	 * something about the program to change: buffered and printed like a warning, never
	 * counted.
	 * @param line the complete line
	 */
	public static void note(String line) {
		emit(line, false);
	}

	/**
	 * Warns about an expansion-time argument-shape rejection the backend is compiling as
	 * a call-time {@code program-error} ({@code LispMacroExpander.lowerProgramError}, the
	 * undefined-function precedent): the program still fails at that call, so say so at
	 * compile time, where the position is known. A {@code %program-error} whose message
	 * is built at run time is a runtime check, not a static rejection, and warns nothing.
	 * @param form the {@code %program-error} form (carrying the rejected call's position)
	 */
	public static void warnStaticProgramError(LispCons form) {
		String message = LispMacroExpander.staticProgramErrorMessage(form);
		if (message != null) {
			warn(form, message + "; compiled as a call-time program-error");
		}
	}

	private static boolean counts(@Nullable LispVal subject) {
		Counter counter = COUNTER.get();
		if (counter == null) {
			return false;
		}
		SourceLocation location = SourceProvenance.programSourceLocation(subject);
		return location != null && (location.file() == null || !counter.dependency.test(location.file()));
	}

	private static void emit(String line, boolean counts) {
		Map<String, Boolean> pending = PENDING.get();
		if (pending == null) {
			print(line, counts);
		}
		else {
			pending.merge(line, counts, Boolean::logicalOr);
		}
	}

	private static void print(String line, boolean counts) {
		System.err.println(line);
		Counter counter = COUNTER.get();
		if (counts && counter != null) {
			counter.count++;
		}
	}

	/**
	 * Starts counting, on this thread, the warnings about the program's own source that
	 * reach the output -- printed straight through or flushed with the attempt that
	 * shipped. The compile boundary opens it around the whole compile, inside the
	 * {@link SourceProvenance} recording scope the count is judged against, and reads
	 * {@link #counted()} before it writes anything.
	 * @param dependency whether a source file is a dependency's (a system a dist
	 * installed): its warnings are printed and not counted, the way a build tool caps a
	 * registry dependency's lints
	 */
	public static void startCounting(Predicate<String> dependency) {
		COUNTER.set(new Counter(dependency));
	}

	/**
	 * How many counted warnings the compile in flight on this thread has emitted so far;
	 * 0 when nothing is counting.
	 * @return the count
	 */
	public static int counted() {
		Counter counter = COUNTER.get();
		return counter == null ? 0 : counter.count;
	}

	/** Stops counting on this thread, in a {@code finally}. */
	public static void stopCounting() {
		COUNTER.remove();
	}

	/**
	 * Starts buffering the warnings of one compile attempt on this thread, discarding
	 * anything a previous attempt left. Every path out of the attempt must reach
	 * {@link #flushAttempt()} or {@link #discardAttempt()}; a buffer left open would
	 * swallow the warnings of whatever compiles next on this thread. Not reentrant: only
	 * a backend's own top-level compile loop opens one.
	 */
	public static void startAttempt() {
		PENDING.set(new LinkedHashMap<>());
	}

	/**
	 * Prints the attempt's lines, in the order they were first emitted, counts its
	 * counted warnings, and ends it.
	 */
	public static void flushAttempt() {
		Map<String, Boolean> pending = PENDING.get();
		PENDING.remove();
		if (pending != null) {
			pending.forEach(CompileWarnings::print);
		}
	}

	/**
	 * Ends the attempt without printing or counting anything: its output was thrown away,
	 * so its warnings describe a compile that never happened.
	 */
	public static void discardAttempt() {
		PENDING.remove();
	}

}
