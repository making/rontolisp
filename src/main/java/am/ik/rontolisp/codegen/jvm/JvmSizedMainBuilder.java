package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

import org.jspecify.annotations.Nullable;

/**
 * Builds the launcher that runs a compiled program's {@code main} on a thread of a size
 * the PROGRAM chose, not the launcher's {@code -Xss} or the platform's first-thread
 * default (1 MiB on linux-x64) -- the compiled twin of {@code RontoLispCli.main}'s worker
 * ({@code .kb/interpreter-stack.md}).
 *
 * <p>
 * The program body that used to BE {@code main} is emitted unchanged as the private
 * static {@code _main$body(String[])}. The new {@code main}:
 *
 * <pre>
 * Prog r = new Prog(); r._main$args = args;
 * Thread t = new Thread(null, r, "main",
 *         (long) Integer.getInteger("rontolisp.stack", 16) &lt;&lt; 20);
 * t.start();
 * join t, retrying on an interrupt and re-asserting it afterwards;
 * if (r._main$thrown != null) throw r._main$thrown;
 * </pre>
 *
 * The class itself is the {@code Runnable} -- no second class file has to travel -- and
 * its {@code run()} calls {@code _main$run(this)}, which runs the body and keeps what it
 * threw. The throwable is rethrown on thread 0, so the report the uncaught-condition
 * handler printed, the {@code Exception in thread "main"} echo and the exit code are what
 * they were; a reflective caller of {@code main} still sees the throw. A program that
 * also carries the async runtime already HAS a {@code run()}: that one takes a prefix
 * instead ({@link JvmAsyncRuntimeBuilder#build}), telling the launcher instance by its
 * null latch.
 *
 * <p>
 * The size is a system property rather than {@code -Xss}, which no longer reaches the
 * program: {@code -Drontolisp.stack=<MiB>}. Zero or less hands the choice back to the JVM
 * ({@code -Xss}); an unparsable value is the default, which is
 * {@code Integer.getInteger}'s contract.
 *
 * <p>
 * <b>A program that reaches {@code objc:} also hands thread 0 over</b> -- the compiled
 * twin of the other half of {@code RontoLispCli.main} ({@code .kb/objc.md}, "AppKit
 * belongs to thread 0"). In a native image on macOS {@code main} IS thread 0, which
 * AppKit needs draining, so when the program's own copy of
 * {@code MainThread.handOverRequired()} says so, {@code main} starts the worker marked
 * {@code _main$exit} and parks in {@code MainThread.get().runLoop()}, which never
 * returns:
 *
 * <pre>
 * if (Prog$ObjcMainThread.handOverRequired()) {
 *     r._main$exit = true; t.start();
 *     Prog$ObjcMainThread.get().runLoop(); return;
 * }
 * </pre>
 *
 * so the worker ends the process itself: {@code System.exit(0)} after the body, or --
 * after dispatching what it threw to the thread's uncaught-exception handler, which
 * prints the same {@code Exception in thread "main"} echo the JVM would --
 * {@code System.exit(1)}. Everywhere else (a {@code java} launcher already parks thread
 * 0, or this is not macOS) the answer is false before anything native is bound, and the
 * launcher is the plain one above.
 */
final class JvmSizedMainBuilder {

	/** The static the old {@code main} body is emitted as, unchanged. */
	static final String BODY_METHOD = "_main$body";

	/** {@code _main$run(Prog)}: the body on the worker, its throwable kept. */
	static final String RUN_METHOD = "_main$run";

	static final String ARGS_FIELD = "_main$args";

	static final String THROWN_FIELD = "_main$thrown";

	/** Set on the launcher instance whose worker must end the process itself. */
	static final String EXIT_FIELD = "_main$exit";

	/** The stack property, in MiB. */
	static final String STACK_PROPERTY = "rontolisp.stack";

	/** The default size in MiB: the interpreter's worker, {@code WORKER_STACK_BYTES}. */
	static final int DEFAULT_STACK_MIB = 16;

	/** A method body ready to emit. */
	record Method(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code,
			List<int[]> exceptionTable) {
	}

