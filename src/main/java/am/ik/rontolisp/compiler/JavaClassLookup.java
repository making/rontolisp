package am.ik.rontolisp.compiler;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Where the {@code java:} resolution finds a type by name: reflection over the running
 * JVM ({@link ReflectiveJavaClasses}, the interpreter's), or class files
 * ({@code codegen.jvm.JvmClassFileLookup}, the JVM compiler's -- a JDK's {@code ct.sym}
 * for {@code --java-release} plus {@code --java-classpath}). The two must describe the
 * same types identically; {@code JvmClassFileLookupTest} pins that over a corpus.
 */
public interface JavaClassLookup {

	/**
	 * Finds a type.
	 * @param name a {@link Class#getName()} spelling: a primitive ({@code int},
	 * {@code void}), a binary class name ({@code java.util.Map$Entry}) or an array
	 * descriptor ({@code [I}, {@code [Ljava.lang.String;})
	 * @return the type, or {@code null} when it does not exist here
	 */
	@Nullable JavaType find(String name);

	/**
	 * The kind of the object a {@code java:reify} or {@code java:proxy} of an interface
	 * makes, canonical within this lookup (kinds compare by identity).
	 * @param iface an interface this lookup found
	 * @return its implementation type
	 */
	default JavaImplementationType implementationOf(JavaType iface) {
		return implementationOf(List.of(iface));
	}

	/**
	 * The kind of the object a {@code java:proxy} of several interfaces makes, canonical
	 * within this lookup per interface list (kinds compare by identity).
	 * @param interfaces interfaces this lookup found, in the form's order
	 * @return their implementation type
	 */
	JavaImplementationType implementationOf(List<JavaType> interfaces);

	/**
	 * The kind of the object a {@code java:subclass} of a superclass and extra interfaces
	 * makes, canonical within this lookup per superclass and interface list (kinds
	 * compare by identity).
	 * @param superclass the superclass this lookup found
	 * @param interfaces the extra interfaces this lookup found, in the form's order
	 * @return their subclass type
	 */
	JavaImplementationType subclassOf(JavaType superclass, List<JavaType> interfaces);

}
