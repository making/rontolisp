package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.wasm.WasmCodeModel.Body;
import am.ik.wasm.WasmCodeModel.BlockType;
import am.ik.wasm.WasmCodeModel.Instr;
import am.ik.wasm.WasmCodeModel.TypeSection;
import am.ik.wasm.WasmSections.Section;
import org.jspecify.annotations.Nullable;

/**
 * Language-independent peepholes over a code section: every rewrite is decided by the
 * bytes of two or three instructions that are ADJACENT in one block's instruction list,
 * with no dataflow and no renumbering of anything. What an emitter leaves behind between
 * a value and its consumer -- a store followed by the matching load, a value produced
 * only to be dropped, a trap the validator no longer needs -- is recovered here rather
 * than being spread over every emission site.
 * <p>
 * The four local rewrites, applied to a fixpoint in one left-to-right pass:
 * <ul>
 * <li>{@code local.set N; local.get N} -&gt; {@code local.tee N} (2 bytes). This also
 * covers the two-value swap-through-temps shape {@code local.set b; local.set a;
 * local.get a; local.get b}, whose middle pair is exactly this one.</li>
 * <li>{@code local.tee N; drop} -&gt; {@code local.set N} (1 byte) -- including the
 * {@code tee} the rule above has just made, so a statement-position assignment
 * ({@code set N; get N; drop}) collapses back to {@code set N}.</li>
 * <li>a pure value then {@code drop} -&gt; nothing (2-3 bytes): a constant, a
 * {@code ref.null}, a {@code local.get} or a {@code global.get} cannot trap and cannot
 * store, so producing it for a {@code drop} is producing it for nobody.</li>
 * <li>{@code if (result T) call F else ref.null end; ref.is_null} -&gt; {@code i32.eqz}
 * (8 bytes and a call), when the caller names {@code F} as a pure producer of a non-null
 * reference: the block's value is null exactly when the condition was 0, so testing the
 * value for null is testing the condition for zero. This is a boolean boxed into a
 * language's true/false objects and immediately re-tested by its consumer -- an emitter
 * that computes a predicate as a value and an {@code if} that tests the value.</li>
 * <li>{@code i32.eqz; i32.eqz} in front of an {@code if} or {@code br_if} -&gt; nothing
 * (2 bytes): the branch only asks whether the operand is zero, which two negations leave
 * as it was. Only there -- a later {@code i32.and} would see the value, not its
 * truth.</li>
 * </ul>
 * and one structural rewrite, which is why this pass decodes blocks at all:
 * <ul>
 * <li>{@code br 0; end; unreachable; end} -&gt; the {@code unreachable} goes (1 byte),
 * when the inner {@code end} closes a {@code loop} whose body ends in that unconditional
 * branch back (so nothing can fall out of it) and that loop is the whole of the enclosing
 * block, which takes and leaves nothing. The trap is dynamically dead, and the stack is
 * already what the enclosing {@code end} wants, so the type checker no longer needs it
 * either.</li>
 * </ul>
 * Adjacency is adjacency in the DECODED instruction list, so a block opener, an
 * {@code else} or an {@code end} between two instructions separates them: a
 * {@code local.get} that starts a block body is not the consumer of a {@code local.set}
 * that precedes the opener. Sizes and where these shapes come from:
 * {@code .kb/optimize-dead-code-elimination.md}.
 */
public final class WasmPeephole {

	private WasmPeephole() {
	}

	private static final int SEC_TYPE = 1;

	private static final int SEC_FUNCTION = 3;

	private static final int SEC_CODE = 10;

	/**
	 * Rewrites every code entry of a core module, with no call treated as pure.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return the module with its bodies rewritten; the input itself when no rule fires
	 */
	public static byte[] rewrite(byte[] module) {
		return rewrite(module, f -> false);
	}

