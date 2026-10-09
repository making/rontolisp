package am.ik.rontolisp.runtime;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What {@code (java:view value items :map ...)} makes: a read-only {@code java.util.Map}
 * standing for a Lisp map, its keys and values converted as {@code Object} arguments when
 * it was made, in their order. {@code get} and {@code containsKey} are the keys' own
 * {@code equals}, every write (an entry's {@code setValue} too) an
 * {@code UnsupportedOperationException}, {@code equals} and {@code hashCode} the
 * {@code Map} contract's, and {@code toString} what its printer answers for the value
 * (the {@code Map} spelling without one).
 */
public final class RontoJavaMapView extends AbstractMap<Object, Object> implements RontoJavaValue {

	private final Object value;

	private final Map<Object, Object> entries;

	private final Object printer;

	private final String className;

	private final RontoJavaCalls calls;

	/**
	 * @param value the Lisp value the view stands for
	 * @param keysAndValues the keys and values as {@code Object} arguments, alternating
	 * @param printer the Lisp function its {@code toString} calls with the value, or null
	 * @param className the class Java's messages name it by, or null for its own
	 * @param calls how the printer is called, or null without one
	 */
	public RontoJavaMapView(Object value, Object[] keysAndValues, Object printer, String className,
			RontoJavaCalls calls) {
		this.value = value;
		Map<Object, Object> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
			map.put(keysAndValues[i], keysAndValues[i + 1]);
		}
		this.entries = Collections.unmodifiableMap(map);
		this.printer = printer;
		this.className = className;
		this.calls = calls;
	}

	@Override
	public Set<Map.Entry<Object, Object>> entrySet() {
		return this.entries.entrySet();
	}

	// Null for an absent key, Map.get's contract, which this class cannot spell
	// @Nullable (it imports nothing but the JDK).
	@Override
	@SuppressWarnings("NullAway")
	public Object get(Object key) {
		return this.entries.get(key);
	}

	@Override
	public boolean containsKey(Object key) {
		return this.entries.containsKey(key);
	}

	@Override
	public int size() {
		return this.entries.size();
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
