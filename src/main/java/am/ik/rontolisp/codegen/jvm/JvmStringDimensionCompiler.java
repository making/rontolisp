package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

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
		MethodRefEntry charCount = JvmEmitHelper.selfMethod(ctx, className, JvmStringIndexRuntimeBuilder.COUNT_METHOD,
				JvmStringIndexRuntimeBuilder.COUNT_DESC);
		ctx.body.aload(0).instanceOf(ctx.stringClass);
		MethodCode.Label ifNotString = ctx.body.newLabel();
		ctx.body.ifeq(ifNotString);
		ctx.body.aload(0).checkcast(ctx.stringClass).invokestatic(charCount).i2l();
		JvmEmitHelper.boxLong(ctx);
		MethodCode.Label gotoEnd = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd);
		ctx.body.labelBinding(ifNotString);
		if (ctx.usesArrays) {
			// The mutable character vector / string view: dims[0] of the slot-0 header,
			// already a boxed Long.
			ClassEntry arrayListClass = ctx.cp.classEntry("java/util/ArrayList");
			MethodRefEntry alGet = ctx.cp.methodRef(arrayListClass, "get", "(I)Ljava/lang/Object;");
			ctx.body.aload(0).checkcast(arrayListClass).iconst_0();
			ctx.body.invokevirtual(alGet).checkcast(ctx.objectArrayClass);
			ctx.body.iconst_0().aaload();
			// header[0] is the dims Object[]; its slot 0 is the boxed Long dimension.
			ctx.body.checkcast(ctx.objectArrayClass).iconst_0().aaload();
		}
		else {
			// No array runtime, so no character vector can exist: the only string shape
			// is the immutable one handled above.
			ctx.body.aconst_null();
		}
		ctx.body.labelBinding(gotoEnd);
	}

}
