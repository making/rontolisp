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
 * <b>Recording is opt-in, per thread, and COMPILE PATH ONLY.</b> Nothing is recorded
 * until {@link #startRecording()} opens a scope, which only
 * {@code RontoLispCli.compileToFile} does. Two reasons, both deliberate:
 * <ul>
 * <li>The interpreter reaches the same expander at EVALUATION time, so recording there
 * would put a {@code file:line:} prefix on ordinary runtime error text, which
 * {@code ci-spec.yaml} and the doc examples pin byte for byte. The compile path has a
 * frontend that is over before the program runs, so its diagnostics are free to say
 * where.</li>
 * <li>A served request may {@code load} at run time; a process-wide table would grow
 * without bound and race across request threads. The state is a {@link ThreadLocal}, so a
 * thread that never opens a scope pays one null check and a request thread that does
 * takes its table with it when it ends.</li>
 * </ul>
 * The re-evaluation trigger for the first bullet: if the interpreter ever grows a
 * separate frontend phase (one that expands a whole program before evaluating any of it),
 * recording it becomes free of that risk and the divergence should be retired.
 *
 * <p>
 * <b>How a location reaches an error.</b> Not by wrapping: a frontend pass may catch its
 * own exception types to fall back, so the type must survive. Instead each recursive pass
 * that descends into a cons calls {@link #noteFailure(LispVal, RuntimeException)} on the
 * way out of a failure and rethrows the SAME exception. The innermost frame with a known
 * location wins, and a frame whose cons is macro-generated (not in the table) simply
 * leaves the slot for an enclosing one to fill -- that is the "nearest enclosing located
 * cons" rule. The compile boundary then reads {@link #failureLocation(RuntimeException)}
 * and prefixes the message it reports. Compiled output is untouched: nothing here reaches
 * an emitter.
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

		SourceLocation location() {
			return SourceLocation.at(this.unit.file(), this.offset, this.unit.text());
		}
	}

	/** The per-thread recording; {@code null} when this thread is not recording. */
	private static final class State {

		final Map<LispCons, Position> positions = new IdentityHashMap<>();

		/** The exception {@link #failureLocation} currently describes, by identity. */
		@Nullable RuntimeException failing;

		/** The innermost known location noted for {@link #failing}. */
		@Nullable SourceLocation failureLocation;

		/** The top-level form the pipeline is on, used when no frame noted a location. */
		@Nullable LispVal topLevelForm;

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
	 * A no-op when the pass handed back the original (the identity rule already applied),
	 * when the result is not a cons, or when this thread is not recording.
	 * @param <T> the static type of the rewritten form
	 * @param original the cons the pass walked
	 * @param rewritten what it produced in its place
	 * @return {@code rewritten}, for {@code return SourceProvenance.inherit(cons, ...)}
	 */
	public static <T extends @Nullable LispVal> T inherit(LispCons original, T rewritten) {
		State state = STATE.get();
		if (state == null || !(rewritten instanceof LispCons cons) || cons == original) {
			return rewritten;
		}
		Position position = state.positions.get(original);
		if (position != null) {
			state.positions.putIfAbsent(cons, position);
		}
		return rewritten;
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
		return position == null ? null : position.location();
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
