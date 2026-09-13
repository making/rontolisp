package am.ik.rontolisp.codegen.wasm;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The radix depth a paged dispatcher is built to, and the funcId range it accepts.
 *
 * <p>
 * Both exist because an unreachable bound must fail rather than hang: counting the depth
 * by shifting the funcId 8 more bits per round never terminates for an id of {@code 2^24}
 * or more, since Java takes a shift distance mod 32 -- the fourth round shifts by 0 and
 * reads the id straight back. A full {@code ./mvnw test} lost two workers to that loop
 * for 2223 s of CPU each before the count was closed-form
 * ({@code .kb/wasm-function-body-size.md}).
 */
class WasmDispatchPagingTest {

	@Test
	void levelCountTerminatesForEveryFuncId() {
		assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			assertThat(WasmRuntimeBuilder.dispatchLevels(0)).isEqualTo(1);
			assertThat(WasmRuntimeBuilder.dispatchLevels(255)).isEqualTo(1);
			assertThat(WasmRuntimeBuilder.dispatchLevels(256)).isEqualTo(2);
			assertThat(WasmRuntimeBuilder.dispatchLevels(2978)).isEqualTo(2);
			assertThat(WasmRuntimeBuilder.dispatchLevels((1 << 16) - 1)).isEqualTo(2);
			assertThat(WasmRuntimeBuilder.dispatchLevels(1 << 16)).isEqualTo(3);
			// The two that used to spin: a funcId needing a fourth 8-bit digit.
			assertThat(WasmRuntimeBuilder.dispatchLevels(1 << 24)).isEqualTo(4);
			assertThat(WasmRuntimeBuilder.dispatchLevels(Integer.MAX_VALUE)).isEqualTo(4);
		});
	}

	@Test
	void aNegativeFuncIdCannotBePagedAndSaysSo() {
		assertTimeoutPreemptively(Duration.ofSeconds(10),
				() -> assertThatThrownBy(() -> WasmRuntimeBuilder.dispatchLevels(-1))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("-1"));
	}

	@Test
	void aSparseLadderSelectsByComparisonAndADenseOneByTable() {
		// 417 lambdas of which one or two have arity 1, at the far end of the id space,
		// so the arity-1 ladder is sparse: a br_table over [0, 416] would spend a label
		// on every hole (zlib's arity-0 ladder: 517 bytes for one callable). One live id
		// is a one-label table biased to it; two ids 316 apart are a comparison chain;
		// six lambdas at 0..5 keep the plain six-label table. Ids are i64 constants, so
		// the tree shaker cannot read one as an address into the string blob.
		WasmLispCompiler.StringTable st = new WasmLispCompiler.StringTable(0, false, false);
		byte[] one = WasmRuntimeBuilder.buildDispatchBody(1, List.of(), lambdas(417, java.util.Set.of(416)), 0, st,
				false, 0);
		byte[] two = WasmRuntimeBuilder.buildDispatchBody(1, List.of(), lambdas(417, java.util.Set.of(100, 416)), 0, st,
				false, 0);
		byte[] dense = WasmRuntimeBuilder.buildDispatchBody(1, List.of(),
				lambdas(6, java.util.Set.of(0, 1, 2, 3, 4, 5)), 0, st, false, 0);

		// i64.extend_i32_u; i64.const 416; i64.sub; i32.wrap_i64; br_table 1
		assertThat(indexOf(one, new byte[] { (byte) 0xAD, 0x42, (byte) 0xA0, 0x03, 0x7D, (byte) 0xA7, 0x0E, 1 }))
			.as("a one-label table biased to the id")
			.isNotNegative();
		assertThat(one.length).isLessThan(128);
		// i64.extend_i32_u; i64.const 100; i64.eq; br_if ... i64.const 416; i64.eq
		assertThat(indexOf(two, new byte[] { (byte) 0xAD, 0x42, (byte) 0xE4, 0x00, 0x51, 0x0D }))
			.as("the first id compared as i64")
			.isNotNegative();
		assertThat(indexOf(two, new byte[] { 0x42, (byte) 0xA0, 0x03, 0x51 })).as("the second id compared as i64")
			.isNotNegative();
		assertThat(indexOf(two, new byte[] { 0x0E })).as("no br_table in the chain").isNegative();
		assertThat(indexOf(dense, new byte[] { 0x0E, 6 })).as("a six-label br_table").isNotNegative();
	}

	// count lambdas, funcIds 0..count-1; those in arityOne take one parameter, the rest
	// two, so only they join the arity-1 ladder.
	private static List<WasmLispCompiler.LambdaInfo> lambdas(int count, java.util.Set<Integer> arityOne) {
		List<WasmLispCompiler.LambdaInfo> out = new java.util.ArrayList<>();
		for (int id = 0; id < count; id++) {
			out.add(new WasmLispCompiler.LambdaInfo(id, "_lambda_" + id,
					arityOne.contains(id) ? List.of("x") : List.of("x", "y"), false, List.of(), List.of(), id));
		}
		return out;
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			if (java.util.Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void aFuncIdFromNoCounterIsRejectedRatherThanEmitted() {
		// One lambda, so the only funcId this compile could have handed out is 0; 2^24
		// is the value that used to reach the level count and hang there.
		WasmLispCompiler.LambdaInfo corrupt = new WasmLispCompiler.LambdaInfo(1 << 24, "_lambda_corrupt", List.of("x"),
				false, List.of(), List.of(), 0);
		assertTimeoutPreemptively(Duration.ofSeconds(30),
				() -> assertThatThrownBy(() -> WasmRuntimeBuilder.buildDispatchBody(1, List.of(), List.of(corrupt), 0,
						new WasmLispCompiler.StringTable(0, false, false), false, 0))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining(String.valueOf(1 << 24))
					.hasMessageContaining("outside [0, 1)"));
	}

}
