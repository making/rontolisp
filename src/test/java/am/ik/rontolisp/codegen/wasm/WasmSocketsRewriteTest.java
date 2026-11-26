package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code --component} socket-I/O rewrite's SHAPE table. Every shape a socket-capable
 * stream built-in accepts has to land on a {@code rontolisp::%io-*} dispatch defun (or on
 * its promoted future in an async context), because a shape the rewrite does not
 * recognize compiles to the NATIVE built-in and its {@code fd_read}/{@code fd_write} on a
 * socket fd (&gt;= 200) walks off the preview 1 adapter's fd table -- the
 * {@code unknown handle index 0} trap.
 *
 * <p>
 * The shapes that matter are NOT only the ones user code tends to write:
 * {@code GrayStreamsLibrary.process} runs BEFORE this pass and its
 * {@code %gray-*-dispatch} helpers' fall-through arms re-spell every built-in with all
 * the optional arguments filled in, so those are what a quickloaded driver actually
 * compiles to.
 */
class WasmSocketsRewriteTest {

	// The rewrite is a no-op unless a provider is spliced; the splice marker is a
	// top-level (defun rontolisp::%io-read-line ...). sockets.lisp's read futures are
	// async bodies.
	private static final String COMPONENT = """
			(defun rontolisp::%io-read-line (&optional s) s)
			(rontolisp:async-defun rontolisp::%read-line-future (&optional s) s)
			""";

	// runner-sockets.lisp's (--native) are plain defuns.
	private static final String RUNNER = """
			(defun rontolisp::%io-read-line (&optional s) s)
			(defun rontolisp::%read-line-future (&optional s) s)
			""";

	private static String rewriteTopLevel(String source) {
		return rewritten(COMPONENT, source);
	}

	// The same call inside a plain defun, where the sync dispatch defuns apply instead of
	// the async promotions.
	private static String rewriteInDefun(String source) {
		return rewritten(COMPONENT, "(defun probe (s seq) " + source + ")");
	}

	// The last form of the provider and the source, rewritten.
	private static String rewritten(String provider, String source) {
		List<LispVal> program = new PackageResolver().resolveProgram(LispReader.readAllFromString(provider + source));
		return new ArrayList<>(WasmSocketsRewrite.rewrite(program)).getLast().print();
	}

	@Test
	void grayStreamsSequenceFallThroughReachesTheSocketDispatch() {
		// (write-sequence bytes socket) in cl-postgres becomes THIS after the Gray
		// rewrite
		// -- the shape that trapped mid-message on every --component leg of
		// ClPostgresE2eTest.
		assertThat(rewriteInDefun("(write-sequence seq s :start 0 :end (length seq))"))
			.contains("RONTOLISP::%IO-WRITE-SEQUENCE")
			.doesNotContain(":START");
		assertThat(rewriteInDefun("(read-sequence seq s :start 1 :end 3)")).contains("RONTOLISP::%IO-READ-SEQUENCE")
			.doesNotContain(":END");
		// The unbounded forms keep working, now with the bounds filled in as nil (the
		// dispatch defuns normalize them against the sequence they were handed).
		assertThat(rewriteInDefun("(write-sequence seq s)")).contains("RONTOLISP::%IO-WRITE-SEQUENCE");
		assertThat(rewriteInDefun("(read-sequence seq s)")).contains("RONTOLISP::%IO-READ-SEQUENCE");
		// Async context: reads promote to a future + await, writes never do.
		assertThat(rewriteTopLevel("(read-sequence seq s :start 1 :end 3)")).contains("RONTOLISP:AWAIT",
				"RONTOLISP::%READ-SEQUENCE-FUTURE");
		assertThat(rewriteTopLevel("(write-sequence seq s :start 1 :end 3)")).contains("RONTOLISP::%IO-WRITE-SEQUENCE")
			.doesNotContain("RONTOLISP:AWAIT");
	}

	@Test
	void grayStreamsEofReadFallThroughReachesTheSocketDispatch() {
		assertThat(rewriteInDefun("(read-line s nil nil)")).contains("RONTOLISP::%IO-READ-LINE-EOF");
		assertThat(rewriteInDefun("(read-char s t nil)")).contains("RONTOLISP::%IO-READ-CHAR-EOF");
		assertThat(rewriteInDefun("(read-byte s nil 0)")).contains("RONTOLISP::%IO-READ-BYTE-EOF");
		assertThat(rewriteTopLevel("(read-line s nil nil)")).contains("RONTOLISP:AWAIT",
				"RONTOLISP::%READ-LINE-EOF-FUTURE");
		assertThat(rewriteTopLevel("(read-char s t nil)")).contains("RONTOLISP:AWAIT",
				"RONTOLISP::%READ-CHAR-EOF-FUTURE");
		// The runner promotes nothing: an await of what is no future answers its primary
		// alone, and read-line's missing-newline-p was lost.
		assertThat(rewritten(RUNNER, "(read-line s nil nil)")).contains("RONTOLISP::%IO-READ-LINE-EOF")
			.doesNotContain("RONTOLISP:AWAIT");
		assertThat(rewritten(RUNNER, "(rontolisp:async-defun probe (s) (read-char s))"))
			.contains("RONTOLISP::%IO-READ-CHAR")
			.doesNotContain("RONTOLISP:AWAIT");
	}

