package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

import org.jspecify.annotations.Nullable;

/**
 * Where a compiled class keeps the {@code %mv-spill} channel
 * ({@code .kb/multiple-values.md}) -- the value-count register every function tail writes
 * -- and how code reaches it.
 *
 * <p>
 * A single-threaded program keeps it in its {@code _g$} static field. A program that runs
 * Lisp code on more than one thread (an async body's virtual thread, a
 * {@code make-thread}, a served request) keeps one register per THREAD, as a native
 * implementation does: through the one static field a thread's tail cleared or overwrote
 * the values another had just published, and a consumer read the wrong count -- an async
 * body's values most visibly, captured where the body completes while its caller and its
 * siblings run on. The thread that runs {@code main} (the OWNER, claimed in
 * {@code main}'s prologue) keeps the static field, behind one {@code currentThread}
 * compare in the {@code _mvGet}/{@code _mvSet} helpers; every other thread's register is
 * a {@link ThreadLocal}. The owner is the fast path because a hot loop runs there: a
 * {@code ThreadLocal.set} on every call of {@code fib} doubled its time.
 *
 * @param field the {@code _g$} static field of the {@code %mv-spill} global
 * @param perThread the per-thread store, or null in a single-threaded program
 */
record JvmMvChannel(FieldrefConstant field, JvmMvChannel.@Nullable PerThread perThread) {

