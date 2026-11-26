package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import am.ik.wasm.WasmCodeModel.Instr;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Call redirection through pure forwarders, on hand-assembled modules: a call of a
 * function that only hands its parameters on is a call of the target, a chain resolves to
 * its last link, and anything that is not exactly that shape keeps its hop.
 */
class WasmCallForwardingTest {

	// Type 0: (i32 i32) -> i32; type 1: a byte-identical twin of type 0 (canonically the
	// same type under a different index); type 2: () -> i32.
	private static final Consumer<TypeDef> TYPES = types -> types
		.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] { Type.I32 })
		.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] { Type.I32 })
		.addFunc(new Type[] {}, new Type[] { Type.I32 });

	private static byte[] module(int[] funcTypes, List<byte[]> bodies, Map<String, Integer> exports) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new WasmWriter(out).write("\0asm").writeLittleEndian4(1).writeTypeSection(TYPES).writeFunction(functions -> {
			for (int t : funcTypes) {
				functions.addFunction(t);
			}
		})
			.writeExport(ex -> exports.forEach((name, index) -> ex.addExport(name, ExternalKind.FUNCTION, index)))
			.writeCode(code -> {
				for (byte[] body : bodies) {
					code.addFunction(body);
				}
			});
		return out.toByteArray();
	}

	private static byte[] body(int i64Locals, Consumer<WasmWriter> instructions) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		if (i64Locals == 0) {
			w.write(0);
		}
		else {
			w.write(1);
			w.write(i64Locals);
			w.write(Type.I64);
		}
		instructions.accept(w);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	private static void forward(WasmWriter w, int target) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(target);
	}

	private static final byte[] ADD = body(0, w -> {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.I32_ADD);
	});

	// The callees of the definedIndex-th function, in call order.
	private static List<Long> callees(byte[] module, int definedIndex) {
		List<WasmSections.Section> sections = WasmSections.parseSections(module);
		WasmCodeModel.TypeSection types = WasmCodeModel
			.parseTypeSection(java.util.Objects.requireNonNull(WasmSections.find(sections, 1)).payload());
		byte[] entry = WasmSections
			.parseCodeEntries(java.util.Objects.requireNonNull(WasmSections.find(sections, 10)).payload())
			.get(definedIndex);
		List<Long> out = new ArrayList<>();
		for (Instr in : WasmCodeModel.decode(entry, types).code()) {
			if (in.op == 0x10) {
				out.add(in.a);
			}
		}
		return out;
	}

	@Test
	void redirectsThroughAForwarderAndAlongAChain() {
		// 0: add; 1: forwards to 0 (declared under the twin type index, still the same
		// type); 2: forwards to 1, with an unused declared local; 3: the export, calling
		// 2 and 1.
		byte[] caller = body(0, w -> {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(2);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(3);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(2);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(4);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(5);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.I32_ADD);
		});
		byte[] module = module(new int[] { 0, 1, 0, 2 },
				List.of(ADD, body(0, w -> forward(w, 0)), body(1, w -> forward(w, 1)), caller), Map.of("g", 3));

		byte[] redirected = WasmCallForwarding.redirect(module);

		assertThat(callees(redirected, 3)).containsExactly(0L, 0L);
		// The forwarders themselves are left as they are; the shaker drops them.
		assertThat(callees(redirected, 1)).containsExactly(0L);
		assertThat(callees(redirected, 2)).containsExactly(0L);
		assertThat(WasmTreeShaker.shake(redirected).length).isLessThan(WasmTreeShaker.shake(module).length);
	}

	@Test
	void leavesAnythingThatIsNotAPureForwarderAlone() {
		// Swapped parameters, a call of a function of another type, and a call the
		// forwarder makes of itself: none is a forwarder, and the module comes back as
		// it was.
		byte[] swapped = body(0, w -> {
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(0);
		});
		byte[] otherType = body(0, w -> {
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(3);
		});
		byte[] self = body(0, w -> forward(w, 2));
		byte[] caller = body(0, w -> {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(2);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(3);
			w.write(Instruction.CALL);
			w.writeUnsignedLeb128(1);
		});
		byte[] module = module(new int[] { 0, 0, 0, 2, 2 }, List.of(ADD, swapped, self, otherType, caller),
				Map.of("g", 4));

		assertThat(WasmCallForwarding.redirect(module)).isSameAs(module);
	}

}
