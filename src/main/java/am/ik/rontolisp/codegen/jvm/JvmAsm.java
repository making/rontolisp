package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.StringConstant;
import am.ik.jvm.Opcode;

/**
 * A minimal one-pass JVM bytecode assembler with symbolic labels. Branch instructions
 * reference labels; forward references are back-patched when the label is bound. All
 * branch offsets are 16-bit signed, which is sufficient because each generated method
 * body is far smaller than 32 KB.
 *
 * <p>
 * This is a shared, top-level extraction of the same assembler used internally by
 * {@link JvmEvalRuntimeBuilder}; it is reused by {@link JvmReadRuntimeBuilder} to emit
 * the runtime reader.
 */
final class JvmAsm {

	/**
	 * The highest local slot the one-byte operand of a load/store can name; past it the
	 * instruction takes a {@code wide} prefix and a two-byte index.
	 */
	private static final int MAX_ONE_BYTE_LOCAL_SLOT = 255;

	final List<Integer> code = new ArrayList<>();

	private final Map<Integer, Integer> labelPos = new HashMap<>();

	private final Map<Integer, List<Integer>> pending = new HashMap<>();

	private int nextLabel = 0;

	int label() {
		return this.nextLabel++;
	}

	void bind(int label) {
		int pos = this.code.size();
		this.labelPos.put(label, pos);
		List<Integer> ps = this.pending.remove(label);
		if (ps != null) {
			for (int bp : ps) {
				JvmRuntimeBuilder.patchBranch(this.code, bp, pos);
			}
		}
	}

	void branch(int opcode, int label) {
		int bp = this.code.size();
		this.code.add(opcode);
		JvmRuntimeBuilder.emitU2(this.code, 0);
		Integer tgt = this.labelPos.get(label);
		if (tgt != null) {
			JvmRuntimeBuilder.patchBranch(this.code, bp, tgt);
		}
		else {
			this.pending.computeIfAbsent(label, k -> new ArrayList<>()).add(bp);
		}
	}

	void op(int opcode) {
		this.code.add(opcode);
	}

	void u2(int value) {
		JvmRuntimeBuilder.emitU2(this.code, value);
	}

	void aload(int slot) {
		this.localOp(Opcode.ALOAD, slot);
	}

	void astore(int slot) {
		this.localOp(Opcode.ASTORE, slot);
	}

	void iload(int slot) {
		this.localOp(Opcode.ILOAD, slot);
	}

	void istore(int slot) {
		this.localOp(Opcode.ISTORE, slot);
	}

	void iinc(int slot, int delta) {
		if (slot > MAX_ONE_BYTE_LOCAL_SLOT) {
			this.code.add(Opcode.WIDE);
			this.code.add(Opcode.IINC);
			JvmRuntimeBuilder.emitU2(this.code, slot);
			JvmRuntimeBuilder.emitU2(this.code, delta);
			return;
		}
		this.code.add(Opcode.IINC);
		this.code.add(slot);
		this.code.add(delta & 0xFF);
	}

	/**
	 * Emits a load or store of a local slot, in the {@code wide} form past slot 255. The
	 * blocks assembled here are spliced into a Ctx-compiled body whole
	 * ({@code Ctx.emitBlock}), so their slots come from {@code Ctx.allocTemp} and grow
	 * with the enclosing method: a one-byte operand would silently name a different slot
	 * exactly as it did before {@code Ctx.emit} learned to widen.
	 */
	private void localOp(int opcode, int slot) {
		if (slot > MAX_ONE_BYTE_LOCAL_SLOT) {
			this.code.add(Opcode.WIDE);
			this.code.add(opcode);
			JvmRuntimeBuilder.emitU2(this.code, slot);
			return;
		}
		this.code.add(opcode);
		this.code.add(slot);
	}

	void aconstNull() {
		this.code.add(Opcode.ACONST_NULL);
	}

	void iconst(int n) {
		if (n == -1) {
			this.code.add(Opcode.ICONST_M1);
		}
		else if (n >= 0 && n <= 5) {
			this.code.add(Opcode.ICONST_0 + n);
		}
		else if (n >= -128 && n <= 127) {
			this.code.add(Opcode.BIPUSH);
			this.code.add(n & 0xFF);
		}
		else {
			this.code.add(Opcode.SIPUSH);
			JvmRuntimeBuilder.emitU2(this.code, n);
		}
	}

	void dup() {
		this.code.add(Opcode.DUP);
	}

	void pop() {
		this.code.add(Opcode.POP);
	}

	void areturn() {
		this.code.add(Opcode.ARETURN);
	}

	void ireturn() {
		this.code.add(Opcode.IRETURN);
	}

	void op0(int opcode) {
		this.code.add(opcode);
	}

	void aaload() {
		this.code.add(Opcode.AALOAD);
	}

	void aastore() {
		this.code.add(Opcode.AASTORE);
	}

	void arraylength() {
		this.code.add(Opcode.ARRAYLENGTH);
	}

	void checkcast(ClassConstant c) {
		this.code.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(this.code, c.index());
	}

	void instanceOf(ClassConstant c) {
		this.code.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(this.code, c.index());
	}

