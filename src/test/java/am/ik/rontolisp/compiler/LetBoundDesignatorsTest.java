package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LetBoundDesignatorsTest {

	private static final Set<String> KNOWN = Set.of("DBL", "HALVE", "CMP");

	private static String propagate(String source) {
		return propagate(source, Set.of());
	}

	private static String propagate(String source, Set<String> specialVars) {
		List<LispVal> forms = LispReader.readAllFromString(source);
		return LetBoundDesignators.propagate((LispCons) forms.get(0), specialVars, KNOWN).print();
	}

	/**
	 * Whether the pass handed back the very form it was given, cons identity included.
	 */
	private static boolean unchanged(String source) {
		List<LispVal> forms = LispReader.readAllFromString(source);
		LispCons letForm = (LispCons) forms.get(0);
		return LetBoundDesignators.propagate(letForm, Set.of(), KNOWN) == letForm;
	}

	@Test
	void aBindingUsedOnlyAsADesignatorIsPropagatedAndDropped() {
		assertThat(propagate("(let ((f #'dbl)) (mapcar f lst))")).isEqualTo("(LET NIL (MAPCAR #'DBL LST))");
		// The six positions the backends resolve, each reached through the binding.
		assertThat(propagate("(let ((f #'dbl)) (funcall f 1))")).isEqualTo("(LET NIL (FUNCALL #'DBL 1))");
		assertThat(propagate("(let ((f #'dbl)) (mapc f lst))")).isEqualTo("(LET NIL (MAPC #'DBL LST))");
		assertThat(propagate("(let ((f #'dbl)) (mapcan f lst))")).isEqualTo("(LET NIL (MAPCAN #'DBL LST))");
		assertThat(propagate("(let ((f #'dbl)) (reduce f lst))")).isEqualTo("(LET NIL (REDUCE #'DBL LST))");
		assertThat(propagate("(let ((f #'cmp)) (sort lst f))")).isEqualTo("(LET NIL (SORT LST #'CMP))");
		// A quoted designator is the same designator (FunctionDesignators.normalize).
		assertThat(propagate("(let ((f 'dbl)) (funcall f 1))")).isEqualTo("(LET NIL (FUNCALL #'DBL 1))");
		// Every use, however deep, and only the qualifying binding leaves.
		assertThat(propagate("(let ((f #'dbl) (n 2)) (if p (funcall f n) (mapcar f lst)))"))
			.isEqualTo("(LET ((N 2)) (IF P (FUNCALL #'DBL N) (MAPCAR #'DBL LST)))");
	}

	@Test
	void oneUseThatIsNotADesignatorPositionKeepsTheBinding() {
		// The value has to resolve, so the binding -- and the ladder case behind it --
		// stays. Cons identity too: a pass that changes nothing returns what it was
		// given (.kb/source-positions.md).
		assertThat(unchanged("(let ((f #'dbl)) (funcall f 1) (print f))")).isTrue();
		assertThat(unchanged("(let ((f #'dbl)) (apply f lst))")).isTrue();
		assertThat(unchanged("(let ((f #'dbl)) (funcall f 1) (setq f #'halve) (funcall f 2))")).isTrue();
		// A head-position occurrence is a Lisp-2 FUNCTION name, not this binding.
		assertThat(unchanged("(let ((f #'dbl)) (funcall f 1) (f 2))")).isTrue();
		// Never used at all: nothing to propagate, so nothing is rewritten.
		assertThat(unchanged("(let ((f #'dbl)) (print 1))")).isTrue();
	}

	@Test
	void aShadowingBindingKeepsIt() {
		// The inner binding's name is an occurrence the walk cannot certify, which is
		// exactly what must refuse the rewrite: substituting would rewrite a binding
		// name, or a use of a different variable.
		assertThat(unchanged("(let ((f #'dbl)) (let ((f #'halve)) (funcall f 1)))")).isTrue();
		assertThat(unchanged("(let ((f #'dbl)) (mapcar (lambda (f) (funcall f 1)) lst))")).isTrue();
		assertThat(unchanged("(let ((f #'dbl)) (dolist (f lst) (funcall f 1)))")).isTrue();
	}

	@Test
	void aFuncallShapedDatumKeepsIt() {
		// The one shape a shape-blind substitution would CORRUPT: (funcall f 1) as
		// data, not as a call. The count check refuses the binding instead.
		assertThat(unchanged("(let ((f #'dbl)) (funcall f 1) (print '(funcall f 1)))")).isTrue();
		assertThat(unchanged("(let ((f #'dbl)) (case x ((funcall f) 1) (t (funcall f 2))))")).isTrue();
		// ... while an ordinary clause body is propagated like any other position.
		assertThat(propagate("(let ((f #'dbl)) (case x (1 (funcall f 2))))"))
			.isEqualTo("(LET NIL (CASE X (1 (FUNCALL #'DBL 2))))");
	}

	@Test
	void aSpecialOrUnregisteredNameKeepsIt() {
		// A dynamic binding is one a CALLEE reads: dropping it would take the binding
		// away from everything the body calls.
		List<LispVal> forms = LispReader.readAllFromString("(let ((*f* #'dbl)) (funcall *f* 1))");
		LispCons letForm = (LispCons) forms.get(0);
		assertThat(LetBoundDesignators.propagate(letForm, Set.of("*F*"), KNOWN)).isSameAs(letForm);
		// A name no registry answers is not a plain funcId: #'cadr synthesizes a lambda
		// per site, and --dynamic resolves at run time. Duplicating either costs more
		// than the binding.
		assertThat(unchanged("(let ((f #'cadr)) (mapcar f lst))")).isTrue();
		assertThat(unchanged("(let ((f #'nowhere)) (mapcar f lst))")).isTrue();
		// A designator that is not literal at all -- the case the binding exists for.
		assertThat(unchanged("(let ((f (choose))) (mapcar f lst))")).isTrue();
		// Bound twice in one list: which one the body reads is not this pass's call.
		assertThat(unchanged("(let ((f #'dbl) (f #'halve)) (mapcar f lst))")).isTrue();
	}

}
