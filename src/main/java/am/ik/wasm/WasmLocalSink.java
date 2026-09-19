package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.wasm.WasmCodeModel.Body;
import am.ik.wasm.WasmCodeModel.Instr;
import am.ik.wasm.WasmCodeModel.StructType;
import am.ik.wasm.WasmCodeModel.TypeDef;
import am.ik.wasm.WasmCodeModel.TypeSection;
import am.ik.wasm.WasmCodeModel.ValType;
import am.ik.wasm.WasmSections.Section;
import org.jspecify.annotations.Nullable;

/**
 * Language-independent expression sinking over a code section: a local that is written
 * ONCE, by a pure expression, and read ONCE, at a point the write dominates, does not
 * need to exist -- the expression belongs at the read. The {@code local.set} and the
 * {@code local.get} go, the local leaves the declaration vector, and every local above it
 * moves down one index (which is what takes a {@code local.*} immediate back under 128).
 * The residue comes from an emitter that hands out a fresh local per temporary, and from
 * {@link WasmInliner}'s argument hand-over, which binds each argument to a fresh local at
 * the call site; what it is worth is measured in
 * {@code .kb/optimize-dead-code-elimination.md}, "The single-use local".
 * <p>
 * The legality argument, in three parts:
 * <ul>
 * <li><strong>The expression is pure</strong>: it reads locals and globals, pushes
 * constants, and computes with instructions that cannot trap and cannot store. Evaluating
 * it later is therefore unobservable -- as long as its inputs are the same. An allocation
 * that cannot trap counts too, since nothing but the sunk local can reach the object
 * before the read -- but only while it is still evaluated ONCE: never as a copy, and
 * never into a loop the write is outside of, where the read runs per iteration and a
 * closure built once would be rebuilt, cell and all, every time.</li>
 * <li><strong>Its inputs are unchanged between where it was and where it goes</strong>:
 * no {@code local.set}/{@code local.tee} of a local it reads, and -- when it reads a
 * global -- no {@code global.set} and no {@code call} (which may set any global). The
 * scanned range runs from the expression to the read, extended to the {@code end} of the
 * outermost {@code loop} opened after the write that is still open at the read, because a
 * later iteration of that loop reaches the read again without passing the write.</li>
 * <li><strong>The write dominates the read</strong>: under structured control flow, no
 * {@code else} or {@code end} between the two closes a block that was open at the write.
 * Otherwise a path reaches the read through the local's default value, which the
 * expression is not.</li>
 * </ul>
 * The expression need not sit right in front of the {@code local.set}: a hand-over of
 * several values stores them in reverse ({@code e1; e2; local.set b; local.set a}), so
 * the walk back from the write allows a stack-neutral gap that never touches the value
 * below it, and the gap's own instructions are part of the scanned range. A write by
 * {@code local.tee} sinks a COPY (the value stays on the stack for its other consumer)
 * and is taken only when the copy is shorter than the {@code tee} and {@code get} it
 * replaces. Two degenerate cases ride along because the renumbering is already paid for:
 * a local nothing reads has its {@code tee}s deleted and its {@code set}s turned into
 * {@code drop} -- or deleted together with the pure expression that fed them -- and a
 * local nothing touches leaves the declaration. Bodies are re-decoded and re-sunk to a
 * fixpoint, so a local whose expression reads a local sunk this round is collected on the
 * next round rather than reasoned about.
 * <p>
 * The pass renumbers a function's OWN locals only -- nothing that another section or an
 * out-of-band claim addresses -- so it runs anywhere after the peepholes and the inliner
 * and before {@link WasmLocalOrder}, which then sees the frame it has left.
 */
public final class WasmLocalSink {

	private WasmLocalSink() {
	}

	private static final int SEC_TYPE = 1;

	private static final int SEC_FUNCTION = 3;

	private static final int SEC_CODE = 10;

	/** How far back from a write the expression walk looks, in instructions. */
	private static final int MAX_WALK = 32;

	/** The stack effect of an instruction this pass cannot see through. */
	private static final int[] UNKNOWN = new int[0];

