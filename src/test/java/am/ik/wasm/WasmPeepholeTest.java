package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import am.ik.wasm.WasmCodeModel.Instr;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adjacent-instruction peepholes, one shape per test on a hand-assembled body: what
 * each rule rewrites, that a rewrite the rule enables is taken in the same pass, and the
 * two things that must stop a rule -- a block boundary between the pair, and a loop tail
 * whose {@code unreachable} the type checker or the program still needs.
 */
class WasmPeepholeTest {

	// Type 0: (i32) -> i32; type 1: () -> (); type 2: () -> i32; type 3: () -> eqref.
	private static final Consumer<TypeDef> TYPES = types -> types
		.addFunc(new Type[] { Type.I32 }, new Type[] { Type.I32 })
		.addFunc(new Type[] {}, new Type[] {})
		.addFunc(new Type[] {}, new Type[] { Type.I32 })
		.addFunc(new Type[] {}, new Type[] { Type.EQ });

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

	private static void local(WasmWriter w, int opcode, int index) {
		w.write(opcode);
		w.writeUnsignedLeb128(index);
	}

	/** The defined function's instruction stream, as {@code "name immediate"} rows. */
	private static List<String> code(byte[] module, int definedIndex) {
		List<WasmSections.Section> sections = WasmSections.parseSections(module);
		WasmCodeModel.TypeSection types = WasmCodeModel
			.parseTypeSection(java.util.Objects.requireNonNull(WasmSections.find(sections, 1)).payload());
		byte[] entry = WasmSections
			.parseCodeEntries(java.util.Objects.requireNonNull(WasmSections.find(sections, 10)).payload())
			.get(definedIndex);
		List<String> out = new ArrayList<>();
		for (Instr in : WasmCodeModel.decode(entry, types).code()) {
			out.add(switch (in.op) {
				case Instruction.UNREACHABLE -> "unreachable";
				case Instruction.BLOCK -> "block";
				case Instruction.LOOP -> "loop";
				case Instruction.END -> "end";
				case Instruction.BR -> "br " + in.a;
				case Instruction.DROP -> "drop";
				case Instruction.GET_LOCAL -> "local.get " + in.a;
				case Instruction.SET_LOCAL -> "local.set " + in.a;
				case Instruction.TEE_LOCAL -> "local.tee " + in.a;
				case Instruction.I32_CONST -> "i32.const";
				case Instruction.I32_ADD -> "i32.add";
				case Instruction.I32_AND -> "i32.and";
				case Instruction.I32_EQZ -> "i32.eqz";
				case Instruction.IF -> "if";
				case Instruction.ELSE -> "else";
				case Instruction.BR_IF -> "br_if " + in.a;
				case Instruction.CALL -> "call " + in.a;
				case Instruction.REF_NULL -> "ref.null";
				case Instruction.REF_IS_NULL -> "ref.is_null";
				default -> String.format("0x%02X", in.op);
			});
		}
		return out;
	}

	@Test
	void foldsAStoreAndTheMatchingLoadIntoATee() {
		// The two-value shape too: `set b; set a; get a; get b` is this rule's pair with
		// one instruction on either side of it.
		byte[] one = body(1, w -> {
			local(w, Instruction.GET_LOCAL, 0);
			local(w, Instruction.SET_LOCAL, 1);
			local(w, Instruction.GET_LOCAL, 1);
		});
		byte[] two = body(2, w -> {
			local(w, Instruction.GET_LOCAL, 0);
			local(w, Instruction.GET_LOCAL, 0);
			local(w, Instruction.SET_LOCAL, 2);
			local(w, Instruction.SET_LOCAL, 1);
			local(w, Instruction.GET_LOCAL, 1);
			local(w, Instruction.GET_LOCAL, 2);
			w.write(Instruction.I32_ADD);
		});
		byte[] rewritten = WasmPeephole.rewrite(module(new int[] { 0, 0 }, List.of(one, two)));

		assertThat(code(rewritten, 0)).containsExactly("local.get 0", "local.tee 1", "end");
		assertThat(code(rewritten, 1)).containsExactly("local.get 0", "local.get 0", "local.set 2", "local.tee 1",
				"local.get 2", "i32.add", "end");
	}

	@Test
	void foldsATeeThatIsDroppedBackIntoAStore() {
		// Both the tee that was written as one and the tee the rule above has just made:
		// a statement-position assignment comes back as the plain store it started as.
		byte[] written = body(2, w -> {
			local(w, Instruction.GET_LOCAL, 0);
			local(w, Instruction.TEE_LOCAL, 1);
			w.write(Instruction.DROP);
		});
		byte[] made = body(2, w -> {
			local(w, Instruction.GET_LOCAL, 0);
			local(w, Instruction.SET_LOCAL, 1);
			local(w, Instruction.GET_LOCAL, 1);
			w.write(Instruction.DROP);
		});
		byte[] rewritten = WasmPeephole.rewrite(module(new int[] { 1, 1 }, List.of(written, made)));

		assertThat(code(rewritten, 0)).containsExactly("local.get 0", "local.set 1", "end");
		assertThat(code(rewritten, 1)).containsExactly("local.get 0", "local.set 1", "end");
	}

	@Test
	void deletesAPureValueThatIsOnlyDropped() {
		// And keeps going: with the constant and its drop gone, the store and the load
		// around them are adjacent, so the tee rule sees them.
		byte[] entry = body(1, w -> {
			local(w, Instruction.GET_LOCAL, 0);
			local(w, Instruction.SET_LOCAL, 1);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(7);
			w.write(Instruction.DROP);
			local(w, Instruction.GET_LOCAL, 1);
		});
		byte[] rewritten = WasmPeephole.rewrite(module(new int[] { 0 }, List.of(entry)));

		assertThat(code(rewritten, 0)).containsExactly("local.get 0", "local.tee 1", "end");
	}

