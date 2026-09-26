package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import am.ik.wasm.WasmCodeModel.BlockType;
import am.ik.wasm.WasmCodeModel.Catch;
import am.ik.wasm.WasmCodeModel.Instr;

/**
 * Language-independent narrowing of CARRIED locals. An emitter that has to keep some
 * locals' values alive across a {@code block} on the operand stack rather than in the
 * locals pushes them with a run of {@code local.get} right in front of the block, and
 * writes them back with a run of {@code local.set} -- the same locals, in reverse --
 * right behind the block's {@code end} and the one {@code local.set} that takes the
 * block's single result. No instruction inside the block can reach a value beneath its
 * entry height, so the second run pops exactly what the first pushed, whatever the block
 * did to the locals; a branch that leaves past the block's end discards the values.
 * <p>
 * A carried local that is DEAD where the write-back ends -- every path from there writes
 * it before it reads it -- gains nothing from the round trip, and this pass takes it out
 * of both runs. The two runs keep the same locals in the same order, so the operand stack
 * stays balanced. The liveness is a backward data flow over the body's structured control
 * flow, with an EXCEPTIONAL edge from every call and throw inside a {@code try_table} to
 * the catch labels of that {@code try_table} and of every one around it: more targets
 * than the tags can reach, which can only keep a local.
 * <p>
 * A kept local is READ by its push, so keeping it at one carry can make it live at
 * another carry's write-back: the kept set is the least fixed point, grown from nothing
 * kept. A local's facts involve no other local, so each local is solved on its own. A
 * write-back always counts as a write, kept or not: where the local is kept that is what
 * it is, and where it is dropped the local is dead there, so a write changes nothing
 * upstream.
 * <p>
 * Which runs are carries is the emitter's claim, as byte spans within the code entry. The
 * pass checks every claim against the shape above and throws on one that is not. Every
 * branch names a label rather than an offset, so removing instructions moves no branch;
 * the declaration vector is left as it is (a local nothing touches any more keeps its
 * declaration, which {@link WasmLocalSink} drops where it runs). The emitter this serves
 * and what it is worth: rontolisp's {@code .kb/wasm-landing-pad-refresh.md}.
 */
public final class WasmCarriedLocals {

	private WasmCarriedLocals() {
	}

	/**
	 * One carry, as byte offsets within the code entry.
	 *
	 * @param pushStart the first byte of the {@code local.get} run
	 * @param pushEnd the first byte after it: the {@code block} the values are carried
	 * across
	 * @param popStart the first byte of the {@code local.set} run that writes them back
	 * @param popEnd the first byte after it
	 */
	public record Carry(int pushStart, int pushEnd, int popStart, int popEnd) {
	}

	/**
	 * Narrows every carry of one code entry to the locals live after its write-back.
	 * @param entry a code entry: the locals vector, then the instructions
	 * @param carries the entry's carries, in any order
	 * @return the entry without its dead carried locals; the input itself when every
	 * carried local is live
	 * @throws IllegalStateException when a claimed span is not a carry
	 */
	public static byte[] narrow(byte[] entry, List<Carry> carries) {
		if (carries.isEmpty()) {
			return entry;
		}
		List<Instr> code = WasmCodeModel.decodeStructure(entry).code();
		Runs runs = Runs.locate(code, carries);
		boolean[] kept = new Flow(code, runs).kept();
		return runs.rewrite(entry, code, kept);
	}

	/**
	 * The carries as instruction indices. Carry {@code k} pushes from instruction
	 * {@code pushFrom[k]} and writes back from {@code popFrom[k]}; its locals are the
	 * POSITIONS {@code first[k]} to {@code first[k + 1]}, in push order, and position
	 * {@code p} carries local {@code slot[p]} for carry {@code carryOf[p]}.
	 */
	private record Runs(int[] pushFrom, int[] popFrom, int[] first, int[] slot, int[] carryOf) {

