package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

import org.jspecify.annotations.Nullable;

/**
 * Compiles {@code rontolisp:float16-bits}/{@code bits-float16} (the JDK 20+
 * {@code java.lang.Float} intrinsics, inline -- no bit trick needed on the JVM, unlike
 * WASM's {@link am.ik.rontolisp.codegen.wasm.WasmFloat16Compiler}) and
 * {@code rontolisp:widen-float-bits}/{@code narrow-float-bits} (a call into
 * {@link JvmFloat16RuntimeBuilder}'s self-referencing helpers). See {@code .todo/671}.
 */
final class JvmFloat16Compiler {

	private JvmFloat16Compiler() {
	}

	/** {@code (float16-bits x)}: the real narrowed to its binary16 bits, as a fixnum. */
	static void compileFloat16Bits(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.unboxDouble(ctx);
		ctx.emit(Opcode.D2F);
		invokeStatic(ctx, "java/lang/Float", "floatToFloat16", "(F)S");
		// The (S)-typed result is an int on the stack, sign-extended -- mask to the
		// unsigned 16-bit pattern. iconst cannot push 0xFFFF directly (SIPUSH is a
		// signed 16-bit immediate); -1 >>> 16 is exactly 0x0000FFFF.
		JvmEmitHelper.emitIntConst(ctx, -1);
		JvmEmitHelper.emitIntConst(ctx, 16);
		ctx.emit(Opcode.IUSHR);
		ctx.emit(Opcode.IAND);
		ctx.emit(Opcode.I2L);
		JvmEmitHelper.boxLong(ctx);
	}

	/** {@code (bits-float16 bits)}: the real a binary16 bit pattern encodes. */
	static void compileBitsFloat16(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "longValue", "()J").index());
		ctx.emit(Opcode.L2I);
		invokeStatic(ctx, "java/lang/Float", "float16ToFloat", "(S)F");
		ctx.emit(Opcode.F2D);
		JvmEmitHelper.boxDouble(ctx);
	}

	/** {@code (widen-float-bits bits format dst &key (start 0))}. */
	static void compileWiden(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileWidenOrNarrow(cons, ctx, className, JvmFloat16RuntimeBuilder.WIDEN, JvmFloat16RuntimeBuilder.WIDEN_DESC);
	}

	/** {@code (narrow-float-bits src format dst &key (start 0))}. */
	static void compileNarrow(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileWidenOrNarrow(cons, ctx, className, JvmFloat16RuntimeBuilder.NARROW,
				JvmFloat16RuntimeBuilder.NARROW_DESC);
	}

	private static void compileWidenOrNarrow(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String name,
			String desc) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmExprCompiler.compileExpr(args.get(3), ctx, className);
		LispVal startExpr = findKeywordValue(args, LispNames.START_KEYWORD, 4);
		if (startExpr != null) {
			JvmExprCompiler.compileExpr(startExpr, ctx, className);
			JvmEmitHelper.toBigInteger(ctx);
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "longValue", "()J").index());
			ctx.emit(Opcode.L2I);
		}
		else {
			JvmEmitHelper.emitIntConst(ctx, 0);
		}
		ClassConstant selfClass = ctx.cp.addClass(ctx.cp.addUtf8(className));
		MethodrefConstant helper = ctx.cp.addMethodref(selfClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(helper.index());
	}

	private static @Nullable LispVal findKeywordValue(List<LispVal> args, String keyword, int from) {
		for (int i = from; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof LispSymbol kw && keyword.equals(kw.name())) {
				return args.get(i + 1);
			}
		}
		return null;
	}

	private static void invokeStatic(JvmLispCompiler.Ctx ctx, String owner, String name, String desc) {
		ConstantPool.ClassConstant cls = ctx.cp.addClass(ctx.cp.addUtf8(owner));
		ConstantPool.MethodrefConstant ref = ctx.cp.addMethodref(cls,
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8(desc)));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
