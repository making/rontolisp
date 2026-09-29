package am.ik.jvm;

import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * An operand-stack model maintained while a method body is emitted. Every instruction a
 * compile context's {@link MethodCode} records is applied here, and its effect on a stack
 * of computational types -- reference, int, float, long, double, the same granularity the
 * JVM verifier uses -- is kept. The model answers two questions a code generator cannot
 * otherwise answer about its own output: what is live on the operand stack right now (so
 * a value can be spilled to a local and reloaded), and how deep did the stack ever get
 * (so {@code max_stack} can be a computed number rather than a guess).
 *
 * <p>
 * Control flow is tracked through the labels. A branch records the stack shape at its
 * jump; when its label is bound, the position adopts that shape ({@link #reconcile}), and
 * a second branch to the same target must agree -- a disagreement is exactly what the
 * verifier would reject, so it is raised here as a compiler bug instead of being written
 * into an unverifiable class. After an unconditional transfer ({@code goto},
 * {@code athrow}, a return) the model is <em>unreachable</em> and instruction effects are
 * ignored until a label or an exception handler entry ({@link #enterHandler()}, whose
 * stack holds only the thrown exception) re-establishes a shape.
 *
 * <p>
 * The model is exact for the instruction set {@link MethodCode} records; an instruction
 * it cannot model raises rather than silently desynchronizing.
 */
public final class OperandStack {

	/** A computational type: what one operand-stack entry holds. */
	public enum Slot {

		/** A reference (including arrays and {@code null}). */
		REF,
		/**
		 * A reference to an object that {@code new} has allocated but no constructor has
		 * run on yet. The verifier tracks these apart from an ordinary reference, and a
		 * handler that discards the operand stack invalidates them -- so one cannot be
		 * spilled to a local across an exception-protected region.
		 */
		UNINIT,
		/**
		 * An {@code int} (also {@code boolean}/{@code byte}/{@code char}/{@code short}).
		 */
		INT,
		/** A {@code float}. */
		FLOAT,
		/** A {@code long} -- two JVM stack slots. */
		LONG,
		/** A {@code double} -- two JVM stack slots. */
		DOUBLE;

		/**
		 * {@return true when this type occupies two JVM stack slots}
		 */
		public boolean wide() {
			return this == LONG || this == DOUBLE;
		}

		/**
		 * {@return the number of JVM stack slots this type occupies}
		 */
		public int width() {
			return this.wide() ? 2 : 1;
		}

	}

	private final List<Slot> stack = new ArrayList<>();

	/** The shape each branch jumped with, by the branch's position. */
	private final Map<Integer, List<Slot>> branchShapes = new HashMap<>();

	private boolean reachable = true;

	private int maxDepth = 0;

	/** The position of the instruction being applied, for the messages. */
	private int at;

	private @Nullable Opcode opcode;

	/** Creates a model for an empty method body. */
	public OperandStack() {
	}

	/**
	 * {@return the operand-stack shape right now, bottom entry first}
	 */
	public List<Slot> snapshot() {
		return List.copyOf(this.stack);
	}

	/**
	 * {@return true when the code position about to be emitted is reachable}
	 */
	public boolean isReachable() {
		return this.reachable;
	}

	/**
	 * {@return the deepest the operand stack ever got, in JVM stack slots}
	 */
	public int maxDepth() {
		return this.maxDepth;
	}

	/**
	 * Marks the position about to be emitted as an exception handler entry: the JVM
	 * discards the operand stack on a throw and enters the handler with the thrown
	 * exception as its only operand.
	 */
	public void enterHandler() {
		this.stack.clear();
		this.stack.add(Slot.REF);
		this.reachable = true;
		this.record();
	}

	/**
	 * Marks the position about to be emitted as a join point the code generator knows is
	 * reached with the given operand-stack shape -- a label targeted by branches that may
	 * be emitted before or after this point (a backward jump's target has no recorded
	 * branch shape for {@link #reconcile} to establish). When the position is also
	 * reachable by fall-through, the two shapes must agree.
	 * @param shape the operand-stack shape every path reaches this position with
	 * @throws IllegalStateException when the fall-through shape disagrees -- a
	 * code-generator bug that would produce a class the verifier rejects
	 */
	public void joinShape(List<Slot> shape) {
		if (this.reachable && !this.stack.equals(shape)) {
			throw new IllegalStateException(
					"operand stack mismatch at join point: fall-through " + this.stack + " vs declared " + shape);
		}
		this.stack.clear();
		this.stack.addAll(shape);
		this.reachable = true;
		this.record();
	}

	/**
	 * Reconciles the model with a forward branch whose label is bound at the position
	 * about to be emitted: the position is reached with the shape the branch had at its
	 * jump.
	 * @param branch the branch's position
	 * @throws IllegalStateException when two paths reach the target with different
	 * operand stacks -- a code-generator bug that would produce a class the verifier
	 * rejects
	 */
	void reconcile(int branch) {
		List<Slot> shape = this.branchShapes.get(branch);
		if (shape == null) {
			// A branch emitted where the code was unreachable carries no shape.
			return;
		}
		if (!this.reachable) {
			this.stack.clear();
			this.stack.addAll(shape);
			this.reachable = true;
			this.record();
			return;
		}
		if (!this.stack.equals(shape)) {
			throw new IllegalStateException("operand stack mismatch at a branch target: fall-through " + this.stack
					+ " vs branch from " + branch + " " + shape);
		}
	}

	/**
	 * Applies a branch: its operands are popped and the shape its target is reached with
	 * recorded; the code after a {@code goto} is unreachable.
	 * @param position the branch's position
	 * @param op a conditional branch or {@code goto}
	 */
	void branch(int position, Opcode op) {
		if (!this.reachable) {
			return;
		}
		this.at = position;
		this.opcode = op;
		switch (op) {
			case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, IFNULL, IFNONNULL -> this.pop();
			case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE -> {
				this.pop();
				this.pop();
			}
			case GOTO -> {
			}
			default -> throw new IllegalStateException("operand-stack model: " + op + " is not a short branch");
		}
		this.branchShapes.put(position, List.copyOf(this.stack));
		if (op == Opcode.GOTO) {
			this.reachable = false;
		}
	}

	/**
	 * Applies one instruction that is not a branch.
	 * @param position its position
	 * @param op its opcode (a local's load or store in its explicit-slot form)
	 * @param entry the master-pool entry it names, or {@code null}
	 */
	void instruction(int position, Opcode op, @Nullable PoolEntry entry) {
		if (!this.reachable) {
			// Between an unconditional transfer and the next label the model has no
			// shape; the label (or handler entry) re-establishes one.
			return;
		}
		this.at = position;
		this.opcode = op;
		switch (op) {
			case NOP, INEG, LNEG, FNEG, DNEG, IINC, I2B, I2C, I2S, CHECKCAST -> {
			}
			case ACONST_NULL -> this.push(Slot.REF);
			case NEW -> this.push(Slot.UNINIT);
			case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5, BIPUSH, SIPUSH ->
				this.push(Slot.INT);
			case LCONST_0, LCONST_1 -> this.push(Slot.LONG);
			case FCONST_0, FCONST_1, FCONST_2 -> this.push(Slot.FLOAT);
			case DCONST_0, DCONST_1 -> this.push(Slot.DOUBLE);
			case LDC, LDC2_W -> this.push(switch (((LoadableConstantEntry) this.named(entry)).typeKind()) {
				case LONG -> Slot.LONG;
				case DOUBLE -> Slot.DOUBLE;
				case FLOAT -> Slot.FLOAT;
				case INT -> Slot.INT;
				default -> Slot.REF;
			});
			case ILOAD -> this.push(Slot.INT);
			case LLOAD -> this.push(Slot.LONG);
			case FLOAD -> this.push(Slot.FLOAT);
			case DLOAD -> this.push(Slot.DOUBLE);
			case ALOAD -> this.push(Slot.REF);
			case ISTORE, LSTORE, FSTORE, DSTORE, ASTORE, POP -> this.pop();
			case IALOAD, BALOAD, CALOAD, SALOAD -> this.replaceArrayLoad(Slot.INT);
			case LALOAD -> this.replaceArrayLoad(Slot.LONG);
			case FALOAD -> this.replaceArrayLoad(Slot.FLOAT);
			case DALOAD -> this.replaceArrayLoad(Slot.DOUBLE);
			case AALOAD -> this.replaceArrayLoad(Slot.REF);
			case IASTORE, LASTORE, FASTORE, DASTORE, AASTORE, BASTORE, CASTORE, SASTORE -> {
				this.pop();
				this.pop();
				this.pop();
			}
			case POP2 -> this.popSlots(2);
			case DUP -> this.duplicate(1, 0);
			case DUP_X1 -> this.duplicate(1, 1);
			case DUP_X2 -> this.duplicate(1, 2);
			case DUP2 -> this.duplicate(2, 0);
			case DUP2_X1 -> this.duplicate(2, 1);
			case DUP2_X2 -> this.duplicate(2, 2);
			case SWAP -> {
				Slot top = this.pop();
				Slot below = this.pop();
				this.push(top);
				this.push(below);
			}
			case IADD, ISUB, IMUL, IDIV, IREM, IAND, IOR, IXOR, ISHL, ISHR, IUSHR, LSHL, LSHR, LUSHR -> this.pop();
			case LADD, LSUB, LMUL, LDIV, LREM, LAND, LOR, LXOR, FADD, FSUB, FMUL, FDIV, FREM, DADD, DSUB, DMUL, DDIV,
					DREM ->
				this.pop();
			case I2L -> this.convert(Slot.LONG);
			case I2F -> this.convert(Slot.FLOAT);
			case I2D -> this.convert(Slot.DOUBLE);
			case L2I -> this.convert(Slot.INT);
			case L2F -> this.convert(Slot.FLOAT);
			case L2D -> this.convert(Slot.DOUBLE);
			case F2I -> this.convert(Slot.INT);
			case F2L -> this.convert(Slot.LONG);
			case F2D -> this.convert(Slot.DOUBLE);
			case D2I -> this.convert(Slot.INT);
			case D2L -> this.convert(Slot.LONG);
			case D2F -> this.convert(Slot.FLOAT);
			case LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> {
				this.pop();
				this.pop();
				this.push(Slot.INT);
			}
			case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, ATHROW -> {
				this.pop();
				this.reachable = false;
			}
			case RETURN -> this.reachable = false;
			case GETSTATIC -> this.push(fieldSlot(this.fieldDescriptor(entry)));
			case PUTSTATIC -> this.pop();
			case GETFIELD -> {
				this.pop();
				this.push(fieldSlot(this.fieldDescriptor(entry)));
			}
			case PUTFIELD -> {
				this.pop();
				this.pop();
			}
			case INVOKEVIRTUAL, INVOKEINTERFACE -> this.invoke(entry, true);
			case INVOKESPECIAL -> {
				// The constructor call that initializes what `new` allocated: every copy
				// of the receiver (the `dup` the caller left below the arguments) becomes
				// an ordinary reference.
				boolean constructing = this.invoke(entry, true) == Slot.UNINIT;
				if (constructing) {
					this.stack.replaceAll(slot -> slot == Slot.UNINIT ? Slot.REF : slot);
				}
			}
			case INVOKESTATIC -> this.invoke(entry, false);
			case NEWARRAY, ANEWARRAY -> {
				this.pop();
				this.push(Slot.REF);
			}
			case ARRAYLENGTH, INSTANCEOF -> {
				this.pop();
				this.push(Slot.INT);
			}
			default -> throw new IllegalStateException("operand-stack model: unsupported " + op + " at " + position);
		}
	}

	private PoolEntry named(@Nullable PoolEntry entry) {
		if (entry == null) {
			throw new IllegalStateException(
					"operand-stack model: the " + this.opcode + " at " + this.at + " names no constant-pool entry");
		}
		return entry;
	}

	private String fieldDescriptor(@Nullable PoolEntry entry) {
		if (!(this.named(entry) instanceof FieldRefEntry field)) {
			throw new IllegalStateException(
					"operand-stack model: the " + this.opcode + " at " + this.at + " names " + entry + ", not a field");
		}
		return field.type().stringValue();
	}

	/**
	 * {@return the receiver the invoked method was called on, null when it is static}
	 */
	private @Nullable Slot invoke(@Nullable PoolEntry entry, boolean hasReceiver) {
		if (!(this.named(entry) instanceof MemberRefEntry method) || method instanceof FieldRefEntry) {
			throw new IllegalStateException("operand-stack model: the " + this.opcode + " at " + this.at + " names "
					+ entry + ", not a method");
		}
		String descriptor = method.type().stringValue();
		for (int i = 0; i < argumentCount(descriptor); i++) {
			this.pop();
		}
		Slot receiver = hasReceiver ? this.pop() : null;
		Slot returned = returnSlot(descriptor);
		if (returned != null) {
			this.push(returned);
		}
		return receiver;
	}

	private void push(Slot slot) {
		this.stack.add(slot);
		this.record();
	}

	/**
	 * {@return the current depth in JVM stack slots (a long/double counts twice)}
	 */
	private int depth() {
		int depth = 0;
		for (Slot slot : this.stack) {
			depth += slot.width();
		}
		return depth;
	}

	private Slot pop() {
		if (this.stack.isEmpty()) {
			throw new IllegalStateException("operand-stack model: underflow at " + this.at + " (" + this.opcode + ")");
		}
		return this.stack.remove(this.stack.size() - 1);
	}

	/** Pops whole entries until {@code slots} JVM stack slots have been removed. */
	private void popSlots(int slots) {
		int remaining = slots;
		while (remaining > 0) {
			remaining -= this.pop().width();
		}
	}

	/**
	 * The whole {@code dup} family: duplicates the entries making up the top
	 * {@code topSlots} JVM stack slots and re-inserts the copy {@code underSlots} slots
	 * further down ({@code dup} = (1, 0), {@code dup_x1} = (1, 1), {@code dup2_x2} = (2,
	 * 2), and so on).
	 */
	private void duplicate(int topSlots, int underSlots) {
		List<Slot> top = this.take(topSlots);
		List<Slot> under = this.take(underSlots);
		top.forEach(this::push);
		under.forEach(this::push);
		top.forEach(this::push);
	}

	/**
	 * Removes and returns the entries making up the top {@code slots} slots, in order.
	 */
	private List<Slot> take(int slots) {
		List<Slot> taken = new ArrayList<>();
		int remaining = slots;
		while (remaining > 0) {
			Slot slot = this.pop();
			taken.addFirst(slot);
			remaining -= slot.width();
		}
		return taken;
	}

	private void replaceArrayLoad(Slot loaded) {
		this.pop();
		this.pop();
		this.push(loaded);
	}

	private void convert(Slot to) {
		this.pop();
		this.push(to);
	}

	/** Notes the current depth against the high-water mark {@code max_stack} reports. */
	private void record() {
		int depth = this.depth();
		if (depth > this.maxDepth) {
			this.maxDepth = depth;
		}
	}

	/**
	 * {@return the number of arguments a method descriptor declares}
	 */
	private static int argumentCount(String descriptor) {
		int count = 0;
		int i = 1;
		while (i < descriptor.length() && descriptor.charAt(i) != ')') {
			while (descriptor.charAt(i) == '[') {
				i++;
			}
			if (descriptor.charAt(i) == 'L') {
				i = descriptor.indexOf(';', i) + 1;
			}
			else {
				i++;
			}
			count++;
		}
		return count;
	}

	/**
	 * {@return the computational type a method descriptor returns, null for void}
	 */
	private static @Nullable Slot returnSlot(String descriptor) {
		String returned = descriptor.substring(descriptor.indexOf(')') + 1);
		return "V".equals(returned) ? null : fieldSlot(returned);
	}

	/**
	 * {@return the computational type of a field descriptor}
	 */
	private static Slot fieldSlot(String descriptor) {
		return switch (descriptor.charAt(0)) {
			case 'J' -> Slot.LONG;
			case 'D' -> Slot.DOUBLE;
			case 'F' -> Slot.FLOAT;
			case 'I', 'Z', 'B', 'C', 'S' -> Slot.INT;
			case 'L', '[' -> Slot.REF;
			default -> throw new IllegalStateException("operand-stack model: bad descriptor " + descriptor);
		};
	}

}
