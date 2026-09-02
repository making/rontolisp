package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pin for what a top-level form owes when nobody reads its value: a form that IS a
 * constant is deleted, a form that could signal is not, and the defvar family is
 * recognized so both backends can compile it for effect.
 */
class ToplevelStatementsTest {

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

	private static String pruned(String source) {
		StringBuilder out = new StringBuilder();
		ToplevelStatements.prune(read(source)).forEach(form -> out.append(form.print()).append('\n'));
		return out.toString();
	}

	@Test
	void theQuotedSymbolAnInPackageDirectiveResolvesToIsDropped() {
		// What PackageResolver leaves behind for every (in-package ...) / (defpackage
		// ...) in every file of a quickloaded system -- 40 of the 71 string builds the
		// zlib top level used to make.
		assertThat(pruned("'chipz\n(print 1)\n")).isEqualTo("(PRINT 1)\n");
	}

	@Test
	void nilAndSelfEvaluatingLiteralsAreDropped() {
		assertThat(pruned("nil\nt\n1\n1.5\n1/2\n#\\a\n\"doc\"\n:key\n(print 1)\n")).isEqualTo("(PRINT 1)\n");
	}

	@Test
	void aBareSymbolIsKept() {
		// Evaluating an unbound variable signals, and signalling is an effect.
		assertThat(pruned("*undefined*\n")).isEqualTo("*UNDEFINED*\n");
	}

	@Test
	void aCallIsKept() {
		// Even one whose arguments are all constants: whether the call itself is pure is
		// PureBuiltinFolder's question, and anything it answers arrives here folded.
		assertThat(pruned("(car '(1 2))\n")).isEqualTo("(CAR '(1 2))\n");
	}

	@Test
	void theOrderAndIdentityOfEverySurvivingFormIsUntouched() {
		List<LispVal> program = read("'a\n(print 1)\nnil\n(print 2)\n");
		List<LispVal> kept = ToplevelStatements.prune(program);
		// Same objects, not copies: source positions are keyed by cons identity.
		assertThat(kept).hasSize(2);
		assertThat(kept.get(0)).isSameAs(program.get(1));
		assertThat(kept.get(1)).isSameAs(program.get(3));
	}

	@Test
	void theDefvarFamilyIsRecognizedWithItsRebindFlag() {
		LispVal defvar = read("(defvar *a* 1)").get(0);
		LispVal defparameter = read("(defparameter *b* 2)").get(0);
		LispVal defconstant = read("(defconstant +c+ 3)").get(0);
		assertThat(ToplevelStatements.isNameValuedDefiner(defvar)).isTrue();
		assertThat(ToplevelStatements.isNameValuedDefiner(defparameter)).isTrue();
		assertThat(ToplevelStatements.isNameValuedDefiner(defconstant)).isTrue();
		// defvar binds only when unbound; the other two always rebind.
		assertThat(ToplevelStatements.definerRebinds(defvar)).isFalse();
		assertThat(ToplevelStatements.definerRebinds(defparameter)).isTrue();
		assertThat(ToplevelStatements.definerRebinds(defconstant)).isTrue();
	}

	@Test
	void aDefinerWithoutASymbolNameIsLeftToTheCompilersToReject() {
		assertThat(ToplevelStatements.isNameValuedDefiner(read("(defvar)").get(0))).isFalse();
		assertThat(ToplevelStatements.isNameValuedDefiner(read("(defvar 1 2)").get(0))).isFalse();
	}

	@Test
	void anOrdinaryFormIsNotADefiner() {
		assertThat(ToplevelStatements.isNameValuedDefiner(read("(setq *a* 1)").get(0))).isFalse();
		assertThat(ToplevelStatements.isNameValuedDefiner(read("nil").get(0))).isFalse();
	}

}
