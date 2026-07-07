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

	/**
	 * Emits a packed float-array literal ({@code #f(...)}) as a bare {@code double[]}
	 * with an embedded dimension header: {@code [rank, dim_0, ..., dim_{rank-1}, e_0,
	 * ..., e_{total-1}]} (rank and dims stored as doubles, exact for realistic sizes).
	 * The data offset is {@code 1 + rank}. This is the native packed representation used
	 * across the whole JVM backend (a {@code double[]} is disjoint from the
	 * {@code Object[]}/ {@code ArrayList} shapes of conses, function refs and general
	 * arrays, so no discriminator changes are needed). The list reference is kept on the
	 * stack and {@code DUP}ed for each store, so the operand stack stays shallow
	 * regardless of the element count.
	 * @param fa the packed literal
	 * @param ctx the compilation context
	 */
	static void compilePackedLiteral(am.ik.rontolisp.LispFloatArray fa, JvmLispCompiler.Ctx ctx) {
		double[] data = fa.data();
		int[] dims = fa.dims();
		int rank = dims.length;
		int off = 1 + rank;
		int len = off + data.length;
		JvmEmitHelper.emitIntConst(ctx, len);
		ctx.emit(Opcode.NEWARRAY);
		ctx.emit(7); // T_DOUBLE
		emitRawDoubleStore(ctx, 0, rank);
		for (int d = 0; d < rank; d++) {
			emitRawDoubleStore(ctx, 1 + d, dims[d]);
		}
		for (int f = 0; f < data.length; f++) {
			emitRawDoubleStore(ctx, off + f, data[f]);
		}
	}

	// Assumes the double[] is on top of the stack; stores value at index (DUP; index;
	// raw double; DASTORE), leaving the array on the stack.
	private static void emitRawDoubleStore(JvmLispCompiler.Ctx ctx, int index, double value) {
		ctx.emit(Opcode.DUP);
		JvmEmitHelper.emitIntConst(ctx, index);
		emitRawDouble(ctx, value);
		ctx.emit(Opcode.DASTORE);
	}

	// Pushes an unboxed double constant (no Double.valueOf), the raw value to store into
	// a double[].
	private static void emitRawDouble(JvmLispCompiler.Ctx ctx, double value) {
		if (value == 0.0 && Double.doubleToRawLongBits(value) == 0L) {
			ctx.emit(Opcode.DCONST_0);
		}
		else if (value == 1.0) {
			ctx.emit(Opcode.DCONST_1);
		}
		else {
			am.ik.jvm.ConstantPool.DoubleConstant dc = ctx.cp.addDouble(value);
			ctx.emit(Opcode.LDC2_W);
			ctx.emitU2(dc.index());
		}
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
			case am.ik.rontolisp.LispChar c -> JvmEmitHelper.compileCharLiteral(c.codePoint(), ctx);
			case LispSymbol sym -> JvmEmitHelper.compileStringLiteral(sym.name(), ctx);
			case LispCons cons -> compileQuotedCons(cons, ctx, className);
			case LispArray array -> compileQuotedArray(array, ctx, className);
			// A packed #f(...) literal compiles to a native double[] with a dimension
			// header (the packed representation), disjoint from the general array.
			case am.ik.rontolisp.LispFloatArray fa -> compilePackedLiteral(fa, ctx);
			default -> throw new UnsupportedOperationException("Cannot quote: " + val.print());
		}
	}

	// Builds the runtime array representation (a java.util.ArrayList whose slot 0 is
	// the {dims, fillPointer, adjustable} header Object[] and slots 1.. are the
	// row-major elements), matching JvmArrayRuntimeBuilder. A literal array never has a
	// fill pointer and is not adjustable, so header slots 1 and 2 stay null. The list
	// reference is kept on the stack and DUPed for each add, so the operand stack stays
	// shallow regardless of the element count.
	private static void compileQuotedArray(LispArray array, JvmLispCompiler.Ctx ctx, String className) {
		int[] dims = array.dimensions();
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
		addElement(ctx, alAdd, () -> {
			JvmEmitHelper.emitIntConst(ctx, 3);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			JvmEmitHelper.emitIntConst(ctx, 0);
			JvmEmitHelper.emitIntConst(ctx, dims.length);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			for (int d = 0; d < dims.length; d++) {
				ctx.emit(Opcode.DUP);
				JvmEmitHelper.emitIntConst(ctx, d);
				JvmEmitHelper.compileLong(dims[d], ctx);
				ctx.emit(Opcode.AASTORE);
			}
			ctx.emit(Opcode.AASTORE);
		});
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
