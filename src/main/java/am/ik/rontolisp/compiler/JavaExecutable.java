package am.ik.rontolisp.compiler;

import java.util.List;

/**
 * A public method or constructor as the {@code java:} resolution sees it (see
 * {@link JavaType}).
 */
public interface JavaExecutable {

	/**
	 * @return the class or interface that declares it
	 */
	JavaType declaringClass();

	/**
	 * @return the method name, or {@code <init>} for a constructor
	 */
	String name();

	/**
	 * @return the parameter types, the varargs parameter as its array type
	 */
	List<? extends JavaType> parameterTypes();

	/**
	 * @return the declared return type; for a constructor, the declaring class
	 */
	JavaType returnType();

	/**
	 * @return whether the last parameter is a varargs array
	 */
	boolean isVarArgs();

	/**
	 * @return whether it is a static method
	 */
	boolean isStatic();

	/**
	 * @return whether it is a constructor
	 */
	boolean isConstructor();

	/**
	 * @return whether it has no body: an interface method that is neither default nor
	 * static, or an abstract class's abstract method -- what a class implementing the
	 * interface must supply ({@link JavaImplementations})
	 */
	boolean isAbstract();

}
