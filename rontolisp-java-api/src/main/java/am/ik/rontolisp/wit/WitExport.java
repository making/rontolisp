package am.ik.rontolisp.wit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a class implements a WIT world the program exports: each function the
 * world exports is a static method of the class, named in camel case ({@code vowel-count}
 * is {@code vowelCount}).
 *
 * <pre>
 * &#64;WitExport(value = "counter", wit = "wit/vowels.wit")
 * public class Counter {
 *
 *     public static int vowelCount(String text) {
 *         ...
 *     }
 * }
 * </pre>
 *
 * <p>
 * The class declares a method for every function the world exports: rontolisp checks each
 * against the WIT when it compiles the program, its name, its parameters and its result.
 * An interface the world exports is a static member class named after the interface in
 * upper camel case ({@code export docs:adder/add;} is {@code Add}), its static methods
 * the interface's functions. The types are those {@link WitImport} maps, of the ones a
 * world's export carries -- every one but {@code flags}, a resource, a {@code stream} and
 * a {@code future}: a record, a variant or an enum the world defines is a member type of
 * the class, one an exported interface defines a member type of the interface's member
 * class.
 *
 * <pre>
 * // record point { x: s32, y: s32 }
 * // flip: func(p: point) -&gt; point;
 * public record Point(int x, int y) {
 * }
 *
 * public static Point flip(Point p) {
 *     return new Point(p.y(), p.x());
 * }
 * </pre>
 *
 * <p>
 * A method answering a {@code result<T, E>} declares a {@link WitResult}, or its ok arm:
 * the error arm is then a {@link WitException} the method throws.
 *
 * <p>
 * Under a JDK, and on rontolisp's interpreter and JVM, the annotation does nothing: the
 * methods are ordinary Java methods, which a program or a test calls. A program with no
 * {@code main} is the classes this annotation marks in its source tree, and rontolisp
 * compiles it to a WebAssembly component that exports the world, its entry points these
 * methods: a host, or a component composed with it, calls them.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WitExport {

	/**
	 * The WIT world the class implements, by its name ({@code counter}).
	 * @return the world
	 */
	String value();

	/**
	 * The WIT the world is read from: a {@code .wit} file, or the directory of a package
	 * as published, named as {@link WitImport#wit()} names one -- as
	 * {@link Class#getResource(String)} names a resource, relative to the package of the
	 * annotated class, or from the top after a leading {@code /}.
	 * @return the name
	 */
	String wit();

}
