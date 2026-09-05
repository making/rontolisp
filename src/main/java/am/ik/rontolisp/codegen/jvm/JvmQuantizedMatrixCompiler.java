package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * Compiles the {@code rontolisp:quantized-matrix} primitives to calls into the
 * {@code _qm*} helpers ({@link JvmQuantizedMatrixRuntimeBuilder}), which exist only in a
 * program that can BUILD a matrix ({@code JvmLispCompiler.Ctx#usesQuantized}: it names
 * {@code rontolisp:quantize} or {@code rontolisp:make-quantized-matrix}). In every other
 * program no matrix can exist, so {@code quantized-matrix-p} compiles to its argument
 * evaluated for effect and {@code nil}, and the four operations that need one compile to
 * a call-time signal -- the shape the wasm-GC backend gives every one of them. That is
 * what lets {@code vec.lisp}'s integer-dot GEMV arm and {@code gguf.lisp}'s Q8_0 arm
 * compile in a program that never reaches them without dragging the helpers in.
 */
final class JvmQuantizedMatrixCompiler {

	private JvmQuantizedMatrixCompiler() {
	}

	/** Whether the member is one of the six primitives this class compiles. */
	static boolean handles(String member) {
		return LispNames.QUANTIZE.equals(member) || LispNames.DEQUANTIZE.equals(member)
				|| LispNames.MAKE_QUANTIZED_MATRIX.equals(member) || LispNames.QUANTIZED_MATRIX_P.equals(member)
				|| LispNames.QUANTIZED_QUANT_INTERNAL.equals(member)
				|| LispNames.QUANTIZED_SCALE_INTERNAL.equals(member);
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (LispNames.QUANTIZED_MATRIX_P.equals(member)) {
			requireArity(args, 1, member);
			if (!ctx.usesQuantized) {
				JvmExprCompiler.compileExpr(
						new LispCons(new LispSymbol(LispNames.PROGN),
								new LispCons(args.get(1), new LispCons(LispNil.INSTANCE, LispNil.INSTANCE))),
						ctx, className);
				return;
			}
			call(args, ctx, className, JvmQuantizedMatrixRuntimeBuilder.PREDICATE,
					JvmQuantizedMatrixRuntimeBuilder.UNARY_DESC);
			return;
		}
		int arity = LispNames.QUANTIZED_QUANT_INTERNAL.equals(member)
				|| LispNames.QUANTIZED_SCALE_INTERNAL.equals(member) ? 3 : 2;
		requireArity(args, arity, member);
		if (!ctx.usesQuantized) {
			// No matrix can exist in this program: the arguments run, then the same
			// sentence the wasm-GC backend gives, at the call.
			JvmExprCompiler.compileExpr(LispMacroExpander.expandUnsupportedCall(cons,
					"rontolisp:" + member.toLowerCase(java.util.Locale.ROOT)
							+ ": no quantized matrix can exist in this program (nothing in it calls"
							+ " rontolisp:quantize or rontolisp:make-quantized-matrix)"),
					ctx, className);
			return;
		}
		String helper = switch (member) {
			case LispNames.QUANTIZE -> JvmQuantizedMatrixRuntimeBuilder.QUANTIZE;
			case LispNames.DEQUANTIZE -> JvmQuantizedMatrixRuntimeBuilder.DEQUANTIZE;
			case LispNames.MAKE_QUANTIZED_MATRIX -> JvmQuantizedMatrixRuntimeBuilder.MAKE;
			case LispNames.QUANTIZED_QUANT_INTERNAL -> JvmQuantizedMatrixRuntimeBuilder.QUANT;
			case LispNames.QUANTIZED_SCALE_INTERNAL -> JvmQuantizedMatrixRuntimeBuilder.SCALE;
			default -> throw new IllegalStateException(member);
		};
		call(args, ctx, className, helper, arity == 3 ? JvmQuantizedMatrixRuntimeBuilder.TERNARY_DESC
				: JvmQuantizedMatrixRuntimeBuilder.BINARY_DESC);
	}

	private static void call(List<LispVal> args, JvmLispCompiler.Ctx ctx, String className, String helper,
			String desc) {
		for (int i = 1; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
		}
		ClassConstant selfClass = ctx.cp.addClass(ctx.cp.addUtf8(className));
		MethodrefConstant ref = ctx.cp.addMethodref(selfClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8(helper), ctx.cp.addUtf8(desc)));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

	private static void requireArity(List<LispVal> args, int arity, String member) {
		if (args.size() != arity + 1) {
			throw new UnsupportedOperationException("rontolisp:" + member.toLowerCase(java.util.Locale.ROOT)
					+ " expects " + arity + " argument" + (arity == 1 ? "" : "s") + ", got " + (args.size() - 1));
		}
	}

}
