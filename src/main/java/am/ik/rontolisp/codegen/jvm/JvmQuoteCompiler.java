package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.DoubleEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.LongEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispComplex;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.LiteralArrayRuns;
import am.ik.rontolisp.compiler.QuotedGraph;

/**
 * Compiles the {@code quote} special form.
 */
final class JvmQuoteCompiler {

	private JvmQuoteCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal quoted = am.ik.rontolisp.LispCircularLiteral.unwrapped(((LispCons) cons.cdr()).car());
		// A quoted AGGREGATE is one shared constant (.kb/quoted-data.md): the site
		// caches its build in a slot of the class's quoted-datum table -- read it; on
		// null build and fill it -- so every evaluation answers the SAME object, like
		// the interpreter, whose evalQuote hands back the reader's datum. Lazy AT THE
		// SITE rather than in <clinit> on purpose: the writer shakes on every build,
		// and a quote site inside a shaken wrapper defun must go with it -- a <clinit>
		// initializer would keep every quoted table of every dropped wrapper alive
		// (measured +13 KB on a three-defun program). The fill settles racing first
		// evaluations under the class monitor -- every loser answers the winner -- and
		// publishes the datum fully built through a final field. Keyed by datum
		// IDENTITY (JvmQuotePool), so a macro expansion splicing one template datum into
		// several sites shares one constant across them too. An atom keeps the inline
		// emission below; a BARE array literal never comes here and stays a constructor
		// (.kb/array-literals.md).
		if (isSharedAggregate(quoted)) {
			emitSharedConstant(quoted, ctx, className, () -> compileDatum(quoted, ctx, className));
			return;
		}
		compileQuotedVal(quoted, ctx, className);
	}

	/**
	 * Compiles a literal holding a circle in code position: as what it holds would
	 * compile there, an array a constructor and anything else one shared constant.
	 * @param literal the literal
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 */
	static void compileCircularLiteral(am.ik.rontolisp.LispCircularLiteral literal, JvmLispCompiler.Ctx ctx,
			String className) {
		LispVal datum = literal.datum();
		if (datum instanceof LispArray array) {
			compileLiteralArray(array, ctx, className);
		}
		else {
			emitSharedConstant(datum, ctx, className, () -> compileDatum(datum, ctx, className));
		}
	}

	/**
	 * Emits the build of a whole datum. A tree is built where it stands. A datum whose
	 * nodes are shared or closed into a circle ({@link QuotedGraph}) keeps its named
	 * nodes in an {@code Object[]} for the length of the build: every one made empty,
	 * then every one filled, so a named node inside a filling answers the one object. The
	 * fill never publishes: a table slot still takes the finished root, and the final
	 * field behind it covers everything the root reaches.
	 */
	private static void compileDatum(LispVal datum, JvmLispCompiler.Ctx ctx, String className) {
		QuotedGraph graph = QuotedGraph.of(datum);
		if (graph == null) {
			compileQuotedVal(datum, ctx, className);
			return;
		}
		int savedNextLocal = ctx.nextLocal;
		ctx.quotedGraph = graph;
		ctx.quotedNames = ctx.allocTemp();
		List<LispVal> named = graph.named();
		JvmEmitHelper.emitIntConst(ctx, named.size());
		ctx.body.anewarray(ctx.objectClass).astore(ctx.quotedNames);
		int steps = named.size() * 2;
		int[] starts = new int[steps];
		int[] sizes = new int[steps];
		for (int i = 0; i < steps; i++) {
			starts[i] = i;
			sizes[i] = (i < named.size()) ? estimateEmpty(named.get(i))
					: estimateFilling(named.get(i - named.size()), graph);
		}
		List<int[]> chunks = chunks(starts, sizes, steps);
		if (chunks.size() == 1) {
			emitNamedSteps(named, 0, steps, ctx, className);
		}
		else {
			for (int[] chunk : chunks) {
				ctx.body.aload(ctx.quotedNames);
				emitBuilderCall(NAMES_DESCRIPTOR, ctx, className,
						builder -> emitNamedSteps(named, chunk[0], chunk[1], builder, className));
				ctx.body.pop();
			}
		}
		compileQuotedVal(datum, ctx, className);
		ctx.quotedGraph = null;
		ctx.nextLocal = savedNextLocal;
	}

	/**
	 * Emits the steps {@code [from, to)} of a named build: step {@code i} below the name
	 * count makes node {@code i} empty, and past it fills the node it counts to.
	 */
	private static void emitNamedSteps(List<LispVal> named, int from, int to, JvmLispCompiler.Ctx ctx,
			String className) {
		for (int step = from; step < to; step++) {
			if (step < named.size()) {
				ctx.body.aload(ctx.quotedNames);
				JvmEmitHelper.emitIntConst(ctx, step);
				emitEmptyNode(named.get(step), ctx, className);
				ctx.body.aastore();
			}
			else {
				emitFilling(named.get(step - named.size()), step - named.size(), ctx, className);
			}
		}
	}

	private static void emitEmptyNode(LispVal node, JvmLispCompiler.Ctx ctx, String className) {
		switch (node) {
			case LispCons ignored -> ctx.body.iconst_2().anewarray(ctx.objectClass);
			case LispArray array -> emitEmptyArray(array, ctx);
			case am.ik.rontolisp.LispInstance inst -> emitEmptyInstance(inst, ctx, className);
			// A packed array holds no reference: it is finished here.
			default -> buildQuotedVal(node, ctx, className);
		}
	}

	private static void emitFilling(LispVal node, int name, JvmLispCompiler.Ctx ctx, String className) {
		switch (node) {
			case LispCons cell -> {
				emitNamedNode(name, ctx);
				ctx.body.checkcast(ctx.cp.classEntry(NAMES_DESCRIPTOR)).dup().iconst_0();
				compileQuotedVal(cell.car(), ctx, className);
				ctx.body.aastore().iconst_1();
				compileQuotedVal(cell.cdr(), ctx, className);
				ctx.body.aastore();
			}
			case LispArray array -> {
				emitNamedNode(name, ctx);
				ctx.body.checkcast(ctx.cp.classEntry("java/util/ArrayList"));
				emitArrayFilling(array, ctx, className);
				ctx.body.pop();
			}
			case am.ik.rontolisp.LispInstance inst -> {
				emitNamedNode(name, ctx);
				ctx.body.checkcast(ctx.cp.classEntry(NAMES_DESCRIPTOR));
				emitInstanceFilling(inst, ctx, className);
				ctx.body.pop();
			}
			default -> {
			}
		}
	}

	private static void emitNamedNode(int name, JvmLispCompiler.Ctx ctx) {
		ctx.body.aload(ctx.quotedNames);
		JvmEmitHelper.emitIntConst(ctx, name);
		ctx.body.aaload();
	}

	private static int estimateEmpty(LispVal node) {
		return (node instanceof LispCons) ? CELL_BYTES : EMPTY_NODE_BYTES;
	}

	private static int estimateFilling(LispVal node, QuotedGraph graph) {
		return switch (node) {
			case LispCons cell -> CELL_BYTES + estimateQuoted(cell.car(), graph) + estimateQuoted(cell.cdr(), graph);
			case LispArray array -> estimateElements(array, graph);
			case am.ik.rontolisp.LispInstance inst -> {
				int size = 0;
				for (int i = 0; i < inst.slotCount(); i++) {
					size += ADD_BYTES + estimateQuoted(inst.slot(i), graph);
				}
				yield size;
			}
			default -> 0;
		};
	}

	/**
	 * Emits one datum's build behind its lazy slot of the quoted-datum table
	 * ({@link JvmQuotePool}), so every evaluation of the site answers the same object.
	 * Shared by the {@code quote} path and the bare-instance-literal path, which have the
	 * same identity rule and differ only in what they build.
	 * @param datum the datum the slot is keyed by (identity, not equality)
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 * @param build emits the construction, leaving exactly one value on the stack
	 */
	private static void emitSharedConstant(LispVal datum, JvmLispCompiler.Ctx ctx, String className, Runnable build) {
		emitSlot(ctx.quotePool.slot(datum), ctx, className, build);
	}

	/**
	 * Emits a string constant whose modified UTF-8 form is too long for one
	 * {@code CONSTANT_Utf8}: its pieces ({@link ConstantPool#utf8Pieces}) joined and
	 * interned behind a slot of the quoted-datum table keyed by content, so the join runs
	 * once and every evaluation of every site answers the object an {@code ldc} of the
	 * whole would have -- the canonical instance of the content.
	 * @param value the string (a framed string literal or a symbol name)
	 * @param ctx the compilation context
	 */
	static void emitLongString(String value, JvmLispCompiler.Ctx ctx) {
		ConstantPool cp = ctx.cp;
		ClassEntry stringClass = cp.classEntry("java/lang/String");
		ClassEntry builderClass = cp.classEntry("java/lang/StringBuilder");
		emitSlot(ctx.quotePool.slot(value), ctx, ctx.className, () -> {
			// new StringBuilder(length) { .append(piece) } .toString().intern()
			ctx.body.new_(builderClass).dup();
			JvmEmitHelper.emitIntConst(ctx, value.length());
			ctx.body.invokespecial(cp.methodRef(builderClass, "<init>", "(I)V"));
			MethodRefEntry append = cp.methodRef(builderClass, "append",
					"(Ljava/lang/String;)Ljava/lang/StringBuilder;");
			for (String piece : ConstantPool.utf8Pieces(value)) {
				ctx.body.ldc(cp.stringEntry(piece)).invokevirtual(append);
			}
			ctx.body.invokevirtual(cp.methodRef(builderClass, "toString", "()Ljava/lang/String;"))
				.invokevirtual(cp.methodRef(stringClass, "intern", "()Ljava/lang/String;"));
		});
		// The slot answers an Object; a literal's consumer may expect the String an ldc
		// would have left.
		ctx.body.checkcast(stringClass);
	}

	/**
	 * Emits a build behind its lazy slot of the quoted-datum table.
	 * @param slot the slot
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 * @param build emits the construction, leaving exactly one value on the stack
	 */
	private static void emitSlot(int slot, JvmLispCompiler.Ctx ctx, String className, Runnable build) {
		JvmQuotePool.Refs refs = ctx.quotePool.refs(ctx.cp, className);
		// <slot>; INVOKESTATIC _qd; DUP; IFNONNULL end; POP; <build>; <slot>;
		// INVOKESTATIC _qdSet; end: -- one value on the stack on both paths.
		JvmEmitHelper.emitIntConst(ctx, slot);
		ctx.body.invokestatic(refs.get()).dup();
		MethodCode.Label branchPos = ctx.body.newLabel();
		ctx.body.ifnonnull(branchPos);
		ctx.body.pop();
		build.run();
		JvmEmitHelper.emitIntConst(ctx, slot);
		ctx.body.invokestatic(refs.set());
		ctx.body.labelBinding(branchPos);
	}

	/**
	 * Whether a quoted datum is memoized into one shared constant. Exactly the mutable
	 * aggregates: an atom (a number, a string, a symbol, a character, nil, t) has no
	 * identity a program can observe diverging, so it keeps its inline emission.
	 * @param val the quoted datum
	 * @return true when the datum gets a slot of the shared quoted-datum table
	 */
	private static boolean isSharedAggregate(LispVal val) {
		return val instanceof LispCons || val instanceof LispArray || val instanceof am.ik.rontolisp.LispInstance
				|| val instanceof am.ik.rontolisp.LispDoubleFloatArray
				|| val instanceof am.ik.rontolisp.LispSingleFloatArray
				|| val instanceof am.ik.rontolisp.LispBFloat16Array || val instanceof am.ik.rontolisp.LispIntVector;
	}

	/**
	 * Emits the construction of a self-evaluating array literal ({@code #(...)}). Shared
	 * by the {@code quote} path and the bare-literal path in {@link JvmExprCompiler}.
	 * @param array the literal array
	 * @param ctx the compilation context
	 * @param className the enclosing class name
	 */
	static void compileLiteralArray(LispArray array, JvmLispCompiler.Ctx ctx, String className) {
		compileDatum(array, ctx, className);
	}

	/**
	 * Emits a packed integer-vector literal (ironclad's {@code #N@(...)}, or a macro-time
	 * {@code LispIntVector} value) as the native packed representation
	 * ({@link JvmIntArrayRuntimeBuilder}): the bare {@code byte[]} of the octets at width
	 * 8, a {@code long[]} {@code [width, e_0, ..., e_{n-1}]} otherwise. The elements
	 * arrive pre-masked from the reader; a zero octet needs no store. The array reference
	 * is kept on the stack and {@code DUP}ed for each store, so the operand stack stays
	 * shallow regardless of the element count.
	 * @param iv the packed integer-vector literal
	 * @param ctx the compilation context
	 * @param className the enclosing class name
	 */
	static void compileLiteralIntVector(am.ik.rontolisp.LispIntVector iv, JvmLispCompiler.Ctx ctx, String className) {
		long[] data = iv.toLongArray();
		if (iv.width() == JvmIntArrayRuntimeBuilder.OCTET_WIDTH) {
			JvmEmitHelper.emitIntConst(ctx, data.length);
			ctx.body.newarray(TypeKind.BYTE);
			emitStores("[B", data.length, 7, ctx, (c, i) -> {
				if (data[i] != 0) {
					emitRawByteStore(c, i, (int) data[i]);
				}
			});
			return;
		}
		JvmEmitHelper.emitIntConst(ctx, 1 + data.length);
		ctx.body.newarray(TypeKind.LONG);
		emitRawLongStore(ctx, 0, iv.width());
		emitStores("[J", data.length, 8, ctx, (c, i) -> emitRawLongStore(c, 1 + i, data[i]));
	}

	/** Emits one element's store, the array staying on top of the stack. */
	private interface ElementStore {

		void emit(JvmLispCompiler.Ctx ctx, int index);

	}

	/**
	 * Emits a packed literal's element stores: inline up to twice
	 * {@link #QUOTE_CHUNK_BUDGET}, else in a builder per budget, so a long table is held
	 * neither to the method limit nor past HotSpot's HugeMethodLimit.
	 * @param arrayDescriptor the array's type descriptor
	 * @param count how many elements there are
	 * @param storeBytes the most bytes one store takes
	 * @param ctx the compilation context
	 * @param store emits the store of element {@code index}
	 */
	private static void emitStores(String arrayDescriptor, int count, int storeBytes, JvmLispCompiler.Ctx ctx,
			ElementStore store) {
		if ((long) count * storeBytes <= 2 * QUOTE_CHUNK_BUDGET) {
			for (int i = 0; i < count; i++) {
				store.emit(ctx, i);
			}
			return;
		}
		int perBuilder = QUOTE_CHUNK_BUDGET / storeBytes;
		for (int start = 0; start < count; start += perBuilder) {
			int from = start;
			int to = Math.min(count, start + perBuilder);
			emitBuilderCall(arrayDescriptor, ctx, ctx.className, builder -> {
				for (int i = from; i < to; i++) {
					store.emit(builder, i);
				}
			});
		}
	}

	/**
	 * Moves a piece of a literal's build into a {@code _ql$N} method of its own, taking
	 * the value on top of the stack and answering what the piece leaves in its place.
	 * @param operandDescriptor the value's type descriptor
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 * @param piece emits the piece, over the context of the new method
	 */
	private static void emitBuilderCall(String operandDescriptor, JvmLispCompiler.Ctx ctx, String className,
			Consumer<JvmLispCompiler.Ctx> piece) {
		String methodName = "_ql$" + ctx.nextOutlinedBodyId[0]++;
		// A piece of a named build takes the names as well: they live in a local.
		String desc = "(" + operandDescriptor + ((ctx.quotedGraph != null) ? NAMES_DESCRIPTOR : "") + ")"
				+ operandDescriptor;
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(methodName);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(desc);
		MethodRefEntry ref = JvmEmitHelper.selfMethod(ctx, className, methodName, desc);
		JvmLispCompiler.Ctx builder = ctx.ctxBuilder.build();
		builder.evalStoreRef = ctx.evalStoreRef;
		builder.nextLocal = 1;
		builder.maxLocals = 1;
		if (ctx.quotedGraph != null) {
			builder.quotedGraph = ctx.quotedGraph;
			builder.quotedNames = 1;
			builder.nextLocal = 2;
			builder.maxLocals = 2;
			ctx.body.aload(ctx.quotedNames);
		}
		builder.body.aload(0);
		piece.accept(builder);
		builder.body.areturn();
		ctx.outlinedBodies.add(new JvmBodyOutliner.OutlinedBody(methodName, nameUtf8, descUtf8, builder));
		ctx.body.invokestatic(ref);
	}

	// Assumes the byte[] is on top of the stack; stores the octet at index (DUP; index;
	// the octet as a signed byte; BASTORE), leaving the array on the stack.
	private static void emitRawByteStore(JvmLispCompiler.Ctx ctx, int index, int octet) {
		ctx.body.dup();
		JvmEmitHelper.emitIntConst(ctx, index);
		JvmEmitHelper.emitIntConst(ctx, (byte) octet);
		ctx.body.bastore();
	}

	// Assumes the long[] is on top of the stack; stores value at index (DUP; index;
	// raw long; LASTORE), leaving the array on the stack.
	private static void emitRawLongStore(JvmLispCompiler.Ctx ctx, int index, long value) {
		ctx.body.dup();
		JvmEmitHelper.emitIntConst(ctx, index);
		if (value == 0L) {
			ctx.body.lconst_0();
		}
		else if (value == 1L) {
			ctx.body.lconst_1();
		}
		else {
			LongEntry lc = ctx.cp.entries().longEntry(value);
			ctx.body.ldc(lc);
		}
		ctx.body.lastore();
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
		ctx.body.newarray(TypeKind.DOUBLE); // T_DOUBLE
		emitRawDoubleStore(ctx, 0, rank);
		for (int d = 0; d < rank; d++) {
			emitRawDoubleStore(ctx, 1 + d, dims[d]);
		}
		emitStores("[D", data.length, 8, ctx, (c, f) -> emitRawDoubleStore(c, off + f, data[f]));
	}

	// Assumes the double[] is on top of the stack; stores value at index (DUP; index;
	// raw double; DASTORE), leaving the array on the stack.
	private static void emitRawDoubleStore(JvmLispCompiler.Ctx ctx, int index, double value) {
		ctx.body.dup();
		JvmEmitHelper.emitIntConst(ctx, index);
		emitRawDouble(ctx, value);
		ctx.body.dastore();
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
		ctx.body.newarray(TypeKind.FLOAT); // T_FLOAT
		emitRawFloatHeaderStore(ctx, 0, rank);
		for (int d = 0; d < rank; d++) {
			emitRawFloatHeaderStore(ctx, 1 + d, dims[d]);
		}
		emitStores("[F", data.length, 9, ctx, (c, f) -> emitRawFloatDataStore(c, off + f, data[f]));
	}

	// Stores an int header value (rank/dim) as a float at index into the float[] on top
	// of
	// the stack (DUP; index; int; I2F; FASTORE), leaving the array on the stack.
	private static void emitRawFloatHeaderStore(JvmLispCompiler.Ctx ctx, int index, int value) {
		ctx.body.dup();
		JvmEmitHelper.emitIntConst(ctx, index);
		JvmEmitHelper.emitIntConst(ctx, value);
		ctx.body.i2f().fastore();
	}

	// Stores a float data value at index into the float[] on top of the stack, emitting
	// the
	// value as its widening double constant narrowed back with D2F (exact) so no float
	// constant pool entry is needed (DUP; index; double; D2F; FASTORE).
	private static void emitRawFloatDataStore(JvmLispCompiler.Ctx ctx, int index, float value) {
		ctx.body.dup();
		JvmEmitHelper.emitIntConst(ctx, index);
		emitRawDouble(ctx, value);
		ctx.body.d2f().fastore();
	}

	/**
	 * Emits a packed bfloat16 array literal ({@code #bf16(...)}) as a bare
	 * {@code short[]} with the two-slots-per-dimension header
	 * {@link JvmPackedFloatWidth#BFLOAT16} lays out ({@code [rank, hi_0, lo_0, ...,
	 * e_0, ...]}, data offset {@code 1 + 2 * rank}). Each word -- header and element
	 * alike -- is a {@code sipush} of its low sixteen bits followed by {@code sastore},
	 * so a bfloat16 literal costs about half the bytes of the {@code #f} one, which is
	 * what keeps it furthest from the 64 KB method-code ceiling a baked literal hits
	 * ({@code .kb/jvm-method-size-limits.md}).
	 * @param fa the packed literal
	 * @param ctx the compilation context
	 */
	static void compileBFloat16PackedLiteral(am.ik.rontolisp.LispBFloat16Array fa, JvmLispCompiler.Ctx ctx) {
		short[] data = fa.data();
		int[] header = JvmPackedFloatWidth.BFLOAT16.headerWords(fa.dims());
		int off = header.length;
		JvmEmitHelper.emitIntConst(ctx, off + data.length);
		ctx.body.newarray(TypeKind.SHORT); // T_SHORT
		for (int h = 0; h < off; h++) {
			emitRawShortStore(ctx, h, (short) header[h]);
		}
		emitStores("[S", data.length, 8, ctx, (c, f) -> emitRawShortStore(c, off + f, data[f]));
	}

	// Stores a short at index into the short[] on top of the stack (DUP; index; value;
	// SASTORE), leaving the array on the stack.
	private static void emitRawShortStore(JvmLispCompiler.Ctx ctx, int index, short value) {
		ctx.body.dup();
		JvmEmitHelper.emitIntConst(ctx, index);
		JvmEmitHelper.emitIntConst(ctx, value);
		ctx.body.sastore();
	}

	// Pushes an unboxed double constant (no Double.valueOf), the raw value to store into
	// a double[].
	private static void emitRawDouble(JvmLispCompiler.Ctx ctx, double value) {
		if (value == 0.0 && Double.doubleToRawLongBits(value) == 0L) {
			ctx.body.dconst_0();
		}
		else if (value == 1.0) {
			ctx.body.dconst_1();
		}
		else {
			DoubleEntry dc = ctx.cp.entries().doubleEntry(value);
			ctx.body.ldc(dc);
		}
	}

	private static void compileQuotedVal(LispVal val, JvmLispCompiler.Ctx ctx, String className) {
		QuotedGraph graph = ctx.quotedGraph;
		int name = (graph != null) ? graph.nameOf(val) : -1;
		if (name >= 0) {
			emitNamedNode(name, ctx);
			return;
		}
		buildQuotedVal(val, ctx, className);
	}

	private static boolean isNamed(LispVal val, @Nullable QuotedGraph graph) {
		return graph != null && graph.nameOf(val) >= 0;
	}

	private static void buildQuotedVal(LispVal val, JvmLispCompiler.Ctx ctx, String className) {
		switch (val) {
			case LispInteger i -> JvmEmitHelper.compileLong(i.value(), ctx);
			case LispBigInteger b -> JvmEmitHelper.compileBigInteger(b.value(), ctx);
			case am.ik.rontolisp.LispRatio r -> JvmEmitHelper.compileRatio(r, ctx);
			case LispDouble d -> JvmEmitHelper.compileDouble(d.value(), ctx);
			case LispComplex c -> JvmComplexCompiler.compileLiteral(c, ctx, className);
			case LispNil ignored -> ctx.body.aconst_null();
			case LispTrue ignored -> JvmEmitHelper.compileTrue(ctx);
			case LispString s -> JvmEmitHelper.compileStringLiteral(s.literal(), ctx);
			case am.ik.rontolisp.LispChar c -> JvmEmitHelper.compileCharLiteral(c.codePoint(), ctx);
			case LispSymbol sym -> JvmEmitHelper.compileStringLiteral(sym.name(), ctx);
			case LispCons cons -> compileQuotedCons(cons, ctx, className);
			case am.ik.rontolisp.LispCircularLiteral literal -> compileQuotedVal(literal.datum(), ctx, className);
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
			// A packed #bf16(...) bfloat16 literal compiles to a native short[] with the
			// two-slot dimension header, disjoint from every other shape.
			case am.ik.rontolisp.LispBFloat16Array fa -> compileBFloat16PackedLiteral(fa, ctx);
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
	 * <p>
	 * It is one SHARED constant, exactly as under {@code quote} (.kb/quoted-data.md): the
	 * interpreter's {@code LispInstance} arm hands the reader's own instance back at
	 * every evaluation, so the site memoizes into the same lazy table slot a quoted datum
	 * uses. This is the one literal family that does NOT follow the fresh-per-evaluation
	 * rule of an array literal (.kb/array-literals.md): there the interpreter could be
	 * moved, here it cannot -- the same arm carries every live instance the evaluator
	 * splices back through {@code (quote <value>)}.
	 * @param inst the instance literal
	 * @param ctx the compilation context
	 * @param className the class being emitted
	 */
	static void compileLiteralInstance(am.ik.rontolisp.LispInstance inst, JvmLispCompiler.Ctx ctx, String className) {
		emitSharedConstant(inst, ctx, className, () -> compileDatum(inst, ctx, className));
	}

	// Builds Object[]{String[] layout, v1, ..., vn} -- the same shape %obj-new emits.
	// The layout is taken from the VALUE, because a #S(...) literal carries the layout
	// the reader already resolved against the same registry the compiler uses.
	private static void compileQuotedInstance(am.ik.rontolisp.LispInstance inst, JvmLispCompiler.Ctx ctx,
			String className) {
		emitEmptyInstance(inst, ctx, className);
		emitInstanceFilling(inst, ctx, className);
	}

	// The Object[] with its layout and no slot value yet, left on the stack.
	private static void emitEmptyInstance(am.ik.rontolisp.LispInstance inst, JvmLispCompiler.Ctx ctx,
			String className) {
		if (!ctx.mayUseInstances) {
			// The gate is decided before any body compiles, so a literal that only
			// appears during Pass 2 (a macro that returns an instance) would build one
			// the predicates were not told about. Fail loudly instead.
			throw new UnsupportedOperationException(
					"an instance literal of type " + inst.layout().tag() + " appeared after the instance gate closed");
		}
		FieldRefEntry lf = ctx.layoutPool.intern(ctx.cp, className, inst.layout());
		JvmEmitHelper.emitIntConst(ctx, 1 + inst.slotCount());
		ctx.body.anewarray(ctx.objectClass).dup().iconst_0().getstatic(lf);
		ctx.body.aastore();
	}

	// Stores the slot values into the Object[] on top of the stack, leaving it there.
	private static void emitInstanceFilling(am.ik.rontolisp.LispInstance inst, JvmLispCompiler.Ctx ctx,
			String className) {
		for (int i = 0; i < inst.slotCount(); i++) {
			ctx.body.dup();
			JvmEmitHelper.emitIntConst(ctx, 1 + i);
			compileQuotedVal(inst.slot(i), ctx, className);
			ctx.body.aastore();
		}
	}

	// Builds the runtime array representation (a java.util.ArrayList whose slot 0 is
	// the {dims, fillPointer, adjustable} header Object[] and slots 1.. are the
	// row-major elements), matching JvmArrayRuntimeBuilder. A literal array never has a
	// fill pointer and is not adjustable, so header slots 1 and 2 stay null. The list
	// reference is kept on the stack and DUPed for each add, so the operand stack stays
	// shallow regardless of the element count.
	private static void compileQuotedArray(LispArray array, JvmLispCompiler.Ctx ctx, String className) {
		emitEmptyArray(array, ctx);
		emitArrayFilling(array, ctx, className);
	}

	// The ArrayList with its header and no element yet, left on the stack.
	private static void emitEmptyArray(LispArray array, JvmLispCompiler.Ctx ctx) {
		int[] dims = array.dimensions();
		ClassEntry arrayListClass = ctx.cp.classEntry("java/util/ArrayList");
		MethodRefEntry alInit = ctx.cp.methodRef(arrayListClass, "<init>", "()V");
		MethodRefEntry alAdd = ctx.cp.methodRef(arrayListClass, "add", "(Ljava/lang/Object;)Z");
		ctx.body.new_(arrayListClass).dup().invokespecial(alInit);
		// A bit-vector literal (#*1011) is stamped with the remembered element type
		// bit: the header grows to the length-5 shape _arrayMakeTyped builds, carrying
		// the name in slot 4, so array-element-type and bit-vector-p read it back.
		// The name is unspelled run-time data the compiler synthesized, like the
		// character answer in compileElementType -- it must not arm the
		// funcall-dispatch gate's name probes.
		boolean stampedBit = array.elementTypeCode() == am.ik.rontolisp.ArrayElementTypes.BIT;
		addElement(ctx, alAdd, () -> {
			JvmEmitHelper.emitIntConst(ctx, stampedBit ? 5 : 3);
			ctx.body.anewarray(ctx.objectClass).dup();
			JvmEmitHelper.emitIntConst(ctx, 0);
			JvmEmitHelper.emitIntConst(ctx, dims.length);
			ctx.body.anewarray(ctx.objectClass);
			for (int d = 0; d < dims.length; d++) {
				ctx.body.dup();
				JvmEmitHelper.emitIntConst(ctx, d);
				JvmEmitHelper.compileLong(dims[d], ctx);
				ctx.body.aastore();
			}
			ctx.body.aastore();
			if (stampedBit) {
				ctx.body.dup();
				JvmEmitHelper.emitIntConst(ctx, 4);
				JvmEmitHelper.compileUnspelledLiteral(am.ik.rontolisp.LispNames.BIT, ctx);
				ctx.body.aastore();
			}
		});
	}

	// Appends the elements to the ArrayList on top of the stack, leaving it there.
	private static void emitArrayFilling(LispArray array, JvmLispCompiler.Ctx ctx, String className) {
		LispVal[] data = array.data();
		List<int[]> chunks = arrayChunks(data, ctx.quotedGraph);
		if (chunks.size() == 1) {
			emitArrayElements(data, 0, data.length, ctx, className);
			return;
		}
		// As a long list's spine: a builder per chunk, so no literal is held to the
		// method limit and the method holding one stays small enough to be compiled.
		for (int[] chunk : chunks) {
			emitBuilderCall("Ljava/util/ArrayList;", ctx, className,
					builder -> emitArrayElements(data, chunk[0], chunk[1], builder, className));
		}
	}

	/**
	 * Appends {@code data[from, to)} to the ArrayList on top of the stack and leaves it
	 * there, a run of equal immediates ({@link LiteralArrayRuns}) as one {@code addAll}
	 * of {@code Collections.nCopies}.
	 */
	private static void emitArrayElements(LispVal[] data, int from, int to, JvmLispCompiler.Ctx ctx, String className) {
		ClassEntry arrayListClass = ctx.cp.classEntry("java/util/ArrayList");
		MethodRefEntry add = ctx.cp.methodRef(arrayListClass, "add", "(Ljava/lang/Object;)Z");
		int run;
		for (int i = from; i < to; i += run) {
			run = LiteralArrayRuns.length(data, i);
			LispVal value = LiteralArrayRuns.element(data, i);
			if (run == 1) {
				addElement(ctx, add, () -> compileQuotedVal(value, ctx, className));
			}
			else {
				ctx.body.dup();
				JvmEmitHelper.emitIntConst(ctx, run);
				compileQuotedVal(value, ctx, className);
				ctx.body
					.invokestatic(ctx.cp.methodRef(ctx.cp.classEntry("java/util/Collections"), "nCopies",
							"(ILjava/lang/Object;)Ljava/util/List;"))
					.invokevirtual(ctx.cp.methodRef(arrayListClass, "addAll", "(Ljava/util/Collection;)Z"))
					.pop();
			}
		}
	}

	// Assumes the ArrayList is on top of the stack; appends one element (pushed by
	// pushValue) and leaves the list on the stack.
	private static void addElement(JvmLispCompiler.Ctx ctx, MethodRefEntry alAdd, Runnable pushValue) {
		ctx.body.dup();
		pushValue.run();
		ctx.body.invokevirtual(alAdd).pop();
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

	/** Bytes one array element's {@code add} costs, without the element. */
	private static final int ADD_BYTES = 5;

	/** Bytes one run's {@code addAll} costs, without the element. */
	private static final int RUN_BYTES = 11;

	/** Bytes a named array or instance costs before it holds anything. */
	private static final int EMPTY_NODE_BYTES = 40;

	/** The type of a cons, of an instance, and of the array of a build's named nodes. */
	private static final String NAMES_DESCRIPTOR = "[Ljava/lang/Object;";

	private static void compileQuotedCons(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// Walk the cdr spine and build the list tail-first through a temp slot, like
		// JvmListCompiler: recursing through the cdr would grow the operand stack
		// linearly with the list length and overflow the fixed max_stack. Only nested
		// sublists recurse (via the car), so the stack depth is bounded by the tree
		// depth, not the list length.
		List<LispVal> cars = new ArrayList<>();
		LispVal tail = cons;
		// A named cell is the tail of the spine that reaches it.
		while (tail instanceof LispCons cell && !isNamed(cell, ctx.quotedGraph)) {
			cars.add(cell.car());
			tail = cell.cdr();
		}
		compileQuotedVal(tail, ctx, className);
		List<int[]> chunks = spineChunks(cars, ctx.quotedGraph);
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
			emitBuilderCall("Ljava/lang/Object;", ctx, className,
					builder -> emitSpineCells(cars, chunk[0], chunk[1], builder, className));
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
			ctx.body.astore(tempSlot).iconst_2().anewarray(ctx.objectClass).dup();
			ctx.body.iconst_0();
			compileQuotedVal(cars.get(i), ctx, className);
			ctx.body.aastore().dup().iconst_1().aload(tempSlot).aastore();
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
	private static List<int[]> spineChunks(List<LispVal> cars, @Nullable QuotedGraph graph) {
		int[] starts = new int[cars.size()];
		int[] sizes = new int[cars.size()];
		for (int i = 0; i < cars.size(); i++) {
			starts[i] = i;
			sizes[i] = CELL_BYTES + estimateQuoted(cars.get(i), graph);
		}
		return chunks(starts, sizes, cars.size());
	}

	/**
	 * {@return the {@code [from, to)} ranges of an array's elements, one per builder
	 * method} A run stays whole: the cuts fall between {@link LiteralArrayRuns} builds.
	 */
	private static List<int[]> arrayChunks(LispVal[] data, @Nullable QuotedGraph graph) {
		int[] starts = new int[data.length];
		int[] sizes = new int[data.length];
		int builds = 0;
		int run;
		for (int i = 0; i < data.length; i += run) {
			run = LiteralArrayRuns.length(data, i);
			starts[builds] = i;
			sizes[builds++] = ((run == 1) ? ADD_BYTES : RUN_BYTES)
					+ estimateQuoted(LiteralArrayRuns.element(data, i), graph);
		}
		return chunks(Arrays.copyOf(starts, builds), sizes, data.length);
	}

	// starts[i] is where the i-th piece begins and sizes[i] what it costs.
	private static List<int[]> chunks(int[] starts, int[] sizes, int end) {
		if (Arrays.stream(sizes).limit(starts.length).sum() <= 2 * QUOTE_CHUNK_BUDGET) {
			return List.of(new int[] { 0, end });
		}
		List<int[]> chunks = new ArrayList<>();
		int start = 0;
		int running = 0;
		for (int i = 0; i < starts.length; i++) {
			running += sizes[i];
			// Cut AFTER the piece that crossed, so a single oversized car (a nested
			// literal, which chunks itself) still lands in a chunk of its own.
			if (running >= QUOTE_CHUNK_BUDGET && i + 1 < starts.length) {
				chunks.add(new int[] { start, starts[i + 1] });
				start = starts[i + 1];
				running = 0;
			}
		}
		chunks.add(new int[] { start, end });
		return chunks;
	}

	/**
	 * {@return a rough count of the bytes {@link #compileQuotedVal} emits for a value}
	 *
	 * Only ever compared against {@link #QUOTE_CHUNK_BUDGET}, so every leaf is charged
	 * the width of the widest constant load and no emitter is consulted. A named node is
	 * such a load, which is also what ends the walk on a circle.
	 */
	private static int estimateQuoted(LispVal val, @Nullable QuotedGraph graph) {
		if (isNamed(val, graph)) {
			return 6;
		}
		if (val instanceof LispCons cons) {
			int size = 0;
			LispVal rest = cons;
			while (rest instanceof LispCons cell && !isNamed(cell, graph)) {
				size += CELL_BYTES + estimateQuoted(cell.car(), graph);
				rest = cell.cdr();
			}
			return size + estimateQuoted(rest, graph);
		}
		if (val instanceof LispArray array) {
			return estimateElements(array, graph);
		}
		return 6;
	}

	private static int estimateElements(LispArray array, @Nullable QuotedGraph graph) {
		LispVal[] data = array.data();
		int size = 16;
		int run;
		for (int i = 0; i < data.length; i += run) {
			run = LiteralArrayRuns.length(data, i);
			size += 8 + estimateQuoted(LiteralArrayRuns.element(data, i), graph);
		}
		return size;
	}

}
