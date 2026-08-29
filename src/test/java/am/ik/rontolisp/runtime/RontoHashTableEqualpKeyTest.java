package am.ik.rontolisp.runtime;

import java.math.BigInteger;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispEquality;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JVM backend's {@code equalp} key fold against the interpreter's.
 *
 * <p>
 * The two are written over different value models -- {@link LispEquality#equalpKey} over
 * {@code LispVal}, {@link RontoHashTable#equalpKey} over the JVM backend's Java
 * representation -- so they cannot be one function, and an {@code equalp} table places
 * the same keys on every backend only for as long as they answer the same fold. Each case
 * below folds ONE value in both models and asserts the two representatives are the same
 * value ({@code .kb/hash-tables.md}).
 */
class RontoHashTableEqualpKeyTest {

	@Test
	void aStringFoldsToUpperCaseInBothModels() {
		assertThat(interpreterFold(new LispString("Cs"))).isEqualTo(new LispString("CS"));
		assertThat(jvmFold("\"Cs\"")).isEqualTo("\"CS\"");
	}

	@Test
	void aSymbolIsItsOwnKeyInBothModels() {
		// A JVM symbol is a bare String and a Lisp string is a framed one: only the
		// framed one folds, or a lower-case symbol would be rewritten by a lookup.
		assertThat(interpreterFold(new LispSymbol("|Cs|"))).isEqualTo(new LispSymbol("|Cs|"));
		assertThat(jvmFold("Cs")).isEqualTo("Cs");
	}

	@Test
	void aCharacterFoldsToUpperCaseInBothModels() {
		assertThat(interpreterFold(new LispChar('a'))).isEqualTo(new LispChar('A'));
		assertThat(RontoHashTable.equalpKey(new int[] { 'a' }, RontoHashTable.FOLD_DEPTH_CAP))
			.isEqualTo(new int[] { 'A' });
	}

	@Test
	void anIntegerValuedFloatFoldsToThatIntegerInBothModels() {
		assertThat(interpreterFold(new LispDouble(1.0)).print()).isEqualTo("1");
		assertThat(jvmFold(Double.valueOf(1.0))).isEqualTo(Long.valueOf(1));
		assertThat(interpreterFold(new LispDouble(-0.0)).print()).isEqualTo("0");
		assertThat(jvmFold(Double.valueOf(-0.0))).isEqualTo(Long.valueOf(0));
		// Past the i64 tier the integer is exact in both models, not rounded.
		assertThat(interpreterFold(new LispDouble(1.0e20)).print()).isEqualTo("100000000000000000000");
		assertThat(jvmFold(Double.valueOf(1.0e20))).isEqualTo(new BigInteger("100000000000000000000"));
	}

	@Test
	void aFloatWithAFractionIsItsOwnKeyInBothModels() {
		// It does NOT fold to the ratio it equals: the WASM ratio cannot hold one, so
		// folding it here would split the backends rather than join them.
		assertThat(interpreterFold(new LispDouble(0.5))).isEqualTo(new LispDouble(0.5));
		assertThat(jvmFold(Double.valueOf(0.5))).isEqualTo(Double.valueOf(0.5));
		assertThat(interpreterFold(new LispDouble(Double.NaN)).print()).isEqualTo(new LispDouble(Double.NaN).print());
		assertThat(jvmFold(Double.valueOf(Double.NaN))).isEqualTo(Double.valueOf(Double.NaN));
	}

	@Test
	void aConsFoldsElementWiseInBothModels() {
		LispVal folded = interpreterFold(
				new LispCons(new LispString("Lu"), new LispCons(new LispDouble(2.0), LispNil.INSTANCE)));
		assertThat(folded.print()).isEqualTo("(\"LU\" 2)");
		Object[] pair = (Object[]) jvmFold(new Object[] { "\"Lu\"", new Object[] { Double.valueOf(2.0), null } });
		assertThat(pair[0]).isEqualTo("\"LU\"");
		assertThat(((Object[]) pair[1])[0]).isEqualTo(Long.valueOf(2));
	}

	@Test
	void theDepthCapStopsBothFoldsAtTheSameLevel() {
		// Past the cap a subtree is its own fold -- what a deep key loses is the case
		// insensitivity below level 64, never a false match -- and both models stop at
		// the same level, or one backend would place such a key differently.
		assertThat(RontoHashTable.FOLD_DEPTH_CAP).isEqualTo(LispEquality.HASH_DEPTH_CAP);
		int depth = LispEquality.HASH_DEPTH_CAP + 1;

		LispVal nested = new LispString("cs");
		for (int i = 0; i < depth; i++) {
			nested = new LispCons(nested, LispNil.INSTANCE);
		}
		assertThat(interpreterFold(nested).print()).contains("\"cs\"");

		Object deep = "\"cs\"";
		for (int i = 0; i < depth; i++) {
			deep = new Object[] { deep, null };
		}
		Object folded = jvmFold(deep);
		for (int i = 0; i < depth; i++) {
			folded = ((Object[]) folded)[0];
		}
		assertThat(folded).isEqualTo("\"cs\"");
	}

	@Test
	void theWorkBudgetStopsBothFoldsOnASharedGraphKey() {
		// The depth cap bounds the fold's HEIGHT and nothing about its SIZE: a DAG of 60
		// conses holds 60 cells and 2^60 root-to-leaf paths, and the fold does not merely
		// WALK those, it allocates one cons per path. Both models spend the same budget,
		// or one backend would place such a key differently. An un-budgeted fold would
		// not return at all, so a regression here is a HANG rather than a slow number.
		assertThat(RontoHashTable.FOLD_WORK_CAP).isEqualTo(LispEquality.HASH_WORK_CAP);

		LispVal dag = new LispString("cs");
		for (int i = 0; i < 60; i++) {
			dag = new LispCons(dag, dag);
		}
		assertThat(interpreterFold(dag)).isInstanceOf(LispCons.class);

		Object jvmDag = "\"cs\"";
		for (int i = 0; i < 60; i++) {
			jvmDag = new Object[] { jvmDag, jvmDag };
		}
		assertThat(jvmFold(jvmDag)).isInstanceOf(Object[].class);
	}

	private static LispVal interpreterFold(LispVal value) {
		return LispEquality.equalpKey(value);
	}

	private static Object jvmFold(Object value) {
		return RontoHashTable.equalpKey(value, RontoHashTable.FOLD_DEPTH_CAP);
	}

}
