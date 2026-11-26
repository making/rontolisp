package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import am.ik.wasm.WasmCodeModel.Body;
import am.ik.wasm.WasmCodeModel.Instr;
import am.ik.wasm.WasmCodeModel.ValType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local renumbering on hand-assembled bodies: the most-used local of a wide frame
 * gets a one-byte index, the parameters and every other local keep their meaning, the
 * declaration vector regroups by type, and a frame that fits in one byte already is left
 * alone.
 */
class WasmLocalOrderTest {

	// Type 0: (i32 i32) -> (); type 1: () -> ().
	private static final Consumer<TypeDef> TYPES = types -> types
		.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] {})
		.addFunc(new Type[] {}, new Type[] {});

	private static byte[] module(int[] funcTypes, List<byte[]> bodies) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new WasmWriter(out).write("\0asm").writeLittleEndian4(1).writeTypeSection(TYPES).writeFunction(functions -> {
			for (int t : funcTypes) {
				functions.addFunction(t);
			}
		}).writeCode(code -> {
			for (byte[] body : bodies) {
				code.addFunction(body);
			}
		});
		return out.toByteArray();
	}

	/**
	 * A body whose locals are the given runs, then the instructions, then {@code end}.
	 */
	private static byte[] body(int[][] runs, Consumer<WasmWriter> instructions) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.writeUnsignedLeb128(runs.length);
		for (int[] run : runs) {
			w.writeUnsignedLeb128(run[0]);
			w.write(run[1]);
		}
		instructions.accept(w);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	private static void get(WasmWriter w, int index) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(index);
		w.write(Instruction.DROP);
	}

	private static Body decoded(byte[] module, int definedIndex) {
		List<WasmSections.Section> sections = WasmSections.parseSections(module);
		WasmCodeModel.TypeSection types = WasmCodeModel
			.parseTypeSection(java.util.Objects.requireNonNull(WasmSections.find(sections, 1)).payload());
		byte[] entry = WasmSections
			.parseCodeEntries(java.util.Objects.requireNonNull(WasmSections.find(sections, 10)).payload())
			.get(definedIndex);
		return WasmCodeModel.decode(entry, types);
	}

	private static List<Long> localGets(Body body) {
		List<Long> out = new ArrayList<>();
		for (Instr in : body.code()) {
			if (in.op == Instruction.GET_LOCAL) {
				out.add(in.a);
			}
		}
		return out;
	}

	private static int count(List<ValType> locals, ValType t) {
		return (int) locals.stream().filter(t::equals).count();
	}

	private static final int EQREF = Type.EQ.code();

	@Test
	void theMostUsedLocalOfAWideFrameGetsAOneByteIndex() {
		// Two parameters, 100 i32 locals (indices 2..101) then 30 eqref ones (102..131):
		// the last eqref is read three times at two bytes each, an i32 once. Both land
		// in the hot set (126 locals: those two and the first 124 nobody reads); the
		// eqref moves to the last one-byte index its type group reaches (127), the i32
		// stays where it was, the cold tail is the four eqrefs nobody reads.
		byte[] wide = body(new int[][] { { 100, Type.I32.code() }, { 30, EQREF } }, w -> {
			get(w, 131);
			get(w, 7);
			get(w, 131);
			get(w, 131);
		});
		byte[] module = module(new int[] { 0 }, List.of(wide));
		byte[] reordered = WasmLocalOrder.reorder(module);

		assertThat(reordered.length).isEqualTo(module.length - 3);
		Body body = decoded(reordered, 0);
		assertThat(localGets(body)).containsExactly(127L, 7L, 127L, 127L);
		assertThat(body.locals()).hasSize(130);
		assertThat(count(body.locals(), ValType.I32)).isEqualTo(100);
		assertThat(body.locals().get(127 - 2)).isEqualTo(ValType.abstractRef(EQREF));
	}

	@Test
	void theLocalsRegroupByTypeSoTheDeclarationStaysAFewRuns() {
		// No parameters, 130 locals alternating i32/eqref -- 130 declaration runs -- with
		// the last (an eqref at 129) read once. The hot set is grouped by type (64 i32,
		// then 64 eqref including the read one, which lands at 127), the cold pair the
		// same: four runs, and the read costs one byte.
		int[][] alternating = new int[130][];
		for (int i = 0; i < 130; i++) {
			alternating[i] = new int[] { 1, i % 2 == 0 ? Type.I32.code() : EQREF };
		}
		byte[] wide = body(alternating, w -> get(w, 129));
		byte[] module = module(new int[] { 1 }, List.of(wide));
		byte[] reordered = WasmLocalOrder.reorder(module);

		Body body = decoded(reordered, 0);
		assertThat(localGets(body)).containsExactly(127L);
		List<ValType> locals = body.locals();
		assertThat(locals).hasSize(130);
		assertThat(locals.subList(0, 64)).containsOnly(ValType.I32);
		assertThat(locals.subList(64, 128)).containsOnly(ValType.abstractRef(EQREF));
		assertThat(locals.get(128)).isEqualTo(ValType.I32);
		assertThat(locals.get(129)).isEqualTo(ValType.abstractRef(EQREF));
		// 130 runs of (count, type) became 4 -- 253 bytes of vector, its count LEB
		// included -- the read lost a byte, and the entry and section size prefixes a
		// byte each.
		assertThat(reordered.length).isEqualTo(module.length - 256);
	}

	@Test
	void aFrameThatFitsInOneByteIsLeftAlone() {
		// Two parameters and 126 locals: index 127 is the widest, and it is one byte.
		byte[] narrow = body(new int[][] { { 126, EQREF } }, w -> {
			get(w, 127);
			get(w, 127);
		});
		byte[] module = module(new int[] { 0 }, List.of(narrow));

		assertThat(WasmLocalOrder.reorder(module)).isSameAs(module);
	}

}