		static Runs locate(List<Instr> code, List<Carry> carries) {
			int[] starts = new int[code.size()];
			for (int i = 0; i < starts.length; i++) {
				starts[i] = code.get(i).start;
			}
			int count = carries.size();
			int[] pushFrom = new int[count];
			int[] popFrom = new int[count];
			int[] first = new int[count + 1];
			Ints slots = new Ints();
			Ints carryOf = new Ints();
			boolean[] claimed = new boolean[starts.length];
			for (int k = 0; k < count; k++) {
				Carry carry = carries.get(k);
				int push = at(starts, carry.pushStart());
				int block = at(starts, carry.pushEnd());
				int pop = at(starts, carry.popStart());
				int popEnd = at(starts, carry.popEnd());
				if (push < 0 || block < 0 || pop < 0 || popEnd < 0) {
					throw notACarry(carry, "a span does not start and end at instruction boundaries");
				}
				int length = block - push;
				if (length <= 0 || popEnd - pop != length) {
					throw notACarry(carry, "the runs are empty or differ in length");
				}
				Instr opener = code.get(block);
				BlockType type = opener.blockType;
				if (opener.op != Instruction.BLOCK || type == null || !type.params().isEmpty()
						|| type.results().size() != 1) {
					throw notACarry(carry, "the push is not in front of a block with one result");
				}
				if (claimed[block]) {
					throw notACarry(carry, "another carry names the same block");
				}
				claimed[block] = true;
				if (pop != opener.match + 2 || code.get(pop - 1).op != Instruction.SET_LOCAL) {
					throw notACarry(carry, "the write-back is not behind the block's end and the set of its result");
				}
				for (int j = 0; j < length; j++) {
					Instr get = code.get(push + j);
					Instr set = code.get(popEnd - 1 - j);
					if (get.op != Instruction.GET_LOCAL || set.op != Instruction.SET_LOCAL || get.a != set.a) {
						throw notACarry(carry, "the write-back is not the push reversed");
					}
					slots.add((int) get.a);
					carryOf.add(k);
				}
				pushFrom[k] = push;
				popFrom[k] = pop;
				first[k + 1] = first[k] + length;
			}
			return new Runs(pushFrom, popFrom, first, slots.toArray(), carryOf.toArray());
		}

		// The instruction starting at this byte, or -1 when none does.
		private static int at(int[] starts, int offset) {
			int i = Arrays.binarySearch(starts, offset);
			return i >= 0 ? i : -1;
		}

		private static IllegalStateException notACarry(Carry carry, String why) {
			return new IllegalStateException("WasmCarriedLocals: " + carry + " is not a carry: " + why);
		}

		int count() {
			return this.pushFrom.length;
		}

		int length(int carry) {
			return this.first[carry + 1] - this.first[carry];
		}

		/**
		 * The instruction just after carry {@code k}'s write-back: where it must be live.
		 */
		int after(int carry) {
			return this.popFrom[carry] + length(carry);
		}

		/**
		 * The entry without the push and the write-back of every position not kept.
		 * @param entry the code entry
		 * @param code its decoded instructions
		 * @param kept per position, whether its local is kept
		 * @return the narrowed entry, or {@code entry} itself when every position is kept
		 */
		byte[] rewrite(byte[] entry, List<Instr> code, boolean[] kept) {
			boolean[] drop = new boolean[code.size()];
			boolean any = false;
			for (int p = 0; p < kept.length; p++) {
				if (!kept[p]) {
					int k = this.carryOf[p];
					int j = p - this.first[k];
					drop[this.pushFrom[k] + j] = true;
					drop[this.popFrom[k] + length(k) - 1 - j] = true;
					any = true;
				}
			}
			if (!any) {
				return entry;
			}
			ByteArrayOutputStream out = new UnsynchronizedByteArrayOutputStream(entry.length);
			int copyFrom = 0;
			for (int i = 0; i < drop.length; i++) {
				if (drop[i]) {
					Instr in = code.get(i);
					out.write(entry, copyFrom, in.start - copyFrom);
					copyFrom = in.end;
				}
			}
			out.write(entry, copyFrom, entry.length - copyFrom);
			return out.toByteArray();
		}

	}

	/**
	 * The body as a control-flow graph whose nodes are its basic blocks plus one node per
	 * {@code try_table} standing for "an exception thrown inside it", and which blocks
	 * read and write each carried local. A block ends at every structured instruction,
	 * after every branch, after every call or throw inside a {@code try_table}, and at
	 * both ends of every carry run -- so a push run and a write-back run are each a block
	 * of their own, and the instruction a write-back ends at starts one.
	 */
	private static final class Flow {

		private final Runs runs;

		/** The dense number of each carried local, by local index; -1 for the others. */
		private final int[] trackedOf;

		private final int tracked;

		private final int[] blockOf;

		private final int nodes;

		/** Per node, its predecessors. */
		private final Grouped preds;

		/**
		 * Per carried local, the blocks that read it before writing it. A push run reads
		 * nothing here: what it keeps is added as the solution grows.
		 */
		private final Grouped uses;

		/** Per carried local, the blocks that write it. */
		private final Grouped defs;

