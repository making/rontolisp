package am.ik.sluice.client.filter;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;

import org.jspecify.annotations.Nullable;
import run.endive.runtime.ExecutionListener;
import run.endive.runtime.MStack;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.AnnotatedInstruction;
import run.endive.wasm.types.FunctionBody;
import run.endive.wasm.types.Instruction;
import run.endive.wasm.types.OpCode;

/**
 * A measurement only: the instructions of a module, by opcode, by function, by the export
 * that was running, and as a call tree.
 */
public final class OpCounter implements ExecutionListener {

	/** Executed instructions by opcode. */
	public static final long[] COUNTS = new long[OpCode.values().length];

	/** The last module started, as that is what is counted. */
	public static @Nullable OpCounter current;

	/** Per export called: instructions executed under it. */
	public static final Map<String, long[]> BY_EXPORT = new TreeMap<>();

	public static long total;

	public static final class Node {

		final @Nullable String label;

		final int function;

		final @Nullable Node parent;

		final Map<Integer, Node> children = new java.util.HashMap<>();

		long self;

		long entries;

		Node(@Nullable String label, int function, @Nullable Node parent) {
			this.label = label;
			this.function = function;
			this.parent = parent;
		}

		Node child(int function) {
			return this.children.computeIfAbsent(function, f -> new Node(null, f, this));
		}

		long inclusive() {
			long sum = this.self;
			for (Node child : this.children.values()) {
				sum += child.inclusive();
			}
			return sum;
		}

		void print(PrintStream out, int depth, double divisor, long floor, IntFunction<String> names) {
			String label = this.label != null ? this.label : names.apply(this.function);
			out.printf("%8.0f %8.0f %5.1f  %s%s%n", inclusive() / divisor, this.self / divisor, this.entries / divisor,
					"  ".repeat(depth), label);
			List<Node> ordered = new ArrayList<>(this.children.values());
			ordered.sort((a, b) -> Long.compare(b.inclusive(), a.inclusive()));
			long hidden = 0;
			for (Node child : ordered) {
				if (child.inclusive() / divisor >= floor) {
					child.print(out, depth + 1, divisor, floor, names);
				}
				else {
					hidden += child.inclusive();
				}
			}
			if (hidden > 0) {
				out.printf("%8.0f %8s %5s  %s(below %d)%n", hidden / divisor, "", "", "  ".repeat(depth + 1), floor);
			}
		}

	}

	public static final Map<String, Node> ROOTS = new TreeMap<>();

	private static @Nullable Node at;

	private final @Nullable ExecutionListener next;

	private final int[] starts;

	private final int[] funcOf;

	private final int imported;

	/** Instructions executed per function index (imports included, always 0). */
	public final long[] byFunction;

	/** Entries per function index, by its first instruction. */
	public final long[] entries;

	/** Calls of an import, per function index. */
	public final long[] hostCalls;

	private final List<String> importNames = new ArrayList<>();

	private final Map<Integer, Integer> lastAddress = new java.util.HashMap<>();

	private boolean pendingCall;

	private boolean pendingTailCall;

	public String importName(int index) {
		return index < this.importNames.size() ? this.importNames.get(index) : "?";
	}

	public OpCounter(@Nullable ExecutionListener next, WasmModule module) {
		this.next = next;
		this.imported = (int) module.importSection().stream().count();
		module.importSection().stream().forEach(i -> this.importNames.add(i.module() + "." + i.name()));
		int bodies = module.codeSection().functionBodyCount();
		List<int[]> ranges = new ArrayList<>();
		for (int i = 0; i < bodies; i++) {
			FunctionBody body = module.codeSection().getFunctionBody(i);
			List<AnnotatedInstruction> instructions = body.instructions();
			if (!instructions.isEmpty()) {
				ranges.add(new int[] { instructions.get(0).address(), i + this.imported });
				this.lastAddress.put(instructions.get(instructions.size() - 1).address(), i + this.imported);
			}
		}
		ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
		this.starts = new int[ranges.size()];
		this.funcOf = new int[ranges.size()];
		for (int i = 0; i < ranges.size(); i++) {
			this.starts[i] = ranges.get(i)[0];
			this.funcOf[i] = ranges.get(i)[1];
		}
		this.byFunction = new long[bodies + this.imported];
		this.entries = new long[bodies + this.imported];
		this.hostCalls = new long[bodies + this.imported];
		current = this;
	}

	@Override
	public void onExecution(Instruction instruction, MStack stack) {
		OpCode opcode = instruction.opcode();
		COUNTS[opcode.ordinal()]++;
		total++;
		int found = Arrays.binarySearch(this.starts, instruction.address());
		int slot = found >= 0 ? found : -found - 2;
		int function = slot >= 0 ? this.funcOf[slot] : -1;
		if (function >= 0) {
			this.byFunction[function]++;
			if (found >= 0) {
				this.entries[function]++;
			}
		}
		Node node = at;
		if (node != null) {
			if (this.pendingCall) {
				node = node.child(function);
				node.entries++;
				this.pendingCall = false;
			}
			else if (this.pendingTailCall) {
				Node parent = node.parent;
				node = (parent != null ? parent : node).child(function);
				node.entries++;
				this.pendingTailCall = false;
			}
			else if (node.function != function) {
				// a return, or an exception that landed: back to the frame of this
				// function, or a frame of the export's own when entered by the host
				Node walk = node;
				while (walk != null && walk.function != function) {
					walk = walk.parent;
				}
				if (walk != null) {
					node = walk;
				}
				else {
					node = node.child(function);
					node.entries++;
				}
			}
			node.self++;
			at = node;
		}
		switch (opcode) {
			case CALL -> {
				int callee = (int) instruction.operand(0);
				if (callee < this.imported) {
					this.hostCalls[callee]++;
				}
				else {
					this.pendingCall = true;
				}
			}
			case RETURN_CALL -> {
				int callee = (int) instruction.operand(0);
				if (callee < this.imported) {
					this.hostCalls[callee]++;
				}
				else {
					this.pendingTailCall = true;
				}
			}
			case CALL_REF, CALL_INDIRECT -> this.pendingCall = true;
			case RETURN_CALL_REF, RETURN_CALL_INDIRECT -> this.pendingTailCall = true;
			case RETURN -> {
				if (at != null && at.parent != null && at.function >= 0) {
					at = at.parent;
				}
			}
			default -> {
				Integer ended = this.lastAddress.get(instruction.address());
				if (ended != null && at != null && at.parent != null && at.function == ended) {
					at = at.parent;
				}
			}
		}
		if (this.next != null) {
			this.next.onExecution(instruction, stack);
		}
	}

	/** A host call of an export starts. */
	public static void begin(String export) {
		at = ROOTS.computeIfAbsent(export, name -> new Node(name, -1, null));
		at.entries++;
		if (current != null) {
			current.pendingCall = true;
		}
	}

	/** The instructions a call of an export takes go to its name. */
	public static void entered(String export, long before) {
		long[] row = BY_EXPORT.computeIfAbsent(export, ignored -> new long[2]);
		row[0] += total - before;
		row[1]++;
		at = null;
		if (current != null) {
			current.pendingCall = false;
			current.pendingTailCall = false;
		}
	}

}
