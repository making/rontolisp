package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import am.ik.rontolisp.DeclaredArityReport;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The wasm-GC arity-report path past a wide funcId span (g48): unlike the JVM leg's old
 * single {@code _arityErr} table -- which silently answered nil past a 30,568-funcId span
 * (g46) -- a per-arity dispatcher selects its miss arms over the funcIds themselves and
 * carries one report arm per distinct callee SHAPE, so no table scales with the span.
 * What still has a ceiling is NAMING the operator: a shape carries the funcId in the
 * sixteen bits above {@code ARITY_FUNC_ID_SHIFT}, which end at
 * {@code ARITY_MAX_NAMED_FUNC_ID}. Past it the callee reports as {@code Function} -- the
 * same silent-generic family as the JVM names budget (g49) -- and one over-limit miss
 * drops the names for its whole unpaged dispatcher.
 */
class WasmAritySpanTest {

	/** The message function index the report arms call in these tests. */
	private static final int MESSAGE_INDEX = 4242;

	/**
	 * That call as bytes: {@code CALL} followed by the unsigned LEB128 of
	 * {@link #MESSAGE_INDEX}. Every other call the dispatcher emits targets a small
	 * runtime-helper index, so the triple is the report arm's fingerprint.
	 */
	private static final byte[] MESSAGE_CALL = new byte[] { 0x10, (byte) 0x92, 0x21 };

	@Test
	void arityShapeKeepsTheFuncIdBelowTheNamedCeilingAndDropsItThere() {
		int max = WasmRuntimeBuilder.ARITY_MAX_NAMED_FUNC_ID;
		assertThat(max).isEqualTo(65_535);
		assertThat(WasmRuntimeBuilder.arityShape(1, false, 42) >>> WasmRuntimeBuilder.ARITY_FUNC_ID_SHIFT)
			.isEqualTo(43);
		assertThat(WasmRuntimeBuilder.arityShape(1, false, max - 1) >>> WasmRuntimeBuilder.ARITY_FUNC_ID_SHIFT)
			.isEqualTo(max);
		// At the ceiling and past it the shape carries no funcId, so _arity_message
		// selects its Function arm: a generic report, never a wrapped smaller id.
		assertThat(WasmRuntimeBuilder.arityShape(1, false, max)).isLessThan(1 << 15);
		assertThat(WasmRuntimeBuilder.arityShape(1, false, max + 10_000)).isLessThan(1 << 15);
	}

	@Test
	void operatorNamesStopAtTheNamedCeiling() {
		String operator = null;
		for (String candidate : new String[] { "LENGTH", "CONS", "CAR" }) {
			if (BuiltinFunctionWrappers.arityOperator(candidate) != null) {
				operator = candidate;
				break;
			}
		}
		assertThat(operator).isNotNull();
		int max = WasmRuntimeBuilder.ARITY_MAX_NAMED_FUNC_ID;
		List<WasmLispCompiler.DefunDecl> defuns = new ArrayList<>(max + 1_000);
		for (int i = 0; i < max + 1_000; i++) {
			defuns.add(new WasmLispCompiler.DefunDecl(operator, List.of("x"), false, 0, List.of()));
		}
		SortedMap<Integer, String> named = WasmLispCompiler.arityOperatorFuncIds(defuns, null, Set.of());
		assertThat(named).hasSize(max);
		assertThat(named).containsKey(max - 1);
		assertThat(named).doesNotContainKey(max);
	}

	@Test
	void declaredReportsStopAtTheNamedCeiling() {
		int max = WasmRuntimeBuilder.ARITY_MAX_NAMED_FUNC_ID;
		DeclaredArityReport report = new DeclaredArityReport("Declared got (", ")");
		Map<String, WasmLispCompiler.WasmFunctionInfo> functions = new TreeMap<>();
		functions.put("low", new WasmLispCompiler.WasmFunctionInfo("low", 1, false, 0, max - 1, 0, 0, report));
		functions.put("high", new WasmLispCompiler.WasmFunctionInfo("high", 1, false, 0, max, 0, 0, report));
		SortedMap<Integer, DeclaredArityReport> declared = WasmLispCompiler.declaredArityFuncIds(functions, List.of(),
				null, Set.of());
		assertThat(declared).containsKey(max - 1);
		assertThat(declared).doesNotContainKey(max);
	}

	/**
	 * A span past the JVM's old 30,000 cliff still throws the report (g46: the
	 * clojure-spec corpus at 30,568 funcIds): 35,000 one-parameter callees against an
	 * arity-0 dispatcher are 35,000 misses sharing one shape arm, and the arm still calls
	 * the message function. Without a report the same dispatcher answers the default arm,
	 * which the scan does not mistake for the report.
	 */
	@Test
	void aSpanPastThirtyThousandFuncIdsStillThrowsTheReport() {
		byte[] with = dispatcherBytes(35_000, true);
		assertThat(contains(with, MESSAGE_CALL)).as("the report arm calls _arity_message").isTrue();
		byte[] without = dispatcherBytes(35_000, false);
		assertThat(contains(without, MESSAGE_CALL)).as("no report, no message call").isFalse();
	}

	/**
	 * One miss past the naming ceiling drops the names for the whole unpaged dispatcher:
	 * at 70,000 funcIds the miss gate ({@code lastKey <
	 * ARITY_MAX_NAMED_FUNC_ID}) is false, so no arm carries a funcId and nothing calls
	 * the message function -- every miss reports as {@code Function}. The over-limit
	 * behavior is silent generic text, the g49 family, not a lost signal.
	 */
	@Test
	void aMissPastTheNamedCeilingReportsEveryMissGenerically() {
		byte[] past = dispatcherBytes(70_000, true);
		assertThat(contains(past, MESSAGE_CALL)).as("no named arm past the ceiling").isFalse();
	}

	private static byte[] dispatcherBytes(int defunCount, boolean withReport) {
		return assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
			List<WasmLispCompiler.DefunDecl> defuns = new ArrayList<>(defunCount);
			for (int i = 0; i < defunCount; i++) {
				defuns.add(new WasmLispCompiler.DefunDecl("wf" + i, List.of("x"), false, 0, List.of()));
			}
			WasmLispCompiler.StringTable st = new WasmLispCompiler.StringTable(0, false, false);
			SortedMap<Integer, String> named = new TreeMap<>();
			named.put(5, "LENGTH");
			WasmRuntimeBuilder.ArityReport report = withReport ? new WasmRuntimeBuilder.ArityReport(st, 0, 0, 8, 0,
					false, named, new TreeMap<>(), new TreeMap<>(), MESSAGE_INDEX) : null;
			WasmRuntimeBuilder.DispatchFunctions built = WasmRuntimeBuilder.buildDispatch(0, defuns, List.of(),
					defunCount, st, false, false, 0, false, null, 1_000_000, report, -1, false, null, false, -1);
			assertThat(built.pages()).as("an unpaged dispatcher").isEmpty();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(built.body());
			for (byte[] page : built.pages()) {
				out.write(page);
			}
			return out.toByteArray();
		});
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			boolean match = true;
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					match = false;
					break;
				}
			}
			if (match) {
				return true;
			}
		}
		return false;
	}

}
