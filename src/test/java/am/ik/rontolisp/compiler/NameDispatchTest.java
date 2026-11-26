package am.ik.rontolisp.compiler;

import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the compilers take for a name dispatch, and the search they emit over its keys.
 */
class NameDispatchTest {

	private static @Nullable NameDispatch match(String source) {
		return NameDispatch.match((LispCons) LispReader.readAllFromString(source).get(0));
	}

	private static NameDispatch dispatch(String source) {
		return Objects.requireNonNull(match(source));
	}

	@Test
	void theChainOverOneVariableIsTheDispatchAndARepeatedNameIsDead() {
		// The chain's tests stop at the first match, so the second arm for A can never
		// run; the miss is the first form that is not a test of the same variable.
		NameDispatch d = dispatch("(if (%symbol-is n 'a) 1 (if (%symbol-is n 'b) 2 (if (%symbol-is n 'a) 3"
				+ " (if (%symbol-is m 'c) 4 5))))");
		assertThat(d.subject().name()).isEqualTo("N");
		assertThat(d.names()).containsExactly("A", "B");
		assertThat(d.arms()).extracting(arm -> arm.print()).containsExactly("1", "2");
		assertThat(d.miss().print()).isEqualTo("(IF (%SYMBOL-IS M 'C) 4 5)");
	}

	@Test
	void anIfWithoutElseMissesWithNil() {
		NameDispatch d = dispatch("(if (%symbol-is n 'a) 1)");
		assertThat(d.miss().print()).isEqualTo("NIL");
	}

	@Test
	void anyOtherTestIsNoDispatch() {
		assertThat(match("(if (eq n 'a) 1 2)")).isNull();
		assertThat(match("(if (%symbol-is (car n) 'a) 1 2)")).isNull();
		assertThat(match("(if (%symbol-is n \"a\") 1 2)")).isNull();
	}

	@Test
	void theSearchHalvesDownToLeavesAndNeverSplitsEqualKeys() {
		NameDispatch.Node root = NameDispatch.searchTree(new long[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 2);
		assertThat(root).isEqualTo(new NameDispatch.Split(5,
				new NameDispatch.Split(3, new NameDispatch.Leaf(0, 2), new NameDispatch.Leaf(2, 4)),
				new NameDispatch.Split(7, new NameDispatch.Leaf(4, 6), new NameDispatch.Leaf(6, 8))));
		// Equal keys (colliding hashes) stay in one leaf, whatever its size.
		assertThat(NameDispatch.searchTree(new long[] { 1, 2, 2, 2, 2, 3 }, 2))
			.isEqualTo(new NameDispatch.Split(2, new NameDispatch.Leaf(0, 1),
					new NameDispatch.Split(3, new NameDispatch.Leaf(1, 5), new NameDispatch.Leaf(5, 6))));
		assertThat(NameDispatch.searchTree(new long[] { 4, 4, 4, 4 }, 1)).isEqualTo(new NameDispatch.Leaf(0, 4));
	}

}
