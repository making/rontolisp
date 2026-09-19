package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import am.ik.wasm.WasmCodeModel.Body;
import am.ik.wasm.WasmCodeModel.Instr;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single-call-site move, on hand-assembled modules: what it relocates, what it
 * declines, and the two ways it hands the arguments over without paying for a local.
 * <p>
 * The move never deletes anything -- it leaves the callee unreferenced for
 * {@link WasmTreeShaker} -- so every size claim here is made on the SHAKEN module, which
 * is what an artifact actually ships.
 */
class WasmInlinerTest {

	// Type 0: (i32 i32) -> i32; type 1: (i32) -> i32; type 2: () -> i32.
	private static final Consumer<TypeDef> TYPES = types -> types
		.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] { Type.I32 })
		.addFunc(new Type[] { Type.I32 }, new Type[] { Type.I32 })
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

	// `local.get 0; local.get 1; i32.add`: every parameter read once, in order, at the
	// start -- the shape the arguments can be left on the stack for.
	private static final byte[] ADD = body(0, w -> {
		op(w, Instruction.GET_LOCAL, 0);
		op(w, Instruction.GET_LOCAL, 1);
		w.write(Instruction.I32_ADD);
	});

	// `local.get 0; local.get 0; i32.mul`: one parameter, read twice, so the stack
	// hand-over does not apply and the argument has to be substituted or stored.
	private static final byte[] SQUARE = body(0, w -> {
		op(w, Instruction.GET_LOCAL, 0);
		op(w, Instruction.GET_LOCAL, 0);
		w.write(Instruction.I32_MUL);
	});

	private static Body decode(byte[] module, int definedIndex) {
		List<WasmSections.Section> sections = WasmSections.parseSections(module);
		WasmCodeModel.TypeSection types = WasmCodeModel
			.parseTypeSection(Objects.requireNonNull(WasmSections.find(sections, 1)).payload());
		byte[] entry = WasmSections.parseCodeEntries(Objects.requireNonNull(WasmSections.find(sections, 10)).payload())
			.get(definedIndex);
		return WasmCodeModel.decode(entry, types);
	}

	private static List<Integer> opcodes(byte[] module, int definedIndex) {
		List<Integer> out = new ArrayList<>();
		for (Instr in : decode(module, definedIndex).code()) {
			out.add(in.op);
		}
		return out;
	}

	/**
	 * {@link WasmInliner#inline(byte[])} plus the oracle the structural assertions cannot
	 * be: a moved body that reaches its parameters across a wrapper block, or leaves
	 * through a branch that now names the wrong label, still DECODES -- only a validator
	 * says it is a module.
	 */
	private static byte[] inlineAndValidate(byte[] module) {
		byte[] inlined = WasmInliner.inline(module);
		validate(inlined);
		validate(WasmTreeShaker.shake(inlined));
		return inlined;
	}

	private static void validate(byte[] module) {
		if (!onPath("wasm-tools")) {
			return;
		}
		try {
			Path file = Files.createTempFile("inliner", ".wasm");
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
		try {
			return new ProcessBuilder("which", tool).start().waitFor() == 0;
		}
		catch (IOException | InterruptedException ex) {
			return false;
		}
	}

	private static int definedFunctions(byte[] module) {
		return WasmSections
			.parseCodeEntries(
					Objects.requireNonNull(WasmSections.find(WasmSections.parseSections(module), 10)).payload())
			.size();
	}

	@Test
	void movesTheBodyToItsOneCallSiteAndLeavesTheArgumentsOnTheStack() {
		// 0: add; 1: the export, calling it once with two constants.
		byte[] caller = body(0, w -> {
			constant(w, 2);
			constant(w, 3);
			op(w, Instruction.CALL, 0);
		});
		byte[] module = module(new int[] { 0, 2 }, List.of(ADD, caller), Map.of("g", 1));

		byte[] inlined = inlineAndValidate(module);

		// The two pushes are still there and the call is gone; `i32.add` took its place,
		// and the callee's parameters cost no local at all.
		assertThat(opcodes(inlined, 1)).containsExactly(Instruction.I32_CONST, Instruction.I32_CONST,
				Instruction.I32_ADD, Instruction.END);
		assertThat(decode(inlined, 1).locals()).isEmpty();
		// The callee's own entry is untouched -- unreferenced, for the shake to collect.
		assertThat(opcodes(inlined, 0)).isEqualTo(opcodes(module, 0));
		assertThat(definedFunctions(WasmTreeShaker.shake(inlined))).isEqualTo(1);
		assertThat(WasmTreeShaker.shake(inlined).length).isLessThan(WasmTreeShaker.shake(module).length);
	}

	@Test
	void aConstantArgumentIsWrittenAgainAtEveryReadRatherThanStoredInALocal() {
		byte[] caller = body(0, w -> {
			constant(w, 7);
			op(w, Instruction.CALL, 0);
		});
		byte[] module = module(new int[] { 1, 2 }, List.of(SQUARE, caller), Map.of("g", 1));

		byte[] inlined = inlineAndValidate(module);

		// `i32.const 7` twice, no local.set, no local at all: the push was deleted and
		// each of the callee's two parameter reads became the constant again.
		assertThat(opcodes(inlined, 1)).containsExactly(Instruction.I32_CONST, Instruction.I32_CONST,
				Instruction.I32_MUL, Instruction.END);
		assertThat(decode(inlined, 1).locals()).isEmpty();
	}

	@Test
	void aLocalArgumentIsReadAgainAtEveryReadRatherThanCopiedIntoANewLocal() {
		// The caller's own parameter is handed to a callee that reads it twice. The
		// callee never writes the parameter, so each read may name the caller's local.
		byte[] caller = body(0, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.CALL, 0);
		});
		byte[] module = module(new int[] { 1, 1 }, List.of(SQUARE, caller), Map.of("g", 1));

		byte[] inlined = inlineAndValidate(module);

		assertThat(opcodes(inlined, 1)).containsExactly(Instruction.GET_LOCAL, Instruction.GET_LOCAL,
				Instruction.I32_MUL, Instruction.END);
		assertThat(decode(inlined, 1).code().get(0).a).isZero();
		assertThat(decode(inlined, 1).code().get(1).a).isZero();
		assertThat(decode(inlined, 1).locals()).isEmpty();
	}

	@Test
	void anArgumentThatIsNeitherConstantNorLocalIsHandedOverThroughAFreshLocal() {
		// The argument is computed, so it cannot be written again; the callee reads its
		// parameter twice, so it cannot stay on the stack either.
		byte[] caller = body(0, w -> {
			constant(w, 2);
			constant(w, 3);
			w.write(Instruction.I32_ADD);
			op(w, Instruction.CALL, 0);
		});
		byte[] module = module(new int[] { 1, 2 }, List.of(SQUARE, caller), Map.of("g", 1));

		byte[] inlined = inlineAndValidate(module);

		assertThat(opcodes(inlined, 1)).containsExactly(Instruction.I32_CONST, Instruction.I32_CONST,
				Instruction.I32_ADD, Instruction.SET_LOCAL, Instruction.GET_LOCAL, Instruction.GET_LOCAL,
				Instruction.I32_MUL, Instruction.END);
		assertThat(decode(inlined, 1).locals()).hasSize(1);
	}

	@Test
	void aReturnBecomesABranchOutOfAWrappingBlock() {
		// `if (local.get 0) { i32.const 1; return } i32.const 0`
		byte[] early = body(0, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			w.write(Instruction.IF);
			w.write(0x40); // (void)
			constant(w, 1);
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
			constant(w, 0);
		});
		byte[] caller = body(0, w -> {
			constant(w, 1);
			op(w, Instruction.CALL, 0);
		});
		byte[] module = module(new int[] { 1, 2 }, List.of(early, caller), Map.of("g", 1));

		byte[] inlined = inlineAndValidate(module);

		// A block of the callee's result type wraps the moved body, and the `return`
		// -- which would otherwise leave the CALLER -- is a branch to it.
		List<Instr> code = decode(inlined, 1).code();
		assertThat(code.get(1).op).isEqualTo(Instruction.BLOCK);
		assertThat(code.stream().map(in -> in.op)).doesNotContain(Instruction.RETURN).contains(Instruction.BR);
		assertThat(code.stream().filter(in -> in.op == Instruction.BR).findFirst().orElseThrow().a).isEqualTo(1L);
	}

	@Test
	void aCalleeWithTwoCallSitesAnExportOrARecursiveBodyIsLeftAlone() {
		byte[] twice = body(0, w -> {
			constant(w, 1);
			constant(w, 2);
			op(w, Instruction.CALL, 0);
			constant(w, 3);
			constant(w, 4);
			op(w, Instruction.CALL, 0);
			w.write(Instruction.I32_ADD);
		});
		byte[] twiceModule = module(new int[] { 0, 2 }, List.of(ADD, twice), Map.of("g", 1));
		assertThat(WasmInliner.inline(twiceModule)).as("two call sites").isSameAs(twiceModule);

		byte[] once = body(0, w -> {
			constant(w, 2);
			constant(w, 3);
			op(w, Instruction.CALL, 0);
		});
		// The same module with the callee ALSO exported: an export is a root, so moving
		// its body would leave the export naming a body nothing reaches.
		byte[] exported = module(new int[] { 0, 2 }, List.of(ADD, once), Map.of("g", 1, "add", 0));
		assertThat(WasmInliner.inline(exported)).isSameAs(exported);

		// A body that calls itself: the moved copy would call an entry the shake kills.
		byte[] selfCalling = body(0, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.CALL, 0);
		});
		byte[] recursive = module(new int[] { 1, 2 }, List.of(selfCalling, once), Map.of("g", 1));
		assertThat(WasmInliner.inline(recursive)).isSameAs(recursive);
	}

	@Test
	void aBodyTheDuplicateFoldWouldReclaimAnywayIsNotWorthMoving() {
		// Two byte-identical adders. The shake's WasmBodyFolder drops one of them for
		// free and redirects its callers, so moving the once-called one buys nothing --
		// and would leave the caller carrying those bytes for good. Measured on zlib:
		// taking these gave back 530 claimed bytes and cost 804 real ones.
		byte[] onceThenTwice = body(0, w -> {
			constant(w, 1);
			constant(w, 2);
			op(w, Instruction.CALL, 0); // the only call of func 0
			constant(w, 3);
			constant(w, 4);
			op(w, Instruction.CALL, 1); // func 1 is byte-identical to func 0
			w.write(Instruction.I32_ADD);
			constant(w, 5);
			constant(w, 6);
			op(w, Instruction.CALL, 1);
			w.write(Instruction.I32_ADD);
		});
		byte[] module = module(new int[] { 0, 0, 2 }, List.of(ADD, ADD, onceThenTwice), Map.of("g", 2));

		assertThat(WasmInliner.inline(module)).isSameAs(module);
		// And the fold does reclaim it: two adders in, one out.
		assertThat(definedFunctions(WasmTreeShaker.shake(module))).isEqualTo(2);
	}

	@Test
	void aBodyBiggerThanTheBudgetStaysWhereItIs() {
		// 64 bytes is the measured budget: what the move reclaims is the per-function
		// overhead, never a function of the body's size, and relocating more than that
		// costs the artifact more compressed bytes than it saves raw ones.
		byte[] big = body(0, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			for (int i = 0; i < 40; i++) {
				constant(w, 1);
				w.write(Instruction.I32_ADD);
			}
		});
		assertThat(big.length).isGreaterThan(64);
		byte[] caller = body(0, w -> {
			constant(w, 7);
			op(w, Instruction.CALL, 0);
		});
		byte[] module = module(new int[] { 1, 2 }, List.of(big, caller), Map.of("g", 1));

		assertThat(WasmInliner.inline(module)).isSameAs(module);
	}

	@Test
	void aChainIsFilledInFromTheBottomUp() {
		// 0: add; 1: calls 0 once; 2: the export, calling 1 once. Everything collapses
		// into the export.
		byte[] middle = body(0, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			constant(w, 1);
			op(w, Instruction.CALL, 0);
		});
		byte[] outer = body(0, w -> {
			constant(w, 9);
			op(w, Instruction.CALL, 1);
		});
		byte[] module = module(new int[] { 0, 1, 2 }, List.of(ADD, middle, outer), Map.of("g", 2));

		byte[] shaken = WasmTreeShaker.shake(inlineAndValidate(module));

		assertThat(definedFunctions(shaken)).isEqualTo(1);
		assertThat(opcodes(shaken, 0)).containsExactly(Instruction.I32_CONST, Instruction.I32_CONST,
				Instruction.I32_ADD, Instruction.END);
	}

	@Test
	void aModuleWithNothingToMoveComesBackAsItWas() {
		byte[] caller = body(0, w -> {
			constant(w, 2);
			constant(w, 3);
			w.write(Instruction.I32_ADD);
		});
		byte[] module = module(new int[] { 2 }, List.of(caller), Map.of("g", 0));

		assertThat(WasmInliner.inline(module)).isSameAs(module);
	}

	@Test
	void aPinnedCalleeKeepsItsBody() {
		// The owner of an OwnedDataSegment claim: the claim names it by index, so a
		// moved body would take the segment with it when the shake kills the entry.
		byte[] caller = body(0, w -> {
			constant(w, 2);
			constant(w, 3);
			op(w, Instruction.CALL, 0);
		});
		byte[] module = module(new int[] { 0, 2 }, List.of(ADD, caller), Map.of("g", 1));

		assertThat(WasmInliner.inline(module, new int[] { 0 })).isSameAs(module);
		assertThat(WasmInliner.inline(module, new int[] { 1 })).isNotSameAs(module);
	}

	@Test
	void aTailCallSiteTakesTheMovedBodyFollowedByAReturn() {
		// The one call site is `return_call 0`: the callee's answer is the caller's, so
		// the moved body ends in a `return` -- whatever the caller had after the site (a
		// dispatcher case falls through into the NEXT case's body there).
		byte[] caller = body(0, w -> {
			constant(w, 2);
			constant(w, 3);
			op(w, Instruction.RETURN_CALL, 0);
		});
		byte[] module = module(new int[] { 0, 2 }, List.of(ADD, caller), Map.of("g", 1));

		byte[] inlined = inlineAndValidate(module);

		assertThat(opcodes(inlined, 1)).containsExactly(Instruction.I32_CONST, Instruction.I32_CONST,
				Instruction.I32_ADD, Instruction.RETURN, Instruction.END);
		assertThat(definedFunctions(WasmTreeShaker.shake(inlined))).isEqualTo(1);
	}

	@Test
	void aTailCallInsideAMovedBodyBecomesACallAndABranchOutOfTheWrappingBlock() {
		// `if (local.get 0) { i32.const 1; return_call 2 } i32.const 0`: moved into its
		// caller, the tail call is a plain call followed by the branch a `return` gets
		// -- the callee's frame is gone either way, so the stack is exactly as deep as
		// the tail call left it. Function 2 is exported so it stays a call target.
		byte[] early = body(0, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			w.write(Instruction.IF);
			w.write(0x40); // (void)
			constant(w, 1);
			op(w, Instruction.RETURN_CALL, 2);
			w.write(Instruction.END);
			constant(w, 0);
		});
		byte[] caller = body(0, w -> {
			constant(w, 5);
			op(w, Instruction.CALL, 0);
		});
		byte[] module = module(new int[] { 1, 2, 1 }, List.of(early, caller, SQUARE), Map.of("g", 1, "sq", 2));

		byte[] inlined = inlineAndValidate(module);

		// The argument push stays on the stack under a block declaring the callee's
		// type (the stack hand-over), so the block is the second instruction.
		List<Instr> code = decode(inlined, 1).code();
		assertThat(code.get(1).op).isEqualTo(Instruction.BLOCK);
		assertThat(code.stream().map(in -> in.op)).doesNotContain(Instruction.RETURN_CALL, Instruction.RETURN);
		int call = -1;
		for (int i = 0; i < code.size(); i++) {
			if (code.get(i).op == Instruction.CALL) {
				call = i;
			}
		}
		assertThat(call).isPositive();
		assertThat(code.get(call).a).isEqualTo(2L);
		assertThat(code.get(call + 1).op).isEqualTo(Instruction.BR);
		assertThat(code.get(call + 1).a).isEqualTo(1L);
		assertThat(definedFunctions(WasmTreeShaker.shake(inlined))).isEqualTo(2);
	}

	@Test
	void aTrailingTailCallInAMovedBodyNeedsNoBlock() {
		// A forwarder, as the emitter spells one: `local.get 0; return_call 2; end`. The
		// tail call is the body's last instruction, so as a plain call it falls off the
		// end exactly as the tail call left -- no wrapping block, no branch, and the
		// move is what it was when the forwarder said `call`.
		byte[] forwarder = body(0, w -> {
			op(w, Instruction.GET_LOCAL, 0);
			op(w, Instruction.RETURN_CALL, 2);
		});
		byte[] caller = body(0, w -> {
			constant(w, 4);
			op(w, Instruction.CALL, 0);
		});
		byte[] module = module(new int[] { 1, 2, 1 }, List.of(forwarder, caller, SQUARE), Map.of("g", 1, "sq", 2));

		byte[] inlined = inlineAndValidate(module);

		List<Instr> code = decode(inlined, 1).code();
		assertThat(code.stream().map(in -> in.op)).containsExactly(Instruction.I32_CONST, Instruction.CALL,
				Instruction.END);
		assertThat(code.get(1).a).isEqualTo(2L);
		assertThat(definedFunctions(WasmTreeShaker.shake(inlined))).isEqualTo(2);
	}

}
