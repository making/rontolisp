package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

import static am.ik.rontolisp.codegen.wasm.WasmVecLoops.get;
import static am.ik.rontolisp.codegen.wasm.WasmVecLoops.set;
import static am.ik.rontolisp.codegen.wasm.WasmVecSimdRuntimeBuilder.boxFloat;
import static am.ik.rontolisp.codegen.wasm.WasmVecSimdRuntimeBuilder.castBuckets;
import static am.ik.rontolisp.codegen.wasm.WasmVecSimdRuntimeBuilder.farrayCount;
import static am.ik.rontolisp.codegen.wasm.WasmVecSimdRuntimeBuilder.farrayField;
import static am.ik.rontolisp.codegen.wasm.WasmVecSimdRuntimeBuilder.farrayGroups;
import static am.ik.rontolisp.codegen.wasm.WasmVecSimdRuntimeBuilder.farrayKind;
import static am.ik.rontolisp.codegen.wasm.WasmVecSimdRuntimeBuilder.i32Const;
import static am.ik.rontolisp.codegen.wasm.WasmVecSimdRuntimeBuilder.loadHeader;
import static am.ik.rontolisp.codegen.wasm.WasmVecSimdRuntimeBuilder.unboxF64;
import static am.ik.rontolisp.codegen.wasm.WasmVecSimdRuntimeBuilder.vblockGroups;
import static am.ik.rontolisp.codegen.wasm.WasmVecSimdRuntimeBuilder.withLocals;

/**
 * The wasm-GC {@code --simd} runtime for the {@code linalg:} kernels (todo-107), the
 * sibling of {@link WasmVecSimdRuntimeBuilder}. Fifteen hand-assembled functions that
 * {@link WasmLinalgSimdCompiler} calls at an intercepted {@code linalg:} call site.
 *
 * <h2>Why this exists: {@code --simd} used to make {@code linalg:} SLOWER here</h2>
 *
 * {@code --simd} switches the packed float-array representation to a {@code $vblock} over
 * an {@code (array (mut v128))} of lane groups (todo-105). Every scalar element access
 * then goes through {@code _v_get} / {@code _v_set} -- an {@code array.get} plus an
 * immediate-lane {@code if}-chain, and for a write a whole-group read-modify-write. The
 * {@code vec:} kernels are intercepted at their call sites and never pay it; not one
 * {@code linalg:} function was, so linalg paid the new representation's cost and got
 * nothing back (measured: {@code linalg:add} 205 ms scalar, 230 ms under {@code --simd}).
 *
 * <h2>The declined-input protocol</h2>
 *
 * A {@code vec:} kernel accepts packed float arrays and nothing else, so it can
 * {@code unreachable} on anything else. {@code linalg:} cannot: its defuns also accept
 * general (boxed) arrays, mixed widths, a scalar operand on either side, plain numbers,
 * and mismatched shapes (a specific {@code error}). So each kernel here is PARTIAL --
 * every entry point returns {@code (ref null eq)} and answers {@code null} for an input
 * it does not handle -- and the emitted call site then invokes the scalar
 * {@code linalg.lisp} defun over the same locals. The library stays the single source of
 * truth for every edge case, error messages included.
 *
 * <p>
 * That is safe because compiled nil is a null reference and none of the fifteen ever
 * returns nil ({@code linalg:array-equal}, which does, is deliberately not intercepted).
 * A {@code ref.test (ref $farray)} on nil is false, so a nil argument declines too.
 *
 * <h2>What is vectorized, and what is merely de-boxed</h2>
 *
 * <ul>
 * <li>Real {@code v128} lane loops: element-wise {@code add}/{@code sub}/{@code mul}/
 * {@code div} between two arrays; a DOUBLE array against a scalar; {@code sum};
 * {@code norm}; {@code dot}'s vector-vector and matrix-vector (GEMV) cases -- the last
 * four by calling the {@code vec:} kernels, whose lane loops are identical.</li>
 * <li>Element loops through {@code _v_get} / {@code _v_set}, with no boxing and no
 * generic arithmetic: a SINGLE-float array against a scalar (it must widen, compute in
 * f64 and narrow, exactly as {@code emap} does -- so the promoting accessors are the
 * point, not an obstacle),
 * {@code amax}/{@code amin}/{@code argmax}/{@code argmin}/{@code trace},
 * {@code transpose}, {@code outer}, and {@code dot}'s vector-matrix / matrix-matrix
 * cases. These stay bit-identical to the oracle and still run several times faster than
 * the defun, which pays a heap-allocated float box and a generic numeric dispatch per
 * element.</li>
 * <li>{@code reshape} copies whole lane groups: the vblock is a flat row-major lane
 * layout, so a reshape is a group copy plus a new {@code dims}.</li>
 * </ul>
 *
 * <p>
 * {@code mean}, {@code matmul}, {@code flatten} and {@code solve} are accelerated
 * TRANSITIVELY through the {@code sum} / {@code dot} / {@code reshape} their spliced
 * bodies call. {@code emap}, {@code det}, {@code inv} and {@code array-equal} are never
 * intercepted.
 *
 * <p>
 * Like the {@code vec:} kernels these must be standalone runtime functions:
 * {@link WasmLispCompiler} declares every extra local of a compiled defun as one
 * {@code (ref null eq)} group, so a defun body cannot hold a v128 / f64 / i32 local. The
 * local declarations are hand-written through the shared
 * {@link WasmVecSimdRuntimeBuilder#withLocals} in its fixed order -- i32, f64, f32, v128,
 * {@code (ref null eq)}, {@code (ref null $v128arr)} -- which all the index arithmetic
 * below assumes.
 */
