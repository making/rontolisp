package am.ik.jvm;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NopInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

import org.jspecify.annotations.Nullable;

/**
 * Plays a method body written as code bytes -- the form the generators still emit -- into
 * a {@code java.lang.classfile} {@link CodeBuilder}, instruction by instruction, so the
 * class-file writer does the rest: branch targets become labels, an {@code ldc} takes the
 * width its constant's index in the written class needs, and the frames,
 * {@code max_stack} and {@code max_locals} are derived from the code. A branch that does
 * not reach in 16 bits is written in its long form here ({@link Layout}).
 * <p>
 * The bytes are read as the generators write them: a constant-pool operand's high part
 * arrives whole, so an index past 65535 still names its master-pool entry, and the
 * operand is resolved through the caller's function -- which is where a call to a method
 * that moved to another class is re-pointed. A local's load or store, an {@code iinc} and
 * a small int constant are written in their SHORTEST form whichever one was emitted
 * ({@code aload 2} becomes {@code aload_2}, {@code bipush 3} {@code iconst_3}), as
 * {@code CodeBuilder} writes them: a generator on {@link MethodCode} and one still on
 * code bytes then write the same class. Every other instruction keeps its form, so the
 * written code is never longer than the code a generator measured but for the {@code ldc}
 * widths and the relaxed branches.
 */
final class CodeReplay {

	private static final @Nullable Opcode[] OPCODES = new Opcode[256];

	private static final @Nullable Opcode[] WIDE_OPCODES = new Opcode[256];

	/** The one-byte, operand-free instructions, shared: they are immutable. */
	private static final @Nullable Instruction[] SIMPLE = new Instruction[256];

	/** The kind of local each {@code xload}/{@code xstore} moves, in opcode order. */
	private static final TypeKind[] LOCAL_KINDS = { TypeKind.INT, TypeKind.LONG, TypeKind.FLOAT, TypeKind.DOUBLE,
			TypeKind.REFERENCE };

	static {
		for (Opcode op : Opcode.values()) {
			if (op.isWide()) {
				WIDE_OPCODES[op.bytecode() & 0xFF] = op;
			}
			else {
				OPCODES[op.bytecode()] = op;
			}
		}
		for (Opcode op : OPCODES) {
			if (op != null && op.sizeIfFixed() == 1) {
				SIMPLE[op.bytecode()] = simple(op);
			}
		}
	}

	private CodeReplay() {
	}

	private static @Nullable Instruction simple(Opcode op) {
		int code = op.bytecode();
		return switch (op.kind()) {
			case CONSTANT -> ConstantInstruction.ofIntrinsic(op);
			// xload_<n> 0x1A..0x2D, xstore_<n> 0x3B..0x4E: the slot is the opcode's own.
			case LOAD -> LoadInstruction.of(op, (code - 0x1A) % 4);
			case STORE -> StoreInstruction.of(op, (code - 0x3B) % 4);
			case ARRAY_LOAD -> ArrayLoadInstruction.of(op);
			case ARRAY_STORE -> ArrayStoreInstruction.of(op);
			case STACK -> StackInstruction.of(op);
			case OPERATOR -> OperatorInstruction.of(op);
			case CONVERT -> ConvertInstruction.of(op);
			case RETURN -> ReturnInstruction.of(op);
			case THROW_EXCEPTION -> ThrowInstruction.of();
			case MONITOR -> MonitorInstruction.of(op);
			case NOP -> NopInstruction.of();
			default -> null;
		};
	}

