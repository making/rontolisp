package am.ik.wasm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import am.ik.wasm.WasmCodeModel.FuncType;
import am.ik.wasm.WasmCodeModel.TypeDef;
import am.ik.wasm.WasmCodeModel.TypeSection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every struct and array type an emitted module declares is a type of its own under
 * wasm-GC's canonicalization. Two rec groups with the same members ARE one type to the
 * engine, so a {@code ref.test} on either answers for both -- a packed float array would
 * test as a complex or a ratio, and its field casts would trap
 * ({@code .kb/wasm-complex.md}). The backend keeps its shapes apart by construction (a
 * tag field, a shared rec group), and this pins that no two groups holding one coincide,
 * across the flags that add conditional types: the identity-hash slot, the instance
 * struct and the {@code --simd} block. The modules are unoptimized, since the shake drops
 * the types a program never uses.
 */
class WasmStructShapeTest {

	private static final String EVERY_VALUE = """
			(defstruct pt x)
			(let ((h (make-hash-table :test 'eq)))
			  (setf (gethash (make-pt :x 1) h) 1)
			  (print (list (hash-table-count h) 1/3 #c(1 2) 1.5 #d(1.0 2.0) (expt 2 100) #\\a "s")))
			""";

	@Test
	void noTwoRecGroupsHoldingAStructOrAnArrayCoincide() {
		assertDistinct(compile(WasmLispCompiler.builder(), "(print 1/3)"));
		assertDistinct(compile(WasmLispCompiler.builder(), EVERY_VALUE));
		assertDistinct(compile(WasmLispCompiler.builder().simd(true), EVERY_VALUE));
	}

	private static byte[] compile(WasmLispCompiler.Builder builder, String source) {
		return builder.optimize(OptimizeLevel.NONE).build().compile(LispReader.readAllFromString(source));
	}

	private static void assertDistinct(byte[] module) {
		TypeSection types = WasmCodeModel.parseTypeSection(
				Objects.requireNonNull(WasmSections.find(WasmSections.parseSections(module), 1)).payload());
		Map<List<TypeDef>, Integer> seen = new HashMap<>();
		int heapTypes = 0;
		for (int g = 0; g < types.groupStart().length; g++) {
			int start = types.groupStart()[g];
			List<TypeDef> members = List.copyOf(types.types().subList(start, start + types.groupSize()[g]));
			if (members.stream().allMatch(FuncType.class::isInstance)) {
				continue;
			}
			heapTypes++;
			Integer twin = seen.putIfAbsent(members, g);
			assertThat(twin).as("rec groups %s and %s are one canonical type: %s", twin, g, members).isNull();
		}
		assertThat(heapTypes).as("the module declares its struct and array types").isGreaterThanOrEqualTo(14);
	}

}
