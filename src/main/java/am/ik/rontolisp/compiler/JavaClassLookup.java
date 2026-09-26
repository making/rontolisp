package am.ik.rontolisp.compiler;

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

}