	/**
	 * Sinks every single-use local of every code entry of a core module.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return the module with those locals removed; the input itself when none was
	 */
	public static byte[] sink(byte[] module) {
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
			int params = types.func(defTypeIdx[d]).params().size();
			byte[] out = entry;
			try {
				byte[] next;
				while ((next = sinkEntry(out, types, params)) != out) {
					out = next;
				}
			}
			catch (RuntimeException ex) {
				// An opcode outside the model's subset: leave the body alone rather than
				// taking the whole module out of the pass.
				out = entry;
			}
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
	 * One write taken out: the local, the expression span {@code [from, to)} that fed it
	 * (empty when the write becomes a {@code drop}), the write itself, the read the
	 * expression goes to (-1 when nothing reads the local), and whether the expression is
	 * copied there rather than moved (a {@code tee}, whose value stays on the stack).
	 */
	private record Sink(int local, int from, int to, int def, int use, boolean copy) {

		boolean drops() {
			return this.from == this.to;
		}

	}

	private static byte[] sinkEntry(byte[] entry, TypeSection types, int params) {
		Body body = WasmCodeModel.decode(entry, types);
		List<Instr> code = body.code();
		int total = params + body.locals().size();
		int[] writes = new int[total];
		int[] gets = new int[total];
		int[] getAt = new int[total];
		// A `local.set N; local.get N` pair already in the body -- the inliner's argument
		// hand-over in front of a callee that reads that parameter first -- is worth an
		// encode on its own, because the encoder writes such a pair as a `tee`.
		boolean adjacentPair = false;
		for (int k = 0; k < code.size(); k++) {
			Instr in = code.get(k);
			if (in.op == Instruction.GET_LOCAL) {
				gets[(int) in.a]++;
				getAt[(int) in.a] = k;
				adjacentPair |= k > 0 && code.get(k - 1).op == Instruction.SET_LOCAL && code.get(k - 1).a == in.a;
			}
			else if (in.op == Instruction.SET_LOCAL || in.op == Instruction.TEE_LOCAL) {
				writes[(int) in.a]++;
			}
		}
		List<Sink> sinks = new ArrayList<>();
		for (int def = 0; def < code.size(); def++) {
			Instr in = code.get(def);
			if ((in.op != Instruction.SET_LOCAL && in.op != Instruction.TEE_LOCAL) || in.a < params) {
				continue;
			}
			int n = (int) in.a;
			boolean tee = in.op == Instruction.TEE_LOCAL;
			if (gets[n] == 0) {
				// Nothing reads the local: a `tee` goes, a `set` becomes a `drop` -- or
				// goes with the pure expression that fed it.
				int[] span = tee ? null : expressionBefore(code, def, types);
				sinks.add(span == null ? new Sink(n, def, def, def, -1, tee)
						: new Sink(n, span[0], span[1], def, -1, false));
				continue;
			}
			if (writes[n] != 1 || gets[n] != 1) {
				continue;
			}
			int use = getAt[n];
			if (use <= def || !dominates(code, def, use)) {
				continue;
			}
			int[] span = expressionBefore(code, def, types);
			if (span == null) {
				continue;
			}
			boolean readsGlobal = false;
			boolean allocates = false;
			int expression = 0;
			boolean[] readsLocal = new boolean[total];
			for (int k = span[0]; k < span[1]; k++) {
				Instr e = code.get(k);
				expression += e.end - e.start;
				if (e.op == Instruction.GET_LOCAL) {
					readsLocal[(int) e.a] = true;
				}
				else if (e.op == Instruction.GET_GLOBAL) {
					readsGlobal = true;
				}
				else if (e.op == Instruction.GC_PREFIX && e.sub <= 0x08) {
					allocates = true;
				}
			}
			int rangeEnd = rangeEnd(code, def, use);
			if (!inputsUnchanged(code, span[1], rangeEnd, readsLocal, readsGlobal)) {
				continue;
			}
			// An allocation evaluated again is a SECOND object, not the one every other
			// path holds: never as a copy, and never into a loop the write is not in --
			// a closure built once and called per iteration would be rebuilt, cell and
			// all, on every iteration.
			if (allocates && (tee || rangeEnd != use)) {
				continue;
			}
			if (tee) {
				int replaced = (in.end - in.start) + (code.get(use).end - code.get(use).start);
				if (expression >= replaced) {
					continue;
				}
			}
			sinks.add(new Sink(n, span[0], span[1], def, use, tee));
		}
		// A sink whose expression reads a local sunk this round waits for the next one,
		// which sees that local's expression in its place.
		boolean[] sunk = new boolean[total];
		for (Sink s : sinks) {
			sunk[s.local] = true;
		}
		boolean[] removed = new boolean[total];
		for (int n = params; n < total; n++) {
			removed[n] = writes[n] == 0 && gets[n] == 0;
		}
		List<Sink> applied = new ArrayList<>(sinks.size());
		for (Sink s : sinks) {
			boolean deferred = false;
			for (int k = s.from; k < s.to && !deferred; k++) {
				Instr e = code.get(k);
				deferred = e.op == Instruction.GET_LOCAL && sunk[(int) e.a];
			}
			if (!deferred) {
				applied.add(s);
			}
		}
		// A `drop` is the fallback for a dead write whose expression the walk could not
		// see through, and is irreversible; when this round takes anything else out --
		// a dead `tee` in that expression's gap, say -- the next round may see a pure
		// expression there instead, so the fallback waits for a round that changes
		// nothing else.
		boolean movesSomething = false;
		for (Sink s : applied) {
			movesSomething |= !s.drops() || s.copy;
		}
		if (movesSomething) {
			applied.removeIf(s -> s.drops() && !s.copy);
		}
		// A local leaves the declaration once every write to it is taken out.
		int[] taken = new int[total];
		for (Sink s : applied) {
			taken[s.local]++;
		}
		for (int n = params; n < total; n++) {
			removed[n] |= taken[n] > 0 && taken[n] == writes[n];
		}
		boolean anyRemoved = !applied.isEmpty() || adjacentPair;
		for (boolean r : removed) {
			anyRemoved |= r;
		}
		if (!anyRemoved) {
			return entry;
		}
		return encode(entry, body, params, removed, applied);
	}

	// Under structured control flow the write at `def` dominates the read at `use` when
	// no `else`/`end` between them closes a block that was open at `def`.
	private static boolean dominates(List<Instr> code, int def, int use) {
		for (int k = def + 1; k < use; k++) {
			Instr in = code.get(k);
			if ((in.op == Instruction.END || in.op == Instruction.ELSE) && in.match < def) {
				return false;
			}
		}
		return true;
	}

	// The last instruction whose execution can separate the expression from the read: the
	// read itself, or the `end` of the outermost loop opened after the write that is
	// still
	// open at the read -- its next iteration reaches the read without passing the write.
	private static int rangeEnd(List<Instr> code, int def, int use) {
		for (int k = def + 1; k < use; k++) {
			Instr in = code.get(k);
			if (in.op == Instruction.LOOP && in.match > use) {
				return in.match;
			}
		}
		return use;
	}

	private static boolean inputsUnchanged(List<Instr> code, int from, int to, boolean[] readsLocal,
			boolean readsGlobal) {
		for (int k = from; k <= to; k++) {
			Instr in = code.get(k);
			switch (in.op) {
				case Instruction.SET_LOCAL, Instruction.TEE_LOCAL -> {
					if (readsLocal[(int) in.a]) {
						return false;
					}
				}
				case Instruction.SET_GLOBAL, Instruction.CALL, Instruction.RETURN_CALL -> {
					if (readsGlobal) {
						return false;
					}
				}
				default -> {
				}
			}
		}
		return true;
	}

	// The pure expression whose value the write at `def` stores, as the span [from, to)
	// of
	// instructions -- possibly followed by a stack-neutral gap up to `def` -- or null
	// when
	// the walk back meets an instruction it cannot see through, or no pure split exists.
	private static int @Nullable [] expressionBefore(List<Instr> code, int def, TypeSection types) {
		int net = 0;
		int k = def;
		while (k > 0 && def - k < MAX_WALK) {
			k--;
			Instr in = code.get(k);
			int[] effect = stackEffect(in, types);
			if (effect == UNKNOWN || in.isOpener() || in.op == Instruction.END || in.op == Instruction.ELSE) {
				return null;
			}
			net += effect[1] - effect[0];
			if (net == 1) {
				break;
			}
		}
		if (net != 1) {
			return null;
		}
		// Split [k, def) into the expression and the gap: the longest pure prefix with
		// net
		// +1 whose remainder is stack-neutral and never reaches below its own start.
		for (int m = def; m > k; m--) {
			if (isExpression(code, k, m, types) && isNeutralGap(code, m, def, types)) {
				return new int[] { k, m };
			}
		}
		return null;
	}

	private static boolean isExpression(List<Instr> code, int from, int to, TypeSection types) {
		int depth = 0;
		for (int k = from; k < to; k++) {
			Instr in = code.get(k);
			if (!isPure(in, k > from ? code.get(k - 1) : null)) {
				return false;
			}
			int[] effect = stackEffect(in, types);
			if (depth < effect[0]) {
				return false;
			}
			depth += effect[1] - effect[0];
		}
		return depth == 1;
	}

	private static boolean isNeutralGap(List<Instr> code, int from, int to, TypeSection types) {
		int depth = 0;
		for (int k = from; k < to; k++) {
			int[] effect = stackEffect(code.get(k), types);
			if (depth < effect[0]) {
				return false;
			}
			depth += effect[1] - effect[0];
		}
		return depth == 0;
	}

	/** The largest constant array length an allocation is trusted not to trap on. */
	private static final long SMALL_ARRAY = 1 << 16;

	// Reads locals and globals, pushes constants, computes without trapping or storing:
	// evaluating it later, with the same inputs, is unobservable. A fresh allocation is
	// pure too -- nothing but the sunk local can reach it before the read -- as long as
	// it
	// cannot trap, which an array allocation of a small constant length cannot.
	private static boolean isPure(Instr in, @Nullable Instr prev) {
		if (in.op >= 0x45 && in.op <= 0xC4) {
			return !WasmCodeModel.numericMayTrap(in.op);
		}
		return switch (in.op) {
			case Instruction.GET_LOCAL, Instruction.GET_GLOBAL, Instruction.SELECT, Instruction.I32_CONST,
					Instruction.I64_CONST, Instruction.F32_CONST, Instruction.F64_CONST, Instruction.REF_NULL,
					Instruction.REF_IS_NULL, Instruction.REF_EQ ->
				true;
			case 0xFB -> switch (in.sub) {
				case 0x00, 0x01, 0x08, 0x14, 0x15, 0x1C -> true; // struct.new*,
																	// array.new_fixed,
																	// ref.test*, ref.i31
				case 0x06, 0x07 ->
					prev != null && prev.op == Instruction.I32_CONST && prev.a >= 0 && prev.a <= SMALL_ARRAY; // array.new
																												// /
																												// array.new_default
																												// of
																												// a
																												// small
																												// constant
																												// length
				default -> false;
			};
			// the saturating truncations
			case 0xFC -> in.sub <= 0x07;
			default -> false;
		};
	}

	private static int[] stackEffect(Instr in, TypeSection types) {
		if (in.op >= 0x45 && in.op <= 0xC4) {
			return new int[] { WasmCodeModel.numericPops(in.op), 1 };
		}
		return switch (in.op) {
			case Instruction.NOP -> new int[] { 0, 0 };
			case Instruction.DROP, Instruction.SET_LOCAL, Instruction.SET_GLOBAL -> new int[] { 1, 0 };
			case Instruction.SELECT -> new int[] { 3, 1 };
			case Instruction.GET_LOCAL, Instruction.GET_GLOBAL, Instruction.I32_CONST, Instruction.I64_CONST,
					Instruction.F32_CONST, Instruction.F64_CONST, Instruction.REF_NULL, Instruction.CURRENT_MEMORY ->
				new int[] { 0, 1 };
			case Instruction.TEE_LOCAL, Instruction.REF_IS_NULL, Instruction.GROW_MEMORY -> new int[] { 1, 1 };
			case Instruction.REF_EQ -> new int[] { 2, 1 };
			case 0xFB -> gcEffect(in, types);
			case 0xFC -> switch (in.sub) {
				case 0x0A, 0x0B -> new int[] { 3, 0 }; // memory.copy / memory.fill
				default -> in.sub <= 0x07 ? new int[] { 1, 1 } : UNKNOWN;
			};
			case 0xFD -> WasmCodeModel.simdArity(in.sub);
			default -> {
				if (in.op >= 0x28 && in.op <= 0x35) {
					yield new int[] { 1, 1 }; // loads
				}
				if (in.op >= 0x36 && in.op <= 0x3E) {
					yield new int[] { 2, 0 }; // stores
				}
				yield UNKNOWN;
			}
		};
	}

	private static int[] gcEffect(Instr in, TypeSection types) {
		return switch (in.sub) {
			case 0x00 -> { // struct.new
				TypeDef t = types.types().get((int) in.a);
				yield t instanceof StructType s ? new int[] { s.fields().size(), 1 } : UNKNOWN;
			}
			case 0x01 -> new int[] { 0, 1 }; // struct.new_default
			case 0x02, 0x03, 0x04 -> new int[] { 1, 1 }; // struct.get*
			case 0x05 -> new int[] { 2, 0 }; // struct.set
			case 0x06 -> new int[] { 2, 1 }; // array.new
			case 0x07 -> new int[] { 1, 1 }; // array.new_default
			case 0x08 -> new int[] { (int) in.b, 1 }; // array.new_fixed
			case 0x0B, 0x0C, 0x0D -> new int[] { 2, 1 }; // array.get*
			case 0x0E -> new int[] { 3, 0 }; // array.set
			case 0x0F -> new int[] { 1, 1 }; // array.len
			case 0x10 -> new int[] { 4, 0 }; // array.fill
			case 0x11 -> new int[] { 5, 0 }; // array.copy
			case 0x14, 0x15, 0x16, 0x17 -> new int[] { 1, 1 }; // ref.test / ref.cast
			case 0x1C, 0x1D, 0x1E -> new int[] { 1, 1 }; // ref.i31 / i31.get_*
			default -> UNKNOWN;
		};
	}

	// --- Encoding ---

	private static byte[] encode(byte[] entry, Body body, int params, boolean[] removed, List<Sink> applied) {
		List<Instr> code = body.code();
		int total = params + body.locals().size();
		int[] newIndex = new int[total];
		List<ValType> kept = new ArrayList<>();
		for (int i = 0; i < total; i++) {
			if (i < params) {
				newIndex[i] = i;
			}
			else if (!removed[i]) {
				newIndex[i] = params + kept.size();
				kept.add(body.locals().get(i - params));
			}
		}
		// Per instruction: skipped, turned into a `drop`, or replaced by an expression.
		boolean[] skip = new boolean[code.size()];
		boolean[] drop = new boolean[code.size()];
		@Nullable Sink[] at = new Sink[code.size()];
		for (Sink s : applied) {
			if (s.drops()) {
				// A dead `set` becomes a `drop`; a dead `tee` leaves its value where it
				// is.
				drop[s.def] = !s.copy;
				skip[s.def] = s.copy;
				continue;
			}
			skip[s.def] = true;
			if (!s.copy) {
				for (int k = s.from; k < s.to; k++) {
					skip[k] = true;
				}
			}
			if (s.use >= 0) {
				at[s.use] = s;
			}
		}
		Encoder out = new Encoder(entry, newIndex);
		WasmCodeModel.writeLocals(out.bytes, kept);
		for (int k = 0; k < code.size(); k++) {
			Sink s = at[k];
			if (s != null) {
				for (int e = s.from; e < s.to; e++) {
					out.write(code.get(e));
				}
			}
			else if (drop[k]) {
				out.bytes.write(Instruction.DROP);
				out.lastSet = -1;
			}
			else if (!skip[k]) {
				out.write(code.get(k));
			}
		}
		return out.bytes.toByteArray();
	}

	// The instruction stream on its way out, with the one adjacency the deletions above
	// create folded as it is written: a `local.set N` that a deleted expression used to
	// separate from its `local.get N` becomes `local.tee N` -- the peephole that ran in
	// front of this pass would have, and it does not run again.
	private static final class Encoder {

		final ByteArrayOutputStream bytes;

		private final byte[] entry;

		private final int[] newIndex;

		/** The local of the `local.set` written last, and where its opcode byte is. */
		int lastSet = -1;

		private int lastSetAt;

		Encoder(byte[] entry, int[] newIndex) {
			this.bytes = new ByteArrayOutputStream(entry.length);
			this.entry = entry;
			this.newIndex = newIndex;
		}

		void write(Instr in) {
			if (in.op == Instruction.GET_LOCAL || in.op == Instruction.SET_LOCAL || in.op == Instruction.TEE_LOCAL) {
				int index = this.newIndex[(int) in.a];
				if (in.op == Instruction.GET_LOCAL && index == this.lastSet) {
					byte[] sofar = this.bytes.toByteArray();
					sofar[this.lastSetAt] = (byte) Instruction.TEE_LOCAL;
					this.bytes.reset();
					this.bytes.write(sofar, 0, sofar.length);
					this.lastSet = -1;
					return;
				}
				this.lastSet = in.op == Instruction.SET_LOCAL ? index : -1;
				this.lastSetAt = this.bytes.size();
				this.bytes.write(in.op);
				WasmSections.writeU(this.bytes, index);
			}
			else {
				this.lastSet = -1;
				WasmSections.writeRaw(this.bytes, WasmSections.slice(this.entry, in.start, in.end));
			}
		}

	}

}
