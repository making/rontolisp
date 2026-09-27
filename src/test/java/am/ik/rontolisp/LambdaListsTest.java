package am.ik.rontolisp;

import java.util.List;

import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape {@link LambdaLists} desugars a keyword lambda list into: one call per keyword
 * parameter and one check per function, against the two helper defuns the program carries
 * once. The semantics (defaults, supplied-p, the unknown-keyword and odd-tail
 * program-errors, {@code :allow-other-keys}) are pinned end to end on every backend by
 * {@code LispEvaluatorTest}, {@code JvmLispCompilerTest},
 * {@code WasmLispCompilerIntegrationTest} and the {@code lambda-list-*} /
 * {@code argument-shape-errors-signal-program-error} ci-spec cases; this class pins the
 * expansion those run on.
 */
class LambdaListsTest {

	private static List<LispVal> read(String src) {
		return LispReader.readAllFromString(src);
	}

	private static String printed(String src) {
		return LispReader.readFromString(src).print();
	}

	/** The compilers' parameter budget. */
	private static final int BUDGET = LambdaLists.MAX_PHYSICAL_PARAMS;

	private static List<LispVal> desugar(String src) {
		return LambdaLists.desugarProgram(read(src), BUDGET);
	}

	@Test
	void aKeywordParameterIsOneCallAndAFunctionOneCheck() {
		List<LispVal> out = desugar("(defun f (a &key b (c 1 cp)) (list a b c cp))");
		assertThat(out).hasSize(3);
		assertThat(out.get(0)).isEqualTo(LambdaLists.runtimeDefun(LispNames.LL_KEY_CELL));
		assertThat(out.get(1)).isEqualTo(LambdaLists.runtimeDefun(LispNames.LL_CHECK_KEYS));
		assertThat(out.get(2).print()).isEqualTo(printed("""
				(defun f (a &rest |__ll_rest|)
				  (let* ((|__ll_cell_B| (%ll-key-cell |__ll_rest| :b nil))
				         (b (if |__ll_cell_B| (car (cdr |__ll_cell_B|)) nil))
				         (|__ll_cell_C| (%ll-key-cell |__ll_rest| :c nil))
				         (cp (if |__ll_cell_C| t nil))
				         (c (if |__ll_cell_C| (car (cdr |__ll_cell_C|)) 1)))
				    (%ll-check-keys |__ll_rest| '(:b :c))
				    (list a b c cp)))
				"""));
	}

	@Test
	void anOptionalTailWithNoRestOrKeyChecksTheCountFirst() {
		// The optionals travel as parameters of their own, the rest list is only what is
		// past them -- so the bound is one test of it, checked BEFORE any default runs,
		// inline (no helper defun: a wrapper built while the backend compiles has
		// &optional too).
		List<LispVal> out = desugar("(defun f (a &optional (b 2) c) (list a b c))");
		assertThat(out).hasSize(1);
		assertThat(out.get(0).print()).isEqualTo(printed("""
				(defun f (a &optional |__ll_opt_0| |__ll_opt_1| &rest |__ll_rest|)
				  (let* ((|__ll_arity| (if |__ll_rest|
				                           (%program-error (%arity-surplus-message 3 3 |__ll_rest|))
				                           nil))
				         (b (if (%supplied-p |__ll_opt_0|) |__ll_opt_0| 2))
				         (c (if (%supplied-p |__ll_opt_1|) |__ll_opt_1| nil)))
				    (list a b c)))
				"""));
		// An &aux-only list stays FIXED arity, so the native count check covers it.
		assertThat(desugar("(defun g (a &aux (b 1)) (list a b))").get(0).print())
			.isEqualTo(printed("(defun g (a) (let* ((b 1)) (list a b)))"));
		// &rest and &key already consume every argument: no check.
		assertThat(desugar("(defun h (&optional a &rest r) (list a r))").get(0).print()).doesNotContain("__ll_arity");
	}