	@Test
	void keepsAPairThatABlockBoundarySeparates() {
		// The load starts a block body, so it is not the store's consumer -- and the
		// store's own value has to stay on the stack for nobody.
		byte[] entry = body(2, w -> {
			local(w, Instruction.GET_LOCAL, 0);
			local(w, Instruction.SET_LOCAL, 1);
			w.write(Instruction.BLOCK);
			w.write(0x40);
			local(w, Instruction.GET_LOCAL, 1);
			w.write(Instruction.DROP);
			w.write(Instruction.END);
		});
		byte[] module = module(new int[] { 1 }, List.of(entry));

		// The `local.get 1; drop` inside the block still goes: that pair IS adjacent.
		assertThat(code(WasmPeephole.rewrite(module), 0)).containsExactly("local.get 0", "local.set 1", "block", "end",
				"end");
	}

	// `if (result eqref) call tSym else ref.null eq end`: an i32 boxed into a language's
	// true object (tSym, a pure non-null producer) or its nil.
	private static void boxedTruth(WasmWriter w, int tSym) {
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(tSym);
		w.write(Instruction.ELSE);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.END);
	}

	@Test
	void foldsABoxedTruthThatIsOnlyTestedForNullIntoTheTestOfItsCondition() {
		// The box is null exactly when the condition was 0, so `ref.is_null` of the box
		// is `i32.eqz` of the condition -- but only when the caller vouches for the
		// call: without that, the call may be what the program is there for.
		byte[] tested = body(0, w -> {
			local(w, Instruction.GET_LOCAL, 0);
			boxedTruth(w, 1);
			w.write(Instruction.REF_IS_NULL);
		});
		byte[] tSym = body(0, w -> {
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
		});
		byte[] module = module(new int[] { 0, 3 }, List.of(tested, tSym));

		assertThat(code(WasmPeephole.rewrite(module, f -> f == 1), 0)).containsExactly("local.get 0", "i32.eqz", "end");
		assertThat(WasmPeephole.rewrite(module)).isSameAs(module);
	}

	@Test
	void deletesADoubleNegationOnlyABranchConsumes() {
		// An `if`/`br_if` asks whether the operand is zero, which two negations leave
		// as it was; an `i32.and` would see the value itself, so that pair stays. The
		// third body is the chain a re-tested box leaves in front of its consumer's own
		// negation: the first rule makes the pair, this one takes it, in one pass.
		byte[] branched = body(0, w -> {
			local(w, Instruction.GET_LOCAL, 0);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.IF);
			w.write(Type.I32);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.ELSE);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
			w.write(Instruction.END);
		});
		byte[] valued = body(0, w -> {
			local(w, Instruction.GET_LOCAL, 0);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_AND);
		});
		byte[] chained = body(0, w -> {
			w.write(Instruction.BLOCK);
			w.write(0x40);
			local(w, Instruction.GET_LOCAL, 0);
			boxedTruth(w, 3);
			w.write(Instruction.REF_IS_NULL);
			w.write(Instruction.I32_EQZ);
			w.write(Instruction.BR_IF);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.END);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(0);
		});
		byte[] tSym = body(0, w -> {
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
		});
		byte[] rewritten = WasmPeephole
			.rewrite(module(new int[] { 0, 0, 0, 3 }, List.of(branched, valued, chained, tSym)), f -> f == 3);

		assertThat(code(rewritten, 0)).containsExactly("local.get 0", "if", "i32.const", "else", "i32.const", "end",
				"end");
		assertThat(code(rewritten, 1)).containsExactly("local.get 0", "i32.eqz", "i32.eqz", "i32.const", "i32.and",
				"end");
		assertThat(code(rewritten, 2)).containsExactly("block", "local.get 0", "br_if 0", "end", "i32.const", "end");
	}

	@Test
	void deletesTheTrapAfterALoopThatNeverEnds() {
		// `block; loop; br 0; end; unreachable; end`: the loop's last instruction is the
		// branch back, so nothing falls out of it and nothing reaches the trap; the
		// enclosing block leaves nothing, so the type checker does not need it either.
		byte[] entry = body(0, w -> {
			w.write(Instruction.BLOCK);
			w.write(0x40);
			w.write(Instruction.LOOP);
			w.write(0x40);
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.END);
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
		});
		byte[] rewritten = WasmPeephole.rewrite(module(new int[] { 1 }, List.of(entry)));

		assertThat(code(rewritten, 0)).containsExactly("block", "loop", "br 0", "end", "end", "end");
	}

	@Test
	void keepsATrapTheBlockStructureStillNeeds() {
		// A plain block instead of a loop: `br 0` LEAVES it, so the trap is reachable
		// and deleting it would let the program run on.
		byte[] escapable = body(0, w -> {
			w.write(Instruction.BLOCK);
			w.write(0x40);
			w.write(Instruction.BLOCK);
			w.write(0x40);
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.END);
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
		});
		// A loop that is not the whole of the enclosing block: a value of the enclosing
		// block's own is on the stack, and only the trap makes the `end` below accept it.
		byte[] loaded = body(0, w -> {
			w.write(Instruction.BLOCK);
			w.write(0x40);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.LOOP);
			w.write(0x40);
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(0);
			w.write(Instruction.END);
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
		});
		byte[] module = module(new int[] { 1, 1 }, List.of(escapable, loaded));

		assertThat(WasmPeephole.rewrite(module)).isSameAs(module);
	}

}