		Flow(List<Instr> code, Runs runs) {
			this.runs = runs;
			int max = -1;
			for (int local : runs.slot()) {
				max = Math.max(max, local);
			}
			this.trackedOf = new int[max + 1];
			Arrays.fill(this.trackedOf, -1);
			int t = 0;
			for (int local : runs.slot()) {
				if (this.trackedOf[local] < 0) {
					this.trackedOf[local] = t++;
				}
			}
			this.tracked = t;

			int n = code.size();
			boolean[] leader = leaders(code, runs);
			this.blockOf = new int[n];
			int blocks = 0;
			for (int i = 0; i < n; i++) {
				if (leader[i]) {
					blocks++;
				}
				this.blockOf[i] = blocks - 1;
			}
			boolean[] pushBlock = new boolean[blocks];
			for (int k = 0; k < runs.count(); k++) {
				pushBlock[this.blockOf[runs.pushFrom()[k]]] = true;
			}

			// One forward walk with the control stack: the edges out of every block (and
			// out of every try_table's node), and the carried locals each block reads
			// before writing, and writes.
			Edges edges = new Edges();
			Pairs uses = new Pairs();
			Pairs defs = new Pairs();
			int[] usedIn = new int[this.tracked];
			int[] definedIn = new int[this.tracked];
			Arrays.fill(usedIn, -1);
			Arrays.fill(definedIn, -1);
			ControlStack ctrl = new ControlStack(code);
			Ints tryNodes = new Ints();
			int tries = 0;
			for (int i = 0; i < n; i++) {
				Instr in = code.get(i);
				int b = this.blockOf[i];
				boolean last = i == n - 1 || leader[i + 1];
				switch (in.op) {
					case Instruction.BLOCK, Instruction.LOOP -> {
						edges.add(b, this.blockOf[i + 1]);
						ctrl.open(i);
					}
					case Instruction.IF -> {
						edges.add(b, this.blockOf[i + 1]);
						edges.add(b, this.blockOf[in.elseIndex >= 0 ? in.elseIndex + 1 : in.match]);
						ctrl.open(i);
					}
					case Instruction.TRY_TABLE -> {
						edges.add(b, this.blockOf[i + 1]);
						// An exception thrown inside reaches each catch label -- resolved
						// outside the try_table, whose own label it does not count -- or
						// else the next try_table out.
						int node = blocks + tries++;
						for (Catch c : Objects.requireNonNull(in.catches)) {
							edges.add(node, this.blockOf[ctrl.target(c.label())]);
						}
						if (tryNodes.size() > 0) {
							edges.add(node, tryNodes.last());
						}
						ctrl.open(i);
						tryNodes.add(node);
					}
					case Instruction.ELSE -> edges.add(b, this.blockOf[code.get(in.match).match]);
					case Instruction.END -> {
						if (!ctrl.isEmpty() && code.get(ctrl.close()).op == Instruction.TRY_TABLE) {
							tryNodes.pop();
						}
						if (i + 1 < n) {
							edges.add(b, this.blockOf[i + 1]);
						}
					}
					case Instruction.BR -> edges.add(b, this.blockOf[ctrl.target((int) in.a)]);
					case Instruction.BR_IF -> {
						edges.add(b, this.blockOf[i + 1]);
						edges.add(b, this.blockOf[ctrl.target((int) in.a)]);
					}
					case Instruction.BR_TABLE -> {
						for (int label : Objects.requireNonNull(in.labels)) {
							edges.add(b, this.blockOf[ctrl.target(label)]);
						}
					}
					// br_on_cast / br_on_cast_fail: a conditional branch, as br_if.
					case Instruction.GC_PREFIX -> {
						if (in.isCastBranch()) {
							edges.add(b, this.blockOf[i + 1]);
							edges.add(b, this.blockOf[ctrl.target((int) in.a)]);
						}
						else if (last) {
							edges.add(b, this.blockOf[i + 1]);
						}
					}
					case Instruction.RETURN, Instruction.UNREACHABLE -> {
					}
					// A tail call leaves the frame, so its callee's exception finds no
					// handler here; counting one anyway can only keep a local.
					case Instruction.THROW, Instruction.THROW_REF, Instruction.RETURN_CALL -> {
						if (tryNodes.size() > 0) {
							edges.add(b, tryNodes.last());
						}
					}
					case Instruction.CALL -> {
						if (last) {
							edges.add(b, this.blockOf[i + 1]);
						}
						if (tryNodes.size() > 0) {
							edges.add(b, tryNodes.last());
						}
					}
					default -> {
						if (last) {
							edges.add(b, this.blockOf[i + 1]);
						}
						// A push run's reads are not uses until the solution keeps them.
						boolean access = in.op == Instruction.GET_LOCAL || in.op == Instruction.SET_LOCAL
								|| in.op == Instruction.TEE_LOCAL;
						int local = access && !pushBlock[b] ? tracked((int) in.a) : -1;
						if (local >= 0 && in.op == Instruction.GET_LOCAL) {
							if (definedIn[local] != b && usedIn[local] != b) {
								usedIn[local] = b;
								uses.add(local, b);
							}
						}
						else if (local >= 0 && definedIn[local] != b) {
							definedIn[local] = b;
							defs.add(local, b);
						}
					}
				}
			}
			this.nodes = blocks + tries;
			this.preds = edges.predecessors(this.nodes);
			this.uses = uses.group(this.tracked);
			this.defs = defs.group(this.tracked);
		}

