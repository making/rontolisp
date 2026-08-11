package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pin for what a literal {@code (boundp 'name)} is worth at compile time: the answer
 * the top-level order fixes, the guard it collapses, and every position the order does
 * NOT fix.
 */
class CompileTimeBoundpTest {

	private static String folded(String source) {
		return folded(source, true);
	}

	private static String folded(String source, boolean packagesResolved) {
		StringBuilder out = new StringBuilder();
		List<LispVal> program = CompileTimeBoundp.fold(LispReader.readAllFromString(source), false, packagesResolved);
		program.forEach(form -> out.append(form.print()).append('\n'));
		return out.toString();
	}

	@Test
	void theDefineConstantGuardCollapsesToTheDefinitionItWraps() {
		// The portable redefinition-safe defconstant: the probe runs BEFORE the
		// definition two tokens later, so it is nil, and what is left is the top-level
		// definer the tree-shaker and the statement emitter can both act on.
		assertThat(folded("(unless (boundp '+k+) (defconstant +k+ 1)) (print +k+)"))
			.isEqualTo("(DEFCONSTANT +K+ 1)\n(PRINT +K+)\n");
	}

	@Test
	void theOtherDefineConstantSpellingLosesItsDeadSymbolValueArm() {
		// cl-ppcre / cl-who / flexi-streams / cl-base64 / cl-unicode all spell the guard
		// inside the initform. Deciding the probe is not enough there: the dead
		// (symbol-value 'NAME) arm is another usesEval OR-chain member, so the guard it
		// was the consequent of has to go with it.
		assertThat(folded("(defconstant +k+ (if (boundp '+k+) (symbol-value '+k+) 1)) (print +k+)"))
			.isEqualTo("(DEFCONSTANT +K+ 1)\n(PRINT +K+)\n");
		// alexandria reads the same probe through not; cl-json through and.
		assertThat(folded("(defparameter *v* (if (not (boundp '*v*)) 1 *v*))")).isEqualTo("(DEFPARAMETER *V* 1)\n");
		assertThat(folded("(defconstant +k+ (if (and (boundp '+k+) (foo)) (symbol-value '+k+) 1))"))
			.isEqualTo("(DEFCONSTANT +K+ 1)\n");
		// The collapse only ever fires on a form this pass rewrote, so a conditional the
		// PROGRAM wrote with a literal test is left exactly as it stands.
		assertThat(folded("(print (if nil 1 2))")).isEqualTo("(PRINT (IF NIL 1 2))\n");
	}

	@Test
	void aProbeAfterTheDefinitionIsTrueAndOneBeforeItIsNil() {
		assertThat(folded("(print (boundp '*v*)) (defvar *v* 1) (print (boundp '*v*))"))
			.isEqualTo("(PRINT NIL)\n(DEFVAR *V* 1)\n(PRINT T)\n");
		// The second guard for the same name is discharged the other way, and with it
		// the redefinition it was there to avoid.
		assertThat(folded("(unless (boundp '+k+) (defconstant +k+ 1)) (unless (boundp '+k+) (defconstant +k+ 2))"))
			.isEqualTo("(DEFCONSTANT +K+ 1)\n");
	}

	@Test
	void aNameNothingEverDefinesIsNilEvenInsideADeferredBody() {
		// A defun body runs whenever it is called, so its probe cannot read the position
		// it was written at -- but "never a global anywhere" has one answer at every
		// moment.
		assertThat(folded("(defun f () (boundp '*never*)) (print (f))")).isEqualTo("(DEFUN F NIL NIL)\n(PRINT (F))\n");
		// ... and a name the program DOES define keeps its probe: the call can happen on
		// either side of the definition.
		assertThat(folded("(defun f () (boundp '*late*)) (print (f)) (defvar *late* 1) (print (f))"))
			.contains("BOUNDP");
	}

	@Test
	void aDefinerThePositionDoesNotOrderKeepsItsProbe() {
		// Written after the probe but inside a loop, so it can also have run before it.
		assertThat(folded("(dotimes (i 2) (print (boundp '*x*)) (defvar *x* 1))")).contains("BOUNDP");
		// Evaluated before the probe in the same top-level form.
		assertThat(folded("(progn (defvar *x* 1) (print (boundp '*x*)))")).contains("BOUNDP");
		// A conditional definer binds nothing on the untaken branch, so a later probe is
		// not decidable either.
		assertThat(folded("(if (foo) (defvar *x* 1)) (print (boundp '*x*))")).contains("BOUNDP");
		// A let-bound name is lexical; the assignment never reaches the global.
		assertThat(folded("(let ((lex 1)) (setq lex 2)) (print (boundp 'lex))")).contains("BOUNDP");
	}

	@Test
	void aNameWhoseBoundnessThisCannotTrackIsLeftToRun() {
		// (defvar x) proclaims x special and binds nothing; a let of it then does.
		assertThat(folded("(defvar *s*) (print (boundp '*s*))")).contains("BOUNDP");
		assertThat(folded("(declaim (special *d*)) (print (boundp '*d*))")).contains("BOUNDP");
		// A cl symbol may be born bound -- which ones is the backends' business.
		assertThat(folded("(print (boundp '*standard-output*))")).contains("BOUNDP");
		// A computed designator is not a literal at all.
		assertThat(folded("(print (boundp (intern \"*X*\")))")).contains("BOUNDP");
		// A declaim that only declares a TYPE binds nothing, so it does not block the
		// guard that follows it (chipz's +crc32-table+).
		assertThat(folded("(declaim (type fixnum +k+)) (unless (boundp '+k+) (defconstant +k+ 1))"))
			.isEqualTo("(DECLAIM (TYPE FIXNUM +K+))\n(DEFCONSTANT +K+ 1)\n");
	}

	@Test
	void aRuntimeGlobalMakesTheWholeProgramUndecidable() {
		// eval / load / --dynamic can each make a global appear after the probe was
		// compiled -- and each of them forces the eval runtime on its own, so refusing
		// here costs nothing.
		assertThat(folded("(eval (read)) (unless (boundp '+k+) (defconstant +k+ 1))")).contains("BOUNDP");
		assertThat(folded("(load \"x.lisp\") (unless (boundp '+k+) (defconstant +k+ 1))")).contains("BOUNDP");
		assertThat(CompileTimeBoundp
			.fold(LispReader.readAllFromString("(unless (boundp '+k+) (defconstant +k+ 1))"), true, true)
			.toString()).contains("BOUNDP");
	}

	@Test
	void beforePackageResolutionOnlyTheUnboundDirectionIsDecided() {
		// Two packages can spell one short name, so a definer found under that name may
		// belong to the other one: it may BLOCK a fold, never assert a binding.
		assertThat(folded("(unless (boundp '+k+) (defconstant +k+ 1))", false)).isEqualTo("(DEFCONSTANT +K+ 1)\n");
		assertThat(folded("(defvar *v* 1) (print (boundp '*v*))", false)).contains("BOUNDP");
		assertThat(folded("(defvar chipz::*v* 1) (print (boundp 'yason::*v*))", false)).contains("BOUNDP");
		assertThat(folded("(defvar chipz::*v* 1) (print (boundp 'yason::*v*))", true))
			.isEqualTo("(DEFVAR CHIPZ::*V* 1)\n(PRINT NIL)\n");
	}

	@Test
	void theSelfBoundLiteralsAnswerWithoutLookingAnythingUp() {
		assertThat(folded("(print (boundp nil)) (print (boundp t)) (print (boundp :kw)) (print (boundp ':kw))"))
			.isEqualTo("(PRINT T)\n(PRINT T)\n(PRINT T)\n(PRINT T)\n");
	}

	@Test
	void aProgramWithNothingToDecideIsHandedBackUnchanged() {
		// The cons-identity rule (.kb/source-positions.md): a pass that changes nothing
		// returns the object it was given, or every position below the top level is lost.
		List<LispVal> program = LispReader.readAllFromString("(defun f (x) (+ x 1)) (print (f 1))");
		assertThat(CompileTimeBoundp.fold(program, false, true)).isSameAs(program);
	}

}
