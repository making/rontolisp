package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

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
 */
final class JvmSizedMainBuilder {

	/** The static the old {@code main} body is emitted as, unchanged. */
	static final String BODY_METHOD = "_main$body";

	/** {@code _main$run(Prog)}: the body on the worker, its throwable kept. */
	static final String RUN_METHOD = "_main$run";

	static final String ARGS_FIELD = "_main$args";

	static final String THROWN_FIELD = "_main$thrown";

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
	 */
	record SizedMain(Utf8Constant bodyName, Method main, Method run, MethodrefConstant runRef, Utf8Constant argsName,
			Utf8Constant argsDesc, Utf8Constant thrownName, Utf8Constant thrownDesc, ClassConstant runnableClass) {

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
	 * @return the launcher's pieces
	 */
	static SizedMain build(ConstantPool cp, ClassConstant thisClass, String className,
			MethodrefConstant instanceInitRef, Utf8Constant mainDesc) {
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
		r.op(Opcode.RETURN);
		int caught = r.pos();
		r.astore(1);
		r.aload(0);
		r.aload(1);
		r.op(Opcode.PUTFIELD);
		r.u2(thrownField.index());
		r.op(Opcode.RETURN);
		Method run = new Method(runName, runDesc, 2, 2, r.finish(),
				List.of(new int[] { bodyStart, bodyEnd, caught, throwableClass.index() }));

		return new SizedMain(bodyName, main, run, runRef, argsName, argsDesc, thrownName, thrownDesc, runnableClass);
	}

}
