package am.ik.rontolisp.compiler;

/**
 * A public field as the {@code java:} resolution sees it (see {@link JavaType}).
 */
public interface JavaField {

	/**
	 * @return the class or interface that declares it
	 */
	JavaType declaringClass();

	/**
	 * @return the field name
	 */
	String name();

	/**
	 * @return the declared type
	 */
	JavaType type();

	/**
	 * @return whether it is a static field
	 */
	boolean isStatic();

}
