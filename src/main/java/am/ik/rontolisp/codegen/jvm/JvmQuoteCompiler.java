package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
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
	 * Emits a packed integer-vector literal (ironclad's {@code #N@(...)}, or a macro-time
	 * {@code LispIntVector} value) as a bare {@code long[]} with a width header:
	 * {@code [width, e_0, ..., e_{n-1}]} -- the native packed representation
	 * ({@link JvmIntArrayRuntimeBuilder}). The elements arrive pre-masked from the
	 * reader. The array reference is kept on the stack and {@code DUP}ed for each store,
	 * so the operand stack stays shallow regardless of the element count.
	 * @param iv the packed integer-vector literal
	 * @param ctx the compilation context
	 * @param className the enclosing class name
	 */
	static void compileLiteralIntVector(am.ik.rontolisp.LispIntVector iv, JvmLispCompiler.Ctx ctx, String className) {
		long[] data = iv.data();
		JvmEmitHelper.emitIntConst(ctx, 1 + data.length);
		ctx.emit(Opcode.NEWARRAY);
		ctx.emit(11); // T_LONG
		emitRawLongStore(ctx, 0, iv.width());
		for (int i = 0; i < data.length; i++) {
			emitRawLongStore(ctx, 1 + i, data[i]);
		}
	}

	// Assumes the long[] is on top of the stack; stores value at index (DUP; index;
	// raw long; LASTORE), leaving the array on the stack.
	private static void emitRawLongStore(JvmLispCompiler.Ctx ctx, int index, long value) {
		ctx.emit(Opcode.DUP);
		JvmEmitHelper.emitIntConst(ctx, index);
		if (value == 0L) {
			ctx.emit(Opcode.LCONST_0);
		}
		else if (value == 1L) {
			ctx.emit(Opcode.LCONST_1);
		}
		else {
			am.ik.jvm.ConstantPool.LongConstant lc = ctx.cp.addLong(value);
			ctx.emit(Opcode.LDC2_W);
			ctx.emitU2(lc.index());
		}
		ctx.emit(Opcode.LASTORE);
	}

	/**
	 * Emits a packed float-array literal ({@code #d(...)}) as a bare {@code double[]}
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
	static void compilePackedLiteral(am.ik.rontolisp.LispDoubleFloatArray fa, JvmLispCompiler.Ctx ctx) {
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

	/**
	 * Emits a packed single-float array literal ({@code #f(...)}) as a bare
	 * {@code float[]} with an embedded dimension header: {@code [rank, dim_0, ...,
	 * dim_{rank-1}, e_0, ..., e_{total-1}]} (rank and dims stored as floats, exact for
	 * realistic sizes). The data offset is {@code 1 + rank}. This is the native
	 * single-float packed representation (a {@code float[]} is disjoint from the {@code
	 * Object[]}/{@code ArrayList}/{@code double[]} shapes, so no discriminator changes
	 * are needed). Each element float is emitted as its widening {@code double} constant
	 * then narrowed with {@code d2f} at load time (an exact round-trip), so no float
	 * constant pool entry is needed.
	 * @param fa the packed literal
	 * @param ctx the compilation context
	 */
	static void compileSinglePackedLiteral(am.ik.rontolisp.LispSingleFloatArray fa, JvmLispCompiler.Ctx ctx) {
		float[] data = fa.data();
		int[] dims = fa.dims();
		int rank = dims.length;
		int off = 1 + rank;
		int len = off + data.length;
		JvmEmitHelper.emitIntConst(ctx, len);
		ctx.emit(Opcode.NEWARRAY);
		ctx.emit(6); // T_FLOAT
		emitRawFloatHeaderStore(ctx, 0, rank);
		for (int d = 0; d < rank; d++) {
			emitRawFloatHeaderStore(ctx, 1 + d, dims[d]);
		}
		for (int f = 0; f < data.length; f++) {
			emitRawFloatDataStore(ctx, off + f, data[f]);
		}
	}

	// Stores an int header value (rank/dim) as a float at index into the float[] on top
	// of
	// the stack (DUP; index; int; I2F; FASTORE), leaving the array on the stack.
	private static void emitRawFloatHeaderStore(JvmLispCompiler.Ctx ctx, int index, int value) {
		ctx.emit(Opcode.DUP);
		JvmEmitHelper.emitIntConst(ctx, index);
		JvmEmitHelper.emitIntConst(ctx, value);
		ctx.emit(Opcode.I2F);
		ctx.emit(Opcode.FASTORE);
	}

	// Stores a float data value at index into the float[] on top of the stack, emitting
	// the
	// value as its widening double constant narrowed back with D2F (exact) so no float
	// constant pool entry is needed (DUP; index; double; D2F; FASTORE).
	private static void emitRawFloatDataStore(JvmLispCompiler.Ctx ctx, int index, float value) {
		ctx.emit(Opcode.DUP);
		JvmEmitHelper.emitIntConst(ctx, index);
		emitRawDouble(ctx, value);
		ctx.emit(Opcode.D2F);
		ctx.emit(Opcode.FASTORE);
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
			case LispString s -> JvmEmitHelper.compileStringLiteral(s.literal(), ctx);
			case am.ik.rontolisp.LispChar c -> JvmEmitHelper.compileCharLiteral(c.codePoint(), ctx);
			case LispSymbol sym -> JvmEmitHelper.compileStringLiteral(sym.name(), ctx);
			case LispCons cons -> compileQuotedCons(cons, ctx, className);
			case LispArray array -> compileQuotedArray(array, ctx, className);
			// An instance inside quoted data (a #S(...) literal) builds the same
			// Object[]{layout, slots...} %obj-new does; the layout constant is interned
			// once per tag.
			case am.ik.rontolisp.LispInstance inst -> compileQuotedInstance(inst, ctx, className);
			// A packed #d(...) double-float literal compiles to a native double[] with a
			// dimension header (the packed representation), disjoint from the general
			// array.
			case am.ik.rontolisp.LispDoubleFloatArray fa -> compilePackedLiteral(fa, ctx);
			// A packed #f(...) single-float literal compiles to a native float[] with a
			// dimension header (the single-float packed representation), disjoint from
			// the
			// general array and from the double[] packed representation.
			case am.ik.rontolisp.LispSingleFloatArray fa -> compileSinglePackedLiteral(fa, ctx);
			// A packed integer-vector literal (ironclad's #N@(...)) compiles to a
			// native long[] with a width header -- the packed representation, disjoint
			// from the general array and the packed float shapes.
			case am.ik.rontolisp.LispIntVector iv -> compileLiteralIntVector(iv, ctx, className);
			default -> throw new UnsupportedOperationException("Cannot quote: " + val.print());
		}
	}

	/**
	 * Compiles a self-evaluating instance literal in code position (an instance is
	 * neither a symbol nor a cons, CLHS 3.1.2.1.3).
	 * @param inst the instance literal
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 */
	static void compileLiteralInstance(am.ik.rontolisp.LispInstance inst, JvmLispCompiler.Ctx ctx, String className) {
		compileQuotedInstance(inst, ctx, className);
	}

	// Builds Object[]{String[] layout, v1, ..., vn} -- the same shape %obj-new emits.
	// The layout is taken from the VALUE, because a #S(...) literal carries the layout
	// the reader already resolved against the same registry the compiler uses.
	private static void compileQuotedInstance(am.ik.rontolisp.LispInstance inst, JvmLispCompiler.Ctx ctx,
			String className) {
		if (!ctx.mayUseInstances) {
			// The gate is decided before any body compiles, so a literal that only
			// appears during Pass 2 (a macro that returns an instance) would build one
			// the predicates were not told about. Fail loudly instead.
			throw new UnsupportedOperationException(
					"an instance literal of type " + inst.layout().tag() + " appeared after the instance gate closed");
		}
		am.ik.jvm.ConstantPool.FieldrefConstant lf = ctx.layoutPool.intern(ctx.cp, className, inst.layout());
		int slots = inst.slotCount();
		JvmEmitHelper.emitIntConst(ctx, 1 + slots);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(lf.index());
		ctx.emit(Opcode.AASTORE);
		for (int i = 0; i < slots; i++) {
			ctx.emit(Opcode.DUP);
			JvmEmitHelper.emitIntConst(ctx, 1 + i);
			compileQuotedVal(inst.slot(i), ctx, className);
			ctx.emit(Opcode.AASTORE);
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

	/**
	 * Emitted bytes a spine chunk is allowed to reach before the rest of the literal
	 * moves into another {@code _ql$N} builder, and -- doubled -- the size a whole
	 * literal has to exceed before it is chunked at all. A literal under that is emitted
	 * inline, byte for byte as it always was.
	 */
	private static final int QUOTE_CHUNK_BUDGET = 2000;

	/** Bytes one spine cell costs, without its car. */
	private static final int CELL_BYTES = 15;

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
		List<int[]> chunks = spineChunks(cars);
		if (chunks.size() == 1) {
			emitSpineCells(cars, 0, cars.size(), ctx, className);
			return;
		}
		// A table literal big enough to put its own function past HotSpot's
		// HugeMethodLimit (a package use-list is ~5 KB of cells, and the function
		// holding it nothing else) is built by a chain of one-argument builders
		// instead: each takes the tail built so far and answers the head of its chunk.
		// The cells are still fresh at every evaluation -- this moves the construction,
		// it does not memoize it.
		for (int c = chunks.size() - 1; c >= 0; c--) {
			int[] chunk = chunks.get(c);
			String methodName = "_ql$" + ctx.nextOutlinedBodyId[0]++;
			String desc = "(Ljava/lang/Object;)Ljava/lang/Object;";
			Utf8Constant nameUtf8 = ctx.cp.addUtf8(methodName);
			Utf8Constant descUtf8 = ctx.cp.addUtf8(desc);
			MethodrefConstant ref = JvmEmitHelper.selfMethod(ctx, className, methodName, desc);
			JvmLispCompiler.Ctx builder = ctx.ctxBuilder.build();
			builder.evalStoreRef = ctx.evalStoreRef;
			builder.nextLocal = 1;
			builder.maxLocals = 1;
			builder.emit(Opcode.ALOAD);
			builder.emit(0);
			emitSpineCells(cars, chunk[0], chunk[1], builder, className);
			builder.emit(Opcode.ARETURN);
			ctx.outlinedBodies.add(new JvmBodyOutliner.OutlinedBody(methodName, nameUtf8, descUtf8, builder));
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ref.index());
		}
	}

	/**
	 * Builds the cells for {@code cars[from, to)} on top of the tail the operand stack
	 * already holds, front cell last, and leaves the head there.
	 */
	private static void emitSpineCells(List<LispVal> cars, int from, int to, JvmLispCompiler.Ctx ctx,
			String className) {
		// One temp for the whole spine: it holds the tail only while the cell in front of
		// it is built, and is dead again by the next iteration. A sublist compiled into
		// the car allocates ABOVE it and gives its slots back, so the locals a literal
		// costs are bounded by its nesting depth, not by its length -- and a long list
		// cannot walk past the highest slot a one-byte operand can name.
		int savedNextLocal = ctx.nextLocal;
		int tempSlot = ctx.allocTemp();
		for (int i = to - 1; i >= from; i--) {
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
		ctx.nextLocal = savedNextLocal;
	}

	/**
	 * {@return the {@code [from, to)} ranges of the spine, one per builder method}
	 *
	 * One range covering the whole spine means "emit it inline", which is what every
	 * literal under twice {@link #QUOTE_CHUNK_BUDGET} gets, so nothing that already fits
	 * in a method changes.
	 */
	private static List<int[]> spineChunks(List<LispVal> cars) {
		int total = 0;
		int[] sizes = new int[cars.size()];
		for (int i = 0; i < cars.size(); i++) {
			sizes[i] = CELL_BYTES + estimateQuoted(cars.get(i));
			total += sizes[i];
		}
		if (total <= 2 * QUOTE_CHUNK_BUDGET) {
			return List.of(new int[] { 0, cars.size() });
		}
		List<int[]> chunks = new ArrayList<>();
		int start = 0;
		int running = 0;
		for (int i = 0; i < cars.size(); i++) {
			running += sizes[i];
			// Cut AFTER the element that crossed, so a single oversized car (a nested
			// literal, which chunks itself) still lands in a chunk of its own.
			if (running >= QUOTE_CHUNK_BUDGET && i + 1 < cars.size()) {
				chunks.add(new int[] { start, i + 1 });
				start = i + 1;
				running = 0;
			}
		}
		chunks.add(new int[] { start, cars.size() });
		return chunks;
	}

	/**
	 * {@return a rough count of the bytes {@link #compileQuotedVal} emits for a value}
	 *
	 * Only ever compared against {@link #QUOTE_CHUNK_BUDGET}, so every leaf is charged
	 * the width of the widest constant load and no emitter is consulted.
	 */
	private static int estimateQuoted(LispVal val) {
		if (val instanceof LispCons cons) {
			int size = 0;
			LispVal rest = cons;
			while (rest instanceof LispCons cell) {
				size += CELL_BYTES + estimateQuoted(cell.car());
				rest = cell.cdr();
			}
			return size + estimateQuoted(rest);
		}
		if (val instanceof LispArray array) {
			int size = 16;
			for (LispVal element : array.data()) {
				size += 8 + estimateQuoted(element);
			}
			return size;
		}
		return 6;
	}

}
