package am.ik.rontolisp.wit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an interface stands for a WIT interface the program imports: each
 * abstract method is a function of it, named in camel case ({@code list-keys} is
 * {@code listKeys}).
 *
 * <pre>
 * &#64;WitImport(value = "wasi:keyvalue/store", wit = "wit/keyvalue.wit")
 * public interface Store {
 *
 *     Bucket open(String identifier);
 *
 *     interface Bucket extends AutoCloseable {
 *
 *         Optional&lt;byte[]&gt; get(String key);
 *
 *         void set(String key, byte[] value);
 *
 *         void close();
 *     }
 * }
 * </pre>
 *
 * <p>
 * The interface declares the functions the program calls, no more: rontolisp checks each
 * against the WIT when it compiles the program, its name, its parameters and its result.
 * A resource is a member interface of the resource's name in upper camel case
 * ({@code Bucket}), its methods the resource's and {@code close()} its release; a
 * resource's constructor and its static functions are methods of this interface named
 * after the resource ({@code bucketNew}, {@code bucketCreate}). A record is a member
 * record, a variant a member sealed interface whose cases are records, an enum a member
 * enum, flags a {@code Set} of a member enum; a {@code list<u8>} is a {@code byte[]}, any
 * other list a {@code List}, an option an {@code Optional}, a tuple a record; the
 * integers are the Java ones of their width, an unsigned one holding its bits. A
 * {@code result}'s ok arm is what the method answers, its error arm a
 * {@link WitException} carrying the error. An {@code async func} is a method that waits
 * for its answer, as a call does; a {@code stream<T>} the host answers is a
 * {@link WitStream}, a {@code future<T>} a {@link WitFuture}.
 *
 * <p>
 * The implementation is a service provider: {@code ServiceLoader.load(Store.class)} finds
 * the program's own, listed in {@code META-INF/services}, under a JDK and on rontolisp's
 * interpreter and JVM; in a WebAssembly module or component rontolisp compiles, it finds
 * the host's alone, which the module imports the WIT interface from.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WitImport {

	/**
	 * The WIT interface: its id ({@code wasi:keyvalue/store@0.2.0-draft}), or the id
	 * without the version, which the WIT read gives.
	 * @return the interface
	 */
	String value();

	/**
	 * The WIT the interface is read from: a {@code .wit} file, or the directory of a
	 * package as published (its {@code .wit} files and {@code deps/}), named as
	 * {@link Class#getResource(String)} names a resource -- relative to the package of
	 * the annotated interface, or from the top after a leading {@code /} -- and looked
	 * for where that class's loader looks: the program's source tree, then the roots of
	 * its source path in order. So it may be beside the source file
	 * ({@code src/main/java/com/example/wit/keyvalue.wit}) or a resource
	 * ({@code src/main/resources/com/example/wit/keyvalue.wit}).
	 * @return the name
	 */
	String wit();

}
