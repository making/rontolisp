package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

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
 * <li>{@code call F} -&gt; one {@code drop} per parameter, then {@code i32.const K}, when
 * F's whole body is {@code i32.const K} (no locals, nothing else): calling it only
 * consumes the arguments. The drops then meet the pure argument pushes in front of them
 * and all of it goes. This is a runtime helper {@link WasmRefTypeFolder} proved has no
 * live arm in this module; left as a call, a never-taken one still costs its caller the
 * registers a call clobbers.</li>
 * <li>{@code if (result T) X else X end}, X one pure instruction the same in both arms
 * -&gt; {@code drop; X}: the condition decides nothing. What a folded constant call
 * leaves when the other arm already answered the same constant.</li>
 * <li>{@code ref.test}, {@code ref.is_null} or {@code i32.eqz} then {@code drop} -&gt;
 * {@code drop}: a unary test that cannot trap, computed for nobody -- so the operand's
 * own push can then go with the drop.</li>
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
		// The functions each body the first pass left alone calls (null for a body it
		// changed): such a body can only change again at a call of a constant function.
		int @Nullable [][] untouchedCallees = new int[entries.size()][];
		for (int d = 0; d < entries.size(); d++) {
			byte[] entry = entries.get(d);
			Body body = WasmCodeModel.decode(entry, types);
			byte[] out = rewriteEntry(entry, body, types.func(defTypeIdx[d]).results().isEmpty(), pureNonNullCall,
					NO_CONSTANTS);
			if (out == entry) {
				untouchedCallees[d] = callees(body);
			}
			changed |= out != entry;
			rewritten.add(out);
		}
		// A second pass for the calls of a constant function, decided over the bodies
		// the first pass left (the fold's debris in a helper is what hides its constant).
		int numImports = WasmSections.importedFunctionCount(module);
		Map<Integer, byte[]> constants = new HashMap<>();
		for (int d = 0; d < rewritten.size(); d++) {
			byte[] constant = constantBody(rewritten.get(d));
			if (constant != null) {
				constants.put(numImports + d, constant);
			}
		}
		if (!constants.isEmpty()) {
			ConstantCalls calls = new ConstantCalls(constants,
					f -> types.func(defTypeIdx[f - numImports]).params().size());
			for (int d = 0; d < rewritten.size(); d++) {
				// A body the first pass left alone that calls no constant function is
				// the same input to the same rules, so it comes out the same again.
				int @Nullable [] callees = untouchedCallees[d];
				if (callees != null && !callsAny(callees, constants)) {
					continue;
				}
				byte[] entry = rewritten.get(d);
				byte[] out = rewriteEntry(entry, WasmCodeModel.decode(entry, types),
						types.func(defTypeIdx[d]).results().isEmpty(), pureNonNullCall, calls);
				changed |= out != entry;
				rewritten.set(d, out);
			}
		}
		if (!changed) {
			return module;
		}
		ByteArrayOutputStream body = new UnsynchronizedByteArrayOutputStream();
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

		// The bytes to write in place of the whole span, for an instruction a rule
		// SYNTHESIZED (a constant call's drops and constant); null otherwise.
		byte @Nullable [] bytes;

		Op(Instr in) {
			this.in = in;
			this.opcode = in.op;
		}

		Op(Instr in, int opcode, byte[] bytes) {
			this.in = in;
			this.opcode = opcode;
			this.bytes = bytes;
		}

	}

	/**
	 * The functions whose whole body is one {@code i32.const}, by function index, with
	 * that instruction's bytes, and each one's parameter count.
	 */
	private record ConstantCalls(Map<Integer, byte[]> constants, IntUnaryOperator paramCount) {
	}

	private static final ConstantCalls NO_CONSTANTS = new ConstantCalls(Map.of(), f -> 0);

	// The `i32.const K` bytes when the code entry is exactly: no local declarations,
	// `i32.const K`, `end`; null otherwise.
	private static byte @Nullable [] constantBody(byte[] entry) {
		if (entry.length < 4 || entry[0] != 0 || entry[1] != (byte) Instruction.I32_CONST
				|| entry[entry.length - 1] != (byte) Instruction.END) {
			return null;
		}
		// The immediate is a signed LEB128 that must end exactly before the `end`.
		int p = 2;
		while (p < entry.length - 1 && (entry[p] & 0x80) != 0) {
			p++;
		}
		if (p != entry.length - 2) {
			return null;
		}
		return WasmSections.slice(entry, 1, entry.length - 1);
	}

	// The targets of the body's calls.
	private static int[] callees(Body body) {
		int count = 0;
		for (Instr in : body.code()) {
			if (in.op == Instruction.CALL) {
				count++;
			}
		}
		int[] out = new int[count];
		int k = 0;
		for (Instr in : body.code()) {
			if (in.op == Instruction.CALL) {
				out[k++] = (int) in.a;
			}
		}
		return out;
	}

	private static boolean callsAny(int[] callees, Map<Integer, byte[]> constants) {
		for (int callee : callees) {
			if (constants.containsKey(callee)) {
				return true;
			}
		}
		return false;
	}

	private static byte[] rewriteEntry(byte[] entry, Body body, boolean funcResultsEmpty,
			java.util.function.IntPredicate pureNonNullCall, ConstantCalls calls) {
		List<Instr> code = body.code();
		if (code.isEmpty()) {
			return entry;
		}
		List<Op> out = local(code, pureNonNullCall, calls);
		boolean changed = out.size() != code.size();
		for (Op op : out) {
			changed |= op.opcode != op.in.op || op.bytes != null;
		}
		while (loopTail(out, funcResultsEmpty)) {
			changed = true;
		}
		if (!changed) {
			return entry;
		}
		int localsEnd = code.get(0).start;
		ByteArrayOutputStream buf = new UnsynchronizedByteArrayOutputStream(entry.length);
		WasmSections.writeRaw(buf, WasmSections.slice(entry, 0, localsEnd));
		for (Op op : out) {
			if (op.bytes != null) {
				WasmSections.writeRaw(buf, op.bytes);
			}
			else if (op.opcode == op.in.op) {
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
	private static List<Op> local(List<Instr> code, java.util.function.IntPredicate pureNonNullCall,
			ConstantCalls calls) {
		List<Op> out = new ArrayList<>(code.size());
		for (Instr in : code) {
			byte @Nullable [] constant = in.op == Instruction.CALL ? calls.constants().get((int) in.a) : null;
			if (constant == null) {
				add(out, new Op(in), pureNonNullCall);
				continue;
			}
			// The call only consumes its arguments: drop each, then push the constant.
			for (int i = calls.paramCount().applyAsInt((int) in.a); i > 0; i--) {
				add(out, new Op(in, Instruction.DROP, new byte[] { (byte) Instruction.DROP }), pureNonNullCall);
			}
			add(out, new Op(in, Instruction.I32_CONST, constant), pureNonNullCall);
		}
		return out;
	}

	private static void add(List<Op> out, Op op, java.util.function.IntPredicate pureNonNullCall) {
		out.add(op);
		while (out.size() >= 2 && collapseTail(out, pureNonNullCall)) {
			// keep collapsing
		}
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
		if (b.opcode == Instruction.DROP && isPureUnaryTest(a)) {
			out.remove(n - 2);
			return true;
		}
		if (b.opcode == Instruction.END && n >= 5 && isSameArmIf(out, n - 5)) {
			Op open = out.get(n - 5);
			Op arm = out.get(n - 4);
			for (int i = 0; i < 5; i++) {
				out.remove(n - 1 - i);
			}
			add(out, new Op(open.in, Instruction.DROP, new byte[] { (byte) Instruction.DROP }), pureNonNullCall);
			add(out, arm, pureNonNullCall);
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

	// `if (result T) X else X end` at out[at..at+4]: no block parameters, one result, and
	// each arm the same single pure instruction.
	private static boolean isSameArmIf(List<Op> out, int at) {
		Op open = out.get(at);
		if (open.opcode != Instruction.IF || open.bytes != null || open.in.blockType == null
				|| !open.in.blockType.params().isEmpty() || open.in.blockType.results().size() != 1) {
			return false;
		}
		Op x = out.get(at + 1);
		Op y = out.get(at + 3);
		return out.get(at + 2).opcode == Instruction.ELSE && sameValue(x, y);
	}

	// Two pure single-instruction values that push the same thing.
	private static boolean sameValue(Op x, Op y) {
		if (x.opcode != y.opcode) {
			return false;
		}
		return switch (x.opcode) {
			case Instruction.I32_CONST -> immediate(x) == immediate(y);
			// Not i64.const: WasmCodeModel skips its immediate, so two of them cannot be
			// told apart here.
			case Instruction.GET_LOCAL, Instruction.GET_GLOBAL ->
				x.bytes == null && y.bytes == null && x.in.a == y.in.a;
			default -> false;
		};
	}

	// The value an i32.const pushes, whether decoded or synthesized.
	private static long immediate(Op op) {
		byte[] bytes = op.bytes;
		return bytes == null ? op.in.a : WasmSections.readS(bytes, new int[] { 1 });
	}

	// A unary operator that cannot trap and cannot store: its operand, dropped, is the
	// same as its result, dropped.
	private static boolean isPureUnaryTest(Op op) {
		if (op.bytes != null) {
			return false;
		}
		return op.opcode == Instruction.I32_EQZ || op.opcode == Instruction.REF_IS_NULL
				|| (op.opcode == Instruction.GC_PREFIX && (op.in.sub == Instruction.REF_TEST || op.in.sub == 0x15));
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
		// The block structure below costs a pass over the body: pay it only when the
		// shape the rule starts from (`br 0; end; unreachable; end`) occurs at all.
		boolean candidate = false;
		for (int i = 2; i + 1 < n && !candidate; i++) {
			candidate = out.get(i).opcode == Instruction.UNREACHABLE && out.get(i + 1).opcode == Instruction.END
					&& out.get(i - 1).opcode == Instruction.END && out.get(i - 2).opcode == Instruction.BR
					&& out.get(i - 2).in.a == 0;
		}
		if (!candidate) {
			return false;
		}
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