	/**
	 * Rewrites every code entry of a core module.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @param pureNonNullCall which function indices name a call that cannot trap, cannot
	 * store anything another function could observe, and answers a non-null reference --
	 * a lazily built constant, say -- so that a call to one whose value is only tested
	 * for null may be dropped with the test
	 * @return the module with its bodies rewritten; the input itself when no rule fires
	 */
	public static byte[] rewrite(byte[] module, java.util.function.IntPredicate pureNonNullCall) {
		List<Section> sections = WasmSections.parseSections(module);
		@Nullable Section typeSec = null;
		@Nullable Section functionSec = null;
		@Nullable Section codeSec = null;
		for (Section s : sections) {
			if (s.id() == SEC_TYPE) {
				typeSec = s;
			}
			else if (s.id() == SEC_FUNCTION) {
				functionSec = s;
			}
			else if (s.id() == SEC_CODE) {
				codeSec = s;
			}
		}
		if (typeSec == null || functionSec == null || codeSec == null) {
			return module;
		}
		TypeSection types = WasmCodeModel.parseTypeSection(typeSec.payload());
		int[] defTypeIdx = WasmSections.parseFunctionSection(functionSec.payload());
		List<byte[]> entries = WasmSections.parseCodeEntries(codeSec.payload());
		if (entries.size() != defTypeIdx.length) {
			return module;
		}
		List<byte[]> rewritten = new ArrayList<>(entries.size());
		boolean changed = false;
		for (int d = 0; d < entries.size(); d++) {
			byte[] entry = entries.get(d);
			byte[] out = rewriteEntry(entry, types, types.func(defTypeIdx[d]).results().isEmpty(), pureNonNullCall);
			changed |= out != entry;
			rewritten.add(out);
		}
		if (!changed) {
			return module;
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmSections.writeU(body, rewritten.size());
		for (byte[] entry : rewritten) {
			WasmSections.writeU(body, entry.length);
			WasmSections.writeRaw(body, entry);
		}
		List<Section> rebuilt = new ArrayList<>(sections.size());
		for (Section s : sections) {
			rebuilt.add(s.id() == SEC_CODE ? new Section(SEC_CODE, body.toByteArray()) : s);
		}
		return WasmSections.assemble(rebuilt);
	}

	/**
	 * One instruction on the way out: the decoded instruction it came from (which owns
	 * its byte span) and, when a rule retyped it, the opcode to write in place of the
	 * span's first byte. Only {@code local.set}/{@code local.tee} are ever retyped, and
	 * they share an encoding, so the immediate bytes are copied through untouched.
	 */
	private static final class Op {

		final Instr in;

		int opcode;

		Op(Instr in) {
			this.in = in;
			this.opcode = in.op;
		}

	}

	private static byte[] rewriteEntry(byte[] entry, TypeSection types, boolean funcResultsEmpty,
			java.util.function.IntPredicate pureNonNullCall) {
		Body body = WasmCodeModel.decode(entry, types);
		List<Instr> code = body.code();
		if (code.isEmpty()) {
			return entry;
		}
		List<Op> out = local(code, pureNonNullCall);
		boolean changed = out.size() != code.size();
		for (Op op : out) {
			changed |= op.opcode != op.in.op;
		}
		while (loopTail(out, funcResultsEmpty)) {
			changed = true;
		}
		if (!changed) {
			return entry;
		}
		int localsEnd = code.get(0).start;
		ByteArrayOutputStream buf = new ByteArrayOutputStream(entry.length);
		WasmSections.writeRaw(buf, WasmSections.slice(entry, 0, localsEnd));
		for (Op op : out) {
			if (op.opcode == op.in.op) {
				WasmSections.writeRaw(buf, WasmSections.slice(entry, op.in.start, op.in.end));
			}
			else {
				buf.write(op.opcode);
				WasmSections.writeRaw(buf, WasmSections.slice(entry, op.in.start + 1, op.in.end));
			}
		}
		return buf.toByteArray();
	}

	// The local rules, to a fixpoint: each instruction joins the output and then the
	// output's tail is collapsed as far as it will go, so a rewrite that creates a new
	// adjacent pair (a `tee` in front of a `drop`, the two neighbours a deleted pure
	// value leaves touching, or the `i32.eqz` a re-tested box becomes beside the
	// `i32.eqz` its consumer wrote) is seen without a second traversal.
	private static List<Op> local(List<Instr> code, java.util.function.IntPredicate pureNonNullCall) {
		List<Op> out = new ArrayList<>(code.size());
		for (Instr in : code) {
			out.add(new Op(in));
			while (out.size() >= 2 && collapseTail(out, pureNonNullCall)) {
				// keep collapsing
			}
		}
		return out;
	}

	private static boolean collapseTail(List<Op> out, java.util.function.IntPredicate pureNonNullCall) {
		int n = out.size();
		Op b = out.get(n - 1);
		Op a = out.get(n - 2);
		if (a.opcode == Instruction.SET_LOCAL && b.opcode == Instruction.GET_LOCAL && a.in.a == b.in.a) {
			a.opcode = Instruction.TEE_LOCAL;
			out.remove(n - 1);
			return true;
		}
		if (a.opcode == Instruction.TEE_LOCAL && b.opcode == Instruction.DROP) {
			a.opcode = Instruction.SET_LOCAL;
			out.remove(n - 1);
			return true;
		}
		if (b.opcode == Instruction.DROP && isPure(a)) {
			out.remove(n - 1);
			out.remove(n - 2);
			return true;
		}
		if (b.opcode == Instruction.REF_IS_NULL && n >= 6 && isBoxedTruth(out, n - 6, pureNonNullCall)) {
			// The block's value is null exactly when its condition was 0: the test of
			// the value is the test of the condition, and the `ref.is_null` becomes
			// the `i32.eqz` that says so.
			for (int i = 0; i < 5; i++) {
				out.remove(n - 2 - i);
			}
			b.opcode = Instruction.I32_EQZ;
			return true;
		}
		if ((b.opcode == Instruction.IF || b.opcode == Instruction.BR_IF) && n >= 3 && a.opcode == Instruction.I32_EQZ
				&& out.get(n - 3).opcode == Instruction.I32_EQZ) {
			out.remove(n - 2);
			out.remove(n - 3);
			return true;
		}
		return false;
	}

	// `if (result T) call F else ref.null end` at out[at..at+4], F a pure non-null
	// producer: a boolean boxed into a language's true and false objects.
	private static boolean isBoxedTruth(List<Op> out, int at, java.util.function.IntPredicate pureNonNullCall) {
		Op open = out.get(at);
		if (open.opcode != Instruction.IF || open.in.blockType == null || !open.in.blockType.params().isEmpty()
				|| open.in.blockType.results().size() != 1) {
			return false;
		}
		Op call = out.get(at + 1);
		return call.opcode == Instruction.CALL && pureNonNullCall.test((int) call.in.a)
				&& out.get(at + 2).opcode == Instruction.ELSE && out.get(at + 3).opcode == Instruction.REF_NULL
				&& out.get(at + 4).opcode == Instruction.END;
	}

	// A value that cannot trap and cannot store, so nothing observes it being produced.
	private static boolean isPure(Op op) {
		return switch (op.opcode) {
			case Instruction.GET_LOCAL, Instruction.GET_GLOBAL, Instruction.I32_CONST, Instruction.I64_CONST,
					Instruction.F32_CONST, Instruction.F64_CONST, Instruction.REF_NULL ->
				true;
			default -> false;
		};
	}

	/**
	 * Deletes one {@code unreachable} that a non-terminating loop made dead, and reports
	 * whether it found one -- the caller repeats, because deleting one can uncover the
	 * next ({@code br 0; end; unreachable; end; unreachable; end}).
	 */
	private static boolean loopTail(List<Op> out, boolean funcResultsEmpty) {
		int n = out.size();
		// opener[i] for an `end`/`else`: the index of the block it closes, or -1 for the
		// function's own `end`; elseOf[opener]: an `if`'s `else`, or -1.
		int[] opener = new int[n];
		int[] elseOf = new int[n];
		java.util.Arrays.fill(opener, -1);
		java.util.Arrays.fill(elseOf, -1);
		List<Integer> stack = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			Op op = out.get(i);
			if (op.in.isOpener()) {
				stack.add(i);
			}
			else if (op.opcode == Instruction.ELSE) {
				elseOf[stack.get(stack.size() - 1)] = i;
			}
			else if (op.opcode == Instruction.END && !stack.isEmpty()) {
				opener[i] = stack.remove(stack.size() - 1);
			}
		}
		for (int i = 2; i + 1 < n; i++) {
			if (out.get(i).opcode != Instruction.UNREACHABLE || out.get(i + 1).opcode != Instruction.END) {
				continue;
			}
			// The loop must be closed right here and must never fall out of itself: its
			// last instruction is `br 0`, the branch back to its own head.
			if (out.get(i - 1).opcode != Instruction.END || out.get(i - 2).opcode != Instruction.BR
					|| out.get(i - 2).in.a != 0) {
				continue;
			}
			int loop = opener[i - 1];
			if (loop < 0 || out.get(loop).opcode != Instruction.LOOP || !empty(out.get(loop).in.blockType)) {
				continue;
			}
			// Nothing of the enclosing region's own is on the stack: the loop is the
			// whole of it, and it takes and leaves nothing -- so the `end` below is
			// already looking at the stack it wants.
			int enclosing = opener[i + 1];
			int regionStart;
			if (enclosing < 0) {
				if (!funcResultsEmpty) {
					continue;
				}
				regionStart = 0;
			}
			else {
				Op e = out.get(enclosing);
				if (!empty(e.in.blockType)) {
					continue;
				}
				int els = elseOf[enclosing];
				regionStart = els >= 0 && els < i ? els + 1 : enclosing + 1;
			}
			if (loop != regionStart) {
				continue;
			}
			out.remove(i);
			return true;
		}
		return false;
	}

	private static boolean empty(@Nullable BlockType bt) {
		return bt != null && bt.params().isEmpty() && bt.results().isEmpty();
	}

}