	@Test
	void theInterpretersShapeStepsTheOptionalsOffTheRestList() {
		// The interpreter binds a list of arguments, so its expansion keeps every
		// optional on the rest list and reads no UNSUPPLIED marker.
		LambdaLists.Expanded e = LambdaLists.expand(LispReader.readFromString("(a &optional (b 2) c)"),
				read("(list a b c)"), false);
		assertThat(e.optionals()).isEmpty();
		assertThat(e.rest()).isEqualTo(new LispSymbol("__ll_rest"));
		assertThat(e.body().get(0).print()).isEqualTo(printed("""
				(let* ((|__ll_arity| (if (cdr (cdr |__ll_rest|))
				                         (%program-error (%arity-surplus-message 3 1 |__ll_rest|))
				                         nil))
				       (b (if (consp |__ll_rest|) (car |__ll_rest|) 2))
				       (|__ll_rest| (if (consp |__ll_rest|) (cdr |__ll_rest|) nil))
				       (c (if (consp |__ll_rest|) (car |__ll_rest|) nil))
				       (|__ll_rest| (if (consp |__ll_rest|) (cdr |__ll_rest|) nil)))
				  (list a b c))
				"""));
	}

	@Test
	void aSuppliedPTakesTheTestAndARestParameterTheListPastTheOptionals() {
		assertThat(desugar("(defun f (&optional (a 1 ap) &rest r &key k) (list a ap r k))").get(2).print())
			.isEqualTo(printed("""
					(defun f (&optional |__ll_opt_0| &rest |__ll_rest|)
					  (let* ((ap (%supplied-p |__ll_opt_0|))
					         (a (if (%supplied-p |__ll_opt_0|) |__ll_opt_0| 1))
					         (r |__ll_rest|)
					         (|__ll_cell_K| (%ll-key-cell r :k nil))
					         (k (if |__ll_cell_K| (car (cdr |__ll_cell_K|)) nil)))
					    (%ll-check-keys r '(:k))
					    (list a ap r k)))
					"""));
	}

	@Test
	void theOptionalsPastTheParameterBudgetRideTheRestList() {
		// Eight required parameters leave one physical optional beside the rest list:
		// the second is stepped off the list, and the count check spans both.
		List<LispVal> out = desugar("(defun f (p1 p2 p3 p4 p5 p6 p7 p8 &optional (a 1) b) (list a b))");
		assertThat(out.get(0).print()).isEqualTo(printed("""
				(defun f (p1 p2 p3 p4 p5 p6 p7 p8 &optional |__ll_opt_0| &rest |__ll_rest|)
				  (let* ((|__ll_arity| (if (cdr |__ll_rest|)
				                           (%program-error (%arity-surplus-message 10 9 |__ll_rest|))
				                           nil))
				         (a (if (%supplied-p |__ll_opt_0|) |__ll_opt_0| 1))
				         (b (if (consp |__ll_rest|) (car |__ll_rest|) nil))
				         (|__ll_rest| (if (consp |__ll_rest|) (cdr |__ll_rest|) nil)))
				    (list a b)))
				"""));
		// With no room at all the lambda list keeps the interpreter's stepped shape.
		LambdaLists.NativeForm nine = LambdaLists
			.toNative(LispReader.readFromString("(p1 p2 p3 p4 p5 p6 p7 p8 p9 &optional a)"), read("a"), BUDGET);
		assertThat(nine.optionals()).isZero();
		assertThat(nine.paramNames()).hasSize(10);
	}

	@Test
	void aDesugaredLambdaListExpandsAsItIs() {
		// The compilers expand every lambda list again where they extract it: a list this
		// class wrote comes back with its physical optionals and its body untouched, so
		// the prologue is never wrapped twice.
		LispCons defun = (LispCons) desugar("(defun f (a &optional (b 2)) (list a b))").get(0);
		List<LispVal> parts = defun.toList();
		LambdaLists.NativeForm nf = LambdaLists.toNative(parts.get(2), parts.subList(3, parts.size()), BUDGET);
		assertThat(nf.paramNames()).containsExactly("A", "__ll_opt_0", "__ll_rest");
		assertThat(nf.variadic()).isTrue();
		assertThat(nf.optionals()).isEqualTo(1);
		assertThat(nf.required()).isEqualTo(1);
		assertThat(nf.body()).isEqualTo(parts.subList(3, parts.size()));
		assertThat(LambdaLists.desugarProgram(List.of(defun), BUDGET).get(0)).isEqualTo(defun);
	}

