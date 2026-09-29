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
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NopInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

/**
 * Plays a method body -- the instruction records {@link MethodCode} keeps -- into a
 * {@code java.lang.classfile} {@link CodeBuilder}, instruction by instruction, so the
 * class-file writer does the rest: branch targets and handler ranges become labels, an
 * {@code ldc} takes the width its constant's index in the written class needs, a local's
 * load or store, an {@code iinc} and an int constant take their shortest form, and the
 * frames, {@code max_stack} and {@code max_locals} are derived from the code. A branch
 * that does not reach in 16 bits is written in its long form here ({@link #farBranches}).
 * <p>
 * Every constant-pool operand is a master-pool entry, resolved through the caller's
 * function -- which is where a call to a method that moved to another class is
 * re-pointed.
 */
final class CodeReplay {

	/** The one-byte, operand-free instructions, shared: they are immutable. */
	private static final @Nullable Instruction[] SIMPLE = new Instruction[256];

	static {
		for (Opcode op : Opcode.values()) {
			if (!op.isWide() && op.sizeIfFixed() == 1) {
				SIMPLE[op.bytecode()] = simple(op);
			}
		}
	}

	private CodeReplay() {
	}

	private static @Nullable Instruction simple(Opcode op) {
		return switch (op.kind()) {
			case CONSTANT -> ConstantInstruction.ofIntrinsic(op);
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
	 * @param method the method
	 * @param cb the builder of the method's {@code Code} attribute
	 * @param operand the entry a master-pool entry stands for in the class being written
	 */
	static void replay(ClassDefinition.Method method, CodeBuilder cb, UnaryOperator<PoolEntry> operand) {
		MethodCode body = method.body();
		int n = body.count();
		// Every position something jumps to, or a handler range starts, ends or lands
		// at, gets a label.
		BitSet targets = new BitSet(n + 1);
		for (int i = 0; i < n; i++) {
			if (body.opcode(i).kind() == Opcode.Kind.BRANCH) {
				targets.set(body.target(i));
			}
		}
		for (MethodCode.Handler handler : body.handlers()) {
			targets.set(handler.start());
			targets.set(handler.end());
			targets.set(handler.handler());
		}
		@Nullable Label[] labels = new Label[n + 1];
		for (int t = targets.nextSetBit(0); t >= 0; t = targets.nextSetBit(t + 1)) {
			labels[t] = cb.newLabel();
		}
		BitSet far = farBranches(body);
		List<ClassDefinition.Line> lines = method.lineNumbers();
		int line = 0;
		for (int i = 0; i < n; i++) {
			Label label = labels[i];
			if (label != null) {
				cb.labelBinding(label);
			}
			// A line entry belongs to the instruction it starts at: the last one there
			// wins, as it does in the table.
			int number = -1;
			while (line < lines.size() && lines.get(line).position() <= i) {
				number = lines.get(line++).lineNumber();
			}
			if (number >= 0) {
				cb.lineNumber(number);
			}
			play(body, i, cb, labels, operand, far.get(i));
		}
		Label end = labels[n];
		if (end != null) {
			cb.labelBinding(end);
		}
		for (MethodCode.Handler handler : body.handlers()) {
			ClassEntry type = handler.catchType();
			cb.exceptionCatch(label(labels, handler.start()), label(labels, handler.end()),
					label(labels, handler.handler()),
					type == null ? Optional.empty() : Optional.of((ClassEntry) operand.apply(type)));
		}
	}

	private static Label label(@Nullable Label[] labels, int position) {
		return Objects.requireNonNull(labels[position]);
	}

	/** Writes the instruction at position {@code i}. */
	private static void play(MethodCode body, int i, CodeBuilder cb, @Nullable Label[] labels,
			UnaryOperator<PoolEntry> operand, boolean far) {
		Opcode op = body.opcode(i);
		Instruction simple = SIMPLE[op.bytecode()];
		if (simple != null) {
			cb.with(simple);
			return;
		}
		int arg = body.operand(i);
		switch (op) {
			case BIPUSH, SIPUSH -> cb.loadConstant(arg);
			case LDC, LDC2_W -> cb.ldc((LoadableConstantEntry) operand.apply(entry(body, i)));
			case ILOAD -> cb.loadLocal(TypeKind.INT, arg);
			case LLOAD -> cb.loadLocal(TypeKind.LONG, arg);
			case FLOAD -> cb.loadLocal(TypeKind.FLOAT, arg);
			case DLOAD -> cb.loadLocal(TypeKind.DOUBLE, arg);
			case ALOAD -> cb.loadLocal(TypeKind.REFERENCE, arg);
			case ISTORE -> cb.storeLocal(TypeKind.INT, arg);
			case LSTORE -> cb.storeLocal(TypeKind.LONG, arg);
			case FSTORE -> cb.storeLocal(TypeKind.FLOAT, arg);
			case DSTORE -> cb.storeLocal(TypeKind.DOUBLE, arg);
			case ASTORE -> cb.storeLocal(TypeKind.REFERENCE, arg);
			case IINC -> cb.iinc(arg & 0xFFFF, arg >> 16);
			case GETSTATIC, PUTSTATIC, GETFIELD, PUTFIELD ->
				cb.fieldAccess(op, (FieldRefEntry) operand.apply(entry(body, i)));
			case INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC ->
				cb.invoke(op, (MemberRefEntry) operand.apply(entry(body, i)));
			case INVOKEINTERFACE -> cb.invokeinterface((InterfaceMethodRefEntry) operand.apply(entry(body, i)));
			case NEW -> cb.new_((ClassEntry) operand.apply(entry(body, i)));
			case NEWARRAY -> cb.newarray(TypeKind.fromNewarrayCode(arg));
			case ANEWARRAY -> cb.anewarray((ClassEntry) operand.apply(entry(body, i)));
			case CHECKCAST -> cb.checkcast((ClassEntry) operand.apply(entry(body, i)));
			case INSTANCEOF -> cb.instanceOf((ClassEntry) operand.apply(entry(body, i)));
			default -> {
				if (op.kind() != Opcode.Kind.BRANCH) {
					throw new IllegalStateException("unsupported " + op + " at " + i);
				}
				Label target = label(labels, body.target(i));
				if (!far) {
					cb.branch(op, target);
				}
				else if (op == Opcode.GOTO) {
					cb.branch(Opcode.GOTO_W, target);
				}
				else {
					// The inverted short branch jumps over the goto_w that reaches.
					Label skip = cb.newLabel();
					cb.branch(inverse(op), skip);
					cb.branch(Opcode.GOTO_W, target);
					cb.labelBinding(skip);
				}
			}
		}
	}

	private static PoolEntry entry(MethodCode body, int i) {
		return Objects.requireNonNull(body.entry(i));
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
	 * can push another out of range).
	 * <p>
	 * The layout is the code as written ({@link MethodCode#length}) but for one thing the
	 * writer decides: an {@code ldc} whose constant lands past index 255 in the written
	 * class's pool takes the three-byte {@code ldc_w}. A branch counts as reaching only
	 * with one byte to spare for every two-byte {@code ldc} of the method.
	 * @param body the method's body
	 * @return the positions of the branches to write in their long form
	 */
	static BitSet farBranches(MethodCode body) {
		BitSet far = new BitSet();
		int n = body.count();
		int[] start = new int[n + 1];
		int[] branches = new int[16];
		int count = 0;
		int ldcs = 0;
		int pc = 0;
		for (int i = 0; i < n; i++) {
			start[i] = pc;
			int length = body.length(i);
			Opcode op = body.opcode(i);
			if (op == Opcode.LDC && length == 2) {
				ldcs++;
			}
			else if (op.kind() == Opcode.Kind.BRANCH) {
				if (count == branches.length) {
					branches = Arrays.copyOf(branches, count * 2);
				}
				branches[count++] = i;
			}
			pc += length;
		}
		start[n] = pc;
		int reach = Short.MAX_VALUE - ldcs;
		if (pc <= reach) {
			// No offset within the method can exceed its length.
			return far;
		}
		int[] growth = new int[n];
		int[] shift = new int[n + 1];
		boolean changed = true;
		while (changed) {
			changed = false;
			int total = 0;
			for (int i = 0; i < n; i++) {
				shift[i] = total;
				total += growth[i];
			}
			shift[n] = total;
			for (int b = 0; b < count; b++) {
				int at = branches[b];
				if (growth[at] != 0) {
					continue;
				}
				int target = body.target(at);
				int offset = start[target] + shift[target] - (start[at] + shift[at]);
				if (offset > reach || offset < -reach - 1) {
					// A goto becomes a goto_w (3 -> 5 bytes); a conditional branch its
					// inverted short branch over one (3 -> 8).
					growth[at] = body.opcode(at) == Opcode.GOTO ? 2 : 5;
					far.set(at);
					changed = true;
				}
			}
		}
		return far;
	}

}
