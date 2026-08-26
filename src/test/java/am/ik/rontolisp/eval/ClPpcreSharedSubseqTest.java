package am.ik.rontolisp.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ClPpcreSharedSubseqTest {

	private static final String UTIL = """
			(in-package :cl-ppcre)

			(declaim (inline nsubseq))
			(defun nsubseq (sequence start &optional (end (length sequence)))
			  "Returns a subsequence by pointing to location in original sequence."
			  (make-array (- end start)
			              :element-type (array-element-type sequence)
			              :displaced-to sequence
			              :displaced-index-offset start))

			(defun normalize-var-list (var-list)
			  (loop for element in var-list collect element))
			""";

	@Test
	void nsubseqCopiesInsteadOfDisplacingIntoTheString() {
		String rewritten = ShimLibraries.rewriteComponentSource("cl-ppcre", "util.lisp", UTIL, "sw/cl-ppcre",
				path -> UTIL);
		assertThat(rewritten).doesNotContain(":displaced-to").contains("(subseq sequence start end)");
		// The declaim, the docstring and everything else in the file stay verbatim.
		assertThat(rewritten).contains("(declaim (inline nsubseq))")
			.contains("\"Returns a subsequence by pointing to location in original sequence.\"")
			.contains("(defun normalize-var-list (var-list)");
	}

	@Test
	void everyOtherComponentIsUntouched() {
		String api = "(in-package :cl-ppcre)\n(defun scan (regex target) (values regex target))\n";
		assertThat(ShimLibraries.rewriteComponentSource("cl-ppcre", "api.lisp", api, "sw/cl-ppcre", path -> api))
			.isEqualTo(api);
	}

	@Test
	void aReleaseThatStopsDisplacingFailsLoudly() {
		// A silent fallback would put the signalling nsubseq back and point at nothing:
		// the failure would surface as MAKE-ARRAY refusing a string, inside whichever
		// cl-ppcre entry point first shared a substring.
		String moved = UTIL.replace(":displaced-index-offset start", ":displaced-index-offset (+ start 0)");
		assertThatIllegalStateException()
			.isThrownBy(() -> ShimLibraries.rewriteComponentSource("cl-ppcre", "util.lisp", moved, "sw/cl-ppcre",
					path -> moved))
			.withMessageContaining("no longer defines nsubseq as a displaced array");
	}

}
