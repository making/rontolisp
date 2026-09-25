package am.ik.jvm;

/**
 * A class needs more constant-pool entries than the class-file format can index
 * ({@link ConstantPool#MAX_INDEX}). Its own type so a generator that can spread a class
 * over several class files ({@link JvmClassSplitter}) can tell this refusal apart from
 * every other failure and take that route instead.
 */
public final class ConstantPoolOverflowException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	/**
	 * @param message what overflowed, and by how much
	 */
	public ConstantPoolOverflowException(String message) {
		super(message);
	}

}
