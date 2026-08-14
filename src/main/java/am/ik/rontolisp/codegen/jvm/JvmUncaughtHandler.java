package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.compiler.UncaughtReport;

/**
 * The generated {@code main}'s last exception-table entry: a condition nobody caught
 * prints {@link UncaughtReport#PREFIX} plus its report on standard error, instead of ten
 * frames of mangled Lisp method names ({@code Fail.$pctERROR-RT-47}).
 *
 * <p>
 * <b>It reports and RETHROWS -- with the stack trace emptied.</b> Not
 * {@code System.exit(1)}, which would be one line shorter and is wrong: a compiled class
 * is a value, and its {@code main} is invoked in-process (reflectively) by roughly a
 * hundred assertions in this project's own JVM-backend tests and by anything else that
 * embeds one. Killing the calling JVM to report an error is not a program's decision to
 * make. Rethrowing keeps the exception observable and lets the launcher supply exit code
 * 1; clearing the trace first is what keeps the default handler's echo to one line:
 *
 * <pre>
 * Unhandled condition: boom: 42
 * Exception in thread "main" java.lang.RuntimeException: boom: 42
 * </pre>
 *
 * The second line is the HOST saying the process died, the same role wasmtime's trap
 * report plays on the two wasm backends -- where it runs to six lines. {@code
 * RONTOLISP_DEBUG} keeps the full trace instead ({@link UncaughtReport#DEBUG_ENV}).
 * <b>Re-evaluate if</b> that echo ever has to go: a class implementing
 * {@code Thread.UncaughtExceptionHandler} and installing itself on the current thread
 * removes it (a reflective caller never consults the handler, so the tests still see the
 * throw), at the price of an interface, a constructor and a shaker root on every artifact
 * -- and of having to re-implement the default trace for the {@code Error}s the catch
 * below deliberately does not take.
 *
 * <p>
 * {@code RuntimeException} is the whole catch, matching what {@code handler-case} takes
 * on this backend ({@code .kb/error-handling.md}: the interpreter catches
 * {@code LispEvalException} only, the JVM backend any {@code RuntimeException}, wasm-GC
 * {@code $lisp-cond} throws only) -- so anything a handler-case COULD have caught reports
 * the same way when nothing did. An {@code Error} still propagates untouched: a
 * {@code StackOverflowError} is not a signaled condition and its trace is the diagnosis.
 *
 * <p>
 * Appended LAST, after every {@code unwind-protect}/{@code handler-case} entry main may
 * already carry, because the JVM dispatches an exception table in order: an inner handler
 * must win. The region is the whole body, and the entry costs 8 bytes plus this handler's
 * ~30 -- main is a list of {@code invokestatic} chunk calls, so neither the 64 KB method
 * limit nor {@code maxStack} (floored at 64) is in reach. Offsets are written raw rather
 * than deferred because main never overflows a branch, so {@link am.ik.jvm.BranchRelaxer}
 * leaves its code untouched.
 */
final class JvmUncaughtHandler {

	private JvmUncaughtHandler() {
	}

	/**
	 * Appends the handler to {@code mainCtx}'s code and the entry covering the body
	 * emitted so far to its exception table. Call once, after main's final
	 * {@code return}.
	 * @param mainCtx the context holding the generated {@code main} body
	 */
	static void append(JvmLispCompiler.Ctx mainCtx) {
		ConstantPool cp = mainCtx.cp;
		int bodyEnd = mainCtx.code.size();
		// Slot 1 in practice: main's body is a list of invokestatic chunk calls and
		// allocates no local of its own, so the one-byte aload/astore operand below is
		// never in reach of the 255-slot wrap that would need the `wide` prefix.
		int exSlot = mainCtx.allocTemp();
		ConstantPool.ClassConstant systemClass = cp.addClass(cp.addUtf8("java/lang/System"));
		ConstantPool.ClassConstant throwableClass = cp.addClass(cp.addUtf8("java/lang/Throwable"));
		ConstantPool.FieldrefConstant systemErr = cp.addFieldref(systemClass,
				cp.addNameAndType(cp.addUtf8("err"), cp.addUtf8("Ljava/io/PrintStream;")));
		int getMessage = cp
			.addMethodref(throwableClass,
					cp.addNameAndType(cp.addUtf8("getMessage"), cp.addUtf8("()Ljava/lang/String;")))
			.index();
		// String.valueOf(Object), not concat's argument directly: a RuntimeException
		// raised by something other than %error may carry a null message, and
		// "...".concat(null) would replace the report with a NullPointerException.
		int valueOf = cp
			.addMethodref(mainCtx.stringClass,
					cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/String;")))
			.index();
		int concat = cp
			.addMethodref(mainCtx.stringClass,
					cp.addNameAndType(cp.addUtf8("concat"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")))
			.index();
		int getenv = cp
			.addMethodref(systemClass,
					cp.addNameAndType(cp.addUtf8("getenv"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")))
			.index();
		int setStackTrace = cp
			.addMethodref(throwableClass,
					cp.addNameAndType(cp.addUtf8("setStackTrace"), cp.addUtf8("([Ljava/lang/StackTraceElement;)V")))
			.index();
		int stackTraceElement = cp.addClass(cp.addUtf8("java/lang/StackTraceElement")).index();

		List<Integer> code = new ArrayList<>();
		// The handler is entered with the exception as the sole operand.
		code.add(Opcode.ASTORE);
		code.add(exSlot);
		// System.err.println(PREFIX.concat(String.valueOf(ex.getMessage())))
		code.add(Opcode.GETSTATIC);
		addU2(code, systemErr.index());
		addLdc(code, cp.addString(UncaughtReport.PREFIX).index());
		code.add(Opcode.ALOAD);
		code.add(exSlot);
		code.add(Opcode.INVOKEVIRTUAL);
		addU2(code, getMessage);
		code.add(Opcode.INVOKESTATIC);
		addU2(code, valueOf);
		code.add(Opcode.INVOKEVIRTUAL);
		addU2(code, concat);
		code.add(Opcode.INVOKEVIRTUAL);
		addU2(code, mainCtx.printlnStr.index());
		// if (System.getenv("RONTOLISP_DEBUG") == null) ex.setStackTrace(new
		// StackTraceElement[0]);
		addLdc(code, cp.addString(UncaughtReport.DEBUG_ENV).index());
		code.add(Opcode.INVOKESTATIC);
		addU2(code, getenv);
		code.add(Opcode.IFNONNULL);
		// past this 3-byte branch, the 9 bytes of the clearing it guards
		addU2(code, 3 + 9);
		code.add(Opcode.ALOAD);
		code.add(exSlot);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.ANEWARRAY);
		addU2(code, stackTraceElement);
		code.add(Opcode.INVOKEVIRTUAL);
		addU2(code, setStackTrace);
		// Rethrow: the launcher's exit code is 1 and its echo is now one line.
		code.add(Opcode.ALOAD);
		code.add(exSlot);
		code.add(Opcode.ATHROW);

		mainCtx.code.addAll(code);
		mainCtx.exceptionTable.add(new ByteCodeWriter.ExceptionTableEntry(0, bodyEnd, bodyEnd,
				cp.addClass(cp.addUtf8("java/lang/RuntimeException")).index()));
	}

	private static void addU2(List<Integer> code, int value) {
		code.add((value >> 8) & 0xFF);
		code.add(value & 0xFF);
	}

	private static void addLdc(List<Integer> code, int index) {
		if (index <= 255) {
			code.add(Opcode.LDC);
			code.add(index);
		}
		else {
			code.add(Opcode.LDC_W);
			addU2(code, index);
		}
	}

}
