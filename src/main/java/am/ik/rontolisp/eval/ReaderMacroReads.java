package am.ik.rontolisp.eval;

import java.util.IdentityHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;

/**
 * The top-level forms of a compile path's own files that a reader macro function read, in
 * the read-time image ({@link ReadTimeImage}), and where in their source it read them, so
 * the macro-time pass ({@link UserMacroExpander}) can read each again in its own
 * evaluator ({@code .kb/readtables.md}, "The compile paths' read").
 *
 * <p>
 * A read's effects on the image -- coalton's {@code coalton-toplevel} reader macro
 * typechecks the form and stores what it defined in coalton's global environment --
 * belong to the compiling image, where the next form's macros read them; the read-time
 * image is thrown away, so the macro-time pass reads the form again and has them too. A
 * spliced system's forms are not recorded: a system is loaded, and its load-time forms
 * are what the macro-time pass replays (coalton's generated code updates the environment
 * itself), as loading a compiled file never reads its source.
 *
 * <p>
 * Per thread, for one compile: {@code cli.LoadInliner}'s read records, the macro-time
 * pass looks up each form it meets, and a new read starts empty.
 */
public final class ReaderMacroReads {

	/**
	 * Where a form was read.
	 *
	 * @param source the whole text of its file
	 * @param file the file, or {@code null} when unknown
	 * @param features the features its guards were tested with
	 * @param start the offset its read started at
	 * @param end the offset its read ended at
	 */
	public record Origin(String source, @Nullable String file, Features features, int start, int end) {
	}

	private static final ThreadLocal<Map<LispVal, Origin>> ORIGINS = ThreadLocal.withInitial(IdentityHashMap::new);

	private ReaderMacroReads() {
	}

	/** Forgets what an earlier read on this thread recorded. */
	public static void clear() {
		ORIGINS.remove();
	}

	/**
	 * Records that a reader macro function read {@code form}.
	 * @param form the form as read
	 * @param origin where it was read
	 */
	public static void record(LispVal form, Origin origin) {
		ORIGINS.get().put(form, origin);
	}

	/**
	 * {@return where a reader macro function read {@code form}, or {@code null} when none
	 * did}
	 * @param form a top-level form, by identity
	 */
	static @Nullable Origin originOf(LispVal form) {
		return ORIGINS.get().get(form);
	}

	/**
	 * Whether this thread has recorded any form.
	 * @return whether it has
	 */
	static boolean any() {
		return !ORIGINS.get().isEmpty();
	}

}
