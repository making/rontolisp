package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.StringConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.compiler.UncaughtReport;

import org.jspecify.annotations.Nullable;

/**
 * The generated {@code main}'s last exception-table entry: a condition nobody caught
 * prints {@link UncaughtReport#PREFIX} plus its report on standard error, instead of ten
 * frames of mangled Lisp method names ({@code Fail.$pctERROR-RT-47}), and under it where
 * it happened -- the same location lines the interpreter prints.
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
 *   at app.lisp:2
 * Exception in thread "main" java.lang.RuntimeException: boom: 42
 * </pre>
 *
 * The last line is the HOST saying the process died, the same role wasmtime's trap report
 * plays on the two wasm backends -- where it runs to six lines. {@code RONTOLISP_DEBUG}
 * keeps the full trace instead ({@link UncaughtReport#DEBUG_ENV}). <b>Re-evaluate if</b>
 * that echo ever has to go: a class implementing {@code Thread.UncaughtExceptionHandler}
 * and installing itself on the current thread removes it (a reflective caller never
 * consults the handler, so the tests still see the throw), at the price of an interface,
 * a constructor and a shaker root on every artifact -- and of having to re-implement the
 * default trace for the {@code Error}s the catch below deliberately does not take.
 *
 * <p>
 * <b>Where it happened is read off the stack trace.</b> Every method the program's own
 * source compiled into carries a {@code LineNumberTable} whose numbers are
 * {@link JvmSourceSites} ids, and {@code _where} ({@link #buildWhere}) walks the trace
 * the exception filled in where it was created: the innermost frame of this class (or of
 * one of its {@code $PartN} classes) at a located site is where it happened, and the
 * first frame from there out whose site belongs to a named function says which function.
 * A frame without a site -- a runtime helper, a library function spliced from the jar --
 * is transparent, which is what reports a library function's failure at its caller's call
 * site. Nothing runs until a condition escapes, and a class compiled with nothing located
 * gets none of it.
 *
 * <p>
 * <b>An async boundary is written INTO the trace.</b> An {@code async-defun} body runs on
 * a virtual thread and {@code _await} rethrows its exception on the awaiting one, whose
 * frames the exception never saw. So the body's thunk catches what escapes it and appends
 * a frame of the made-up class {@link #ASYNC_FRAME_CLASS} ({@code /} is in no binary
 * name, so no real frame can be mistaken for it) naming the async function
 * ({@link #appendAsyncCrossing}, {@code _asyncCross}); an {@code _await} that rethrows it
 * then puts back the trace the future stored and appends the awaiting thread's own frames
 * ({@code _asyncAwaited}), so the hop names the await the condition escaped from -- a
 * second await of a failed future after a handler caught the first, as the interpreter's
 * trace rewinds to the boundary at each await. {@code _where} reads each such frame as a
 * hop line. A trace the report empties never shows any of it; under
 * {@code RONTOLISP_DEBUG} the printed trace is the async chain end to end.
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
 * limit nor {@code maxStack} (floored at 64) is in reach.
 */
final class JvmUncaughtHandler {

	/**
	 * The class an async boundary's frame names. {@link StackTraceElement#getClassName()}
	 * answers a binary name, which never contains a {@code /}.
	 */
	static final String ASYNC_FRAME_CLASS = "rontolisp/async";

	/** An async boundary's frame before any {@code await} rethrew the exception. */
	static final String ASYNC_CROSSED = "crossed";

	/** An async boundary's frame once an {@code await} appended its own frames. */
	static final String ASYNC_AWAITED = "awaited";

	/** {@code _where(Throwable)}: prints the location lines. */
	static final String WHERE_METHOD = "_where";

	static final String WHERE_DESC = "(Ljava/lang/Throwable;)V";

	/** {@code _asyncCross(Throwable, String)}: appends a crossed boundary. */
	static final String ASYNC_CROSS_METHOD = "_asyncCross";

	static final String ASYNC_CROSS_DESC = "(Ljava/lang/Throwable;Ljava/lang/String;)V";

