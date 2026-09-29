package am.ik.jvm;

import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.DoubleEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.LongEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The code of one method as it is emitted, written in the words of
 * {@code java.lang.classfile}'s {@code CodeBuilder}: typed instructions over entries of
 * the master pool ({@link ConstantPool#entries()}), labels, exception catches, and
 * {@link #size()} -- the measure every size budget reads, since a {@code CodeBuilder}
 * exposes none (.kb/jvm-method-size-limits.md).
 * <p>
 * The body is still stored as code bytes, the list the generators not yet on this layer
 * append to directly, so one body can mix the two: a compile context's layer writes into
 * the context's own list and feeds its {@link OperandStack} every byte, exactly as the
 * context's byte emitter does. {@link JvmClassSplitter} plays the bytes into a
 * {@code CodeBuilder} ({@link CodeReplay}), which writes loads, stores and small int
 * constants in their shortest forms however they were emitted, so the written class does
 * not depend on which emitter a sequence came from. Here a local is still encoded in the
 * explicit-slot form the byte emitters use ({@code aload 1}, two bytes), so a sequence
 * moved onto this layer measures what it measured before and no size budget decides
 * differently; {@link #size()} is the written size or a little over.
 * <p>
 * A branch to a label is patched when the label is bound, or at once for a bound one; one
 * whose target is past the signed 16-bit offset is recorded as a long branch and written
 * in its {@code goto_w} form. An operand index is written at full width (the high part of
 * a u2 whole), so an entry past 65535 still names itself.
 */
public final class MethodCode {

	private final List<Integer> code;

	private final @Nullable OperandStack stack;

	private final List<ClassDefinition.Branch> longBranches;

	private final List<ClassDefinition.Handler> handlers;

	/** Branches emitted to a label not bound yet. */
	private int unbound;

	/** A body of its own: its own code, no operand-stack model. */
	public MethodCode() {
		this(new ArrayList<>(), null, new ArrayList<>(), new ArrayList<>());
	}

	/**
	 * A layer over a body another emitter also writes.
	 * @param code the body's code bytes
	 * @param stack the model fed every byte appended here, or {@code null} for none
	 * @param longBranches where a branch past the 16-bit offset is recorded
	 * @param handlers the body's exception table
	 */
	public MethodCode(List<Integer> code, @Nullable OperandStack stack, List<ClassDefinition.Branch> longBranches,
			List<ClassDefinition.Handler> handlers) {
		this.code = code;
		this.stack = stack;
		this.longBranches = longBranches;
		this.handlers = handlers;
	}

	/**
	 * A position in the body a branch or a handler range names.
	 */
	public static final class Label {

		private int position = -1;

		private final List<Integer> pending = new ArrayList<>(2);

		/**
		 * @return the position the label is bound at
		 * @throws IllegalStateException when it is not bound yet
		 */
		public int position() {
			if (this.position < 0) {
				throw new IllegalStateException("label not bound");
			}
			return this.position;
		}

	}

	/**
	 * @return the byte count of the code so far: the position of the next instruction
	 */
	public int size() {
		return this.code.size();
	}

	/**
	 * @return the code bytes
	 */
	public List<Integer> code() {
		return this.code;
	}

	/**
	 * @return the exception table, in dispatch order
	 */
	public List<ClassDefinition.Handler> handlers() {
		return this.handlers;
	}

	/**
	 * @return the branches past the 16-bit offset
	 */
	public List<ClassDefinition.Branch> longBranches() {
		return this.longBranches;
	}

	/**
	 * Adds the body to a class as a method without line numbers.
	 * @param definition the class
	 * @param access the method's access flags
	 * @param name its name
	 * @param descriptor its descriptor
	 * @throws IllegalStateException when a label a branch names was never bound
	 */
	public void addTo(ClassDefinition.Builder definition, int access, ConstantPool.Utf8Constant name,
			ConstantPool.Utf8Constant descriptor) {
		this.checkComplete();
		definition.addMethod(access, name, descriptor, this.code, this.handlers, List.of(), this.longBranches);
	}

	/**
	 * Checks that no branch still waits for its label: its placeholder offset would jump
	 * to itself.
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
	 * {@code <clinit>}'s pieces are. Its handlers and long branches move with it; a label
	 * bound in it keeps the block's position and names nothing here.
	 * @param block the block, every label bound
	 * @return this
	 * @throws IllegalStateException when a branch in the block waits for its label
	 * @throws IllegalArgumentException when this body feeds an operand-stack model (the
	 * block's branches never reached it)
	 */
	public MethodCode append(MethodCode block) {
		block.checkComplete();
		if (this.stack != null) {
			throw new IllegalArgumentException("a block is appended to a plain body only");
		}
		int base = this.code.size();
		this.code.addAll(block.code);
		for (ClassDefinition.Handler h : block.handlers) {
			this.handlers.add(new ClassDefinition.Handler(h.startPc() + base, h.endPc() + base, h.handlerPc() + base,
					h.catchType()));
		}
		for (ClassDefinition.Branch b : block.longBranches) {
			this.longBranches.add(new ClassDefinition.Branch(b.pc() + base, b.target() + base));
		}
		return this;
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
	 * Binds a label at the current position, patching every branch already waiting for
	 * it.
	 * @param label the label
	 * @return this
	 * @throws IllegalStateException when the label is already bound
	 */
	public MethodCode labelBinding(Label label) {
		if (label.position >= 0) {
			throw new IllegalStateException("label already bound at " + label.position);
		}
		label.position = this.code.size();
		for (int pc : label.pending) {
			this.patch(pc, label.position);
		}
		this.unbound -= label.pending.size();
		label.pending.clear();
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
		int pc = this.code.size();
		this.u1(op.bytecode());
		this.u2(0);
		if (target.position >= 0) {
			this.patch(pc, target.position);
		}
		else {
			target.pending.add(pc);
			this.unbound++;
		}
		return this;
	}

	private void patch(int pc, int target) {
		int offset = target - pc;
		if (offset < Short.MIN_VALUE || offset > Short.MAX_VALUE) {
			// The placeholder bytes stay; the writer places the branch in its goto_w
			// form.
			this.longBranches.add(new ClassDefinition.Branch(pc, target));
		}
		else {
			this.code.set(pc + 1, (offset >> 8) & 0xFF);
			this.code.set(pc + 2, offset & 0xFF);
		}
		if (this.stack != null) {
			this.stack.reconcile(pc, target, this.code.size());
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
		this.handlers.add(new ClassDefinition.Handler(start.position(), end.position(), handler.position(),
				catchType == null ? 0 : catchType.index()));
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

	public MethodCode aconst_null() {
		return this.op(Opcode.ACONST_NULL);
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
			return this.op(Opcode.ICONST_0.bytecode() + value);
		}
		if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
			this.u1(Opcode.BIPUSH.bytecode());
			this.u1(value & 0xFF);
			return this;
		}
		if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
			this.u1(Opcode.SIPUSH.bytecode());
			this.u2(value & 0xFFFF);
			return this;
		}
		throw new IllegalArgumentException("not a short: " + value + "; load it with ldc");
	}

	public MethodCode iconst_m1() {
		return this.op(Opcode.ICONST_M1);
	}

	public MethodCode iconst_0() {
		return this.op(Opcode.ICONST_0);
	}

	public MethodCode iconst_1() {
		return this.op(Opcode.ICONST_1);
	}

	public MethodCode iconst_2() {
		return this.op(Opcode.ICONST_2);
	}

	public MethodCode iconst_3() {
		return this.op(Opcode.ICONST_3);
	}

	public MethodCode iconst_4() {
		return this.op(Opcode.ICONST_4);
	}

	public MethodCode iconst_5() {
		return this.op(Opcode.ICONST_5);
	}

	public MethodCode lconst_0() {
		return this.op(Opcode.LCONST_0);
	}

	public MethodCode lconst_1() {
		return this.op(Opcode.LCONST_1);
	}

	public MethodCode fconst_0() {
		return this.op(Opcode.FCONST_0);
	}

	public MethodCode fconst_1() {
		return this.op(Opcode.FCONST_1);
	}

	public MethodCode fconst_2() {
		return this.op(Opcode.FCONST_2);
	}

	public MethodCode dconst_0() {
		return this.op(Opcode.DCONST_0);
	}

	public MethodCode dconst_1() {
		return this.op(Opcode.DCONST_1);
	}

	/**
	 * Loads a constant: {@code ldc2_w} for a long or a double, else {@code ldc} or
	 * {@code ldc_w} by the entry's index (the writer re-decides by the index the constant
	 * takes in the class it lands in).
	 * @param entry the constant
	 * @return this
	 */
	public MethodCode ldc(LoadableConstantEntry entry) {
		int index = entry.index();
		if (entry instanceof LongEntry || entry instanceof DoubleEntry) {
			this.u1(Opcode.LDC2_W.bytecode());
			this.u2(index);
		}
		else if (index <= 0xFF) {
			this.u1(Opcode.LDC.bytecode());
			this.u1(index);
		}
		else {
			this.u1(Opcode.LDC_W.bytecode());
			this.u2(index);
		}
		return this;
	}

	// --- locals
	// -------------------------------------------------------------------------

	/**
	 * Loads a local: {@code xload}, or its {@code wide} form past slot 255 (written in
	 * the shortest form).
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
	 * Stores a local: {@code xstore}, or its {@code wide} form past slot 255 (written in
	 * the shortest form).
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
	 * Increments an int local, in the {@code wide} form when the slot or the constant
	 * needs it.
	 * @param slot the local
	 * @param value the signed 16-bit increment
	 * @return this
	 */
	public MethodCode iinc(int slot, int value) {
		if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
			throw new IllegalArgumentException("iinc constant past 16 bits: " + value);
		}
		checkSlot(slot);
		if (slot > 0xFF || value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
			this.u1(0xC4);
			this.u1(Opcode.IINC.bytecode());
			this.u2(slot);
			this.u2(value & 0xFFFF);
		}
		else {
			this.u1(Opcode.IINC.bytecode());
			this.u1(slot);
			this.u1(value & 0xFF);
		}
		return this;
	}

	// A load or store in its explicit-slot form, wide past 255: base is ILOAD..ALOAD or
	// ISTORE..ASTORE.
	private MethodCode local(Opcode base, int slot) {
		checkSlot(slot);
		int op = base.bytecode();
		if (slot <= 0xFF) {
			this.u1(op);
			this.u1(slot);
		}
		else {
			this.u1(0xC4);
			this.u1(op);
			this.u2(slot);
		}
		return this;
	}

	private static void checkSlot(int slot) {
		if (slot < 0 || slot > 0xFFFF) {
			throw new IllegalArgumentException("no local slot " + slot);
		}
	}

	// --- stack
	// --------------------------------------------------------------------------

	public MethodCode pop() {
		return this.op(Opcode.POP);
	}

	public MethodCode pop2() {
		return this.op(Opcode.POP2);
	}

	public MethodCode dup() {
		return this.op(Opcode.DUP);
	}

	public MethodCode dup_x1() {
		return this.op(Opcode.DUP_X1);
	}

	public MethodCode dup_x2() {
		return this.op(Opcode.DUP_X2);
	}

	public MethodCode dup2() {
		return this.op(Opcode.DUP2);
	}

	public MethodCode dup2_x1() {
		return this.op(Opcode.DUP2_X1);
	}

	public MethodCode swap() {
		return this.op(Opcode.SWAP);
	}

	// --- arithmetic, conversion, comparison
	// ---------------------------------------------

	public MethodCode iadd() {
		return this.op(Opcode.IADD);
	}

	public MethodCode isub() {
		return this.op(Opcode.ISUB);
	}

	public MethodCode imul() {
		return this.op(Opcode.IMUL);
	}

	public MethodCode idiv() {
		return this.op(Opcode.IDIV);
	}

	public MethodCode irem() {
		return this.op(Opcode.IREM);
	}

	public MethodCode ineg() {
		return this.op(Opcode.INEG);
	}

	public MethodCode iand() {
		return this.op(Opcode.IAND);
	}

	public MethodCode ior() {
		return this.op(Opcode.IOR);
	}

	public MethodCode ixor() {
		return this.op(Opcode.IXOR);
	}

	public MethodCode ishl() {
		return this.op(Opcode.ISHL);
	}

	public MethodCode ishr() {
		return this.op(Opcode.ISHR);
	}

	public MethodCode iushr() {
		return this.op(Opcode.IUSHR);
	}

	public MethodCode ladd() {
		return this.op(Opcode.LADD);
	}

	public MethodCode lsub() {
		return this.op(Opcode.LSUB);
	}

	public MethodCode lmul() {
		return this.op(Opcode.LMUL);
	}

	public MethodCode ldiv() {
		return this.op(Opcode.LDIV);
	}

	public MethodCode lrem() {
		return this.op(Opcode.LREM);
	}

	public MethodCode lneg() {
		return this.op(Opcode.LNEG);
	}

	public MethodCode land() {
		return this.op(Opcode.LAND);
	}

	public MethodCode lor() {
		return this.op(Opcode.LOR);
	}

	public MethodCode lxor() {
		return this.op(Opcode.LXOR);
	}

	public MethodCode lshl() {
		return this.op(Opcode.LSHL);
	}

	public MethodCode lshr() {
		return this.op(Opcode.LSHR);
	}

	public MethodCode lushr() {
		return this.op(Opcode.LUSHR);
	}

	public MethodCode dadd() {
		return this.op(Opcode.DADD);
	}

	public MethodCode dsub() {
		return this.op(Opcode.DSUB);
	}

	public MethodCode dmul() {
		return this.op(Opcode.DMUL);
	}

	public MethodCode ddiv() {
		return this.op(Opcode.DDIV);
	}

	public MethodCode drem() {
		return this.op(Opcode.DREM);
	}

	public MethodCode dneg() {
		return this.op(Opcode.DNEG);
	}

	public MethodCode fadd() {
		return this.op(Opcode.FADD);
	}

	public MethodCode fsub() {
		return this.op(Opcode.FSUB);
	}

	public MethodCode fmul() {
		return this.op(Opcode.FMUL);
	}

	public MethodCode fdiv() {
		return this.op(Opcode.FDIV);
	}

	public MethodCode fneg() {
		return this.op(Opcode.FNEG);
	}

	public MethodCode i2l() {
		return this.op(Opcode.I2L);
	}

	public MethodCode i2d() {
		return this.op(Opcode.I2D);
	}

	public MethodCode i2c() {
		return this.op(Opcode.I2C);
	}

	public MethodCode i2b() {
		return this.op(Opcode.I2B);
	}

	public MethodCode i2s() {
		return this.op(Opcode.I2S);
	}

	public MethodCode i2f() {
		return this.op(Opcode.I2F);
	}

	public MethodCode l2f() {
		return this.op(Opcode.L2F);
	}

	public MethodCode f2i() {
		return this.op(Opcode.F2I);
	}

	public MethodCode f2d() {
		return this.op(Opcode.F2D);
	}

	public MethodCode d2f() {
		return this.op(Opcode.D2F);
	}

	public MethodCode l2i() {
		return this.op(Opcode.L2I);
	}

	public MethodCode l2d() {
		return this.op(Opcode.L2D);
	}

	public MethodCode d2i() {
		return this.op(Opcode.D2I);
	}

	public MethodCode d2l() {
		return this.op(Opcode.D2L);
	}

	public MethodCode lcmp() {
		return this.op(Opcode.LCMP);
	}

	public MethodCode dcmpl() {
		return this.op(Opcode.DCMPL);
	}

	public MethodCode dcmpg() {
		return this.op(Opcode.DCMPG);
	}

	public MethodCode fcmpl() {
		return this.op(Opcode.FCMPL);
	}

	public MethodCode fcmpg() {
		return this.op(Opcode.FCMPG);
	}

	// --- fields and invocations
	// ---------------------------------------------------------

	public MethodCode getstatic(FieldRefEntry field) {
		return this.indexed(Opcode.GETSTATIC, field.index());
	}

	public MethodCode putstatic(FieldRefEntry field) {
		return this.indexed(Opcode.PUTSTATIC, field.index());
	}

	public MethodCode getfield(FieldRefEntry field) {
		return this.indexed(Opcode.GETFIELD, field.index());
	}

	public MethodCode putfield(FieldRefEntry field) {
		return this.indexed(Opcode.PUTFIELD, field.index());
	}

	/**
	 * {@code invokestatic} of a class's method, or of an interface's static one.
	 * @param method the method
	 * @return this
	 */
	public MethodCode invokestatic(MemberRefEntry method) {
		return this.indexed(Opcode.INVOKESTATIC, method.index());
	}

	public MethodCode invokevirtual(MethodRefEntry method) {
		return this.indexed(Opcode.INVOKEVIRTUAL, method.index());
	}

	/**
	 * {@code invokespecial} of a constructor, a private method or a super call.
	 * @param method the method
	 * @return this
	 */
	public MethodCode invokespecial(MemberRefEntry method) {
		return this.indexed(Opcode.INVOKESPECIAL, method.index());
	}

	/**
	 * {@code invokeinterface}, its count operand taken from the descriptor.
	 * @param method the interface method
	 * @return this
	 */
	public MethodCode invokeinterface(InterfaceMethodRefEntry method) {
		this.indexed(Opcode.INVOKEINTERFACE, method.index());
		int slots = 1;
		for (var parameter : MethodTypeDesc.ofDescriptor(method.type().stringValue()).parameterList()) {
			slots += TypeKind.from(parameter).slotSize();
		}
		this.u1(slots);
		this.u1(0);
		return this;
	}

	// --- objects and arrays
	// -------------------------------------------------------------

	public MethodCode new_(ClassEntry type) {
		return this.indexed(Opcode.NEW, type.index());
	}

	/**
	 * A primitive array.
	 * @param kind the element kind
	 * @return this
	 */
	public MethodCode newarray(TypeKind kind) {
		this.u1(Opcode.NEWARRAY.bytecode());
		this.u1(kind.newarrayCode());
		return this;
	}

	public MethodCode anewarray(ClassEntry componentType) {
		return this.indexed(Opcode.ANEWARRAY, componentType.index());
	}

	public MethodCode checkcast(ClassEntry type) {
		return this.indexed(Opcode.CHECKCAST, type.index());
	}

	public MethodCode instanceOf(ClassEntry type) {
		return this.indexed(Opcode.INSTANCEOF, type.index());
	}

	public MethodCode arraylength() {
		return this.op(Opcode.ARRAYLENGTH);
	}

	public MethodCode aaload() {
		return this.op(Opcode.AALOAD);
	}

	public MethodCode aastore() {
		return this.op(Opcode.AASTORE);
	}

	public MethodCode iaload() {
		return this.op(Opcode.IALOAD);
	}

	public MethodCode iastore() {
		return this.op(Opcode.IASTORE);
	}

	public MethodCode laload() {
		return this.op(Opcode.LALOAD);
	}

	public MethodCode lastore() {
		return this.op(Opcode.LASTORE);
	}

	public MethodCode daload() {
		return this.op(Opcode.DALOAD);
	}

	public MethodCode dastore() {
		return this.op(Opcode.DASTORE);
	}

	public MethodCode baload() {
		return this.op(Opcode.BALOAD);
	}

	public MethodCode bastore() {
		return this.op(Opcode.BASTORE);
	}

	public MethodCode caload() {
		return this.op(Opcode.CALOAD);
	}

	public MethodCode castore() {
		return this.op(Opcode.CASTORE);
	}

	/**
	 * Stores into an array of the given element kind.
	 * @param kind the element kind ({@code BOOLEAN} and {@code BYTE} share
	 * {@code bastore})
	 * @return this
	 */
	public MethodCode arrayStore(TypeKind kind) {
		return this.op(switch (kind) {
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
		return this.op(Opcode.FALOAD);
	}

	public MethodCode fastore() {
		return this.op(Opcode.FASTORE);
	}

	public MethodCode saload() {
		return this.op(Opcode.SALOAD);
	}

	public MethodCode sastore() {
		return this.op(Opcode.SASTORE);
	}

	// --- returns and throws
	// -------------------------------------------------------------

	public MethodCode areturn() {
		return this.op(Opcode.ARETURN);
	}

	public MethodCode ireturn() {
		return this.op(Opcode.IRETURN);
	}

	public MethodCode lreturn() {
		return this.op(Opcode.LRETURN);
	}

	public MethodCode dreturn() {
		return this.op(Opcode.DRETURN);
	}

	public MethodCode freturn() {
		return this.op(Opcode.FRETURN);
	}

	public MethodCode return_() {
		return this.op(Opcode.RETURN);
	}

	/**
	 * Returns a value of the given kind, or nothing for {@code VOID}.
	 * @param kind the returned kind
	 * @return this
	 */
	public MethodCode return_(TypeKind kind) {
		return this.op(switch (kind) {
			case INT, BOOLEAN, BYTE, CHAR, SHORT -> Opcode.IRETURN;
			case LONG -> Opcode.LRETURN;
			case FLOAT -> Opcode.FRETURN;
			case DOUBLE -> Opcode.DRETURN;
			case REFERENCE -> Opcode.ARETURN;
			case VOID -> Opcode.RETURN;
		});
	}

	public MethodCode athrow() {
		return this.op(Opcode.ATHROW);
	}

	public MethodCode monitorenter() {
		return this.op(Opcode.MONITORENTER);
	}

	public MethodCode monitorexit() {
		return this.op(Opcode.MONITOREXIT);
	}

	// --- encoding
	// -----------------------------------------------------------------------

	private MethodCode op(Opcode op) {
		return this.op(op.bytecode());
	}

	private MethodCode op(int bytecode) {
		this.u1(bytecode);
		return this;
	}

	private MethodCode indexed(Opcode op, int index) {
		this.u1(op.bytecode());
		this.u2(index);
		return this;
	}

	private void u1(int b) {
		this.code.add(b);
		OperandStack model = this.stack;
		if (model != null) {
			model.feed(b);
		}
	}

	// The high part whole: a master-pool index past 65535 must reach the writer, and the
	// operand-stack model, uncut.
	private void u2(int value) {
		this.u1(value >> 8);
		this.u1(value & 0xFF);
	}

	@Override
	public String toString() {
		return "MethodCode[" + this.code.size() + " bytes]";
	}

}