		private int tracked(int local) {
			return local < this.trackedOf.length ? this.trackedOf[local] : -1;
		}

		/**
		 * The least fixed point: which carried locals are live after their carry's
		 * write-back, when exactly those are pushed.
		 * @return per position, whether its local is kept
		 */
		boolean[] kept() {
			int[] slot = this.runs.slot();
			boolean[] kept = new boolean[slot.length];
			Pairs positions = new Pairs();
			for (int p = 0; p < slot.length; p++) {
				positions.add(this.trackedOf[slot[p]], p);
			}
			Grouped carried = positions.group(this.tracked);
			Liveness liveness = new Liveness(this.nodes);
			for (int t = 0; t < this.tracked; t++) {
				liveness.writtenIn(this.defs, t);
				for (int u = this.uses.start()[t]; u < this.uses.start()[t + 1]; u++) {
					liveness.reach(this.uses.values()[u]);
				}
				boolean grew = true;
				while (grew) {
					liveness.propagate(this.preds);
					grew = false;
					for (int c = carried.start()[t]; c < carried.start()[t + 1]; c++) {
						int p = carried.values()[c];
						int k = this.runs.carryOf()[p];
						if (!kept[p] && liveness.isLive(this.blockOf[this.runs.after(k)])) {
							kept[p] = true;
							liveness.reach(this.blockOf[this.runs.pushFrom()[k]]);
							grew = true;
						}
					}
				}
				liveness.clear(this.defs, t);
			}
			return kept;
		}

		private static boolean[] leaders(List<Instr> code, Runs runs) {
			int n = code.size();
			boolean[] leader = new boolean[n + 1];
			leader[0] = true;
			Ints openers = new Ints();
			int tryDepth = 0;
			for (int i = 0; i < n; i++) {
				Instr in = code.get(i);
				switch (in.op) {
					case Instruction.BLOCK, Instruction.LOOP, Instruction.IF, Instruction.TRY_TABLE -> {
						leader[i] = true;
						leader[i + 1] = true;
						openers.add(in.op);
						if (in.op == Instruction.TRY_TABLE) {
							tryDepth++;
						}
					}
					case Instruction.ELSE -> {
						leader[i] = true;
						leader[i + 1] = true;
					}
					case Instruction.END -> {
						leader[i] = true;
						leader[i + 1] = true;
						if (openers.size() > 0 && openers.pop() == Instruction.TRY_TABLE) {
							tryDepth--;
						}
					}
					case Instruction.BR, Instruction.BR_IF, Instruction.BR_TABLE, Instruction.RETURN,
							Instruction.UNREACHABLE, Instruction.THROW, Instruction.THROW_REF,
							Instruction.RETURN_CALL ->
						leader[i + 1] = true;
					case Instruction.CALL -> {
						if (tryDepth > 0) {
							leader[i + 1] = true;
						}
					}
					case Instruction.GC_PREFIX -> {
						if (in.isCastBranch()) {
							leader[i + 1] = true;
						}
					}
					default -> {
					}
				}
			}
			for (int k = 0; k < runs.count(); k++) {
				leader[runs.pushFrom()[k]] = true;
				leader[runs.pushFrom()[k] + runs.length(k)] = true;
				leader[runs.popFrom()[k]] = true;
				leader[runs.after(k)] = true;
			}
			return leader;
		}

	}

	/**
	 * One carried local's liveness on entry to every node, grown backward from the blocks
	 * that read it and cleared for the next local.
	 */
	private static final class Liveness {

		private final boolean[] live;

		private final boolean[] written;

		private final int[] pending;

		private int pendingSize;

		private final int[] touched;

		private int touchedSize;

