package am.ik.rontolisp;

import org.jspecify.annotations.Nullable;

/**
 * The default renderers' shared cycle guard: the values the CURRENT thread is rendering,
 * outermost first, plus the depth cap that bounds the render stack. An instance, a cons
 * chain or a general array opens one frame when its rendering begins; a value already on
 * the path -- or the frame that would open past {@link #MAX_RENDER_DEPTH} -- prints as
 * {@code "#"}, CL's {@code *print-level*} cutoff marker, instead of recursing without
 * end. The check is IDENTITY along the current path, not equality and not "rendered
 * before": the same value reachable twice on a finite path still renders twice.
 * <p>
 * One mechanism, three arms: {@link LispInstance#render}, {@link LispCons}'s renderer and
 * the array renderers ({@link LispArray}, the packed vectors) all share this path, so a
 * cycle threading through any mix of them is caught by whichever arm re-enters. The JVM
 * backend's emitted twin is the {@code _renderPath}/{@code _renderDepth} static pair, the
 * WASM backends' the two printer module globals -- the same cap, the same marker, so the
 * four renderings stay byte-identical.
 */
public final class RenderCycleGuard {

	/**
	 * The render-frame depth the default renderers will open before writing {@code #}
	 * instead -- the guard's capacity, and the bound on the render stack for a deep
	 * FINITE nest too.
	 */
	public static final int MAX_RENDER_DEPTH = 256;

	/** The values the CURRENT thread is rendering, outermost first. */
	private static final ThreadLocal<RenderPath> RENDER_PATH = ThreadLocal.withInitial(RenderPath::new);

	private static final class RenderPath {

		private final @Nullable Object[] path = new @Nullable Object[MAX_RENDER_DEPTH];

		private int depth;

	}

	private RenderCycleGuard() {
	}

	/**
	 * Opens a render frame for {@code value}. Answers false -- and opens nothing -- when
	 * the value is already on the current rendering path or the depth cap is reached; the
	 * caller then prints {@code "#"} and must NOT call {@link #exit()}.
	 * @param value the instance, cons or array whose rendering begins
	 * @return whether the frame was opened
	 */
	public static boolean enter(Object value) {
		RenderPath rp = RENDER_PATH.get();
		for (int i = 0; i < rp.depth; i++) {
			if (rp.path[i] == value) {
				return false;
			}
		}
		if (rp.depth == MAX_RENDER_DEPTH) {
			return false;
		}
		rp.path[rp.depth++] = value;
		return true;
	}

	/** Closes the frame the matching successful {@link #enter(Object)} opened. */
	public static void exit() {
		RenderPath rp = RENDER_PATH.get();
		rp.path[--rp.depth] = null;
	}

}