final class WasmLinalgSimdRuntimeBuilder {

	private WasmLinalgSimdRuntimeBuilder() {
	}

	// Function indices, relative to WasmLispCompiler.linalgFuncBase(). Emitted (and only
	// emitted) under --simd, right after the vec: block and before the user defuns.

	static final int ADD = 0;

	static final int SUB = 1;

	static final int MUL = 2;

	static final int DIV = 3;

	static final int SUM = 4;

	static final int NORM = 5;

	static final int AMAX = 6;

	static final int AMIN = 7;

	static final int ARGMAX = 8;

	static final int ARGMIN = 9;

	static final int TRACE = 10;

	static final int TRANSPOSE = 11;

	static final int RESHAPE = 12;

	static final int DOT = 13;

	static final int OUTER = 14;

	/**
	 * The number of functions this builder contributes (shifts {@code FUNC_USER_BASE}).
	 */
	static final int FUNC_COUNT = 15;

	/** The type index of each function, in emission order (for the function section). */
	static int typeIndexOf(int fn) {
		return switch (fn) {
			// One eq param -> eq.
			case SUM, NORM, AMAX, AMIN, ARGMAX, ARGMIN, TRACE, TRANSPOSE -> WasmLispCompiler.TYPE_CALLABLE_BASE;
			// Two eq params -> eq.
			default -> WasmLispCompiler.TYPE_CALLABLE_BASE + 1;
		};
	}

	/**
	 * Builds the body of the given helper.
	 * @param fn the function index (one of the constants above)
	 * @param vecBase the module index of the first {@code vec:} helper
	 * ({@code WasmLispCompiler.FUNC_VEC_BASE}); the linalg kernels call {@code _v_new} /
	 * {@code _v_get} / {@code _v_set} and reuse four whole {@code vec:} kernels
	 * @return the encoded function body (locals + code + END)
	 */
	static byte[] build(int fn, int vecBase) {
		return switch (fn) {
			case ADD -> buildElementwise(Instruction.F64X2_ADD, Instruction.F64_ADD, vecBase);
			case SUB -> buildElementwise(Instruction.F64X2_SUB, Instruction.F64_SUB, vecBase);
			case MUL -> buildElementwise(Instruction.F64X2_MUL, Instruction.F64_MUL, vecBase);
			case DIV -> buildElementwise(Instruction.F64X2_DIV, Instruction.F64_DIV, vecBase);
			case SUM -> buildSum(vecBase);
			case NORM -> buildNorm(vecBase);
			case AMAX -> buildExtremum(true, false, vecBase);
			case AMIN -> buildExtremum(false, false, vecBase);
			case ARGMAX -> buildExtremum(true, true, vecBase);
			case ARGMIN -> buildExtremum(false, true, vecBase);
			case TRACE -> buildTrace(vecBase);
			case TRANSPOSE -> buildTranspose(vecBase);
			case RESHAPE -> buildReshape(vecBase);
			case DOT -> buildDot(vecBase);
			case OUTER -> buildOuter(vecBase);
			default -> throw new IllegalArgumentException("no linalg: simd helper " + fn);
		};
	}

	// --- add / sub / mul / div ---------------------------------------------------------