	void anewarray(ClassConstant c) {
		this.code.add(Opcode.ANEWARRAY);
		JvmRuntimeBuilder.emitU2(this.code, c.index());
	}

	void anew(ClassConstant c) {
		this.code.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(this.code, c.index());
	}

	void ldcString(StringConstant sc) {
		if (sc.index() <= 255) {
			this.code.add(Opcode.LDC);
			this.code.add(sc.index());
		}
		else {
			this.code.add(Opcode.LDC_W);
			JvmRuntimeBuilder.emitU2(this.code, sc.index());
		}
	}

	void invokestatic(MethodrefConstant m) {
		this.code.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(this.code, m.index());
	}

	void invokevirtual(MethodrefConstant m) {
		this.code.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(this.code, m.index());
	}

	void invokespecial(MethodrefConstant m) {
		this.code.add(Opcode.INVOKESPECIAL);
		JvmRuntimeBuilder.emitU2(this.code, m.index());
	}

	void getstatic(FieldrefConstant f) {
		this.code.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(this.code, f.index());
	}

	// --- int[] support (used by the char runtime -- a CHARACTER is int[]{codePoint}) ---

	/** The {@code newarray int} instruction (atype 10 = {@code T_INT}). */
	void newarrayInt() {
		this.code.add(Opcode.NEWARRAY);
		this.code.add(10);
	}

	void iaload() {
		this.code.add(Opcode.IALOAD);
	}

	void iastore() {
		this.code.add(Opcode.IASTORE);
	}

	// --- double / double[] support (used by the packed float-array runtime) ---

	/** The {@code newarray double} instruction (atype 7 = {@code T_DOUBLE}). */
	void newarrayDouble() {
		this.code.add(Opcode.NEWARRAY);
		this.code.add(7);
	}

	void daload() {
		this.code.add(Opcode.DALOAD);
	}

	void dastore() {
		this.code.add(Opcode.DASTORE);
	}

	void dload(int slot) {
		this.localOp(Opcode.DLOAD, slot);
	}

	void dstore(int slot) {
		this.localOp(Opcode.DSTORE, slot);
	}

	void dreturn() {
		this.code.add(Opcode.DRETURN);
	}

	void dup2() {
		this.code.add(Opcode.DUP2);
	}

	void i2d() {
		this.code.add(Opcode.I2D);
	}

	void d2i() {
		this.code.add(Opcode.D2I);
	}

	// --- long / long[] support (used by the packed integer-vector runtime) ---

	/** The {@code newarray long} instruction (atype 11 = {@code T_LONG}). */
	void newarrayLong() {
		this.code.add(Opcode.NEWARRAY);
		this.code.add(11);
	}

	void laload() {
		this.code.add(Opcode.LALOAD);
	}

	void lastore() {
		this.code.add(Opcode.LASTORE);
	}

	void lload(int slot) {
		this.localOp(Opcode.LLOAD, slot);
	}

	void lstore(int slot) {
		this.localOp(Opcode.LSTORE, slot);
	}

	void l2i() {
		this.code.add(Opcode.L2I);
	}

	void i2l() {
		this.code.add(Opcode.I2L);
	}

	// --- float / float[] support (used by the packed single-float array runtime) ---

	/** The {@code newarray float} instruction (atype 6 = {@code T_FLOAT}). */
	void newarrayFloat() {
		this.code.add(Opcode.NEWARRAY);
		this.code.add(6);
	}

	void faload() {
		this.code.add(Opcode.FALOAD);
	}

	void fastore() {
		this.code.add(Opcode.FASTORE);
	}

	void f2d() {
		this.code.add(Opcode.F2D);
	}

	void d2f() {
		this.code.add(Opcode.D2F);
	}

	void f2i() {
		this.code.add(Opcode.F2I);
	}

	void i2f() {
		this.code.add(Opcode.I2F);
	}

	void l2d() {
		this.code.add(Opcode.L2D);
	}

	void dadd() {
		this.code.add(Opcode.DADD);
	}

	void dsub() {
		this.code.add(Opcode.DSUB);
	}

	void dmul() {
		this.code.add(Opcode.DMUL);
	}

	void dcmpl() {
		this.code.add(Opcode.DCMPL);
	}

	void dcmpg() {
		this.code.add(Opcode.DCMPG);
	}

	/** Pushes a wide double constant via {@code ldc2_w}. */
	void ldc2Double(am.ik.jvm.ConstantPool.DoubleConstant dc) {
		this.code.add(Opcode.LDC2_W);
		JvmRuntimeBuilder.emitU2(this.code, dc.index());
	}

	/** Pushes a wide long constant via {@code ldc2_w}. */
	void ldc2Long(am.ik.jvm.ConstantPool.LongConstant lc) {
		this.code.add(Opcode.LDC2_W);
		JvmRuntimeBuilder.emitU2(this.code, lc.index());
	}

	void putstatic(FieldrefConstant f) {
		this.code.add(Opcode.PUTSTATIC);
		JvmRuntimeBuilder.emitU2(this.code, f.index());
	}

	List<Integer> finish() {
		if (!this.pending.isEmpty()) {
			throw new IllegalStateException("Unbound labels in runtime assembly: " + this.pending.keySet());
		}
		return this.code;
	}

}
