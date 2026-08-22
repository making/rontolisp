package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code %read-sequence-packed} / {@code %write-sequence-packed} primitives
 * the {@code read-sequence} / {@code write-sequence} expansions call first
 * ({@code .kb/binary-sequence-io.md}): a call to the {@code _readSeqPacked} /
 * {@code _writeSeqPacked} runtime helper when the program emits it, else a plain
 * {@code nil} -- the "declined" answer, which sends the expansion down its element loop
 * exactly as before the primitive existed.
 */
final class JvmSequencePackedCompiler {

	private JvmSequencePackedCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		boolean read = LispNames.READ_SEQUENCE_PACKED.equals(((LispSymbol) parts.get(0)).name());
		if (parts.size() != 5) {
			throw new UnsupportedOperationException(
					parts.get(0).print() + " expects (seq stream start end), got " + (parts.size() - 1) + " arguments");
		}
		if (!ctx.usesPackedSequenceIo) {
			ctx.emit(Opcode.ACONST_NULL);
			return;
		}
		// The stream designator, like read-byte: an explicit nil means the current
		// *standard-input* / *standard-output*, whose default the helper reads as the
		// process stream.
		LispVal stream = read ? JvmStringStreamCompiler.inputStreamArg(ctx, parts.get(2))
				: JvmStringStreamCompiler.streamArg(ctx, parts.get(2));
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		// A bulk READ writes a packed float array's storage in place, behind the element
		// setter's back, and a bulk WRITE reads it behind every reader's. Under --gpu the
		// device may hold a resident -- or the only -- copy of that array, so the call
		// site reports the sequence to _gpuWritten / _gpuMaterialize BEFORE the helper
		// runs (.kb/gpu.md, "Device residency").
		Map<String, MethodrefConstant> gpuOps = ctx.gpuOps;
		if (gpuOps != null) {
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(Objects
				.requireNonNull(gpuOps.get(read ? JvmGpuRuntimeBuilder.WRITTEN : JvmGpuRuntimeBuilder.MATERIALIZE))
				.index());
		}
		JvmExprCompiler.compileExpr(stream != null ? stream : parts.get(2), ctx, className);
		JvmExprCompiler.compileExpr(parts.get(3), ctx, className);
		JvmExprCompiler.compileExpr(parts.get(4), ctx, className);
		Utf8Constant nameUtf8 = ctx.cp
			.addUtf8(read ? JvmIoRuntimeBuilder.READ_SEQ_PACKED_METHOD : JvmIoRuntimeBuilder.WRITE_SEQ_PACKED_METHOD);
		Utf8Constant descUtf8 = ctx.cp
			.addUtf8(read ? JvmIoRuntimeBuilder.READ_SEQ_PACKED_DESC : JvmIoRuntimeBuilder.WRITE_SEQ_PACKED_DESC);
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
