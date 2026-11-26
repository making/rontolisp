package am.ik.rontolisp.runtime;

/**
 * A Java object standing for a Lisp value: what {@code java:handle} and {@code java:view}
 * make, and a {@code java:reify} given {@code :value} ({@code .kb/java-interop.md},
 * "Handles and views"). Wherever Java hands one back -- a call's answer, an array's
 * element, a callback's argument -- the {@code java:} surface answers {@link #value()}:
 * the interpreter's {@code JavaInterop}, a compiled program's {@code _junm} and its
 * bridge alike. The interpreter makes these classes itself; they travel beside a compiled
 * program that makes one ({@code .kb/jvm-export.md}, "What travels").
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

	/**
	 * The {@code equals} of a {@code java:reify} standing for a value whose form names no
	 * {@code equals}, the one rule its classes share (the interpreter's {@code Proxy}, a
	 * compiled program's generated class): equal only to an object standing for the very
	 * same value, named by the same class -- a nil-hash {@code java:handle}'s identity.
	 * @param self the object
	 * @param other the object compared with
	 * @return whether they are equal
	 */
	static boolean sameValue(RontoJavaValue self, Object other) {
		return other == self || other instanceof RontoJavaValue that && that.value() == self.value()
				&& that.className().equals(self.className());
	}

	/**
	 * The {@code hashCode} of a {@code java:reify} standing for a value whose form names
	 * no {@code hashCode}: the value's identity hash, as a nil-hash
	 * {@code java:handle}'s.
	 * @param self the object
	 * @return the hash
	 */
	static int identityHash(RontoJavaValue self) {
		return System.identityHashCode(self.value());
	}

	/**
	 * The {@code toString} of a {@code java:reify} standing for a value whose form names
	 * no {@code toString}: {@code Object}'s spelling of its class, the class and its
	 * {@code hashCode} in hex -- the one its form names when it names one.
	 * @param self the object
	 * @return the text
	 */
	static String identityText(RontoJavaValue self) {
		return self.className() + "@" + Integer.toHexString(self.hashCode());
	}

}