	@Test
	void aSuppliedPOnlyTestedIsTheTestItself() {
		// Every reference an if test: the binding goes, and each test reads the
		// physical parameter -- no boxed t built per call.
		assertThat(desugar("(defun f (&optional (a 1 ap)) (if ap (list a) nil))").get(0).print()).isEqualTo(printed("""
				(defun f (&optional |__ll_opt_0| &rest |__ll_rest|)
				  (let* ((|__ll_arity| (if |__ll_rest|
				                           (%program-error (%arity-surplus-message 1 1 |__ll_rest|))
				                           nil))
				         (a (if (%supplied-p |__ll_opt_0|) |__ll_opt_0| 1)))
				    (if (%supplied-p |__ll_opt_0|) (list a) nil)))
				"""));
		// A macro over it, a value use or an assignment keeps the binding.
		assertThat(desugar("(defun f (&optional (a 1 ap)) (when ap a))").get(0).print()).contains("(AP (%SUPPLIED-P");
		assertThat(desugar("(defun f (&optional (a 1 ap)) (if ap ap a))").get(0).print()).contains("(AP (%SUPPLIED-P");
		assertThat(desugar("(defun f (&optional (a 1 ap)) (setq ap nil) (if ap a 0))").get(0).print())
			.contains("(AP (%SUPPLIED-P");
	}

	@Test
	void aLocalFunctionsPhysicalLambdaListReachesTheInterpreterWithTheMarkerAsItsDefault() {
		// flet/labels expand before the backend is known, so their definitions take the
		// physical shape everywhere: the interpreter steps the list off its arguments
		// and binds a missing optional to UNSUPPLIED, which %supplied-p then reads.
		LambdaLists.Expanded physical = LambdaLists.expandPhysical(LispReader.readFromString("(a &optional (b 2))"),
				read("(list a b)"));
		assertThat(physical.optionals()).containsExactly(new LispSymbol("__ll_opt_0"));
		LispVal lambdaList = LispReader.readFromString("(a &optional |__ll_opt_0| &rest |__ll_rest|)");
		LambdaLists.Expanded stepped = LambdaLists.expand(lambdaList, physical.body(), false);
		assertThat(stepped.optionals()).isEmpty();
		LispCons prologue = (LispCons) stepped.body().get(0);
		LispCons firstBinding = (LispCons) ((LispCons) ((LispCons) prologue.cdr()).car()).car();
		assertThat(firstBinding.print()).startsWith("(|__ll_opt_0| (IF (CONSP |__ll_rest|) (CAR |__ll_rest|) ");
		assertThat(((LispCons) ((LispCons) firstBinding.cdr()).car()).toList().get(3)).isSameAs(LambdaLists.UNSUPPLIED);
	}

	@Test
	void aLowercaseAuthoredKeywordPassesItsUpcasedTwinAndTheCheckKnowsBoth() {
		// An internal lowercase-authored library's (&key foo) is called by user code
		// whose reader upcased the keyword: the cell scan takes either spelling, and the
		// check lists both -- so the literal, not the helper, carries the twin.
		LispVal form = new LispCons(new LispSymbol(LispNames.DEFUN),
				new LispCons(new LispSymbol("g"),
						new LispCons(
								new LispCons(new LispSymbol(LispNames.LAMBDA_KEY),
										new LispCons(new LispSymbol("foo"), LispNil.INSTANCE)),
								new LispCons(new LispSymbol("foo"), LispNil.INSTANCE))));
		List<LispVal> out = LambdaLists.desugarProgram(List.of(form), BUDGET);
		assertThat(out.get(2).print()).isEqualTo(printed("""
				(defun |g| (&rest |__ll_rest|)
				  (let* ((|__ll_cell_foo| (%ll-key-cell |__ll_rest| :|foo| :foo))
				         (|foo| (if |__ll_cell_foo| (car (cdr |__ll_cell_foo|)) nil)))
				    (%ll-check-keys |__ll_rest| '(:|foo| :foo))
				    |foo|))
				"""));
	}

