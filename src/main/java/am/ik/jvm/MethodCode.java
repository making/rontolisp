package am.ik.jvm;

import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The code of one method as it is emitted, written in the words of
 * {@code java.lang.classfile}'s {@code CodeBuilder}: typed instructions over entries of
 * the master pool ({@link ConstantPool#entries()}), labels, exception catches, and
 * {@link #size()} -- the measure every size budget reads, since a {@code CodeBuilder}
 * exposes none (.kb/jvm-method-size-limits.md).
 * <p>
 * The body is a list of instruction records -- an opcode and its operand: a local slot,
 * an int, a master-pool entry or a label -- which {@link JvmClassSplitter} plays into the
 * class's {@code CodeBuilder} once the split has decided the class ({@link CodeReplay}).
 * A position is an instruction's index: a label is bound before the instruction at its
 * position, a handler range and a line entry name positions.
 * <p>
 * {@link #size()} is the written size: a local's load or store, an {@code iinc} and an
 * int constant in their shortest forms (as {@code CodeBuilder} writes them), an
 * {@code ldc} by its constant's master index (the writer re-decides the width by the
 * index the constant takes in the class it lands in), and a branch this layer knows to be
 * past the signed 16-bit offset in its long form ({@code goto_w}, or an inverted short
 * branch over one). A far branch is found where its offset is known -- at its label's
 * binding, or at once for a bound label; the writer relaxes the method exactly
 * ({@link CodeReplay}).
 * <p>
 * A body over a compile context feeds the context's {@link OperandStack} every
 * instruction, and reconciles the model where a forward branch's label is bound.
 */
public final class MethodCode {

	private static final @Nullable Opcode[] OPCODES = new Opcode[256];

	static {
		for (Opcode op : Opcode.values()) {
			if (!op.isWide()) {
				OPCODES[op.bytecode()] = op;
			}
		}
	}

	private static final int INITIAL_CAPACITY = 16;

	/** Per instruction, its opcode: a local's load or store in its explicit-slot form. */
	private byte[] ops = new byte[INITIAL_CAPACITY];

	/**
	 * Per instruction, its operand: a local's slot, an int constant, a {@code newarray}
	 * type code, an {@code iinc}'s slot (low half) and increment (high half).
	 */
	private int[] args = new int[INITIAL_CAPACITY];

	/** Per instruction, its master-pool entry, or the label a branch names. */
	private @Nullable Object[] refs = new Object[INITIAL_CAPACITY];

	private int count;

	private int size;

	private final @Nullable OperandStack stack;

	private final List<Handler> handlers = new ArrayList<>();

	/** Branches emitted to a label not bound yet. */
	private int unbound;

	/** A body of its own, no operand-stack model. */
	public MethodCode() {
		this.stack = null;
	}

	/**
	 * A compile context's body, whose every instruction feeds the context's model.
	 * @param stack the model
	 */
	public MethodCode(OperandStack stack) {
		this.stack = stack;
	}

	/**
	 * A position in the body a branch or a handler range names.
	 */
	public static final class Label {

		/** The index of the instruction the label is bound before, -1 until bound. */
		private int position = -1;

		/** The byte offset it is bound at, in the measure of {@link #size()}. */
		private int offset;

		/** The branches waiting for the binding: their index and offset, in pairs. */
		private int @Nullable [] waiting;

		private int waitingCount;

		/**
		 * @return the position the label is bound at: the index of the instruction that
		 * follows it
		 * @throws IllegalStateException when it is not bound yet
		 */
		public int position() {
			if (this.position < 0) {
				throw new IllegalStateException("label not bound");
			}
			return this.position;
		}

		private void await(int branch, int offset) {
			int[] pairs = this.waiting;
			if (pairs == null) {
				pairs = new int[4];
			}
			else if (this.waitingCount == pairs.length) {
				pairs = Arrays.copyOf(pairs, pairs.length * 2);
			}
			pairs[this.waitingCount++] = branch;
			pairs[this.waitingCount++] = offset;
			this.waiting = pairs;
		}

	}

	/**
	 * An exception table entry. An exception thrown by an instruction in
	 * {@code [start, end)} is dispatched to the instruction at {@code handler} when its
	 * class is (a subclass of) {@code catchType}; a null {@code catchType} catches any
	 * throwable (the {@code finally} shape).
	 *
	 * @param start the position of the first instruction covered
	 * @param end the position past the last one covered
	 * @param handler the position of the handler's entry (the operand stack there holds
	 * only the thrown exception)
	 * @param catchType the class caught, or {@code null} for any
	 */
	public record Handler(int start, int end, int handler, @Nullable ClassEntry catchType) {
	}

	/**
	 * @return the byte count of the code so far, as written (see the class comment)
	 */
	public int size() {
		return this.size;
	}

	/**
	 * @return the position the next instruction takes: the count of instructions so far
	 */
	public int position() {
		return this.count;
	}

	/**
	 * @return the exception table, in dispatch order
	 */
	public List<Handler> handlers() {
		return this.handlers;
	}

	/**
	 * Checks that no branch still waits for its label.
	 * @throws IllegalStateException when one does
	 */
	public void checkComplete() {
		if (this.unbound != 0) {
			throw new IllegalStateException(this.unbound + " branch(es) to a label never bound");
		}
	}

	/**
	 * Appends a body assembled apart: a block whose branches all land inside it, or a
	 * fragment whose entries had to be minted before this body reaches it, as
	 * {@code <clinit>}'s pieces are. Its instructions and handlers move with it, every
	 * label its branches name rebased to where the block lands; a label bound in it names
	 * nothing here.
	 * @param block the block, every label bound
	 * @return this
	 * @throws IllegalStateException when a branch in the block waits for its label
	 * @throws IllegalArgumentException when this body feeds an operand-stack model (the
	 * block's instructions never reached it)
	 */
	public MethodCode append(MethodCode block) {
		block.checkComplete();
		if (this.stack != null) {
			throw new IllegalArgumentException("a block is appended to a plain body only");
		}
		int base = this.count;
		int baseOffset = this.size;
		this.reserve(base + block.count);
		System.arraycopy(block.ops, 0, this.ops, base, block.count);
		System.arraycopy(block.args, 0, this.args, base, block.count);
		Map<Label, Label> rebased = new IdentityHashMap<>();
		for (int i = 0; i < block.count; i++) {
			Object ref = block.refs[i];
			if (ref instanceof Label label) {
				ref = rebased.computeIfAbsent(label, l -> {
					Label moved = new Label();
					moved.position = base + l.position;
					moved.offset = baseOffset + l.offset;
					return moved;
				});
			}
			this.refs[base + i] = ref;
		}
		this.count = base + block.count;
		this.size += block.size;
		for (Handler h : block.handlers) {
			this.handlers.add(new Handler(h.start() + base, h.end() + base, h.handler() + base, h.catchType()));
		}
		return this;
	}

	// --- the records, for the writer and the splitter's scan
	// ----------------------------

	/**
	 * @return the number of instructions
	 */
	int count() {
		return this.count;
	}

	/**
	 * @param i an instruction's position
	 * @return its opcode; a local's load or store in its explicit-slot form
	 */
	Opcode opcode(int i) {
		return opcodeOf(this.ops[i]);
	}

	/**
	 * @param i an instruction's position
	 * @return its operand: a local's slot, an int constant, a {@code newarray} type code,
	 * or an {@code iinc}'s slot in the low half and its increment in the high half
	 */
	int operand(int i) {
		return this.args[i];
	}

	/**
	 * @param i an instruction's position
	 * @return its master-pool entry, or {@code null} when it names none
	 */
	@Nullable PoolEntry entry(int i) {
		return this.refs[i] instanceof PoolEntry entry ? entry : null;
	}

	/**
	 * @param i a branch's position
	 * @return the position it jumps to
	 */
	int target(int i) {
		return ((Label) java.util.Objects.requireNonNull(this.refs[i])).position;
	}

	/**
	 * @param i an instruction's position
	 * @return the bytes it takes, in the measure of {@link #size()} (a branch in its
	 * short form)
	 */
	int length(int i) {
		return length(this.ops[i] & 0xFF, this.args[i], this.refs[i]);
	}

	private static Opcode opcodeOf(byte op) {
		return java.util.Objects.requireNonNull(OPCODES[op & 0xFF]);
	}

	/** The bytes an instruction takes as written; a branch in its short form. */
	private static int length(int op, int arg, @Nullable Object ref) {
		return switch (op) {
			// iload..aload, istore..astore: the shortest form for the slot
			case 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A -> arg <= 3 ? 1 : arg <= 0xFF ? 2 : 4;
			// iinc: wide when the slot or the increment does not fit a byte
			case 0x84 -> (arg & 0xFFFF) <= 0xFF && (arg >> 16) == (byte) (arg >> 16) ? 3 : 6;
			// ldc by its master index
			case 0x12 -> ((PoolEntry) java.util.Objects.requireNonNull(ref)).index() <= 0xFF ? 2 : 3;
			// bipush, newarray
			case 0x10, 0xBC -> 2;
			// invokeinterface
			case 0xB9 -> 5;
			// sipush, ldc2_w, the branches, the field accesses, the invocations, new,
			// anewarray, checkcast, instanceof
			case 0x11, 0x14, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
					0xC6, 0xC7, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1 ->
				3;
			default -> 1;
		};
	}

	private void reserve(int capacity) {
		if (capacity > this.ops.length) {
			int grown = Math.max(capacity, this.ops.length * 2);
			this.ops = Arrays.copyOf(this.ops, grown);
			this.args = Arrays.copyOf(this.args, grown);
			this.refs = Arrays.copyOf(this.refs, grown);
		}
	}

	private int add(Opcode op, int arg, @Nullable Object ref) {
		int i = this.count;
		if (i == this.ops.length) {
			this.reserve(i + 1);
		}
		int bytecode = op.bytecode();
		this.ops[i] = (byte) bytecode;
		this.args[i] = arg;
		this.refs[i] = ref;
		this.count = i + 1;
		this.size += length(bytecode, arg, ref);
		return i;
	}

	private MethodCode emit(Opcode op, int arg, @Nullable PoolEntry entry) {
		int i = this.add(op, arg, entry);
		OperandStack model = this.stack;
		if (model != null) {
			model.instruction(i, op, entry);
		}
		return this;
	}

	private MethodCode emit(Opcode op) {
		return this.emit(op, 0, null);
	}

	// --- labels, branches, handlers ---------------------------------------------------

	/**
	 * @return a new, unbound label
	 */
	public Label newLabel() {
		return new Label();
	}

	/**
	 * @return a new label bound at the current position
	 */
	public Label newBoundLabel() {
		Label label = new Label();
		this.labelBinding(label);
		return label;
	}

	/**
	 * Binds a label at the current position, where every branch already waiting for it
	 * lands.
	 * @param label the label
	 * @return this
	 * @throws IllegalStateException when the label is already bound
	 */
	public MethodCode labelBinding(Label label) {
		if (label.position >= 0) {
			throw new IllegalStateException("label already bound at " + label.position);
		}
		label.position = this.count;
		label.offset = this.size;
		int[] waiting = label.waiting;
		if (waiting != null) {
			OperandStack model = this.stack;
			for (int k = 0; k < label.waitingCount; k += 2) {
				int branch = waiting[k];
				this.reach(opcodeOf(this.ops[branch]), waiting[k + 1], label.offset);
				if (model != null) {
					model.reconcile(branch);
				}
			}
			this.unbound -= label.waitingCount / 2;
			label.waiting = null;
			label.waitingCount = 0;
		}
		return this;
	}

	/**
	 * A branch to a label, bound or not.
	 * @param op a conditional branch or {@code goto}
	 * @param target where it jumps
	 * @return this
	 */
	public MethodCode branch(Opcode op, Label target) {
		if (op.kind() != Opcode.Kind.BRANCH || op.sizeIfFixed() != 3) {
			throw new IllegalArgumentException(op + " is not a short branch");
		}
		int offset = this.size;
		int i = this.add(op, 0, target);
		OperandStack model = this.stack;
		if (model != null) {
			model.branch(i, op);
		}
		if (target.position >= 0) {
			this.reach(op, offset, target.offset);
		}
		else {
			target.await(i, offset);
			this.unbound++;
		}
		return this;
	}

	/**
	 * Counts the long form of a branch whose offset does not fit 16 bits: a
	 * {@code goto_w} for a {@code goto} (2 bytes more), an inverted short branch over one
	 * for a conditional branch (5 more).
	 */
	private void reach(Opcode op, int from, int to) {
		int offset = to - from;
		if (offset < Short.MIN_VALUE || offset > Short.MAX_VALUE) {
			this.size += op == Opcode.GOTO ? 2 : 5;
		}
	}

	/**
	 * Adds an exception table entry after every one already there (dispatch order).
	 * @param start the first instruction covered
	 * @param end the first instruction past the range
	 * @param handler the handler's entry
	 * @param catchType the class caught, or {@code null} for any throwable
	 * @return this
	 * @throws IllegalStateException when a label is not bound yet
	 */
	public MethodCode exceptionCatch(Label start, Label end, Label handler, @Nullable ClassEntry catchType) {
		this.handlers.add(new Handler(start.position(), end.position(), handler.position(), catchType));
		return this;
	}

	public MethodCode ifeq(Label target) {
		return this.branch(Opcode.IFEQ, target);
	}

	public MethodCode ifne(Label target) {
		return this.branch(Opcode.IFNE, target);
	}

	public MethodCode iflt(Label target) {
		return this.branch(Opcode.IFLT, target);
	}

	public MethodCode ifge(Label target) {
		return this.branch(Opcode.IFGE, target);
	}

	public MethodCode ifgt(Label target) {
		return this.branch(Opcode.IFGT, target);
	}

	public MethodCode ifle(Label target) {
		return this.branch(Opcode.IFLE, target);
	}

	public MethodCode if_icmpeq(Label target) {
		return this.branch(Opcode.IF_ICMPEQ, target);
	}

	public MethodCode if_icmpne(Label target) {
		return this.branch(Opcode.IF_ICMPNE, target);
	}

	public MethodCode if_icmplt(Label target) {
		return this.branch(Opcode.IF_ICMPLT, target);
	}

	public MethodCode if_icmpge(Label target) {
		return this.branch(Opcode.IF_ICMPGE, target);
	}

	public MethodCode if_icmpgt(Label target) {
		return this.branch(Opcode.IF_ICMPGT, target);
	}

	public MethodCode if_icmple(Label target) {
		return this.branch(Opcode.IF_ICMPLE, target);
	}

	public MethodCode if_acmpeq(Label target) {
		return this.branch(Opcode.IF_ACMPEQ, target);
	}

	public MethodCode if_acmpne(Label target) {
		return this.branch(Opcode.IF_ACMPNE, target);
	}

	public MethodCode ifnull(Label target) {
		return this.branch(Opcode.IFNULL, target);
	}

	public MethodCode ifnonnull(Label target) {
		return this.branch(Opcode.IFNONNULL, target);
	}

	public MethodCode goto_(Label target) {
		return this.branch(Opcode.GOTO, target);
	}

	// --- constants
	// ----------------------------------------------------------------------

	public MethodCode nop() {
		return this.emit(Opcode.NOP);
	}

	public MethodCode aconst_null() {
		return this.emit(Opcode.ACONST_NULL);
	}

	/**
	 * Pushes an int in the shortest form: {@code iconst_<n>}, {@code bipush} or
	 * {@code sipush}.
	 * @param value the constant, in the short range (a wider one is an {@link #ldc} of an
	 * Integer entry)
	 * @return this
	 */
	public MethodCode loadConstant(int value) {
		if (value >= -1 && value <= 5) {
			return this.emit(opcodeOf((byte) (Opcode.ICONST_0.bytecode() + value)));
		}
		if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
			return this.emit(Opcode.BIPUSH, value, null);
		}
		if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
			return this.emit(Opcode.SIPUSH, value, null);
		}
		throw new IllegalArgumentException("not a short: " + value + "; load it with ldc");
	}

	public MethodCode iconst_m1() {
		return this.emit(Opcode.ICONST_M1);
	}

	public MethodCode iconst_0() {
		return this.emit(Opcode.ICONST_0);
	}

	public MethodCode iconst_1() {
		return this.emit(Opcode.ICONST_1);
	}

	public MethodCode iconst_2() {
		return this.emit(Opcode.ICONST_2);
	}

	public MethodCode iconst_3() {
		return this.emit(Opcode.ICONST_3);
	}

	public MethodCode iconst_4() {
		return this.emit(Opcode.ICONST_4);
	}

	public MethodCode iconst_5() {
		return this.emit(Opcode.ICONST_5);
	}

	public MethodCode lconst_0() {
		return this.emit(Opcode.LCONST_0);
	}

	public MethodCode lconst_1() {
		return this.emit(Opcode.LCONST_1);
	}

	public MethodCode fconst_0() {
		return this.emit(Opcode.FCONST_0);
	}

	public MethodCode fconst_1() {
		return this.emit(Opcode.FCONST_1);
	}

	public MethodCode fconst_2() {
		return this.emit(Opcode.FCONST_2);
	}

	public MethodCode dconst_0() {
		return this.emit(Opcode.DCONST_0);
	}

	public MethodCode dconst_1() {
		return this.emit(Opcode.DCONST_1);
	}

	/**
	 * Loads a constant: {@code ldc2_w} for a long or a double, else {@code ldc} --
	 * written {@code ldc_w} when the constant's index in the class it lands in needs it.
	 * @param entry the constant
	 * @return this
	 */
	public MethodCode ldc(LoadableConstantEntry entry) {
		return this.emit(entry.width() == 2 ? Opcode.LDC2_W : Opcode.LDC, 0, entry);
	}

	// --- locals
	// -------------------------------------------------------------------------

	/**
	 * Loads a local: {@code xload}, written in the shortest form for the slot.
	 * @param kind the local's kind
	 * @param slot its slot
	 * @return this
	 */
	public MethodCode loadLocal(TypeKind kind, int slot) {
		return this.local(switch (kind) {
			case INT, BOOLEAN, BYTE, CHAR, SHORT -> Opcode.ILOAD;
			case LONG -> Opcode.LLOAD;
			case FLOAT -> Opcode.FLOAD;
			case DOUBLE -> Opcode.DLOAD;
			case REFERENCE -> Opcode.ALOAD;
			case VOID -> throw new IllegalArgumentException("no void local");
		}, slot);
	}

	/**
	 * Stores a local: {@code xstore}, written in the shortest form for the slot.
	 * @param kind the local's kind
	 * @param slot its slot
	 * @return this
	 */
	public MethodCode storeLocal(TypeKind kind, int slot) {
		return this.local(switch (kind) {
			case INT, BOOLEAN, BYTE, CHAR, SHORT -> Opcode.ISTORE;
			case LONG -> Opcode.LSTORE;
			case FLOAT -> Opcode.FSTORE;
			case DOUBLE -> Opcode.DSTORE;
			case REFERENCE -> Opcode.ASTORE;
			case VOID -> throw new IllegalArgumentException("no void local");
		}, slot);
	}

	public MethodCode aload(int slot) {
		return this.local(Opcode.ALOAD, slot);
	}

	public MethodCode astore(int slot) {
		return this.local(Opcode.ASTORE, slot);
	}

	public MethodCode iload(int slot) {
		return this.local(Opcode.ILOAD, slot);
	}

	public MethodCode istore(int slot) {
		return this.local(Opcode.ISTORE, slot);
	}

	public MethodCode lload(int slot) {
		return this.local(Opcode.LLOAD, slot);
	}

	public MethodCode lstore(int slot) {
		return this.local(Opcode.LSTORE, slot);
	}

	public MethodCode fload(int slot) {
		return this.local(Opcode.FLOAD, slot);
	}

	public MethodCode fstore(int slot) {
		return this.local(Opcode.FSTORE, slot);
	}

	public MethodCode dload(int slot) {
		return this.local(Opcode.DLOAD, slot);
	}

	public MethodCode dstore(int slot) {
		return this.local(Opcode.DSTORE, slot);
	}

	/**
	 * Increments an int local, written in the {@code wide} form when the slot or the
	 * constant needs it.
	 * @param slot the local
	 * @param value the signed 16-bit increment
	 * @return this
	 */
	public MethodCode iinc(int slot, int value) {
		if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
			throw new IllegalArgumentException("iinc constant past 16 bits: " + value);
		}
		checkSlot(slot);
		return this.emit(Opcode.IINC, value << 16 | slot, null);
	}

	// A load or store: base is ILOAD..ALOAD or ISTORE..ASTORE.
	private MethodCode local(Opcode base, int slot) {
		checkSlot(slot);
		return this.emit(base, slot, null);
	}

	private static void checkSlot(int slot) {
		if (slot < 0 || slot > 0xFFFF) {
			throw new IllegalArgumentException("no local slot " + slot);
		}
	}

	// --- stack
	// --------------------------------------------------------------------------

	public MethodCode pop() {
		return this.emit(Opcode.POP);
	}

	public MethodCode pop2() {
		return this.emit(Opcode.POP2);
	}

	public MethodCode dup() {
		return this.emit(Opcode.DUP);
	}

	public MethodCode dup_x1() {
		return this.emit(Opcode.DUP_X1);
	}

	public MethodCode dup_x2() {
		return this.emit(Opcode.DUP_X2);
	}

	public MethodCode dup2() {
		return this.emit(Opcode.DUP2);
	}

	public MethodCode dup2_x1() {
		return this.emit(Opcode.DUP2_X1);
	}

	public MethodCode swap() {
		return this.emit(Opcode.SWAP);
	}

	// --- arithmetic, conversion, comparison
	// ---------------------------------------------

	public MethodCode iadd() {
		return this.emit(Opcode.IADD);
	}

	public MethodCode isub() {
		return this.emit(Opcode.ISUB);
	}

	public MethodCode imul() {
		return this.emit(Opcode.IMUL);
	}

	public MethodCode idiv() {
		return this.emit(Opcode.IDIV);
	}

	public MethodCode irem() {
		return this.emit(Opcode.IREM);
	}

	public MethodCode ineg() {
		return this.emit(Opcode.INEG);
	}

	public MethodCode iand() {
		return this.emit(Opcode.IAND);
	}

	public MethodCode ior() {
		return this.emit(Opcode.IOR);
	}

	public MethodCode ixor() {
		return this.emit(Opcode.IXOR);
	}

	public MethodCode ishl() {
		return this.emit(Opcode.ISHL);
	}

	public MethodCode ishr() {
		return this.emit(Opcode.ISHR);
	}

	public MethodCode iushr() {
		return this.emit(Opcode.IUSHR);
	}

	public MethodCode ladd() {
		return this.emit(Opcode.LADD);
	}

	public MethodCode lsub() {
		return this.emit(Opcode.LSUB);
	}

	public MethodCode lmul() {
		return this.emit(Opcode.LMUL);
	}

	public MethodCode ldiv() {
		return this.emit(Opcode.LDIV);
	}

	public MethodCode lrem() {
		return this.emit(Opcode.LREM);
	}

	public MethodCode lneg() {
		return this.emit(Opcode.LNEG);
	}

	public MethodCode land() {
		return this.emit(Opcode.LAND);
	}

	public MethodCode lor() {
		return this.emit(Opcode.LOR);
	}

	public MethodCode lxor() {
		return this.emit(Opcode.LXOR);
	}

	public MethodCode lshl() {
		return this.emit(Opcode.LSHL);
	}

	public MethodCode lshr() {
		return this.emit(Opcode.LSHR);
	}

	public MethodCode lushr() {
		return this.emit(Opcode.LUSHR);
	}

	public MethodCode dadd() {
		return this.emit(Opcode.DADD);
	}

	public MethodCode dsub() {
		return this.emit(Opcode.DSUB);
	}

	public MethodCode dmul() {
		return this.emit(Opcode.DMUL);
	}

	public MethodCode ddiv() {
		return this.emit(Opcode.DDIV);
	}

	public MethodCode drem() {
		return this.emit(Opcode.DREM);
	}

	public MethodCode dneg() {
		return this.emit(Opcode.DNEG);
	}

	public MethodCode fadd() {
		return this.emit(Opcode.FADD);
	}

	public MethodCode fsub() {
		return this.emit(Opcode.FSUB);
	}

	public MethodCode fmul() {
		return this.emit(Opcode.FMUL);
	}

	public MethodCode fdiv() {
		return this.emit(Opcode.FDIV);
	}

	public MethodCode fneg() {
		return this.emit(Opcode.FNEG);
	}

	public MethodCode i2l() {
		return this.emit(Opcode.I2L);
	}

	public MethodCode i2d() {
		return this.emit(Opcode.I2D);
	}

	public MethodCode i2c() {
		return this.emit(Opcode.I2C);
	}

	public MethodCode i2b() {
		return this.emit(Opcode.I2B);
	}

	public MethodCode i2s() {
		return this.emit(Opcode.I2S);
	}

	public MethodCode i2f() {
		return this.emit(Opcode.I2F);
	}

	public MethodCode l2f() {
		return this.emit(Opcode.L2F);
	}

	public MethodCode f2i() {
		return this.emit(Opcode.F2I);
	}

	public MethodCode f2d() {
		return this.emit(Opcode.F2D);
	}

	public MethodCode d2f() {
		return this.emit(Opcode.D2F);
	}

	public MethodCode l2i() {
		return this.emit(Opcode.L2I);
	}

	public MethodCode l2d() {
		return this.emit(Opcode.L2D);
	}

	public MethodCode d2i() {
		return this.emit(Opcode.D2I);
	}

	public MethodCode d2l() {
		return this.emit(Opcode.D2L);
	}

	public MethodCode lcmp() {
		return this.emit(Opcode.LCMP);
	}

	public MethodCode dcmpl() {
		return this.emit(Opcode.DCMPL);
	}

	public MethodCode dcmpg() {
		return this.emit(Opcode.DCMPG);
	}

	public MethodCode fcmpl() {
		return this.emit(Opcode.FCMPL);
	}

	public MethodCode fcmpg() {
		return this.emit(Opcode.FCMPG);
	}

	// --- fields and invocations
	// ---------------------------------------------------------

	public MethodCode getstatic(FieldRefEntry field) {
		return this.emit(Opcode.GETSTATIC, 0, field);
	}

	public MethodCode putstatic(FieldRefEntry field) {
		return this.emit(Opcode.PUTSTATIC, 0, field);
	}

	public MethodCode getfield(FieldRefEntry field) {
		return this.emit(Opcode.GETFIELD, 0, field);
	}

	public MethodCode putfield(FieldRefEntry field) {
		return this.emit(Opcode.PUTFIELD, 0, field);
	}

	/**
	 * {@code invokestatic} of a class's method, or of an interface's static one.
	 * @param method the method
	 * @return this
	 */
	public MethodCode invokestatic(MemberRefEntry method) {
		return this.emit(Opcode.INVOKESTATIC, 0, method);
	}

	public MethodCode invokevirtual(MethodRefEntry method) {
		return this.emit(Opcode.INVOKEVIRTUAL, 0, method);
	}

	/**
	 * {@code invokespecial} of a constructor, a private method or a super call.
	 * @param method the method
	 * @return this
	 */
	public MethodCode invokespecial(MemberRefEntry method) {
		return this.emit(Opcode.INVOKESPECIAL, 0, method);
	}

	/**
	 * {@code invokeinterface}; the writer derives its count operand from the descriptor.
	 * @param method the interface method
	 * @return this
	 */
	public MethodCode invokeinterface(InterfaceMethodRefEntry method) {
		return this.emit(Opcode.INVOKEINTERFACE, 0, method);
	}

	// --- objects and arrays
	// -------------------------------------------------------------

	public MethodCode new_(ClassEntry type) {
		return this.emit(Opcode.NEW, 0, type);
	}

	/**
	 * A primitive array.
	 * @param kind the element kind
	 * @return this
	 */
	public MethodCode newarray(TypeKind kind) {
		return this.emit(Opcode.NEWARRAY, kind.newarrayCode(), null);
	}

	public MethodCode anewarray(ClassEntry componentType) {
		return this.emit(Opcode.ANEWARRAY, 0, componentType);
	}

	public MethodCode checkcast(ClassEntry type) {
		return this.emit(Opcode.CHECKCAST, 0, type);
	}

	public MethodCode instanceOf(ClassEntry type) {
		return this.emit(Opcode.INSTANCEOF, 0, type);
	}

	public MethodCode arraylength() {
		return this.emit(Opcode.ARRAYLENGTH);
	}

	public MethodCode aaload() {
		return this.emit(Opcode.AALOAD);
	}

	public MethodCode aastore() {
		return this.emit(Opcode.AASTORE);
	}

	public MethodCode iaload() {
		return this.emit(Opcode.IALOAD);
	}

	public MethodCode iastore() {
		return this.emit(Opcode.IASTORE);
	}

	public MethodCode laload() {
		return this.emit(Opcode.LALOAD);
	}

	public MethodCode lastore() {
		return this.emit(Opcode.LASTORE);
	}

	public MethodCode daload() {
		return this.emit(Opcode.DALOAD);
	}

	public MethodCode dastore() {
		return this.emit(Opcode.DASTORE);
	}

	public MethodCode baload() {
		return this.emit(Opcode.BALOAD);
	}

	public MethodCode bastore() {
		return this.emit(Opcode.BASTORE);
	}

	public MethodCode caload() {
		return this.emit(Opcode.CALOAD);
	}

	public MethodCode castore() {
		return this.emit(Opcode.CASTORE);
	}

	/**
	 * Stores into an array of the given element kind.
	 * @param kind the element kind ({@code BOOLEAN} and {@code BYTE} share
	 * {@code bastore})
	 * @return this
	 */
	public MethodCode arrayStore(TypeKind kind) {
		return this.emit(switch (kind) {
			case BOOLEAN, BYTE -> Opcode.BASTORE;
			case CHAR -> Opcode.CASTORE;
			case SHORT -> Opcode.SASTORE;
			case INT -> Opcode.IASTORE;
			case LONG -> Opcode.LASTORE;
			case FLOAT -> Opcode.FASTORE;
			case DOUBLE -> Opcode.DASTORE;
			case REFERENCE -> Opcode.AASTORE;
			case VOID -> throw new IllegalArgumentException("no void array");
		});
	}

	public MethodCode faload() {
		return this.emit(Opcode.FALOAD);
	}

	public MethodCode fastore() {
		return this.emit(Opcode.FASTORE);
	}

	public MethodCode saload() {
		return this.emit(Opcode.SALOAD);
	}

	public MethodCode sastore() {
		return this.emit(Opcode.SASTORE);
	}

	// --- returns and throws
	// -------------------------------------------------------------

	public MethodCode areturn() {
		return this.emit(Opcode.ARETURN);
	}

	public MethodCode ireturn() {
		return this.emit(Opcode.IRETURN);
	}

	public MethodCode lreturn() {
		return this.emit(Opcode.LRETURN);
	}

	public MethodCode dreturn() {
		return this.emit(Opcode.DRETURN);
	}

	public MethodCode freturn() {
		return this.emit(Opcode.FRETURN);
	}

	public MethodCode return_() {
		return this.emit(Opcode.RETURN);
	}

	/**
	 * Returns a value of the given kind, or nothing for {@code VOID}.
	 * @param kind the returned kind
	 * @return this
	 */
	public MethodCode return_(TypeKind kind) {
		return this.emit(switch (kind) {
			case INT, BOOLEAN, BYTE, CHAR, SHORT -> Opcode.IRETURN;
			case LONG -> Opcode.LRETURN;
			case FLOAT -> Opcode.FRETURN;
			case DOUBLE -> Opcode.DRETURN;
			case REFERENCE -> Opcode.ARETURN;
			case VOID -> Opcode.RETURN;
		});
	}

	public MethodCode athrow() {
		return this.emit(Opcode.ATHROW);
	}

	public MethodCode monitorenter() {
		return this.emit(Opcode.MONITORENTER);
	}

	public MethodCode monitorexit() {
		return this.emit(Opcode.MONITOREXIT);
	}

	@Override
	public String toString() {
		return "MethodCode[" + this.count + " instructions, " + this.size + " bytes]";
	}

}
