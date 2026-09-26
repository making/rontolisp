package am.ik.rontolisp;

import java.util.IdentityHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Where each cons of the program was read from, so a FRONTEND pass can name a file and a
 * line in an error the reader never saw -- a macro that signals while expanding, an
 * unknown operator, a malformed binding list a compiler pass casts and fails on. Giving
 * every READ error a {@code file:line:column:} prefix came first; this is the same answer
 * for every error raised AFTER the read.
 *
 * <p>
 * <b>A side table, never a field on the AST.</b> {@link LispVal} is sealed and its leaf
 * values are shared/interned, so a location field would be wrong on the leaves and would
 * cost memory on every program. Cons identity, on the other hand, survives the whole
 * frontend: a cons is created fresh per read (backquote is read-time, but the cells it
 * builds are fresh too), so an {@link IdentityHashMap} keyed by cons is exact. Only
 * conses are recorded; an error about an atom is reported against the form containing it.
 *
 * <p>
 * <b>This table is opt-in, per thread, and COMPILE PATH ONLY.</b> Nothing is recorded
 * until {@link #startRecording()} opens a scope, which only
 * {@code RontoLispCli.compileToFile} does: the compile path has a frontend that is over
 * before the program runs, so its diagnostics are free to prefix the MESSAGE with where.
 * The interpreter reaches the same expander at evaluation time, where a prefix on the
 * message would change runtime error text a program can read; a read outside a scope
 * makes each datum's outermost cons a {@link LocatedCons} instead, whose position reaches
 * only the top-level uncaught-condition report, never a message -- and lives exactly as
 * long as the cons, so a served request's run-time {@code load} leaves nothing behind.
 *
 * <p>
 * <b>How a location reaches an error.</b> Not by wrapping: a frontend pass may catch its
 * own exception types to fall back, so the type must survive. Instead each recursive pass
 * that descends into a cons calls {@link #noteFailure(LispVal, RuntimeException)} on the
 * way out of a failure and rethrows the SAME exception. The innermost frame with a known
 * location wins, and a frame whose cons is macro-generated (not in the table) simply
 * leaves the slot for an enclosing one to fill -- that is the "nearest enclosing located
 * cons" rule. The compile boundary then reads {@link #failureLocation(RuntimeException)}
 * and prefixes the message it reports.
 *
 * <p>
 * <b>Two emitters read it, both for the uncaught report's location lines.</b> A compiled
 * JVM class maps its instructions to the forms they came from
 * ({@code codegen.jvm.JvmSourceSites}), and a wasm-GC module compiled with
 * {@code --report-locations} wraps each located function in a frame that notes its line
 * ({@code codegen.wasm.WasmUncaughtLocations}). A form with no FILE (a {@code -e}
 * program, a library spliced from the jar) counts as unlocated for both, and an output in
 * which nothing was located is emitted exactly as without this table.
 */
public final class SourceProvenance {

	/**
	 * One unit of source: its origin file (or {@code null} when unknown, e.g. a REPL
	 * buffer) and the full text, kept so a recorded offset can be resolved to a
	 * line/column lazily -- resolving eagerly per cons would make a read quadratic.
	 *
	 * @param file the origin file, or {@code null} when unknown
	 * @param text the full source text of the unit
	 */
	public record Unit(@Nullable String file, String text) {
	}

	/** A cons's recorded origin: the unit it was read from and its offset in it. */
	private record Position(Unit unit, int offset) {
	}

	/** The per-thread recording; {@code null} when this thread is not recording. */
	private static final class State {

		final Map<LispCons, Position> positions = new IdentityHashMap<>();

		/**
		 * Each unit's line-start offsets, built on the first {@link #locate} into it. A
		 * backend that asks for the line of every form it compiles (the JVM line numbers,
		 * the wasm-GC {@code --report-locations}) would otherwise rescan the unit's text
		 * from the start once per form -- quadratic in the size of the file. By identity:
		 * {@link Unit} is a record, and comparing two whole source texts per lookup is
		 * what this index exists to avoid.
		 */
		final Map<Unit, int[]> lineStarts = new IdentityHashMap<>();

		/** The exception {@link #failureLocation} currently describes, by identity. */
		@Nullable RuntimeException failing;

		/** The innermost known location noted for {@link #failing}. */
		@Nullable SourceLocation failureLocation;

		/** The top-level form the pipeline is on, used when no frame noted a location. */
		@Nullable LispVal topLevelForm;

		/**
		 * The location of a recorded position: what {@link SourceLocation#at} computes by
		 * scanning the text from its start, answered through the line index.
		 */
		SourceLocation location(Position position) {
			Unit unit = position.unit();
			String text = unit.text();
			int[] starts = this.lineStarts.computeIfAbsent(unit, u -> lineStartsOf(text));
			// The same clamp SourceLocation.at applies to an offset past the end.
			int limit = Math.max(0, Math.min(position.offset(), text.length()));
			int found = java.util.Arrays.binarySearch(starts, limit);
			// A line starts AFTER its newline, so an offset equal to a start is on that
			// line; otherwise it is on the line whose start precedes it.
			int line = found >= 0 ? found : -found - 2;
			return new SourceLocation(unit.file(), line + 1, limit - starts[line] + 1);
		}

		private static int[] lineStartsOf(String text) {
			int count = 1;
			for (int i = 0; i < text.length(); i++) {
				if (text.charAt(i) == '\n') {
					count++;
				}
			}
			int[] starts = new int[count];
			int next = 1;
			for (int i = 0; i < text.length(); i++) {
				if (text.charAt(i) == '\n') {
					starts[next++] = i + 1;
				}
			}
			return starts;
		}

	}

	private static final ThreadLocal<@Nullable State> STATE = new ThreadLocal<>();

	private SourceProvenance() {
	}

	/**
	 * Starts recording cons origins on the CURRENT thread, discarding anything a previous
	 * scope on this thread recorded. Pair with {@link #stopRecording()} in a
	 * {@code finally}: the table holds the whole program's conses alive, so a scope that
	 * is never closed is a leak for as long as the thread lives.
	 */
	public static void startRecording() {
		STATE.set(new State());
	}

	/** Stops recording on the current thread and drops the recorded table. */
	public static void stopRecording() {
		STATE.remove();
	}

	/**
	 * Whether this thread is recording. The reader checks this before doing any per-datum
	 * work, so a non-recording read (the interpreter, a runtime {@code read-from-string})
	 * costs one {@link ThreadLocal} lookup per datum and nothing else.
	 * @return true when a recording scope is open on this thread
	 */
	public static boolean isRecording() {
		return STATE.get() != null;
	}

	/**
	 * Records where a cons was read from. A cons already recorded keeps its FIRST origin:
	 * a datum read once and spliced into several places (a {@code load}ed file included
	 * twice, a reader label) belongs to the file it was written in.
	 * @param cons the cons the reader just produced
	 * @param unit the source unit it was read from
	 * @param offset the character offset in the unit where the datum starts
	 */
	public static void record(LispCons cons, Unit unit, int offset) {
		State state = STATE.get();
		if (state != null) {
			state.positions.putIfAbsent(cons, new Position(unit, offset));
		}
	}

	/**
	 * Gives a cons a REWRITING pass just built the position of the cons it replaces, and
	 * returns it. The identity rule ({@code .kb/source-positions.md}) covers the pass
	 * that changes nothing; a pass that legitimately rewrites a form -- a fold, an
	 * inliner -- would still drop the position of every cons on the path from the
	 * top-level form down to the rewrite, because a rebuilt parent forces rebuilt
	 * children. The rewritten form stands for the same source text, so it inherits the
	 * same position.
	 *
	 * <p>
	 * A no-op when the pass handed back the original (the identity rule already applied)
	 * or when the result is not a cons. With no recording scope open the rewrite is the
	 * interpreter's (the package resolver runs at evaluation time), whose positions ride
	 * on {@link LocatedCons}: a located original's rewrite is answered as a located COPY
	 * of its top cell, so the caller must use the value returned, never its argument --
	 * which every caller's {@code return SourceProvenance.inherit(cons, ...)} does.
	 * @param <T> the static type of the rewritten form
	 * @param original the cons the pass walked
	 * @param rewritten what it produced in its place, a cell nothing else holds yet
	 * @return {@code rewritten}, or its located copy
	 */
	@SuppressWarnings("unchecked")
	public static <T extends @Nullable LispVal> T inherit(LispCons original, T rewritten) {
		if (!(rewritten instanceof LispCons cons) || cons == original) {
			return rewritten;
		}
		State state = STATE.get();
		if (state == null) {
			if (original instanceof LocatedCons located && !(cons instanceof LocatedCons)) {
				return (T) new LocatedCons(cons.car(), cons.cdr(), located.file(), located.line());
			}
			return rewritten;
		}
		Position position = state.positions.get(original);
		if (position != null) {
			state.positions.putIfAbsent(cons, position);
		}
		return rewritten;
	}

	/**
	 * {@link #inherit} for the compile path only: records {@code rewritten} at the
	 * position of {@code original} when a recording scope is open, and does nothing
	 * otherwise -- the interpreter's forms keep exactly the {@link LocatedCons} cells
	 * they had. For a cell whose position only a compiled output reads: the lambda a
	 * local function or an async body is built as, which the wasm-GC
	 * {@code --report-locations} frames name by its line. The interpreter never
	 * attributes a condition to such a form (evaluating it only makes a closure), so a
	 * located copy there would buy nothing and would have to replace the cell inside its
	 * already-built parent.
	 * @param original the cons the rewrite stands for
	 * @param rewritten what replaced it
	 */
	public static void inheritWhenCompiling(LispCons original, @Nullable LispVal rewritten) {
		if (STATE.get() != null) {
			inherit(original, rewritten);
		}
	}

	/**
	 * The recorded location of a form, or {@code null} when it is not a cons, was not
	 * read from source (a macro built it), or this thread is not recording.
	 * @param form the form to locate
	 * @return its source location, or {@code null}
	 */
	public static @Nullable SourceLocation locate(@Nullable LispVal form) {
		State state = STATE.get();
		if (state == null || !(form instanceof LispCons cons)) {
			return null;
		}
		Position position = state.positions.get(cons);
		return position == null ? null : state.location(position);
	}

	/**
	 * Whether a form has a recorded position in a NAMED file: {@link #locate} answering a
	 * location with a file, without resolving the line.
	 * @param form the form to ask about
	 * @return true when it was read from a named file on this recording thread
	 */
	public static boolean locatedInFile(@Nullable LispVal form) {
		State state = STATE.get();
		if (state == null || !(form instanceof LispCons cons)) {
			return false;
		}
		Position position = state.positions.get(cons);
		return position != null && position.unit().file() != null;
	}

	/**
	 * The {@code file:line:column: } prefix for a form, or {@code ""} when its position
	 * is unknown. For a frontend WARNING, which is printed rather than thrown and so
	 * never reaches the compile boundary's failure decoration.
	 * @param form the form the diagnostic is about
	 * @return the prefix, or {@code ""}
	 */
	public static String prefix(@Nullable LispVal form) {
		SourceLocation location = locate(form);
		return location == null ? "" : location.prefix();
	}

	/**
	 * Records which top-level form the frontend is currently on, as the fallback location
	 * for a failure no pass had a hook for. A pass that walks a program form by form pays
	 * one call per form and needs no {@code try}/{@code finally}: the value is
	 * overwritten by the next form and only ever read while a failure is unwinding, so a
	 * stale one can never be reported.
	 * @param form the top-level form about to be processed
	 */
	public static void enterTopLevelForm(@Nullable LispVal form) {
		State state = STATE.get();
		if (state != null) {
			state.topLevelForm = form;
		}
	}

	/**
	 * Notes {@code form} as the location of an in-flight failure and returns
	 * {@code exception} unchanged, so a pass can write {@code catch (RuntimeException ex)
	 * { throw SourceProvenance.noteFailure(cons, ex); }} without altering the exception's
	 * type or message. The FIRST frame to note a location for a given exception wins,
	 * which is the innermost one because the note happens while unwinding; a form with no
	 * recorded location leaves the slot open for an enclosing frame.
	 * @param form the form the failing pass was processing
	 * @param exception the exception being rethrown
	 * @return {@code exception}, for {@code throw noteFailure(...)}
	 */
	public static RuntimeException noteFailure(@Nullable LispVal form, RuntimeException exception) {
		State state = STATE.get();
		if (state == null) {
			return exception;
		}
		if (state.failing != exception) {
			// A new failure: an earlier one either was reported or was caught and
			// recovered from, and its location must not leak into this one.
			state.failing = exception;
			state.failureLocation = null;
		}
		if (state.failureLocation == null) {
			state.failureLocation = locate(form);
		}
		return exception;
	}

	/**
	 * The innermost location noted for {@code exception} while it unwound, falling back
	 * to the top-level form the frontend was on ({@link #enterTopLevelForm}) when no
	 * frame knew one -- a pass without a hook still names the file and the form that
	 * failed, which is most of the answer. {@code null} only when nothing at all is known
	 * (the whole failing form was macro-generated, or this thread is not recording).
	 * @param exception the exception that escaped the frontend
	 * @return the location to report it at, or {@code null}
	 */
	public static @Nullable SourceLocation failureLocation(RuntimeException exception) {
		State state = STATE.get();
		if (state == null) {
			return null;
		}
		SourceLocation noted = state.failing == exception ? state.failureLocation : null;
		return noted != null ? noted : locate(state.topLevelForm);
	}

}
