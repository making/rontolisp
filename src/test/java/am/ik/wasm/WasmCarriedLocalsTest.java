package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import am.ik.wasm.WasmCarriedLocals.Carry;
import am.ik.wasm.WasmCodeModel.Instr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The carried-local narrowing on hand-assembled bodies shaped like a landing pad: a run
 * of {@code local.get} in front of a landing {@code block}, a {@code try_table} inside it
 * whose catch clause targets that block, and behind the block's {@code end} the payload
 * store and the run of {@code local.set} that writes the carried locals back. What
 * survives is exactly the carried locals some path reads after the write-back; the
 * narrowed body must still be a valid module, which {@code wasm-tools} checks when it is
 * on the {@code PATH}.
 */
class WasmCarriedLocalsTest {

	@TempDir
	Path tempDir;

	// Type 0: (i32) -> (), the tag's payload; type 1: (i32) -> i32, the function under
	// test; type 2: () -> (), the callee that may throw.
	private static final int TAG_TYPE = 0;

	private static final int TESTED_TYPE = 1;

	private static final int THROWER_TYPE = 2;

	/** Function 0: the thrower, {@code i32.const 1; throw 0}. */
	private static final int THROWER = 0;

	/**
	 * A body under construction, all of whose locals are i32: the instructions, and the
	 * byte spans of every carry it has closed.
	 */
	private static final class Asm {

		final ByteArrayOutputStream out = new ByteArrayOutputStream();

		final WasmWriter w = new WasmWriter(this.out);

		final List<Carry> carries = new ArrayList<>();

		Asm(int locals) {
			this.w.write(1);
			this.w.writeUnsignedLeb128(locals);
			this.w.write(Type.I32);
		}

		int size() {
			return this.out.size();
		}

		Asm op(int opcode, int immediate) {
			this.w.write(opcode);
			this.w.writeUnsignedLeb128(immediate);
			return this;
		}

		Asm get(int local) {
			return op(Instruction.GET_LOCAL, local);
		}

		Asm set(int local) {
			return op(Instruction.SET_LOCAL, local);
		}

		Asm constant(int value) {
			this.w.write(Instruction.I32_CONST);
			this.w.writeSignedLeb128(value);
			return this;
		}

		Asm raw(int... bytes) {
			for (int b : bytes) {
				this.w.write(b);
			}
			return this;
		}

		/**
		 * A protected region as the compilers lay it out:
		 * {@code block $done (result i32)}, the push, {@code block $h (result i32)},
		 * {@code try_table (catch 0 $h)} around {@code body} (which leaves nothing), the
		 * normal exit {@code i32.const 0; br
		 * $done}, then the pad: the payload into {@code payload}, the write-back, and
		 * {@code pad} (which leaves nothing) before falling into {@code $done} with 0.
		 * @param carried the locals pushed, in push order
		 */
		Asm region(int[] carried, int payload, Consumer<Asm> body, Consumer<Asm> pad) {
			raw(Instruction.BLOCK, Type.I32.code()); // $done
			int pushStart = size();
			for (int local : carried) {
				get(local);
			}
			int pushEnd = size();
			raw(Instruction.BLOCK, Type.I32.code()); // $h
			raw(Instruction.TRY_TABLE, Type.I32.code(), 1, Instruction.CATCH, 0, 0);
			body.accept(this);
			constant(0);
			raw(Instruction.END); // try_table
			op(Instruction.BR, 1); // $done, discarding the pushed values
			raw(Instruction.END); // $h: the payload is on the stack
			set(payload);
			int popStart = size();
			for (int i = carried.length - 1; i >= 0; i--) {
				set(carried[i]);
			}
			this.carries.add(new Carry(pushStart, pushEnd, popStart, size()));
			pad.accept(this);
			constant(0);
			raw(Instruction.END); // $done
			return this;
		}

		/** A call to the thrower. */
		Asm call() {
			return op(Instruction.CALL, THROWER);
		}

		/** Reads a local for effect. */
		Asm use(int local) {
			get(local);
			raw(Instruction.DROP);
			return this;
		}

		byte[] entry() {
			raw(Instruction.END);
			return this.out.toByteArray();
		}

	}

	private static void nothing(Asm a) {
	}

