package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The keywords a {@code java:} form may end in -- a {@code java:new}, {@code java:call},
 * {@code java:static} or {@code java:field} after its arguments, a {@code java:proxy},
 * {@code java:reify} or {@code java:subclass} after its callable (its last function) --
 * each a convention of the code that makes the call, in any order. A keyword converts to
 * no Java value, so a marker is never mistaken for an argument, and the run-time paths
 * read the markers off the evaluated arguments the same way. {@code :through} is followed
 * by its function, which is no argument either.
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
 * ({@link JavaImplementation#readsComparison}), and one converted to {@code Runnable},
 * {@code Callable} or {@code Comparator} stands for itself
 * ({@link JavaImplementation#standsForItsFunction})
 * @param octets {@code :octets}: a {@code byte[]} the form answers -- a call's value, a
 * field's, an element of an array either answers -- and one Java hands a function
 * converted at the call or of the implementation the form makes -- an argument, or an
 * element of one -- comes back as an {@code (unsigned-byte 8)} vector of its octets
 * rather than a list of signed bytes
 * @param through {@code :through f}: Java calls each function the form converts to an
 * interface, and each the implementation it makes calls, through {@code f} --
 * {@code (f function kind resolved arguments)}, {@code arguments} the list the function
 * would be applied to, {@code kind} how Java takes the answer
 * ({@link JavaImplementation#answerKind}: the method's primitive return type as a
 * keyword, {@code :object} for a reference, {@code nil} for none), {@code resolved}
 * {@link #resolved} -- and takes what {@code f} answers. The name of the function a
 * literal {@code #'name} names, {@link #COMPUTED} for any other form or an evaluated
 * value, {@code null} when the form ends in no {@code :through}
 * @param resolved no keyword a form is written with: whether the site converting a
 * function chose its member before it ran -- resolved to one member, or dispatched over
 * its only overload ({@code JavaSiteResolver}) -- which a {@code :through} function is
 * handed, as Clojure's compiled call and its reflector coerce a fn's answer otherwise.
 * Kept only beside a {@code :through} ({@link #withResolved}); never set by
 * {@link #of(List, int)}, so a site chosen as it runs and an implementation form's own
 * functions hand {@code nil}
 */
public record JavaMarkers(boolean functional, boolean javaFalse, boolean octets, @Nullable String through,
		boolean resolved) {

	/** No marker. */
	public static final JavaMarkers NONE = new JavaMarkers(false, false, false);

	/** {@code :functional} alone. */
	public static final JavaMarkers FUNCTIONAL = new JavaMarkers(true, false, false);

	/**
	 * The {@link #through} of a {@code :through} whose function no literal {@code #'name}
	 * names: a form computing it, or the value a run-time path evaluated.
	 */
	public static final String COMPUTED = "";

	/**
	 * Why a form ending in {@code :through} with a function no literal {@code #'name}
	 * names is left to run time ({@link #throughComputed}): a compiled program calls the
	 * function it names.
	 */
	public static final String THROUGH_COMPUTED = "its :through function is not named by #'";

	/**
	 * Markers with no {@code :through}.
	 * @param functional {@code :functional}
	 * @param javaFalse {@code :java-false}
	 * @param octets {@code :octets}
	 */
	public JavaMarkers(boolean functional, boolean javaFalse, boolean octets) {
		this(functional, javaFalse, octets, null);
	}

	/**
	 * Markers as a form is written with them: no site's choice ({@link #resolved}).
	 * @param functional {@code :functional}
	 * @param javaFalse {@code :java-false}
	 * @param octets {@code :octets}
	 * @param through {@code :through}'s function name, or {@code null}
	 */
	public JavaMarkers(boolean functional, boolean javaFalse, boolean octets, @Nullable String through) {
		this(functional, javaFalse, octets, through, false);
	}

	/**
	 * These markers at a site that chose its member before it ran, or did not: the
	 * {@link #resolved} a {@code :through} function is handed. Without a {@code :through}
	 * nothing reads it, so the markers stay as they are -- a shape keyed by them is not
	 * split for nothing.
	 * @param chosen whether the site chose its member before it ran
	 * @return the markers
	 */
	public JavaMarkers withResolved(boolean chosen) {
		boolean kept = chosen && this.through != null;
		return kept == this.resolved ? this
				: new JavaMarkers(this.functional, this.javaFalse, this.octets, this.through, kept);
	}

	/**
	 * @return whether any marker is set ({@link #resolved}, which no form is written
	 * with, aside)
	 */
	public boolean any() {
		return this.functional || this.javaFalse || this.octets || this.through != null;
	}

	/**
	 * @return whether the form ends in {@code :through} with a function no literal
	 * {@code #'name} names, which only a run-time path can call
	 */
	public boolean throughComputed() {
		return COMPUTED.equals(this.through);
	}

	/**
	 * These markers and the one a value is.
	 * @param marker a value {@link #isMarker} accepts
	 * @return the union
	 */
	private JavaMarkers with(LispVal marker) {
		String name = ((LispSymbol) marker).name();
		if (LispNames.JAVA_FUNCTIONAL_MARKER.equals(name)) {
			return new JavaMarkers(true, this.javaFalse, this.octets, this.through);
		}
		return LispNames.JAVA_FALSE_MARKER.equals(name)
				? new JavaMarkers(this.functional, true, this.octets, this.through)
				: new JavaMarkers(this.functional, this.javaFalse, true, this.through);
	}

	/**
	 * @param value a form or an evaluated argument
	 * @return whether it is one of the marker keywords that stand alone (every marker but
	 * {@code :through}, which its function follows)
	 */
	public static boolean isMarker(LispVal value) {
		return value instanceof LispSymbol symbol && (LispNames.JAVA_FUNCTIONAL_MARKER.equals(symbol.name())
				|| LispNames.JAVA_FALSE_MARKER.equals(symbol.name())
				|| LispNames.JAVA_OCTETS_MARKER.equals(symbol.name()));
	}

	/**
	 * @param value a form or an evaluated argument
	 * @return whether it is the {@code :through} keyword
	 */
	public static boolean isThrough(LispVal value) {
		return value instanceof LispSymbol symbol && LispNames.JAVA_THROUGH_MARKER.equals(symbol.name());
	}

	/**
	 * How many of the parts at or after {@code first} are markers ending them: the
	 * keywords, and a {@code :through} with the function after it.
	 * @param parts a form's elements, or evaluated arguments
	 * @param first the first index a marker may be at (the first argument's)
	 * @return the count, 0 when the parts end in no marker
	 */
	public static int count(List<LispVal> parts, int first) {
		int end = parts.size();
		while (end > first) {
			if (isMarker(parts.get(end - 1))) {
				end--;
			}
			else if (end - 2 >= first && isThrough(parts.get(end - 2))) {
				end -= 2;
			}
			else {
				break;
			}
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
			if (isThrough(parts.get(i))) {
				i++;
				if (markers.through == null) {
					markers = new JavaMarkers(markers.functional, markers.javaFalse, markers.octets,
							functionName(parts.get(i)));
				}
			}
			else {
				markers = markers.with(parts.get(i));
			}
		}
		return markers;
	}

	/**
	 * The function a {@code :through} ending the parts names: the form after the first
	 * one, or the value a run-time path evaluated it to.
	 * @param parts a form's elements, or evaluated arguments
	 * @param first the first index a marker may be at (the first argument's)
	 * @return the form or value, {@code null} when the parts end in no {@code :through}
	 */
	public static @Nullable LispVal throughFunction(List<LispVal> parts, int first) {
		int i = parts.size() - count(parts, first);
		while (i < parts.size() && !isThrough(parts.get(i))) {
			i++;
		}
		return i < parts.size() ? parts.get(i + 1) : null;
	}

	// The name a literal #'name form names, else COMPUTED.
	private static String functionName(LispVal form) {
		if (form instanceof LispCons cell && cell.car() instanceof LispSymbol head
				&& LispNames.FUNCTION.equals(head.name()) && cell.cdr() instanceof LispCons rest
				&& rest.cdr() instanceof LispNil && rest.car() instanceof LispSymbol name && !name.isKeyword()) {
			return name.name();
		}
		return COMPUTED;
	}

}