	/**
	 * Plays a method body into a builder.
	 * @param method the method, its body as code bytes
	 * @param cb the builder of the method's {@code Code} attribute
	 * @param operand the entry a constant-pool operand index stands for in the class
	 * being written
	 */
	static void replay(ClassDefinition.Method method, CodeBuilder cb, IntFunction<PoolEntry> operand) {
		List<Integer> code = method.code();
		int n = code.size();
		Map<Integer, Integer> longTargets = new HashMap<>();
		for (ClassDefinition.Branch branch : method.longBranches()) {
			longTargets.put(branch.pc(), branch.target());
		}
		// Every position something jumps to, or a handler range starts, ends or lands
		// at, gets a label.
		BitSet targets = new BitSet(n + 1);
		Layout layout = new Layout(n);
		int pc = 0;
		while (pc < n) {
			int op = code.get(pc) & 0xFF;
			layout.instruction(pc, op);
			if (isBranch(op)) {
				int target = target(code, pc, op, longTargets);
				targets.set(target);
				if (op != 0xC8) {
					layout.branch(pc, op, target);
				}
			}
			pc += length(code, pc);
		}
		if (pc != n) {
			throw new IllegalStateException("the last instruction overruns the code, at " + pc + " of " + n);
		}
		BitSet far = layout.farBranches();
		for (ClassDefinition.Handler handler : method.exceptionTable()) {
			targets.set(handler.startPc());
			targets.set(handler.endPc());
			targets.set(handler.handlerPc());
		}
		Label[] labels = new Label[n + 1];
		for (int t = targets.nextSetBit(0); t >= 0; t = targets.nextSetBit(t + 1)) {
			labels[t] = cb.newLabel();
		}
		List<ClassDefinition.Line> lines = method.lineNumbers();
		int line = 0;
		pc = 0;
		while (pc < n) {
			if (labels[pc] != null) {
				cb.labelBinding(labels[pc]);
			}
			// A line entry belongs to the instruction it starts at: the last one there
			// wins, as it does in the table.
			int number = -1;
			while (line < lines.size() && lines.get(line).startPc() <= pc) {
				number = lines.get(line++).lineNumber();
			}
			if (number >= 0) {
				cb.lineNumber(number);
			}
			if (far.get(pc)) {
				int op = code.get(pc) & 0xFF;
				Label target = labels[target(code, pc, op, longTargets)];
				if (op == 0xA7) {
					cb.branch(Opcode.GOTO_W, target);
				}
				else {
					// The inverted short branch jumps over the goto_w that reaches.
					Label skip = cb.newLabel();
					cb.branch(inverse(java.util.Objects.requireNonNull(OPCODES[op])), skip);
					cb.branch(Opcode.GOTO_W, target);
					cb.labelBinding(skip);
				}
				pc += 3;
				continue;
			}
			pc = emit(code, pc, cb, labels, operand, longTargets);
		}
		if (labels[n] != null) {
			cb.labelBinding(labels[n]);
		}
		for (ClassDefinition.Handler handler : method.exceptionTable()) {
			Optional<ClassEntry> type = handler.catchType() == 0 ? Optional.empty()
					: Optional.of((ClassEntry) operand.apply(handler.catchType()));
			cb.exceptionCatch(labels[handler.startPc()], labels[handler.endPc()], labels[handler.handlerPc()], type);
		}
	}

	/**
	 * Emits the instruction at {@code pc}.
	 * @return the position of the next instruction
	 */
	private static int emit(List<Integer> code, int pc, CodeBuilder cb, Label[] labels, IntFunction<PoolEntry> operand,
			Map<Integer, Integer> longTargets) {
		int op = code.get(pc) & 0xFF;
		@Nullable Instruction simple = SIMPLE[op];
		if (simple != null) {
			cb.with(simple);
			return pc + 1;
		}
		switch (op) {
			case 0x10 -> cb.loadConstant((int) (byte) (int) code.get(pc + 1));
			case 0x11 -> cb.loadConstant((int) (short) u2(code, pc + 1));
			case 0x12 -> cb.with(ConstantInstruction.ofLoad(Opcode.LDC,
					(LoadableConstantEntry) operand.apply(oneByteIndex(code.get(pc + 1)))));
			case 0x13, 0x14 -> cb.with(ConstantInstruction.ofLoad(OPCODES[op],
					(LoadableConstantEntry) operand.apply(index(code, pc + 1))));
			case 0x15, 0x16, 0x17, 0x18, 0x19 -> cb.loadLocal(LOCAL_KINDS[op - 0x15], code.get(pc + 1) & 0xFF);
			case 0x36, 0x37, 0x38, 0x39, 0x3A -> cb.storeLocal(LOCAL_KINDS[op - 0x36], code.get(pc + 1) & 0xFF);
			case 0x84 -> cb.iinc(code.get(pc + 1) & 0xFF, (byte) (int) code.get(pc + 2));
			case 0xB2, 0xB3, 0xB4, 0xB5 ->
				cb.fieldAccess(OPCODES[op], (FieldRefEntry) operand.apply(index(code, pc + 1)));
			case 0xB6, 0xB7, 0xB8 -> cb.invoke(OPCODES[op], (MemberRefEntry) operand.apply(index(code, pc + 1)));
			case 0xB9 -> cb.invokeinterface((InterfaceMethodRefEntry) operand.apply(index(code, pc + 1)));
			case 0xBB -> cb.new_((ClassEntry) operand.apply(index(code, pc + 1)));
			case 0xBC -> cb.newarray(TypeKind.fromNewarrayCode(code.get(pc + 1) & 0xFF));
			case 0xBD -> cb.anewarray((ClassEntry) operand.apply(index(code, pc + 1)));
			case 0xC0 -> cb.checkcast((ClassEntry) operand.apply(index(code, pc + 1)));
			case 0xC1 -> cb.instanceOf((ClassEntry) operand.apply(index(code, pc + 1)));
			case 0xC4 -> {
				int widened = code.get(pc + 1) & 0xFF;
				int slot = u2(code, pc + 2);
				Opcode wide = WIDE_OPCODES[widened];
				switch (wide == null ? Opcode.Kind.NOP : wide.kind()) {
					case LOAD -> cb.loadLocal(LOCAL_KINDS[widened - 0x15], slot);
					case STORE -> cb.storeLocal(LOCAL_KINDS[widened - 0x36], slot);
					case INCREMENT -> cb.iinc(slot, (short) u2(code, pc + 4));
					default -> throw new IllegalStateException(
							String.format("wide 0x%02X at %d is not a load, store or iinc", widened, pc));
				}
			}
			case 0xC5 -> cb.multianewarray((ClassEntry) operand.apply(index(code, pc + 1)), code.get(pc + 3) & 0xFF);
			default -> {
				if (!isBranch(op)) {
					throw new IllegalStateException(String.format("unsupported opcode 0x%02X at %d", op, pc));
				}
				cb.branch(OPCODES[op], labels[target(code, pc, op, longTargets)]);
			}
		}
		return pc + length(code, pc);
	}

