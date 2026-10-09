package am.ik.rontolisp.runtime;

/**
 * A Java object standing for a Lisp value: what {@code java:handle} and {@code java:view}
 * make ({@code .kb/java-interop.md}, "Handles and views"). Wherever Java hands one back
 * -- a call's answer, an array's element, a callback's argument -- the {@code java:}
 * surface answers {@link #value()}: the interpreter's {@code JavaInterop}, a compiled
 * program's {@code _junm} and its bridge alike. The interpreter makes these classes
 * itself; they travel beside a compiled program that names either operator
 * ({@code .kb/jvm-export.md}, "What travels").
 */
public interface RontoJavaValue {

	/**
	 * @return the Lisp value it stands for, in the representation of the backend that
	 * made it
	 */
	Object value();

	/**
	 * @return the class Java's messages name it by: the one its maker gave, else its own
	 */
	String className();

}
