package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import am.ik.wasm.WasmCodeModel.Instr;
import am.ik.wasm.WasmCodeModel.StructType;
import am.ik.wasm.WasmCodeModel.TypeSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The type-test fold on hand-assembled modules small enough to read the rewritten body
 * back instruction by instruction, plus the program-level effect on a compiled reactor.
 * Each hand-built module is also validated and run when {@code wasm-tools} /
 * {@code wasmtime} are on the {@code PATH}, so a fold that changes an answer fails here
 * rather than in a program.
 */
class WasmRefTypeFolderTest {

	@TempDir
	Path tempDir;

	// Type 0: struct {i32} (its own rec group, as the backend declares every type);
	// type 1: (eqref) -> i32; type 2: () -> i32; type 3: struct {f64}.
	private static final Consumer<am.ik.wasm.TypeDef> TYPES = types -> types
		.addRecGroup(rec -> rec.addSubFinalStruct(fields -> fields.addField(false, w -> w.write(Type.I32))))
		.addFunc(new Type[] { Type.EQ }, new Type[] { Type.I32 })
		.addFunc(new Type[] {}, new Type[] { Type.I32 })
		.addRecGroup(rec -> rec.addSubFinalStruct(fields -> fields.addField(false, w -> w.write(Type.F64))));

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

