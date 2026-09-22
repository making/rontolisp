package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pin for the byte-buffer narrowing of {@code read-sequence} / {@code write-sequence}
 * (.todo/338): a sequence proven not to be a string takes the byte arm directly, so the
 * dead character arm (and with it the {@code read-char} runtime on WASM) leaves the
 * artifact. Every case the analysis cannot decide keeps the shared runtime-tested
 * expansion exactly as it was.
 */
class SequenceIoNarrowingTest {

	private static String narrowed(String source) {
		List<LispVal> program = SequenceIoNarrowing.narrow(LispReader.readAllFromString(source), false, false);
		StringBuilder out = new StringBuilder();
		program.forEach(form -> out.append(form.print()).append('\n'));
		return out.toString();
	}

	@Test
	void aLetBoundByteBufferDropsTheCharacterArm() {
		String result = narrowed("""
				(let ((buf (make-array 8 :element-type '(unsigned-byte 8))))
				  (read-sequence buf *standard-input*))
				""");
		assertThat(result).doesNotContain("(READ-SEQUENCE ").doesNotContain("(READ-CHAR ").contains("(READ-BYTE ");
	}

	@Test
	void aLetBoundByteBufferDropsTheWriteStringBranch() {
		String result = narrowed("""
				(let ((buf (make-array 8 :element-type '(unsigned-byte 8))))
				  (write-sequence buf *standard-output*))
				""");
		assertThat(result).doesNotContain("(WRITE-SEQUENCE ").doesNotContain("(WRITE-STRING ").contains("(WRITE-BYTE ");
	}

	@Test
	void aDirectByteBufferFormNarrowsWithoutABinding() {
		String result = narrowed("""
				(read-sequence (make-array 8 :element-type '(unsigned-byte 8)) *standard-input*)
				""");
		assertThat(result).doesNotContain("(READ-SEQUENCE ").doesNotContain("(READ-CHAR ").contains("(READ-BYTE ");
	}

	@Test
	void aStringBufferKeepsTheRuntimeTest() {
		String result = narrowed("""
				(let ((buf (make-string 8)))
				  (read-sequence buf *standard-input*))
				""");
		assertThat(result).contains("(READ-SEQUENCE ");
	}

	@Test
	void aCharacterBufferKeepsTheRuntimeTest() {
		String result = narrowed("""
				(let ((buf (make-array 8 :element-type 'character)))
				  (read-sequence buf *standard-input*))
				""");
		assertThat(result).contains("(READ-SEQUENCE ");
	}

	@Test
	void aParameterKeepsTheRuntimeTest() {
		String result = narrowed("""
				(defun f (buf) (read-sequence buf *standard-input*))
				""");
		assertThat(result).contains("(READ-SEQUENCE ");
	}

	@Test
	void aReboundBufferKeepsTheRuntimeTest() {
		String result = narrowed("""
				(let ((buf (make-array 8 :element-type '(unsigned-byte 8))))
				  (setq buf "str")
				  (read-sequence buf *standard-input*))
				""");
		assertThat(result).contains("(READ-SEQUENCE ");
	}

	@Test
	void aCapturedBufferKeepsTheRuntimeTest() {
		String result = narrowed("""
				(let ((buf (make-array 8 :element-type '(unsigned-byte 8))))
				  (funcall (lambda () (setq buf "str")))
				  (read-sequence buf *standard-input*))
				""");
		assertThat(result).contains("(READ-SEQUENCE ");
	}

	// With a character stream possible, a buffer of element type t may receive
	// characters: only a buffer that cannot hold one stays proven byte-only.
	@Test
	void withCharacterStreamsAnUntypedBufferKeepsTheRuntimeTest() {
		List<LispVal> program = SequenceIoNarrowing.narrow(LispReader.readAllFromString("""
				(let ((buf (make-array 8)) (v (vector 0 0)))
				  (read-sequence buf s)
				  (write-sequence v s))
				"""), true, false);
		String result = program.get(0).print();
		assertThat(result).contains("(READ-SEQUENCE ").contains("(WRITE-SEQUENCE ");
	}

	@Test
	void withCharacterStreamsANumericBufferStillNarrows() {
		List<LispVal> program = SequenceIoNarrowing.narrow(LispReader.readAllFromString("""
				(let* ((buf (make-array 8 :element-type '(unsigned-byte 8))) (part (subseq buf 0 4)))
				  (read-sequence buf s)
				  (write-sequence part s))
				"""), true, false);
		String result = program.get(0).print();
		assertThat(result).doesNotContain("(READ-SEQUENCE ").doesNotContain("(WRITE-SEQUENCE ");
	}

	@Test
	void aShadowedBufferKeepsTheRuntimeTest() {
		String result = narrowed("""
				(let ((buf (make-array 8 :element-type '(unsigned-byte 8))))
				  (let ((buf "str"))
				    (read-sequence buf *standard-input*)))
				""");
		assertThat(result).contains("(READ-SEQUENCE ");
	}

}
