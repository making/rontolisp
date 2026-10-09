package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The keywords a {@code java:} form may end in -- a {@code java:new}, {@code java:call},
 * {@code java:static} or {@code java:field} after its arguments, a {@code java:proxy},
 * {@code java:reify} or {@code java:subclass} after its callable (its last function) --
 * each a convention of the code that makes the call, in any order. A keyword converts to
 * no Java value, so a marker is never mistaken for an argument, and the run-time paths
 * read the markers off the evaluated arguments the same way.
 *
 * @param functional {@code :functional}: a function converted to an interface implements
 * every abstract method by the method's arguments
 * ({@link JavaImplementations#functional}), not as a {@code java:proxy} called with the
 * method's name first
 * @param javaFalse {@code :java-false}: Java's {@code false} comes back as the symbol
 * {@code |false|} ({@link LispNames#JAVA_FALSE}) rather than {@code nil} -- a
 * {@code boolean} or {@code Boolean} the call answers, an element of an array it answers,
 * an argument Java hands a function converted at the call (or the implementation the form
 * makes); and, with {@code :functional} too, a function implementing
 * {@code java.util.Comparator} by its arguments may answer a boolean
 * ({@link JavaImplementation#readsComparison})
 */
public record JavaMarkers(boolean functional, boolean javaFalse) {

	/** No marker. */
	public static final JavaMarkers NONE = new JavaMarkers(false, false);

	/** {@code :functional} alone. */
	public static final JavaMarkers FUNCTIONAL = new JavaMarkers(true, false);

	/**
	 * @return whether any marker is set
	 */
	public boolean any() {
		return this.functional || this.javaFalse;
	}

	/**
	 * These markers and the one a value is.
	 * @param marker a value {@link #isMarker} accepts
	 * @return the union
	 */
	private JavaMarkers with(LispVal marker) {
		String name = ((LispSymbol) marker).name();
		return LispNames.JAVA_FUNCTIONAL_MARKER.equals(name) ? new JavaMarkers(true, this.javaFalse)
				: new JavaMarkers(this.functional, true);
	}

	/**
	 * @param value a form or an evaluated argument
	 * @return whether it is one of the marker keywords
	 */
	public static boolean isMarker(LispVal value) {
		return value instanceof LispSymbol symbol && (LispNames.JAVA_FUNCTIONAL_MARKER.equals(symbol.name())
				|| LispNames.JAVA_FALSE_MARKER.equals(symbol.name()));
	}

	/**
	 * How many of the parts at or after {@code first} are markers ending them.
	 * @param parts a form's elements, or evaluated arguments
	 * @param first the first index a marker may be at (the first argument's)
	 * @return the count, 0 when the parts end in no marker
	 */
	public static int count(List<LispVal> parts, int first) {
		int end = parts.size();
		while (end > first && isMarker(parts.get(end - 1))) {
			end--;
		}
		return parts.size() - end;
	}

	/**
	 * The markers ending the parts.
	 * @param parts a form's elements, or evaluated arguments
	 * @param first the first index a marker may be at (the first argument's)
	 * @return the markers, {@link #NONE} when there is none
	 */
	public static JavaMarkers of(List<LispVal> parts, int first) {
		JavaMarkers markers = NONE;
		for (int i = parts.size() - count(parts, first); i < parts.size(); i++) {
			markers = markers.with(parts.get(i));
		}
		return markers;
	}

}