	/** The conditional branch that jumps exactly when {@code op} does not. */
	private static Opcode inverse(Opcode op) {
		return switch (op) {
			case IFEQ -> Opcode.IFNE;
			case IFNE -> Opcode.IFEQ;
			case IFLT -> Opcode.IFGE;
			case IFGE -> Opcode.IFLT;
			case IFGT -> Opcode.IFLE;
			case IFLE -> Opcode.IFGT;
			case IF_ICMPEQ -> Opcode.IF_ICMPNE;
			case IF_ICMPNE -> Opcode.IF_ICMPEQ;
			case IF_ICMPLT -> Opcode.IF_ICMPGE;
			case IF_ICMPGE -> Opcode.IF_ICMPLT;
			case IF_ICMPGT -> Opcode.IF_ICMPLE;
			case IF_ICMPLE -> Opcode.IF_ICMPGT;
			case IF_ACMPEQ -> Opcode.IF_ACMPNE;
			case IF_ACMPNE -> Opcode.IF_ACMPEQ;
			case IFNULL -> Opcode.IFNONNULL;
			case IFNONNULL -> Opcode.IFNULL;
			default -> throw new IllegalStateException(op + " is not a conditional branch");
		};
	}

	/**
	 * Which branches the written code needs in their long form: a {@code goto_w}, or an
	 * inverted short branch over one. The writer's own relaxation
	 * ({@code ShortJumpsOption.FIX_SHORT_JUMPS}) is not used: once one short jump
	 * overflows it rewrites EVERY forward branch of the method in the long form, and a
	 * method near the 64 KB limit then outgrows it -- measured on the jose test suite's
	 * {@code JSON::DECODE-JSON-ARRAY}, 82,541 bytes. Here only the branches that do not
	 * reach are widened, to a fixpoint (widening one moves every later instruction, which
	 * can push another out of range), as {@code BranchRelaxer} did on the finished bytes.
	 * <p>
	 * The layout is the code as written but for one thing the writer decides: an
	 * {@code ldc} whose constant lands past index 255 in the written class's pool takes
	 * the three-byte {@code ldc_w}. A branch counts as reaching only with one byte to
	 * spare for every {@code ldc} of the method.
	 */
	private static final class Layout {

		private final int length;

		private int[] starts = new int[64];

		private int count;

		private int[] branchPcs = new int[16];

		private int[] branchTargets = new int[16];

		private boolean[] branchIsGoto = new boolean[16];

		private int branches;

		private int ldcs;

		Layout(int length) {
			this.length = length;
		}

		void instruction(int pc, int op) {
			if (this.count == this.starts.length) {
				this.starts = java.util.Arrays.copyOf(this.starts, this.count * 2);
			}
			this.starts[this.count++] = pc;
			if (op == 0x12) {
				this.ldcs++;
			}
		}

