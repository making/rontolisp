package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;

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
record JvmMvChannel(FieldRefEntry field, JvmMvChannel.@Nullable PerThread perThread) {

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
	record PerThread(Utf8Constant threadLocalName, Utf8Constant threadLocalDesc, FieldRefEntry threadLocal,
			Utf8Constant ownerName, Utf8Constant ownerDesc, FieldRefEntry owner, Utf8Constant getName,
			Utf8Constant getDesc, MethodRefEntry get, Utf8Constant setName, Utf8Constant setDesc, MethodRefEntry set,
			MethodRefEntry currentThread, MethodRefEntry tlGet, MethodRefEntry tlSet) {

		/** {@code _mvGet}: the owner's static field, or this thread's ThreadLocal. */
		MethodCode getCode(FieldRefEntry field) {
			MethodCode code = new MethodCode();
			code.invokestatic(this.currentThread);
			code.getstatic(this.owner);
			MethodCode.Label branch = code.newLabel();
			code.if_acmpne(branch);
			code.getstatic(field);
			code.areturn();
			code.labelBinding(branch);
			code.getstatic(this.threadLocal);
			code.invokevirtual(this.tlGet);
			code.areturn();
			return code;
		}

		/** {@code _mvSet(v)}: the owner's static field, or this thread's ThreadLocal. */
		MethodCode setCode(FieldRefEntry field) {
			MethodCode code = new MethodCode();
			code.invokestatic(this.currentThread);
			code.getstatic(this.owner);
			MethodCode.Label branch = code.newLabel();
			code.if_acmpne(branch);
			code.aload(0);
			code.putstatic(field);
			code.return_();
			code.labelBinding(branch);
			code.getstatic(this.threadLocal);
			code.aload(0);
			code.invokevirtual(this.tlSet);
			code.return_();
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
	static JvmMvChannel perThread(ConstantPool cp, ClassEntry thisClass, FieldRefEntry field) {
		ClassEntry threadLocalClass = cp.classEntry("java/lang/ThreadLocal");
		ClassEntry threadClass = cp.classEntry("java/lang/Thread");
		Utf8Constant tlName = cp.addUtf8("_mvTl");
		Utf8Constant tlDesc = cp.addUtf8("Ljava/lang/ThreadLocal;");
		Utf8Constant ownerName = cp.addUtf8("_mvOwner");
		Utf8Constant ownerDesc = cp.addUtf8("Ljava/lang/Thread;");
		Utf8Constant getName = cp.addUtf8("_mvGet");
		Utf8Constant getDesc = cp.addUtf8("()Ljava/lang/Object;");
		Utf8Constant setName = cp.addUtf8("_mvSet");
		Utf8Constant setDesc = cp.addUtf8("(Ljava/lang/Object;)V");
		return new JvmMvChannel(field,
				new PerThread(tlName, tlDesc, cp.fieldRef(thisClass, tlName.entry(), tlDesc.entry()), ownerName,
						ownerDesc, cp.fieldRef(thisClass, ownerName.entry(), ownerDesc.entry()), getName, getDesc,
						cp.methodRef(thisClass, getName.entry(), getDesc.entry()), setName, setDesc,
						cp.methodRef(thisClass, setName.entry(), setDesc.entry()),
						cp.methodRef(threadClass, "currentThread", "()Ljava/lang/Thread;"),
						cp.methodRef(threadLocalClass, "get", "()Ljava/lang/Object;"),
						cp.methodRef(threadLocalClass, "set", "(Ljava/lang/Object;)V")));
	}

	/**
	 * Pushes the calling thread's channel value.
	 * @param code the body
	 */
	void emitLoad(MethodCode code) {
		if (this.perThread == null) {
			code.getstatic(this.field);
			return;
		}
		code.invokestatic(this.perThread.get());
	}

	/**
	 * Pops the value on the stack into the calling thread's channel.
	 * @param code the body
	 */
	void emitStore(MethodCode code) {
		if (this.perThread == null) {
			code.putstatic(this.field);
			return;
		}
		code.invokestatic(this.perThread.set());
	}

	/** {@link #emitLoad(MethodCode)} into a compile context's body. */
	void emitLoad(JvmLispCompiler.Ctx ctx) {
		emitLoad(ctx.body);
	}

	/** {@link #emitStore(MethodCode)} into a compile context's body. */
	void emitStore(JvmLispCompiler.Ctx ctx) {
		emitStore(ctx.body);
	}

	/** Clears the calling thread's channel: the value just produced is one value. */
	void emitClear(JvmLispCompiler.Ctx ctx) {
		ctx.body.aconst_null();
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
		ctx.body.invokestatic(this.perThread.currentThread());
		ctx.body.putstatic(this.perThread.owner());
	}

}
