package am.ik.rontolisp.runtime;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What {@code (java:view value items :set ...)} makes: a read-only {@code java.util.Set}
 * standing for a Lisp collection of members, the items converted as {@code Object}
 * arguments when it was made, in their order. {@code contains} is the members' own
 * {@code equals}, every write is an {@code UnsupportedOperationException}, {@code equals}
 * and {@code hashCode} are the {@code Set} contract's, and {@code toString} is what its
 * printer answers for the value (the {@code Set} spelling without one).
 */
public final class RontoJavaSetView extends AbstractSet<Object> implements RontoJavaValue {

	private final Object value;

	private final Set<Object> members;

	private final Object printer;

	private final String className;

	private final RontoJavaCalls calls;

	/**
	 * @param value the Lisp value the view stands for
	 * @param elements the members as {@code Object} arguments
	 * @param printer the Lisp function its {@code toString} calls with the value, or null
	 * @param className the class Java's messages name it by, or null for its own
	 * @param calls how the printer is called, or null without one
	 */
	public RontoJavaSetView(Object value, Object[] elements, Object printer, String className, RontoJavaCalls calls) {
		this.value = value;
		this.members = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(elements)));
		this.printer = printer;
		this.className = className;
		this.calls = calls;
	}

	@Override
	public Iterator<Object> iterator() {
		return this.members.iterator();
	}

	@Override
	public int size() {
		return this.members.size();
	}

	@Override
	public boolean contains(Object member) {
		return this.members.contains(member);
	}

	@Override
	public String toString() {
		return this.printer == null ? super.toString() : this.calls.text(this.printer, this.value);
	}

	@Override
	public Object value() {
		return this.value;
	}

	@Override
	public String className() {
		return this.className != null ? this.className : getClass().getName();
	}

}
