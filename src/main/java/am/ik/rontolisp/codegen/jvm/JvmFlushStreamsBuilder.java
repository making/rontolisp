package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

/**
 * Builds {@code _flushStreams()V}: flush every {@code java.io.Flushable} left in the
 * {@code _streams} table -- the {@code BufferedWriter} / {@code BufferedOutputStream} of
 * an output file the program never closed. C's {@code exit} does this for its stdio
 * buffers and both wasm backends need nothing ({@code fd_write} writes through), so
 * without it the JVM and the interpreter were the two backends where such a file ended up
 * empty.
 *
 * <p>
 * Called on every way out of the program: {@code main}'s return, {@code %host-exit}
 * before its {@code System.exit} ({@link JvmExitCompiler}) and the uncaught-condition
 * handler before its rethrow ({@link JvmUncaughtHandler}). A flush that fails is skipped,
 * as {@code exit} skips it, so the other streams still get theirs. Emitted, and called,
 * only for a program that names {@code open}, so every other artifact keeps its bytes.
 */
final class JvmFlushStreamsBuilder {

	static final String METHOD = "_flushStreams";

	static final String DESC = "()V";

	private JvmFlushStreamsBuilder() {
	}

	static JvmIoRuntimeBuilder.IoMethod build(ConstantPool cp, ClassEntry thisClass) {
		FieldRefEntry streams = cp.fieldRef(thisClass, JvmIoRuntimeBuilder.STREAMS_FIELD,
				JvmIoRuntimeBuilder.STREAMS_DESC);
		ClassEntry flushable = cp.classEntry("java/io/Flushable");
		InterfaceMethodRefEntry flush = cp.interfaceMethodRef(flushable, "flush", "()V");
		ClassEntry ioException = cp.classEntry("java/io/IOException");
		// Slots: 0=table, 1=index, 2=entry
		MethodCode code = new MethodCode();
		// Object[] table = _streams; if (table == null) return;
		code.getstatic(streams);
		code.astore(0);
		code.aload(0);
		MethodCode.Label ifTable = code.newLabel();
		code.ifnonnull(ifTable);
		code.return_();
		code.labelBinding(ifTable);
		// for (int i = 0; i < table.length; i++)
		code.iconst_0();
		code.istore(1);
		MethodCode.Label loop = code.newBoundLabel();
		code.iload(1);
		code.aload(0);
		code.arraylength();
		MethodCode.Label ifDone = code.newLabel();
		code.if_icmpge(ifDone);
		// Object entry = table[i]; if (entry instanceof Flushable)
		code.aload(0);
		code.iload(1);
		code.aaload();
		code.astore(2);
		code.aload(2);
		code.instanceOf(flushable);
		MethodCode.Label ifNotFlushable = code.newLabel();
		code.ifeq(ifNotFlushable);
		// try { ((Flushable) entry).flush(); } catch (IOException e) { }
		MethodCode.Label tryStart = code.newBoundLabel();
		code.aload(2);
		code.checkcast(flushable);
		code.invokeinterface(flush);
		MethodCode.Label tryEnd = code.newBoundLabel();
		MethodCode.Label gotoNext = code.newLabel();
		code.goto_(gotoNext);
		MethodCode.Label handler = code.newBoundLabel();
		code.pop();
		code.labelBinding(ifNotFlushable);
		code.labelBinding(gotoNext);
		code.iinc(1, 1);
		code.goto_(loop);
		code.labelBinding(ifDone);
		code.return_();
		code.exceptionCatch(tryStart, tryEnd, handler, ioException);
		return new JvmIoRuntimeBuilder.IoMethod(cp.utf8Entry(METHOD), cp.utf8Entry(DESC), code);
	}

}
