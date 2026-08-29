package am.ik.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * An operand-stack model maintained while a method body is emitted. Every byte written
 * into the {@code Code} attribute is also fed to {@link #feed(int)}, which decodes the
 * instruction stream (opcode plus its operand bytes) and applies each instruction's
 * effect to a stack of computational types -- reference, int, float, long, double, the
 * same granularity the JVM verifier uses. The model answers two questions a code
 * generator cannot otherwise answer about its own output: what is live on the operand
 * stack right now (so a value can be spilled to a local and reloaded), and how deep did
 * the stack ever get (so {@code max_stack} can be a computed number rather than a guess).
 *
 * <p>
 * Control flow is tracked through the same back-patching the emitter already performs. A
 * branch records the stack shape at its jump; when the branch is patched, the target
 * position adopts that shape ({@link #reconcile}), and a second branch to the same target
 * must agree -- a disagreement is exactly what the verifier would reject, so it is raised
 * here as a compiler bug instead of being written into an unverifiable class. After an
 * unconditional transfer ({@code goto}, {@code athrow}, a return) the model is
 * <em>unreachable</em> and instruction effects are ignored until a label or an exception
 * handler entry ({@link #enterHandler()}, whose stack holds only the thrown exception)
 * re-establishes a shape.
 *
 * <p>
 * The model is exact for the instruction set an emitter can produce through this library;
 * an instruction it cannot model ({@code tableswitch}/{@code lookupswitch}/{@code jsr})
 * raises rather than silently desynchronizing. The {@code wide} prefix IS modelled: a
 * local index past 255 has no other encoding, and an emitter rewrites the pending
 * instruction into that form through {@link #awaitingLocalIndex()} /
 * {@link #widenPendingLocalIndex()}.
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

	/**
	 * How much operand stack an appended opaque block is assumed to use internally. The
	 * blocks are small straight-line sequences over locals; this only feeds
	 * {@code max_stack}, which has a floor well above it anyway.
	 */
	private static final int OPAQUE_BLOCK_HEADROOM = 8;

	private final ConstantPool cp;

	private final List<Slot> stack = new ArrayList<>();

	private final Map<Integer, List<Slot>> branchShapes = new HashMap<>();

	private boolean reachable = true;

	private int pc = 0;

	private int maxDepth = 0;

	private int opcode = -1;

	private int opcodePc = 0;

	private final int[] operands = new int[4];

	private int operandCount = 0;

	private int operandsExpected = 0;

	/** True between a {@code wide} prefix and the opcode it widens. */
	private boolean pendingWide = false;

	/**
	 * Creates a model for a method body whose constants come from the given pool (the
	 * pool resolves the descriptors of {@code invoke*}, the field ops and {@code ldc}).
	 * @param cp the constant pool the emitted code indexes into
	 */
	public OperandStack(ConstantPool cp) {
		this.cp = cp;
	}

	/**
	 * Feeds one emitted code byte to the model: an opcode, or one of the operand bytes of
	 * the opcode last fed. The instruction's effect is applied once its last operand byte
	 * arrives.
	 * @param b the byte written into the code array (only the low 8 bits are used)
	 */
	public void feed(int b) {
		int value = b & 0xFF;
		this.pc++;
		if (this.operandsExpected > 0) {
			if (this.operandCount < this.operands.length) {
				this.operands[this.operandCount] = value;
			}
			this.operandCount++;
			if (--this.operandsExpected == 0) {
				this.apply();
			}
			return;
		}
		if (this.pendingWide) {
			// The opcode a `wide` prefix widens: the same instruction with a two-byte
			// local index (four operand bytes for `iinc`, whose constant widens too).
			this.pendingWide = false;
			this.opcode = value;
			this.opcodePc = this.pc - 2;
			this.operandCount = 0;
			this.operandsExpected = value == Opcode.IINC ? 4 : 2;
			return;
		}
		if (value == Opcode.WIDE) {
			this.pendingWide = true;
			return;
		}
		this.opcode = value;
		this.opcodePc = this.pc - 1;
		this.operandCount = 0;
		this.operandsExpected = operandBytes(value);
		if (this.operandsExpected == 0) {
			this.apply();
		}
	}

	/**
	 * {@return true when the last byte fed was a load/store opcode still waiting for its
	 * one-byte local index} An emitter that is about to write an index past 255 asks
	 * this, rewrites the instruction into its {@code wide} form, and reports it with
	 * {@link #widenPendingLocalIndex()}.
	 */
	public boolean awaitingLocalIndex() {
		return !this.pendingWide && this.operandsExpected == 1 && this.operandCount == 0
				&& isOneByteLocalOp(this.opcode);
	}

	/**
	 * Accounts for the pending load/store having been rewritten into its {@code wide}
	 * form: the one opcode byte already fed became four ({@code wide}, the opcode, and a
	 * two-byte local index). The instruction's operand-stack effect is unchanged -- a
	 * load pushes and a store pops whatever slot number it names -- so only the model's
	 * position bookkeeping moves.
	 * @throws IllegalStateException when no such instruction is pending
	 */
	public void widenPendingLocalIndex() {
		if (!this.awaitingLocalIndex()) {
			throw new IllegalStateException("operand-stack model: no one-byte local index is pending at " + this.pc);
		}
		this.pc += 3;
		this.opcodePc--;
		this.operandCount = 1;
		this.operandsExpected = 0;
		this.apply();
	}

	private static boolean isOneByteLocalOp(int opcode) {
		return (opcode >= Opcode.ILOAD && opcode <= Opcode.ALOAD)
				|| (opcode >= Opcode.ISTORE && opcode <= Opcode.ASTORE);
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
	 * Accounts for a self-contained block of code appended to the method whole, rather
	 * than emitted through {@link #feed(int)} -- an assembled sequence with its own
	 * internal labels, computing over locals only. Such a block neither reads nor rejoins
	 * the operand stack it is spliced into: it leaves exactly {@code produced} behind.
	 * @param byteCount the block's length, which the model's positions must skip over
	 * @param produced what the block leaves on the operand stack
	 */
	public void appendOpaque(int byteCount, Slot... produced) {
		this.pc += byteCount;
		int headroom = this.depth() + OPAQUE_BLOCK_HEADROOM;
		if (headroom > this.maxDepth) {
			this.maxDepth = headroom;
		}
		for (Slot slot : produced) {
			this.push(slot);
		}
	}

	/**
	 * Reconciles the model with a back-patched branch: the target position is reached
	 * with the shape the branch had at its jump. Called when a forward branch emitted at
	 * {@code branchPos} is patched to {@code targetPos}, which is the position about to
	 * be emitted.
	 * @param branchPos the position of the branch instruction
	 * @param targetPos the position it jumps to
	 * @param currentPos the position about to be emitted
	 * @throws IllegalStateException when two paths reach the target with different
	 * operand stacks -- a code-generator bug that would produce a class the verifier
	 * rejects
	 */
	public void reconcile(int branchPos, int targetPos, int currentPos) {
		List<Slot> shape = this.branchShapes.get(branchPos);
		if (shape == null || targetPos != currentPos) {
			// A backward branch (a loop's back edge) jumps to code already emitted, whose
			// shape is fixed; nothing to establish here.
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
			throw new IllegalStateException("operand stack mismatch at branch target " + targetPos + ": fall-through "
					+ this.stack + " vs branch from " + branchPos + " " + shape);
		}
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

	private void apply() {
		int op = this.opcode;
		if (!this.reachable) {
			// Between an unconditional transfer and the next label the model has no
			// shape; the label (or handler entry) re-establishes one.
			return;
		}
		switch (op) {
			case Opcode.NOP, Opcode.INEG, Opcode.LNEG, Opcode.FNEG, Opcode.DNEG, Opcode.IINC, Opcode.I2B, Opcode.I2C,
					Opcode.I2S, Opcode.CHECKCAST ->
				{
				}
			case Opcode.ACONST_NULL -> this.push(Slot.REF);
			case Opcode.NEW -> this.push(Slot.UNINIT);
			case Opcode.ICONST_M1, Opcode.ICONST_0, Opcode.ICONST_1, Opcode.ICONST_2, Opcode.ICONST_3, Opcode.ICONST_4,
					Opcode.ICONST_5, Opcode.BIPUSH, Opcode.SIPUSH ->
				this.push(Slot.INT);
			case Opcode.LCONST_0, Opcode.LCONST_1 -> this.push(Slot.LONG);
			case Opcode.FCONST_0, Opcode.FCONST_1, Opcode.FCONST_2 -> this.push(Slot.FLOAT);
			case Opcode.DCONST_0, Opcode.DCONST_1 -> this.push(Slot.DOUBLE);
			case Opcode.LDC, Opcode.LDC_W, Opcode.LDC2_W -> this.push(this.constantSlot());
			case Opcode.ILOAD, Opcode.ILOAD_0, Opcode.ILOAD_1, Opcode.ILOAD_2, Opcode.ILOAD_3 -> this.push(Slot.INT);
			case Opcode.LLOAD, Opcode.LLOAD_0, Opcode.LLOAD_1, Opcode.LLOAD_2, Opcode.LLOAD_3 -> this.push(Slot.LONG);
			case Opcode.FLOAD, Opcode.FLOAD_0, Opcode.FLOAD_1, Opcode.FLOAD_2, Opcode.FLOAD_3 -> this.push(Slot.FLOAT);
			case Opcode.DLOAD, Opcode.DLOAD_0, Opcode.DLOAD_1, Opcode.DLOAD_2, Opcode.DLOAD_3 -> this.push(Slot.DOUBLE);
			case Opcode.ALOAD, Opcode.ALOAD_0, Opcode.ALOAD_1, Opcode.ALOAD_2, Opcode.ALOAD_3 -> this.push(Slot.REF);
			case Opcode.ISTORE, Opcode.ISTORE_0, Opcode.ISTORE_1, Opcode.ISTORE_2, Opcode.ISTORE_3, Opcode.LSTORE,
					Opcode.LSTORE_0, Opcode.LSTORE_1, Opcode.LSTORE_2, Opcode.LSTORE_3, Opcode.FSTORE, Opcode.FSTORE_0,
					Opcode.FSTORE_1, Opcode.FSTORE_2, Opcode.FSTORE_3, Opcode.DSTORE, Opcode.DSTORE_0, Opcode.DSTORE_1,
					Opcode.DSTORE_2, Opcode.DSTORE_3, Opcode.ASTORE, Opcode.ASTORE_0, Opcode.ASTORE_1, Opcode.ASTORE_2,
					Opcode.ASTORE_3, Opcode.POP ->
				this.pop();
			case Opcode.IALOAD, Opcode.BALOAD, Opcode.CALOAD, Opcode.SALOAD -> this.replaceArrayLoad(Slot.INT);
			case Opcode.LALOAD -> this.replaceArrayLoad(Slot.LONG);
			case Opcode.FALOAD -> this.replaceArrayLoad(Slot.FLOAT);
			case Opcode.DALOAD -> this.replaceArrayLoad(Slot.DOUBLE);
			case Opcode.AALOAD -> this.replaceArrayLoad(Slot.REF);
			case Opcode.IASTORE, Opcode.LASTORE, Opcode.FASTORE, Opcode.DASTORE, Opcode.AASTORE, Opcode.BASTORE,
					Opcode.CASTORE, Opcode.SASTORE -> {
				this.pop();
				this.pop();
				this.pop();
			}
			case Opcode.POP2 -> this.popSlots(2);
			case Opcode.DUP -> this.duplicate(1, 0);
			case Opcode.DUP_X1 -> this.duplicate(1, 1);
			case Opcode.DUP_X2 -> this.duplicate(1, 2);
			case Opcode.DUP2 -> this.duplicate(2, 0);
			case Opcode.DUP2_X1 -> this.duplicate(2, 1);
			case Opcode.DUP2_X2 -> this.duplicate(2, 2);
			case Opcode.SWAP -> {
				Slot top = this.pop();
				Slot below = this.pop();
				this.push(top);
				this.push(below);
			}
			case Opcode.IADD, Opcode.ISUB, Opcode.IMUL, Opcode.IDIV, Opcode.IREM, Opcode.IAND, Opcode.IOR, Opcode.IXOR,
					Opcode.ISHL, Opcode.ISHR, Opcode.IUSHR, Opcode.LSHL, Opcode.LSHR, Opcode.LUSHR ->
				this.pop();
			case Opcode.LADD, Opcode.LSUB, Opcode.LMUL, Opcode.LDIV, Opcode.LREM, Opcode.LAND, Opcode.LOR, Opcode.LXOR,
					Opcode.FADD, Opcode.FSUB, Opcode.FMUL, Opcode.FDIV, Opcode.FREM, Opcode.DADD, Opcode.DSUB,
					Opcode.DMUL, Opcode.DDIV, Opcode.DREM ->
				this.pop();
			case Opcode.I2L -> this.convert(Slot.LONG);
			case Opcode.I2F -> this.convert(Slot.FLOAT);
			case Opcode.I2D -> this.convert(Slot.DOUBLE);
			case Opcode.L2I -> this.convert(Slot.INT);
			case Opcode.L2F -> this.convert(Slot.FLOAT);
			case Opcode.L2D -> this.convert(Slot.DOUBLE);
			case Opcode.F2I -> this.convert(Slot.INT);
			case Opcode.F2L -> this.convert(Slot.LONG);
			case Opcode.F2D -> this.convert(Slot.DOUBLE);
			case Opcode.D2I -> this.convert(Slot.INT);
			case Opcode.D2L -> this.convert(Slot.LONG);
			case Opcode.D2F -> this.convert(Slot.FLOAT);
			case Opcode.LCMP, Opcode.FCMPL, Opcode.FCMPG, Opcode.DCMPL, Opcode.DCMPG -> {
				this.pop();
				this.pop();
				this.push(Slot.INT);
			}
			case Opcode.IFEQ, Opcode.IFNE, Opcode.IFLT, Opcode.IFGE, Opcode.IFGT, Opcode.IFLE, Opcode.IFNULL,
					Opcode.IFNONNULL -> {
				this.pop();
				this.recordBranch();
			}
			case Opcode.IF_ICMPEQ, Opcode.IF_ICMPNE, Opcode.IF_ICMPLT, Opcode.IF_ICMPGE, Opcode.IF_ICMPGT,
					Opcode.IF_ICMPLE, Opcode.IF_ACMPEQ, Opcode.IF_ACMPNE -> {
				this.pop();
				this.pop();
				this.recordBranch();
			}
			case Opcode.GOTO -> {
				this.recordBranch();
				this.reachable = false;
			}
			case Opcode.IRETURN, Opcode.LRETURN, Opcode.FRETURN, Opcode.DRETURN, Opcode.ARETURN, Opcode.ATHROW -> {
				this.pop();
				this.reachable = false;
			}
			case Opcode.RETURN -> this.reachable = false;
			case Opcode.GETSTATIC -> this.push(fieldSlot(this.descriptor()));
			case Opcode.PUTSTATIC -> this.pop();
			case Opcode.GETFIELD -> {
				this.pop();
				this.push(fieldSlot(this.descriptor()));
			}
			case Opcode.PUTFIELD -> {
				this.pop();
				this.pop();
			}
			case Opcode.INVOKEVIRTUAL, Opcode.INVOKEINTERFACE -> this.invoke(true);
			case Opcode.INVOKESPECIAL -> {
				// The constructor call that initializes what `new` allocated: every copy
				// of the receiver (the `dup` the caller left below the arguments) becomes
				// an ordinary reference.
				boolean constructing = this.invoke(true) == Slot.UNINIT;
				if (constructing) {
					this.stack.replaceAll(slot -> slot == Slot.UNINIT ? Slot.REF : slot);
				}
			}
			case Opcode.INVOKESTATIC -> this.invoke(false);
			case Opcode.NEWARRAY, Opcode.ANEWARRAY -> {
				this.pop();
				this.push(Slot.REF);
			}
			case Opcode.ARRAYLENGTH, Opcode.INSTANCEOF -> {
				this.pop();
				this.push(Slot.INT);
			}
			default -> throw new IllegalStateException(
					"operand-stack model: unsupported opcode 0x" + Integer.toHexString(op) + " at " + this.opcodePc);
		}
	}

	/**
	 * {@return the receiver the invoked method was called on, null when it is static}
	 */
	private @Nullable Slot invoke(boolean hasReceiver) {
		String descriptor = this.descriptor();
		if (!descriptor.startsWith("(")) {
			// The operand names an entry that is not a method: the emitted index is
			// corrupt (a u2 that was truncated, a wrong constant handed to the emitter),
			// and reading a field descriptor as an argument list would report the damage
			// as an operand-stack underflow instead.
			throw new IllegalStateException("operand-stack model: the invoke at " + this.opcodePc
					+ " references the constant-pool entry " + ((this.operands[0] << 8) | this.operands[1])
					+ ", whose descriptor " + descriptor + " is not a method descriptor");
		}
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

	/**
	 * {@return the descriptor of the constant-pool entry this instruction references}
	 */
	private String descriptor() {
		int index = (this.operands[0] << 8) | this.operands[1];
		String descriptor = this.cp.descriptorOf(index);
		if (descriptor == null) {
			throw new IllegalStateException("operand-stack model: no descriptor for the constant-pool entry " + index
					+ " referenced at " + this.opcodePc);
		}
		return descriptor;
	}

	private Slot constantSlot() {
		int index = this.opcode == Opcode.LDC ? this.operands[0] : (this.operands[0] << 8) | this.operands[1];
		String descriptor = this.cp.descriptorOf(index);
		if (descriptor == null) {
			throw new IllegalStateException("operand-stack model: no descriptor for the constant loaded at "
					+ this.opcodePc + " (pool index " + index + ")");
		}
		return fieldSlot(descriptor);
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
			throw new IllegalStateException("operand-stack model: underflow at " + this.opcodePc + " (opcode 0x"
					+ Integer.toHexString(this.opcode) + ")");
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

	private void recordBranch() {
		this.branchShapes.put(this.opcodePc, List.copyOf(this.stack));
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

	/**
	 * {@return the number of operand bytes the given opcode carries}
	 */
	private static int operandBytes(int opcode) {
		return switch (opcode) {
			case Opcode.BIPUSH, Opcode.LDC, Opcode.ILOAD, Opcode.LLOAD, Opcode.FLOAD, Opcode.DLOAD, Opcode.ALOAD,
					Opcode.ISTORE, Opcode.LSTORE, Opcode.FSTORE, Opcode.DSTORE, Opcode.ASTORE, Opcode.NEWARRAY ->
				1;
			case Opcode.SIPUSH, Opcode.LDC_W, Opcode.LDC2_W, Opcode.IINC, Opcode.IFEQ, Opcode.IFNE, Opcode.IFLT,
					Opcode.IFGE, Opcode.IFGT, Opcode.IFLE, Opcode.IF_ICMPEQ, Opcode.IF_ICMPNE, Opcode.IF_ICMPLT,
					Opcode.IF_ICMPGE, Opcode.IF_ICMPGT, Opcode.IF_ICMPLE, Opcode.IF_ACMPEQ, Opcode.IF_ACMPNE,
					Opcode.GOTO, Opcode.GETSTATIC, Opcode.PUTSTATIC, Opcode.GETFIELD, Opcode.PUTFIELD,
					Opcode.INVOKEVIRTUAL, Opcode.INVOKESPECIAL, Opcode.INVOKESTATIC, Opcode.NEW, Opcode.ANEWARRAY,
					Opcode.CHECKCAST, Opcode.INSTANCEOF, Opcode.IFNULL, Opcode.IFNONNULL ->
				2;
			case Opcode.MULTIANEWARRAY -> 3;
			case Opcode.INVOKEINTERFACE, Opcode.INVOKEDYNAMIC, Opcode.GOTO_W, Opcode.JSR_W -> 4;
			// `wide` never reaches here: feed() consumes the prefix and sizes the widened
			// instruction from the opcode that follows it.
			case Opcode.TABLESWITCH, Opcode.LOOKUPSWITCH, Opcode.JSR, Opcode.RET ->
				throw new IllegalStateException("operand-stack model: opcode 0x" + Integer.toHexString(opcode)
						+ " has a variable length and is not modelled");
			default -> 0;
		};
	}

}