	/**
	 * The launcher's pieces.
	 *
	 * @param bodyName the name the old {@code main} body is emitted under
	 * @param main the new public static {@code main}
	 * @param run the private static {@code _main$run(Prog)}
	 * @param runRef a reference to {@code run}, for the instance {@code run()}
	 * @param argsName the instance field holding {@code main}'s arguments
	 * @param argsDesc its descriptor
	 * @param thrownName the instance field holding what the body threw
	 * @param thrownDesc its descriptor
	 * @param runnableClass {@code java/lang/Runnable}
	 * @param exitName the instance field marking a worker that ends the process, or
	 * {@code null} when the launcher never hands thread 0 over
	 * @param exitDesc its descriptor, or {@code null} with it
	 */
	record SizedMain(Utf8Constant bodyName, Method main, Method run, MethodrefConstant runRef, Utf8Constant argsName,
			Utf8Constant argsDesc, Utf8Constant thrownName, Utf8Constant thrownDesc, ClassConstant runnableClass,
			@Nullable Utf8Constant exitName, @Nullable Utf8Constant exitDesc) {

		/**
		 * The instance {@code run()} of a class with no other {@code Runnable} use:
		 * {@code _main$run(this)}.
		 * @param cp the constant pool
		 * @return the method
		 */
		Method instanceRun(ConstantPool cp) {
			JvmAsyncRuntimeBuilder.Asm a = new JvmAsyncRuntimeBuilder.Asm();
			a.aload(0);
			a.op(Opcode.INVOKESTATIC);
			a.u2(this.runRef.index());
			a.op(Opcode.RETURN);
			return new Method(cp.addUtf8("run"), cp.addUtf8("()V"), 1, 1, a.finish(), List.of());
		}

	}

	private JvmSizedMainBuilder() {
	}

