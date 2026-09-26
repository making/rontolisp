package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LocatedCons;
import am.ik.rontolisp.compiler.UncaughtReport;

import org.jspecify.annotations.Nullable;

/**
 * Where an interpreted condition has been, noted while it UNWINDS so an uncaught one can
 * say where it happened ({@link UncaughtReport#atLine},
 * {@link UncaughtReport#asyncLine}).
 *
 * <p>
 * <b>Recorded on the throw path only.</b> Each evaluation frame keeps, in two locals, the
 * innermost {@link LocatedCons} its loop has stepped onto and the lambda whose body it
 * was in then -- a type test and two stores per step -- and hands them to
 * {@link #passing} from a {@code catch} it already had. A program that never signals
 * builds nothing; nothing here reaches the condition's message, so {@code handler-case},
 * {@code princ} and every pinned output see the text they always did.
 *
 * <p>
 * <b>Segment 0</b> is where the condition was signaled: the innermost located form and
 * the innermost NAMED function whose body holds it. A frame's located form was visited in
 * the lambda it reports with it; when that is none (the frame had not entered a lambda)
 * or an anonymous one, the answer is the lambda the ENCLOSING frame is in, which that
 * frame supplies as its current lambda. So a tail call into a library function whose own
 * forms carry no position still reports the call site and the caller, and a location
 * found only in a caller is never attributed to the callee.
 *
 * <p>
 * <b>An async boundary</b> ({@link #crossedAsync}) closes segment 0 and opens a hop: an
 * {@code async-defun} body runs on its own virtual thread and its condition is rethrown
 * by {@code await} on another, so the frames after the boundary are the AWAITER's and
 * must not be attributed to the async function. The first located form after it is the
 * {@code await} site.
 */
final class ConditionTrace {

	private record Hop(@Nullable String function, @Nullable LocatedCons awaitedAt) {
	}

	private @Nullable LocatedCons location;

	private @Nullable String function;

	private boolean functionDecided;

	/** The async boundaries crossed, innermost first; {@code null} until the first. */
	private @Nullable List<Hop> hops;

	/**
	 * Notes one evaluation frame the condition is leaving.
	 * @param located the innermost located form the frame visited, or {@code null}
	 * @param locatedIn the lambda whose body the frame was in when it visited
	 * {@code located}, or {@code null} for the frame's starting context
	 * @param current the lambda whose body the frame is in now, or {@code null}
	 */
	synchronized void passing(@Nullable LocatedCons located, @Nullable LispLambda locatedIn,
			@Nullable LispLambda current) {
		List<Hop> crossed = this.hops;
		if (crossed != null) {
			int last = crossed.size() - 1;
			Hop hop = crossed.get(last);
			if (hop.awaitedAt() == null && located != null) {
				crossed.set(last, new Hop(hop.function(), located));
			}
			return;
		}
		if (this.location == null) {
			if (located == null) {
				return;
			}
			this.location = located;
			decide(locatedIn);
			return;
		}
		decide(current);
	}

	/**
	 * Notes a frame that runs a named body without a {@link LispLambda} of its own -- a
	 * macro's expander, evaluated at the call site's evaluation time -- so a location
	 * found inside it is attributed to it rather than to the function the call site is
	 * in.
	 * @param name the body's name
	 */
	synchronized void passingNamed(String name) {
		if (this.hops == null && this.location != null && !this.functionDecided) {
			this.function = name;
			this.functionDecided = true;
		}
	}

	private void decide(@Nullable LispLambda lambda) {
		if (!this.functionDecided && lambda != null && lambda.name() != null) {
			this.function = lambda.name();
			this.functionDecided = true;
		}
	}

	/**
	 * Notes that the condition escaped an async body and is about to be stored for an
	 * {@code await} on another thread.
	 * @param asyncFunction the async function's name, or {@code null} for an async lambda
	 */
	synchronized void crossedAsync(@Nullable String asyncFunction) {
		this.functionDecided = true;
		List<Hop> crossed = this.hops;
		if (crossed == null) {
			crossed = new ArrayList<>(1);
			this.hops = crossed;
		}
		crossed.add(new Hop(asyncFunction, null));
	}

	/**
	 * The location lines under the report, innermost first; empty when nothing is known.
	 * @return the lines, without newlines
	 */
	synchronized List<String> lines() {
		List<String> lines = new ArrayList<>();
		LocatedCons at = this.location;
		if (at != null) {
			lines.add(UncaughtReport.atLine(at.file(), at.line(), this.function));
		}
		for (Hop hop : this.hops == null ? List.<Hop>of() : this.hops) {
			LocatedCons awaited = hop.awaitedAt();
			lines.add(UncaughtReport.asyncLine(hop.function(), awaited == null ? null : awaited.file(),
					awaited == null ? 0 : awaited.line()));
		}
		return lines;
	}

}
