package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

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
	record Method(Utf8Entry name, Utf8Entry desc, MethodCode code) {
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
	record SizedMain(Utf8Entry bodyName, Method main, Method run, MethodRefEntry runRef, Utf8Entry argsName,
			Utf8Entry argsDesc, Utf8Entry thrownName, Utf8Entry thrownDesc, ClassEntry runnableClass,
			@Nullable Utf8Entry exitName, @Nullable Utf8Entry exitDesc) {

		/**
		 * The instance {@code run()} of a class with no other {@code Runnable} use:
		 * {@code _main$run(this)}.
		 * @param cp the constant pool
		 * @return the method
		 */
		Method instanceRun(ConstantPool cp) {
			MethodCode a = new MethodCode();
			a.aload(0);
			a.invokestatic(this.runRef);
			a.return_();
			return new Method(cp.utf8Entry("run"), cp.utf8Entry("()V"), a);
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
	static SizedMain build(ConstantPool cp, ClassEntry thisClass, String className, MethodRefEntry instanceInitRef,
			Utf8Entry mainDesc, @Nullable String mainThreadClass) {
		Utf8Entry bodyName = cp.utf8Entry(BODY_METHOD);
		MethodRefEntry bodyRef = cp.methodRef(thisClass, bodyName, mainDesc);
		Utf8Entry argsName = cp.utf8Entry(ARGS_FIELD);
		Utf8Entry argsDesc = cp.utf8Entry("[Ljava/lang/String;");
		Utf8Entry thrownName = cp.utf8Entry(THROWN_FIELD);
		Utf8Entry thrownDesc = cp.utf8Entry("Ljava/lang/Throwable;");
		FieldRefEntry argsField = cp.fieldRef(thisClass, argsName, argsDesc);
		FieldRefEntry thrownField = cp.fieldRef(thisClass, thrownName, thrownDesc);
		Utf8Entry runName = cp.utf8Entry(RUN_METHOD);
		Utf8Entry runDesc = cp.utf8Entry("(L" + className + ";)V");
		MethodRefEntry runRef = cp.methodRef(thisClass, runName, runDesc);
		ClassEntry runnableClass = cp.classEntry("java/lang/Runnable");
		ClassEntry threadClass = cp.classEntry("java/lang/Thread");
		ClassEntry integerClass = cp.classEntry("java/lang/Integer");
		ClassEntry throwableClass = cp.classEntry("java/lang/Throwable");
		ClassEntry interruptedClass = cp.classEntry("java/lang/InterruptedException");
		MethodRefEntry threadInit = cp.methodRef(threadClass, "<init>",
				"(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;Ljava/lang/String;J)V");
		MethodRefEntry getInteger = cp.methodRef(integerClass, "getInteger",
				"(Ljava/lang/String;I)Ljava/lang/Integer;");
		MethodRefEntry intValue = cp.methodRef(integerClass, "intValue", "()I");
		MethodRefEntry threadStart = cp.methodRef(threadClass, "start", "()V");
		MethodRefEntry threadJoin = cp.methodRef(threadClass, "join", "()V");
		MethodRefEntry currentThread = cp.methodRef(threadClass, "currentThread", "()Ljava/lang/Thread;");
		MethodRefEntry threadInterrupt = cp.methodRef(threadClass, "interrupt", "()V");
		StringEntry threadName = cp.stringEntry("main");
		StringEntry property = cp.stringEntry(STACK_PROPERTY);
		// The hand-over's constants come last, so a program without objc: mints
		// exactly the pool it always did.
		@Nullable HandOver handOver = mainThreadClass != null ? HandOver.mint(cp, thisClass, mainThreadClass) : null;

		// --- main(String[] args): locals 0 args, 1 runner, 2 thread, 3 interrupted
		MethodCode m = new MethodCode();
		m.new_(thisClass);
		m.dup();
		m.invokespecial(instanceInitRef);
		m.astore(1);
		m.aload(1);
		m.aload(0);
		m.putfield(argsField);
		m.new_(threadClass);
		m.dup();
		m.aconst_null();
		m.aload(1);
		m.ldc(threadName);
		m.ldc(property);
		m.loadConstant(DEFAULT_STACK_MIB);
		m.invokestatic(getInteger);
		m.invokevirtual(intValue);
		m.i2l();
		m.loadConstant(20);
		m.lshl();
		m.invokespecial(threadInit);
		m.astore(2);
		if (handOver != null) {
			// Thread 0 is the one AppKit needs: the worker takes the program and
			// thread 0 parks in the run loop for good.
			MethodCode.Label keep = m.newLabel();
			m.invokestatic(handOver.required());
			m.ifeq(keep);
			m.aload(1);
			m.loadConstant(1);
			m.putfield(handOver.exitField());
			m.aload(2);
			m.invokevirtual(threadStart);
			m.invokestatic(handOver.get());
			m.invokevirtual(handOver.runLoop());
			m.return_();
			m.labelBinding(keep);
		}
		m.aload(2);
		m.invokevirtual(threadStart);
		m.loadConstant(0);
		m.istore(3);
		MethodCode.Label join = m.newLabel();
		MethodCode.Label joined = m.newLabel();
		m.labelBinding(join);
		MethodCode.Label tryStart = m.newBoundLabel();
		m.aload(2);
		m.invokevirtual(threadJoin);
		MethodCode.Label tryEnd = m.newBoundLabel();
		m.goto_(joined);
		// An interrupt aimed at thread 0 is remembered, never a reason to stop waiting:
		// returning early would end main while the program still runs.
		MethodCode.Label handler = m.newBoundLabel();
		m.pop();
		m.loadConstant(1);
		m.istore(3);
		m.goto_(join);
		m.labelBinding(joined);
		MethodCode.Label notInterrupted = m.newLabel();
		m.iload(3);
		m.ifeq(notInterrupted);
		m.invokestatic(currentThread);
		m.invokevirtual(threadInterrupt);
		m.labelBinding(notInterrupted);
		MethodCode.Label clean = m.newLabel();
		m.aload(1);
		m.getfield(thrownField);
		m.dup();
		m.ifnull(clean);
		m.athrow();
		m.labelBinding(clean);
		m.pop();
		m.return_();
		m.exceptionCatch(tryStart, tryEnd, handler, interruptedClass);
		Method main = new Method(cp.utf8Entry("main"), mainDesc, m);

		// --- _main$run(Prog r): try { _main$body(r._main$args) } catch (Throwable t) {
		// r._main$thrown = t }
		MethodCode r = new MethodCode();
		MethodCode.Label bodyStart = r.newBoundLabel();
		r.aload(0);
		r.getfield(argsField);
		r.invokestatic(bodyRef);
		MethodCode.Label bodyEnd = r.newBoundLabel();
		MethodCode.Label after = r.newLabel();
		if (handOver != null) {
			r.goto_(after);
		}
		else {
			r.return_();
		}
		MethodCode.Label caught = r.newBoundLabel();
		r.astore(1);
		r.aload(0);
		r.aload(1);
		r.putfield(thrownField);
		if (handOver == null) {
			r.return_();
		}
		else {
			// The worker of a hand-over ends the process: nothing waits for it, and
			// thread 0 never leaves the run loop.
			r.labelBinding(after);
			handOver.emitExit(r, thrownField);
		}
		r.exceptionCatch(bodyStart, bodyEnd, caught, throwableClass);
		Method run = new Method(runName, runDesc, r);

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
	private record HandOver(MethodRefEntry required, MethodRefEntry get, MethodRefEntry runLoop, Utf8Entry exitName,
			Utf8Entry exitDesc, FieldRefEntry exitField, MethodRefEntry currentThread, MethodRefEntry handlerOf,
			InterfaceMethodRefEntry uncaught, MethodRefEntry exit) {

		static HandOver mint(ConstantPool cp, ClassEntry thisClass, String mainThreadClass) {
			ClassEntry mainThread = cp.classEntry(mainThreadClass);
			MethodRefEntry required = cp.methodRef(mainThread, "handOverRequired", "()Z");
			MethodRefEntry get = cp.methodRef(mainThread, "get", "()L" + mainThreadClass + ";");
			MethodRefEntry runLoop = cp.methodRef(mainThread, "runLoop", "()V");
			Utf8Entry exitName = cp.utf8Entry(EXIT_FIELD);
			Utf8Entry exitDesc = cp.utf8Entry("Z");
			FieldRefEntry exitField = cp.fieldRef(thisClass, exitName, exitDesc);
			ClassEntry threadClass = cp.classEntry("java/lang/Thread");
			ClassEntry handlerClass = cp.classEntry("java/lang/Thread$UncaughtExceptionHandler");
			MethodRefEntry currentThread = cp.methodRef(threadClass, "currentThread", "()Ljava/lang/Thread;");
			MethodRefEntry handlerOf = cp.methodRef(threadClass, "getUncaughtExceptionHandler",
					"()Ljava/lang/Thread$UncaughtExceptionHandler;");
			InterfaceMethodRefEntry uncaught = cp.interfaceMethodRef(handlerClass, "uncaughtException",
					"(Ljava/lang/Thread;Ljava/lang/Throwable;)V");
			MethodRefEntry exit = cp.methodRef(cp.classEntry("java/lang/System"), "exit", "(I)V");
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
		void emitExit(MethodCode r, FieldRefEntry thrownField) {
			MethodCode.Label done = r.newLabel();
			MethodCode.Label clean = r.newLabel();
			r.aload(0);
			r.getfield(this.exitField);
			r.ifeq(done);
			r.aload(0);
			r.getfield(thrownField);
			r.astore(1);
			r.aload(1);
			r.ifnull(clean);
			r.invokestatic(this.currentThread);
			r.astore(2);
			r.aload(2);
			r.invokevirtual(this.handlerOf);
			r.aload(2);
			r.aload(1);
			r.invokeinterface(this.uncaught);
			r.loadConstant(1);
			r.invokestatic(this.exit);
			r.return_();
			r.labelBinding(clean);
			r.loadConstant(0);
			r.invokestatic(this.exit);
			r.labelBinding(done);
			r.return_();
		}

	}

}