	/**
	 * Builds the launcher. Every constant it needs is minted here, so the caller must
	 * call it before the constant pool is serialized.
	 * @param cp the constant pool
	 * @param thisClass the generated class
	 * @param className its internal name
	 * @param instanceInitRef the generated class's no-arg constructor
	 * @param mainDesc {@code ([Ljava/lang/String;)V}
	 * @param mainThreadClass the internal name of the program's own copy of
	 * {@code am.ik.objc.MainThread} when the program reaches {@code objc:}, else
	 * {@code null} -- and then no constant or instruction of the hand-over exists
	 * @return the launcher's pieces
	 */
	static SizedMain build(ConstantPool cp, ClassConstant thisClass, String className,
			MethodrefConstant instanceInitRef, Utf8Constant mainDesc, @Nullable String mainThreadClass) {
		Utf8Constant bodyName = cp.addUtf8(BODY_METHOD);
		MethodrefConstant bodyRef = cp.addMethodref(thisClass, cp.addNameAndType(bodyName, mainDesc));
		Utf8Constant argsName = cp.addUtf8(ARGS_FIELD);
		Utf8Constant argsDesc = cp.addUtf8("[Ljava/lang/String;");
		Utf8Constant thrownName = cp.addUtf8(THROWN_FIELD);
		Utf8Constant thrownDesc = cp.addUtf8("Ljava/lang/Throwable;");
		FieldrefConstant argsField = cp.addFieldref(thisClass, cp.addNameAndType(argsName, argsDesc));
		FieldrefConstant thrownField = cp.addFieldref(thisClass, cp.addNameAndType(thrownName, thrownDesc));
		Utf8Constant runName = cp.addUtf8(RUN_METHOD);
		Utf8Constant runDesc = cp.addUtf8("(L" + className + ";)V");
		MethodrefConstant runRef = cp.addMethodref(thisClass, cp.addNameAndType(runName, runDesc));
		ClassConstant runnableClass = cp.addClass(cp.addUtf8("java/lang/Runnable"));
		ClassConstant threadClass = cp.addClass(cp.addUtf8("java/lang/Thread"));
		ClassConstant integerClass = cp.addClass(cp.addUtf8("java/lang/Integer"));
		ClassConstant throwableClass = cp.addClass(cp.addUtf8("java/lang/Throwable"));
		ClassConstant interruptedClass = cp.addClass(cp.addUtf8("java/lang/InterruptedException"));
		MethodrefConstant threadInit = cp.addMethodref(threadClass, cp.addNameAndType(cp.addUtf8("<init>"),
				cp.addUtf8("(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;Ljava/lang/String;J)V")));
		MethodrefConstant getInteger = cp.addMethodref(integerClass,
				cp.addNameAndType(cp.addUtf8("getInteger"), cp.addUtf8("(Ljava/lang/String;I)Ljava/lang/Integer;")));
		MethodrefConstant intValue = cp.addMethodref(integerClass,
				cp.addNameAndType(cp.addUtf8("intValue"), cp.addUtf8("()I")));
		MethodrefConstant threadStart = cp.addMethodref(threadClass,
				cp.addNameAndType(cp.addUtf8("start"), cp.addUtf8("()V")));
		MethodrefConstant threadJoin = cp.addMethodref(threadClass,
				cp.addNameAndType(cp.addUtf8("join"), cp.addUtf8("()V")));
		MethodrefConstant currentThread = cp.addMethodref(threadClass,
				cp.addNameAndType(cp.addUtf8("currentThread"), cp.addUtf8("()Ljava/lang/Thread;")));
		MethodrefConstant threadInterrupt = cp.addMethodref(threadClass,
				cp.addNameAndType(cp.addUtf8("interrupt"), cp.addUtf8("()V")));
		ConstantPool.StringConstant threadName = cp.addString("main");
		ConstantPool.StringConstant property = cp.addString(STACK_PROPERTY);
		// The hand-over's constants come last, so a program without objc: mints
		// exactly the pool it always did.
		@Nullable HandOver handOver = mainThreadClass != null ? HandOver.mint(cp, thisClass, mainThreadClass) : null;

		// --- main(String[] args): locals 0 args, 1 runner, 2 thread, 3 interrupted
		JvmAsyncRuntimeBuilder.Asm m = new JvmAsyncRuntimeBuilder.Asm();
		m.op(Opcode.NEW);
		m.u2(thisClass.index());
		m.op(Opcode.DUP);
		m.op(Opcode.INVOKESPECIAL);
		m.u2(instanceInitRef.index());
		m.astore(1);
		m.aload(1);
		m.aload(0);
		m.op(Opcode.PUTFIELD);
		m.u2(argsField.index());
		m.op(Opcode.NEW);
		m.u2(threadClass.index());
		m.op(Opcode.DUP);
		m.aconstNull();
		m.aload(1);
		m.ldc(threadName.index());
		m.ldc(property.index());
		m.iconst(DEFAULT_STACK_MIB);
		m.op(Opcode.INVOKESTATIC);
		m.u2(getInteger.index());
		m.op(Opcode.INVOKEVIRTUAL);
		m.u2(intValue.index());
		m.op(Opcode.I2L);
		m.iconst(20);
		m.op(Opcode.LSHL);
		m.op(Opcode.INVOKESPECIAL);
		m.u2(threadInit.index());
		m.astore(2);
		if (handOver != null) {
			// Thread 0 is the one AppKit needs: the worker takes the program and
			// thread 0 parks in the run loop for good.
			int keep = m.label();
			m.op(Opcode.INVOKESTATIC);
			m.u2(handOver.required().index());
			m.branch(Opcode.IFEQ, keep);
			m.aload(1);
			m.iconst(1);
			m.op(Opcode.PUTFIELD);
			m.u2(handOver.exitField().index());
			m.aload(2);
			m.op(Opcode.INVOKEVIRTUAL);
			m.u2(threadStart.index());
			m.op(Opcode.INVOKESTATIC);
			m.u2(handOver.get().index());
			m.op(Opcode.INVOKEVIRTUAL);
			m.u2(handOver.runLoop().index());
			m.op(Opcode.RETURN);
			m.bind(keep);
		}
		m.aload(2);
		m.op(Opcode.INVOKEVIRTUAL);
		m.u2(threadStart.index());
		m.iconst(0);
		m.istore(3);
		int join = m.label();
		int joined = m.label();
		m.bind(join);
		int tryStart = m.pos();
		m.aload(2);
		m.op(Opcode.INVOKEVIRTUAL);
		m.u2(threadJoin.index());
		int tryEnd = m.pos();
		m.branch(Opcode.GOTO, joined);
		// An interrupt aimed at thread 0 is remembered, never a reason to stop waiting:
		// returning early would end main while the program still runs.
		int handler = m.pos();
		m.op(Opcode.POP);
		m.iconst(1);
		m.istore(3);
		m.branch(Opcode.GOTO, join);
		m.bind(joined);
		int notInterrupted = m.label();
		m.iload(3);
		m.branch(Opcode.IFEQ, notInterrupted);
		m.op(Opcode.INVOKESTATIC);
		m.u2(currentThread.index());
		m.op(Opcode.INVOKEVIRTUAL);
		m.u2(threadInterrupt.index());
		m.bind(notInterrupted);
		int clean = m.label();
		m.aload(1);
		m.op(Opcode.GETFIELD);
		m.u2(thrownField.index());
		m.op(Opcode.DUP);
		m.branch(Opcode.IFNULL, clean);
		m.op(Opcode.ATHROW);
		m.bind(clean);
		m.op(Opcode.POP);
		m.op(Opcode.RETURN);
		Method main = new Method(cp.addUtf8("main"), mainDesc, 9, 4, m.finish(),
				List.of(new int[] { tryStart, tryEnd, handler, interruptedClass.index() }));

		// --- _main$run(Prog r): try { _main$body(r._main$args) } catch (Throwable t) {
		// r._main$thrown = t }
		JvmAsyncRuntimeBuilder.Asm r = new JvmAsyncRuntimeBuilder.Asm();
		int bodyStart = r.pos();
		r.aload(0);
		r.op(Opcode.GETFIELD);
		r.u2(argsField.index());
		r.op(Opcode.INVOKESTATIC);
		r.u2(bodyRef.index());
		int bodyEnd = r.pos();
		int after = r.label();
		if (handOver != null) {
			r.branch(Opcode.GOTO, after);
		}
		else {
			r.op(Opcode.RETURN);
		}
		int caught = r.pos();
		r.astore(1);
		r.aload(0);
		r.aload(1);
		r.op(Opcode.PUTFIELD);
		r.u2(thrownField.index());
		if (handOver == null) {
			r.op(Opcode.RETURN);
		}
		else {
			// The worker of a hand-over ends the process: nothing waits for it, and
			// thread 0 never leaves the run loop.
			r.bind(after);
			handOver.emitExit(r, thrownField);
		}
		Method run = new Method(runName, runDesc, handOver != null ? 3 : 2, handOver != null ? 3 : 2, r.finish(),
				List.of(new int[] { bodyStart, bodyEnd, caught, throwableClass.index() }));

		return new SizedMain(bodyName, main, run, runRef, argsName, argsDesc, thrownName, thrownDesc, runnableClass,
				handOver != null ? handOver.exitName() : null, handOver != null ? handOver.exitDesc() : null);
	}

