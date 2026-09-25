package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

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

	static JvmIoRuntimeBuilder.IoMethod build(ConstantPool cp, ClassConstant thisClass) {
		FieldrefConstant streams = cp.addFieldref(thisClass, cp.addNameAndType(
				cp.addUtf8(JvmIoRuntimeBuilder.STREAMS_FIELD), cp.addUtf8(JvmIoRuntimeBuilder.STREAMS_DESC)));
		ClassConstant flushable = cp.addClass(cp.addUtf8("java/io/Flushable"));
		MethodrefConstant flush = cp.addInterfaceMethodref(flushable,
				cp.addNameAndType(cp.addUtf8("flush"), cp.addUtf8("()V")));
		ClassConstant ioException = cp.addClass(cp.addUtf8("java/io/IOException"));
		// Slots: 0=table, 1=index, 2=entry
		List<Integer> code = new ArrayList<>();
		// Object[] table = _streams; if (table == null) return;
		code.add(Opcode.GETSTATIC);
		emitU2(code, streams.index());
		code.add(Opcode.ASTORE_0);
		code.add(Opcode.ALOAD_0);
		int ifTablePos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.RETURN);
		patchBranch(code, ifTablePos, code.size());
		// for (int i = 0; i < table.length; i++)
		code.add(Opcode.ICONST_0);
		code.add(Opcode.ISTORE_1);
		int loop = code.size();
		code.add(Opcode.ILOAD_1);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ARRAYLENGTH);
		int ifDonePos = code.size();
		code.add(Opcode.IF_ICMPGE);
		emitU2(code, 0);
		// Object entry = table[i]; if (entry instanceof Flushable)
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ILOAD_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ASTORE_2);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, flushable.index());
		int ifNotFlushablePos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// try { ((Flushable) entry).flush(); } catch (IOException e) { }
		int tryStart = code.size();
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.CHECKCAST);
		emitU2(code, flushable.index());
		code.add(Opcode.INVOKEINTERFACE);
		emitU2(code, flush.index());
		code.add(1);
		code.add(0);
		int tryEnd = code.size();
		int gotoNextPos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		int handler = code.size();
		code.add(Opcode.POP);
		int next = code.size();
		patchBranch(code, ifNotFlushablePos, next);
		patchBranch(code, gotoNextPos, next);
		code.add(Opcode.IINC);
		code.add(1);
		code.add(1);
		int gotoLoopPos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, gotoLoopPos, loop);
		patchBranch(code, ifDonePos, code.size());
		code.add(Opcode.RETURN);
		List<ByteCodeWriter.ExceptionTableEntry> handlers = List
			.of(new ByteCodeWriter.ExceptionTableEntry(tryStart, tryEnd, handler, ioException.index()));
		return new JvmIoRuntimeBuilder.IoMethod(cp.addUtf8(METHOD), cp.addUtf8(DESC), 2, 3, code, 0, handlers);
	}

	private static void emitU2(List<Integer> code, int value) {
		// The shared writer keeps a pool index past 65535 whole
		// (JvmRuntimeBuilder.emitU2).
		JvmRuntimeBuilder.emitU2(code, value);
	}

	private static void patchBranch(List<Integer> code, int branchPos, int target) {
		int offset = target - branchPos;
		code.set(branchPos + 1, (offset >> 8) & 0xFF);
		code.set(branchPos + 2, offset & 0xFF);
	}

}
