package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WasmWriteOnlyGlobalsTest {

	private static void write(WasmWriter w, int global) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(7);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(global);
	}

	private static void update(WasmWriter w, int global) {
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(global);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(global);
	}

	private static byte[] body(Consumer<WasmWriter> instructions) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.write(0);
		instructions.accept(w);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	private static byte[] module(byte[] live, byte[] dead) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new WasmWriter(out).write("\0asm")
			.writeLittleEndian4(1)
			.writeTypeSection(types -> types.addFunc(new Type[] {}, new Type[] {}))
			.writeFunction(functions -> {
				functions.addFunction(0);
				functions.addFunction(0);
			})
			.writeGlobal(globals -> {
				for (int g = 0; g < 5; g++) {
					globals.add(w -> {
						w.write(Type.I32);
						w.write(Mutability.VAR.code());
						w.write(Instruction.I32_CONST);
						w.writeSignedLeb128(0);
						w.write(Instruction.END);
					});
				}
			})
			.writeExport(exports -> {
				exports.addExport("live", ExternalKind.FUNCTION, 0);
				exports.addExport("g3", ExternalKind.GLOBAL, 3);
			})
			.writeCode(code -> {
				code.addFunction(live);
				code.addFunction(dead);
			});
		return out.toByteArray();
	}

	@Test
	void deletesTheWritesOfAGlobalOnlyADeadBodyReads() {
		// G0 is written, G1 only feeds its own update, and G4's one read is in a function
		// nothing reaches: each goes, the stored value left for the peepholes as a drop.
		// G2 is read, and G3 is the host's to read: their writes stay. So does the update
		// of G2, whose read counts for nothing but neither does it keep G2 out.
		byte[] live = body(w -> {
			write(w, 0);
			update(w, 1);
			update(w, 1);
			write(w, 2);
			update(w, 2);
			w.write(Instruction.GET_GLOBAL);
			w.writeUnsignedLeb128(2);
			w.write(Instruction.DROP);
			write(w, 3);
			write(w, 4);
		});
		byte[] dead = body(w -> {
			w.write(Instruction.GET_GLOBAL);
			w.writeUnsignedLeb128(4);
			w.write(Instruction.DROP);
		});
		byte[] expected = body(w -> {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(7);
			w.write(Instruction.DROP);
			write(w, 2);
			update(w, 2);
			w.write(Instruction.GET_GLOBAL);
			w.writeUnsignedLeb128(2);
			w.write(Instruction.DROP);
			write(w, 3);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(7);
			w.write(Instruction.DROP);
		});

		byte[] rewritten = WasmWriteOnlyGlobals.rewrite(module(live, dead));

		List<byte[]> entries = WasmSections.parseCodeEntries(
				java.util.Objects.requireNonNull(WasmSections.find(WasmSections.parseSections(rewritten), 10))
					.payload());
		assertThat(entries.get(0)).isEqualTo(expected);
		assertThat(entries.get(1)).isEqualTo(dead);
		assertThat(WasmWriteOnlyGlobals.rewrite(rewritten)).isSameAs(rewritten);
	}

}