	// (linalg:<op> a b), the three shapes linalg::%la-bcast distinguishes:
	//
	// array (+) array equal dims and equal widths -> whole lane groups
	// array (+) scalar #d: f64x2 lanes; #f: an element loop in f64 (see below)
	// scalar (+) array the same, with the operands the other way round
	//
	// Anything else -- two numbers, a general (boxed) array, a ratio scalar, mixed
	// widths,
	// mismatched shapes -- returns null, and the call site runs the defun.
	//
	// params: 0 = a, 1 = b
	// i32: count 2, kind 3, shift 4, ng 5, g 6, rem 7, ok 8, i 9, len 10
	// f64: s 11
	// v128: old 12, cur 13
	// eq: res 14, vbD 15, vbA 16, da 17, db 18, nd 19, box 20
	// $v128arr: ga 21, gb 22, gd 23
	private static byte[] buildElementwise(int lane64Op, int scalar64Op, int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, bArg = 1;
		int count = 2, kind = 3, shift = 4, ng = 5, g = 6, rem = 7, ok = 8, i = 9, len = 10;
		int s = 11;
		int old = 12, cur = 13;
		int res = 14, vbD = 15, vbA = 16, da = 17, db = 18, nd = 19, box = 20;
		int ga = 21, gb = 22, gd = 23;

		block(w); // B0: the declined exit -- res stays null
		block(w); // B1: a is not an farray
		block(w); // B2: a and b are both farrays

		// --- array (+) array ---
		isFarray(w, a);
		isFarray(w, bArg);
		w.write(Instruction.I32_AND);
		brIfFalse(w, 0); // not both -> B1
		farrayKind(w, a);
		set(w, kind);
		get(w, kind);
		farrayKind(w, bArg);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 2); // mixed widths -> decline
		emitDimsEqual(w, a, bArg, da, db, len, i, ok);
		get(w, ok);
		brIfFalse(w, 2); // shape mismatch -> decline (the defun signals)
		loadHeader(w, a, count, kind, shift, ng);
		newVblock(w, count, kind, vbD, vecBase);
		vblockGroups(w, vbD, gd);
		farrayGroups(w, a, ga);
		farrayGroups(w, bArg, gb);
		get(w, kind);
		w.write(Instruction.IF, 0x40);
		WasmVecLoops.gcMap2(w, gd, ga, gb, ng, g, count, rem, old, cur, true, lane64Op);
		w.write(Instruction.ELSE);
		WasmVecLoops.gcMap2(w, gd, ga, gb, ng, g, count, rem, old, cur, false, lane64Op);
		w.write(Instruction.END);
		copyDims(w, a, nd, len, i, da);
		makeFarrayWithDims(w, nd, vbD);
		set(w, res);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END); // B2

		// --- array (+) scalar ---
		isFarray(w, a);
		brIfFalse(w, 0); // a is not an farray -> B0
		isNumber(w, bArg);
		brIfFalse(w, 1); // not a broadcastable number -> decline
		get(w, bArg);
		unboxF64(w, box);
		set(w, s);
		emitBroadcast(w, a, count, kind, shift, ng, g, rem, i, s, old, cur, vbD, vbA, nd, len, da, ga, gd, lane64Op,
				scalar64Op, false, vecBase);
		set(w, res);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END); // B1

		// --- scalar (+) array ---
		isFarray(w, bArg);
		brIfFalse(w, 0);
		isNumber(w, a);
		brIfFalse(w, 0);
		get(w, a);
		unboxF64(w, box);
		set(w, s);
		emitBroadcast(w, bArg, count, kind, shift, ng, g, rem, i, s, old, cur, vbD, vbA, nd, len, da, ga, gd, lane64Op,
				scalar64Op, true, vecBase);
		set(w, res);
		w.write(Instruction.END); // B0

		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 9, 1, 0, 2, 7, 3);
	}

	/**
	 * Leaves the broadcast result {@code $farray} on the stack. The double path runs
	 * {@code f64x2} lanes; the single path walks elements through {@code _v_get} /
	 * {@code _v_set}, which widen on read and narrow on write -- exactly what
	 * {@code %la-bcast}'s {@code emap} does, and what a splat of {@code (f32) s} would
	 * NOT do for a scalar that is not representable in single precision (0.1, say).
	 */
	private static void emitBroadcast(WasmWriter w, int arr, int count, int kind, int shift, int ng, int g, int rem,
			int i, int s, int old, int cur, int vbD, int vbA, int nd, int len, int da, int gv, int gd, int lane64Op,
			int scalar64Op, boolean reversed, int vecBase) {
		loadHeader(w, arr, count, kind, shift, ng);
		newVblock(w, count, kind, vbD, vecBase);
		farrayField(w, arr, 1);
		set(w, vbA);
		get(w, kind);
		w.write(Instruction.IF, 0x40);
		// single-float: widen, compute in f64, narrow -- one element at a time.
		openCountLoop(w, i, count);
		get(w, vbD);
		get(w, i);
		if (reversed) {
			get(w, s);
			vget(w, vbA, i, vecBase);
		}
		else {
			vget(w, vbA, i, vecBase);
			get(w, s);
		}
		w.write(scalar64Op);
		w.write(Instruction.CALL).writeSignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
		w.write(Instruction.DROP);
		closeCountLoop(w, i);
		w.write(Instruction.ELSE);
		vblockGroups(w, vbD, gd);
		farrayGroups(w, arr, gv);
		WasmVecLoops.gcBroadcastF64(w, gd, gv, ng, g, count, rem, old, cur, s, lane64Op, reversed);
		w.write(Instruction.END);
		copyDims(w, arr, nd, len, i, da);
		makeFarrayWithDims(w, nd, vbD);
	}

	// --- sum / norm --------------------------------------------------------------------

	// (linalg:sum a) -> the vec: reduction over the same flat store, at any rank.
	// An EMPTY array declines: the defun folds from the integer 0 and answers 0, not 0.0.
	// params: 0 = a. i32: count 1. eq: res 2.
	private static byte[] buildSum(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, count = 1, res = 2;
		block(w);
		isFarray(w, a);
		brIfFalse(w, 0);
		farrayCount(w, a);
		set(w, count);
		get(w, count);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		get(w, a);
		w.write(Instruction.CALL).writeSignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.SUM);
		set(w, res);
		w.write(Instruction.END);
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 1, 0, 0, 0, 1, 0);
	}

	// (linalg:norm a) = sqrt(dot(a, a)), FUSED: the defun spells it
	// (sqrt (sum (emap square a))) and allocates an intermediate array per call.
	// params: 0 = a. i32: count 1. eq: res 2.
	private static byte[] buildNorm(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, count = 1, res = 2;
		block(w);
		isFarray(w, a);
		brIfFalse(w, 0);
		farrayCount(w, a);
		set(w, count);
		get(w, count);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		get(w, a);
		get(w, a);
		w.write(Instruction.CALL).writeSignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.DOT);
		unboxFloatStruct(w);
		w.write(Instruction.F64_SQRT);
		boxFloat(w);
		set(w, res);
		w.write(Instruction.END);
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 1, 0, 0, 0, 1, 0);
	}

	// --- amax / amin / argmax / argmin ---------------------------------------------
	//
	// A lane MAX reduce is wrong twice over: the last group's padding lanes are ZERO, so
	// an all-negative array would answer 0, and a horizontal fold loses the "first
	// strictly
	// greater wins" tie-break the defun has. These walk elements instead. wasm's f64.gt
	// is
	// the same IEEE comparison the compiled `>` lowers to, so ties, -0.0 and NaN all
	// agree
	// with the defun. (The INTERPRETER's `>` is Double.compare, a total order -- the two
	// backends already disagree about (> 0.0 -0.0) without any of this.)
	//
	// params: 0 = a. i32: count 1, i 2, bi 3. f64: best 4, x 5. eq: res 6, vbA 7.
	private static byte[] buildExtremum(boolean max, boolean index, int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, count = 1, i = 2, bi = 3;
		int best = 4, x = 5;
		int res = 6, vbA = 7;
		block(w);
		isFarray(w, a);
		brIfFalse(w, 0);
		if (index) {
			// argmax/argmin are vector-only in linalg.lisp (they use length).
			farrayRank(w, a);
			i32Const(w, 1);
			w.write(Instruction.I32_NE);
			w.write(Instruction.BR_IF, 0);
		}
		farrayCount(w, a);
		set(w, count);
		get(w, count);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		farrayField(w, a, 1);
		set(w, vbA);
		i32Const(w, 0);
		set(w, i);
		vget(w, vbA, i, vecBase);
		set(w, best);
		i32Const(w, 0);
		set(w, bi);
		i32Const(w, 1);
		set(w, i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, i);
		get(w, count);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		vget(w, vbA, i, vecBase);
		set(w, x);
		get(w, x);
		get(w, best);
		w.write(max ? Instruction.F64_GT : Instruction.F64_LT);
		w.write(Instruction.IF, 0x40);
		get(w, x);
		set(w, best);
		get(w, i);
		set(w, bi);
		w.write(Instruction.END);
		get(w, i);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		if (index) {
			get(w, bi);
			w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
		else {
			get(w, best);
			boxFloat(w);
		}
		set(w, res);
		w.write(Instruction.END);
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 3, 2, 0, 0, 2, 0);
	}

	// (linalg:trace a): the main-diagonal sum of a square matrix, accumulated in f64 at
	// both widths -- the defun reads elements widened, so this is bit-identical.
	// params: 0 = a. i32: n 1, i 2, k 3. f64: acc 4. eq: res 5, vbA 6.
	private static byte[] buildTrace(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, n = 1, i = 2, k = 3;
		int acc = 4;
		int res = 5, vbA = 6;
		block(w);
		isFarray(w, a);
		brIfFalse(w, 0);
		farrayRank(w, a);
		i32Const(w, 2);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0);
		dimAt(w, a, 0, n);
		dimAt(w, a, 1, i);
		get(w, n);
		get(w, i);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0); // not square -> the defun signals
		farrayField(w, a, 1);
		set(w, vbA);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		set(w, acc);
		openCountLoop(w, i, n);
		get(w, i);
		get(w, n);
		w.write(Instruction.I32_MUL);
		get(w, i);
		w.write(Instruction.I32_ADD);
		set(w, k);
		get(w, acc);
		vget(w, vbA, k, vecBase);
		w.write(Instruction.F64_ADD);
		set(w, acc);
		closeCountLoop(w, i);
		get(w, acc);
		boxFloat(w);
		set(w, res);
		w.write(Instruction.END);
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 3, 1, 0, 0, 2, 0);
	}

	// --- transpose / reshape --------------------------------------------------------

	// (linalg:transpose a): a vector is returned UNCHANGED -- the very same object, so eq
	// still holds, as in the defun. A matrix is a strided scatter.
	// params: 0 = a. i32: r 1, c 2, i 3, j 4, src 5, dst 6, cnt 7.
	// eq: res 8, vbA 9, vbD 10, nd 11.
	private static byte[] buildTranspose(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, r = 1, c = 2, i = 3, j = 4, src = 5, dst = 6, kind = 7;
		int res = 8, vbA = 9, vbD = 10, nd = 11;
		block(w);
		isFarray(w, a);
		brIfFalse(w, 0);
		farrayRank(w, a);
		set(w, r);
		get(w, r);
		i32Const(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		get(w, a); // a rank-1 array transposes to itself
		set(w, res);
		w.write(Instruction.END);
		get(w, r);
		i32Const(w, 2);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0); // rank 1 (res already set) or rank > 2 (decline)
		dimAt(w, a, 0, r);
		dimAt(w, a, 1, c);
		farrayField(w, a, 1);
		set(w, vbA);
		farrayKind(w, a);
		set(w, kind);
		get(w, r);
		get(w, c);
		w.write(Instruction.I32_MUL);
		set(w, src);
		newVblock(w, src, kind, vbD, vecBase);
		openCountLoop(w, i, r);
		openCountLoop(w, j, c);
		// dst = j * r + i; src = i * c + j
		get(w, j);
		get(w, r);
		w.write(Instruction.I32_MUL);
		get(w, i);
		w.write(Instruction.I32_ADD);
		set(w, dst);
		get(w, i);
		get(w, c);
		w.write(Instruction.I32_MUL);
		get(w, j);
		w.write(Instruction.I32_ADD);
		set(w, src);
		get(w, vbD);
		get(w, dst);
		vget(w, vbA, src, vecBase);
		w.write(Instruction.CALL).writeSignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
		w.write(Instruction.DROP);
		closeCountLoop(w, j);
		closeCountLoop(w, i);
		newBuckets2(w, c, r, nd);
		makeFarrayWithDims(w, nd, vbD);
		set(w, res);
		w.write(Instruction.END);
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 7, 0, 0, 0, 4, 0);
	}

	// (linalg:reshape a shape): the vblock is a flat row-major lane layout, so a reshape
	// is
	// a whole-group copy plus a fresh dims. shape is a Lisp integer (i31) or a proper
	// list
	// of them; anything else, or a size mismatch, declines.
	// params: 0 = a, 1 = shape.
	// i32: count 2, kind 3, shift 4, ng 5, g 6, rank 7, prod 8, d 9.
	// eq: res 10, vbA 11, vbD 12, nd 13, cur 14.
	// $v128arr: gs 15, gd 16.
	private static byte[] buildReshape(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, shape = 1;
		int count = 2, kind = 3, shift = 4, ng = 5, g = 6, rank = 7, prod = 8, d = 9;
		int res = 10, vbA = 11, vbD = 12, nd = 13, cur = 14;
		int gs = 15, gd = 16;

		block(w);
		isFarray(w, a);
		brIfFalse(w, 0);
		// rank/prod from the shape designator; declines on anything but i31 / a list of
		// i31
		emitShapeDims(w, shape, rank, prod, d, g, nd, cur);
		get(w, rank);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		farrayCount(w, a);
		set(w, count);
		get(w, prod);
		get(w, count);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0); // size mismatch -> the defun signals
		loadHeader(w, a, count, kind, shift, ng);
		newVblock(w, count, kind, vbD, vecBase);
		vblockGroups(w, vbD, gd);
		farrayGroups(w, a, gs);
		// Copy ng + 1 groups: the real ones plus the trailing shuffle sentinel.
		get(w, ng);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, count);
		openCountLoop(w, g, count);
		get(w, gd);
		get(w, g);
		WasmVecLoops.groupGet(w, gs, g);
		WasmVecLoops.arraySet(w);
		closeCountLoop(w, g);
		makeFarrayWithDims(w, nd, vbD);
		set(w, res);
		w.write(Instruction.END);
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 8, 0, 0, 0, 5, 2);
	}

	/**
	 * Reads a {@code make-array} shape designator into a fresh {@code $hash_buckets}
	 * array ({@code ndLocal}), its rank ({@code rankLocal}) and its element product
	 * ({@code prodLocal}). A rank of 0 means "declined" -- the designator was neither a
	 * non-negative i31 nor a proper list of them.
	 */
	private static void emitShapeDims(WasmWriter w, int shape, int rank, int prod, int d, int i, int nd, int cur) {
		i32Const(w, 0);
		set(w, rank);
		i32Const(w, 1);
		set(w, prod);
		// An integer shape: a rank-1 array of that length.
		get(w, shape);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		get(w, shape);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		set(w, d);
		get(w, d);
		i32Const(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		i32Const(w, 1);
		set(w, rank);
		get(w, d);
		set(w, prod);
		newBuckets1(w, d, nd);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		// A proper list of non-negative integers: count it, then fill.
		countList(w, shape, rank, cur, d);
		get(w, rank);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.ELSE);
		get(w, rank);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		set(w, nd);
		get(w, shape);
		set(w, cur);
		openCountLoop(w, i, rank);
		consCar(w, cur);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		set(w, d);
		get(w, prod);
		get(w, d);
		w.write(Instruction.I32_MUL);
		set(w, prod);
		get(w, nd);
		castBuckets(w);
		get(w, i);
		get(w, d);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		consCdr(w, cur);
		set(w, cur);
		closeCountLoop(w, i);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	/**
	 * {@code rankLocal} = the length of the proper list in {@code shape}, or 0 when it is
	 * not a proper list of non-negative i31s (which is how {@code emitShapeDims}
	 * declines).
	 */
	private static void countList(WasmWriter w, int shape, int rank, int cur, int d) {
		i32Const(w, 0);
		set(w, rank);
		get(w, shape);
		set(w, cur);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, cur);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		// A non-i31 or negative car makes the whole designator unusable.
		consCar(w, cur);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		i32Const(w, 0);
		set(w, rank);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);
		consCar(w, cur);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		set(w, d);
		get(w, d);
		i32Const(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32Const(w, 0);
		set(w, rank);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);
		get(w, rank);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, rank);
		consCdr(w, cur);
		set(w, cur);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// A dotted tail (a non-nil, non-cons cdr) is not a shape either.
		get(w, cur);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		i32Const(w, 0);
		set(w, rank);
		w.write(Instruction.END);
	}

	// --- dot / outer -------------------------------------------------------------------

	// (linalg:dot a b): the numpy dispatch, for two packed operands of the same width and
	// rank <= 2. A scalar operand declines -- the defun routes that to linalg:mul, itself
	// intercepted. v.v and M.v reuse the vec: kernels (whose lane loops are the same);
	// v.M
	// and M.M run an ikj-free ijk element loop, whose f64 accumulator makes them
	// bit-identical to the defun at BOTH widths (the lanes of a matrix product run across
	// the output row, not along the summation axis, so nothing forces a single-precision
	// accumulator here -- see JvmSimdVectorTemplate.laMatmulF).
	//
	// params: 0 = a, 1 = b.
	// i32: ra 2, rb 3, n 4, m 5, p 6, i 7, j 8, k 9, kind 10, t 11.
	// f64: acc 12.
	// eq: res 13, vbA 14, vbB 15, vbD 16, nd 17, da 18, db 19.
	private static byte[] buildDot(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, bArg = 1;
		int ra = 2, rb = 3, n = 4, m = 5, p = 6, i = 7, j = 8, k = 9, kind = 10, t = 11;
		int acc = 12;
		int res = 13, vbA = 14, vbB = 15, vbD = 16, nd = 17;

		block(w); // B0 declined
		isFarray(w, a);
		isFarray(w, bArg);
		w.write(Instruction.I32_AND);
		brIfFalse(w, 0);
		farrayKind(w, a);
		set(w, kind);
		get(w, kind);
		farrayKind(w, bArg);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0); // mixed widths -> the defun widens both
		farrayRank(w, a);
		set(w, ra);
		farrayRank(w, bArg);
		set(w, rb);
		get(w, ra);
		i32Const(w, 2);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.BR_IF, 0);
		get(w, rb);
		i32Const(w, 2);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.BR_IF, 0);

		// vector . vector -> a scalar, via the vec: dot kernel.
		get(w, ra);
		i32Const(w, 1);
		w.write(Instruction.I32_EQ);
		get(w, rb);
		i32Const(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		dimAt(w, a, 0, n);
		dimAt(w, bArg, 0, m);
		get(w, n);
		get(w, m);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		get(w, a);
		get(w, bArg);
		w.write(Instruction.CALL).writeSignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.DOT);
		set(w, res);
		w.write(Instruction.END);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);

		// matrix . vector (GEMV) -> a vector, via the vec: matvec kernel.
		get(w, ra);
		i32Const(w, 2);
		w.write(Instruction.I32_EQ);
		get(w, rb);
		i32Const(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		dimAt(w, a, 1, m);
		dimAt(w, bArg, 0, t);
		get(w, m);
		get(w, t);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		get(w, a);
		get(w, bArg);
		w.write(Instruction.CALL).writeSignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.MATVEC);
		set(w, res);
		w.write(Instruction.END);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);

		// vector . matrix is the n = 1 case of the matrix product; matrix . matrix the
		// rest.
		get(w, ra);
		i32Const(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32Const(w, 1);
		set(w, n);
		dimAt(w, a, 0, m);
		w.write(Instruction.ELSE);
		dimAt(w, a, 0, n);
		dimAt(w, a, 1, m);
		w.write(Instruction.END);
		dimAt(w, bArg, 0, t);
		get(w, m);
		get(w, t);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0); // inner dimensions differ -> the defun signals
		dimAt(w, bArg, 1, p);
		farrayField(w, a, 1);
		set(w, vbA);
		farrayField(w, bArg, 1);
		set(w, vbB);
		get(w, n);
		get(w, p);
		w.write(Instruction.I32_MUL);
		set(w, t);
		newVblock(w, t, kind, vbD, vecBase);
		openCountLoop(w, i, n);
		openCountLoop(w, j, p);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		set(w, acc);
		openCountLoop(w, k, m);
		get(w, acc);
		get(w, i);
		get(w, m);
		w.write(Instruction.I32_MUL);
		get(w, k);
		w.write(Instruction.I32_ADD);
		set(w, t);
		vget(w, vbA, t, vecBase);
		get(w, k);
		get(w, p);
		w.write(Instruction.I32_MUL);
		get(w, j);
		w.write(Instruction.I32_ADD);
		set(w, t);
		vget(w, vbB, t, vecBase);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_ADD);
		set(w, acc);
		closeCountLoop(w, k);
		get(w, vbD);
		get(w, i);
		get(w, p);
		w.write(Instruction.I32_MUL);
		get(w, j);
		w.write(Instruction.I32_ADD);
		get(w, acc);
		w.write(Instruction.CALL).writeSignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
		w.write(Instruction.DROP);
		closeCountLoop(w, j);
		closeCountLoop(w, i);
		get(w, ra);
		i32Const(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		newBuckets1(w, p, nd); // a row vector times a matrix is a vector
		w.write(Instruction.ELSE);
		newBuckets2(w, n, p, nd);
		w.write(Instruction.END);
		makeFarrayWithDims(w, nd, vbD);
		set(w, res);
		w.write(Instruction.END); // B0

		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 10, 1, 0, 0, 5, 0);
	}

	// (linalg:outer u v): out[i][j] = uf[i] * vf[j], the operands flattened first -- and
	// a
	// packed backing IS the flattened data at any rank.
	// params: 0 = u, 1 = v.
	// i32: n 2, m 3, i 4, j 5, kind 6, t 7.
	// f64: s 8.
	// eq: res 9, vbU 10, vbV 11, vbD 12, nd 13.
	private static byte[] buildOuter(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int u = 0, v = 1;
		int n = 2, m = 3, i = 4, j = 5, kind = 6, t = 7;
		int s = 8;
		int res = 9, vbU = 10, vbV = 11, vbD = 12, nd = 13;
		block(w);
		isFarray(w, u);
		isFarray(w, v);
		w.write(Instruction.I32_AND);
		brIfFalse(w, 0);
		farrayKind(w, u);
		set(w, kind);
		get(w, kind);
		farrayKind(w, v);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0);
		farrayCount(w, u);
		set(w, n);
		farrayCount(w, v);
		set(w, m);
		farrayField(w, u, 1);
		set(w, vbU);
		farrayField(w, v, 1);
		set(w, vbV);
		get(w, n);
		get(w, m);
		w.write(Instruction.I32_MUL);
		set(w, t);
		newVblock(w, t, kind, vbD, vecBase);
		openCountLoop(w, i, n);
		vget(w, vbU, i, vecBase);
		set(w, s);
		openCountLoop(w, j, m);
		get(w, vbD);
		get(w, i);
		get(w, m);
		w.write(Instruction.I32_MUL);
		get(w, j);
		w.write(Instruction.I32_ADD);
		get(w, s);
		vget(w, vbV, j, vecBase);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.CALL).writeSignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
		w.write(Instruction.DROP);
		closeCountLoop(w, j);
		closeCountLoop(w, i);
		newBuckets2(w, n, m, nd);
		makeFarrayWithDims(w, nd, vbD);
		set(w, res);
		w.write(Instruction.END);
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 6, 1, 0, 0, 5, 0);
	}

	// --- shared emit helpers --------------------------------------------------------

	private static void block(WasmWriter w) {
		w.write(Instruction.BLOCK, 0x40);
	}

	/** Pushes 1 when the local holds a packed float array (nil and boxed arrays: 0). */
	private static void isFarray(WasmWriter w, int local) {
		get(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
	}

	/**
	 * Pushes 1 when the local holds a broadcastable number: an i31 integer or a boxed
	 * float. A ratio declines -- the defun's exact arithmetic on the scalar side is not
	 * reproduced here (the interpreter and JVM kernels decline it too).
	 */
	private static void isNumber(WasmWriter w, int local) {
		get(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		get(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.I32_OR);
	}

	/** {@code if (!cond) br depth} -- the declined exit. */
	private static void brIfFalse(WasmWriter w, int depth) {
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, depth);
	}

	/** Pushes the rank (the length of the dims bucket array). */
	private static void farrayRank(WasmWriter w, int argLocal) {
		farrayField(w, argLocal, 0);
		castBuckets(w);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
	}

	/** {@code outLocal = dims(arg)[index]}. */
	private static void dimAt(WasmWriter w, int argLocal, int index, int outLocal) {
		farrayField(w, argLocal, 0);
		castBuckets(w);
		i32Const(w, index);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		set(w, outLocal);
	}

	/** {@code outLocal = _v_new(count, kind)}: a fresh zeroed lane-group block. */
	private static void newVblock(WasmWriter w, int countLocal, int kindLocal, int outLocal, int vecBase) {
		get(w, countLocal);
		get(w, kindLocal);
		w.write(Instruction.CALL).writeSignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_NEW);
		set(w, outLocal);
	}

	/** Pushes {@code _v_get(vb, idx)} as an f64 (a single-float element is promoted). */
	private static void vget(WasmWriter w, int vbLocal, int idxLocal, int vecBase) {
		get(w, vbLocal);
		get(w, idxLocal);
		w.write(Instruction.CALL).writeSignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_GET);
	}

	/** Consumes the {@code $farray} on the stack and pushes its {@code $float} value. */
	private static void unboxFloatStruct(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeSignedLeb128(0);
	}

	private static void consCar(WasmWriter w, int cellLocal) {
		get(w, cellLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
	}

	private static void consCdr(WasmWriter w, int cellLocal) {
		get(w, cellLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
	}

	/** {@code for (i = 0; i < count; i++)} -- the body follows, then closeCountLoop. */
	private static void openCountLoop(WasmWriter w, int iLocal, int countLocal) {
		i32Const(w, 0);
		set(w, iLocal);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, iLocal);
		get(w, countLocal);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
	}

	private static void closeCountLoop(WasmWriter w, int iLocal) {
		get(w, iLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, iLocal);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	/** {@code outLocal} = a one-element dims bucket array holding {@code d0}. */
	private static void newBuckets1(WasmWriter w, int d0, int outLocal) {
		get(w, d0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		i32Const(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		set(w, outLocal);
	}

	/** {@code outLocal} = a two-element dims bucket array {@code (d0 d1)}. */
	private static void newBuckets2(WasmWriter w, int d0, int d1, int outLocal) {
		i32Const(w, 2);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		set(w, outLocal);
		get(w, outLocal);
		castBuckets(w);
		i32Const(w, 0);
		get(w, d0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, outLocal);
		castBuckets(w);
		i32Const(w, 1);
		get(w, d1);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	/**
	 * {@code outLocal} = a fresh copy of {@code arg}'s dims. Copied rather than shared,
	 * matching {@code linalg::%la-like}'s fresh {@code make-array}; the rank is at most a
	 * handful of entries.
	 */
	private static void copyDims(WasmWriter w, int argLocal, int outLocal, int lenLocal, int iLocal, int srcLocal) {
		farrayField(w, argLocal, 0);
		set(w, srcLocal);
		get(w, srcLocal);
		castBuckets(w);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		set(w, lenLocal);
		get(w, lenLocal);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		set(w, outLocal);
		openCountLoop(w, iLocal, lenLocal);
		get(w, outLocal);
		castBuckets(w);
		get(w, iLocal);
		get(w, srcLocal);
		castBuckets(w);
		get(w, iLocal);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		closeCountLoop(w, iLocal);
	}

	/** Pushes {@code struct.new $farray (dims, vblock)}. */
	private static void makeFarrayWithDims(WasmWriter w, int dimsLocal, int vbLocal) {
		get(w, dimsLocal);
		get(w, vbLocal);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FARRAY);
	}

	/** {@code okLocal} = whether the two farrays have identical dimension lists. */
	private static void emitDimsEqual(WasmWriter w, int a, int b, int da, int db, int len, int i, int ok) {
		farrayField(w, a, 0);
		set(w, da);
		farrayField(w, b, 0);
		set(w, db);
		i32Const(w, 0);
		set(w, ok);
		get(w, da);
		castBuckets(w);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		set(w, len);
		get(w, len);
		get(w, db);
		castBuckets(w);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32Const(w, 1);
		set(w, ok);
		i32Const(w, 0);
		set(w, i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, i);
		get(w, len);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		bucketAt(w, da, i);
		bucketAt(w, db, i);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		i32Const(w, 0);
		set(w, ok);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);
		get(w, i);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.END); // if
	}

	/**
	 * Pushes {@code i31.get_s(buckets[i])} for a bucket array held (as eq) in a local.
	 */
	private static void bucketAt(WasmWriter w, int bucketsLocal, int iLocal) {
		get(w, bucketsLocal);
		castBuckets(w);
		get(w, iLocal);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

}
