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
		this.code.add(Opcode.ALOAD);
		this.code.add(slot);
	}

	void astore(int slot) {
		this.code.add(Opcode.ASTORE);
		this.code.add(slot);
	}

	void iload(int slot) {
		this.code.add(Opcode.ILOAD);
		this.code.add(slot);
	}

	void istore(int slot) {
		this.code.add(Opcode.ISTORE);
		this.code.add(slot);
	}

	void iinc(int slot, int delta) {
		this.code.add(Opcode.IINC);
		this.code.add(slot);
		this.code.add(delta & 0xFF);
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
