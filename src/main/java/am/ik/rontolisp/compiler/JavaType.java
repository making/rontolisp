package am.ik.rontolisp.compiler;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A Java type as the {@code java:} resolution sees it -- a class, an interface, an array
 * or a primitive -- independent of where its description comes from: reflection over a
 * loaded class ({@link ReflectiveJavaClasses}), or class files read without loading
 * anything ({@code codegen.jvm.JvmClassFileLookup}, over a JDK's {@code ct.sym} and a
 * class path). Both answer with the semantics of {@link Class} -- {@link #methods} is
 * {@link Class#getMethods()} of that name, re-resolved to accessible declarations -- so a
 * call site resolves to the same member whichever describes it.
 * <p>
 * A type is its own {@link JavaKind}: the kind of a wrapped host object is its exact
 * class. Instances are canonical within one {@link JavaClassLookup}, so kinds compare by
 * identity.
 */
public interface JavaType extends JavaKind {

	/**
	 * @return the name in {@link Class#getName()} spelling: {@code int},
	 * {@code java.util.Map$Entry}, {@code [Ljava.lang.String;}
	 */
	String name();

	/**
	 * @return whether this is a primitive type (or {@code void})
	 */
	boolean isPrimitive();

	/**
	 * @return whether this is an array type
	 */
	boolean isArray();

	/**
	 * @return the component type of an array type, otherwise {@code null}
	 */
	@Nullable JavaType componentType();

	/**
	 * @return whether this is an interface
	 */
	boolean isInterface();

	/**
	 * @return whether no other class can be a subclass: a {@code final} class, a
	 * primitive or an array
	 */
	boolean isFinal();

	/**
	 * Whether the class file says {@code ACC_PUBLIC}: what bytecode in another package
	 * may name the class by. A member class declared {@code public} or {@code protected}
	 * is public in its class file (the language-level access lives in the
	 * {@code InnerClasses} attribute, which linking does not read); a primitive is
	 * public, an array as public as its element type.
	 * @return whether the class is public to the linker
	 */
	boolean isPublic();

	/**
	 * @return whether it is an interface or an {@code abstract} class: no constructor of
	 * it makes an instance
	 */
	boolean isAbstract();

	/**
	 * Whether a value of {@code other} can be assigned to this type without conversion,
	 * as {@link Class#isAssignableFrom(Class)} answers.
	 * @param other the source type
	 * @return true when {@code other} is this type or a subtype
	 */
	boolean isAssignableFrom(JavaType other);

	/**
	 * The public methods of this name a call can reach through this type:
	 * {@link Class#getMethods()} filtered by name, each re-resolved to an accessible
	 * declaration (a public method of a non-exported or non-public class is reached
	 * through the supertype that declares it accessibly) and dropped when there is none.
	 * Covariant variants (one parameter list, several return types) are all kept.
	 * @param name the method name
	 * @return the candidates, in no particular order
	 */
	List<? extends JavaExecutable> methods(String name);

	/**
	 * @return the public constructors ({@link Class#getConstructors()})
	 */
	List<? extends JavaExecutable> constructors();

	/**
	 * The public field of this name ({@link Class#getField(String)}: declared here, on a
	 * superinterface, or on a superclass).
	 * @param name the field name
	 * @return the field, or {@code null} when there is none
	 */
	@Nullable JavaField field(String name);

	/**
	 * Whether code outside this type's module can use its public members: the type is
	 * public and its package is exported, or it lives on the class path.
	 * @return whether the members are accessible
	 */
	boolean isAccessible();

	/**
	 * Whether a compiled program can name this type in its own bytecode -- a class
	 * constant, a {@code checkcast}, a method's owner: a primitive, or a type that is
	 * {@link #isPublic() public} and {@link #isAccessible() accessible}. A site resolves
	 * only through linkable types, so every resolved site can be compiled to a direct
	 * call ({@code JavaSiteResolver}).
	 * @return whether bytecode in another package and module links against it
	 */
	default boolean isLinkable() {
		return isPrimitive() || (isPublic() && isAccessible());
	}

}