	@Test
	void aBareKeySectionStillChecksAgainstAnEmptyList() {
		// &key with no parameter switches the tail to keyword convention (any keyword
		// tail is an error without &allow-other-keys), so the check runs over nil.
		List<LispVal> out = desugar("(defun h (x &key) x)");
		assertThat(out.get(2).print()).isEqualTo(printed("""
				(defun h (x &rest |__ll_rest|)
				  (let* ()
				    (%ll-check-keys |__ll_rest| nil)
				    x))
				"""));
	}

	@Test
	void allowOtherKeysDropsTheCheckAndKeepsTheScan() {
		List<LispVal> out = desugar("(defun h (&key a &allow-other-keys) a)");
		assertThat(out.get(2).print()).isEqualTo(printed("""
				(defun h (&rest |__ll_rest|)
				  (let* ((|__ll_cell_A| (%ll-key-cell |__ll_rest| :a nil))
				         (a (if |__ll_cell_A| (car (cdr |__ll_cell_A|)) nil)))
				    a))
				"""));
	}

	@Test
	void theHelpersArePrependedOnlyWhenTheProgramSpellsKey() {
		// A program without &key is returned form for form: the helpers would be two
		// dead defuns in every hello-world, and their absence is what keeps that module
		// byte-identical.
		List<LispVal> program = read("(defun f (a &optional b) (list a b)) (f 1)");
		List<LispVal> out = LambdaLists.desugarProgram(program, BUDGET);
		assertThat(out).hasSize(2);
		assertThat(out.get(1)).isSameAs(program.get(1));
		// A destructuring-bind or an flet spells &key where this pass does not rewrite,
		// and their expansions call the helpers while the backend compiles the body --
		// so the spelling alone, anywhere, brings the helpers in.
		List<LispVal> lazy = desugar("(defun f (l) (destructuring-bind (&key a) l a))");
		assertThat(lazy).hasSize(3);
		assertThat(lazy.get(0)).isEqualTo(LambdaLists.runtimeDefun(LispNames.LL_KEY_CELL));
		assertThat(lazy.get(1)).isEqualTo(LambdaLists.runtimeDefun(LispNames.LL_CHECK_KEYS));
	}

	@Test
	void theHelperBodiesAreTheScanAndTheCheck() {
		assertThat(LambdaLists.runtimeDefun(LispNames.LL_KEY_CELL).print()).isEqualTo(printed("""
				(defun %ll-key-cell (|__ll_plist| |__ll_key| |__ll_upper|)
				  (do ((|__ll_cur| |__ll_plist| (cddr |__ll_cur|)))
				      ((atom |__ll_cur|) nil)
				    (if (or (eq (car |__ll_cur|) |__ll_key|)
				            (and |__ll_upper| (eq (car |__ll_cur|) |__ll_upper|)))
				        (return |__ll_cur|)
				        nil)))
				"""));
		assertThat(LambdaLists.runtimeDefun(LispNames.LL_CHECK_KEYS).print()).isEqualTo(printed("""
				(defun %ll-check-keys (|__ll_plist| |__ll_known|)
				  (do ((|__ll_cur| |__ll_plist| (cddr |__ll_cur|)))
				      ((atom |__ll_cur|) nil)
				    (if (or (member (car |__ll_cur|) |__ll_known|)
				            (eq (car |__ll_cur|) :allow-other-keys)
				            (eq (car |__ll_cur|) :|allow-other-keys|)
				            (getf |__ll_plist| :allow-other-keys)
				            (getf |__ll_plist| :|allow-other-keys|))
				        (if (atom (cdr |__ll_cur|))
				            (%program-error (%string-concat "Odd number of keyword arguments: "
				                                            (%prin1-piece (car |__ll_cur|))))
				            nil)
				        (%program-error (%string-concat "Unknown keyword argument: "
				                                        (%prin1-piece (car |__ll_cur|)))))))
				"""));
		assertThat(LambdaLists.isRuntimeHelper(LispNames.LL_KEY_CELL)).isTrue();
		assertThat(LambdaLists.isRuntimeHelper(LispNames.LL_CHECK_KEYS)).isTrue();
		assertThat(LambdaLists.isRuntimeHelper("GETF")).isFalse();
	}

}
