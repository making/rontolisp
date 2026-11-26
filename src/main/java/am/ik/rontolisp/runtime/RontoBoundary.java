package am.ik.rontolisp.runtime;

/**
 * The marshalling seam a {@code rontolisp:jvm-export} wrapper calls — the one place where
 * a Java value becomes a rontolisp value and back for the boundary types too large to
 * inline into hand-assembled bytecode.
 *
 * <p>
 * A generated class calls these; a caller of a generated class does not. Each method
 * takes the {@code owner} class so a handle can find that class's {@code --gpu} residency
 * guards ({@code .kb/gpu.md}), and a {@code where} prefix so the exact-or-throw message
 * names the export and the argument the way every other boundary type's guard does
 * ({@code .kb/jvm-export.md}).
 */
public final class RontoBoundary {

	private RontoBoundary() {
	}

	/**
	 * A {@code :float-vector} / {@code :float-matrix} ARGUMENT: the packed float array
	 * the handle holds, aliased rather than copied (the copy is the 10x this boundary
	 * type exists to avoid).
	 * @param value the handle the Java caller passed
	 * @param rank the rank the designator declares (1 for {@code :float-vector}, 2 for
	 * {@code :float-matrix})
	 * @param owner the generated class, whose {@code --gpu} guards the handle adopts
	 * @param where the message prefix naming the export and the argument
	 * @return the packed representation, ready to hand to the untyped defun method
	 * @throws IllegalArgumentException if the handle is null or of the wrong rank
	 */
	public static Object floatArrayArgument(RontoFloatArray value, int rank, Class<?> owner, String where) {
		if (value == null) {
			throw new IllegalArgumentException(where + "must not be null");
		}
		if (value.rank() != rank) {
			throw new IllegalArgumentException(where + "expects rank " + rank + ", got rank " + value.rank());
		}
		value.adopt(owner);
		return value.packed();
	}

	/**
	 * A {@code :float-vector} / {@code :float-matrix} RESULT: a handle over the packed
	 * float array the function answered, aliased rather than copied — so a chain of calls
	 * through Java pays no marshalling at all, and (under {@code --gpu}) a result the
	 * device still holds is NOT brought home until the caller actually reads it.
	 * @param value the value the untyped defun method answered
	 * @param rank the rank the designator declares
	 * @param owner the generated class, whose {@code --gpu} guards the handle adopts
	 * @param where the message prefix naming the export
	 * @return the handle
	 * @throws ClassCastException if the value is not a packed float array of that rank
	 */
	public static RontoFloatArray floatArrayResult(Object value, int rank, Class<?> owner, String where) {
		try {
			RontoFloatArray.checkPacked(value);
		}
		catch (IllegalArgumentException ex) {
			throw new ClassCastException(where + ex.getMessage());
		}
		RontoFloatArray handle = RontoFloatArray.wrap(value);
		if (handle.rank() != rank) {
			throw new ClassCastException(where + "expects rank " + rank + ", got rank " + handle.rank());
		}
		handle.adopt(owner);
		return handle;
	}

}