	/**
	 * The per-thread store's members.
	 *
	 * @param threadLocalName {@code _mvTl}, the other threads' registers
	 * @param threadLocalDesc {@code Ljava/lang/ThreadLocal;}
	 * @param threadLocal the {@code _mvTl} field
	 * @param ownerName {@code _mvOwner}, the thread that keeps the static field
	 * @param ownerDesc {@code Ljava/lang/Thread;}
	 * @param owner the {@code _mvOwner} field
	 * @param getName {@code _mvGet}
	 * @param getDesc {@code ()Ljava/lang/Object;}
	 * @param get the calling thread's register, read
	 * @param setName {@code _mvSet}
	 * @param setDesc {@code (Ljava/lang/Object;)V}
	 * @param set the calling thread's register, written
	 * @param currentThread {@code Thread.currentThread()}
	 * @param tlGet {@code ThreadLocal.get()}
	 * @param tlSet {@code ThreadLocal.set(Object)}
	 */
	record PerThread(Utf8Constant threadLocalName, Utf8Constant threadLocalDesc, FieldrefConstant threadLocal,
			Utf8Constant ownerName, Utf8Constant ownerDesc, FieldrefConstant owner, Utf8Constant getName,
			Utf8Constant getDesc, MethodrefConstant get, Utf8Constant setName, Utf8Constant setDesc,
			MethodrefConstant set, MethodrefConstant currentThread, MethodrefConstant tlGet, MethodrefConstant tlSet) {

		/** {@code _mvGet}: the owner's static field, or this thread's ThreadLocal. */
		List<Integer> getCode(FieldrefConstant field) {
			List<Integer> code = new ArrayList<>();
			code.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(code, this.currentThread.index());
			code.add(Opcode.GETSTATIC);
			JvmRuntimeBuilder.emitU2(code, this.owner.index());
			int branch = code.size();
			code.add(Opcode.IF_ACMPNE);
			JvmRuntimeBuilder.emitU2(code, 0);
			code.add(Opcode.GETSTATIC);
			JvmRuntimeBuilder.emitU2(code, field.index());
			code.add(Opcode.ARETURN);
			JvmRuntimeBuilder.patchBranch(code, branch, code.size());
			code.add(Opcode.GETSTATIC);
			JvmRuntimeBuilder.emitU2(code, this.threadLocal.index());
			code.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(code, this.tlGet.index());
			code.add(Opcode.ARETURN);
			return code;
		}

		/** {@code _mvSet(v)}: the owner's static field, or this thread's ThreadLocal. */
		List<Integer> setCode(FieldrefConstant field) {
			List<Integer> code = new ArrayList<>();
			code.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(code, this.currentThread.index());
			code.add(Opcode.GETSTATIC);
			JvmRuntimeBuilder.emitU2(code, this.owner.index());
			int branch = code.size();
			code.add(Opcode.IF_ACMPNE);
			JvmRuntimeBuilder.emitU2(code, 0);
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.PUTSTATIC);
			JvmRuntimeBuilder.emitU2(code, field.index());
			code.add(Opcode.RETURN);
			JvmRuntimeBuilder.patchBranch(code, branch, code.size());
			code.add(Opcode.GETSTATIC);
			JvmRuntimeBuilder.emitU2(code, this.threadLocal.index());
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(code, this.tlSet.index());
			code.add(Opcode.RETURN);
			return code;
		}

	}

	/**
	 * The channel of a program that runs Lisp code on more than one thread.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param field the {@code _g$} static field of the {@code %mv-spill} global
	 * @return the per-thread channel
	 */
	static JvmMvChannel perThread(ConstantPool cp, ClassConstant thisClass, FieldrefConstant field) {
		ClassConstant threadLocalClass = cp.addClass(cp.addUtf8("java/lang/ThreadLocal"));
		ClassConstant threadClass = cp.addClass(cp.addUtf8("java/lang/Thread"));
		Utf8Constant tlName = cp.addUtf8("_mvTl");
		Utf8Constant tlDesc = cp.addUtf8("Ljava/lang/ThreadLocal;");
		Utf8Constant ownerName = cp.addUtf8("_mvOwner");
		Utf8Constant ownerDesc = cp.addUtf8("Ljava/lang/Thread;");
		Utf8Constant getName = cp.addUtf8("_mvGet");
		Utf8Constant getDesc = cp.addUtf8("()Ljava/lang/Object;");
		Utf8Constant setName = cp.addUtf8("_mvSet");
		Utf8Constant setDesc = cp.addUtf8("(Ljava/lang/Object;)V");
		return new JvmMvChannel(field,
				new PerThread(tlName, tlDesc, cp.addFieldref(thisClass, cp.addNameAndType(tlName, tlDesc)), ownerName,
						ownerDesc, cp.addFieldref(thisClass, cp.addNameAndType(ownerName, ownerDesc)), getName, getDesc,
						cp.addMethodref(thisClass, cp.addNameAndType(getName, getDesc)), setName, setDesc,
						cp.addMethodref(thisClass, cp.addNameAndType(setName, setDesc)),
						cp.addMethodref(threadClass,
								cp.addNameAndType(cp.addUtf8("currentThread"), cp.addUtf8("()Ljava/lang/Thread;"))),
						cp.addMethodref(threadLocalClass,
								cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("()Ljava/lang/Object;"))),
						cp.addMethodref(threadLocalClass,
								cp.addNameAndType(cp.addUtf8("set"), cp.addUtf8("(Ljava/lang/Object;)V")))));
	}

	/**
	 * Pushes the calling thread's channel value.
	 * @param op emits an opcode
	 * @param u2 emits a two-byte operand
	 */
	void emitLoad(IntConsumer op, IntConsumer u2) {
		if (this.perThread == null) {
			op.accept(Opcode.GETSTATIC);
			u2.accept(this.field.index());
			return;
		}
		op.accept(Opcode.INVOKESTATIC);
		u2.accept(this.perThread.get().index());
	}

	/**
	 * Pops the value on the stack into the calling thread's channel.
	 * @param op emits an opcode
	 * @param u2 emits a two-byte operand
	 */
	void emitStore(IntConsumer op, IntConsumer u2) {
		if (this.perThread == null) {
			op.accept(Opcode.PUTSTATIC);
			u2.accept(this.field.index());
			return;
		}
		op.accept(Opcode.INVOKESTATIC);
		u2.accept(this.perThread.set().index());
	}

	/** {@link #emitLoad(IntConsumer, IntConsumer)} into a method body. */
	void emitLoad(JvmLispCompiler.Ctx ctx) {
		emitLoad(ctx::emit, ctx::emitU2);
	}

	/** {@link #emitStore(IntConsumer, IntConsumer)} into a method body. */
	void emitStore(JvmLispCompiler.Ctx ctx) {
		emitStore(ctx::emit, ctx::emitU2);
	}

	/** Clears the calling thread's channel: the value just produced is one value. */
	void emitClear(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.ACONST_NULL);
		emitStore(ctx);
	}

	/**
	 * Makes the calling thread the owner -- {@code main}'s prologue, before any Lisp code
	 * runs on it. A class whose {@code main} never runs (a jvm-export library, a servlet)
	 * keeps no owner, and every thread takes its ThreadLocal.
	 * @param ctx {@code main}'s context
	 */
	void emitClaimOwner(JvmLispCompiler.Ctx ctx) {
		if (this.perThread == null) {
			return;
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(this.perThread.currentThread().index());
		ctx.emit(Opcode.PUTSTATIC);
		ctx.emitU2(this.perThread.owner().index());
	}

}
