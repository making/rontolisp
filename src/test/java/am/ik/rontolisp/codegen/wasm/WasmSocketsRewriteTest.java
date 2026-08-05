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

	// The rewrite is a no-op unless sockets.lisp is spliced; the splice marker is a
	// top-level (defun rontolisp::%io-read-line ...).
	private static final String MARKER = "(defun rontolisp::%io-read-line (&optional s) s)\n";

	private static String rewriteTopLevel(String source) {
		return rewritten(source).get(1).print();
	}

	// The same call inside a plain defun, where the sync dispatch defuns apply instead of
	// the async promotions.
	private static String rewriteInDefun(String source) {
		return rewritten("(defun probe (s seq) " + source + ")").get(1).print();
	}

	private static List<LispVal> rewritten(String source) {
		List<LispVal> program = new PackageResolver().resolveProgram(LispReader.readAllFromString(MARKER + source));
		return new ArrayList<>(WasmSocketsRewrite.rewrite(program));
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
	void anUnknownShapeIsLeftForThePublicNameToReport() {
		// A non-literal keyword (or a stray positional argument) is NOT quietly routed
		// somewhere: the call keeps its public head so the error names the built-in the
		// program actually wrote.
		assertThat(rewriteInDefun("(read-sequence seq s :junk 1)")).contains("(READ-SEQUENCE ");
		assertThat(rewriteInDefun("(write-sequence seq s :junk 1)")).contains("(WRITE-SEQUENCE ");
		assertThat(rewriteInDefun("(read-line s nil nil nil nil)")).contains("(READ-LINE ");
	}

}
