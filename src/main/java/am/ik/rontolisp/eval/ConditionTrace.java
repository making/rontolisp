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
 * innermost {@link LocatedCons} its loop has stepped onto and the program function that
 * form is written in -- a type test and two stores per step -- and hands them to
 * {@link #passing} from a {@code catch} it already had. A program that never signals
 * builds nothing; nothing here reaches the condition's message, so {@code handler-case},
 * {@code princ} and every pinned output see the text they always did.
 *
 * <p>
 * <b>Segment 0</b> is where the condition was signaled: the innermost located form and
 * the program function whose TEXT holds it -- lexical, so the answer is a property of the
 * form, the same one every compiled backend gives from its own tables, whatever tail
 * call, inlining or thread the condition travelled through. A form in an anonymous lambda
 * (a callback, an {@code flet} helper, a handler) belongs to the function it was written
 * in; one at the top level or in an async body to none. The function is the name of its
 * scope ({@link Environment#lexicalFunction}): a program function's call scope
 * ({@link LispLambda#sourced} -- a library function's never is) or a macro expander's
 * starts its own, and every scope inside it, a closure's included, inherits it.
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

	/** The async boundaries crossed, innermost first; {@code null} until the first. */
	private @Nullable List<Hop> hops;

	/**
	 * Notes one evaluation frame the condition is leaving.
	 * @param located the innermost located form the frame visited, or {@code null}
	 * @param writtenIn the name the program function {@code located} is written in was
	 * defined under, or {@code null} for none
	 */
	synchronized void passing(@Nullable LocatedCons located, @Nullable String writtenIn) {
		if (located == null) {
			return;
		}
		List<Hop> crossed = this.hops;
		if (crossed != null) {
			int last = crossed.size() - 1;
			Hop hop = crossed.get(last);
			if (hop.awaitedAt() == null) {
				crossed.set(last, new Hop(hop.function(), located));
			}
			return;
		}
		if (this.location == null) {
			this.location = located;
			this.function = writtenIn == null ? null : UncaughtReport.functionName(writtenIn);
		}
	}

	/**
	 * Notes that the condition escaped an async body and is about to be stored for an
	 * {@code await} on another thread.
	 * @param asyncFunction the async function's name, or {@code null} for an async lambda
	 */
	synchronized void crossedAsync(@Nullable String asyncFunction) {
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
