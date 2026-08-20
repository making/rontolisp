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
 * The wasm-GC {@code --simd} runtime for the {@code linalg:} kernels, the sibling of
 * {@link WasmVecSimdRuntimeBuilder}. Forty-one hand-assembled functions that
 * {@link WasmLinalgSimdCompiler} calls at an intercepted {@code linalg:} call site (the
 * last seven cover the declined call shapes: the general numpy broadcast, the axes
 * transpose and the axis folds).
 *
 * <h2>Why this exists: {@code --simd} used to make {@code linalg:} SLOWER here</h2>
 *
 * {@code --simd} switches the packed float-array representation to a {@code $vblock} over
 * an {@code (array (mut v128))} of lane groups. Every scalar element access then goes
 * through {@code _v_get} / {@code _v_set} -- an {@code array.get} plus an immediate-lane
 * {@code if}-chain, and for a write a whole-group read-modify-write. The {@code vec:}
 * kernels are intercepted at their call sites and never pay it; not one {@code linalg:}
 * function was, so linalg paid the new representation's cost and got nothing back
 * (measured: {@code linalg:add} 205 ms scalar, 230 ms under {@code --simd}).
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
 * That is safe because compiled nil is a null reference and none of them ever returns nil
 * ({@code linalg:array-equal}, which does, is deliberately not intercepted). A
 * {@code ref.test (ref $farray)} on nil is false, so a nil argument declines too.
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

	// The element-wise unary ufuncs: named emaps, so the kernels are the
	// vec: unary loops with the linalg decline protocol and a copied rank-n dims.
	// linalg:square / linalg:reciprocal need no kernel -- their spliced defuns call
	// linalg:mul / linalg:div, which are already intercepted.

	static final int EXP = 15;

	static final int SQRT = 16;

	static final int ABS = 17;

	static final int NEGATIVE = 18;

	static final int SIGN = 19;

	// The transcendental ufuncs (log / tanh / sin / cos / tan / asin / acos / atan /
	// sinh / cosh): element loops like exp / sign, mirroring
	// WasmLogCompiler / WasmTanhCompiler / WasmSinCosCompiler / WasmAtanCompiler /
	// WasmSinhCoshCompiler (no lane form exists).

	static final int LOG = 20;

	static final int TANH = 21;

	static final int SIN = 22;

	static final int COS = 23;

	static final int TAN = 24;

	// The inverse-trigonometric / hyperbolic ufuncs (asin / acos / atan / sinh / cosh):
	// element loops mirroring WasmAtanCompiler / WasmSinhCoshCompiler.

	static final int ASIN = 25;

	static final int ACOS = 26;

	static final int ATAN = 27;

	static final int SINH = 28;

	static final int COSH = 29;

	// The comparison-select ufuncs: the elementwise shape with the
	// arithmetic lane op replaced by a gt/lt mask + bitselect (array-array and the f64
	// scalar broadcast) or a scalar select (the widened f32-vs-scalar element loop).
	// linalg:clip / linalg:relu need no kernel -- their spliced defuns compose
	// linalg:maximum / linalg:minimum, which are intercepted (the square/reciprocal
	// pattern).

	static final int MAXIMUM = 30;

	static final int MINIMUM = 31;

	// The internal CNN window unfolding pair: pure index arithmetic through
	// _v_get / _v_set element loops -- no lanes, but no boxing and no generic numeric
	// dispatch either, which is what dominated the accelerated convolution runs.

	static final int IM2COL = 32;

	static final int COL2IM = 33;

	// The declined call shapes: the general numpy broadcast behind the
	// element-wise kernels' unequal-dims branch, the rank-n axes transpose, and the
	// axis folds of sum/amax/amin/argmax/argmin. All _v_get / _v_set element loops --
	// every element is read widened to f64, computed in f64 and narrowed only by a
	// store into a single-float result (the oracle's widen-compute-narrow round trip),
	// so all of them are bit-identical at both widths.

	static final int BCAST = 34;

	static final int TRANSPOSE_AXES = 35;

	static final int SUM_AXIS = 36;

	static final int AMAX_AXIS = 37;

	static final int AMIN_AXIS = 38;

	static final int ARGMAX_AXIS = 39;

	static final int ARGMIN_AXIS = 40;

	/**
	 * The number of functions this builder contributes (shifts {@code FUNC_USER_BASE}).
	 */
	static final int FUNC_COUNT = 41;

	// The BCAST op selector, passed as an i31 by the element-wise kernels. BOP_MAX /
	// BOP_MIN are the strict selects ((if (> x y) x y)): the SECOND operand wins any
	// false comparison, ties and NaN included.

	static final int BOP_ADD = 0;

	static final int BOP_SUB = 1;

	static final int BOP_MUL = 2;

	static final int BOP_DIV = 3;

	static final int BOP_MAX = 4;

	static final int BOP_MIN = 5;

	/** The type index of each function, in emission order (for the function section). */
	static int typeIndexOf(int fn) {
		return switch (fn) {
			// One eq param -> eq.
			case SUM, NORM, AMAX, AMIN, ARGMAX, ARGMIN, TRACE, TRANSPOSE, EXP, LOG, TANH, SIN, COS, TAN, ASIN, ACOS,
					ATAN, SINH, COSH, SQRT, ABS, NEGATIVE, SIGN ->
				WasmLispCompiler.TYPE_CALLABLE_BASE;
			// Three eq params -> eq.
			case BCAST, SUM_AXIS, AMAX_AXIS, AMIN_AXIS -> WasmLispCompiler.TYPE_CALLABLE_BASE + 2;
			// Five / six eq params -> eq (the always-present callable_arity_N types).
			case IM2COL -> WasmLispCompiler.TYPE_CALLABLE_BASE + 4;
			case COL2IM -> WasmLispCompiler.TYPE_CALLABLE_BASE + 5;
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
			case ADD -> buildElementwise(Instruction.F64X2_ADD, Instruction.F64_ADD, BOP_ADD, vecBase);
			case SUB -> buildElementwise(Instruction.F64X2_SUB, Instruction.F64_SUB, BOP_SUB, vecBase);
			case MUL -> buildElementwise(Instruction.F64X2_MUL, Instruction.F64_MUL, BOP_MUL, vecBase);
			case DIV -> buildElementwise(Instruction.F64X2_DIV, Instruction.F64_DIV, BOP_DIV, vecBase);
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
			case EXP -> buildUnary(-1, WasmVecSimdRuntimeBuilder.SCALAR_OP_EXP, vecBase);
			case SQRT -> buildUnary(WasmVecLoops.U_SQRT, -1, vecBase);
			case ABS -> buildUnary(WasmVecLoops.U_ABS, -1, vecBase);
			case NEGATIVE -> buildUnary(WasmVecLoops.U_NEG, -1, vecBase);
			case SIGN -> buildUnary(-1, WasmVecSimdRuntimeBuilder.SCALAR_OP_SIGN, vecBase);
			case LOG -> buildUnary(-1, WasmVecSimdRuntimeBuilder.SCALAR_OP_LOG, vecBase);
			case TANH -> buildUnary(-1, WasmVecSimdRuntimeBuilder.SCALAR_OP_TANH, vecBase);
			case SIN -> buildUnary(-1, WasmVecSimdRuntimeBuilder.SCALAR_OP_SIN, vecBase);
			case COS -> buildUnary(-1, WasmVecSimdRuntimeBuilder.SCALAR_OP_COS, vecBase);
			case TAN -> buildUnary(-1, WasmVecSimdRuntimeBuilder.SCALAR_OP_TAN, vecBase);
			case ASIN -> buildUnary(-1, WasmVecSimdRuntimeBuilder.SCALAR_OP_ASIN, vecBase);
			case ACOS -> buildUnary(-1, WasmVecSimdRuntimeBuilder.SCALAR_OP_ACOS, vecBase);
			case ATAN -> buildUnary(-1, WasmVecSimdRuntimeBuilder.SCALAR_OP_ATAN, vecBase);
			case SINH -> buildUnary(-1, WasmVecSimdRuntimeBuilder.SCALAR_OP_SINH, vecBase);
			case COSH -> buildUnary(-1, WasmVecSimdRuntimeBuilder.SCALAR_OP_COSH, vecBase);
			case MAXIMUM -> buildSelectElementwise(true, vecBase);
			case MINIMUM -> buildSelectElementwise(false, vecBase);
			case IM2COL -> buildIm2col(vecBase);
			case COL2IM -> buildCol2im(vecBase);
			case BCAST -> buildBcast(vecBase);
			case TRANSPOSE_AXES -> buildTransposeAxes(vecBase);
			case SUM_AXIS -> buildFoldAxis(BOP_ADD, vecBase);
			case AMAX_AXIS -> buildFoldAxis(BOP_MAX, vecBase);
			case AMIN_AXIS -> buildFoldAxis(BOP_MIN, vecBase);
			case ARGMAX_AXIS -> buildArgFoldAxis(true, vecBase);
			case ARGMIN_AXIS -> buildArgFoldAxis(false, vecBase);
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
	private static byte[] buildElementwise(int lane64Op, int scalar64Op, int bop, int vecBase) {
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
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		// Unequal shapes: the general numpy broadcast. BCAST
		// answers null for an incompatible pair, and B0 hands that straight to the
		// call site's decline branch -- the defun then signals its own error.
		get(w, a);
		get(w, bArg);
		i32Const(w, bop);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.CALL).writeUnsignedLeb128(WasmLispCompiler.linalgFuncBase() + BCAST);
		set(w, res);
		w.write(Instruction.BR, 3); // -> B0
		w.write(Instruction.END);
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

	// (linalg:maximum a b) / (linalg:minimum a b): the buildElementwise shapes with
	// every arithmetic op replaced by the strict-comparison select the %la-bcast
	// lambda spells out ((if (> x y) x y) / (if (< x y) x y); the SECOND operand wins
	// any false comparison, NaN and the -0.0/0.0 tie included). Array-array runs
	// gcMap2Select lane groups at both widths (a select only copies input bits, so
	// f32 lanes are bit-identical); a #d-vs-scalar broadcast runs the
	// gcBroadcastSelectF64 lane select; a #f-vs-scalar broadcast walks elements
	// widened through _v_get / _v_set so the comparison sees the FULL f64 scalar.
	// Same params and local layout as buildElementwise, plus one f64 temp (t) for the
	// element select.
	//
	// params: 0 = a, 1 = b
	// i32: count 2, kind 3, shift 4, ng 5, g 6, rem 7, ok 8, i 9, len 10
	// f64: s 11, t 12
	// v128: old 13, cur 14
	// eq: res 15, vbD 16, vbA 17, da 18, db 19, nd 20, box 21
	// $v128arr: ga 22, gb 23, gd 24
	private static byte[] buildSelectElementwise(boolean greater, int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, bArg = 1;
		int count = 2, kind = 3, shift = 4, ng = 5, g = 6, rem = 7, ok = 8, i = 9, len = 10;
		int s = 11, t = 12;
		int old = 13, cur = 14;
		int res = 15, vbD = 16, vbA = 17, da = 18, db = 19, nd = 20, box = 21;
		int ga = 22, gb = 23, gd = 24;

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
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		// Unequal shapes: the general numpy broadcast, with the strict select as the
		// op; null (incompatible) flows to the decline exit.
		get(w, a);
		get(w, bArg);
		i32Const(w, greater ? BOP_MAX : BOP_MIN);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.CALL).writeUnsignedLeb128(WasmLispCompiler.linalgFuncBase() + BCAST);
		set(w, res);
		w.write(Instruction.BR, 3); // -> B0
		w.write(Instruction.END);
		loadHeader(w, a, count, kind, shift, ng);
		newVblock(w, count, kind, vbD, vecBase);
		vblockGroups(w, vbD, gd);
		farrayGroups(w, a, ga);
		farrayGroups(w, bArg, gb);
		get(w, kind);
		w.write(Instruction.IF, 0x40);
		WasmVecLoops.gcMap2Select(w, gd, ga, gb, ng, g, count, rem, old, cur, true, greater);
		w.write(Instruction.ELSE);
		WasmVecLoops.gcMap2Select(w, gd, ga, gb, ng, g, count, rem, old, cur, false, greater);
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
		emitBroadcastSelect(w, a, count, kind, shift, ng, g, rem, i, s, t, old, cur, vbD, vbA, nd, len, da, ga, gd,
				greater, false, vecBase);
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
		emitBroadcastSelect(w, bArg, count, kind, shift, ng, g, rem, i, s, t, old, cur, vbD, vbA, nd, len, da, ga, gd,
				greater, true, vecBase);
		set(w, res);
		w.write(Instruction.END); // B0

		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 9, 2, 0, 2, 7, 3);
	}

	/**
	 * Leaves the broadcast select result {@code $farray} on the stack: the select sibling
	 * of {@link #emitBroadcast}. The double path runs {@code gcBroadcastSelectF64} lane
	 * groups; the single path walks elements through {@code _v_get} / {@code _v_set} and
	 * selects between the widened element and the FULL f64 scalar ({@code tLocal} holds
	 * the element across the comparison).
	 */
	private static void emitBroadcastSelect(WasmWriter w, int arr, int count, int kind, int shift, int ng, int g,
			int rem, int i, int s, int tLocal, int old, int cur, int vbD, int vbA, int nd, int len, int da, int gv,
			int gd, boolean greater, boolean reversed, int vecBase) {
		loadHeader(w, arr, count, kind, shift, ng);
		newVblock(w, count, kind, vbD, vecBase);
		farrayField(w, arr, 1);
		set(w, vbA);
		get(w, kind);
		w.write(Instruction.IF, 0x40);
		// single-float: widen, compare against the full f64 scalar, narrow on store.
		openCountLoop(w, i, count);
		get(w, vbD);
		get(w, i);
		vget(w, vbA, i, vecBase);
		set(w, tLocal);
		if (reversed) {
			// select(s, x, cmp(s, x))
			get(w, s);
			get(w, tLocal);
			get(w, s);
			get(w, tLocal);
		}
		else {
			// select(x, s, cmp(x, s))
			get(w, tLocal);
			get(w, s);
			get(w, tLocal);
			get(w, s);
		}
		w.write(greater ? Instruction.F64_GT : Instruction.F64_LT);
		w.write(Instruction.SELECT);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
		w.write(Instruction.DROP);
		closeCountLoop(w, i);
		w.write(Instruction.ELSE);
		vblockGroups(w, vbD, gd);
		farrayGroups(w, arr, gv);
		WasmVecLoops.gcBroadcastSelectF64(w, gd, gv, ng, g, count, rem, old, cur, s, greater, reversed);
		w.write(Instruction.END);
		copyDims(w, arr, nd, len, i, da);
		makeFarrayWithDims(w, nd, vbD);
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
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
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

	// --- element-wise unary ufuncs
	// --------------------------------------------

	// (linalg:exp a) / (linalg:log a) / (linalg:tanh a) / (linalg:sqrt a) /
	// (linalg:abs a) / (linalg:negative a) / (linalg:sign a): a named emap over one
	// packed operand of either width and any rank (the flat store is rank-agnostic; the
	// dims are copied into the result). Anything else -- a general boxed array, a plain
	// number -- declines to the defun.
	//
	// laneUop >= 0 runs whole lane groups (WasmVecLoops.gcMap1: sqrt / abs / negative,
	// each mirroring the wasm defun's own scalar semantics); laneUop < 0 walks elements
	// through _v_get / _v_set with the defun's exact f64 sequence (scalarOp names it:
	// the WasmExpCompiler Horner approximation, the WasmLogCompiler atanh series, the
	// WasmTanhCompiler clamped exp derivation, the WasmSinCosCompiler Cody-Waite
	// reduction, or WasmSignumCompiler's (x>0)-(x<0)).
	//
	// params: 0 = a
	// i32: count 1, kind 2, shift 3, ng 4, g 5, rem 6, i 7, len 8
	// f64: scratch 9-13 (the widest scalarOps, the sin/cos/tan and asin/acos/atan
	// families, need 5)
	// v128: old 14, cur 15
	// eq: res 16, vbD 17, vbA 18, nd 19, da 20
	// $v128arr: gv 21, gd 22
	private static byte[] buildUnary(int laneUop, int scalarOp, int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0;
		int count = 1, kind = 2, shift = 3, ng = 4, g = 5, rem = 6, i = 7, len = 8;
		int f64Base = 9;
		int old = 14, cur = 15;
		int res = 16, vbD = 17, vbA = 18, nd = 19, da = 20;
		int gv = 21, gd = 22;

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
			WasmVecSimdRuntimeBuilder.emitScalarUnaryF64(w, scalarOp, f64Base);
			w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
			w.write(Instruction.DROP);
			WasmVecLoops.closeIndexLoop(w, i);
		}
		copyDims(w, a, nd, len, i, da);
		makeFarrayWithDims(w, nd, vbD);
		set(w, res);
		w.write(Instruction.END); // B0
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 8, 5, 0, 2, 5, 2);
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
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.SUM);
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
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.DOT);
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
	// with the defun -- and with the interpreter's and the JVM's `>` as well (all three
	// backends compare IEEE now).
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
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_V128ARR);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
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
	// scratch row of the OPERAND width (`_v_new(p, kind)`, reused and re-zeroed per
	// output row), which is then written out element-wise through _v_set -- O(n*p)
	// writes against O(n*m*p) flops.
	//
	// The accumulator matches the operands, so an #f product folds each output cell in
	// f32: the `#f` reduction contract, in the defun's own k-ascending order. It is NOT
	// bit-identical to the oracle (which has only f64 to accumulate in), and the two
	// scalar backends make the same trade for a much sharper reason -- there, an f64
	// accumulator costs the lane loop outright. Lanes run across the output row, not
	// along the summation axis, so the lane count never enters the answer: every backend
	// folds each cell over k in the same order and produces the same bits.
	//
	// The window's overhang past the row end and the accumulator's padding lanes may
	// hold garbage (the overhang lanes are REAL next-row elements here, unlike matvec's
	// zero-padded x), but lanes are independent and the write-out reads only the p real
	// elements.
	//
	// params: 0 = a, 1 = b.
	// i32: ra 2, rb 3, n 4, m 5, p 6, i 7, j 8, k 9, kind 10, t 11, base 12, off 13,
	// pg 14, rowD 15, shift 16.
	// f64: aik 17.
	// v128: vs 18.
	// eq: res 19, vbA 20, vbB 21, vbD 22, nd 23, acc 24.
	// $v128arr: gb 25, gacc 26.
	private static byte[] buildDot(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, bArg = 1;
		int ra = 2, rb = 3, n = 4, m = 5, p = 6, i = 7, j = 8, k = 9, kind = 10, t = 11, base = 12, off = 13, pg = 14,
				rowD = 15, shift = 16;
		int aik = 17;
		int vs = 18;
		int res = 19, vbA = 20, vbB = 21, vbD = 22, nd = 23, acc = 24;
		int gb = 25, gacc = 26;

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
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.DOT);
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
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.MATVEC);
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
		// pg = ceil(p / lanes): the scratch row's group count at the operand width
		get(w, kind);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, shift);
		WasmVecSimdRuntimeBuilder.ceilShift(w, p, shift, pg);
		// acc = _v_new(p, kind): one accumulator row, reused across output rows
		get(w, p);
		get(w, kind);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_NEW);
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
		// aik = a[i*m + k]; b row k starts at flat k*p, at the INPUT width's lane
		// geometry
		get(w, i);
		get(w, m);
		w.write(Instruction.I32_MUL);
		get(w, k);
		w.write(Instruction.I32_ADD);
		set(w, t);
		vget(w, vbA, t, vecBase);
		set(w, aik);
		get(w, k);
		get(w, p);
		w.write(Instruction.I32_MUL);
		set(w, t);
		get(w, kind);
		w.write(Instruction.IF, 0x40);
		// vs = f32x4.splat(aik), demoted back to the width it was stored at
		get(w, aik);
		w.write(Instruction.F32_DEMOTE_F64);
		WasmVecLoops.simd(w, Instruction.F32X4_SPLAT);
		set(w, vs);
		get(w, t);
		i32Const(w, 2);
		w.write(Instruction.I32_SHR_U);
		set(w, base);
		get(w, t);
		i32Const(w, 3);
		w.write(Instruction.I32_AND);
		set(w, off);
		WasmVecSimdRuntimeBuilder.emitLaneChain(w, off, 4, 0x40,
				o -> emitGemmRow(w, pg, j, base, o, vs, gb, gacc, true));
		w.write(Instruction.ELSE);
		get(w, aik);
		WasmVecLoops.simd(w, Instruction.F64X2_SPLAT);
		set(w, vs);
		get(w, t);
		i32Const(w, 1);
		w.write(Instruction.I32_SHR_U);
		set(w, base);
		get(w, t);
		i32Const(w, 1);
		w.write(Instruction.I32_AND);
		set(w, off);
		WasmVecSimdRuntimeBuilder.emitLaneChain(w, off, 2, 0x40,
				o -> emitGemmRow(w, pg, j, base, o, vs, gb, gacc, false));
		w.write(Instruction.END);
		closeCountLoop(w, k);
		// write the row out: dst[rowD + j] = acc[j], an exact round trip -- _v_get
		// promotes the f32 lane and _v_set narrows it back
		openCountLoop(w, j, p);
		get(w, vbD);
		get(w, rowD);
		get(w, j);
		w.write(Instruction.I32_ADD);
		vget(w, acc, j, vecBase);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
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
		return withLocals(b.toByteArray(), 15, 1, 0, 1, 6, 2);
	}

	// acc[q] += vs * window(b row, q), whole lane groups, for one (i, k). The
	// accumulator and the operands share a width, so one loop serves both: the group
	// count is the scratch row's own, and every group index is its own. `off` is a
	// compile-time lane offset (an i8x16.shuffle immediate cannot be computed).
	private static void emitGemmRow(WasmWriter w, int pg, int q, int base, int off, int vs, int gb, int gacc,
			boolean single) {
		openCountLoop(w, q, pg);
		get(w, gacc);
		get(w, q);
		WasmVecLoops.groupGet(w, gacc, q);
		get(w, vs);
		WasmVecSimdRuntimeBuilder.emitRowGroup(w, gb, base, q, off, single);
		WasmVecLoops.simd(w, single ? Instruction.F32X4_MUL : Instruction.F64X2_MUL);
		WasmVecLoops.simd(w, single ? Instruction.F32X4_ADD : Instruction.F64X2_ADD);
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
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
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

	// --- %la-im2col / %la-col2im -------------------------------------------------------

	// (linalg::%la-im2col x fh fw stride pad): unfolds a rank-4 packed NCHW array into
	// the (n*oh*ow, c*fh*fw) window matrix, mirroring the linalg.lisp defun loop for
	// loop -- an element loop through _v_get / _v_set (a pure copy, so bit-identity is
	// free at both widths; _v_new zeroes the destination, so a window element in the
	// padding stays 0.0). Declines on anything but a rank-4 packed operand with i31
	// window parameters (positive filter/stride, non-negative pad) whose padded extents
	// are non-negative -- so the defun's floor is a plain truncating division here.
	//
	// params: 0 = x, 1 = fh, 2 = fw, 3 = stride, 4 = pad.
	// i32: fh 5, fw 6, stride 7, pad 8, n 9, c 10, h 11, w 12, eh 13, ew 14, oh 15,
	// ow 16, rows 17, cols 18, count 19, kind 20, ni 21, yo 22, xo 23, ci 24, fy 25,
	// fx 26, iy 27, ix0 28, ix 29, base 30, cursor 31, t 32.
	// eq: res 33, vbX 34, vbD 35, nd 36.
	private static byte[] buildIm2col(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int x = 0, fhA = 1, fwA = 2, strideA = 3, padA = 4;
		int fh = 5, fw = 6, stride = 7, pad = 8, n = 9, c = 10, h = 11, wl = 12, eh = 13, ew = 14, oh = 15, ow = 16,
				rows = 17, cols = 18, count = 19, kind = 20, ni = 21, yo = 22, xo = 23, ci = 24, fy = 25, fx = 26,
				iy = 27, ix0 = 28, ix = 29, base = 30, cursor = 31, t = 32;
		int res = 33, vbX = 34, vbD = 35, nd = 36;

		block(w); // B0: the declined exit -- res stays null
		isFarray(w, x);
		brIfFalse(w, 0);
		farrayRank(w, x);
		i32Const(w, 4);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0);
		dimAt(w, x, 0, n);
		dimAt(w, x, 1, c);
		dimAt(w, x, 2, h);
		dimAt(w, x, 3, wl);
		emitWindowParams(w, fhA, fwA, strideA, padA, fh, fw, stride, pad, h, wl, eh, ew, oh, ow);
		// rows = n*oh*ow; cols = c*fh*fw; count = rows*cols
		get(w, n);
		get(w, oh);
		w.write(Instruction.I32_MUL);
		get(w, ow);
		w.write(Instruction.I32_MUL);
		set(w, rows);
		get(w, c);
		get(w, fh);
		w.write(Instruction.I32_MUL);
		get(w, fw);
		w.write(Instruction.I32_MUL);
		set(w, cols);
		get(w, rows);
		get(w, cols);
		w.write(Instruction.I32_MUL);
		set(w, count);
		farrayKind(w, x);
		set(w, kind);
		newVblock(w, count, kind, vbD, vecBase);
		farrayField(w, x, 1);
		set(w, vbX);
		emitUnfoldLoops(w, true, vbX, vbD, n, c, h, wl, fh, fw, stride, pad, oh, ow, ni, yo, xo, ci, fy, fx, iy, ix0,
				ix, base, cursor, t, vecBase);
		newBuckets2(w, rows, cols, nd);
		makeFarrayWithDims(w, nd, vbD);
		set(w, res);
		w.write(Instruction.END); // B0
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 28, 0, 0, 0, 4, 0);
	}

	// (linalg::%la-col2im col dims fh fw stride pad): the im2col adjoint -- scatter-ADDS
	// the window matrix back into a fresh zero rank-4 array of the given dims
	// (overlapping windows accumulate; padding elements are dropped). The accumulate
	// reads both sides promoted to f64, adds, and lets _v_set narrow -- exactly the
	// defun's widen-add-narrow round trip, so bit-identity holds at both widths (the
	// exact f64 sum of two f32s narrows to the correctly rounded f32). dims must be a
	// proper list of exactly four non-negative i31s and col must hold exactly the
	// unfolded element count; anything else declines (the defun signals the shortfall).
	//
	// params: 0 = col, 1 = dims, 2 = fh, 3 = fw, 4 = stride, 5 = pad.
	// i32: fh 6, fw 7, stride 8, pad 9, n 10, c 11, h 12, w 13, eh 14, ew 15, oh 16,
	// ow 17, rows 18, cols 19, rank 20, prod 21, d 22, i 23, kind 24, ni 25, yo 26,
	// xo 27, ci 28, fy 29, fx 30, iy 31, ix0 32, ix 33, base 34, cursor 35, t 36.
	// eq: res 37, vbC 38, vbD 39, nd 40, cur 41.
	private static byte[] buildCol2im(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int col = 0, dimsA = 1, fhA = 2, fwA = 3, strideA = 4, padA = 5;
		int fh = 6, fw = 7, stride = 8, pad = 9, n = 10, c = 11, h = 12, wl = 13, eh = 14, ew = 15, oh = 16, ow = 17,
				rows = 18, cols = 19, rank = 20, prod = 21, d = 22, i = 23, kind = 24, ni = 25, yo = 26, xo = 27,
				ci = 28, fy = 29, fx = 30, iy = 31, ix0 = 32, ix = 33, base = 34, cursor = 35, t = 36;
		int res = 37, vbC = 38, vbD = 39, nd = 40, cur = 41;

		block(w); // B0: the declined exit -- res stays null
		isFarray(w, col);
		brIfFalse(w, 0);
		// dims: a proper list of exactly four non-negative i31s, read into a fresh
		// bucket array (which then becomes the result's dims, like %la-make's).
		emitShapeDims(w, dimsA, rank, prod, d, i, nd, cur);
		get(w, rank);
		i32Const(w, 4);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0);
		bucketConstAt(w, nd, 0, n);
		bucketConstAt(w, nd, 1, c);
		bucketConstAt(w, nd, 2, h);
		bucketConstAt(w, nd, 3, wl);
		emitWindowParams(w, fhA, fwA, strideA, padA, fh, fw, stride, pad, h, wl, eh, ew, oh, ow);
		// col must hold exactly rows*cols elements (the defun signals a shortfall).
		get(w, n);
		get(w, oh);
		w.write(Instruction.I32_MUL);
		get(w, ow);
		w.write(Instruction.I32_MUL);
		set(w, rows);
		get(w, c);
		get(w, fh);
		w.write(Instruction.I32_MUL);
		get(w, fw);
		w.write(Instruction.I32_MUL);
		set(w, cols);
		farrayCount(w, col);
		get(w, rows);
		get(w, cols);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0);
		farrayKind(w, col);
		set(w, kind);
		newVblock(w, prod, kind, vbD, vecBase);
		farrayField(w, col, 1);
		set(w, vbC);
		emitUnfoldLoops(w, false, vbC, vbD, n, c, h, wl, fh, fw, stride, pad, oh, ow, ni, yo, xo, ci, fy, fx, iy, ix0,
				ix, base, cursor, t, vecBase);
		makeFarrayWithDims(w, nd, vbD);
		set(w, res);
		w.write(Instruction.END); // B0
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 31, 0, 0, 0, 5, 0);
	}

	/**
	 * Unboxes and validates the four window parameters against the spatial extent held in
	 * the {@code h} / {@code w} locals: each argument must be an i31, filter and stride
	 * positive, pad non-negative, and both padded extents ({@code ehLocal} /
	 * {@code ewLocal}) non-negative. Computes {@code oh} / {@code ow}. Every failed check
	 * branches to depth 0 (the enclosing declined-exit block, which every check here sits
	 * directly inside -- callers must not nest this deeper).
	 */
	private static void emitWindowParams(WasmWriter w, int fhA, int fwA, int strideA, int padA, int fh, int fw,
			int stride, int pad, int h, int wl, int eh, int ew, int oh, int ow) {
		unboxI31OrDecline(w, fhA, fh);
		unboxI31OrDecline(w, fwA, fw);
		unboxI31OrDecline(w, strideA, stride);
		unboxI31OrDecline(w, padA, pad);
		// fh < 1 || fw < 1 || stride < 1 || pad < 0 -> decline
		get(w, fh);
		i32Const(w, 1);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 0);
		get(w, fw);
		i32Const(w, 1);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 0);
		get(w, stride);
		i32Const(w, 1);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 0);
		get(w, pad);
		i32Const(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 0);
		// eh = h + 2*pad - fh; ew = w + 2*pad - fw; either negative -> decline
		get(w, h);
		get(w, pad);
		i32Const(w, 2);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		get(w, fh);
		w.write(Instruction.I32_SUB);
		set(w, eh);
		get(w, eh);
		i32Const(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 0);
		get(w, wl);
		get(w, pad);
		i32Const(w, 2);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		get(w, fw);
		w.write(Instruction.I32_SUB);
		set(w, ew);
		get(w, ew);
		i32Const(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 0);
		// oh = eh / stride + 1; ow = ew / stride + 1 (both operands non-negative)
		get(w, eh);
		get(w, stride);
		w.write(Instruction.I32_DIV_S);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, oh);
		get(w, ew);
		get(w, stride);
		w.write(Instruction.I32_DIV_S);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, ow);
	}

	/**
	 * The shared six-deep index walk of {@code %la-im2col} / {@code %la-col2im},
	 * mirroring the defun loop for loop. With {@code gather} the destination cursor walks
	 * the window matrix and each in-bounds element is copied out of {@code vbSrc}
	 * ({@code out[cursor] = x[base+ix]}); without it the SOURCE cursor walks the window
	 * matrix and each in-bounds element is accumulated into the image
	 * ({@code img[base+ix] += col[cursor]} over {@code vbDst}, both sides promoted to f64
	 * and narrowed once by {@code _v_set}). An out-of-bounds filter row skips {@code fw}
	 * cursor positions at once, an out-of-bounds column just advances it.
	 */
	private static void emitUnfoldLoops(WasmWriter w, boolean gather, int vbSrc, int vbDst, int n, int c, int h, int wl,
			int fh, int fw, int stride, int pad, int oh, int ow, int ni, int yo, int xo, int ci, int fy, int fx, int iy,
			int ix0, int ix, int base, int cursor, int t, int vecBase) {
		i32Const(w, 0);
		set(w, cursor);
		openCountLoop(w, ni, n);
		openCountLoop(w, yo, oh);
		openCountLoop(w, xo, ow);
		openCountLoop(w, ci, c);
		openCountLoop(w, fy, fh);
		// iy = yo*stride + fy - pad
		get(w, yo);
		get(w, stride);
		w.write(Instruction.I32_MUL);
		get(w, fy);
		w.write(Instruction.I32_ADD);
		get(w, pad);
		w.write(Instruction.I32_SUB);
		set(w, iy);
		get(w, iy);
		i32Const(w, 0);
		w.write(Instruction.I32_GE_S);
		get(w, iy);
		get(w, h);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		// base = ((ni*c + ci)*h + iy)*w; ix0 = xo*stride - pad
		get(w, ni);
		get(w, c);
		w.write(Instruction.I32_MUL);
		get(w, ci);
		w.write(Instruction.I32_ADD);
		get(w, h);
		w.write(Instruction.I32_MUL);
		get(w, iy);
		w.write(Instruction.I32_ADD);
		get(w, wl);
		w.write(Instruction.I32_MUL);
		set(w, base);
		get(w, xo);
		get(w, stride);
		w.write(Instruction.I32_MUL);
		get(w, pad);
		w.write(Instruction.I32_SUB);
		set(w, ix0);
		openCountLoop(w, fx, fw);
		get(w, ix0);
		get(w, fx);
		w.write(Instruction.I32_ADD);
		set(w, ix);
		get(w, ix);
		i32Const(w, 0);
		w.write(Instruction.I32_GE_S);
		get(w, ix);
		get(w, wl);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		get(w, base);
		get(w, ix);
		w.write(Instruction.I32_ADD);
		set(w, t);
		if (gather) {
			// out[cursor] = x[t]
			get(w, vbDst);
			get(w, cursor);
			vget(w, vbSrc, t, vecBase);
		}
		else {
			// img[t] = img[t] + col[cursor]
			get(w, vbDst);
			get(w, t);
			vget(w, vbDst, t, vecBase);
			vget(w, vbSrc, cursor, vecBase);
			w.write(Instruction.F64_ADD);
		}
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
		w.write(Instruction.DROP);
		w.write(Instruction.END); // if in-bounds column
		get(w, cursor);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, cursor);
		closeCountLoop(w, fx);
		w.write(Instruction.ELSE);
		// The whole filter row fell in the padding: skip its fw cursor positions.
		get(w, cursor);
		get(w, fw);
		w.write(Instruction.I32_ADD);
		set(w, cursor);
		w.write(Instruction.END); // if in-bounds row
		closeCountLoop(w, fy);
		closeCountLoop(w, ci);
		closeCountLoop(w, xo);
		closeCountLoop(w, yo);
		closeCountLoop(w, ni);
	}

	/** Unboxes an i31 argument into an i32 local, or declines (depth 0) on a non-i31. */
	private static void unboxI31OrDecline(WasmWriter w, int argLocal, int outLocal) {
		get(w, argLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		brIfFalse(w, 0);
		get(w, argLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		set(w, outLocal);
	}

	/** {@code outLocal = i31.get_s(buckets[index])} for a constant index. */
	private static void bucketConstAt(WasmWriter w, int bucketsLocal, int index, int outLocal) {
		get(w, bucketsLocal);
		castBuckets(w);
		i32Const(w, index);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		set(w, outLocal);
	}

	// --- shared emit helpers --------------------------------------------------------

	// --- the declined call-shape kernels: bcast / axes / axis folds ------------------

	// (linalg::%la-bcast-loop op a b), reached from the element-wise kernels'
	// unequal-dims branch with the op as an i31: the general numpy broadcast walk. The
	// output's flat row-major index advances by 1 while each operand's flat index
	// follows its stride-0-padded strides through an odometer carry from the innermost
	// axis out. Every element is read widened (vget), computed in f64 and narrowed only
	// by the store into a single-float result (_v_set) -- %la-bcast's own emap rule, so
	// bit-identical at both widths. An incompatible pair, mixed widths, or an output
	// too large for one i32 index answers null (the defun signals its own error).
	//
	// params: 0 = a, 1 = b, 2 = op (i31).
	// i32: op 3, ra 4, rb 5, rank 6, k 7, ai 8, dxa 9, dxb 10, total 11, acc 12,
	// kind 13, ax 14, tmp 15, ox 16, oy 17.
	// f64: vx 18, vy 19, tf 20.
	// eq: res 21, da 22, db 23, od 24, sx 25, sy 26, idx 27, vbA 28, vbB 29, vbD 30.
	private static byte[] buildBcast(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, bArg = 1, opRef = 2;
		int op = 3, ra = 4, rb = 5, rank = 6, k = 7, ai = 8, dxa = 9, dxb = 10, total = 11, acc = 12, kind = 13,
				ax = 14, tmp = 15, ox = 16, oy = 17;
		int vx = 18, vy = 19, tf = 20;
		int res = 21, da = 22, db = 23, od = 24, sx = 25, sy = 26, idx = 27, vbA = 28, vbB = 29, vbD = 30;

		block(w); // B0: the declined exit -- res stays null
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
		get(w, opRef);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		set(w, op);
		farrayRank(w, a);
		set(w, ra);
		farrayRank(w, bArg);
		set(w, rb);
		get(w, ra);
		get(w, rb);
		get(w, ra);
		get(w, rb);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.SELECT);
		set(w, rank);
		farrayField(w, a, 0);
		set(w, da);
		farrayField(w, bArg, 0);
		set(w, db);
		newBucketsFilled(w, rank, od);
		w.write(Instruction.F64_CONST);
		w.writeF64(1.0);
		set(w, tf);
		// The broadcast shape (%la-bcast-shape): trailing axes align, a pair agrees
		// when equal or either is 1, the output extent is the larger; the running f64
		// product guards the output size (exact for any int below 2^53).
		openCountLoop(w, k, rank);
		get(w, ra);
		get(w, rank);
		w.write(Instruction.I32_SUB);
		get(w, k);
		w.write(Instruction.I32_ADD);
		set(w, ai);
		get(w, ai);
		i32Const(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x7F);
		bucketAt(w, da, ai);
		w.write(Instruction.ELSE);
		i32Const(w, 1);
		w.write(Instruction.END);
		set(w, dxa);
		get(w, rb);
		get(w, rank);
		w.write(Instruction.I32_SUB);
		get(w, k);
		w.write(Instruction.I32_ADD);
		set(w, ai);
		get(w, ai);
		i32Const(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x7F);
		bucketAt(w, db, ai);
		w.write(Instruction.ELSE);
		i32Const(w, 1);
		w.write(Instruction.END);
		set(w, dxb);
		get(w, dxa);
		get(w, dxb);
		w.write(Instruction.I32_NE);
		get(w, dxa);
		i32Const(w, 1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
		get(w, dxb);
		i32Const(w, 1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
		w.write(Instruction.BR_IF, 2); // incompatible -> B0
		get(w, dxa);
		get(w, dxb);
		get(w, dxa);
		get(w, dxb);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.SELECT);
		set(w, tmp);
		bucketSet(w, od, k, tmp);
		get(w, tf);
		get(w, tmp);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_MUL);
		set(w, tf);
		get(w, tf);
		w.write(Instruction.F64_CONST);
		w.writeF64(2147483000.0);
		w.write(Instruction.F64_GT);
		w.write(Instruction.BR_IF, 2); // output too large -> decline
		closeCountLoop(w, k);
		i32Const(w, 1);
		set(w, total);
		openCountLoop(w, k, rank);
		get(w, total);
		bucketAt(w, od, k);
		w.write(Instruction.I32_MUL);
		set(w, total);
		closeCountLoop(w, k);
		emitAlignedStrides(w, da, ra, rank, sx, ax, ai, dxa, acc, true);
		emitAlignedStrides(w, db, rb, rank, sy, ax, ai, dxa, acc, true);
		newBucketsFilled(w, rank, idx);
		farrayField(w, a, 1);
		set(w, vbA);
		farrayField(w, bArg, 1);
		set(w, vbB);
		newVblock(w, total, kind, vbD, vecBase);
		i32Const(w, 0);
		set(w, ox);
		i32Const(w, 0);
		set(w, oy);
		openCountLoop(w, k, total);
		get(w, vbD);
		get(w, k);
		vget(w, vbA, ox, vecBase);
		set(w, vx);
		vget(w, vbB, oy, vecBase);
		set(w, vy);
		emitApplyBop(w, op, vx, vy);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
		w.write(Instruction.DROP);
		emitOdometer(w, rank, ax, tmp, idx, od, sx, ox, sy, oy);
		closeCountLoop(w, k);
		makeFarrayWithDims(w, od, vbD);
		set(w, res);
		w.write(Instruction.END); // B0
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 15, 3, 0, 0, 10, 0);
	}

	/**
	 * Applies the {@code BOP_*} selector held in {@code opLocal} to the two f64 locals,
	 * leaving the f64 result on the stack. {@code BOP_MAX}/{@code BOP_MIN} are the strict
	 * selects (the second operand wins any false comparison).
	 */
	private static void emitApplyBop(WasmWriter w, int opLocal, int vxLocal, int vyLocal) {
		get(w, opLocal);
		i32Const(w, 2);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x7C);
		get(w, opLocal);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x7C);
		get(w, vxLocal);
		get(w, vyLocal);
		w.write(Instruction.F64_ADD);
		w.write(Instruction.ELSE);
		get(w, vxLocal);
		get(w, vyLocal);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		get(w, opLocal);
		i32Const(w, 2);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x7C);
		get(w, vxLocal);
		get(w, vyLocal);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.ELSE);
		get(w, opLocal);
		i32Const(w, 3);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x7C);
		get(w, vxLocal);
		get(w, vyLocal);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.ELSE);
		// The strict select: both comparisons are computed, an i32 select picks the
		// one the op asks for (a value below a block boundary is unreachable inside
		// it, so a result-typed IF around the comparison would not validate).
		get(w, vxLocal);
		get(w, vyLocal);
		get(w, vxLocal);
		get(w, vyLocal);
		w.write(Instruction.F64_GT);
		get(w, vxLocal);
		get(w, vyLocal);
		w.write(Instruction.F64_LT);
		get(w, opLocal);
		i32Const(w, BOP_MAX);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.SELECT);
		w.write(Instruction.SELECT);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// (linalg:transpose a axes), the 2-argument axes form (%la-transpose-axes): a
	// rank-n axis permutation, a pure copy through vget/_v_set (the f32 -> f64 -> f32
	// round trip of an unchanged value is exact), so trivially bit-identical. The axes
	// argument must be a proper list of i31s forming a permutation of 0..rank-1;
	// anything else -- nil (the defun's plain-transpose branch), a bare integer, a bad
	// permutation (the defun's error) -- declines.
	//
	// params: 0 = a, 1 = axes.
	// i32: rank 2, k 3, axv 4, count 5, kind 6, tmp 7, src 8, acc 9, ax 10.
	// eq: res 11, da 12, perm 13, seen 14, st 15, od 16, os 17, idx 18, vbA 19,
	// vbD 20, cur 21.
	private static byte[] buildTransposeAxes(int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, axes = 1;
		int rank = 2, k = 3, axv = 4, count = 5, kind = 6, tmp = 7, src = 8, acc = 9, ax = 10;
		int res = 11, da = 12, perm = 13, seen = 14, st = 15, od = 16, os = 17, idx = 18, vbA = 19, vbD = 20, cur = 21;

		block(w); // B0: the declined exit
		isFarray(w, a);
		brIfFalse(w, 0);
		farrayRank(w, a);
		set(w, rank);
		farrayField(w, a, 0);
		set(w, da);
		newBucketsFilled(w, rank, perm);
		newBucketsFilled(w, rank, seen);
		i32Const(w, 0);
		set(w, k);
		get(w, axes);
		set(w, cur);
		w.write(Instruction.BLOCK, 0x40); // BW: the axes walk
		w.write(Instruction.LOOP, 0x40);
		get(w, cur);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1); // end of list -> BW
		get(w, k);
		get(w, rank);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 2); // too many axes -> B0
		consCar(w, cur);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 2); // a non-integer axis -> B0
		consCar(w, cur);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		set(w, axv);
		get(w, axv);
		i32Const(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 2);
		get(w, axv);
		get(w, rank);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 2);
		bucketAt(w, seen, axv);
		w.write(Instruction.BR_IF, 2); // a repeated axis -> B0
		i32Const(w, 1);
		set(w, tmp);
		bucketSet(w, seen, axv, tmp);
		bucketSet(w, perm, k, axv);
		get(w, k);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, k);
		consCdr(w, cur);
		set(w, cur);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // BW
		get(w, cur);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0); // a dotted tail -> B0
		get(w, k);
		get(w, rank);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0); // not a full permutation -> B0
		emitAlignedStrides(w, da, rank, rank, st, ax, axv, tmp, acc, false);
		newBucketsFilled(w, rank, od);
		newBucketsFilled(w, rank, os);
		openCountLoop(w, k, rank);
		bucketAt(w, perm, k);
		set(w, axv);
		bucketAt(w, da, axv);
		set(w, tmp);
		bucketSet(w, od, k, tmp);
		bucketAt(w, st, axv);
		set(w, tmp);
		bucketSet(w, os, k, tmp);
		closeCountLoop(w, k);
		farrayCount(w, a);
		set(w, count);
		farrayKind(w, a);
		set(w, kind);
		farrayField(w, a, 1);
		set(w, vbA);
		newVblock(w, count, kind, vbD, vecBase);
		newBucketsFilled(w, rank, idx);
		i32Const(w, 0);
		set(w, src);
		openCountLoop(w, k, count);
		get(w, vbD);
		get(w, k);
		vget(w, vbA, src, vecBase);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
		w.write(Instruction.DROP);
		emitOdometer(w, rank, ax, tmp, idx, od, os, src, -1, -1);
		closeCountLoop(w, k);
		makeFarrayWithDims(w, od, vbD);
		set(w, res);
		w.write(Instruction.END); // B0
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 9, 0, 0, 0, 11, 0);
	}

	// (linalg:sum/amax/amin a :axis ax :keepdims k), the axis form (%la-fold-axis) over
	// the
	// flat index (o * axlen + j) * inner + i. BOP_ADD folds from the defun's 0 seed
	// with j from 0; BOP_MAX / BOP_MIN seed from the first element along the axis and
	// fold the defun's (if (> x acc) x acc) -- the ACCUMULATOR wins ties/NaN, the
	// opposite of the element-wise select -- with j from 1. Always accumulates in f64:
	// an axis fold is NOT a lane reduction, so the oracle's boxed double arithmetic is
	// mirrored exactly at both widths (_v_set narrows a single-float store, the
	// defun's own final store). A nil / non-integer / out-of-range axis and an empty
	// axis decline; a vector without keepdims reduces to the boxed f64 accumulator.
	//
	// params: 0 = a, 1 = axis, 2 = keepdims.
	// i32: rank 3, ax 4, axlen 5, outer 6, inner 7, o 8, i 9, j 10, base 11, kind 12,
	// k 13, tmp 14, keepF 15, odRank 16.
	// f64: accF 17, vf 18.
	// eq: res 19, da 20, od 21, vbA 22, vbD 23.
	private static byte[] buildFoldAxis(int bop, int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, axisRef = 1, keep = 2;
		int rank = 3, ax = 4, axlen = 5, outer = 6, inner = 7, o = 8, i = 9, j = 10, base = 11, kind = 12, k = 13,
				tmp = 14, keepF = 15, odRank = 16;
		int accF = 17, vf = 18;
		int res = 19, da = 20, od = 21, vbA = 22, vbD = 23;

		block(w); // B0: the declined exit
		isFarray(w, a);
		brIfFalse(w, 0);
		emitNormAxis(w, axisRef, a, rank, ax);
		get(w, ax);
		i32Const(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 0); // nil / non-integer / out of range -> decline
		farrayField(w, a, 0);
		set(w, da);
		bucketAt(w, da, ax);
		set(w, axlen);
		get(w, axlen);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0); // an empty axis -> decline (the defun errors)
		get(w, keep);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		set(w, keepF);
		farrayKind(w, a);
		set(w, kind);
		farrayField(w, a, 1);
		set(w, vbA);
		emitHeadTailSizes(w, da, rank, ax, outer, inner, k);
		// A vector without keepdims reduces to the scalar accumulator itself, NOT
		// narrowed to the input's width (the defun returns the boxed fold value).
		get(w, rank);
		i32Const(w, 1);
		w.write(Instruction.I32_EQ);
		get(w, keepF);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		i32Const(w, 0);
		set(w, base);
		i32Const(w, 1);
		set(w, inner);
		emitFoldRun(w, bop, vecBase, vbA, base, inner, axlen, j, k, accF, vf);
		get(w, accF);
		boxFloat(w);
		set(w, res);
		w.write(Instruction.BR, 1); // -> B0
		w.write(Instruction.END);
		get(w, outer);
		get(w, inner);
		w.write(Instruction.I32_MUL);
		set(w, tmp);
		newVblock(w, tmp, kind, vbD, vecBase);
		openCountLoop(w, o, outer);
		openCountLoop(w, i, inner);
		get(w, o);
		get(w, axlen);
		w.write(Instruction.I32_MUL);
		get(w, inner);
		w.write(Instruction.I32_MUL);
		get(w, i);
		w.write(Instruction.I32_ADD);
		set(w, base);
		emitFoldRun(w, bop, vecBase, vbA, base, inner, axlen, j, k, accF, vf);
		get(w, vbD);
		get(w, o);
		get(w, inner);
		w.write(Instruction.I32_MUL);
		get(w, i);
		w.write(Instruction.I32_ADD);
		get(w, accF);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
		w.write(Instruction.DROP);
		closeCountLoop(w, i);
		closeCountLoop(w, o);
		emitAxisShape(w, da, rank, ax, keepF, od, odRank, k, tmp, j);
		makeFarrayWithDims(w, od, vbD);
		set(w, res);
		w.write(Instruction.END); // B0
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 14, 2, 0, 0, 5, 0);
	}

	/**
	 * One fold along the axis: {@code accF} = the defun's fold over
	 * {@code a[base + j * inner]} for {@code j} in {@code 0..axlen} ({@code BOP_ADD} from
	 * the 0 seed) or {@code 1..axlen} (seeded from the first element).
	 */
	private static void emitFoldRun(WasmWriter w, int bop, int vecBase, int vbA, int base, int inner, int axlen, int j,
			int k, int accF, int vf) {
		if (bop == BOP_ADD) {
			w.write(Instruction.F64_CONST);
			w.writeF64(0.0);
			set(w, accF);
			i32Const(w, 0);
			set(w, j);
		}
		else {
			vget(w, vbA, base, vecBase);
			set(w, accF);
			i32Const(w, 1);
			set(w, j);
		}
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, j);
		get(w, axlen);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		get(w, base);
		get(w, j);
		get(w, inner);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		set(w, k);
		vget(w, vbA, k, vecBase);
		set(w, vf);
		if (bop == BOP_ADD) {
			get(w, accF);
			get(w, vf);
			w.write(Instruction.F64_ADD);
			set(w, accF);
		}
		else {
			get(w, vf);
			get(w, accF);
			w.write(bop == BOP_MAX ? Instruction.F64_GT : Instruction.F64_LT);
			w.write(Instruction.IF, 0x40);
			get(w, vf);
			set(w, accF);
			w.write(Instruction.END);
		}
		get(w, j);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, j);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	// (linalg:argmax/argmin v :axis ax), the axis form (%la-argfold-axis): the per-slice
	// index of the first element winning the strict comparison, the axis always
	// dropped. A vector reduces to the i31 index itself; a higher rank fills a packed
	// DOUBLE array of index values at any input width (indices are exact in f64).
	//
	// params: 0 = a, 1 = axis.
	// i32: rank 2, ax 3, axlen 4, outer 5, inner 6, o 7, i 8, j 9, base 10, k 11,
	// bi 12, tmp 13, zero 14.
	// f64: best 15, vf 16.
	// eq: res 17, da 18, od 19, vbA 20, vbD 21.
	private static byte[] buildArgFoldAxis(boolean max, int vecBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int a = 0, axisRef = 1;
		int rank = 2, ax = 3, axlen = 4, outer = 5, inner = 6, o = 7, i = 8, j = 9, base = 10, k = 11, bi = 12,
				tmp = 13, zero = 14;
		int best = 15, vf = 16;
		int res = 17, da = 18, od = 19, vbA = 20, vbD = 21;

		block(w); // B0: the declined exit
		isFarray(w, a);
		brIfFalse(w, 0);
		emitNormAxis(w, axisRef, a, rank, ax);
		get(w, ax);
		i32Const(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 0);
		farrayField(w, a, 0);
		set(w, da);
		bucketAt(w, da, ax);
		set(w, axlen);
		get(w, axlen);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0); // an empty axis -> decline (the defun errors)
		farrayField(w, a, 1);
		set(w, vbA);
		emitHeadTailSizes(w, da, rank, ax, outer, inner, k);
		get(w, rank);
		i32Const(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32Const(w, 0);
		set(w, base);
		i32Const(w, 1);
		set(w, inner);
		emitArgFoldRun(w, max, vecBase, vbA, base, inner, axlen, j, k, bi, best, vf);
		get(w, bi);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		set(w, res);
		w.write(Instruction.BR, 1); // -> B0
		w.write(Instruction.END);
		get(w, outer);
		get(w, inner);
		w.write(Instruction.I32_MUL);
		set(w, tmp);
		i32Const(w, 0);
		set(w, zero); // the result is always a DOUBLE array (kind 0)
		newVblock(w, tmp, zero, vbD, vecBase);
		openCountLoop(w, o, outer);
		openCountLoop(w, i, inner);
		get(w, o);
		get(w, axlen);
		w.write(Instruction.I32_MUL);
		get(w, inner);
		w.write(Instruction.I32_MUL);
		get(w, i);
		w.write(Instruction.I32_ADD);
		set(w, base);
		emitArgFoldRun(w, max, vecBase, vbA, base, inner, axlen, j, k, bi, best, vf);
		get(w, vbD);
		get(w, o);
		get(w, inner);
		w.write(Instruction.I32_MUL);
		get(w, i);
		w.write(Instruction.I32_ADD);
		get(w, bi);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_SET);
		w.write(Instruction.DROP);
		closeCountLoop(w, i);
		closeCountLoop(w, o);
		emitAxisShape(w, da, rank, ax, -1, od, tmp, k, base, j);
		makeFarrayWithDims(w, od, vbD);
		set(w, res);
		w.write(Instruction.END); // B0
		get(w, res);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 13, 2, 0, 0, 5, 0);
	}

	/** One argfold along the axis: {@code bi} = the first strict-comparison winner. */
	private static void emitArgFoldRun(WasmWriter w, boolean max, int vecBase, int vbA, int base, int inner, int axlen,
			int j, int k, int bi, int best, int vf) {
		vget(w, vbA, base, vecBase);
		set(w, best);
		i32Const(w, 0);
		set(w, bi);
		i32Const(w, 1);
		set(w, j);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, j);
		get(w, axlen);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		get(w, base);
		get(w, j);
		get(w, inner);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		set(w, k);
		vget(w, vbA, k, vecBase);
		set(w, vf);
		get(w, vf);
		get(w, best);
		w.write(max ? Instruction.F64_GT : Instruction.F64_LT);
		w.write(Instruction.IF, 0x40);
		get(w, vf);
		set(w, best);
		get(w, j);
		set(w, bi);
		w.write(Instruction.END);
		get(w, j);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, j);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	/**
	 * {@code axLocal} = the axis argument normalized against the rank
	 * ({@code %la-norm-axis}), or -1 when it is not an i31 or is out of range.
	 * {@code rankLocal} is set to the operand's rank as a side effect.
	 */
	private static void emitNormAxis(WasmWriter w, int axisRef, int a, int rankLocal, int axLocal) {
		farrayRank(w, a);
		set(w, rankLocal);
		i32Const(w, -1);
		set(w, axLocal);
		get(w, axisRef);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		get(w, axisRef);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		set(w, axLocal);
		get(w, axLocal);
		i32Const(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		get(w, axLocal);
		get(w, rankLocal);
		w.write(Instruction.I32_ADD);
		set(w, axLocal);
		w.write(Instruction.END);
		get(w, axLocal);
		get(w, rankLocal);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		i32Const(w, -1);
		set(w, axLocal);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	/**
	 * {@code outerLocal} / {@code innerLocal} = the products of the dims entries before /
	 * after the (normalized) axis ({@code %la-head-size} / {@code %la-tail-size}).
	 */
	private static void emitHeadTailSizes(WasmWriter w, int da, int rankLocal, int axLocal, int outerLocal,
			int innerLocal, int kLocal) {
		i32Const(w, 1);
		set(w, outerLocal);
		openCountLoop(w, kLocal, axLocal);
		get(w, outerLocal);
		bucketAt(w, da, kLocal);
		w.write(Instruction.I32_MUL);
		set(w, outerLocal);
		closeCountLoop(w, kLocal);
		i32Const(w, 1);
		set(w, innerLocal);
		get(w, axLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, kLocal);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, kLocal);
		get(w, rankLocal);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		get(w, innerLocal);
		bucketAt(w, da, kLocal);
		w.write(Instruction.I32_MUL);
		set(w, innerLocal);
		get(w, kLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, kLocal);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	/**
	 * {@code odLocal} = the dims with the axis dropped -- or kept as extent 1 when the
	 * i32 local {@code keepFLocal} is non-zero (pass -1 for the argfolds, which always
	 * drop). Mirrors {@code %la-axis-shape}.
	 */
	private static void emitAxisShape(WasmWriter w, int da, int rankLocal, int axLocal, int keepFLocal, int odLocal,
			int odRankLocal, int kLocal, int writeLocal, int valLocal) {
		get(w, rankLocal);
		get(w, rankLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_SUB);
		if (keepFLocal >= 0) {
			get(w, keepFLocal);
		}
		else {
			i32Const(w, 0);
		}
		w.write(Instruction.SELECT);
		set(w, odRankLocal);
		newBucketsFilled(w, odRankLocal, odLocal);
		i32Const(w, 0);
		set(w, writeLocal);
		openCountLoop(w, kLocal, rankLocal);
		get(w, kLocal);
		get(w, axLocal);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		bucketAt(w, da, kLocal);
		set(w, valLocal);
		bucketSet(w, odLocal, writeLocal, valLocal);
		get(w, writeLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, writeLocal);
		w.write(Instruction.ELSE);
		if (keepFLocal >= 0) {
			get(w, keepFLocal);
			w.write(Instruction.IF, 0x40);
			i32Const(w, 1);
			set(w, valLocal);
			bucketSet(w, odLocal, writeLocal, valLocal);
			get(w, writeLocal);
			i32Const(w, 1);
			w.write(Instruction.I32_ADD);
			set(w, writeLocal);
			w.write(Instruction.END);
		}
		w.write(Instruction.END);
		closeCountLoop(w, kLocal);
	}

	/**
	 * Row-major strides of the {@code opRankLocal}-dim operand whose dims buckets are in
	 * {@code dimsLocal}, aligned to a broadcast rank of {@code rankLocal} entries, into a
	 * fresh buckets array ({@code outLocal}). With {@code zeroStretched} every stretched
	 * axis (extent 1 or missing) gets stride 0 ({@code %la-bcast-strides}); without it
	 * the plain row-major strides ({@code %la-strides}, requires
	 * {@code opRankLocal == rankLocal}).
	 */
	private static void emitAlignedStrides(WasmWriter w, int dimsLocal, int opRankLocal, int rankLocal, int outLocal,
			int axLocal, int aiLocal, int nLocal, int accLocal, boolean zeroStretched) {
		newBucketsFilled(w, rankLocal, outLocal);
		i32Const(w, 1);
		set(w, accLocal);
		get(w, rankLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, axLocal);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, axLocal);
		i32Const(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 1);
		get(w, opRankLocal);
		get(w, rankLocal);
		w.write(Instruction.I32_SUB);
		get(w, axLocal);
		w.write(Instruction.I32_ADD);
		set(w, aiLocal);
		get(w, aiLocal);
		i32Const(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x7F);
		bucketAt(w, dimsLocal, aiLocal);
		w.write(Instruction.ELSE);
		i32Const(w, 1);
		w.write(Instruction.END);
		set(w, nLocal);
		if (zeroStretched) {
			get(w, nLocal);
			i32Const(w, 1);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x7F);
			i32Const(w, 0);
			w.write(Instruction.ELSE);
			get(w, accLocal);
			w.write(Instruction.END);
		}
		else {
			get(w, accLocal);
		}
		set(w, aiLocal);
		bucketSet(w, outLocal, axLocal, aiLocal);
		get(w, accLocal);
		get(w, nLocal);
		w.write(Instruction.I32_MUL);
		set(w, accLocal);
		get(w, axLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, axLocal);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	/**
	 * The odometer carry of {@code %la-bcast-loop}: bumps the innermost counter, advances
	 * one or two flat offsets by their per-axis strides, and carries outward while a
	 * counter reaches its extent. Pass -1 for the second stride/offset pair when only one
	 * operand is walked.
	 */
	private static void emitOdometer(WasmWriter w, int rankLocal, int axLocal, int tmpLocal, int idxLocal, int odLocal,
			int s1Local, int off1Local, int s2Local, int off2Local) {
		get(w, rankLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, axLocal);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, axLocal);
		i32Const(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 1);
		bucketAt(w, idxLocal, axLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, tmpLocal);
		bucketSet(w, idxLocal, axLocal, tmpLocal);
		get(w, off1Local);
		bucketAt(w, s1Local, axLocal);
		w.write(Instruction.I32_ADD);
		set(w, off1Local);
		if (s2Local >= 0) {
			get(w, off2Local);
			bucketAt(w, s2Local, axLocal);
			w.write(Instruction.I32_ADD);
			set(w, off2Local);
		}
		get(w, tmpLocal);
		bucketAt(w, odLocal, axLocal);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 1);
		i32Const(w, 0);
		set(w, tmpLocal);
		bucketSet(w, idxLocal, axLocal, tmpLocal);
		get(w, off1Local);
		bucketAt(w, odLocal, axLocal);
		bucketAt(w, s1Local, axLocal);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_SUB);
		set(w, off1Local);
		if (s2Local >= 0) {
			get(w, off2Local);
			bucketAt(w, odLocal, axLocal);
			bucketAt(w, s2Local, axLocal);
			w.write(Instruction.I32_MUL);
			w.write(Instruction.I32_SUB);
			set(w, off2Local);
		}
		get(w, axLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, axLocal);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	/** {@code outLocal} = a fresh buckets array of {@code nLocal} i31 zeros. */
	private static void newBucketsFilled(WasmWriter w, int nLocal, int outLocal) {
		i32Const(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		get(w, nLocal);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		set(w, outLocal);
	}

	/** {@code buckets[i] = i31(val)}. */
	private static void bucketSet(WasmWriter w, int bucketsLocal, int iLocal, int valLocal) {
		get(w, bucketsLocal);
		castBuckets(w);
		get(w, iLocal);
		get(w, valLocal);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		set(w, outLocal);
	}

	/** {@code outLocal = _v_new(count, kind)}: a fresh zeroed lane-group block. */
	private static void newVblock(WasmWriter w, int countLocal, int kindLocal, int outLocal, int vecBase) {
		get(w, countLocal);
		get(w, kindLocal);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_NEW);
		set(w, outLocal);
	}

	/** Pushes {@code _v_get(vb, idx)} as an f64 (a single-float element is promoted). */
	private static void vget(WasmWriter w, int vbLocal, int idxLocal, int vecBase) {
		get(w, vbLocal);
		get(w, idxLocal);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + WasmVecSimdRuntimeBuilder.V_GET);
	}

	/** Consumes the {@code $farray} on the stack and pushes its {@code $float} value. */
	private static void unboxFloatStruct(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
	}

	private static void consCar(WasmWriter w, int cellLocal) {
		get(w, cellLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(0);
	}

	private static void consCdr(WasmWriter w, int cellLocal) {
		get(w, cellLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeUnsignedLeb128(1);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		set(w, outLocal);
	}

	/** {@code outLocal} = a two-element dims bucket array {@code (d0 d1)}. */
	private static void newBuckets2(WasmWriter w, int d0, int d1, int outLocal) {
		i32Const(w, 2);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		set(w, outLocal);
		get(w, outLocal);
		castBuckets(w);
		i32Const(w, 0);
		get(w, d0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		get(w, outLocal);
		castBuckets(w);
		i32Const(w, 1);
		get(w, d1);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		set(w, outLocal);
		openCountLoop(w, iLocal, lenLocal);
		get(w, outLocal);
		castBuckets(w);
		get(w, iLocal);
		get(w, srcLocal);
		castBuckets(w);
		get(w, iLocal);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		closeCountLoop(w, iLocal);
	}

	/** Pushes {@code struct.new $farray (dims, vblock)}. */
	private static void makeFarrayWithDims(WasmWriter w, int dimsLocal, int vbLocal) {
		get(w, dimsLocal);
		get(w, vbLocal);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
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
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

}
