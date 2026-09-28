package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An {@code NSException} raised inside a send is the condition
 * {@code objc:objc-exception}, and {@code objc:invoke-with-error} signals
 * {@code objc:ns-error} for a method that fails through its {@code NSError **}, on the
 * interpreter (.kb/objc.md, "The new base: exceptions and NSError"). The corpus
 * ({@code objc-exception-corpus.lisp}) is what a JVM class and a {@code --native}
 * executable print byte for byte too.
 */
public class ObjcExceptionTest {

	private static String resource(String name) {
		try (InputStream in = ObjcExceptionTest.class.getResourceAsStream("/" + name)) {
			return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Test
	@EnabledOnOs(value = OS.MAC, architectures = "aarch64")
	void theCorpusPrintsWhatIsCommitted() {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream oldErr = System.err;
		System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
		String out;
		try {
			out = ObjcBaseTest.interpret(resource("objc-exception-corpus.lisp"));
		}
		finally {
			System.setErr(oldErr);
		}
		assertThat(out).isEqualTo(resource("objc-exception-corpus.expected"));
		// An exception a method defined in Lisp lets escape is a Lisp error inside a
		// callback: printed, answered as zero.
		assertThat(err.toString(StandardCharsets.UTF_8)).isEqualTo(ESCAPED);
	}

	/** What the corpus's escaping method prints on every target. */
	public static final String ESCAPED = "objc: error in a callback: -objectAtIndex: raised NSRangeException: "
			+ "*** -[__NSArray0 objectAtIndex:]: index 9 beyond bounds for empty array\n";

}