		Liveness(int nodes) {
			this.live = new boolean[nodes];
			this.written = new boolean[nodes];
			this.pending = new int[nodes];
			this.touched = new int[nodes];
		}

		boolean isLive(int node) {
			return this.live[node];
		}

		/** Marks the blocks that write local {@code t}. */
		void writtenIn(Grouped defs, int t) {
			for (int d = defs.start()[t]; d < defs.start()[t + 1]; d++) {
				this.written[defs.values()[d]] = true;
			}
		}

		/** Marks a node live on entry: it reads the local before writing it. */
		void reach(int node) {
			if (!this.live[node]) {
				this.live[node] = true;
				this.touched[this.touchedSize++] = node;
				this.pending[this.pendingSize++] = node;
			}
		}

		/**
		 * Carries the marks backward: a predecessor that does not write the local is live
		 * on entry too.
		 */
		void propagate(Grouped preds) {
			while (this.pendingSize > 0) {
				int node = this.pending[--this.pendingSize];
				for (int p = preds.start()[node]; p < preds.start()[node + 1]; p++) {
					int pred = preds.values()[p];
					if (!this.written[pred]) {
						reach(pred);
					}
				}
			}
		}

		/** Forgets local {@code t}, whose writes are {@code defs}'. */
		void clear(Grouped defs, int t) {
			for (int i = 0; i < this.touchedSize; i++) {
				this.live[this.touched[i]] = false;
			}
			this.touchedSize = 0;
			for (int d = defs.start()[t]; d < defs.start()[t + 1]; d++) {
				this.written[defs.values()[d]] = false;
			}
		}

	}

	/** The structured constructs open at an instruction, innermost last. */
	private static final class ControlStack {

		private final List<Instr> code;

		private final Ints openers = new Ints();

		ControlStack(List<Instr> code) {
			this.code = code;
		}

		void open(int opener) {
			this.openers.add(opener);
		}

		/** Closes the innermost construct, answering its opener. */
		int close() {
			return this.openers.pop();
		}

		boolean isEmpty() {
			return this.openers.size() == 0;
		}

		/**
		 * The instruction a branch to {@code label} continues at: a loop's start, any
		 * other construct's end, and the function's own end past the outermost one.
		 */
		int target(int label) {
			int depth = this.openers.size();
			if (label >= depth) {
				return this.code.size() - 1;
			}
			int opener = this.openers.get(depth - 1 - label);
			Instr in = this.code.get(opener);
			return in.op == Instruction.LOOP ? opener : in.match;
		}

	}

	/** The edges of the flow graph, kept for their predecessor lists. */
	private static final class Edges {

		private final Pairs byDestination = new Pairs();

		void add(int source, int destination) {
			this.byDestination.add(destination, source);
		}

		Grouped predecessors(int nodes) {
			return this.byDestination.group(nodes);
		}

	}

	/** Key-value pairs of ints, grouped by key once complete. */
	private static final class Pairs {

		private final Ints keys = new Ints();

		private final Ints values = new Ints();

		void add(int key, int value) {
			this.keys.add(key);
			this.values.add(value);
		}

		/** Counting sort by key; a key's values keep the order they were added in. */
		Grouped group(int keyCount) {
			int[] start = new int[keyCount + 1];
			for (int i = 0; i < this.keys.size(); i++) {
				start[this.keys.get(i) + 1]++;
			}
			for (int k = 0; k < keyCount; k++) {
				start[k + 1] += start[k];
			}
			int[] fill = Arrays.copyOf(start, keyCount);
			int[] grouped = new int[this.keys.size()];
			for (int i = 0; i < this.keys.size(); i++) {
				grouped[fill[this.keys.get(i)]++] = this.values.get(i);
			}
			return new Grouped(start, grouped);
		}

	}

	/**
	 * Values grouped by key: key {@code k}'s are
	 * {@code values[start[k] .. start[k + 1])}.
	 */
	private record Grouped(int[] start, int[] values) {
	}

	/** A growable int array, also used as a stack. */
	private static final class Ints {

		private int[] values = new int[16];

		private int size;

		void add(int value) {
			if (this.size == this.values.length) {
				this.values = Arrays.copyOf(this.values, this.size * 2);
			}
			this.values[this.size++] = value;
		}

		int get(int index) {
			return this.values[index];
		}

		int size() {
			return this.size;
		}

		int last() {
			return this.values[this.size - 1];
		}

		int pop() {
			return this.values[--this.size];
		}

		int[] toArray() {
			return Arrays.copyOf(this.values, this.size);
		}

	}

}
