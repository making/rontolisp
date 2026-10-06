package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;

import am.ik.jvm.ConstantPool;
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
 * {@code make-thread}, a served request, a host calling a {@code jvm-export} or a
 * callback in -- {@code JvmLispCompiler}'s {@code lispOnOtherThreads}) keeps one register
 * per THREAD, as a native implementation does: through the one static field a thread's
 * tail cleared or overwrote the values another had just published, and a consumer read
 * the wrong count -- an async body's values most visibly, captured where the body
 * completes while its caller and its siblings run on. One thread, the OWNER, keeps its
 * register in a slot of {@code _mvBox}, behind one {@code currentThread} compare in the
 * {@code _mvGet}/{@code _mvSet} helpers; every other thread's register is a
 * {@link ThreadLocal}. The owner is the fast path because a hot loop runs there: a
 * {@code ThreadLocal.set} on every call of {@code fib} doubled its time.
 *
 * <p>
 * The owner's register is the middle slot of an array of its own rather than a static
 * field: every other thread reads {@code _mvOwner} on every access, and a static field
 * shares the class's cache lines with it, so the owner's writes to a register there made
 * those reads miss -- the cost of one shared register, back ({@code .kb/multiple-values.md},
 * "One register per thread"). The slots around the register fill the cache-line pair it
 * sits in, so nothing else lives there.
 *
 * <p>
 * The owner is the first thread to enter the class through {@code main}'s prologue or a
 * jvm-export wrapper, claimed ONCE under the class's lock ({@code _mvClaim}) and never
 * moved: a claim that moved would strand the values the old owner had just published in
 * the box, its consumer then reading its ThreadLocal. So a library's host thread owns the
 * box as a program's {@code main} thread does, and a {@code main} run after a
 * host already called an export leaves the owner where it is.
 *
 * @param field the {@code _g$} static field of the {@code %mv-spill} global, the channel
 * of a single-threaded program
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
	 * @param boxName {@code _mvBox}
	 * @param boxDesc {@code [Ljava/lang/Object;}
	 * @param box the array whose middle slot is the owner's register
	 * @param objectClass {@code java/lang/Object}, the box's component type
	 * @param claimName {@code _mvClaim}
	 * @param claimDesc {@code ()V}
	 * @param claim makes the calling thread the owner unless one is claimed
	 * @param currentThread {@code Thread.currentThread()}
	 * @param tlGet {@code ThreadLocal.get()}
	 * @param tlSet {@code ThreadLocal.set(Object)}
	 */
	record PerThread(Utf8Entry threadLocalName, Utf8Entry threadLocalDesc, FieldRefEntry threadLocal,
			Utf8Entry ownerName, Utf8Entry ownerDesc, FieldRefEntry owner, Utf8Entry getName, Utf8Entry getDesc,
			MethodRefEntry get, Utf8Entry setName, Utf8Entry setDesc, MethodRefEntry set, Utf8Entry boxName,
			Utf8Entry boxDesc, FieldRefEntry box, ClassEntry objectClass, Utf8Entry claimName, Utf8Entry claimDesc,
			MethodRefEntry claim, MethodRefEntry currentThread, MethodRefEntry tlGet, MethodRefEntry tlSet) {

		/**
		 * {@code _mvBox}'s length: two 64-byte lines of 4-byte references, so the line
		 * pair holding the middle slot lies inside the array.
		 */
		static final int BOX_LENGTH = 64;

		/** The owner's register in {@code _mvBox}. */
		static final int BOX_SLOT = BOX_LENGTH / 2;

		/** {@code _mvGet}: the owner's register, or this thread's ThreadLocal. */
		MethodCode getCode() {
			MethodCode code = new MethodCode();
			code.invokestatic(this.currentThread);
			code.getstatic(this.owner);
			MethodCode.Label branch = code.newLabel();
			code.if_acmpne(branch);
			code.getstatic(this.box);
			code.loadConstant(BOX_SLOT);
			code.aaload();
			code.areturn();
			code.labelBinding(branch);
			code.getstatic(this.threadLocal);
			code.invokevirtual(this.tlGet);
			code.areturn();
			return code;
		}

		/** {@code _mvSet(v)}: the owner's register, or this thread's ThreadLocal. */
		MethodCode setCode() {
			MethodCode code = new MethodCode();
			code.invokestatic(this.currentThread);
			code.getstatic(this.owner);
			MethodCode.Label branch = code.newLabel();
			code.if_acmpne(branch);
			code.getstatic(this.box);
			code.loadConstant(BOX_SLOT);
			code.aload(0);
			code.aastore();
			code.return_();
			code.labelBinding(branch);
			code.getstatic(this.threadLocal);
			code.aload(0);
			code.invokevirtual(this.tlSet);
			code.return_();
			return code;
		}

		/**
		 * {@code <clinit>}'s part: {@code _mvBox = new Object[BOX_LENGTH]}, before any
		 * Lisp code runs.
		 * @param clinit the class initializer's body
		 */
		void emitInit(MethodCode clinit) {
			clinit.loadConstant(BOX_LENGTH);
			clinit.anewarray(this.objectClass);
			clinit.putstatic(this.box);
		}

		/**
		 * {@code _mvClaim}, a {@code synchronized} static method: the calling thread
		 * becomes the owner unless a thread already is. The lock makes the check and the
		 * store one step, so two first callers cannot both claim.
		 */
		MethodCode claimCode() {
			MethodCode code = new MethodCode();
			code.getstatic(this.owner);
			MethodCode.Label claimed = code.newLabel();
			code.ifnonnull(claimed);
			code.invokestatic(this.currentThread);
			code.putstatic(this.owner);
			code.labelBinding(claimed);
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
		Utf8Entry tlName = cp.utf8Entry("_mvTl");
		Utf8Entry tlDesc = cp.utf8Entry("Ljava/lang/ThreadLocal;");
		Utf8Entry ownerName = cp.utf8Entry("_mvOwner");
		Utf8Entry ownerDesc = cp.utf8Entry("Ljava/lang/Thread;");
		Utf8Entry getName = cp.utf8Entry("_mvGet");
		Utf8Entry getDesc = cp.utf8Entry("()Ljava/lang/Object;");
		Utf8Entry setName = cp.utf8Entry("_mvSet");
		Utf8Entry setDesc = cp.utf8Entry("(Ljava/lang/Object;)V");
		Utf8Entry boxName = cp.utf8Entry("_mvBox");
		Utf8Entry boxDesc = cp.utf8Entry("[Ljava/lang/Object;");
		Utf8Entry claimName = cp.utf8Entry("_mvClaim");
		Utf8Entry claimDesc = cp.utf8Entry("()V");
		return new JvmMvChannel(field, new PerThread(tlName, tlDesc, cp.fieldRef(thisClass, tlName, tlDesc), ownerName,
				ownerDesc, cp.fieldRef(thisClass, ownerName, ownerDesc), getName, getDesc,
				cp.methodRef(thisClass, getName, getDesc), setName, setDesc, cp.methodRef(thisClass, setName, setDesc),
				boxName, boxDesc, cp.fieldRef(thisClass, boxName, boxDesc), cp.classEntry("java/lang/Object"), claimName,
				claimDesc, cp.methodRef(thisClass, claimName, claimDesc),
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
	 * Makes the calling thread the owner unless a thread already is -- {@code main}'s
	 * prologue and every jvm-export wrapper's, before any Lisp code runs on the thread.
	 * Once claimed, the check is one {@code getstatic} and a branch; the claim itself
	 * takes the class's lock ({@code _mvClaim}). A servlet's request runs on a fresh
	 * virtual thread, so its handler claims nothing: the first request's thread would
	 * keep the field after it ended.
	 * @param code the entry's body
	 */
	void emitClaimOwner(MethodCode code) {
		if (this.perThread == null) {
			return;
		}
		code.getstatic(this.perThread.owner());
		MethodCode.Label claimed = code.newLabel();
		code.ifnonnull(claimed);
		code.invokestatic(this.perThread.claim());
		code.labelBinding(claimed);
	}

}
