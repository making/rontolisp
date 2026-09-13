package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import am.ik.wasm.WasmCodeModel.Body;
import am.ik.wasm.WasmCodeModel.Instr;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single-use local sink on hand-assembled bodies: the expression goes to its one
 * read, the local leaves the frame and the ones above it move down, and every shape the
 * legality argument declines -- a rewritten input, a read the write does not dominate, a
 * global carried across a call -- is left byte-for-byte alone. Every rewritten module is
 * also handed to {@code wasm-tools validate} when it is on the PATH, because a stack
 * mistake in a moved expression still decodes.
 */
class WasmLocalSinkTest {

	// Type 0: (i32 i32) -> i32; type 1: (i32) -> i32; type 2: () -> i32.
	private static final Consumer<TypeDef> TYPES = types -> types
		.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] { Type.I32 })
		.addFunc(new Type[] { Type.I32 }, new Type[] { Type.I32 })
		.addFunc(new Type[] {}, new Type[] { Type.I32 });

	private static byte[] module(int[] funcTypes, List<byte[]> bodies) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new WasmWriter(out).write("\0asm").writeLittleEndian4(1).writeTypeSection(TYPES).writeFunction(functions -> {
			for (int t : funcTypes) {
				functions.addFunction(t);
			}
		}).writeGlobal(globals -> globals.addGlobal(Type.I32, Mutability.VAR, w -> constant(w, 0))).writeCode(code -> {
			for (byte[] body : bodies) {
				code.addFunction(body);
			}
		});
		return out.toByteArray();
	}

	private static byte[] body(int i32Locals, Consumer<WasmWriter> instructions) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		if (i32Locals == 0) {
			w.write(0);
		}
		else {
			w.write(1);
			w.write(i32Locals);
			w.write(Type.I32);
		}
		instructions.accept(w);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	private static void op(WasmWriter w, int opcode, int immediate) {
		w.write(opcode);
		w.writeUnsignedLeb128(immediate);
	}

	private static void constant(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static Body decode(byte[] module, int definedIndex) {
		List<WasmSections.Section> sections = WasmSections.parseSections(module);
		WasmCodeModel.TypeSection types = WasmCodeModel
			.parseTypeSection(Objects.requireNonNull(WasmSections.find(sections, 1)).payload());
		byte[] entry = WasmSections.parseCodeEntries(Objects.requireNonNull(WasmSections.find(sections, 10)).payload())
			.get(definedIndex);
		return WasmCodeModel.decode(entry, types);
	}

	/** The instruction stream as {@code op} or {@code op:immediate} tokens. */
	private static List<String> code(byte[] module, int definedIndex) {
		List<String> out = new ArrayList<>();
		for (Instr in : decode(module, definedIndex).code()) {
			boolean local = in.op == Instruction.GET_LOCAL || in.op == Instruction.SET_LOCAL
					|| in.op == Instruction.TEE_LOCAL || in.op == Instruction.GET_GLOBAL
					|| in.op == Instruction.I32_CONST || in.op == Instruction.CALL;
			out.add(local ? String.format("%02X:%d", in.op, in.a) : String.format("%02X", in.op));
		}
		return out;
	}

	private static byte[] sinkAndValidate(byte[] module) {
		byte[] sunk = WasmLocalSink.sink(module);
		validate(sunk);
		return sunk;
	}

	private static void validate(byte[] module) {
		if (!onPath("wasm-tools")) {
			return;
		}
		try {
			Path file = Files.createTempFile("sink", ".wasm");
			Files.write(file, module);
			Process process = new ProcessBuilder("wasm-tools", "validate", file.toString()).redirectErrorStream(true)
				.start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int exit = process.waitFor();
			Files.deleteIfExists(file);
			assertThat(exit).as("wasm-tools validate failed:%n%s", output).isZero();
		}
		catch (IOException | InterruptedException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static boolean onPath(String tool) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(java.io.File.pathSeparator)) {
			if (Files.isExecutable(Path.of(dir, tool))) {
				return true;
			}
		}
		return false;
	}

	@Test
	void aCopyOfAnUnwrittenLocalIsReadAgainAtItsOneUse() {
		// `local.get 0; local.set 2; local.get 1; local.get 2; i32.add`: local 2 is a
		// copy of parameter 0 that nothing rewrites, read once. The copy goes, the read
		// becomes `local.get 0`, and the frame is empty: two instructions (4 B) and the
		// one-run declaration (2 B) gone.
		byte[] module = module(new int[] { 0 }, List.of(body(1, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.SET_LOCAL, 2);
			op(w, Instruction.GET_LOCAL, 1);
			op(w, Instruction.GET_LOCAL, 2);
			w.write(Instruction.I32_ADD);
		})));
		byte[] sunk = sinkAndValidate(module);

		assertThat(code(sunk, 0)).containsExactly("20:1", "20:0", "6A", "0B");
		assertThat(decode(sunk, 0).locals()).isEmpty();
		assertThat(sunk.length).isEqualTo(module.length - 6);
	}

	@Test
	void anExpressionSinksPastTheHandOverOfAnotherValue() {
		// The inliner's hand-over shape: `a+1` and `a` are pushed, then stored in reverse
		// (`set 3; set 2`). Both expressions are pure and neither input changes before
		// the reads, so both locals go: the gap `local.get 0; local.set 3` between `a+1`
		// and its `local.set 2` is walked through, and is itself the second sink.
		byte[] module = module(new int[] { 1 }, List.of(body(2, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			constant(w, 1);
			w.write(Instruction.I32_ADD);
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.SET_LOCAL, 2);
			op(w, Instruction.SET_LOCAL, 1);
			op(w, Instruction.GET_LOCAL, 2);
			op(w, Instruction.GET_LOCAL, 1);
			w.write(Instruction.I32_MUL);
		})));
		byte[] sunk = sinkAndValidate(module);

		assertThat(code(sunk, 0)).containsExactly("20:0", "20:0", "41:1", "6A", "6C", "0B");
		assertThat(decode(sunk, 0).locals()).isEmpty();
	}

	@Test
	void aCollectedLocalRenumbersTheOnesAboveIt() {
		// Locals 1 (sunk) and 2 (kept: read twice): the survivor becomes local 1.
		byte[] module = module(new int[] { 1 }, List.of(body(2, w -> {
			constant(w, 5);
			op(w, Instruction.SET_LOCAL, 1);
			constant(w, 7);
			op(w, Instruction.SET_LOCAL, 2);
			op(w, Instruction.GET_LOCAL, 1);
			op(w, Instruction.GET_LOCAL, 2);
			w.write(Instruction.I32_ADD);
			op(w, Instruction.GET_LOCAL, 2);
			w.write(Instruction.I32_MUL);
		})));
		byte[] sunk = sinkAndValidate(module);

		assertThat(code(sunk, 0)).containsExactly("41:7", "21:1", "41:5", "20:1", "6A", "20:1", "6C", "0B");
		assertThat(decode(sunk, 0).locals()).hasSize(1);
	}

	@Test
	void aReadInsideALoopThatRewritesTheInputIsLeftAlone() {
		// `local.set 1` copies parameter 0 before a loop that reads local 1 and then
		// rewrites parameter 0: the second iteration reads the OLD value through local 1
		// and would read the new one through a sunk `local.get 0`.
		byte[] module = module(new int[] { 1 }, List.of(body(1, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.SET_LOCAL, 1);
			w.write(Instruction.LOOP);
			w.write(0x40);
			op(w, Instruction.GET_LOCAL, 1);
			w.write(Instruction.DROP);
			constant(w, 1);
			op(w, Instruction.SET_LOCAL, 0);
			constant(w, 0);
			op(w, Instruction.GET_LOCAL, 0);
			w.write(Instruction.I32_ADD);
			op(w, Instruction.BR_IF, 0);
			w.write(Instruction.END);
			op(w, Instruction.GET_LOCAL, 0);
		})));

		assertThat(WasmLocalSink.sink(module)).isSameAs(module);
	}

	@Test
	void aReadTheWriteDoesNotDominateIsLeftAlone() {
		// The write sits in the `then` arm, the read after the `if`: the other arm
		// reaches the read with the local's default value.
		byte[] module = module(new int[] { 1 }, List.of(body(1, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			w.write(Instruction.IF);
			w.write(0x40);
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.SET_LOCAL, 1);
			w.write(Instruction.END);
			op(w, Instruction.GET_LOCAL, 1);
		})));

		assertThat(WasmLocalSink.sink(module)).isSameAs(module);
	}

	@Test
	void aGlobalReadIsNotSunkAcrossACallButSinksAcrossAnythingElse() {
		// Function 1 may write the global: `global.get 0; local.set 1; call 1; drop;
		// local.get 1` stays. With a `local.get 0; drop` in place of the call it goes.
		byte[] callee = body(0, w -> constant(w, 1));
		byte[] acrossCall = body(1, w -> {
			op(w, Instruction.GET_GLOBAL, 0);
			op(w, Instruction.SET_LOCAL, 1);
			op(w, Instruction.CALL, 1);
			w.write(Instruction.DROP);
			op(w, Instruction.GET_LOCAL, 1);
		});
		byte[] acrossRead = body(1, w -> {
			op(w, Instruction.GET_GLOBAL, 0);
			op(w, Instruction.SET_LOCAL, 1);
			op(w, Instruction.GET_LOCAL, 0);
			w.write(Instruction.DROP);
			op(w, Instruction.GET_LOCAL, 1);
		});
		byte[] kept = module(new int[] { 1, 2 }, List.of(acrossCall, callee));
		byte[] sunk = sinkAndValidate(module(new int[] { 1, 2 }, List.of(acrossRead, callee)));

		assertThat(WasmLocalSink.sink(kept)).isSameAs(kept);
		assertThat(code(sunk, 0)).containsExactly("20:0", "1A", "23:0", "0B");
	}

	@Test
	void aTeeIsCopiedToItsReadOnlyWhenTheCopyIsShorter() {
		// `local.get 0; local.tee 2; local.get 1; i32.add; local.get 2; i32.mul`: the
		// copy `local.get 0` (2 B) is shorter than the tee and the get (4 B), so the
		// read re-reads parameter 0 and the tee goes. When the tee'd value is `a + b`
		// (5 B), it stays.
		byte[] shortCopy = module(new int[] { 0 }, List.of(body(1, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.TEE_LOCAL, 2);
			op(w, Instruction.GET_LOCAL, 1);
			w.write(Instruction.I32_ADD);
			op(w, Instruction.GET_LOCAL, 2);
			w.write(Instruction.I32_MUL);
		})));
		byte[] longCopy = module(new int[] { 0 }, List.of(body(1, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.GET_LOCAL, 1);
			w.write(Instruction.I32_ADD);
			op(w, Instruction.TEE_LOCAL, 2);
			op(w, Instruction.GET_LOCAL, 1);
			w.write(Instruction.I32_MUL);
			op(w, Instruction.GET_LOCAL, 2);
			w.write(Instruction.I32_SUB);
		})));
		byte[] sunk = sinkAndValidate(shortCopy);

		assertThat(code(sunk, 0)).containsExactly("20:0", "20:1", "6A", "20:0", "6C", "0B");
		assertThat(decode(sunk, 0).locals()).isEmpty();
		assertThat(WasmLocalSink.sink(longCopy)).isSameAs(longCopy);
	}

	@Test
	void deadWritesGoWithTheirExpressionAndAnUntouchedLocalLeavesTheFrame() {
		// Local 1 is tee'd and never read, local 2 is set from `a * 2` (through that tee)
		// and never read, local 3 is never mentioned. The first round deletes the dead
		// tee; the second sees the pure `local.get 0; i32.const 2; i32.mul` in front of
		// the dead set and deletes both. Only the result is left.
		byte[] module = module(new int[] { 1 }, List.of(body(3, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.TEE_LOCAL, 1);
			constant(w, 2);
			w.write(Instruction.I32_MUL);
			op(w, Instruction.SET_LOCAL, 2);
			constant(w, 7);
		})));
		byte[] sunk = sinkAndValidate(module);

		assertThat(code(sunk, 0)).containsExactly("41:7", "0B");
		assertThat(decode(sunk, 0).locals()).isEmpty();
	}

	@Test
	void aDeadWriteOfAValueTheWalkCannotSeeThroughBecomesADrop() {
		// The dead local is set from a call's result: the call stays, the set is a drop,
		// and the local is gone from the frame.
		byte[] callee = body(0, w -> constant(w, 1));
		byte[] module = module(new int[] { 2, 2 }, List.of(body(1, w -> {
			op(w, Instruction.CALL, 1);
			op(w, Instruction.SET_LOCAL, 0);
			constant(w, 7);
		}), callee));
		byte[] sunk = sinkAndValidate(module);

		assertThat(code(sunk, 0)).containsExactly("10:1", "1A", "41:7", "0B");
		assertThat(decode(sunk, 0).locals()).isEmpty();
	}

	@Test
	void aLocalWhoseExpressionIsASunkLocalIsCollectedOnTheNextRound() {
		// Local 1 copies parameter 0 and local 2 copies local 1, with a read of parameter
		// 0 between each pair so no adjacency existed for the peephole. Round one sinks
		// local 1 (local 2's expression reads it, so local 2 waits); round two sees
		// `local.get 0; local.set 2` and sinks that too.
		byte[] module = module(new int[] { 1 }, List.of(body(2, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.SET_LOCAL, 1);
			op(w, Instruction.GET_LOCAL, 0);
			w.write(Instruction.DROP);
			op(w, Instruction.GET_LOCAL, 1);
			op(w, Instruction.SET_LOCAL, 2);
			op(w, Instruction.GET_LOCAL, 0);
			w.write(Instruction.DROP);
			op(w, Instruction.GET_LOCAL, 2);
		})));
		byte[] sunk = sinkAndValidate(module);

		assertThat(code(sunk, 0)).containsExactly("20:0", "1A", "20:0", "1A", "20:0", "0B");
		assertThat(decode(sunk, 0).locals()).isEmpty();
	}

	@Test
	void aSetItsDeletedNeighbourLeavesBesideItsGetBecomesATee() {
		// `local.set 1; [local.get 0; local.set 2]; local.get 1; ...; local.get 2`:
		// sinking local 2 leaves `local.set 1; local.get 1` adjacent, which is written
		// as the `local.tee 1` the peephole in front of this pass would have made. Local
		// 1 is read three times, so it is not itself a candidate afterwards.
		byte[] module = module(new int[] { 1 }, List.of(body(2, w -> {
			constant(w, 5);
			op(w, Instruction.SET_LOCAL, 1);
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.SET_LOCAL, 2);
			op(w, Instruction.GET_LOCAL, 1);
			op(w, Instruction.GET_LOCAL, 1);
			w.write(Instruction.I32_ADD);
			op(w, Instruction.GET_LOCAL, 1);
			w.write(Instruction.I32_ADD);
			op(w, Instruction.GET_LOCAL, 2);
			w.write(Instruction.I32_MUL);
		})));
		byte[] sunk = sinkAndValidate(module);

		assertThat(code(sunk, 0)).containsExactly("41:5", "22:1", "20:1", "6A", "20:1", "6A", "20:0", "6C", "0B");
		assertThat(decode(sunk, 0).locals()).hasSize(1);
	}

	@Test
	void aPairAlreadyAdjacentInABodyWithNothingToSinkIsStillWrittenAsATee() {
		// The inliner's hand-over in front of a callee that reads the parameter first:
		// `local.set 1; local.get 1` with local 1 read again later, so no sink applies
		// in the first round -- the pair alone is worth the encode, which writes it as
		// `local.tee 1`. The next round then sees a tee read once whose copy
		// (`local.get 0`) is shorter, and the local goes altogether.
		byte[] module = module(new int[] { 1 }, List.of(body(1, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.SET_LOCAL, 1);
			op(w, Instruction.GET_LOCAL, 1);
			op(w, Instruction.GET_LOCAL, 1);
			w.write(Instruction.I32_ADD);
		})));
		byte[] sunk = sinkAndValidate(module);

		assertThat(code(sunk, 0)).containsExactly("20:0", "20:0", "6A", "0B");
		assertThat(decode(sunk, 0).locals()).isEmpty();
		assertThat(sunk.length).isEqualTo(module.length - 6);
	}

	@Test
	void aModuleWithNothingToSinkComesBackAsItWas() {
		byte[] module = module(new int[] { 0 }, List.of(body(0, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.GET_LOCAL, 1);
			w.write(Instruction.I32_ADD);
		})));

		assertThat(WasmLocalSink.sink(module)).isSameAs(module);
	}

}