		/**
		 * Records a short branch: {@code goto} or a conditional one.
		 */
		void branch(int pc, int op, int target) {
			if (this.branches == this.branchPcs.length) {
				this.branchPcs = java.util.Arrays.copyOf(this.branchPcs, this.branches * 2);
				this.branchTargets = java.util.Arrays.copyOf(this.branchTargets, this.branches * 2);
				this.branchIsGoto = java.util.Arrays.copyOf(this.branchIsGoto, this.branches * 2);
			}
			this.branchPcs[this.branches] = pc;
			this.branchTargets[this.branches] = target;
			this.branchIsGoto[this.branches] = op == 0xA7;
			this.branches++;
		}

		/**
		 * @return the positions of the branches to write in their long form
		 */
		BitSet farBranches() {
			BitSet far = new BitSet();
			int reach = Short.MAX_VALUE - this.ldcs;
			if (this.length <= reach) {
				// No offset within the method can exceed its length.
				return far;
			}
			int[] indexOf = new int[this.length + 1];
			for (int i = 0; i < this.count; i++) {
				indexOf[this.starts[i]] = i;
			}
			indexOf[this.length] = this.count;
			int[] growth = new int[this.count];
			int[] shift = new int[this.count + 1];
			boolean changed = true;
			while (changed) {
				changed = false;
				int total = 0;
				for (int i = 0; i < this.count; i++) {
					shift[i] = total;
					total += growth[i];
				}
				shift[this.count] = total;
				for (int b = 0; b < this.branches; b++) {
					int at = indexOf[this.branchPcs[b]];
					if (growth[at] != 0) {
						continue;
					}
					int target = this.branchTargets[b];
					int offset = target + shift[indexOf[target]] - (this.branchPcs[b] + shift[at]);
					if (offset > reach || offset < -reach - 1) {
						// A goto becomes a goto_w (3 -> 5 bytes); a conditional branch
						// its
						// inverted short branch over one (3 -> 8).
						growth[at] = this.branchIsGoto[b] ? 2 : 5;
						far.set(this.branchPcs[b]);
						changed = true;
					}
				}
			}
			return far;
		}

	}

	/**
	 * The conditional branches, {@code goto}, {@code ifnull}/{@code ifnonnull} and
	 * {@code goto_w}; {@code jsr} and the switches are never emitted.
	 */
	private static boolean isBranch(int op) {
		return (op >= 0x99 && op <= 0xA7) || op == 0xC6 || op == 0xC7 || op == 0xC8;
	}

	private static int target(List<Integer> code, int pc, int op, Map<Integer, Integer> longTargets) {
		Integer deferred = longTargets.get(pc);
		if (deferred != null) {
			return deferred;
		}
		if (op == 0xC8) {
			return pc + ((code.get(pc + 1) & 0xFF) << 24 | (code.get(pc + 2) & 0xFF) << 16
					| (code.get(pc + 3) & 0xFF) << 8 | (code.get(pc + 4) & 0xFF));
		}
		return pc + (short) u2(code, pc + 1);
	}

	/** The byte length of the instruction at {@code pc}. */
	static int length(List<Integer> code, int pc) {
		int op = code.get(pc) & 0xFF;
		if (SIMPLE[op] != null) {
			return 1;
		}
		return switch (op) {
			case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xBC -> 2;
			case 0x11, 0x13, 0x14, 0x84, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1 -> 3;
			case 0xB9, 0xC8 -> 5;
			case 0xC5 -> 4;
			case 0xC4 -> (code.get(pc + 1) & 0xFF) == 0x84 ? 6 : 4;
			default -> {
				if (isBranch(op)) {
					yield 3;
				}
				throw new IllegalStateException(String.format("unsupported opcode 0x%02X at %d", op, pc));
			}
		};
	}

	private static int u2(List<Integer> code, int at) {
		return (code.get(at) & 0xFF) << 8 | (code.get(at + 1) & 0xFF);
	}

	/**
	 * A two-byte constant-pool operand: the high part arrives whole from a writer that
	 * kept a past-65535 index; a negative one is a sign-extended byte, which only a value
	 * that fit 16 bits produces.
	 */
	static int index(List<Integer> code, int at) {
		int high = code.get(at);
		return ((high < 0 ? high & 0xFF : high) << 8) | (code.get(at + 1) & 0xFF);
	}

	static int oneByteIndex(int element) {
		return element < 0 ? element & 0xFF : element;
	}

}
