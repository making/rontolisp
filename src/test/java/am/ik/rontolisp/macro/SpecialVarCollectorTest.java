package am.ik.rontolisp.macro;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpecialVarCollectorTest {

	/**
	 * A {@code progv} binds a RUNTIME-computed list of symbols, so
	 * {@link SpecialVarCollector#collectDynamicallyBound} cannot name what it touches and
	 * falls back to over-collecting EVERY special of the program. That fallback is a bulk
	 * {@code addAll} of the caller's set, and the order the names land in is the order
	 * both compilers mint their global fields in -- so it must be a function of the
	 * PROGRAM, never of the JVM run. It was not: the seeded stream specials were handed
	 * over as a {@code Set.of}, whose iteration order {@code ImmutableCollections}
	 * re-scrambles from {@code System.nanoTime()} once per process, and eight compiles of
	 * this program with one unmodified build produced three different classes --
	 * {@code _g$*ERROR-OUTPUT*} and {@code _g$*STANDARD-INPUT*} swapping places and every
	 * later constant-pool index shifting with them (.kb/emitted-output-determinism.md).
	 * The order asserted below is the declaration order of
	 * {@code SpecialVarCollector.SEEDED_STREAM_SPECIALS}; only a fixed-order assertion
	 * like this one can catch a salted collection, because the salt is constant for the
	 * lifetime of a process and two calls inside one JVM therefore always agree.
	 */
	@Test
	void aProgvProgramSeedsTheStreamSpecialsInAFixedOrder() {
		List<LispVal> program = LispReader.readAllFromString("""
				(defvar *a* 1)
				(defun f (syms vals)
				  (progv syms vals
				    (print *a*)))
				""");
		assertThat(SpecialVarCollector.collect(program)).containsExactly("*A*", "*STANDARD-OUTPUT*", "*STANDARD-INPUT*",
				"*ERROR-OUTPUT*");
	}

	/**
	 * The contract that makes the assertion above hold for every caller: the
	 * over-collection arm emits the specials in the ITERATION ORDER OF THE ARGUMENT, so
	 * passing an unordered set is what breaks determinism. The parameter type is
	 * {@link SequencedSet}, which no {@code Set.of} satisfies, so the compiler now
	 * refuses the shape that caused the bug -- this test pins the promise that type
	 * makes.
	 */
	@Test
	void theProgvOverCollectionArmEmitsTheSpecialsInTheArgumentOrder() {
		List<LispVal> program = LispReader.readAllFromString("(defun f (s v) (progv s v (list *x* *y* *z*)))");
		SequencedSet<String> declared = new LinkedHashSet<>(List.of("*X*", "*Y*", "*Z*"));
		assertThat(SpecialVarCollector.collectDynamicallyBound(program, declared)).containsExactly("*X*", "*Y*", "*Z*");
		SequencedSet<String> reversed = new LinkedHashSet<>(List.of("*Z*", "*Y*", "*X*"));
		assertThat(SpecialVarCollector.collectDynamicallyBound(program, reversed)).containsExactly("*Z*", "*Y*", "*X*");
	}

	/**
	 * A parameter named like a special binds it dynamically, as a let would -- every
	 * section of a lambda list, a supplied-p variable, a default form's own bindings, in
	 * lambda-list order, and the lambdas an {@code flet} expansion builds -- so the JVM
	 * gives each a thread-local store before {@code LambdaLists.toNative} lowers the
	 * parameter into a special let. A stream special named as a parameter is special from
	 * then on, exactly as a let of it makes it: the interpreter binds that parameter
	 * dynamically too.
	 */
	@Test
	void aParameterNamedLikeASpecialIsADynamicBinding() {
		List<LispVal> program = LispReader.readAllFromString("""
				(defun f (*a* &optional (*b* (let ((*y* 1)) *y*) *bp*) &rest *r* &key ((:k *k*)) &aux (*x* 2))
				  (mapcar (lambda (*z*) *z*) (list *a* *b* *r* *k* *x*)))
				(flet ((g (*w*) *w*)) (g 1))
				""");
		SequencedSet<String> specials = new LinkedHashSet<>(
				List.of("*A*", "*B*", "*BP*", "*R*", "*K*", "*X*", "*Y*", "*Z*", "*W*", "*NEVER-BOUND*"));
		assertThat(SpecialVarCollector.collectDynamicallyBound(program, specials)).containsExactly("*A*", "*B*", "*Y*",
				"*BP*", "*R*", "*K*", "*X*", "*Z*", "*W*");
		assertThat(SpecialVarCollector.collect(LispReader.readAllFromString("(defun f (*standard-output*) (print 1))")))
			.containsExactly("*STANDARD-OUTPUT*");
	}

	/**
	 * A local {@code (declare (special ...))} is no proclamation: {@code collectForm}
	 * (the interpreter's special set) answers the proclaimed names alone, and
	 * {@code collectLocallyDeclaredOnly} the names only local declarations name -- the
	 * names whose other bindings are lexical, which the compile paths rename apart. A
	 * proclaimed name and a {@code cl} symbol are never one. {@code collect}, the compile
	 * paths' set after that renaming, still holds both kinds, in declaration order.
	 */
	@Test
	void aLocalSpecialDeclarationIsNoProclamation() {
		List<LispVal> program = LispReader.readAllFromString("""
				(defvar *p* 1)
				(defun f (x) (declare (special x *p* *print-base*)) (let ((y 2)) (declare (special y)) (g)))
				(defun h () '(declare (special quoted)))
				""");
		java.util.Set<String> proclaimed = new LinkedHashSet<>();
		program.forEach(form -> SpecialVarCollector.collectForm(form, proclaimed));
		assertThat(proclaimed).containsExactly("*P*");
		assertThat(SpecialVarCollector.collectLocallyDeclaredOnly(program)).containsExactly("X", "Y");
		assertThat(SpecialVarCollector.collect(program)).containsExactly("*P*", "X", "*PRINT-BASE*", "Y");
	}

	/**
	 * A special the program actually let-binds still comes first, in walk order, ahead of
	 * the ones the {@code progv} fallback sweeps up -- the fallback only tops the set up,
	 * it does not reorder what the static walk already found.
	 */
	@Test
	void aStaticallyBoundSpecialKeepsItsWalkOrderAheadOfTheProgvFallback() {
		List<LispVal> program = LispReader
			.readAllFromString("(defun f (s v) (let ((*z* 1)) (progv s v (list *x* *y* *z*))))");
		SequencedSet<String> declared = new LinkedHashSet<>(List.of("*X*", "*Y*", "*Z*"));
		assertThat(SpecialVarCollector.collectDynamicallyBound(program, declared)).containsExactly("*Z*", "*X*", "*Y*");
	}

	/**
	 * The specials whose bound-ness the compile paths keep in their variable: a probed
	 * one declared without a value -- a {@code (defvar *x*)} or a {@code special}
	 * declaration -- and never given one by a definer. A literal probe names one; a
	 * computed probe can name any of them; a {@code cl} symbol, quoted data and a
	 * self-evaluating argument never count.
	 */
	@Test
	void aProbedSpecialWithoutADefinersValueCarriesItsBoundnessInItsVariable() {
		SequencedSet<String> specials = new LinkedHashSet<>(
				List.of("*A*", "*B*", "*C*", "*D*", "*E*", "*PRINT-BASE*", "*STANDARD-OUTPUT*"));
		List<LispVal> literal = LispReader.readAllFromString("""
				(defvar *a*)
				(defvar *b* nil)
				(defparameter *c* 1)
				(declaim (special *d*))
				(defun p () (list (boundp '*a*) (boundp '*b*) (boundp '*c*) (boundp '*d*) (boundp '*print-base*)
				                  '(boundp '*e*)))
				""");
		assertThat(SpecialVarCollector.collectProbedValueless(literal, specials)).containsExactly("*A*", "*D*");
		List<LispVal> computed = LispReader.readAllFromString("""
				(defvar *a*)
				(defvar *b* nil)
				(defun p (s) (boundp s))
				(defun q () (when (cond) (defvar *e* 2)))
				""");
		assertThat(SpecialVarCollector.collectProbedValueless(computed, specials)).containsExactly("*A*", "*C*", "*D*");
		List<LispVal> unprobed = LispReader.readAllFromString("""
				(defvar *a*)
				(defun p () (list *a* '(boundp x) (symbol-value '*a*) (boundp :k) (boundp nil) (boundp t) (boundp 1)))
				""");
		assertThat(SpecialVarCollector.collectProbedValueless(unprobed, specials)).isEmpty();
	}

	/**
	 * What the eval gate reads before the runtime is injected: the probed specials
	 * without a value that something binds, and whether any {@code boundp} reaches past
	 * them to the mirror -- every occurrence of the symbol but a literal probe of one of
	 * them, quoted data, {@code #'boundp} and a macro template included.
	 */
	@Test
	void theEvalGateReadsWhetherEveryBoundpIsAProbeOfABoundSpecialWithoutAValue() {
		SequencedSet<String> specials = new LinkedHashSet<>(List.of("*A*", "*B*", "*C*"));
		List<LispVal> program = LispReader.readAllFromString("""
				(defvar *a*)
				(defvar *b*)
				(defvar *c* 1)
				(defun p () (list (boundp '*a*) (boundp '*b*)))
				(let ((*a* 1)) (p))
				""");
		SequencedSet<String> tracked = SpecialVarCollector.collectProbedValuelessBound(program, List.of(), specials,
				false);
		assertThat(tracked).containsExactly("*A*");
		assertThat(SpecialVarCollector.collectProbedValuelessBound(program, List.of(), specials, true))
			.containsExactly("*A*", "*B*");
		assertThat(LispMacroExpander.boundpReachesMirror(program, tracked)).isTrue();
		assertThat(LispMacroExpander.boundpReachesMirror(program, List.of("*A*", "*B*"))).isFalse();
		for (String other : List.of("(boundp '*c*)", "(boundp x)", "'(boundp '*b*)", "#'boundp", "(funcall 'boundp x)",
				"`(boundp ',x)", "(boundp '*a* 1)", "(flet ((boundp (x) x)) x)")) {
			assertThat(LispMacroExpander.boundpReachesMirror(LispReader.readAllFromString("(boundp '*a*) " + other),
					List.of("*A*")))
				.as(other)
				.isTrue();
		}
		assertThat(LispMacroExpander.boundpReachesMirror(
				LispReader.readAllFromString("(boundp '*a*) (list (boundp '*a*) '(boundp '*a*))"), List.of("*A*")))
			.isFalse();
	}

}
