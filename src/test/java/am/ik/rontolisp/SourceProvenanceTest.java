package am.ik.rontolisp;

import java.util.List;

import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceProvenanceTest {

	@AfterEach
	void stopRecording() {
		SourceProvenance.stopRecording();
	}

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source, Features.JVM, "prog.lisp");
	}

	@Test
	void recordsNothingUntilAScopeIsOpened() {
		// The interpreter never opens one, so it pays nothing and its error text is
		// untouched -- the deliberate compile-path-only divergence.
		assertThat(SourceProvenance.isRecording()).isFalse();
		List<LispVal> forms = read("(print (+ 1 2))");
		assertThat(SourceProvenance.locate(forms.get(0))).isNull();
		assertThat(SourceProvenance.prefix(forms.get(0))).isEmpty();
	}

	@Test
	void recordsEveryConsWithItsOwnLineAndColumn() {
		SourceProvenance.startRecording();
		List<LispVal> forms = read("(defun f (x)\n  (+ x 1))\n\n(print (f 2))\n");
		assertThat(SourceProvenance.locate(forms.get(0))).isEqualTo(new SourceLocation("prog.lisp", 1, 1));
		assertThat(SourceProvenance.locate(forms.get(1))).isEqualTo(new SourceLocation("prog.lisp", 4, 1));
		// A NESTED form carries its own position, not the enclosing top-level form's:
		// that is what makes a frontend error inside a long defun point at the right
		// line.
		LispVal body = ((LispCons) ((LispCons) ((LispCons) forms.get(0)).cdr()).cdr()).cdr();
		assertThat(SourceProvenance.locate(((LispCons) body).car())).isEqualTo(new SourceLocation("prog.lisp", 2, 3));
	}

	@Test
	void atomsAreNotRecordedAndAMissingPositionIsNull() {
		SourceProvenance.startRecording();
		List<LispVal> forms = read("(print 1)");
		assertThat(SourceProvenance.locate(((LispCons) forms.get(0)).car())).isNull();
		assertThat(SourceProvenance.locate(new LispCons(LispNil.INSTANCE, LispNil.INSTANCE))).isNull();
	}

	@Test
	void stoppingTheScopeDropsTheTable() {
		SourceProvenance.startRecording();
		List<LispVal> forms = read("(print 1)");
		assertThat(SourceProvenance.locate(forms.get(0))).isNotNull();
		SourceProvenance.stopRecording();
		assertThat(SourceProvenance.locate(forms.get(0))).isNull();
	}

	@Test
	void theInnermostFrameToNoteALocationWins() {
		SourceProvenance.startRecording();
		List<LispVal> forms = read("(defun f (x)\n  (+ x 1))");
		LispVal inner = ((LispCons) ((LispCons) ((LispCons) ((LispCons) forms.get(0)).cdr()).cdr()).cdr()).car();
		RuntimeException failure = new IllegalStateException("boom");
		// Notes arrive innermost-first, the way an exception unwinds.
		SourceProvenance.noteFailure(inner, failure);
		SourceProvenance.noteFailure(forms.get(0), failure);
		assertThat(SourceProvenance.failureLocation(failure)).isEqualTo(new SourceLocation("prog.lisp", 2, 3));
	}

	@Test
	void aFrameWithNoRecordedPositionLeavesTheSlotForAnEnclosingOne() {
		SourceProvenance.startRecording();
		List<LispVal> forms = read("(defun f (x) x)");
		// A macro-generated cons: never read, so never recorded.
		LispVal generated = new LispCons(new LispSymbol("PROGN"), LispNil.INSTANCE);
		RuntimeException failure = new IllegalStateException("boom");
		SourceProvenance.noteFailure(generated, failure);
		SourceProvenance.noteFailure(forms.get(0), failure);
		assertThat(SourceProvenance.failureLocation(failure)).isEqualTo(new SourceLocation("prog.lisp", 1, 1));
	}

	@Test
	void aNewFailureDoesNotInheritTheLocationOfAnEarlierOne() {
		SourceProvenance.startRecording();
		List<LispVal> forms = read("(defun f (x) x)");
		RuntimeException recovered = new IllegalStateException("caught and recovered from");
		SourceProvenance.noteFailure(forms.get(0), recovered);
		RuntimeException fresh = new IllegalStateException("a different failure");
		assertThat(SourceProvenance.failureLocation(fresh)).isNull();
	}

	@Test
	void theTopLevelFormIsTheFallbackWhenNoFrameNotedALocation() {
		SourceProvenance.startRecording();
		List<LispVal> forms = read("(print 1)\n(print 2)");
		SourceProvenance.enterTopLevelForm(forms.get(1));
		// A pass with no hook at all: nothing was noted for this exception.
		assertThat(SourceProvenance.failureLocation(new IllegalStateException("boom")))
			.isEqualTo(new SourceLocation("prog.lisp", 2, 1));
	}

	@Test
	void noteFailureReturnsTheExceptionUnchanged() {
		// The pass hooks rethrow the SAME exception: a frontend pass that catches its own
		// exception types to fall back must still see the type it expects.
		SourceProvenance.startRecording();
		RuntimeException failure = new IllegalStateException("boom");
		assertThat(SourceProvenance.noteFailure(read("(print 1)").get(0), failure)).isSameAs(failure);
		SourceProvenance.stopRecording();
		assertThat(SourceProvenance.noteFailure(null, failure)).isSameAs(failure);
	}

	@Test
	void aReadWithNoOriginFileRecordsAPositionButNoPrefix() {
		// A REPL buffer has line/column but nothing worth naming, so the message stays
		// bare -- the same rule reader errors follow (phase 1).
		SourceProvenance.startRecording();
		List<LispVal> forms = LispReader.readAllFromString("(print 1)", Features.JVM);
		assertThat(SourceProvenance.locate(forms.get(0))).isEqualTo(new SourceLocation(null, 1, 1));
		assertThat(SourceProvenance.prefix(forms.get(0))).isEmpty();
	}

}
