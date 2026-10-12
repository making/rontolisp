package am.ik.rontolisp.clojure;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The Java classes a lowering asks what a host form means -- whether a member is a static
 * method or a field, a throwable's superclasses and constructors, the methods an
 * interface declares -- by binary name, each answered as {@link Class} answers it. On a
 * JVM they are the classes of the program's class loader, by reflection
 * ({@link ClojureFiles#javaClasses()}). A native image holds only the classes and the
 * reflection its build registered, so its own {@link Class} would answer part of a JDK
 * class or none of it; there the classes are read from class files -- the JDK's and the
 * program's class path -- which describe each one as reflection on a JVM does, so the
 * binary lowers a program as the JVM does ({@code .kb/clojure-frontend.md}, "Host
 * classes").
 */
public interface ClojureJavaClasses {

	/**
	 * The class of a binary name.
	 * @param name the binary name ({@code java.util.Map$Entry})
	 * @return the class, or {@code null} when there is none of that name here (a
	 * primitive's name names none)
	 */
	@Nullable JavaClass find(String name);

	/** A class, as {@link Class} describes it. */
	interface JavaClass {

		/**
		 * @return the binary name ({@link Class#getName()})
		 */
		String name();

		/**
		 * @return whether it is an interface
		 */
		boolean isInterface();

		/**
		 * @return whether its class file says public -- a member class declared
		 * {@code protected} included, as the linker reads it
		 */
		boolean isPublic();

		/**
		 * @return whether it is abstract (an interface is)
		 */
		boolean isAbstract();

		/**
		 * @return whether it is final
		 */
		boolean isFinal();

		/**
		 * @return the superclass's binary name ({@link Class#getSuperclass()}): none for
		 * an interface or {@code java.lang.Object}
		 */
		@Nullable String superclass();

		/**
		 * @return the binary names of the interfaces it directly implements or extends,
		 * in declaration order ({@link Class#getInterfaces()})
		 */
		List<String> interfaces();

		/**
		 * @return its public methods, inherited ones included, each as declared
		 * ({@link Class#getMethods()})
		 */
		List<Member> methods();

		/**
		 * @return its public constructors ({@link Class#getConstructors()})
		 */
		List<Member> constructors();

		/**
		 * @return whether it has a public field, an inherited one included
		 * ({@link Class#getFields()})
		 */
		boolean hasPublicFields();

		/**
		 * The public field of a name ({@link Class#getField(String)}).
		 * @param name the field's name
		 * @return the field, or {@code null} when there is none
		 */
		@Nullable Field field(String name);

		/**
		 * Whether a value of another class is one of this
		 * ({@link Class#isAssignableFrom}).
		 * @param other the other class, found among the same classes
		 * @return whether it is this class or a subtype of it
		 */
		boolean isAssignableFrom(JavaClass other);

	}

	/**
	 * A public method or constructor.
	 *
	 * @param name the method's name ({@code <init>} for a constructor)
	 * @param declaringClass the binary name of the class declaring it
	 * @param parameterTypes the parameter types' names, a varargs parameter as its array
	 * type ({@link Class#getName()} spelling: {@code int}, {@code [B},
	 * {@code [Ljava.lang.Object;})
	 * @param returnType the return type's name ({@code void}); a constructor's is its
	 * class
	 * @param isStatic whether it is a static method
	 * @param isVarArgs whether its last parameter is a varargs array
	 * @param isSynthetic whether the compiler made it (a bridge is synthetic)
	 * @param isBridge whether it is a bridge to a method of a supertype
	 * @param isAbstract whether it has no body
	 */
	record Member(String name, String declaringClass, List<String> parameterTypes, String returnType, boolean isStatic,
			boolean isVarArgs, boolean isSynthetic, boolean isBridge, boolean isAbstract) {

		/**
		 * @return the parameter count
		 */
		public int parameterCount() {
			return this.parameterTypes.size();
		}

	}

	/**
	 * A public field.
	 *
	 * @param name the field's name
	 * @param type its type's name
	 * @param isStatic whether it is static
	 */
	record Field(String name, String type, boolean isStatic) {
	}

}