	private static byte[] body(Consumer<WasmWriter> instructions) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.write(0); // no locals
		instructions.accept(w);
		w.write(Instruction.END);
		return out.toByteArray();
	}

	private static void local(WasmWriter w, int index) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(index);
	}

	private static void refTest(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(heapType);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void call(WasmWriter w, int function) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(function);
	}

	// i32.const v; ref.i31 -- an i31 argument.
	private static void i31(WasmWriter w, int value) {
		i32(w, value);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	// The decoded instruction stream of the definedIndex-th function of the module.
	private static List<Instr> code(byte[] module, int definedIndex) {
		List<WasmSections.Section> sections = WasmSections.parseSections(module);
		TypeSection types = WasmCodeModel
			.parseTypeSection(java.util.Objects.requireNonNull(WasmSections.find(sections, 1)).payload());
		byte[] entry = WasmSections
			.parseCodeEntries(java.util.Objects.requireNonNull(WasmSections.find(sections, 10)).payload())
			.get(definedIndex);
		return WasmCodeModel.decode(entry, types).code();
	}

	private static List<String> mnemonics(List<Instr> code) {
		List<String> out = new ArrayList<>();
		for (Instr in : code) {
			out.add(switch (in.op) {
				case 0x0B -> "end";
				case 0x02 -> "block";
				case 0x04 -> "if";
				case 0x05 -> "else";
				case 0x0C -> "br " + in.a;
				case 0x00 -> "unreachable";
				case 0x10 -> "call " + in.a;
				case 0x20 -> "local.get " + in.a;
				case 0x41 -> "i32.const " + in.a;
				case 0x72 -> "i32.or";
				case 0xFB -> switch (in.sub) {
					case 0x14 -> "ref.test " + in.a;
					case 0x16 -> "ref.cast " + in.a;
					case 0x18 -> "br_on_cast " + in.a;
					case 0x19 -> "br_on_cast_fail " + in.a;
					case 0x1C -> "ref.i31";
					case 0x00 -> "struct.new " + in.a;
					case 0x02 -> "struct.get " + in.a + "." + in.b;
					default -> String.format("gc 0x%02X", in.sub);
				};
				default -> String.format("0x%02X", in.op);
			});
		}
		return out;
	}

	@Test
	void decidesAGuardAsOneQuestionAboutTheLocal() throws Exception {
		// f answers `x is i31 or x is struct{i32}`; its only caller hands it an i31, so
		// the two tests about local 0 combine into one question the local's set decides
		// -- the whole guard, the if and the dead arm fold to the constant.
		byte[] f = body(w -> {
			local(w, 0);
			refTest(w, Type.I31.code());
			local(w, 0);
			refTest(w, 0);
			w.write(Instruction.I32_OR);
			w.write(Instruction.IF, Type.I32.code());
			i32(w, 1);
			w.write(Instruction.ELSE);
			i32(w, 0);
			w.write(Instruction.END);
		});
		byte[] g = body(w -> {
			i31(w, 5);
			call(w, 0);
		});
		byte[] folded = WasmRefTypeFolder.fold(module(new int[] { 1, 2 }, List.of(f, g), Map.of("g", 1)));

		assertThat(mnemonics(code(folded, 0))).containsExactly("i32.const 1", "end");
		assertThat(validateAndInvoke(folded, "g")).isEqualTo("1");
	}

	@Test
	void keepsAGuardTheSetsCannotDecideAndDropsOnlyTheDecidedHalf() throws Exception {
		// Called with an i31 AND with null, local 0 is {i31, null}: the i31 test stays a
		// question, the struct test is decided (no struct is ever made) and vanishes
		// with its `or`.
		byte[] f = body(w -> {
			local(w, 0);
			refTest(w, Type.I31.code());
			local(w, 0);
			refTest(w, 0);
			w.write(Instruction.I32_OR);
			w.write(Instruction.IF, Type.I32.code());
			i32(w, 1);
			w.write(Instruction.ELSE);
			i32(w, 0);
			w.write(Instruction.END);
		});
		byte[] g = body(w -> {
			i31(w, 5);
			call(w, 0);
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
			call(w, 0);
			w.write(Instruction.I32_ADD);
		});
		byte[] folded = WasmRefTypeFolder.fold(module(new int[] { 1, 2 }, List.of(f, g), Map.of("g", 1)));

		assertThat(mnemonics(code(folded, 0))).containsExactly("local.get 0", "ref.test " + WasmCodeModel.HEAP_I31,
				"if", "i32.const 1", "else", "i32.const 0", "end", "end");
		assertThat(validateAndInvoke(folded, "g")).isEqualTo("1");
	}

	@Test
	void refinesTheLocalInsideTheArmTheGuardSelects() throws Exception {
		// Local 0 is {i31, struct{i32}}: the outer `x is i31` cannot fold, but inside its
		// then-arm x IS an i31, so the inner `x is struct{i32}` is decided there.
		byte[] f = body(w -> {
			local(w, 0);
			refTest(w, Type.I31.code());
			w.write(Instruction.IF, Type.I32.code());
			local(w, 0);
			refTest(w, 0);
			w.write(Instruction.IF, Type.I32.code());
			i32(w, 9);
			w.write(Instruction.ELSE);
			i32(w, 1);
			w.write(Instruction.END);
			w.write(Instruction.ELSE);
			i32(w, 0);
			w.write(Instruction.END);
		});
		byte[] g = body(w -> {
			i31(w, 5);
			call(w, 0);
			i32(w, 7);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			w.writeUnsignedLeb128(0);
			call(w, 0);
			w.write(Instruction.I32_ADD);
		});
		byte[] folded = WasmRefTypeFolder.fold(module(new int[] { 1, 2 }, List.of(f, g), Map.of("g", 1)));

		assertThat(mnemonics(code(folded, 0))).containsExactly("local.get 0", "ref.test " + WasmCodeModel.HEAP_I31,
				"if", "i32.const 1", "else", "i32.const 0", "end", "end");
		assertThat(validateAndInvoke(folded, "g")).isEqualTo("1");
	}

	@Test
	void splicesAFoldedArmInWithoutItsLabelAndReindexesTheBranchesCrossingIt() throws Exception {
		// The if is decided, no branch targets it, so its arm is spliced in bare: the
		// `br 1` that used to cross the if's label to reach the block now crosses
		// nothing and becomes `br 0`.
		byte[] g = body(w -> {
			w.write(Instruction.BLOCK, Type.I32.code());
			i31(w, 5);
			refTest(w, Type.I31.code());
			w.write(Instruction.IF, Type.I32.code());
			i32(w, 7);
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(1);
			w.write(Instruction.ELSE);
			i32(w, 0);
			w.write(Instruction.END);
			w.write(Instruction.END);
		});
		byte[] folded = WasmRefTypeFolder.fold(module(new int[] { 2 }, List.of(g), Map.of("g", 0)));

		assertThat(mnemonics(code(folded, 0))).containsExactly("block", "i32.const 7", "br 0", "end", "end");
		assertThat(validateAndInvoke(folded, "g")).isEqualTo("7");
	}

	@Test
	void keepsTheLabelOfAFoldedArmSomethingBranchesTo() throws Exception {
		// Same fold, but the arm branches to the if's own label (`br 0` inside it): the
		// block wrapper has to stay so the branch keeps a target.
		byte[] g = body(w -> {
			i31(w, 5);
			refTest(w, Type.I31.code());
			w.write(Instruction.IF, Type.I32.code());
			i32(w, 7);
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.ELSE);
			i32(w, 0);
			w.write(Instruction.END);
		});
		byte[] folded = WasmRefTypeFolder.fold(module(new int[] { 2 }, List.of(g), Map.of("g", 0)));

		assertThat(mnemonics(code(folded, 0))).containsExactly("block", "i32.const 7", "br 0", "end", "end");
		assertThat(validateAndInvoke(folded, "g")).isEqualTo("7");
	}

	@Test
	void turnsACastNoValueCanPassIntoATrap() throws Exception {
		// f casts its argument to struct{i32}, and the only value it is ever handed is
		// an i31: the cast can only fail, so it becomes the trap it always was and the
		// field read behind it goes.
		byte[] f = body(w -> {
			local(w, 0);
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(0);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			w.writeUnsignedLeb128(0);
			w.writeUnsignedLeb128(0);
		});
		byte[] g = body(w -> {
			i31(w, 5);
			call(w, 0);
		});
		byte[] folded = WasmRefTypeFolder.fold(module(new int[] { 1, 2 }, List.of(f, g), Map.of("g", 1)));

		assertThat(mnemonics(code(folded, 0))).containsExactly("local.get 0", "unreachable", "end");
		assumeTrue(onPath("wasmtime"), "wasmtime not on PATH; skipping execution");
		Process process = wasmtime(folded, "g");
		String output = new String(process.getErrorStream().readAllBytes());
		assertThat(process.waitFor()).as("the folded module must still trap:%n%s", output).isNotZero();
	}

	// br_on_cast_fail DEPTH eqref (ref TYPE): leaves for the label with the operand when
	// it is not a (ref TYPE), falls through with it cast otherwise.
	private static void brOnCastFail(WasmWriter w, int depth, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.BR_ON_CAST_FAIL);
		w.write(0x01); // the operand nullable, the cast not
		w.writeUnsignedLeb128(depth);
		w.writeHeapType(Type.EQ.code());
		w.writeHeapType(heapType);
	}

	// `block (result eqref) local.get 0; br_on_cast_fail 0 (ref 0); <fallThrough> end;
	// drop; i32.const -1`: the value a struct{i32} argument answers, or -1.
	private static byte[] castBranch(Consumer<WasmWriter> fallThrough) {
		return body(w -> {
			w.write(Instruction.BLOCK, Type.EQ.code());
			local(w, 0);
			brOnCastFail(w, 0, 0);
			fallThrough.accept(w);
			w.write(Instruction.END);
			w.write(Instruction.DROP);
			i32(w, -1);
		});
	}

	private static void readFieldAndReturn(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(0);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.RETURN);
	}

	private static void struct(WasmWriter w, int value) {
		i32(w, value);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(0);
	}

	@Test
	void aCastBranchNoValueFailsBecomesTheCastItFallsThroughTo() throws Exception {
		// Only a struct{i32} ever reaches f: the branch is never taken, so what is left
		// is
		// the cast its fall-through is -- and the label nothing reaches any more.
		byte[] f = castBranch(WasmRefTypeFolderTest::readFieldAndReturn);
		byte[] g = body(w -> {
			struct(w, 7);
			call(w, 0);
		});
		byte[] folded = WasmRefTypeFolder.fold(module(new int[] { 1, 2 }, List.of(f, g), Map.of("g", 1)));

		assertThat(mnemonics(code(folded, 0))).containsExactly("block", "local.get 0", "ref.cast 0", "struct.get 0.0",
				"0x0F", "end", "unreachable", "end");
		assertThat(validateAndInvoke(folded, "g")).isEqualTo("7");
	}

	@Test
	void aCastBranchEveryValueFailsBecomesAPlainBranch() throws Exception {
		// Only an i31 ever reaches f: the branch is always taken -- a br, and the
		// fall-through behind it is dead.
		byte[] f = castBranch(WasmRefTypeFolderTest::readFieldAndReturn);
		byte[] g = body(w -> {
			i31(w, 5);
			call(w, 0);
		});
		byte[] folded = WasmRefTypeFolder.fold(module(new int[] { 1, 2 }, List.of(f, g), Map.of("g", 1)));

		assertThat(mnemonics(code(folded, 0))).containsExactly("block", "local.get 0", "br 0", "end", "0x1A",
				"i32.const -1", "end");
		assertThat(validateAndInvoke(folded, "g")).isEqualTo("-1");
	}

	@Test
	void keepsACastBranchTheSetsCannotDecideAndRefinesTheLocalItFallsThroughWith() throws Exception {
		// f is handed an i31 and a struct{i32}: the branch stays. Past it the local IS a
		// struct{i32} -- the fall-through is a guard, as a br_if's is -- so the test of
		// the same local there is decided.
		byte[] f = castBranch(w -> {
			w.write(Instruction.DROP);
			local(w, 0);
			refTest(w, 0);
			w.write(Instruction.RETURN);
		});
		byte[] g = body(w -> {
			i31(w, 5);
			call(w, 0);
			struct(w, 7);
			call(w, 0);
			w.write(Instruction.I32_ADD);
		});
		byte[] folded = WasmRefTypeFolder.fold(module(new int[] { 1, 2 }, List.of(f, g), Map.of("g", 1)));

		assertThat(mnemonics(code(folded, 0))).containsExactly("block", "local.get 0", "br_on_cast_fail 0", "0x1A",
				"i32.const 1", "0x0F", "end", "0x1A", "i32.const -1", "end");
		assertThat(validateAndInvoke(folded, "g")).isEqualTo("0");
	}

	@Test
	void reindexesACastBranchCrossingASplicedInArm() throws Exception {
		// The if is decided and spliced in bare: the cast branch that crossed its label
		// to reach the block crosses nothing now, and names the block one label closer.
		byte[] f = body(w -> {
			w.write(Instruction.BLOCK, Type.EQ.code());
			i31(w, 5);
			refTest(w, Type.I31.code());
			w.write(Instruction.IF, Type.I32.code());
			local(w, 0);
			brOnCastFail(w, 1, 0);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			w.writeUnsignedLeb128(0);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.ELSE);
			i32(w, 0);
			w.write(Instruction.END);
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
			w.write(Instruction.DROP);
			i32(w, -1);
		});
		byte[] g = body(w -> {
			i31(w, 5);
			call(w, 0);
			struct(w, 7);
			call(w, 0);
			w.write(Instruction.I32_ADD);
		});
		byte[] folded = WasmRefTypeFolder.fold(module(new int[] { 1, 2 }, List.of(f, g), Map.of("g", 1)));

		assertThat(mnemonics(code(folded, 0))).containsExactly("block", "local.get 0", "br_on_cast_fail 0",
				"struct.get 0.0", "0x0F", "end", "0x1A", "i32.const -1", "end");
		assertThat(validateAndInvoke(folded, "g")).isEqualTo("6");
	}

	@Test
	void leavesAModuleWithAReferenceTypedBoundaryAlone() {
		// An exported function taking eqref is a door a host could hand any value
		// through; the licence (a closed module) does not hold, and the pass declines.
		byte[] f = body(w -> {
			local(w, 0);
			refTest(w, Type.I31.code());
		});
		byte[] module = module(new int[] { 1 }, List.of(f), Map.of("f", 0));

		assertThat(WasmRefTypeFolder.fold(module)).isSameAs(module);
	}

	@Test
	void theIntegerOnlyReactorLosesTheFloatAndRationalTiers() {
		// The .todo/790 reactor: three operators on a value that entered as an :s32 and
		// never leaves the exact tiers. Reachability alone keeps every arm of the generic
		// arithmetic (a float or ratio operand is a branch, not a call); the fold proves
		// those arms dead, and the float struct disappears from the module with them.
		String source = """
				(defun fib (n) (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))
				(rontolisp:wasm-export 'fib :as "RunComputation" :params '(:s32) :returns :s32)
				""";
		List<LispVal> program = LispReader.readAllFromString(source);
		byte[] shakenOnly = WasmTreeShaker
			.shake(WasmLispCompiler.builder().noWasi(true).optimize(OptimizeLevel.NONE).build().compile(program));
		byte[] folded = WasmLispCompiler.builder().noWasi(true).optimize(OptimizeLevel.SIZE).build().compile(program);

		assertThat(folded.length).as("folded %d vs shaken-only %d", folded.length, shakenOnly.length)
			.isLessThan(shakenOnly.length * 65 / 100);
		// The float struct's rec group survives whole (a group is atomic to the shaker),
		// so the probe is the TESTS: no surviving body asks "is it a float" any more.
		assertThat(floatTests(shakenOnly)).isPositive();
		assertThat(floatTests(folded)).isZero();
		// The compiler's output is already folded: a second pass prunes nothing. It is
		// not BYTE-identical any more, and that is not a fold that missed something: the
		// peepholes that run behind the fold delete the explicit `unreachable` it writes
		// after a loop that cannot terminate, and a second fold writes it back
		// (.kb/optimize-dead-code-elimination.md, "The adjacent-instruction peepholes").
		// What the second pass produces is where the fold itself stops.
		byte[] refolded = WasmRefTypeFolder.fold(folded);
		assertThat(floatTests(refolded)).isZero();
		assertThat(WasmRefTypeFolder.fold(refolded)).isSameAs(refolded);
	}

	// How many ref.test / ref.cast instructions across the module name a struct whose one
	// field is an f64 (the backend's TYPE_FLOAT).
	private static int floatTests(byte[] module) {
		List<WasmSections.Section> sections = WasmSections.parseSections(module);
		TypeSection types = WasmCodeModel
			.parseTypeSection(java.util.Objects.requireNonNull(WasmSections.find(sections, 1)).payload());
		int count = 0;
		for (byte[] entry : WasmSections
			.parseCodeEntries(java.util.Objects.requireNonNull(WasmSections.find(sections, 10)).payload())) {
			for (Instr in : WasmCodeModel.decode(entry, types).code()) {
				if (in.op == 0xFB && (in.sub == 0x14 || in.sub == 0x16) && in.a >= 0
						&& types.types().get((int) in.a) instanceof StructType s && s.fields().size() == 1
						&& s.fields().get(0).storage().code() == 0x7C) {
					count++;
				}
			}
		}
		return count;
	}

	// Validates the module with wasm-tools and returns what wasmtime prints for the
	// export; assumptions skip the halves whose tool is missing.
	private String validateAndInvoke(byte[] module, String export) throws Exception {
		assumeTrue(onPath("wasm-tools"), "wasm-tools not on PATH; skipping validation");
		Path file = this.tempDir.resolve("folded.wasm");
		Files.write(file, module);
		Process validate = new ProcessBuilder("wasm-tools", "validate", "-f", "gc", file.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(validate.getInputStream().readAllBytes());
		assertThat(validate.waitFor()).as("wasm-tools validate failed:%n%s", output).isZero();
		assumeTrue(onPath("wasmtime"), "wasmtime not on PATH; skipping execution");
		Process run = wasmtime(module, export);
		String stdout = new String(run.getInputStream().readAllBytes());
		String stderr = new String(run.getErrorStream().readAllBytes());
		assertThat(run.waitFor()).as("wasmtime run failed:%n%s", stderr).isZero();
		return stdout.trim();
	}

	private Process wasmtime(byte[] module, String export) throws Exception {
		Path file = this.tempDir.resolve("run.wasm");
		Files.write(file, module);
		// stderr stays separate: wasmtime warns there that --invoke is experimental.
		return new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "--invoke", export, file.toString()).start();
	}

	private static boolean onPath(String command) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(java.io.File.pathSeparator)) {
			if (Files.isExecutable(Path.of(dir, command))) {
				return true;
			}
		}
		return false;
	}

}