	/**
	 * The constants of the thread-0 hand-over.
	 *
	 * @param required {@code MainThread.handOverRequired()Z}
	 * @param get {@code MainThread.get()}
	 * @param runLoop {@code MainThread.runLoop()V}
	 * @param exitName the {@code _main$exit} field's name
	 * @param exitDesc its descriptor, {@code Z}
	 * @param exitField the field
	 * @param currentThread {@code Thread.currentThread()}
	 * @param handlerOf {@code Thread.getUncaughtExceptionHandler()}
	 * @param uncaught
	 * {@code UncaughtExceptionHandler.uncaughtException(Thread, Throwable)}
	 * @param exit {@code System.exit(I)V}
	 */
	private record HandOver(MethodrefConstant required, MethodrefConstant get, MethodrefConstant runLoop,
			Utf8Constant exitName, Utf8Constant exitDesc, FieldrefConstant exitField, MethodrefConstant currentThread,
			MethodrefConstant handlerOf, MethodrefConstant uncaught, MethodrefConstant exit) {

		static HandOver mint(ConstantPool cp, ClassConstant thisClass, String mainThreadClass) {
			ClassConstant mainThread = cp.addClass(cp.addUtf8(mainThreadClass));
			MethodrefConstant required = cp.addMethodref(mainThread,
					cp.addNameAndType(cp.addUtf8("handOverRequired"), cp.addUtf8("()Z")));
			MethodrefConstant get = cp.addMethodref(mainThread,
					cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("()L" + mainThreadClass + ";")));
			MethodrefConstant runLoop = cp.addMethodref(mainThread,
					cp.addNameAndType(cp.addUtf8("runLoop"), cp.addUtf8("()V")));
			Utf8Constant exitName = cp.addUtf8(EXIT_FIELD);
			Utf8Constant exitDesc = cp.addUtf8("Z");
			FieldrefConstant exitField = cp.addFieldref(thisClass, cp.addNameAndType(exitName, exitDesc));
			ClassConstant threadClass = cp.addClass(cp.addUtf8("java/lang/Thread"));
			ClassConstant handlerClass = cp.addClass(cp.addUtf8("java/lang/Thread$UncaughtExceptionHandler"));
			MethodrefConstant currentThread = cp.addMethodref(threadClass,
					cp.addNameAndType(cp.addUtf8("currentThread"), cp.addUtf8("()Ljava/lang/Thread;")));
			MethodrefConstant handlerOf = cp.addMethodref(threadClass,
					cp.addNameAndType(cp.addUtf8("getUncaughtExceptionHandler"),
							cp.addUtf8("()Ljava/lang/Thread$UncaughtExceptionHandler;")));
			MethodrefConstant uncaught = cp.addInterfaceMethodref(handlerClass, cp.addNameAndType(
					cp.addUtf8("uncaughtException"), cp.addUtf8("(Ljava/lang/Thread;Ljava/lang/Throwable;)V")));
			MethodrefConstant exit = cp.addMethodref(cp.addClass(cp.addUtf8("java/lang/System")),
					cp.addNameAndType(cp.addUtf8("exit"), cp.addUtf8("(I)V")));
			return new HandOver(required, get, runLoop, exitName, exitDesc, exitField, currentThread, handlerOf,
					uncaught, exit);
		}

