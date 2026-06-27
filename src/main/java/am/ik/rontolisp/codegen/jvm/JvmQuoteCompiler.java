package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code quote} special form.
 */
final class JvmQuoteCompiler {

	private JvmQuoteCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal quoted = ((LispCons) cons.cdr()).car();
		compileQuotedVal(quoted, ctx, className);
	}

	/**
	 * Emits the construction of a self-evaluating array literal ({@code #(...)}). Shared
	 * by the {@code quote} path and the bare-literal path in {@link JvmExprCompiler}.
	 * @param array the literal array
	 * @param ctx the compilation context
	 * @param className the enclosing class name
	 */
	static void compileLiteralArray(LispArray array, JvmLispCompiler.Ctx ctx, String className) {
		compileQuotedArray(array, ctx, className);
	}

	private static void compileQuotedVal(LispVal val, JvmLispCompiler.Ctx ctx, String className) {
		switch (val) {
			case LispInteger i -> JvmEmitHelper.compileLong(i.value(), ctx);
			case LispBigInteger b -> JvmEmitHelper.compileBigInteger(b.value(), ctx);
			case am.ik.rontolisp.LispRatio r -> JvmEmitHelper.compileRatio(r, ctx);
			case LispDouble d -> JvmEmitHelper.compileDouble(d.value(), ctx);
			case LispNil ignored -> ctx.emit(Opcode.ACONST_NULL);
			case LispTrue ignored -> JvmEmitHelper.compileTrue(ctx);
			case LispString s -> JvmEmitHelper.compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> JvmEmitHelper.compileStringLiteral(sym.name(), ctx);
			case LispCons cons -> compileQuotedCons(cons, ctx, className);
			case LispArray array -> compileQuotedArray(array, ctx, className);
			default -> throw new UnsupportedOperationException("Cannot quote: " + val.print());
		}
	}

	// Builds the runtime array representation (a java.util.ArrayList whose slot 0 is the
	// column count and slots 1.. are the row-major elements), matching
	// JvmArrayRuntimeBuilder. The list reference is kept on the stack and DUPed for each
	// add, so the operand stack stays shallow regardless of the element count.
	private static void compileQuotedArray(LispArray array, JvmLispCompiler.Ctx ctx, String className) {
		int[] dims = array.dimensions();
		long cols = dims.length >= 2 ? dims[dims.length - 1] : 0;
		ClassConstant arrayListClass = ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList"));
		MethodrefConstant alInit = ctx.cp.addMethodref(arrayListClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("()V")));
		MethodrefConstant alAdd = ctx.cp.addMethodref(arrayListClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("add"), ctx.cp.addUtf8("(Ljava/lang/Object;)Z")));
		ctx.emit(Opcode.NEW);
		ctx.emitU2(arrayListClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.INVOKESPECIAL);
		ctx.emitU2(alInit.index());
		addElement(ctx, alAdd, () -> JvmEmitHelper.compileLong(cols, ctx));
		for (LispVal element : array.data()) {
			LispVal value = (element == null) ? LispNil.INSTANCE : element;
			addElement(ctx, alAdd, () -> compileQuotedVal(value, ctx, className));
		}
	}

	// Assumes the ArrayList is on top of the stack; appends one element (pushed by
	// pushValue) and leaves the list on the stack.
	private static void addElement(JvmLispCompiler.Ctx ctx, MethodrefConstant alAdd, Runnable pushValue) {
		ctx.emit(Opcode.DUP);
		pushValue.run();
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(alAdd.index());
		ctx.emit(Opcode.POP);
	}

	private static void compileQuotedCons(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// Walk the cdr spine and build the list tail-first through a temp slot, like
		// JvmListCompiler: recursing through the cdr would grow the operand stack
		// linearly with the list length and overflow the fixed max_stack. Only nested
		// sublists recurse (via the car), so the stack depth is bounded by the tree
		// depth, not the list length.
		List<LispVal> cars = new ArrayList<>();
		LispVal tail = cons;
		while (tail instanceof LispCons cell) {
			cars.add(cell.car());
			tail = cell.cdr();
		}
		compileQuotedVal(tail, ctx, className);
		for (int i = cars.size() - 1; i >= 0; i--) {
			int tempSlot = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.ICONST_2);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			compileQuotedVal(cars.get(i), ctx, className);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(tempSlot);
			ctx.emit(Opcode.AASTORE);
		}
	}

}