	/**
	 * {@code _asyncAwaited(Throwable, StackTraceElement[])}: puts back the trace a future
	 * stored and completes its boundary with the awaiter.
	 */
	static final String ASYNC_AWAITED_METHOD = "_asyncAwaited";

	static final String ASYNC_AWAITED_DESC = "(Ljava/lang/Throwable;[Ljava/lang/StackTraceElement;)V";

	// _where's locals.
	private static final int EX = 0;

	private static final int TRACE = 1;

	private static final int TABLE = 2;

	private static final int NAMES = 3;

	private static final int COUNT = 4;

	private static final int INDEX = 5;

	private static final int LOCATION = 6;

	private static final int FUNCTION = 7;

	private static final int FRAME = 8;

	private static final int FRAME_CLASS = 9;

	private static final int SITE = 10;

	private static final int SITE_BASE = 11;

	private static final int HEAD = 12;

	private static final int AWAITED = 13;

	private JvmUncaughtHandler() {
	}

	/**
	 * A generated runtime method.
	 *
	 * @param name its name
	 * @param desc its descriptor
	 * @param code the body, its handlers included
	 */
	record Built(Utf8Constant name, Utf8Constant desc, MethodCode code) {
	}

	/**
	 * Mints what the handler references and takes its local slot, leaving the code for
	 * {@link Prepared#append}. Split in two because whether the class reports locations
	 * is known only once every body is compiled, while the handler's constant-pool
	 * entries have always been minted right after main's own body: minting them here
	 * keeps the pool -- and so the class -- of a program with nothing located exactly
	 * what it was. Call once, after main's final {@code return}.
	 * @param mainCtx the context holding the generated {@code main} body
	 * @return the handler, ready to append
	 */
	static Prepared prepare(JvmLispCompiler.Ctx mainCtx) {
		ConstantPool cp = mainCtx.cp;
		int exSlot = mainCtx.allocTemp();
		ConstantPool.ClassConstant systemClass = cp.addClass(cp.addUtf8("java/lang/System"));
		ConstantPool.ClassConstant throwableClass = cp.addClass(cp.addUtf8("java/lang/Throwable"));
		ConstantPool.FieldrefConstant systemErr = cp.addFieldref(systemClass,
				cp.addNameAndType(cp.addUtf8("err"), cp.addUtf8("Ljava/io/PrintStream;")));
		MethodrefConstant getMessage = method(cp, throwableClass, "getMessage", "()Ljava/lang/String;");
		// String.valueOf(Object), not concat's argument directly: a RuntimeException
		// raised by something other than %error may carry a null message, and
		// "...".concat(null) would replace the report with a NullPointerException.
		MethodrefConstant valueOf = method(cp, mainCtx.stringClass, "valueOf",
				"(Ljava/lang/Object;)Ljava/lang/String;");
		MethodrefConstant concat = method(cp, mainCtx.stringClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		MethodrefConstant getenv = method(cp, systemClass, "getenv", "(Ljava/lang/String;)Ljava/lang/String;");
		MethodrefConstant setStackTrace = method(cp, throwableClass, "setStackTrace",
				"([Ljava/lang/StackTraceElement;)V");
		ClassConstant stackTraceElement = cp.addClass(cp.addUtf8("java/lang/StackTraceElement"));
		StringConstant prefix = cp.addString(UncaughtReport.PREFIX);
		StringConstant debugEnv = cp.addString(UncaughtReport.DEBUG_ENV);
		ClassConstant runtimeException = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		return new Prepared(mainCtx, exSlot, systemErr, getMessage, valueOf, concat, getenv, setStackTrace,
				stackTraceElement, prefix, debugEnv, runtimeException);
	}

	/**
	 * The handler with everything it references minted ({@link #prepare}).
	 *
	 * @param mainCtx the context holding the generated {@code main} body
	 * @param exSlot the local the exception is kept in
	 * @param systemErr {@code System.err}
	 * @param getMessage {@code Throwable.getMessage}
	 * @param valueOf {@code String.valueOf(Object)}
	 * @param concat {@code String.concat}
	 * @param getenv {@code System.getenv}
	 * @param setStackTrace {@code Throwable.setStackTrace}
	 * @param stackTraceElement the {@code StackTraceElement} class
	 * @param prefix the report line's prefix
	 * @param debugEnv {@link UncaughtReport#DEBUG_ENV}
	 * @param runtimeException the {@code RuntimeException} class, the entry's catch type
	 */
	record Prepared(JvmLispCompiler.Ctx mainCtx, int exSlot, ConstantPool.FieldrefConstant systemErr,
			MethodrefConstant getMessage, MethodrefConstant valueOf, MethodrefConstant concat, MethodrefConstant getenv,
			MethodrefConstant setStackTrace, ClassConstant stackTraceElement, StringConstant prefix,
			StringConstant debugEnv, ClassConstant runtimeException) {

		/**
		 * Appends the handler to main's code and the entry covering the body to its
		 * exception table. Nothing may be emitted into main between {@link #prepare} and
		 * this.
		 * @param where {@code _where}, which prints the location lines after the report
		 * line, or {@code null} when nothing in the class was located
		 */
		void append(@Nullable MethodrefConstant where) {
			appendHandler(this, where);
		}

	}

	private static void appendHandler(Prepared p, @Nullable MethodrefConstant where) {
		JvmLispCompiler.Ctx mainCtx = p.mainCtx();
		MethodCode code = mainCtx.body;
		MethodCode.Label bodyEnd = code.newBoundLabel();
		int exSlot = p.exSlot();
		// The handler is entered with the exception as the sole operand.
		mainCtx.stack.enterHandler();
		code.astore(exSlot);
		// System.err.println(PREFIX.concat(String.valueOf(ex.getMessage())))
		code.getstatic(p.systemErr().entry())
			.ldc(p.prefix().entry())
			.aload(exSlot)
			.invokevirtual(p.getMessage().methodRefEntry())
			.invokestatic(p.valueOf().entry())
			.invokevirtual(p.concat().methodRefEntry())
			.invokevirtual(mainCtx.printlnStr.methodRefEntry());
		// _where(ex): the location lines, read off the trace before it is emptied.
		if (where != null) {
			code.aload(exSlot).invokestatic(where.entry());
		}
		// if (System.getenv("RONTOLISP_DEBUG") == null) ex.setStackTrace(new
		// StackTraceElement[0]);
		MethodCode.Label debugging = code.newLabel();
		code.ldc(p.debugEnv().entry()).invokestatic(p.getenv().entry()).ifnonnull(debugging);
		code.aload(exSlot)
			.iconst_0()
			.anewarray(p.stackTraceElement().entry())
			.invokevirtual(p.setStackTrace().methodRefEntry());
		code.labelBinding(debugging);
		// The program ends here, so the output files it never closed get what they
		// still buffer -- the same flush main's return and %host-exit do.
		if (mainCtx.flushStreams != null) {
			code.invokestatic(mainCtx.flushStreams.entry());
		}
		// Rethrow: the launcher's exit code is 1 and its echo is now one line.
		code.aload(exSlot).athrow();
		code.exceptionCatch(mainCtx.bodyStart, bodyEnd, bodyEnd, p.runtimeException().entry());
	}

	/**
	 * Makes an async body's thunk record the boundary its condition is about to cross: a
	 * last exception-table entry over the whole thunk that hands the exception to
	 * {@code _asyncCross} with the boundary's head ({@link UncaughtReport#asyncHead}) and
	 * rethrows it. Call once, after the thunk's final {@code areturn}. Last, so every
	 * handler the body establishes itself dispatches first; a {@code RuntimeException}
	 * only, as the uncaught report takes.
	 * @param ctx the thunk's method
	 * @param head the async function's head, fixed where {@code %async-run} was compiled
	 * @param cross the {@code _asyncCross} reference
	 */
	static void appendAsyncCrossing(JvmLispCompiler.Ctx ctx, String head, MethodrefConstant cross) {
		ConstantPool cp = ctx.cp;
		MethodCode.Label bodyEnd = ctx.body.newBoundLabel();
		int exSlot = ctx.allocTemp();
		ctx.stack.enterHandler();
		ctx.body.astore(exSlot).aload(exSlot);
		// A Java string for the trace, never a value the program can hold.
		JvmEmitHelper.compileUnspelledLiteral(head, ctx);
		ctx.body.invokestatic(cross.entry()).aload(exSlot).athrow();
		ctx.body.exceptionCatch(ctx.bodyStart, bodyEnd, bodyEnd,
				cp.addClass(cp.addUtf8("java/lang/RuntimeException")).entry());
	}

	/**
	 * Builds {@code _where(Throwable)V}: the location lines under the report, printed to
	 * standard error. Segment 0 is the trace up to the first async boundary: its
	 * innermost frame at a site gives {@code at FILE:LINE} and, from the same site, the
	 * function the form is written in, {@code in FUNCTION}. Each boundary then gives one
	 * {@code in NAME (async)} line, with {@code awaited at} the first site among the
	 * frames the await appended. The whole body sits in a catch-all: the lines are a
	 * courtesy, and nothing in them may mask the condition.
	 * @param cp the class's pool
	 * @param className the class's internal name
	 * @param sites the compilation's site table, not empty
	 * @param printlnStr {@code PrintStream.println(String)}
	 * @return the method
	 */
	static Built buildWhere(ConstantPool cp, String className, JvmSourceSites sites, MethodrefConstant printlnStr) {
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		ClassConstant throwableClass = cp.addClass(cp.addUtf8("java/lang/Throwable"));
		ClassConstant frameClass = cp.addClass(cp.addUtf8("java/lang/StackTraceElement"));
		ClassConstant systemClass = cp.addClass(cp.addUtf8("java/lang/System"));
		MethodrefConstant getStackTrace = method(cp, throwableClass, "getStackTrace",
				"()[Ljava/lang/StackTraceElement;");
		MethodrefConstant concat = method(cp, stringClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		MethodrefConstant split = method(cp, stringClass, "split", "(Ljava/lang/String;)[Ljava/lang/String;");
		MethodrefConstant equals = method(cp, stringClass, "equals", "(Ljava/lang/Object;)Z");
		MethodrefConstant startsWith = method(cp, stringClass, "startsWith", "(Ljava/lang/String;)Z");
		MethodrefConstant charAt = method(cp, stringClass, "charAt", "(I)C");
		MethodrefConstant length = method(cp, stringClass, "length", "()I");
		MethodrefConstant valueOfInt = method(cp, stringClass, "valueOf", "(I)Ljava/lang/String;");
		MethodrefConstant getClassName = method(cp, frameClass, "getClassName", "()Ljava/lang/String;");
		MethodrefConstant getLineNumber = method(cp, frameClass, "getLineNumber", "()I");
		MethodrefConstant getFileName = method(cp, frameClass, "getFileName", "()Ljava/lang/String;");
		ConstantPool.FieldrefConstant systemErr = cp.addFieldref(systemClass,
				cp.addNameAndType(cp.addUtf8("err"), cp.addUtf8("Ljava/io/PrintStream;")));
		String self = className.replace('/', '.');
		Strings s = new Strings(cp.addString(self), cp.addString(self + "$Part"), cp.addString(ASYNC_FRAME_CLASS),
				cp.addString(":"));
		Refs r = new Refs(concat, equals, startsWith, charAt, length, valueOfInt, getClassName, getLineNumber);

		MethodCode a = new MethodCode();
		MethodCode.Label start = a.newBoundLabel();
		// trace = ex.getStackTrace()
		a.aload(EX);
		a.invokevirtual(getStackTrace.methodRefEntry());
		a.astore(TRACE);
		// table / names: the constants, concatenated back when one did not hold them
		emitJoined(a, cp, sites.tableChunks(), concat);
		a.astore(TABLE);
		emitJoined(a, cp, sites.nameChunks(), concat);
		a.ldc(cp.addString(String.valueOf(JvmSourceSites.NAME_SEPARATOR)).entry());
		a.invokevirtual(split.methodRefEntry());
		a.astore(NAMES);
		a.aload(TRACE);
		a.arraylength();
		a.istore(COUNT);
		a.loadConstant(0);
		a.istore(INDEX);
		a.loadConstant(0);
		a.istore(LOCATION);
		a.aconst_null();
		a.astore(FUNCTION);

		// Segment 0: the frames up to the first async boundary. The innermost frame at a
		// site is the location, and the site's own function the one it is written in;
		// the frames past it only lead to the boundary.
		MethodCode.Label segmentLoop = a.newLabel();
		MethodCode.Label segmentEnd = a.newLabel();
		MethodCode.Label segmentNext = a.newLabel();
		a.labelBinding(segmentLoop);
		emitNextFrame(a, s, r, segmentEnd);
		a.iload(LOCATION);
		a.ifne(segmentNext);
		emitSiteOf(a, s, r, segmentNext);
		a.iload(SITE);
		a.istore(LOCATION);
		a.aload(TABLE);
		a.iload(SITE_BASE);
		a.loadConstant(2);
		a.iadd();
		a.invokevirtual(charAt.methodRefEntry());
		a.istore(SITE);
		a.iload(SITE);
		a.ifeq(segmentNext);
		a.aload(NAMES);
		a.iload(SITE);
		a.aaload();
		a.astore(FUNCTION);
		a.labelBinding(segmentNext);
		a.iinc(INDEX, 1);
		a.goto_(segmentLoop);
		a.labelBinding(segmentEnd);

		// " at FILE:LINE[ in FUNCTION]"
		MethodCode.Label noLocation = a.newLabel();
		a.iload(LOCATION);
		a.ifeq(noLocation);
		a.getstatic(systemErr.entry());
		a.ldc(cp.addString(UncaughtReport.AT_PREFIX).entry());
		emitPosition(a, s, r, LOCATION);
		MethodCode.Label noFunction = a.newLabel();
		a.aload(FUNCTION);
		a.ifnull(noFunction);
		a.ldc(cp.addString(UncaughtReport.IN_FUNCTION).entry());
		a.invokevirtual(concat.methodRefEntry());
		a.aload(FUNCTION);
		a.invokevirtual(concat.methodRefEntry());
		a.labelBinding(noFunction);
		a.invokevirtual(printlnStr.methodRefEntry());
		a.labelBinding(noLocation);

		// One line per async boundary: the frame names the head, the frames after it
		// (up to the next boundary) are the awaiter's.
		MethodCode.Label hopLoop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.labelBinding(hopLoop);
		a.iload(INDEX);
		a.iload(COUNT);
		a.if_icmpge(done);
		a.aload(TRACE);
		a.iload(INDEX);
		a.aaload();
		a.invokevirtual(getFileName.methodRefEntry());
		a.astore(HEAD);
		a.loadConstant(0);
		a.istore(AWAITED);
		a.iinc(INDEX, 1);
		MethodCode.Label hopScan = a.newLabel();
		MethodCode.Label hopEnd = a.newLabel();
		MethodCode.Label hopNext = a.newLabel();
		a.labelBinding(hopScan);
		emitNextFrame(a, s, r, hopEnd);
		a.iload(AWAITED);
		a.ifne(hopNext);
		emitSiteOf(a, s, r, hopNext);
		a.iload(SITE);
		a.istore(AWAITED);
		a.labelBinding(hopNext);
		a.iinc(INDEX, 1);
		a.goto_(hopScan);
		a.labelBinding(hopEnd);
		// " in HEAD[, awaited at FILE:LINE]"
		a.getstatic(systemErr.entry());
		a.ldc(cp.addString(UncaughtReport.ASYNC_PREFIX).entry());
		a.aload(HEAD);
		a.invokevirtual(concat.methodRefEntry());
		MethodCode.Label notAwaited = a.newLabel();
		a.iload(AWAITED);
		a.ifeq(notAwaited);
		a.ldc(cp.addString(UncaughtReport.AWAITED_AT).entry());
		a.invokevirtual(concat.methodRefEntry());
		emitPosition(a, s, r, AWAITED);
		a.labelBinding(notAwaited);
		a.invokevirtual(printlnStr.methodRefEntry());
		a.goto_(hopLoop);
		a.labelBinding(done);
		MethodCode.Label end = a.newBoundLabel();
		a.return_();
		// catch (Throwable any): the report line is out already; stop here.
		MethodCode.Label handler = a.newBoundLabel();
		a.pop();
		a.return_();
		a.exceptionCatch(start, end, handler, null);
		return new Built(cp.addUtf8(WHERE_METHOD), cp.addUtf8(WHERE_DESC), a);
	}

	/**
	 * Builds {@code _asyncCross(Throwable, String)V}: appends an async boundary's frame,
	 * crossed but not yet awaited, to the exception's trace.
	 * @param cp the class's pool
	 * @return the method
	 */
	static Built buildAsyncCross(ConstantPool cp) {
		Frames f = new Frames(cp);
		MethodCode a = new MethodCode();
		// trace = t.getStackTrace(); out = Arrays.copyOf(trace, trace.length + 1)
		a.aload(0);
		a.invokevirtual(f.getStackTrace.methodRefEntry());
		a.astore(2);
		a.aload(2);
		a.aload(2);
		a.arraylength();
		a.loadConstant(1);
		a.iadd();
		a.invokestatic(f.copyOf.entry());
		a.checkcast(f.frameArrayClass.entry());
		a.astore(3);
		// out[trace.length] = new StackTraceElement(ASYNC_FRAME_CLASS, "crossed", head,
		// -1)
		a.aload(3);
		a.aload(2);
		a.arraylength();
		emitBoundaryFrame(a, f, cp.addString(ASYNC_CROSSED), () -> a.aload(1));
		a.aastore();
		a.aload(0);
		a.aload(3);
		a.invokevirtual(f.setStackTrace.methodRefEntry());
		a.return_();
		return new Built(cp.addUtf8(ASYNC_CROSS_METHOD), cp.addUtf8(ASYNC_CROSS_DESC), a);
	}

	/**
	 * Builds {@code _asyncAwaited(Throwable, StackTraceElement[])V}: sets the exception's
	 * trace back to the one its future stored -- ending in the boundary its body crossed,
	 * whatever an earlier await of the same future appended since -- and, when it ends in
	 * a boundary, marks it awaited and appends the current thread's frames, the await's,
	 * after it.
	 * @param cp the class's pool
	 * @return the method
	 */
	static Built buildAsyncAwaited(ConstantPool cp) {
		Frames f = new Frames(cp);
		ClassConstant systemClass = cp.addClass(cp.addUtf8("java/lang/System"));
		MethodrefConstant arraycopy = method(cp, systemClass, "arraycopy",
				"(Ljava/lang/Object;ILjava/lang/Object;II)V");
		MethodrefConstant throwableInit = method(cp, f.throwableClass, "<init>", "()V");
		MethodrefConstant equals = method(cp, cp.addClass(cp.addUtf8("java/lang/String")), "equals",
				"(Ljava/lang/Object;)Z");
		MethodCode a = new MethodCode();
		MethodCode.Label skip = a.newLabel();
		// t.setStackTrace(stored): an earlier await's frames are a caught signal's
		a.aload(0);
		a.aload(1);
		a.invokevirtual(f.setStackTrace.methodRefEntry());
		// trace = t.getStackTrace(); n = trace.length; if (n == 0) return
		a.aload(0);
		a.invokevirtual(f.getStackTrace.methodRefEntry());
		a.astore(1);
		a.aload(1);
		a.arraylength();
		a.istore(2);
		a.iload(2);
		a.ifeq(skip);
		// last = trace[n - 1]; a crossed boundary?
		a.aload(1);
		a.iload(2);
		a.loadConstant(1);
		a.isub();
		a.aaload();
		a.astore(3);
		a.ldc(cp.addString(ASYNC_FRAME_CLASS).entry());
		a.aload(3);
		a.invokevirtual(f.getClassName.methodRefEntry());
		a.invokevirtual(equals.methodRefEntry());
		a.ifeq(skip);
		a.ldc(cp.addString(ASYNC_CROSSED).entry());
		a.aload(3);
		a.invokevirtual(f.getMethodName.methodRefEntry());
		a.invokevirtual(equals.methodRefEntry());
		a.ifeq(skip);
		// here = new Throwable().getStackTrace()
		a.new_(f.throwableClass.entry());
		a.dup();
		a.invokespecial(throwableInit.entry());
		a.invokevirtual(f.getStackTrace.methodRefEntry());
		a.astore(4);
		// out = Arrays.copyOf(trace, n + here.length)
		a.aload(1);
		a.iload(2);
		a.aload(4);
		a.arraylength();
		a.iadd();
		a.invokestatic(f.copyOf.entry());
		a.checkcast(f.frameArrayClass.entry());
		a.astore(5);
		// out[n - 1] = new StackTraceElement(ASYNC_FRAME_CLASS, "awaited", last.head, -1)
		a.aload(5);
		a.iload(2);
		a.loadConstant(1);
		a.isub();
		emitBoundaryFrame(a, f, cp.addString(ASYNC_AWAITED), () -> {
			a.aload(3);
			a.invokevirtual(f.getFileName.methodRefEntry());
		});
		a.aastore();
		// System.arraycopy(here, 0, out, n, here.length); t.setStackTrace(out)
		a.aload(4);
		a.loadConstant(0);
		a.aload(5);
		a.iload(2);
		a.aload(4);
		a.arraylength();
		a.invokestatic(arraycopy.entry());
		a.aload(0);
		a.aload(5);
		a.invokevirtual(f.setStackTrace.methodRefEntry());
		a.labelBinding(skip);
		a.return_();
		return new Built(cp.addUtf8(ASYNC_AWAITED_METHOD), cp.addUtf8(ASYNC_AWAITED_DESC), a);
	}

	/** The string constants {@code _where} compares frames against. */
	private record Strings(StringConstant self, StringConstant part, StringConstant boundary, StringConstant colon) {
	}

	/** The library methods {@code _where}'s repeated sequences call. */
	private record Refs(MethodrefConstant concat, MethodrefConstant equals, MethodrefConstant startsWith,
			MethodrefConstant charAt, MethodrefConstant length, MethodrefConstant valueOfInt,
			MethodrefConstant getClassName, MethodrefConstant getLineNumber) {
	}

	/** The trace-editing references the two boundary helpers share. */
	private static final class Frames {

		final ClassConstant throwableClass;

		final ClassConstant frameClass;

		final ClassConstant frameArrayClass;

		final MethodrefConstant getStackTrace;

		final MethodrefConstant setStackTrace;

		final MethodrefConstant copyOf;

		final MethodrefConstant frameInit;

		final MethodrefConstant getClassName;

		final MethodrefConstant getMethodName;

		final MethodrefConstant getFileName;

		final StringConstant boundaryClass;

		Frames(ConstantPool cp) {
			this.throwableClass = cp.addClass(cp.addUtf8("java/lang/Throwable"));
			this.frameClass = cp.addClass(cp.addUtf8("java/lang/StackTraceElement"));
			this.frameArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/StackTraceElement;"));
			this.getStackTrace = method(cp, this.throwableClass, "getStackTrace", "()[Ljava/lang/StackTraceElement;");
			this.setStackTrace = method(cp, this.throwableClass, "setStackTrace", "([Ljava/lang/StackTraceElement;)V");
			this.copyOf = method(cp, cp.addClass(cp.addUtf8("java/util/Arrays")), "copyOf",
					"([Ljava/lang/Object;I)[Ljava/lang/Object;");
			this.frameInit = method(cp, this.frameClass, "<init>",
					"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
			this.getClassName = method(cp, this.frameClass, "getClassName", "()Ljava/lang/String;");
			this.getMethodName = method(cp, this.frameClass, "getMethodName", "()Ljava/lang/String;");
			this.getFileName = method(cp, this.frameClass, "getFileName", "()Ljava/lang/String;");
			this.boundaryClass = cp.addString(ASYNC_FRAME_CLASS);
		}

	}

	/**
	 * Pushes {@code new StackTraceElement(ASYNC_FRAME_CLASS, state, head, -1)}, the head
	 * pushed by {@code head}.
	 */
	private static void emitBoundaryFrame(MethodCode a, Frames f, StringConstant state, Runnable head) {
		a.new_(f.frameClass.entry());
		a.dup();
		a.ldc(f.boundaryClass.entry());
		a.ldc(state.entry());
		head.run();
		a.loadConstant(-1);
		a.invokespecial(f.frameInit.entry());
	}

	/**
	 * Loads {@code trace[index]} into {@code FRAME} and its class into
	 * {@code FRAME_CLASS}, jumping to {@code end} past the last frame or at an async
	 * boundary.
	 */
	private static void emitNextFrame(MethodCode a, Strings s, Refs r, MethodCode.Label end) {
		a.iload(INDEX);
		a.iload(COUNT);
		a.if_icmpge(end);
		a.aload(TRACE);
		a.iload(INDEX);
		a.aaload();
		a.astore(FRAME);
		a.aload(FRAME);
		a.invokevirtual(r.getClassName().methodRefEntry());
		a.astore(FRAME_CLASS);
		a.aload(FRAME_CLASS);
		a.ldc(s.boundary().entry());
		a.invokevirtual(r.equals().methodRefEntry());
		a.ifne(end);
	}

	/**
	 * The site of {@code FRAME} into {@code SITE}, and its offset in the table into
	 * {@code SITE_BASE}; jumps to {@code none} for a frame of another class, a frame with
	 * no line, and a number past the table.
	 */
	private static void emitSiteOf(MethodCode a, Strings s, Refs r, MethodCode.Label none) {
		MethodCode.Label ours = a.newLabel();
		a.aload(FRAME_CLASS);
		a.ldc(s.self().entry());
		a.invokevirtual(r.equals().methodRefEntry());
		a.ifne(ours);
		a.aload(FRAME_CLASS);
		a.ldc(s.part().entry());
		a.invokevirtual(r.startsWith().methodRefEntry());
		a.ifeq(none);
		a.labelBinding(ours);
		a.aload(FRAME);
		a.invokevirtual(r.getLineNumber().methodRefEntry());
		a.istore(SITE);
		a.iload(SITE);
		a.ifle(none);
		// site k's three chars end at 3k
		a.iload(SITE);
		a.loadConstant(3);
		a.imul();
		a.aload(TABLE);
		a.invokevirtual(r.length().methodRefEntry());
		a.if_icmpgt(none);
		a.iload(SITE);
		a.loadConstant(1);
		a.isub();
		a.loadConstant(3);
		a.imul();
		a.istore(SITE_BASE);
	}

	/**
	 * Appends {@code FILE:LINE} of the site in {@code siteLocal} to the string on top of
	 * the stack.
	 */
	private static void emitPosition(MethodCode a, Strings s, Refs r, int siteLocal) {
		a.iload(siteLocal);
		a.loadConstant(1);
		a.isub();
		a.loadConstant(3);
		a.imul();
		a.istore(SITE_BASE);
		a.aload(NAMES);
		a.aload(TABLE);
		a.iload(SITE_BASE);
		a.invokevirtual(r.charAt().methodRefEntry());
		a.aaload();
		a.invokevirtual(r.concat().methodRefEntry());
		a.ldc(s.colon().entry());
		a.invokevirtual(r.concat().methodRefEntry());
		a.aload(TABLE);
		a.iload(SITE_BASE);
		a.loadConstant(1);
		a.iadd();
		a.invokevirtual(r.charAt().methodRefEntry());
		a.invokestatic(r.valueOfInt().entry());
		a.invokevirtual(r.concat().methodRefEntry());
	}

	/** Pushes the concatenation of the chunks, each a string constant. */
	private static void emitJoined(MethodCode a, ConstantPool cp, List<String> chunks, MethodrefConstant concat) {
		for (int i = 0; i < chunks.size(); i++) {
			a.ldc(cp.addString(chunks.get(i)).entry());
			if (i > 0) {
				a.invokevirtual(concat.methodRefEntry());
			}
		}
	}

	private static MethodrefConstant method(ConstantPool cp, ClassConstant owner, String name, String desc) {
		return cp.addMethodref(owner, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc)));
	}

}
