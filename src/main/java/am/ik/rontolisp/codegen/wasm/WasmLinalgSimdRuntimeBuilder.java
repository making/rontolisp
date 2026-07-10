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
 * <li>{@code dot}'s vector-matrix / matrix-matrix cases are the ikj lane loop over the
 * output row: each b row is read through the matvec shuffle window and
 * multiply-accumulated whole groups at a time into an f64 scratch row (see
 * {@code buildDot}). {@code outer} writes whole destination groups when its rows are lane
 * aligned; {@code transpose} runs lanes-by-lanes register blocks (two shuffles per f64
 * block, the eight-shuffle butterfly per f32 block) when BOTH dims are lane aligned.</li>
 * <li>Element loops through {@code _v_get} / {@code _v_set}, with no boxing and no
 * generic arithmetic: a SINGLE-float array against a scalar (it must widen, compute in
 * f64 and narrow, exactly as {@code emap} does -- so the promoting accessors are the
 * point, not an obstacle),
 * {@code amax}/{@code amin}/{@code argmax}/{@code argmin}/{@code trace}, and the
 * lane-unaligned {@code outer} / {@code transpose} shapes. These stay bit-identical to
 * the oracle and still run several times faster than the defun, which pays a
 * heap-allocated float box and a generic numeric dispatch per element.</li>
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

	// The element-wise unary ufuncs (todo 109): named emaps, so the kernels are the
	// vec: unary loops with the linalg decline protocol and a copied rank-n dims.
	// linalg:square / linalg:reciprocal need no kernel -- their spliced defuns call
	// linalg:mul / linalg:div, which are already intercepted.

	static final int EXP = 15;

	static final int SQRT = 16;

	static final int ABS = 17;

	static final int NEGATIVE = 18;

	static final int SIGN = 19;

	/**
	 * The number of functions this builder contributes (shifts {@code FUNC_USER_BASE}).
	 */
	static final int FUNC_COUNT = 20;

	/** The type index of each function, in emission order (for the function section). */
	static int typeIndexOf(int fn) {
		return switch (fn) {
			// One eq param -> eq.
			case SUM, NORM, AMAX, AMIN, ARGMAX, ARGMIN, TRACE, TRANSPOSE, EXP, SQRT, ABS, NEGATIVE, SIGN ->
				WasmLispCompiler.TYPE_CALLABLE_BASE;
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
			case EXP -> buildUnary(-1, true, vecBase);
			case SQRT -> buildUnary(WasmVecLoops.U_SQRT, false, vecBase);
			case ABS -> buildUnary(WasmVecLoops.U_ABS, false, vecBase);
			case NEGATIVE -> buildUnary(WasmVecLoops.U_NEG, false, vecBase);
			case SIGN -> buildUnary(-1, false, vecBase);
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

	// --- element-wise unary ufuncs (todo 109)
	// --------------------------------------------

	// (linalg:exp a) / (linalg:sqrt a) / (linalg:abs a) / (linalg:negative a) /
	// (linalg:sign a): a named emap over one packed operand of either width and any rank
	// (the flat store is rank-agnostic; the dims are copied into the result). Anything
	// else -- a general boxed array, a plain number -- declines to the defun.
	//
	// laneUop >= 0 runs whole lane groups (WasmVecLoops.gcMap1: sqrt / abs / negative,
	// each mirroring the wasm defun's own scalar semantics); laneUop < 0 walks elements
	// through _v_get / _v_set with the defun's exact f64 sequence (isExp: the
	// WasmExpCompiler Horner approximation, else WasmSignumCompiler's (x>0)-(x<0)).
	//
	// params: 0 = a
	// i32: count 1, kind 2, shift 3, ng 4, g 5, rem 6, i 7, len 8
	// f64: t 9, acc 10
	// v128: old 11, cur 12
	// eq: res 13, vbD 14, vbA 15, nd 16, da 17
	// $v128arr: gv 18, gd 19
	private static byte[] buildUnary(int laneUop, boolean isExp, int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0;
		int count = 1, kind = 2, shift = 3, ng = 4, g = 5, rem = 6, i = 7, len = 8;
		int t = 9, acc = 10;
		int old = 11, cur = 12;
		int res = 13, vbD = 14, vbA = 15, nd = 16, da = 17;
		int gv = 18, gd = 19;

		block(w); // B0: the declined exit -- res stays null
		isFarray(w, a);
		brIfFalse(w, 0);
		loadHeader(w, a, count, kind, shift, ng);
		newVblock(w, count, kind, vbD, vecBase);
		if (laneUop >= 0) {
			vblockGroups(w, vbD, gd);
			farrayGroups(w, a, gv);
			get(w, kind);
			w.write(Instruction.IF, 0x40);
			WasmVecLoops.gcMap1(w, gd, gv, ng, g, count, rem, old, cur, true, laneUop);
			w.write(Instruction.ELSE);
			WasmVecLoops.gcMap1(w, gd, gv, ng, g, count, rem, old, cur, false, laneUop);
			w.write(Instruction.END);
		}
		else {
			farrayField(w, a, 1);
			set(w, vbA);
			WasmVecLoops.openIndexLoop(w, i, count);
			get(w, vbD);
			get(w, i);
			vget(w, vbA, i, vecBase);
			if (isExp) {
				WasmVecSimdRuntimeBuilder.emitExpF64(w, t, acc);
			}
			else {
				WasmVecSimdRuntimeBuilder.emitSignumF64(w, t);
			}
			w.write(Instruction.CALL).writeSignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
			w.write(Instruction.DROP);
			WasmVecLoops.closeIndexLoop(w, i);
		}
		copyDims(w, a, nd, len, i, da);
		makeFarrayWithDims(w, nd, vbD);
		set(w, res);
		w.write(Instruction.END); // B0
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 8, 2, 0, 2, 5, 2);
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
	// with the defun -- and, since the todo-108 fixes, with the interpreter's and the
	// JVM's `>` as well (all three backends compare IEEE now).
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
	// still holds, as in the defun.
	//
	// When BOTH dims are whole multiples of the lane count, the matrix is transposed in
	// lanes x lanes register blocks: 2x2 via two i8x16.shuffles (f64), 4x4 via the
	// classic eight-shuffle butterfly (f32) -- pure data movement, so bit-identity is
	// free, and every destination group is written whole (no read-modify-write). Any
	// other shape keeps the element loop through _v_get / _v_set.
	//
	// params: 0 = a.
	// i32: r 1, c 2, i 3, j 4, src 5, dst 6, kind 7, cg 8, rg 9.
	// v128: va 10, vb 11, vc 12, vd 13, t0 14, t1 15, t2 16, t3 17.
	// eq: res 18, vbA 19, vbD 20, nd 21.
	// $v128arr: ga 22, gd 23.
	private static byte[] buildTranspose(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, r = 1, c = 2, i = 3, j = 4, src = 5, dst = 6, kind = 7, cg = 8, rg = 9;
		int va = 10, vb = 11, vc = 12, vd = 13, t0 = 14, t1 = 15, t2 = 16, t3 = 17;
		int res = 18, vbA = 19, vbD = 20, nd = 21;
		int ga = 22, gd = 23;
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
		vblockGroups(w, vbD, gd);
		farrayGroups(w, a, ga);
		// (r | c) & (lanes - 1): nonzero means some row is not a whole number of groups
		get(w, r);
		get(w, c);
		w.write(Instruction.I32_OR);
		laneMask(w, kind);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		// unaligned: the element loop
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
		w.write(Instruction.ELSE);
		get(w, kind);
		w.write(Instruction.IF, 0x40);
		emitTransposeBlocksSingle(w, r, c, cg, rg, i, j, va, vb, vc, vd, t0, t1, t2, t3, ga, gd);
		w.write(Instruction.ELSE);
		emitTransposeBlocksDouble(w, r, c, cg, rg, i, j, va, vb, ga, gd);
		w.write(Instruction.END);
		w.write(Instruction.END);
		newBuckets2(w, c, r, nd);
		makeFarrayWithDims(w, nd, vbD);
		set(w, res);
		w.write(Instruction.END);
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 9, 0, 0, 8, 4, 2);
	}

	// i8x16.shuffle immediates for the register-block transposes. H01/H23 take the low /
	// high 8 bytes (one f64 lane, two f32 lanes) of each operand; LO32/HI32 interleave
	// the low / high pair of 32-bit lanes.
	private static final int[] SHUF_H01 = { 0, 1, 2, 3, 4, 5, 6, 7, 16, 17, 18, 19, 20, 21, 22, 23 };

	private static final int[] SHUF_H23 = { 8, 9, 10, 11, 12, 13, 14, 15, 24, 25, 26, 27, 28, 29, 30, 31 };

	private static final int[] SHUF_LO32 = { 0, 1, 2, 3, 16, 17, 18, 19, 4, 5, 6, 7, 20, 21, 22, 23 };

	private static final int[] SHUF_HI32 = { 8, 9, 10, 11, 24, 25, 26, 27, 12, 13, 14, 15, 28, 29, 30, 31 };

	/** Consumes two v128 operands and pushes {@code i8x16.shuffle} with the immediate. */
	private static void shuffle(WasmWriter w, int[] imm) {
		WasmVecLoops.simd(w, Instruction.I8X16_SHUFFLE);
		for (int lane : imm) {
			w.write(lane);
		}
	}

	// 2x2 f64 blocks: rows 2i / 2i+1, column group j -> output rows 2j / 2j+1 at group
	// position i. out[2j] = (a0, b0) = H01(va, vb); out[2j+1] = (a1, b1) = H23(va, vb).
	private static void emitTransposeBlocksDouble(WasmWriter w, int r, int c, int cg, int rg, int i, int j, int va,
			int vb, int ga, int gd) {
		get(w, c);
		i32Const(w, 1);
		w.write(Instruction.I32_SHR_U);
		set(w, cg);
		get(w, r);
		i32Const(w, 1);
		w.write(Instruction.I32_SHR_U);
		set(w, rg);
		openCountLoop(w, i, rg);
		openCountLoop(w, j, cg);
		blockGroupGet(w, ga, i, 1, 0, cg, j, va);
		blockGroupGet(w, ga, i, 1, 1, cg, j, vb);
		blockGroupSetOpen(w, gd, j, 1, 0, rg, i);
		get(w, va);
		get(w, vb);
		shuffle(w, SHUF_H01);
		WasmVecLoops.arraySet(w);
		blockGroupSetOpen(w, gd, j, 1, 1, rg, i);
		get(w, va);
		get(w, vb);
		shuffle(w, SHUF_H23);
		WasmVecLoops.arraySet(w);
		closeCountLoop(w, j);
		closeCountLoop(w, i);
	}

	// 4x4 f32 blocks, the classic eight-shuffle butterfly: interleave row pairs 32-bit
	// lane-wise (t0..t3), then take half-vectors to form the four output groups.
	private static void emitTransposeBlocksSingle(WasmWriter w, int r, int c, int cg, int rg, int i, int j, int va,
			int vb, int vc, int vd, int t0, int t1, int t2, int t3, int ga, int gd) {
		get(w, c);
		i32Const(w, 2);
		w.write(Instruction.I32_SHR_U);
		set(w, cg);
		get(w, r);
		i32Const(w, 2);
		w.write(Instruction.I32_SHR_U);
		set(w, rg);
		openCountLoop(w, i, rg);
		openCountLoop(w, j, cg);
		blockGroupGet(w, ga, i, 2, 0, cg, j, va);
		blockGroupGet(w, ga, i, 2, 1, cg, j, vb);
		blockGroupGet(w, ga, i, 2, 2, cg, j, vc);
		blockGroupGet(w, ga, i, 2, 3, cg, j, vd);
		get(w, va);
		get(w, vb);
		shuffle(w, SHUF_LO32);
		set(w, t0);
		get(w, vc);
		get(w, vd);
		shuffle(w, SHUF_LO32);
		set(w, t1);
		get(w, va);
		get(w, vb);
		shuffle(w, SHUF_HI32);
		set(w, t2);
		get(w, vc);
		get(w, vd);
		shuffle(w, SHUF_HI32);
		set(w, t3);
		blockGroupSetOpen(w, gd, j, 2, 0, rg, i);
		get(w, t0);
		get(w, t1);
		shuffle(w, SHUF_H01);
		WasmVecLoops.arraySet(w);
		blockGroupSetOpen(w, gd, j, 2, 1, rg, i);
		get(w, t0);
		get(w, t1);
		shuffle(w, SHUF_H23);
		WasmVecLoops.arraySet(w);
		blockGroupSetOpen(w, gd, j, 2, 2, rg, i);
		get(w, t2);
		get(w, t3);
		shuffle(w, SHUF_H01);
		WasmVecLoops.arraySet(w);
		blockGroupSetOpen(w, gd, j, 2, 3, rg, i);
		get(w, t2);
		get(w, t3);
		shuffle(w, SHUF_H23);
		WasmVecLoops.arraySet(w);
		closeCountLoop(w, j);
		closeCountLoop(w, i);
	}

	// outLocal = arr[((blk << laneShift) + row) * stride + pos]: one input group of a
	// register block.
	private static void blockGroupGet(WasmWriter w, int arr, int blk, int laneShift, int row, int stride, int pos,
			int outLocal) {
		get(w, arr);
		blockGroupIndex(w, blk, laneShift, row, stride, pos);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_V128ARR);
		set(w, outLocal);
	}

	// Pushes arr and the index ((blk << laneShift) + row) * stride + pos; the caller
	// pushes the value and emits array.set.
	private static void blockGroupSetOpen(WasmWriter w, int arr, int blk, int laneShift, int row, int stride, int pos) {
		get(w, arr);
		blockGroupIndex(w, blk, laneShift, row, stride, pos);
	}

	private static void blockGroupIndex(WasmWriter w, int blk, int laneShift, int row, int stride, int pos) {
		get(w, blk);
		i32Const(w, laneShift);
		w.write(Instruction.I32_SHL);
		if (row != 0) {
			i32Const(w, row);
			w.write(Instruction.I32_ADD);
		}
		get(w, stride);
		w.write(Instruction.I32_MUL);
		get(w, pos);
		w.write(Instruction.I32_ADD);
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
	// intercepted. v.v and M.v reuse the vec: kernels (whose lane loops are the same).
	//
	// v.M and M.M are the ikj LANE loop over the output row: for each (i, k) the row k of
	// b is read through the same i8x16.shuffle window matvec uses (rows are not group
	// aligned in general) and multiply-accumulated, whole groups at a time, into a
	// lane-aligned f64 scratch row (`_v_new(p, 0)`, reused and re-zeroed per output row),
	// which is then written out element-wise through _v_set -- O(n*p) writes against
	// O(n*m*p) flops. The f64 accumulator is NOT optional at #f width: the lanes of a
	// matrix product run across the output row, not along the summation axis, so the
	// todo-106 single-precision-reduction contract does not apply, and accumulating in
	// f64 in k-ascending order (the defun's own ijk summation order per output cell)
	// keeps the result bit-identical to the oracle at BOTH widths -- see
	// JvmSimdVectorTemplate.laMatmulF, which keeps a double[] accumulator row for the
	// same reason. At #f width a b-row window group is widened exactly via
	// f64x2.promote_low_f32x4 (low half, then the high half swapped down by a shuffle).
	// The window's overhang past the row end and the accumulator's padding lanes may
	// hold garbage (the overhang lanes are REAL next-row elements here, unlike matvec's
	// zero-padded x), but lanes are independent and the write-out reads only the p real
	// elements.
	//
	// params: 0 = a, 1 = b.
	// i32: ra 2, rb 3, n 4, m 5, p 6, i 7, j 8, k 9, kind 10, t 11, base 12, off 13,
	// pg 14, qc 15, rowD 16, q2 17.
	// f64: aik 18.
	// v128: vs 19, wg 20.
	// eq: res 21, vbA 22, vbB 23, vbD 24, nd 25, acc 26.
	// $v128arr: gb 27, gacc 28.
	private static byte[] buildDot(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, bArg = 1;
		int ra = 2, rb = 3, n = 4, m = 5, p = 6, i = 7, j = 8, k = 9, kind = 10, t = 11, base = 12, off = 13, pg = 14,
				qc = 15, rowD = 16, q2 = 17;
		int aik = 18;
		int vs = 19, wg = 20;
		int res = 21, vbA = 22, vbB = 23, vbD = 24, nd = 25, acc = 26;
		int gb = 27, gacc = 28;

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
		// pg = ceil(p / 2): the f64 scratch row's group count
		get(w, p);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		i32Const(w, 1);
		w.write(Instruction.I32_SHR_U);
		set(w, pg);
		// acc = _v_new(p, 0): one f64 accumulator row, reused across output rows
		get(w, p);
		i32Const(w, 0);
		w.write(Instruction.CALL).writeSignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_NEW);
		set(w, acc);
		vblockGroups(w, acc, gacc);
		farrayGroups(w, bArg, gb);
		openCountLoop(w, i, n);
		get(w, i);
		get(w, p);
		w.write(Instruction.I32_MUL);
		set(w, rowD);
		// re-zero the accumulator row
		openCountLoop(w, j, pg);
		get(w, gacc);
		get(w, j);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		WasmVecLoops.simd(w, Instruction.F64X2_SPLAT);
		WasmVecLoops.arraySet(w);
		closeCountLoop(w, j);
		openCountLoop(w, k, m);
		// vs = f64x2.splat(a[i*m + k], widened)
		get(w, i);
		get(w, m);
		w.write(Instruction.I32_MUL);
		get(w, k);
		w.write(Instruction.I32_ADD);
		set(w, t);
		vget(w, vbA, t, vecBase);
		set(w, aik);
		get(w, aik);
		WasmVecLoops.simd(w, Instruction.F64X2_SPLAT);
		set(w, vs);
		// b row k starts at flat k*p, at the INPUT width's lane geometry
		get(w, k);
		get(w, p);
		w.write(Instruction.I32_MUL);
		set(w, t);
		get(w, kind);
		w.write(Instruction.IF, 0x40);
		get(w, t);
		i32Const(w, 2);
		w.write(Instruction.I32_SHR_U);
		set(w, base);
		get(w, t);
		i32Const(w, 3);
		w.write(Instruction.I32_AND);
		set(w, off);
		get(w, p);
		i32Const(w, 3);
		w.write(Instruction.I32_ADD);
		i32Const(w, 2);
		w.write(Instruction.I32_SHR_U);
		set(w, qc);
		WasmVecSimdRuntimeBuilder.emitLaneChain(w, off, 4, 0x40,
				o -> emitGemmRowSingle(w, qc, j, q2, base, o, vs, wg, gb, gacc));
		w.write(Instruction.ELSE);
		get(w, t);
		i32Const(w, 1);
		w.write(Instruction.I32_SHR_U);
		set(w, base);
		get(w, t);
		i32Const(w, 1);
		w.write(Instruction.I32_AND);
		set(w, off);
		get(w, p);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		i32Const(w, 1);
		w.write(Instruction.I32_SHR_U);
		set(w, qc);
		WasmVecSimdRuntimeBuilder.emitLaneChain(w, off, 2, 0x40,
				o -> emitGemmRowDouble(w, qc, j, base, o, vs, gb, gacc));
		w.write(Instruction.END);
		closeCountLoop(w, k);
		// write the row out: dst[rowD + j] = acc[j], narrowed once at #f width by _v_set
		openCountLoop(w, j, p);
		get(w, vbD);
		get(w, rowD);
		get(w, j);
		w.write(Instruction.I32_ADD);
		vget(w, acc, j, vecBase);
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
		return withLocals(b.toByteArray(), 16, 1, 0, 2, 6, 2);
	}

	// acc[q] += vs * window(b row, q), whole f64x2 groups, for one (i, k) at #d width.
	// `off` is a compile-time lane offset (an i8x16.shuffle immediate cannot be
	// computed).
	private static void emitGemmRowDouble(WasmWriter w, int qc, int q, int base, int off, int vs, int gb, int gacc) {
		openCountLoop(w, q, qc);
		get(w, gacc);
		get(w, q);
		WasmVecLoops.groupGet(w, gacc, q);
		get(w, vs);
		WasmVecSimdRuntimeBuilder.emitRowGroup(w, gb, base, q, off, false);
		WasmVecLoops.simd(w, Instruction.F64X2_MUL);
		WasmVecLoops.simd(w, Instruction.F64X2_ADD);
		WasmVecLoops.arraySet(w);
		closeCountLoop(w, q);
	}

	// The #f-width sibling: each f32x4 window group feeds TWO f64x2 accumulator groups
	// (2q and 2q + 1), each half widened exactly via f64x2.promote_low_f32x4 -- the high
	// half is first swapped down by an i8x16.shuffle. When p % 4 is 1 or 2 the high
	// half's accumulator index reaches the scratch row's sentinel group; that group
	// exists (a valid array slot) and is never read back.
	private static void emitGemmRowSingle(WasmWriter w, int qc, int q, int q2, int base, int off, int vs, int wg,
			int gb, int gacc) {
		openCountLoop(w, q, qc);
		WasmVecSimdRuntimeBuilder.emitRowGroup(w, gb, base, q, off, true);
		set(w, wg);
		get(w, q);
		i32Const(w, 1);
		w.write(Instruction.I32_SHL);
		set(w, q2);
		get(w, gacc);
		get(w, q2);
		WasmVecLoops.groupGet(w, gacc, q2);
		get(w, vs);
		get(w, wg);
		WasmVecLoops.simd(w, Instruction.F64X2_PROMOTE_LOW_F32X4);
		WasmVecLoops.simd(w, Instruction.F64X2_MUL);
		WasmVecLoops.simd(w, Instruction.F64X2_ADD);
		WasmVecLoops.arraySet(w);
		get(w, q2);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, q2);
		get(w, gacc);
		get(w, q2);
		WasmVecLoops.groupGet(w, gacc, q2);
		get(w, vs);
		get(w, wg);
		get(w, wg);
		WasmVecLoops.simd(w, Instruction.I8X16_SHUFFLE);
		for (int lane = 8; lane < 16; lane++) {
			w.write(lane);
		}
		for (int lane = 0; lane < 8; lane++) {
			w.write(lane);
		}
		WasmVecLoops.simd(w, Instruction.F64X2_PROMOTE_LOW_F32X4);
		WasmVecLoops.simd(w, Instruction.F64X2_MUL);
		WasmVecLoops.simd(w, Instruction.F64X2_ADD);
		WasmVecLoops.arraySet(w);
		closeCountLoop(w, q);
	}

	// (linalg:outer u v): out[i][j] = uf[i] * vf[j], the operands flattened first -- and
	// a packed backing IS the flattened data at any rank.
	//
	// When m is a whole multiple of the lane count every output row is group aligned
	// (row i occupies groups [i*mg, (i+1)*mg)) and v is aligned by definition, so each
	// row is splat(u[i]) * v over whole lane groups -- at #f width the f32x4 product is
	// bit-identical to the oracle's widen-multiply-narrow round trip (the innocuous-
	// double-rounding bound, as for the element-wise kernels). A misaligned m keeps the
	// element loop through _v_get / _v_set.
	//
	// params: 0 = u, 1 = v.
	// i32: n 2, m 3, i 4, j 5, kind 6, t 7, mg 8, rowBase 9.
	// f64: s 10.
	// v128: vs 11.
	// eq: res 12, vbU 13, vbV 14, vbD 15, nd 16.
	// $v128arr: gv 17, gd 18.
	private static byte[] buildOuter(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int u = 0, v = 1;
		int n = 2, m = 3, i = 4, j = 5, kind = 6, t = 7, mg = 8, rowBase = 9;
		int s = 10;
		int vs = 11;
		int res = 12, vbU = 13, vbV = 14, vbD = 15, nd = 16;
		int gv = 17, gd = 18;
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
		vblockGroups(w, vbD, gd);
		farrayGroups(w, v, gv);
		// m & (lanes - 1), lanes = 2 << kind: nonzero means rows are not group aligned
		get(w, m);
		laneMask(w, kind);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		// misaligned: the element loop
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
		w.write(Instruction.ELSE);
		get(w, kind);
		w.write(Instruction.IF, 0x40);
		emitOuterRows(w, n, mg, m, i, j, rowBase, vs, vbU, gv, gd, true, vecBase);
		w.write(Instruction.ELSE);
		emitOuterRows(w, n, mg, m, i, j, rowBase, vs, vbU, gv, gd, false, vecBase);
		w.write(Instruction.END);
		w.write(Instruction.END);
		newBuckets2(w, n, m, nd);
		makeFarrayWithDims(w, nd, vbD);
		set(w, res);
		w.write(Instruction.END);
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 8, 1, 0, 1, 5, 2);
	}

	// dst rows [0, n) = splat(u[i]) * v, whole lane groups (rows group aligned).
	private static void emitOuterRows(WasmWriter w, int n, int mg, int m, int i, int j, int rowBase, int vs, int vbU,
			int gv, int gd, boolean single, int vecBase) {
		get(w, m);
		i32Const(w, WasmVecLoops.laneShift(single));
		w.write(Instruction.I32_SHR_U);
		set(w, mg);
		openCountLoop(w, i, n);
		vget(w, vbU, i, vecBase);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
			WasmVecLoops.simd(w, Instruction.F32X4_SPLAT);
		}
		else {
			WasmVecLoops.simd(w, Instruction.F64X2_SPLAT);
		}
		set(w, vs);
		get(w, i);
		get(w, mg);
		w.write(Instruction.I32_MUL);
		set(w, rowBase);
		openCountLoop(w, j, mg);
		get(w, gd);
		get(w, rowBase);
		get(w, j);
		w.write(Instruction.I32_ADD);
		get(w, vs);
		WasmVecLoops.groupGet(w, gv, j);
		WasmVecLoops.simd(w, single ? Instruction.F32X4_MUL : Instruction.F64X2_MUL);
		WasmVecLoops.arraySet(w);
		closeCountLoop(w, j);
		closeCountLoop(w, i);
	}

	/** Pushes {@code (2 << kind) - 1}, the lane-alignment mask at the runtime width. */
	private static void laneMask(WasmWriter w, int kindLocal) {
		i32Const(w, 2);
		get(w, kindLocal);
		w.write(Instruction.I32_SHL);
		i32Const(w, 1);
		w.write(Instruction.I32_SUB);
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