	@Test
	void writeCharAndBoundedWriteStringLowerOntoTheWriteStringDispatch() {
		// Both have a shared lowering onto the plain write-string this pass covers; doing
		// it HERE (instead of at WasmExprCompiler time, which is after this pass) is what
		// puts them on the socket path at all.
		assertThat(rewriteInDefun("(write-char #\\Z s)")).contains("RONTOLISP::%IO-WRITE-STRING")
			.doesNotContain("(WRITE-STRING ");
		assertThat(rewriteInDefun("(write-string \"0123456789\" s :start 2 :end 5)"))
			.contains("RONTOLISP::%IO-WRITE-STRING", "SUBSEQ")
			.doesNotContain(":START");
	}

	@Test
	void terpriAndForceOutputReachTheSocketDispatchAndKeepTheNilAnswer() {
		// terpri has no dispatch defun of its own: it lowers onto write-string (which
		// owns the socket arm), the same shared-lowering rule write-char follows. A
		// terpri on a socket would otherwise reach the NATIVE terpri compiler and
		// fd_write the newline past the preview 1 adapter's fd table. The progn
		// restores the nil terpri answers (write-string answers its string); the
		// stream-less form resolves through *standard-output* at the original call
		// site, like the write-string/write-line defaulting.
		assertThat(rewriteInDefun("(terpri s)")).contains("RONTOLISP::%IO-WRITE-STRING", "(PROGN")
			.doesNotContain("(TERPRI ");
		assertThat(rewriteInDefun("(terpri)")).contains("*STANDARD-OUTPUT*", "RONTOLISP::%IO-WRITE-STRING");
		// Writes never promote to a future + await in async context.
		assertThat(rewriteTopLevel("(terpri s)")).contains("RONTOLISP::%IO-WRITE-STRING")
			.doesNotContain("RONTOLISP:AWAIT");
		// An explicit nil keeps flowing through the dispatch defun's non-socket arm,
		// whose designator rule answers stdout -- same as write-string's.
		assertThat(rewriteInDefun("(terpri nil)")).contains("RONTOLISP::%IO-WRITE-STRING");
		// force-output / finish-output flush a component TLS socket's output buffer, so
		// they have dispatch defuns (answering nil) and default to *standard-output*.
		assertThat(rewriteInDefun("(force-output s)")).contains("(RONTOLISP::%IO-FORCE-OUTPUT S)");
		assertThat(rewriteTopLevel("(finish-output)")).contains("RONTOLISP::%IO-FINISH-OUTPUT", "*STANDARD-OUTPUT*")
			.doesNotContain("RONTOLISP:AWAIT");
	}

	@Test
	void anUnknownShapeOrAListThatIsNoCallKeepsItsHead() {
		// A non-literal keyword (or a stray positional argument) is NOT quietly routed
		// somewhere: the call keeps its public head so the error names the built-in the
		// program actually wrote.
		assertThat(rewriteInDefun("(read-sequence seq s :junk 1)")).contains("(READ-SEQUENCE ");
		assertThat(rewriteInDefun("(write-sequence seq s :junk 1)")).contains("(WRITE-SEQUENCE ");
		assertThat(rewriteInDefun("(read-line s nil nil nil nil)")).contains("(READ-LINE ");
		// A list where no form goes is no call at all: a lexical variable named like a
		// stream built-in (legal, CLHS 11.1.2.1.2.1), a case key, a loop pattern. Read as
		// a call, (let ((close s)) ...) bound %io-close and left close unbound, and
		// (case k (close 1)) never matched close. The binding's value form is still code.
		assertThat(rewriteTopLevel("(let ((close s) (read-line (read-line s))) (list close read-line))"))
			.contains("(CLOSE S)", "(READ-LINE (RONTOLISP:AWAIT (RONTOLISP::%READ-LINE-FUTURE S)))")
			.doesNotContain("%IO-");
		assertThat(rewriteInDefun("(case s (close 1) ((listen) 2))")).doesNotContain("%IO-");
		assertThat(rewriteInDefun(
				"(dolist (close seq) (symbol-macrolet ((listen close)) (loop for (read-char) in seq collect listen)))"))
			.doesNotContain("%IO-");
	}

}