		/**
		 * {@code _main$run}'s tail, locals 0 runner, 1 thrown, 2 thread: <pre>
		 * if (r._main$exit) {
		 *     Throwable t = r._main$thrown;
		 *     if (t != null) {
		 *         Thread c = Thread.currentThread();
		 *         c.getUncaughtExceptionHandler().uncaughtException(c, t);
		 *         System.exit(1);
		 *     }
		 *     System.exit(0);
		 * }
		 * </pre>
		 */
		void emitExit(JvmAsyncRuntimeBuilder.Asm r, FieldrefConstant thrownField) {
			int done = r.label();
			int clean = r.label();
			r.aload(0);
			r.op(Opcode.GETFIELD);
			r.u2(this.exitField.index());
			r.branch(Opcode.IFEQ, done);
			r.aload(0);
			r.op(Opcode.GETFIELD);
			r.u2(thrownField.index());
			r.astore(1);
			r.aload(1);
			r.branch(Opcode.IFNULL, clean);
			r.op(Opcode.INVOKESTATIC);
			r.u2(this.currentThread.index());
			r.astore(2);
			r.aload(2);
			r.op(Opcode.INVOKEVIRTUAL);
			r.u2(this.handlerOf.index());
			r.aload(2);
			r.aload(1);
			r.op(Opcode.INVOKEINTERFACE);
			r.u2(this.uncaught.index());
			r.op(3);
			r.op(0);
			r.iconst(1);
			r.op(Opcode.INVOKESTATIC);
			r.u2(this.exit.index());
			r.op(Opcode.RETURN);
			r.bind(clean);
			r.iconst(0);
			r.op(Opcode.INVOKESTATIC);
			r.u2(this.exit.index());
			r.bind(done);
			r.op(Opcode.RETURN);
		}

	}

}
