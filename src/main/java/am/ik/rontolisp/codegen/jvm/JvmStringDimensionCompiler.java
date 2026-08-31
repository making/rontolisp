package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the internal {@code %string-dimension} accessor: the array DIMENSION of a
 * string, which is the size a sized string type specifier compares against.
 *
 * <p>
 * It is {@code _length} with the fill-pointer branch removed, and that is the whole point
 * -- {@code length} of a fill-pointered character vector is the FILL POINTER, while
 * {@code (typep cv '(string n))} sizes itself by the dimension
 * ({@code .kb/declarations-type-checks.md}). The two string representations
 * {@link JvmStringpCompiler} recognizes:
 * <ul>
 * <li>a quote-framed {@code java.lang.String} -- its character count, through
 * {@code _scount}, so a supplementary code point counts as one character exactly as
 * {@code length} counts it;</li>
 * <li>an {@code ArrayList} whose slot-0 header is the length-4 character vector or the
 * length-7 string view -- {@code dims[0]}, already a boxed {@code Long}. That arm is
 * emitted only when the array runtime is, so an array-free program stays
 * byte-identical.</li>
 * </ul>
 *
 * <p>
 * Every call site is guarded by {@code stringp} (it is how a type test reaches its string
 * arm at all), so no other representation can arrive.
 */
final class JvmStringDimensionCompiler {

	private JvmStringDimensionCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("%string-dimension expects 1 argument, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.emitSharedCall(ctx, className, "_strDim", 1, helper -> emitBody(helper, className));
	}

	private static void emitBody(JvmLispCompiler.Ctx ctx, String className) {
		MethodrefConstant charCount = JvmEmitHelper.selfMethod(ctx, className,
				JvmStringIndexRuntimeBuilder.COUNT_METHOD, JvmStringIndexRuntimeBuilder.COUNT_DESC);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(0);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.stringClass.index());
		int ifNotString = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(0);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(charCount.index());
		ctx.emit(Opcode.I2L);
		JvmEmitHelper.boxLong(ctx);
		int gotoEnd = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotString, ctx.code.size());
		if (ctx.usesArrays) {
			// The mutable character vector / string view: dims[0] of the slot-0 header,
			// already a boxed Long.
			ClassConstant arrayListClass = ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList"));
			MethodrefConstant alGet = ctx.cp.addMethodref(arrayListClass,
					ctx.cp.addNameAndType(ctx.cp.addUtf8("get"), ctx.cp.addUtf8("(I)Ljava/lang/Object;")));
			ctx.emit(Opcode.ALOAD);
			ctx.emit(0);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(arrayListClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(alGet.index());
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.AALOAD);
			// header[0] is the dims Object[]; its slot 0 is the boxed Long dimension.
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.AALOAD);
		}
		else {
			// No array runtime, so no character vector can exist: the only string shape
			// is the immutable one handled above.
			ctx.emit(Opcode.ACONST_NULL);
		}
		JvmEmitHelper.patchBranch(ctx, gotoEnd, ctx.code.size());
	}

}
