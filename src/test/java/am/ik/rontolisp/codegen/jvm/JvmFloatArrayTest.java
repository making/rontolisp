package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code #f(...)} packed-float type on the JVM backend. A packed literal (and
 * {@code make-array :element-type 'double-float}) compiles to a native unboxed
 * {@code double[]} carrying a dimension header {@code [rank, dim..., data...]}; every
 * array op routes through the {@code _fv*} dispatch helpers, which handle both the packed
 * {@code double[]} and a general {@code ArrayList} at runtime. Printing uses the
 * {@code #f(...)} reader syntax (rendered via {@code _fvToGeneral} +
 * {@code _arrayToString} then rewriting the {@code #}/{@code #nA} prefix to {@code #f})
 * so it round-trips to a packed array, while the packed-specific semantics are pinned
 * too: an element stores as a {@code double} (a non-real store is a type error), and
 * {@code array-element-type} reports {@code double-float}. {@code --simd} acceleration
 * over the same {@code double[]} is a follow-up (see {@code .todo/94}).
 */
class JvmFloatArrayTest {

	@TempDir
	Path tempDir;

	private String compileAndRun(String lispCode) throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("Test");
		byte[] classBytes = compiler.compile(LispReader.readAllFromString(lispCode));
		Path classFile = this.tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			return baos.toString().trim();
		}
	}

	@Test
	void rank1LiteralPrintsAsADoubleVector() throws Exception {
		assertThat(compileAndRun("(print #f(1.0 2.0 3.0))")).isEqualTo("#f(1.0 2.0 3.0)");
	}

	@Test
	void integerLeavesCoerceToDoubleAtReadTime() throws Exception {
		assertThat(compileAndRun("(print #f(1 2 3))")).isEqualTo("#f(1.0 2.0 3.0)");
	}

	@Test
	void rank2LiteralPrintsAsAMatrix() throws Exception {
		assertThat(compileAndRun("(print #f((1.0 2.0) (3.0 4.0)))")).isEqualTo("#f((1.0 2.0) (3.0 4.0))");
	}

	@Test
	void arefReadsElements() throws Exception {
		assertThat(compileAndRun("(print (aref #f(1.0 2.0 3.0) 1))")).isEqualTo("2.0");
		assertThat(compileAndRun("(print (aref #f((1.0 2.0) (3.0 4.0)) 1 0))")).isEqualTo("3.0");
		assertThat(compileAndRun("(print (row-major-aref #f((1.0 2.0) (3.0 4.0)) 2))")).isEqualTo("3.0");
	}

	@Test
	void lengthAndDimensions() throws Exception {
		assertThat(compileAndRun("(print (length #f(1.0 2.0 3.0)))")).isEqualTo("3");
		assertThat(compileAndRun("(print (array-dimensions #f((1.0 2.0) (3.0 4.0))))")).isEqualTo("(2 2)");
	}

	@Test
	void setfArefWithADoubleMutatesInPlace() throws Exception {
		assertThat(compileAndRun("(let ((v #f(1.0 2.0 3.0))) (setf (aref v 1) 9.0) (print v))"))
			.isEqualTo("#f(1.0 9.0 3.0)");
	}

	@Test
	void makeArrayDoubleFloatDefaultsToZeroFill() throws Exception {
		// The double-float default element is 0.0, matching the interpreter's packed
		// array
		// (a general array otherwise defaults to nil).
		assertThat(compileAndRun("(print (make-array 3 :element-type 'double-float))")).isEqualTo("#f(0.0 0.0 0.0)");
	}

	@Test
	void makeArrayDoubleFloatWithInitialElement() throws Exception {
		assertThat(compileAndRun("(print (make-array 3 :element-type 'double-float :initial-element 2.0))"))
			.isEqualTo("#f(2.0 2.0 2.0)");
	}

	@Test
	void arraypAndVectorp() throws Exception {
		// %arrayp is the internal predicate arrayp expands to; the raw compile path here
		// does not run the macro layer, so the internal name is exercised directly.
		assertThat(compileAndRun("(print (if (%arrayp #f(1.0 2.0)) 'yes 'no))")).isEqualTo("yes");
		assertThat(compileAndRun("(print (if (vectorp #f(1.0 2.0)) 'yes 'no))")).isEqualTo("yes");
	}

	@Test
	void quotedPackedLiteral() throws Exception {
		// A #f literal inside a quote builds the same packed double[].
		assertThat(compileAndRun("(print (quote #f(1.0 2.0 3.0)))")).isEqualTo("#f(1.0 2.0 3.0)");
	}

	@Test
	void rank3ArefUsesTheGenericHelper() throws Exception {
		// Rank 3 exercises _fvArefN (the Horner flat index over the header dims).
		assertThat(compileAndRun("(print (aref #f(((1.0 2.0) (3.0 4.0)) ((5.0 6.0) (7.0 8.0))) 1 0 1))"))
			.isEqualTo("6.0");
	}

	@Test
	void nonDoubleStoreCoercesToDouble() throws Exception {
		// Storing an integer into a packed array coerces it to a double (the element type
		// is always double-float), so the array reads back as 9.0, not 9.
		assertThat(compileAndRun("(let ((v #f(1.0 2.0 3.0))) (setf (aref v 0) 9) (print v))"))
			.isEqualTo("#f(9.0 2.0 3.0)");
	}

	@Test
	void setfArefReturnsTheStoredDouble() throws Exception {
		// setf on a packed element returns the coerced double (matching the interpreter),
		// so a stored integer 9 yields 9.0.
		assertThat(compileAndRun("(print (let ((v #f(1.0 2.0 3.0))) (setf (aref v 0) 9)))")).isEqualTo("9.0");
	}

	@Test
	void rank2SetfMutatesInPlace() throws Exception {
		assertThat(compileAndRun("(let ((m #f((1.0 2.0) (3.0 4.0)))) (setf (aref m 1 0) 9.0) (print m))"))
			.isEqualTo("#f((1.0 2.0) (9.0 4.0))");
	}

	@Test
	void arrayElementTypeIsDoubleFloat() throws Exception {
		assertThat(compileAndRun("(print (array-element-type #f(1.0 2.0)))")).isEqualTo("double-float");
		assertThat(compileAndRun("(print (array-element-type (make-array 3 :element-type 'double-float)))"))
			.isEqualTo("double-float");
	}

	@Test
	void nonRealStoreIsATypeError() throws Exception {
		// Storing a non-real (a string) into a packed array is a type error at runtime.
		assertThatThrownBy(() -> compileAndRun("(let ((v #f(1.0 2.0 3.0))) (setf (aref v 0) \"x\") (print v))"))
			.hasRootCauseInstanceOf(ClassCastException.class);
	}

}