	/** The module around one tested function body, for the validator. */
	private static byte[] module(byte[] tested) {
		ByteArrayOutputStream thrower = new ByteArrayOutputStream();
		new WasmWriter(thrower).write(0)
			.write(Instruction.I32_CONST)
			.writeSignedLeb128(1)
			.write(Instruction.THROW)
			.writeUnsignedLeb128(0)
			.write(Instruction.END);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new WasmWriter(out).write("\0asm")
			.writeLittleEndian4(1)
			.writeTypeSection(types -> types.addFunc(new Type[] { Type.I32 }, new Type[] {})
				.addFunc(new Type[] { Type.I32 }, new Type[] { Type.I32 })
				.addFunc(new Type[] {}, new Type[] {}))
			.writeFunction(functions -> functions.addFunction(THROWER_TYPE).addFunction(TESTED_TYPE))
			.writeTagSection(tags -> tags.addTag(TAG_TYPE))
			.writeCode(code -> code.addFunction(thrower.toByteArray()).addFunction(tested));
		return out.toByteArray();
	}

	private void validate(byte[] entry) throws IOException, InterruptedException {
		if (!onPath("wasm-tools")) {
			return;
		}
		Path file = this.tempDir.resolve("carried.wasm");
		Files.write(file, module(entry));
		Process process = new ProcessBuilder("wasm-tools", "validate", "-f", "exceptions", file.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as("wasm-tools validate failed:%n%s", output).isZero();
	}

	private static boolean onPath(String tool) {
		try {
			return new ProcessBuilder("which", tool).start().waitFor() == 0;
		}
		catch (IOException | InterruptedException ex) {
			return false;
		}
	}

	/**
	 * The locals each landing block still carries, in push order: the {@code local.get}
	 * run right in front of every block a {@code try_table} catches to.
	 */
	private static List<List<Long>> pushes(byte[] entry) {
		List<Instr> code = WasmCodeModel.decodeStructure(entry).code();
		List<List<Long>> out = new ArrayList<>();
		for (int i = 1; i < code.size(); i++) {
			if (code.get(i).op == Instruction.TRY_TABLE && code.get(i - 1).op == Instruction.BLOCK) {
				List<Long> run = new ArrayList<>();
				for (int k = i - 2; k >= 0 && code.get(k).op == Instruction.GET_LOCAL; k--) {
					run.add(0, code.get(k).a);
				}
				out.add(run);
			}
		}
		return out;
	}

	/**
	 * The locals each pad still writes back, in write order: the {@code local.set} run
	 * after the payload store behind every landing block.
	 */
	private static List<List<Long>> refreshes(byte[] entry) {
		List<Instr> code = WasmCodeModel.decodeStructure(entry).code();
		List<List<Long>> out = new ArrayList<>();
		for (int i = 1; i < code.size(); i++) {
			if (code.get(i).op == Instruction.TRY_TABLE && code.get(i - 1).op == Instruction.BLOCK) {
				List<Long> run = new ArrayList<>();
				for (int k = code.get(i - 1).match + 2; code.get(k).op == Instruction.SET_LOCAL; k++) {
					run.add(code.get(k).a);
				}
				out.add(run);
			}
		}
		return out;
	}

	@Test
	void aCarriedLocalThePadNeverReadsLeavesBothRuns() throws Exception {
		// Locals 0 (the parameter) .. 4, all carried; the pad reads 2 only, and 3 is
		// written before its read. What stays is 2, pushed once and written back once.
		Asm a = new Asm(4);
		a.region(new int[] { 0, 1, 2, 3 }, 4, Asm::call, pad -> pad.use(2).constant(9).set(3).use(3));
		byte[] entry = a.entry();

		byte[] narrowed = WasmCarriedLocals.narrow(entry, a.carries);

		assertThat(pushes(narrowed)).containsExactly(List.of(2L));
		assertThat(refreshes(narrowed)).containsExactly(List.of(2L));
		assertThat(narrowed.length).isEqualTo(entry.length - 12);
		validate(entry);
		validate(narrowed);
	}

	@Test
	void everyCarriedLocalLiveReturnsTheEntryItself() {
		Asm a = new Asm(3);
		a.region(new int[] { 1, 2 }, 3, Asm::call, pad -> pad.use(1).use(2));
		byte[] entry = a.entry();

		assertThat(WasmCarriedLocals.narrow(entry, a.carries)).isSameAs(entry);
		assertThat(WasmCarriedLocals.narrow(entry, List.of())).isSameAs(entry);
	}

	@Test
	void aLocalTheNextIterationReadsStaysWhereOneItWritesFirstGoes() throws Exception {
		// loop { read 1; write 2 then read it; region carrying 1 and 2 whose pad reads
		// nothing; br_if back }: after the pad, the back edge reaches the read of 1
		// before any write, and the write of 2 before its read.
		Asm a = new Asm(3);
		a.raw(Instruction.LOOP, 0x40);
		a.use(1).constant(5).set(2).use(2);
		a.region(new int[] { 1, 2 }, 3, Asm::call, WasmCarriedLocalsTest::nothing);
		a.raw(Instruction.DROP);
		a.get(0).op(Instruction.BR_IF, 0);
		a.raw(Instruction.END);
		a.constant(0);
		byte[] entry = a.entry();

		byte[] narrowed = WasmCarriedLocals.narrow(entry, a.carries);

		assertThat(pushes(narrowed)).containsExactly(List.of(1L));
		assertThat(refreshes(narrowed)).containsExactly(List.of(1L));
		validate(narrowed);
	}

	@Test
	void aCallAfterThePadReachesTheEnclosingPadAndWhatItReads() throws Exception {
		// An outer region carrying 0 whose pad reads 1 -- a local it does not carry --
		// around an inner region carrying 0 and 1 whose pad calls the thrower. Nothing
		// but the exceptional edge from that call to the outer pad reads 1 after the
		// inner pad, so the inner region keeps 1 and drops 0; the outer keeps nothing.
		Asm a = new Asm(4);
		a.region(new int[] { 0 }, 2, outer -> {
			outer.constant(7).set(1);
			outer.region(new int[] { 0, 1 }, 3, Asm::call, Asm::call);
			outer.raw(Instruction.DROP);
		}, pad -> pad.use(1));
		byte[] entry = a.entry();

		byte[] narrowed = WasmCarriedLocals.narrow(entry, a.carries);

		assertThat(pushes(narrowed)).containsExactly(List.of(), List.of(1L));
		assertThat(refreshes(narrowed)).containsExactly(List.of(), List.of(1L));
		validate(narrowed);
	}

	@Test
	void aThrowInsideTheBodyReachesThePadToo() throws Exception {
		// The same, with the inner pad throwing instead of calling.
		Asm a = new Asm(4);
		a.region(new int[] { 0 }, 2, outer -> {
			outer.constant(7).set(1);
			outer.region(new int[] { 0, 1 }, 3, Asm::call, pad -> pad.constant(1).op(Instruction.THROW, 0));
			outer.raw(Instruction.DROP);
		}, pad -> pad.use(1));
		byte[] entry = a.entry();

		byte[] narrowed = WasmCarriedLocals.narrow(entry, a.carries);

		assertThat(pushes(narrowed)).containsExactly(List.of(), List.of(1L));
		validate(narrowed);
	}

	@Test
	void keepingALocalAtOnePadMakesItLiveAtAnEarlierOne() throws Exception {
		// Two regions in a row, both carrying 1. Only the second pad reads 1; the first
		// pad's continuation reaches the second region's push, which READS 1 once the
		// second keeps it -- so the first must keep it too, or the second would push a
		// value that reached the first pad through its catch block.
		Asm a = new Asm(3);
		a.region(new int[] { 1 }, 2, Asm::call, WasmCarriedLocalsTest::nothing);
		a.raw(Instruction.DROP);
		a.region(new int[] { 1 }, 2, Asm::call, pad -> pad.use(1));
		byte[] entry = a.entry();

		byte[] narrowed = WasmCarriedLocals.narrow(entry, a.carries);

		assertThat(pushes(narrowed)).containsExactly(List.of(1L), List.of(1L));
		assertThat(narrowed).isSameAs(entry);
		// And with no reader after the second pad, both go.
		Asm b = new Asm(3);
		b.region(new int[] { 1 }, 2, Asm::call, WasmCarriedLocalsTest::nothing);
		b.raw(Instruction.DROP);
		b.region(new int[] { 1 }, 2, Asm::call, WasmCarriedLocalsTest::nothing);
		byte[] unread = b.entry();
		assertThat(pushes(WasmCarriedLocals.narrow(unread, b.carries))).containsExactly(List.of(), List.of());
		validate(WasmCarriedLocals.narrow(unread, b.carries));
	}

	@Test
	void aLocalReadOnlyOnTheNormalPathIsDeadAfterThePad() throws Exception {
		// Local 1 is read after the region, but the pad branches straight out of the
		// function: no path from the pad reaches that read.
		Asm a = new Asm(3);
		a.region(new int[] { 1 }, 2, Asm::call, pad -> pad.constant(0).raw(Instruction.RETURN));
		a.raw(Instruction.DROP);
		a.use(1);
		a.constant(0);
		byte[] entry = a.entry();

		byte[] narrowed = WasmCarriedLocals.narrow(entry, a.carries);

		assertThat(pushes(narrowed)).containsExactly(List.of());
		validate(narrowed);
	}

	@Test
	void aCompiledPadAfterManyDeadLetScopesRefreshesOnlyWhatItReads() {
		// The compilers' own pads, end to end: at emission a pad pushes every local
		// declared so far -- here the closure slot, x, the fused let's m and 24 dead let
		// scopes -- and once the body is complete only x, which the clause reads, is
		// left. The fused (logand (+ x 1) 255) puts i64 scratch references in front of
		// the pad, whose placeholders the local-declaration splice shortens: the recorded
		// spans have to follow them there, or the narrowing refuses them.
		StringBuilder source = new StringBuilder("""
				(defun lp-risky (y) (if (> y 3) (error "boom") y))
				(defun lp-dead-scopes (x)
				  (let ((m (logand (+ x 1) 255))) (print m))
				""");
		for (int i = 1; i <= 24; i++) {
			source.append("  (let ((a")
				.append(i)
				.append(" (list x ")
				.append(i)
				.append("))) (print a")
				.append(i)
				.append("))\n");
		}
		source.append("""
				  (handler-case (lp-risky x)
				    (error () (list x x))))
				(print (lp-dead-scopes 5))
				""");
		List<am.ik.rontolisp.LispVal> program = am.ik.rontolisp.reader.LispReader.readAllFromString(source.toString());
		byte[] module = am.ik.rontolisp.codegen.wasm.WasmLispCompiler.builder()
			.optimize(am.ik.rontolisp.compiler.OptimizeLevel.NONE)
			.build()
			.compile(program);

		List<WasmSections.Section> sections = WasmSections.parseSections(module);
		List<byte[]> entries = WasmSections
			.parseCodeEntries(java.util.Objects.requireNonNull(WasmSections.find(sections, 10)).payload());
		// lp-dead-scopes is the body with a landing pad and the most locals.
		byte[] scopes = null;
		int most = -1;
		for (byte[] entry : entries) {
			WasmCodeModel.Body body = WasmCodeModel.decodeStructure(entry);
			if (!pushes(entry).isEmpty() && body.locals().size() > most) {
				most = body.locals().size();
				scopes = entry;
			}
		}
		assertThat(scopes).isNotNull();
		WasmCodeModel.Body body = WasmCodeModel.decodeStructure(scopes);
		assertThat(body.locals()).as("the dead scopes' locals are declared").hasSizeGreaterThan(24);
		assertThat(body.locals()).as("the fused let's i64 scratch").contains(WasmCodeModel.ValType.I64);
		assertThat(pushes(scopes)).containsExactly(List.of(1L));
		assertThat(refreshes(scopes)).containsExactly(List.of(1L));
	}

	@Test
	void aSpanThatIsNotACarryIsRefused() {
		// The same region with the write-back claimed as the push: the spans are real
		// instructions, but not a push in front of a landing block.
		Asm a = new Asm(3);
		a.region(new int[] { 1, 2 }, 3, Asm::call, pad -> pad.use(1));
		byte[] entry = a.entry();
		Carry carry = a.carries.get(0);
		Carry swapped = new Carry(carry.popStart(), carry.popEnd(), carry.pushStart(), carry.pushEnd());
		assertThatThrownBy(() -> WasmCarriedLocals.narrow(entry, List.of(swapped)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("not a carry");
		// A span that starts inside an instruction.
		Carry inside = new Carry(carry.pushStart() + 1, carry.pushEnd(), carry.popStart(), carry.popEnd());
		assertThatThrownBy(() -> WasmCarriedLocals.narrow(entry, List.of(inside)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("instruction boundaries");
	}

}
