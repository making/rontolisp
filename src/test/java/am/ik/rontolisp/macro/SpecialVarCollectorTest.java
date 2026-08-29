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

}
