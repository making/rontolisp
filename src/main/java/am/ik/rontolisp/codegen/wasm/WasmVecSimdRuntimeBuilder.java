package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * The wasm-GC {@code --simd} vector runtime: three element helpers over the packed
 * float-array store plus twelve hand-assembled v128 kernels ({@code _vec_add} ..
 * {@code _vec_matvec_into}).
 *
 * <h2>The store: {@code (array (mut v128))}</h2>
 *
 * The GC proposal's {@code fieldtype} admits any {@code valtype}, and {@code v128} is one
 * -- so {@code (array (mut v128))} is a legal GC array and {@code array.get} on it yields
 * a lane group straight out of a <em>collected</em> object. Under {@code --simd} the
 * {@code $farray} struct therefore keeps its {@code dims} bucket array as before while
 * its {@code data} field -- {@code (ref null eq)} either way -- holds a {@code $vblock}
 * instead of a {@code $f64arr} / {@code $f32arr}:
 *
 * <pre>
 *   $vblock = struct { i32 count, i32 kind, (ref null eq) groups }
 *   $v128arr = (array (mut v128))
 * </pre>
 *
 * {@code count} is the logical element count, {@code kind} the width tag (0 =
 * double-float / 2 lanes, 1 = single-float / 4 lanes) that replaces the
 * {@code ref.test $f32arr} of the default backend, since both widths share the one
 * {@code $v128arr} type. {@code groups} holds {@code ceil(count / lanes) + 1} lane
 * groups; the {@code + 1} is a zero <strong>sentinel</strong> so a {@code matvec} shuffle
 * window over the last group can always read {@code g + 1} without a bounds trap.
 *
 * <h2>Why there is no scalar tail</h2>
 *
 * {@code array.new_default} zero-initializes the v128 elements, and nothing ever writes
 * past {@code count}, so the last group's padding lanes are zero. {@code add} /
 * {@code sub} / {@code mul} / {@code scale} map zero to zero and {@code sum} /
 * {@code dot} fold zero in: every kernel runs whole groups only.
 *
 * <p>
 * The one place that is not free: a group-at-a-time WRITE covers up to {@code lanes - 1}
 * elements past {@code count}. That is harmless when the destination is exactly
 * {@code count} long (those are its own zero padding), but an {@code -into} destination
 * LONGER than its operands has real elements there, which the scalar {@code vec.lisp}
 * defun would leave alone. So {@link WasmVecLoops#gcSaveLastGroup} /
 * {@link WasmVecLoops#gcRestoreLastGroupTail} blend the last written group, restoring
 * lanes {@code >= count % lanes} from the destination's pre-loop value. That keeps the
 * padding invariant airtight too: {@code (vec:scale v s)} with a non-finite {@code s}
 * leaves zeros in the padding instead of NaN.
 *
 * <h2>Why standalone functions</h2>
 *
 * The kernels need v128 / f64 / f32 / i32 / {@code (ref null $v128arr)} locals, which a
 * defun body cannot have: {@link WasmLispCompiler} declares every extra local of a
 * compiled body as one {@code (ref null eq)} group. So each kernel is emitted here as a
 * standalone runtime function with hand-written local declarations, taking and returning
 * {@code (ref null eq)} exactly like a compiled Lisp function, and
 * {@link WasmVecSimdCompiler} rewrites a {@code (vec:dot a b)} call site into a
 * {@code call $_vec_dot}. This mirrors the JVM {@code --simd} bridge: only the seven
 * vectorizable kernels (and their five {@code -into} siblings) are intercepted; every
 * other {@code vec:} member keeps running the scalar {@code vec.lisp} defun over the same
 * -- now grouped -- packed surface.
 *
 * <p>
 * The same reason drives {@code _v_new} / {@code _v_get} / {@code _v_set}: centralizing
 * the width and immediate-lane branches in three helpers is what keeps
 * {@link WasmArrayCompiler} / {@link WasmQuoteCompiler} / {@link WasmRuntimeBuilder} to
 * one {@code call} apiece at an {@code aref} / literal / print site.
 *
 * <p>
 * Widths are decided at RUNTIME from the {@code kind} field, so one kernel serves both
 * {@code #d} and {@code #f} vectors; each branch runs the loop from {@link WasmVecLoops}
 * at its own native precision. Mixing widths in one call traps, matching the JVM bridge's
 * hard error.
 */
final class WasmVecSimdRuntimeBuilder {

	private WasmVecSimdRuntimeBuilder() {
	}

	// Function indices, relative to WasmLispCompiler.FUNC_VEC_BASE. Emitted (and only
	// emitted) under --simd, between the string-GC helpers and the user defuns.

	/**
	 * {@code _v_new (count i32, kind i32) -> (ref null eq)}: a zeroed {@code $vblock}.
	 */
	static final int V_NEW = 0;

	/** {@code _v_get ((ref null eq) vb, i32 idx) -> f64}: one element, widened. */
	static final int V_GET = 1;

	/**
	 * {@code _v_set ((ref null eq) vb, i32 idx, f64 v) -> f64}: stores one element and
	 * returns it AS STORED (an f32 round-trip at single-float width).
	 */
	static final int V_SET = 2;

	static final int ADD = 3;

	static final int SUB = 4;

	static final int MUL = 5;

	static final int SCALE = 6;

	static final int SUM = 7;

	static final int DOT = 8;

	static final int MATVEC = 9;

	static final int ADD_INTO = 10;

	static final int SUB_INTO = 11;

	static final int MUL_INTO = 12;

	static final int SCALE_INTO = 13;

	static final int MATVEC_INTO = 14;

	// The element-wise unary ufuncs, each with its -into sibling. sqrt /
	// negative / reciprocal / abs run whole lane groups (WasmVecLoops.gcMap1, mirroring
	// the wasm defun's own scalar semantics -- see the U_* notes there); exp / log /
	// tanh / sin / cos / tan / asin / acos / atan / sinh / cosh / sign walk elements
	// through _v_get / _v_set with the defun's exact f64 sequence (the WasmExpCompiler
	// Horner approximation, the WasmLogCompiler atanh series, the WasmTanhCompiler
	// clamped exp derivation, the WasmSinCosCompiler Cody-Waite reduction, the
	// WasmAtanCompiler fold-and-series, the WasmSinhCoshCompiler exp derivation, the
	// WasmSignumCompiler (x>0)-(x<0)), so bit-identity to the scalar path is
	// constructive at both widths.

	static final int EXP = 15;

	static final int SQRT = 16;

	static final int ABS = 17;

	static final int NEGATIVE = 18;

	static final int SIGN = 19;

	static final int RECIPROCAL = 20;

	static final int EXP_INTO = 21;

	static final int SQRT_INTO = 22;

	static final int ABS_INTO = 23;

	static final int NEGATIVE_INTO = 24;

	static final int SIGN_INTO = 25;

	static final int RECIPROCAL_INTO = 26;

	// The transcendental ufuncs (log / tanh / sin / cos / tan): element loops like
	// exp / sign, mirroring WasmLogCompiler / WasmTanhCompiler / WasmSinCosCompiler
	// (no lane form exists).

	static final int LOG = 27;

	static final int TANH = 28;

	static final int LOG_INTO = 29;

	static final int TANH_INTO = 30;

	static final int SIN = 31;

	static final int COS = 32;

	static final int TAN = 33;

	static final int SIN_INTO = 34;

	static final int COS_INTO = 35;

	static final int TAN_INTO = 36;

	// The inverse-trigonometric / hyperbolic ufuncs (asin / acos / atan / sinh / cosh):
	// element loops mirroring WasmAtanCompiler / WasmSinhCoshCompiler.

	static final int ASIN = 37;

	static final int ACOS = 38;

	static final int ATAN = 39;

	static final int SINH = 40;

	static final int COSH = 41;

	static final int ASIN_INTO = 42;

	static final int ACOS_INTO = 43;

	static final int ATAN_INTO = 44;

	static final int SINH_INTO = 45;

	static final int COSH_INTO = 46;

	// The comparison-select ufuncs: maximum / minimum are lane
	// bitselects over a gt/lt mask (bit-identical at both widths -- a select only
	// copies input bits), relu rides the U_RELU lane form, and clip is an element
	// loop comparing the widened element against the two FULL f64 bounds (the
	// array-vs-scalar rule).

	static final int MAXIMUM = 47;

	static final int MINIMUM = 48;

	static final int RELU = 49;

	static final int CLIP = 50;

	static final int MAXIMUM_INTO = 51;

	static final int MINIMUM_INTO = 52;

	static final int RELU_INTO = 53;

	static final int CLIP_INTO = 54;

	/**
	 * The number of functions this builder contributes (shifts {@code FUNC_USER_BASE}).
	 */
	static final int FUNC_COUNT = 55;

	/**
	 * Emits the type index of each function, in emission order (for the function
	 * section).
	 */
	static int typeIndexOf(int vecFunc) {
		return switch (vecFunc) {
			// _v_new (i32, i32) -> (ref null eq) reuses TYPE_RAT_NEW's shape.
			case V_NEW -> WasmLispCompiler.TYPE_RAT_NEW;
			case V_GET -> WasmLispCompiler.TYPE_V_GET;
			case V_SET -> WasmLispCompiler.TYPE_V_SET;
			// One eq param -> eq (sum + the unary ufuncs).
			case SUM, EXP, LOG, TANH, SIN, COS, TAN, ASIN, ACOS, ATAN, SINH, COSH, SQRT, ABS, NEGATIVE, SIGN,
					RECIPROCAL, RELU ->
				WasmLispCompiler.TYPE_CALLABLE_BASE;
			// Three eq params -> eq (the binary -into kernels + clip's two bounds).
			case ADD_INTO, SUB_INTO, MUL_INTO, SCALE_INTO, MATVEC_INTO, CLIP, MAXIMUM_INTO, MINIMUM_INTO ->
				WasmLispCompiler.TYPE_CALLABLE_BASE + 2;
			// Four eq params -> eq (clip-into: out, v, lo, hi).
			case CLIP_INTO -> WasmLispCompiler.TYPE_CALLABLE_BASE + 3;
			// Two eq params -> eq (the binary kernels + the unary -into kernels).
			default -> WasmLispCompiler.TYPE_CALLABLE_BASE + 1;
		};
	}

	/** Builds the body of the given helper, in {@code vecFunc} index order. */
	static byte[] build(int vecFunc, int vecBase) {
		return switch (vecFunc) {
			case V_NEW -> buildVNewBody();
			case V_GET -> buildVGetBody();
			case V_SET -> buildVSetBody();
			case ADD -> buildElementwise(Instruction.F64X2_ADD, false, vecBase);
			case SUB -> buildElementwise(Instruction.F64X2_SUB, false, vecBase);
			case MUL -> buildElementwise(Instruction.F64X2_MUL, false, vecBase);
			case SCALE -> buildScale(false, vecBase);
			case SUM -> buildSum();
			case DOT -> buildDot();
			case MATVEC -> buildMatvec(false, vecBase);
			case ADD_INTO -> buildElementwise(Instruction.F64X2_ADD, true, vecBase);
			case SUB_INTO -> buildElementwise(Instruction.F64X2_SUB, true, vecBase);
			case MUL_INTO -> buildElementwise(Instruction.F64X2_MUL, true, vecBase);
			case SCALE_INTO -> buildScale(true, vecBase);
			case MATVEC_INTO -> buildMatvec(true, vecBase);
			case EXP -> buildUnaryElement(SCALAR_OP_EXP, false, vecBase);
			case SQRT -> buildUnaryLane(WasmVecLoops.U_SQRT, false, vecBase);
			case ABS -> buildUnaryLane(WasmVecLoops.U_ABS, false, vecBase);
			case NEGATIVE -> buildUnaryLane(WasmVecLoops.U_NEG, false, vecBase);
			case SIGN -> buildUnaryElement(SCALAR_OP_SIGN, false, vecBase);
			case RECIPROCAL -> buildUnaryLane(WasmVecLoops.U_RECIP, false, vecBase);
			case EXP_INTO -> buildUnaryElement(SCALAR_OP_EXP, true, vecBase);
			case SQRT_INTO -> buildUnaryLane(WasmVecLoops.U_SQRT, true, vecBase);
			case ABS_INTO -> buildUnaryLane(WasmVecLoops.U_ABS, true, vecBase);
			case NEGATIVE_INTO -> buildUnaryLane(WasmVecLoops.U_NEG, true, vecBase);
			case SIGN_INTO -> buildUnaryElement(SCALAR_OP_SIGN, true, vecBase);
			case RECIPROCAL_INTO -> buildUnaryLane(WasmVecLoops.U_RECIP, true, vecBase);
			case LOG -> buildUnaryElement(SCALAR_OP_LOG, false, vecBase);
			case TANH -> buildUnaryElement(SCALAR_OP_TANH, false, vecBase);
			case LOG_INTO -> buildUnaryElement(SCALAR_OP_LOG, true, vecBase);
			case TANH_INTO -> buildUnaryElement(SCALAR_OP_TANH, true, vecBase);
			case SIN -> buildUnaryElement(SCALAR_OP_SIN, false, vecBase);
			case COS -> buildUnaryElement(SCALAR_OP_COS, false, vecBase);
			case TAN -> buildUnaryElement(SCALAR_OP_TAN, false, vecBase);
			case SIN_INTO -> buildUnaryElement(SCALAR_OP_SIN, true, vecBase);
			case COS_INTO -> buildUnaryElement(SCALAR_OP_COS, true, vecBase);
			case TAN_INTO -> buildUnaryElement(SCALAR_OP_TAN, true, vecBase);
			case ASIN -> buildUnaryElement(SCALAR_OP_ASIN, false, vecBase);
			case ACOS -> buildUnaryElement(SCALAR_OP_ACOS, false, vecBase);
			case ATAN -> buildUnaryElement(SCALAR_OP_ATAN, false, vecBase);
			case SINH -> buildUnaryElement(SCALAR_OP_SINH, false, vecBase);
			case COSH -> buildUnaryElement(SCALAR_OP_COSH, false, vecBase);
			case ASIN_INTO -> buildUnaryElement(SCALAR_OP_ASIN, true, vecBase);
			case ACOS_INTO -> buildUnaryElement(SCALAR_OP_ACOS, true, vecBase);
			case ATAN_INTO -> buildUnaryElement(SCALAR_OP_ATAN, true, vecBase);
			case SINH_INTO -> buildUnaryElement(SCALAR_OP_SINH, true, vecBase);
			case COSH_INTO -> buildUnaryElement(SCALAR_OP_COSH, true, vecBase);
			case MAXIMUM -> buildSelectElementwise(true, false, vecBase);
			case MINIMUM -> buildSelectElementwise(false, false, vecBase);
			case RELU -> buildUnaryLane(WasmVecLoops.U_RELU, false, vecBase);
			case CLIP -> buildClip(false, vecBase);
			case MAXIMUM_INTO -> buildSelectElementwise(true, true, vecBase);
			case MINIMUM_INTO -> buildSelectElementwise(false, true, vecBase);
			case RELU_INTO -> buildUnaryLane(WasmVecLoops.U_RELU, true, vecBase);
			case CLIP_INTO -> buildClip(true, vecBase);
			default -> throw new IllegalArgumentException("no vec: simd helper " + vecFunc);
		};
	}

	// --- _v_new / _v_get / _v_set ---------------------------------------------------

	// _v_new(count, kind) -> $vblock: groups = array.new_default $v128arr (ceil(count /
	// lanes) + 1), zero-filled, the trailing group being the shuffle sentinel. Params: 0
	// =
	// count, 1 = kind. Locals: 2 = shift (i32).
	private static byte[] buildVNewBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int count = 0, kind = 1, shift = 2;
		// shift = kind + 1 (1 = f64x2, 2 = f32x4)
		WasmVecLoops.get(w, kind);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		WasmVecLoops.set(w, shift);
		// struct.new $vblock (count, kind, groups)
		WasmVecLoops.get(w, count);
		WasmVecLoops.get(w, kind);
		// ngroups = ((count + (1 << shift) - 1) >> shift) + 1
		WasmVecLoops.get(w, count);
		i32Const(w, 1);
		WasmVecLoops.get(w, shift);
		w.write(Instruction.I32_SHL);
		i32Const(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		WasmVecLoops.get(w, shift);
		w.write(Instruction.I32_SHR_U);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_V128ARR);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_VBLOCK);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 1, 0, 0, 0, 0, 0);
	}

	// _v_get(vb, idx) -> f64: array.get the group, then the immediate-lane branch (a
	// 2-way if for f64x2, a 4-way if-chain for f32x4 -- lane indices are shuffle-style
	// immediates, so they cannot be computed). Params: 0 = vb, 1 = idx.
	// Locals: 2 = lane (i32), 3 = v (v128), 4 = groups.
	private static byte[] buildVGetBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int vb = 0, idx = 1, lane = 2, v = 3, groups = 4;
		vblockGroups(w, vb, groups);
		vblockField(w, vb, 1); // kind
		w.write(Instruction.IF, Type.F64.code());
		// single-float: four f32 lanes
		loadGroupOf(w, groups, idx, v, true);
		WasmVecLoops.get(w, idx);
		i32Const(w, 3);
		w.write(Instruction.I32_AND);
		WasmVecLoops.set(w, lane);
		emitLaneChain(w, lane, 4, Type.F32.code(), k -> {
			WasmVecLoops.get(w, v);
			WasmVecLoops.simd(w, Instruction.F32X4_EXTRACT_LANE);
			w.write(k);
		});
		w.write(Instruction.F64_PROMOTE_F32);
		w.write(Instruction.ELSE);
		// double-float: two f64 lanes
		loadGroupOf(w, groups, idx, v, false);
		WasmVecLoops.get(w, idx);
		i32Const(w, 1);
		w.write(Instruction.I32_AND);
		WasmVecLoops.set(w, lane);
		emitLaneChain(w, lane, 2, Type.F64.code(), k -> {
			WasmVecLoops.get(w, v);
			WasmVecLoops.simd(w, Instruction.F64X2_EXTRACT_LANE);
			w.write(k);
		});
		w.write(Instruction.END);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 1, 0, 0, 1, 0, 1);
	}

	// _v_set(vb, idx, val) -> f64: a group read-modify-write through replace_lane, and
	// the
	// value AS STORED (an f32 round-trip at single width, matching the aset return value
	// the interpreter and the JVM produce). Params: 0 = vb, 1 = idx, 2 = val.
	// Locals: 3 = g, 4 = lane (i32); 5 = fv (f32); 6 = v (v128); 7 = groups.
	private static byte[] buildVSetBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int vb = 0, idx = 1, val = 2, g = 3, lane = 4, fv = 5, v = 6, groups = 7;
		vblockGroups(w, vb, groups);
		vblockField(w, vb, 1); // kind
		w.write(Instruction.IF, Type.F64.code());
		// single-float
		groupIndex(w, idx, g, true);
		WasmVecLoops.get(w, idx);
		i32Const(w, 3);
		w.write(Instruction.I32_AND);
		WasmVecLoops.set(w, lane);
		WasmVecLoops.get(w, val);
		w.write(Instruction.F32_DEMOTE_F64);
		WasmVecLoops.set(w, fv);
		WasmVecLoops.groupGet(w, groups, g);
		WasmVecLoops.set(w, v);
		emitLaneChain(w, lane, 4, 0x40, k -> {
			WasmVecLoops.get(w, v);
			WasmVecLoops.get(w, fv);
			WasmVecLoops.simd(w, Instruction.F32X4_REPLACE_LANE);
			w.write(k);
			WasmVecLoops.set(w, v);
		});
		storeGroup(w, groups, g, v);
		WasmVecLoops.get(w, fv);
		w.write(Instruction.F64_PROMOTE_F32);
		w.write(Instruction.ELSE);
		// double-float
		groupIndex(w, idx, g, false);
		WasmVecLoops.get(w, idx);
		i32Const(w, 1);
		w.write(Instruction.I32_AND);
		WasmVecLoops.set(w, lane);
		WasmVecLoops.groupGet(w, groups, g);
		WasmVecLoops.set(w, v);
		emitLaneChain(w, lane, 2, 0x40, k -> {
			WasmVecLoops.get(w, v);
			WasmVecLoops.get(w, val);
			WasmVecLoops.simd(w, Instruction.F64X2_REPLACE_LANE);
			w.write(k);
			WasmVecLoops.set(w, v);
		});
		storeGroup(w, groups, g, v);
		WasmVecLoops.get(w, val);
		w.write(Instruction.END);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 2, 0, 1, 1, 0, 1);
	}

	/** Emits one lane body per immediate lane index, selected by {@code laneLocal}. */
	interface LaneBody {

		void emit(int lane);

	}

	// if (lane == 0) body(0) else if (lane == 1) body(1) else ... else body(n-1).
	// blockType is the shared result type of every arm (0x40 = void).
	static void emitLaneChain(WasmWriter w, int laneLocal, int laneCount, int blockType, LaneBody body) {
		for (int k = 0; k < laneCount - 1; k++) {
			WasmVecLoops.get(w, laneLocal);
			if (k == 0) {
				w.write(Instruction.I32_EQZ);
			}
			else {
				i32Const(w, k);
				w.write(Instruction.I32_EQ);
			}
			w.write(Instruction.IF, blockType);
			body.emit(k);
			w.write(Instruction.ELSE);
		}
		body.emit(laneCount - 1);
		for (int k = 0; k < laneCount - 1; k++) {
			w.write(Instruction.END);
		}
	}

	// --- element-wise kernels (add / sub / mul, allocating and -into) ---------------

	// (vec:add a b) / -into: dst[i] = op(a[i], b[i]), run whole lane groups at a time.
	//
	// Plain: params 0 = a, 1 = b. -into: params 0 = out, 1 = a, 2 = b (CL's map-into
	// order), and the destination block is the caller's -- so a loop over an -into kernel
	// allocates nothing. Aliasing `out` with an operand is intentional and safe (element
	// i
	// depends only on element i).
	//
	// i32: count, kind, shift, ng, g, rem. v128: old, cur. eq: vbD. $v128arr: ga, gb, gd.
	private static byte[] buildElementwise(int simdOp, boolean into, int vecBase) {
		int params = into ? 3 : 2;
		int a = into ? 1 : 0;
		int bArg = into ? 2 : 1;
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int count = params, kind = params + 1, shift = params + 2, ng = params + 3, g = params + 4, rem = params + 5;
		int old = params + 6, cur = params + 7; // v128
		int vbD = params + 8;
		int ga = params + 9, gb = params + 10, gd = params + 11;

		loadHeader(w, a, count, kind, shift, ng);
		requireSameKind(w, kind, bArg);
		destination(w, into, count, kind, vbD, gd, vecBase);
		farrayGroups(w, a, ga);
		farrayGroups(w, bArg, gb);
		WasmVecLoops.get(w, kind);
		w.write(Instruction.IF, 0x40);
		WasmVecLoops.gcMap2(w, gd, ga, gb, ng, g, count, rem, old, cur, true, simdOp);
		w.write(Instruction.ELSE);
		WasmVecLoops.gcMap2(w, gd, ga, gb, ng, g, count, rem, old, cur, false, simdOp);
		w.write(Instruction.END);
		finish(w, into, count, vbD);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 6, 0, 0, 2, 1, 3);
	}

	// --- comparison-select ufuncs ---------------------------------------------------

	// (vec:maximum a b) / (vec:minimum a b) and their -into siblings: dst[i] =
	// (if (> a[i] b[i]) a[i] b[i]) (or <), run whole lane groups at a time through
	// WasmVecLoops.gcMap2Select -- a gt/lt lane mask + bitselect, never the IEEE lane
	// min/max (the strict-comparison contract the vec.lisp defuns state; the SECOND
	// operand wins any false comparison, NaN and the -0.0/0.0 tie included). Same
	// shape, params and locals as buildElementwise.
	private static byte[] buildSelectElementwise(boolean greater, boolean into, int vecBase) {
		int params = into ? 3 : 2;
		int a = into ? 1 : 0;
		int bArg = into ? 2 : 1;
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int count = params, kind = params + 1, shift = params + 2, ng = params + 3, g = params + 4, rem = params + 5;
		int old = params + 6, cur = params + 7; // v128
		int vbD = params + 8;
		int ga = params + 9, gb = params + 10, gd = params + 11;

		loadHeader(w, a, count, kind, shift, ng);
		requireSameKind(w, kind, bArg);
		destination(w, into, count, kind, vbD, gd, vecBase);
		farrayGroups(w, a, ga);
		farrayGroups(w, bArg, gb);
		WasmVecLoops.get(w, kind);
		w.write(Instruction.IF, 0x40);
		WasmVecLoops.gcMap2Select(w, gd, ga, gb, ng, g, count, rem, old, cur, true, greater);
		w.write(Instruction.ELSE);
		WasmVecLoops.gcMap2Select(w, gd, ga, gb, ng, g, count, rem, old, cur, false, greater);
		w.write(Instruction.END);
		finish(w, into, count, vbD);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 6, 0, 0, 2, 1, 3);
	}

	// (vec:clip v lo hi) / (vec:clip-into out v lo hi): an element loop through
	// _v_get / _v_set with the two bounds unboxed to raw f64 locals once. Each element
	// runs the composition selects the vec.lisp lambda spells out -- t = (if (> x lo)
	// x lo), then (if (< t hi) t hi) -- with both comparisons against the FULL double
	// bounds at either width (the array-vs-scalar rule; splatting (f32) lo would not
	// widen), so a NaN element becomes lo and inverted bounds end at hi, exactly like
	// the defun.
	//
	// i32: count, kind, shift, ng, i. f64: lo, hi, t. eq: vbD, vbV, box.
	private static byte[] buildClip(boolean into, int vecBase) {
		int params = into ? 4 : 3;
		int v = into ? 1 : 0;
		int loArg = into ? 2 : 1;
		int hiArg = into ? 3 : 2;
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int count = params, kind = params + 1, shift = params + 2, ng = params + 3, i = params + 4;
		int lo = params + 5, hi = params + 6, t = params + 7; // f64
		int vbD = params + 8, vbV = params + 9, box = params + 10; // (ref null eq)

		loadHeader(w, v, count, kind, shift, ng);
		WasmVecLoops.get(w, loArg);
		unboxF64(w, box);
		WasmVecLoops.set(w, lo);
		WasmVecLoops.get(w, hiArg);
		unboxF64(w, box);
		WasmVecLoops.set(w, hi);
		if (into) {
			requireSameKind(w, kind, 0);
			farrayField(w, 0, 1);
			WasmVecLoops.set(w, vbD);
		}
		else {
			WasmVecLoops.get(w, count);
			WasmVecLoops.get(w, kind);
			w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + V_NEW);
			WasmVecLoops.set(w, vbD);
		}
		farrayField(w, v, 1);
		WasmVecLoops.set(w, vbV);
		WasmVecLoops.openIndexLoop(w, i, count);
		WasmVecLoops.get(w, vbD);
		WasmVecLoops.get(w, i);
		WasmVecLoops.get(w, vbV);
		WasmVecLoops.get(w, i);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + V_GET);
		WasmVecLoops.set(w, t);
		// t = select(x, lo, x > lo)
		WasmVecLoops.get(w, t);
		WasmVecLoops.get(w, lo);
		WasmVecLoops.get(w, t);
		WasmVecLoops.get(w, lo);
		w.write(Instruction.F64_GT);
		w.write(Instruction.SELECT);
		WasmVecLoops.set(w, t);
		// select(t, hi, t < hi)
		WasmVecLoops.get(w, t);
		WasmVecLoops.get(w, hi);
		WasmVecLoops.get(w, t);
		WasmVecLoops.get(w, hi);
		w.write(Instruction.F64_LT);
		w.write(Instruction.SELECT);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + V_SET);
		w.write(Instruction.DROP);
		WasmVecLoops.closeIndexLoop(w, i);
		finish(w, into, count, vbD);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 5, 3, 0, 0, 3, 0);
	}

	// --- scale ---------------------------------------------------------------------

	// (vec:scale v s) / -into: dst[i] = v[i] * s.
	// i32: count, kind, shift, ng, g, rem. f64: s. v128: old, cur. eq: vbD, box.
	// $v128arr: gv, gd.
	private static byte[] buildScale(boolean into, int vecBase) {
		int params = into ? 3 : 2;
		int v = into ? 1 : 0;
		int sArg = into ? 2 : 1;
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int count = params, kind = params + 1, shift = params + 2, ng = params + 3, g = params + 4, rem = params + 5;
		int s = params + 6; // f64
		int old = params + 7, cur = params + 8; // v128
		int vbD = params + 9, box = params + 10; // (ref null eq)
		int gv = params + 11, gd = params + 12;

		loadHeader(w, v, count, kind, shift, ng);
		WasmVecLoops.get(w, sArg);
		unboxF64(w, box);
		WasmVecLoops.set(w, s);
		destination(w, into, count, kind, vbD, gd, vecBase);
		farrayGroups(w, v, gv);
		WasmVecLoops.get(w, kind);
		w.write(Instruction.IF, 0x40);
		WasmVecLoops.gcScale(w, gd, gv, ng, g, count, rem, old, cur, s, true);
		w.write(Instruction.ELSE);
		WasmVecLoops.gcScale(w, gd, gv, ng, g, count, rem, old, cur, s, false);
		w.write(Instruction.END);
		finish(w, into, count, vbD);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 6, 1, 0, 2, 2, 2);
	}

	// --- element-wise unary ufuncs ---------------------------------------------------

	// (vec:sqrt v) / (vec:abs v) / (vec:negative v) / (vec:reciprocal v) and their
	// -into siblings: dst[i] = uop(v[i]), run whole lane groups at a time (see the U_*
	// notes in WasmVecLoops for why each form mirrors the wasm defun's own scalar
	// semantics). Plain: params 0 = v. -into: params 0 = out, 1 = v; the destination is
	// the caller's, which MAY alias v (element i depends only on element i, the
	// add-into rule -- the same contract vec.lisp states, repeated here because this
	// call site replaces the defun).
	//
	// i32: count, kind, shift, ng, g, rem. v128: old, cur. eq: vbD. $v128arr: gv, gd.
	private static byte[] buildUnaryLane(int uop, boolean into, int vecBase) {
		int params = into ? 2 : 1;
		int v = into ? 1 : 0;
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int count = params, kind = params + 1, shift = params + 2, ng = params + 3, g = params + 4, rem = params + 5;
		int old = params + 6, cur = params + 7; // v128
		int vbD = params + 8;
		int gv = params + 9, gd = params + 10;

		loadHeader(w, v, count, kind, shift, ng);
		destination(w, into, count, kind, vbD, gd, vecBase);
		farrayGroups(w, v, gv);
		WasmVecLoops.get(w, kind);
		w.write(Instruction.IF, 0x40);
		WasmVecLoops.gcMap1(w, gd, gv, ng, g, count, rem, old, cur, true, uop);
		w.write(Instruction.ELSE);
		WasmVecLoops.gcMap1(w, gd, gv, ng, g, count, rem, old, cur, false, uop);
		w.write(Instruction.END);
		finish(w, into, count, vbD);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 6, 0, 0, 2, 1, 3);
	}

	// The scalar-sequence unary operators (no lane form exists): each emit helper
	// consumes an f64 on the stack and leaves the result, using consecutive raw f64
	// locals starting at the base the caller hands to emitScalarUnaryF64. Shared by
	// the vec:/linalg: kernels here and NoGcWasmCompiler.compileSimdUnaryF64.

	static final int SCALAR_OP_EXP = 0;

	static final int SCALAR_OP_SIGN = 1;

	static final int SCALAR_OP_LOG = 2;

	static final int SCALAR_OP_TANH = 3;

	static final int SCALAR_OP_SIN = 4;

	static final int SCALAR_OP_COS = 5;

	static final int SCALAR_OP_TAN = 6;

	static final int SCALAR_OP_ASIN = 7;

	static final int SCALAR_OP_ACOS = 8;

	static final int SCALAR_OP_ATAN = 9;

	static final int SCALAR_OP_SINH = 10;

	static final int SCALAR_OP_COSH = 11;

	/** The number of consecutive f64 locals {@link #emitScalarUnaryF64} needs. */
	static int scalarOpF64Locals(int scalarOp) {
		return switch (scalarOp) {
			case SCALAR_OP_SIN, SCALAR_OP_COS, SCALAR_OP_TAN, SCALAR_OP_ASIN, SCALAR_OP_ACOS, SCALAR_OP_ATAN -> 5;
			case SCALAR_OP_LOG, SCALAR_OP_SINH, SCALAR_OP_COSH -> 3;
			default -> 2;
		};
	}

	/**
	 * Consumes an f64 on the stack and leaves {@code op(x)}, the defun path's exact f64
	 * sequence, over consecutive raw f64 locals starting at {@code f64Base}.
	 */
	static void emitScalarUnaryF64(WasmWriter w, int scalarOp, int f64Base) {
		switch (scalarOp) {
			case SCALAR_OP_EXP -> emitExpF64(w, f64Base, f64Base + 1);
			case SCALAR_OP_SIGN -> emitSignumF64(w, f64Base);
			case SCALAR_OP_LOG -> emitLogF64(w, f64Base, f64Base + 1, f64Base + 2);
			case SCALAR_OP_TANH -> emitTanhF64(w, f64Base, f64Base + 1);
			case SCALAR_OP_SIN, SCALAR_OP_COS, SCALAR_OP_TAN -> emitSinCosF64(w, scalarOp, f64Base);
			case SCALAR_OP_ASIN, SCALAR_OP_ACOS, SCALAR_OP_ATAN -> emitAtanFamilyF64(w, scalarOp, f64Base);
			case SCALAR_OP_SINH, SCALAR_OP_COSH -> emitSinhCoshF64(w, scalarOp, f64Base);
			default -> throw new IllegalArgumentException("no scalar unary op " + scalarOp);
		}
	}

	// (vec:exp v) / (vec:log v) / (vec:tanh v) / (vec:sign v) and their -into siblings:
	// an element loop through _v_get / _v_set (widen on read, compute in f64, narrow on
	// write -- the emap rule), with the operator emitted as the SAME f64 sequence the
	// boxed defun path uses (WasmExpCompiler's Horner approximation, WasmLogCompiler's
	// atanh series, WasmTanhCompiler's clamped exp derivation, WasmSignumCompiler's
	// (x>0)-(x<0)), so the result is bit-identical to the scalar defun by construction
	// at both widths.
	//
	// i32: count, kind, shift, ng, i. f64: the op's scratch locals. eq: vbD, vbV.
	private static byte[] buildUnaryElement(int scalarOp, boolean into, int vecBase) {
		int params = into ? 2 : 1;
		int v = into ? 1 : 0;
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int count = params, kind = params + 1, shift = params + 2, ng = params + 3, i = params + 4;
		int f64Base = params + 5; // f64 scratch
		int nF64 = scalarOpF64Locals(scalarOp);
		int vbD = f64Base + nF64, vbV = vbD + 1; // (ref null eq)

		loadHeader(w, v, count, kind, shift, ng);
		if (into) {
			requireSameKind(w, kind, 0);
			farrayField(w, 0, 1);
			WasmVecLoops.set(w, vbD);
		}
		else {
			WasmVecLoops.get(w, count);
			WasmVecLoops.get(w, kind);
			w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + V_NEW);
			WasmVecLoops.set(w, vbD);
		}
		farrayField(w, v, 1);
		WasmVecLoops.set(w, vbV);
		WasmVecLoops.openIndexLoop(w, i, count);
		WasmVecLoops.get(w, vbD);
		WasmVecLoops.get(w, i);
		WasmVecLoops.get(w, vbV);
		WasmVecLoops.get(w, i);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + V_GET);
		emitScalarUnaryF64(w, scalarOp, f64Base);
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + V_SET);
		w.write(Instruction.DROP);
		WasmVecLoops.closeIndexLoop(w, i);
		finish(w, into, count, vbD);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 5, nF64, 0, 0, 2, 0);
	}

	/**
	 * Consumes an f64 {@code x} on the stack and leaves {@code exp(x)}: the exact
	 * argument-reduction + Horner + repeated-squaring sequence {@link WasmExpCompiler}
	 * emits on the boxed defun path, on two raw f64 locals instead of boxed temps.
	 */
	static void emitExpF64(WasmWriter w, int tLocal, int accLocal) {
		// t = x / 256
		w.write(Instruction.F64_CONST).writeF64(WasmExpCompiler.INV_SCALE);
		w.write(Instruction.F64_MUL);
		WasmVecLoops.set(w, tLocal);
		// Horner: acc = ((((c0 * t + c1) * t + c2) ... ) * t + c5)
		w.write(Instruction.F64_CONST).writeF64(WasmExpCompiler.HORNER_COEFFS[0]);
		for (int i = 1; i < WasmExpCompiler.HORNER_COEFFS.length; i++) {
			WasmVecLoops.get(w, tLocal);
			w.write(Instruction.F64_MUL);
			w.write(Instruction.F64_CONST).writeF64(WasmExpCompiler.HORNER_COEFFS[i]);
			w.write(Instruction.F64_ADD);
		}
		WasmVecLoops.set(w, accLocal);
		// Square SQUARINGS times: acc = acc * acc.
		for (int s = 0; s < WasmExpCompiler.SQUARINGS; s++) {
			WasmVecLoops.get(w, accLocal);
			WasmVecLoops.get(w, accLocal);
			w.write(Instruction.F64_MUL);
			WasmVecLoops.set(w, accLocal);
		}
		WasmVecLoops.get(w, accLocal);
	}

	/**
	 * Consumes an f64 {@code x} on the stack and leaves {@code signum(x)} as
	 * {@code (x > 0) - (x < 0)} converted to f64 -- {@link WasmSignumCompiler}'s float
	 * path exactly (so {@code -0.0} and {@code NaN} both map to {@code 0.0} here, the
	 * wasm defun's own edges).
	 */
	static void emitSignumF64(WasmWriter w, int xLocal) {
		WasmVecLoops.set(w, xLocal);
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.F64_CONST).writeF64(0.0);
		w.write(Instruction.F64_GT);
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.F64_CONST).writeF64(0.0);
		w.write(Instruction.F64_LT);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.F64_CONVERT_S_I32);
	}

	/**
	 * Consumes an f64 {@code x} on the stack and leaves {@code log(x)}: the exact
	 * exponent-extraction + atanh-series sequence {@link WasmLogCompiler} emits on the
	 * boxed defun path, on three raw f64 locals instead of boxed temps ({@code x} is
	 * reused for {@code s} and {@code m} for {@code u}, exactly as the boxed slots are).
	 */
	static void emitLogF64(WasmWriter w, int xLocal, int mLocal, int eLocal) {
		WasmVecLoops.set(w, xLocal);
		// The IEEE edges, then the finite positive main path (WasmLogCompiler's order).
		WasmVecLoops.get(w, xLocal);
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.F64_NE);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.F64_CONST).writeF64(0.0);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, Type.F64.code());
		w.write(Instruction.F64_CONST).writeF64(Double.NEGATIVE_INFINITY);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.F64_CONST).writeF64(0.0);
		w.write(Instruction.F64_LT);
		w.write(Instruction.IF, Type.F64.code());
		w.write(Instruction.F64_CONST).writeF64(Double.NaN);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.F64_CONST).writeF64(Double.POSITIVE_INFINITY);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, Type.F64.code());
		w.write(Instruction.F64_CONST).writeF64(Double.POSITIVE_INFINITY);
		w.write(Instruction.ELSE);
		emitLogMainF64(w, xLocal, mLocal, eLocal);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// The finite positive path of emitLogF64; leaves the f64 result on the stack.
	private static void emitLogMainF64(WasmWriter w, int xLocal, int mLocal, int eLocal) {
		// e = 0.0; if (x < MIN_NORMAL) { x *= 2^54; e = -54.0 }
		w.write(Instruction.F64_CONST).writeF64(0.0);
		WasmVecLoops.set(w, eLocal);
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.F64_CONST).writeF64(WasmLogCompiler.MIN_NORMAL);
		w.write(Instruction.F64_LT);
		w.write(Instruction.IF, 0x40);
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.F64_CONST).writeF64(WasmLogCompiler.SCALE_UP);
		w.write(Instruction.F64_MUL);
		WasmVecLoops.set(w, xLocal);
		w.write(Instruction.F64_CONST).writeF64(WasmLogCompiler.SCALE_E);
		WasmVecLoops.set(w, eLocal);
		w.write(Instruction.END);
		// e += (f64)((bits(x) >> 52) - 1023)
		WasmVecLoops.get(w, eLocal);
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.I64_REINTERPRET_F64);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(52);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(1023);
		w.write(Instruction.I64_SUB);
		w.write(Instruction.F64_CONVERT_S_I64);
		w.write(Instruction.F64_ADD);
		WasmVecLoops.set(w, eLocal);
		// m = reinterpret((bits(x) & MANT_MASK) | ONE_BITS), in [1, 2).
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.I64_REINTERPRET_F64);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(WasmLogCompiler.MANT_MASK);
		w.write(Instruction.I64_AND);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(WasmLogCompiler.ONE_BITS);
		w.write(Instruction.I64_OR);
		w.write(Instruction.F64_REINTERPRET_I64);
		WasmVecLoops.set(w, mLocal);
		// if (m > sqrt(2)) { m *= 0.5; e += 1 }
		WasmVecLoops.get(w, mLocal);
		w.write(Instruction.F64_CONST).writeF64(WasmLogCompiler.SQRT2);
		w.write(Instruction.F64_GT);
		w.write(Instruction.IF, 0x40);
		WasmVecLoops.get(w, mLocal);
		w.write(Instruction.F64_CONST).writeF64(0.5);
		w.write(Instruction.F64_MUL);
		WasmVecLoops.set(w, mLocal);
		WasmVecLoops.get(w, eLocal);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		w.write(Instruction.F64_ADD);
		WasmVecLoops.set(w, eLocal);
		w.write(Instruction.END);
		// s = (m - 1) / (m + 1), reusing xLocal; u = s^2, reusing mLocal.
		WasmVecLoops.get(w, mLocal);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		w.write(Instruction.F64_SUB);
		WasmVecLoops.get(w, mLocal);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		w.write(Instruction.F64_ADD);
		w.write(Instruction.F64_DIV);
		WasmVecLoops.set(w, xLocal);
		WasmVecLoops.get(w, xLocal);
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.F64_MUL);
		WasmVecLoops.set(w, mLocal);
		// Horner over u, then ln(m) = (poly * s) * 2, then + e*ln2.
		w.write(Instruction.F64_CONST).writeF64(WasmLogCompiler.HORNER_COEFFS[0]);
		for (int i = 1; i < WasmLogCompiler.HORNER_COEFFS.length; i++) {
			WasmVecLoops.get(w, mLocal);
			w.write(Instruction.F64_MUL);
			w.write(Instruction.F64_CONST).writeF64(WasmLogCompiler.HORNER_COEFFS[i]);
			w.write(Instruction.F64_ADD);
		}
		WasmVecLoops.get(w, xLocal);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_CONST).writeF64(2.0);
		w.write(Instruction.F64_MUL);
		WasmVecLoops.get(w, eLocal);
		w.write(Instruction.F64_CONST).writeF64(WasmLogCompiler.LN2);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_ADD);
	}

	/**
	 * Consumes an f64 {@code x} on the stack and leaves {@code tanh(x)}: the exact
	 * clamped {@code (e^(2x)-1)/(e^(2x)+1)} derivation {@link WasmTanhCompiler} emits on
	 * the boxed defun path, over the same two raw f64 locals as {@link #emitExpF64}.
	 */
	static void emitTanhF64(WasmWriter w, int tLocal, int accLocal) {
		w.write(Instruction.F64_CONST).writeF64(2.0);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_CONST).writeF64(-WasmTanhCompiler.CLAMP);
		w.write(Instruction.F64_MAX);
		w.write(Instruction.F64_CONST).writeF64(WasmTanhCompiler.CLAMP);
		w.write(Instruction.F64_MIN);
		emitExpF64(w, tLocal, accLocal);
		w.write(Instruction.DROP);
		WasmVecLoops.get(w, accLocal);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		w.write(Instruction.F64_SUB);
		WasmVecLoops.get(w, accLocal);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		w.write(Instruction.F64_ADD);
		w.write(Instruction.F64_DIV);
	}

	/**
	 * Consumes an f64 {@code x} on the stack and leaves {@code sin(x)} / {@code cos(x)} /
	 * {@code tan(x)}: the exact Cody-Waite reduction + Taylor-polynomial + quadrant
	 * selection sequence {@link WasmSinCosCompiler} emits on the boxed defun path, on
	 * five raw f64 locals instead of boxed temps ({@code x} is reused for {@code r} and
	 * {@code k} for the quadrant {@code q}, exactly as the boxed slots are).
	 */
	static void emitSinCosF64(WasmWriter w, int scalarOp, int f64Base) {
		int x = f64Base, k = f64Base + 1, z = f64Base + 2, s = f64Base + 3, c = f64Base + 4;
		WasmVecLoops.set(w, x);
		// The IEEE edges, then the finite main path (WasmSinCosCompiler's order).
		WasmVecLoops.get(w, x);
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_NE);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.get(w, x);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_ABS);
		w.write(Instruction.F64_CONST).writeF64(Double.POSITIVE_INFINITY);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, Type.F64.code());
		w.write(Instruction.F64_CONST).writeF64(Double.NaN);
		w.write(Instruction.ELSE);
		emitSinCosMainF64(w, scalarOp, x, k, z, s, c);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// The finite path of emitSinCosF64; leaves the f64 result on the stack.
	private static void emitSinCosMainF64(WasmWriter w, int scalarOp, int x, int k, int z, int s, int c) {
		// if (|x| > BIG) { x -= nearest(x / 2pi) * 2pi; x = clamp(x, -BIG, BIG) }
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_ABS);
		w.write(Instruction.F64_CONST).writeF64(WasmSinCosCompiler.BIG);
		w.write(Instruction.F64_GT);
		w.write(Instruction.IF, 0x40);
		WasmVecLoops.get(w, x);
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_CONST).writeF64(WasmSinCosCompiler.INV_TWO_PI);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_NEAREST);
		w.write(Instruction.F64_CONST).writeF64(WasmSinCosCompiler.TWO_PI);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.F64_CONST).writeF64(-WasmSinCosCompiler.BIG);
		w.write(Instruction.F64_MAX);
		w.write(Instruction.F64_CONST).writeF64(WasmSinCosCompiler.BIG);
		w.write(Instruction.F64_MIN);
		WasmVecLoops.set(w, x);
		w.write(Instruction.END);
		// k = nearest(x * 2/pi)
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_CONST).writeF64(WasmSinCosCompiler.TWO_OVER_PI);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_NEAREST);
		WasmVecLoops.set(w, k);
		// r = (x - k*PIO2_1) - k*PIO2_1T, reusing x.
		WasmVecLoops.get(w, x);
		WasmVecLoops.get(w, k);
		w.write(Instruction.F64_CONST).writeF64(WasmSinCosCompiler.PIO2_1);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_SUB);
		WasmVecLoops.get(w, k);
		w.write(Instruction.F64_CONST).writeF64(WasmSinCosCompiler.PIO2_1T);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_SUB);
		WasmVecLoops.set(w, x);
		// z = r * r
		WasmVecLoops.get(w, x);
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_MUL);
		WasmVecLoops.set(w, z);
		// s = Horner(SIN_COEFFS over z) * r
		w.write(Instruction.F64_CONST).writeF64(WasmSinCosCompiler.SIN_COEFFS[0]);
		for (int i = 1; i < WasmSinCosCompiler.SIN_COEFFS.length; i++) {
			WasmVecLoops.get(w, z);
			w.write(Instruction.F64_MUL);
			w.write(Instruction.F64_CONST).writeF64(WasmSinCosCompiler.SIN_COEFFS[i]);
			w.write(Instruction.F64_ADD);
		}
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_MUL);
		WasmVecLoops.set(w, s);
		// c = Horner(COS_COEFFS over z)
		w.write(Instruction.F64_CONST).writeF64(WasmSinCosCompiler.COS_COEFFS[0]);
		for (int i = 1; i < WasmSinCosCompiler.COS_COEFFS.length; i++) {
			WasmVecLoops.get(w, z);
			w.write(Instruction.F64_MUL);
			w.write(Instruction.F64_CONST).writeF64(WasmSinCosCompiler.COS_COEFFS[i]);
			w.write(Instruction.F64_ADD);
		}
		WasmVecLoops.set(w, c);
		// q = (f64)(trunc(k) & 3), reusing k.
		WasmVecLoops.get(w, k);
		w.write(Instruction.I32_TRUNC_S_F64);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_AND);
		w.write(Instruction.F64_CONVERT_S_I32);
		WasmVecLoops.set(w, k);
		// The quadrant selection.
		switch (scalarOp) {
			case SCALAR_OP_SIN -> emitQuadrantSelectF64(w, k, s, c, false);
			case SCALAR_OP_COS -> emitQuadrantSelectF64(w, k, c, s, true);
			default -> emitTanSelectF64(w, k, s, c);
		}
	}

	// The raw-local mirror of WasmSinCosCompiler.emitQuadrantSelect.
	private static void emitQuadrantSelectF64(WasmWriter w, int q, int primary, int secondary, boolean negateOdd) {
		WasmVecLoops.get(w, q);
		w.write(Instruction.F64_CONST).writeF64(0.0);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.get(w, primary);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, q);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.get(w, secondary);
		if (negateOdd) {
			w.write(Instruction.F64_NEG);
		}
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, q);
		w.write(Instruction.F64_CONST).writeF64(2.0);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.get(w, primary);
		w.write(Instruction.F64_NEG);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, secondary);
		if (!negateOdd) {
			w.write(Instruction.F64_NEG);
		}
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// The raw-local mirror of WasmSinCosCompiler.emitTanSelect.
	private static void emitTanSelectF64(WasmWriter w, int q, int s, int c) {
		WasmVecLoops.get(w, q);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		w.write(Instruction.F64_EQ);
		WasmVecLoops.get(w, q);
		w.write(Instruction.F64_CONST).writeF64(3.0);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.get(w, c);
		WasmVecLoops.get(w, s);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.F64_NEG);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, s);
		WasmVecLoops.get(w, c);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.END);
	}

	/**
	 * Consumes an f64 {@code x} on the stack and leaves {@code atan(x)} / {@code
	 * asin(x)} / {@code acos(x)}: the exact fold-and-series sequence
	 * {@link WasmAtanCompiler} emits on the boxed defun path, on five raw f64 locals
	 * instead of boxed temps ({@code x} is overwritten with the transformed atan argument
	 * for asin/acos, exactly as the boxed slot is).
	 */
	static void emitAtanFamilyF64(WasmWriter w, int scalarOp, int f64Base) {
		int x = f64Base, t = f64Base + 1, u = f64Base + 2, z = f64Base + 3, r = f64Base + 4;
		WasmVecLoops.set(w, x);
		switch (scalarOp) {
			case SCALAR_OP_ATAN -> emitAtanCoreF64(w, x, t, u, z, r);
			case SCALAR_OP_ASIN -> {
				// if (|x| > 1) -> NaN, else atan(x / sqrt((1-x)*(1+x))).
				emitAtanDomainGuardF64(w, x);
				WasmVecLoops.get(w, x);
				emitOneMinusAndOnePlusF64(w, x);
				w.write(Instruction.F64_MUL);
				w.write(Instruction.F64_SQRT);
				w.write(Instruction.F64_DIV);
				WasmVecLoops.set(w, x);
				emitAtanCoreF64(w, x, t, u, z, r);
				w.write(Instruction.END);
			}
			default -> {
				// acos: if (|x| > 1) -> NaN, else 2 * atan(sqrt((1-x)/(1+x))).
				emitAtanDomainGuardF64(w, x);
				emitOneMinusAndOnePlusF64(w, x);
				w.write(Instruction.F64_DIV);
				w.write(Instruction.F64_SQRT);
				WasmVecLoops.set(w, x);
				emitAtanCoreF64(w, x, t, u, z, r);
				w.write(Instruction.F64_CONST).writeF64(2.0);
				w.write(Instruction.F64_MUL);
				w.write(Instruction.END);
			}
		}
	}

	// The raw-local mirror of WasmAtanCompiler.emitDomainGuard: opens
	// "if (|x| > 1) -> NaN else ..." (the caller emits the else body + END).
	private static void emitAtanDomainGuardF64(WasmWriter w, int x) {
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_ABS);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		w.write(Instruction.F64_GT);
		w.write(Instruction.IF, Type.F64.code());
		w.write(Instruction.F64_CONST).writeF64(Double.NaN);
		w.write(Instruction.ELSE);
	}

	// The raw-local mirror of WasmAtanCompiler.emitOneMinusAndOnePlus: leaves
	// [1-x, 1+x] on the stack.
	private static void emitOneMinusAndOnePlusF64(WasmWriter w, int x) {
		w.write(Instruction.F64_CONST).writeF64(1.0);
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_ADD);
	}

	// The raw-local mirror of WasmAtanCompiler.emitAtanCore; leaves the f64 result on
	// the stack.
	private static void emitAtanCoreF64(WasmWriter w, int x, int t, int u, int z, int r) {
		// t = x < 0 ? 0 - x : x
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_CONST).writeF64(0.0);
		w.write(Instruction.F64_LT);
		w.write(Instruction.IF, Type.F64.code());
		w.write(Instruction.F64_CONST).writeF64(0.0);
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, x);
		w.write(Instruction.END);
		WasmVecLoops.set(w, t);
		// u = t > 1 ? 1/t : t
		WasmVecLoops.get(w, t);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		w.write(Instruction.F64_GT);
		w.write(Instruction.IF, Type.F64.code());
		w.write(Instruction.F64_CONST).writeF64(1.0);
		WasmVecLoops.get(w, t);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, t);
		w.write(Instruction.END);
		WasmVecLoops.set(w, u);
		// The half-angle folds: u = u / (sqrt(1 + u*u) + 1).
		for (int i = 0; i < WasmAtanCompiler.HALF_ANGLE_FOLDS; i++) {
			WasmVecLoops.get(w, u);
			w.write(Instruction.F64_CONST).writeF64(1.0);
			WasmVecLoops.get(w, u);
			WasmVecLoops.get(w, u);
			w.write(Instruction.F64_MUL);
			w.write(Instruction.F64_ADD);
			w.write(Instruction.F64_SQRT);
			w.write(Instruction.F64_CONST).writeF64(1.0);
			w.write(Instruction.F64_ADD);
			w.write(Instruction.F64_DIV);
			WasmVecLoops.set(w, u);
		}
		// z = u * u
		WasmVecLoops.get(w, u);
		WasmVecLoops.get(w, u);
		w.write(Instruction.F64_MUL);
		WasmVecLoops.set(w, z);
		// r = Horner(ATAN_COEFFS over z) * u * 4
		w.write(Instruction.F64_CONST).writeF64(WasmAtanCompiler.ATAN_COEFFS[0]);
		for (int i = 1; i < WasmAtanCompiler.ATAN_COEFFS.length; i++) {
			WasmVecLoops.get(w, z);
			w.write(Instruction.F64_MUL);
			w.write(Instruction.F64_CONST).writeF64(WasmAtanCompiler.ATAN_COEFFS[i]);
			w.write(Instruction.F64_ADD);
		}
		WasmVecLoops.get(w, u);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_CONST).writeF64(4.0);
		w.write(Instruction.F64_MUL);
		WasmVecLoops.set(w, r);
		// The reciprocal select: r = t > 1 ? pi/2 - r : r.
		WasmVecLoops.get(w, t);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		w.write(Instruction.F64_GT);
		w.write(Instruction.IF, Type.F64.code());
		w.write(Instruction.F64_CONST).writeF64(WasmAtanCompiler.PI_OVER_2);
		WasmVecLoops.get(w, r);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, r);
		w.write(Instruction.END);
		WasmVecLoops.set(w, r);
		// The sign select: x < 0 ? 0 - r : r, left on the stack.
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_CONST).writeF64(0.0);
		w.write(Instruction.F64_LT);
		w.write(Instruction.IF, Type.F64.code());
		w.write(Instruction.F64_CONST).writeF64(0.0);
		WasmVecLoops.get(w, r);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, r);
		w.write(Instruction.END);
	}

	/**
	 * Consumes an f64 {@code x} on the stack and leaves {@code sinh(x)} / {@code
	 * cosh(x)}: the exact exp-derivation (+ sinh's small-x odd Taylor series) sequence
	 * {@link WasmSinhCoshCompiler} emits on the boxed defun path, on three raw f64 locals
	 * instead of boxed temps (the exp core's {@code t} / {@code acc} pair plus {@code x};
	 * {@code t} doubles as the series' {@code z} and the sign-restore scratch, exactly as
	 * the boxed slot does).
	 */
	static void emitSinhCoshF64(WasmWriter w, int scalarOp, int f64Base) {
		int x = f64Base, t = f64Base + 1, acc = f64Base + 2;
		WasmVecLoops.set(w, x);
		// if (x != x) -> NaN
		WasmVecLoops.get(w, x);
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_NE);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.get(w, x);
		w.write(Instruction.ELSE);
		// if (|x| == +inf) -> x (sinh) / |x| (cosh)
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_ABS);
		w.write(Instruction.F64_CONST).writeF64(Double.POSITIVE_INFINITY);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.get(w, x);
		if (scalarOp == SCALAR_OP_COSH) {
			w.write(Instruction.F64_ABS);
		}
		w.write(Instruction.ELSE);
		if (scalarOp == SCALAR_OP_COSH) {
			emitCoshMainF64(w, x, t, acc);
		}
		else {
			emitSinhMainF64(w, x, t, acc);
		}
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// The raw-local mirror of WasmSinhCoshCompiler.emitSinhMain.
	private static void emitSinhMainF64(WasmWriter w, int x, int t, int acc) {
		// if (|x| > SMALL) exp derivation else the odd Taylor series.
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_ABS);
		w.write(Instruction.F64_CONST).writeF64(WasmSinhCoshCompiler.SMALL);
		w.write(Instruction.F64_GT);
		w.write(Instruction.IF, Type.F64.code());
		// e = exp(|x|); s = (e - 1/e) * 0.5.
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_ABS);
		emitExpF64(w, t, acc);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		WasmVecLoops.get(w, acc);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.F64_CONST).writeF64(0.5);
		w.write(Instruction.F64_MUL);
		WasmVecLoops.set(w, t);
		// The sign restore: x < 0 ? 0 - s : s.
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_CONST).writeF64(0.0);
		w.write(Instruction.F64_LT);
		w.write(Instruction.IF, Type.F64.code());
		w.write(Instruction.F64_CONST).writeF64(0.0);
		WasmVecLoops.get(w, t);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, t);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		// z = x * x; Horner(SINH_COEFFS over z) * x.
		WasmVecLoops.get(w, x);
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_MUL);
		WasmVecLoops.set(w, t);
		w.write(Instruction.F64_CONST).writeF64(WasmSinhCoshCompiler.SINH_COEFFS[0]);
		for (int i = 1; i < WasmSinhCoshCompiler.SINH_COEFFS.length; i++) {
			WasmVecLoops.get(w, t);
			w.write(Instruction.F64_MUL);
			w.write(Instruction.F64_CONST).writeF64(WasmSinhCoshCompiler.SINH_COEFFS[i]);
			w.write(Instruction.F64_ADD);
		}
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.END);
	}

	// The raw-local mirror of WasmSinhCoshCompiler.emitCoshMain.
	private static void emitCoshMainF64(WasmWriter w, int x, int t, int acc) {
		// e = exp(|x|); (e + 1/e) * 0.5.
		WasmVecLoops.get(w, x);
		w.write(Instruction.F64_ABS);
		emitExpF64(w, t, acc);
		w.write(Instruction.F64_CONST).writeF64(1.0);
		WasmVecLoops.get(w, acc);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.F64_ADD);
		w.write(Instruction.F64_CONST).writeF64(0.5);
		w.write(Instruction.F64_MUL);
	}

	// --- reductions ----------------------------------------------------------------

	// (vec:sum v) -> a boxed TYPE_FLOAT.
	// i32: count, kind, shift, ng, g. f64: sum. f32: sumF. v128: acc. $v128arr: gv.
	private static byte[] buildSum() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int count = 1, kind = 2, shift = 3, ng = 4, g = 5;
		int sum = 6; // f64
		int sumF = 7; // f32
		int acc = 8; // v128
		int gv = 9;

		loadHeader(w, 0, count, kind, shift, ng);
		farrayGroups(w, 0, gv);
		WasmVecLoops.get(w, kind);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.splatZeroF32(w, acc);
		WasmVecLoops.gcSum(w, gv, ng, g, acc, sumF, true);
		w.write(Instruction.ELSE);
		WasmVecLoops.splatZero(w, acc);
		WasmVecLoops.gcSum(w, gv, ng, g, acc, sum, false);
		w.write(Instruction.END);
		boxFloat(w);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 5, 1, 1, 1, 0, 1);
	}

	// (vec:dot a b) -> a boxed TYPE_FLOAT.
	// i32: count, kind, shift, ng, g. f64: sum. f32: sumF. v128: acc. $v128arr: ga, gb.
	private static byte[] buildDot() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int count = 2, kind = 3, shift = 4, ng = 5, g = 6;
		int sum = 7; // f64
		int sumF = 8; // f32
		int acc = 9; // v128
		int ga = 10, gb = 11;

		loadHeader(w, 0, count, kind, shift, ng);
		requireSameKind(w, kind, 1);
		farrayGroups(w, 0, ga);
		farrayGroups(w, 1, gb);
		WasmVecLoops.get(w, kind);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.splatZeroF32(w, acc);
		WasmVecLoops.gcDot(w, ga, gb, ng, g, acc, sumF, true);
		w.write(Instruction.ELSE);
		WasmVecLoops.splatZero(w, acc);
		WasmVecLoops.gcDot(w, ga, gb, ng, g, acc, sum, false);
		w.write(Instruction.END);
		boxFloat(w);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 5, 1, 1, 1, 0, 2);
	}

	// --- matvec (GEMV) --------------------------------------------------------------

	// (vec:matvec w x) / -into: a rank-2 matrix times a rank-1 vector. Unlike --no-gc
	// (whose packed block is rank-1 only), the $farray struct carries its dims, so the
	// rows are readable: r[row] = dot(W row, x).
	//
	// Row `row` of a d x n matrix starts at flat element row*n, i.e. inside group
	// `base = (row*n) >> laneShift` at lane `off = (row*n) & (lanes-1)`, both
	// loop-invariant
	// per row. When off is 0 a row group is one array.get; otherwise it is the
	// i8x16.shuffle window over groups base+k and base+k+1 (the immediate is simply
	// [c, c+1, .. c+15] with c = off * elementBytes -- indices >= 16 naturally select the
	// second operand). The trailing zero sentinel group makes that final base+k+1 safe.
	// The lanes of the last row group that overhang into the NEXT row are multiplied by
	// x's zero padding, so they contribute nothing -- the same zero invariant that
	// removes
	// the tail from every other kernel. f64 needs 2 row-loop variants, f32 needs 4,
	// because
	// the shuffle immediate cannot be computed.
	//
	// Plain: params 0 = W, 1 = x. -into: 0 = out, 1 = W, 2 = x. `out` may alias NEITHER
	// `x`
	// (each output element folds over ALL of x) NOR `W` (the row windows keep reading
	// it),
	// so the -into form traps on ref.eq against both -- the guard the vec.lisp defun and
	// the JVM/interpreter kernels raise as an error.
	//
	// i32: d, n, kind, shift, nrg, row, flat, base, off, k. f64: sum. f32: sumF.
	// v128: acc. eq: dims, vbD. $v128arr: gw, gx.
	private static byte[] buildMatvec(boolean into, int vecBase) {
		int params = into ? 3 : 2;
		int mat = into ? 1 : 0;
		int vec = into ? 2 : 1;
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int d = params, n = params + 1, kind = params + 2, shift = params + 3, nrg = params + 4;
		int row = params + 5, flat = params + 6, base = params + 7, off = params + 8, k = params + 9;
		int sum = params + 10; // f64
		int sumF = params + 11; // f32
		int acc = params + 12; // v128
		int dims = params + 13, vbD = params + 14; // (ref null eq)
		int gw = params + 15, gx = params + 16;

		if (into) {
			// out may alias NEITHER x nor w: out[row] folds over all of x, and the row
			// windows keep reading W while the rows already computed are written back.
			// vec.lisp, JvmSimdVectorTemplate and VecSimd all reject both.
			requireNotAliased(w, vec);
			requireNotAliased(w, mat);
		}
		// dims = W.dims; trap unless rank 2
		farrayField(w, mat, 0);
		WasmVecLoops.set(w, dims);
		WasmVecLoops.get(w, dims);
		castBuckets(w);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		i32Const(w, 2);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		bucketI32(w, dims, 0, d);
		bucketI32(w, dims, 1, n);
		// kind from the matrix; the vector must match
		farrayKind(w, mat);
		WasmVecLoops.set(w, kind);
		requireSameKind(w, kind, vec);
		WasmVecLoops.get(w, kind);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		WasmVecLoops.set(w, shift);
		// nrg = ceil(n / lanes): the groups one row spans
		ceilShift(w, n, shift, nrg);
		if (into) {
			requireSameKind(w, kind, 0);
			farrayField(w, 0, 1);
			WasmVecLoops.set(w, vbD);
		}
		else {
			WasmVecLoops.get(w, d);
			WasmVecLoops.get(w, kind);
			w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + V_NEW);
			WasmVecLoops.set(w, vbD);
		}
		farrayGroups(w, mat, gw);
		farrayGroups(w, vec, gx);
		WasmVecLoops.get(w, kind);
		w.write(Instruction.IF, 0x40);
		emitMatvecRows(w, d, n, nrg, row, flat, base, off, k, sumF, acc, gw, gx, vbD, true, vecBase);
		w.write(Instruction.ELSE);
		emitMatvecRows(w, d, n, nrg, row, flat, base, off, k, sum, acc, gw, gx, vbD, false, vecBase);
		w.write(Instruction.END);
		finish(w, into, d, vbD);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 10, 1, 1, 1, 2, 2);
	}

	// for (row = 0, flat = 0; row < d; row++, flat += n) dst[row] = dot(W row, x)
	private static void emitMatvecRows(WasmWriter w, int d, int n, int nrg, int row, int flat, int base, int off, int k,
			int sumLocal, int acc, int gw, int gx, int vbD, boolean single, int vecBase) {
		int laneShift = WasmVecLoops.laneShift(single);
		int lanes = WasmVecLoops.lanes(single);
		i32Const(w, 0);
		WasmVecLoops.set(w, row);
		i32Const(w, 0);
		WasmVecLoops.set(w, flat);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		WasmVecLoops.get(w, row);
		WasmVecLoops.get(w, d);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// base = flat >> laneShift; off = flat & (lanes - 1)
		WasmVecLoops.get(w, flat);
		i32Const(w, laneShift);
		w.write(Instruction.I32_SHR_U);
		WasmVecLoops.set(w, base);
		WasmVecLoops.get(w, flat);
		i32Const(w, lanes - 1);
		w.write(Instruction.I32_AND);
		WasmVecLoops.set(w, off);
		WasmVecLoops.splatZero(w, acc, single);
		emitLaneChain(w, off, lanes, 0x40, o -> emitRowDot(w, nrg, k, base, o, acc, gw, gx, single));
		// dst[row] = fold(acc), through the shared element writer
		if (single) {
			WasmVecLoops.horizontalAddF32(w, acc, sumLocal);
		}
		else {
			WasmVecLoops.horizontalAdd(w, acc, sumLocal);
		}
		WasmVecLoops.get(w, vbD);
		WasmVecLoops.get(w, row);
		WasmVecLoops.get(w, sumLocal);
		if (single) {
			w.write(Instruction.F64_PROMOTE_F32);
		}
		w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + V_SET);
		w.write(Instruction.DROP);
		// flat += n; row++
		WasmVecLoops.get(w, flat);
		WasmVecLoops.get(w, n);
		w.write(Instruction.I32_ADD);
		WasmVecLoops.set(w, flat);
		WasmVecLoops.get(w, row);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		WasmVecLoops.set(w, row);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	// acc += window(W, base + k, off) * x[k], over k in [0, nrg). `off` is a compile-time
	// lane offset: 0 reads one group, otherwise the shuffle window spans two.
	private static void emitRowDot(WasmWriter w, int nrg, int k, int base, int off, int acc, int gw, int gx,
			boolean single) {
		WasmVecLoops.openGroupLoop(w, nrg, k);
		WasmVecLoops.get(w, acc);
		emitRowGroup(w, gw, base, k, off, single);
		WasmVecLoops.groupGet(w, gx, k);
		WasmVecLoops.simd(w, single ? Instruction.F32X4_MUL : Instruction.F64X2_MUL);
		WasmVecLoops.simd(w, single ? Instruction.F32X4_ADD : Instruction.F64X2_ADD);
		WasmVecLoops.set(w, acc);
		WasmVecLoops.closeGroupLoop(w, k);
	}

	// Pushes the lane group of row element k*lanes: groups[base+k] when the row is group
	// aligned, else the i8x16.shuffle window over groups[base+k] and groups[base+k+1].
	// Package-private: the linalg matrix-product kernel reads its B rows through the
	// same window.
	static void emitRowGroup(WasmWriter w, int gw, int base, int k, int off, boolean single) {
		groupGetOffset(w, gw, base, k, 0);
		if (off == 0) {
			return;
		}
		groupGetOffset(w, gw, base, k, 1);
		int c = off * (single ? 4 : 8);
		WasmVecLoops.simd(w, Instruction.I8X16_SHUFFLE);
		for (int i = 0; i < 16; i++) {
			w.write(c + i);
		}
	}

	// array.get $v128arr (gw, base + k + delta)
	private static void groupGetOffset(WasmWriter w, int gw, int base, int k, int delta) {
		WasmVecLoops.get(w, gw);
		WasmVecLoops.get(w, base);
		WasmVecLoops.get(w, k);
		w.write(Instruction.I32_ADD);
		if (delta != 0) {
			i32Const(w, delta);
			w.write(Instruction.I32_ADD);
		}
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_V128ARR);
	}

	// --- shared emit helpers --------------------------------------------------------

	static void i32Const(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST).writeSignedLeb128(value);
	}

	// count/kind of the farray in argLocal, plus shift = kind + 1 and the REAL group
	// count
	// ng = ceil(count / lanes) -- never the trailing sentinel.
	static void loadHeader(WasmWriter w, int argLocal, int countLocal, int kindLocal, int shiftLocal, int ngLocal) {
		farrayCount(w, argLocal);
		WasmVecLoops.set(w, countLocal);
		farrayKind(w, argLocal);
		WasmVecLoops.set(w, kindLocal);
		WasmVecLoops.get(w, kindLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		WasmVecLoops.set(w, shiftLocal);
		ceilShift(w, countLocal, shiftLocal, ngLocal);
	}

	// outLocal = ceil(valueLocal / (1 << shiftLocal))
	static void ceilShift(WasmWriter w, int valueLocal, int shiftLocal, int outLocal) {
		WasmVecLoops.get(w, valueLocal);
		i32Const(w, 1);
		WasmVecLoops.get(w, shiftLocal);
		w.write(Instruction.I32_SHL);
		i32Const(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		WasmVecLoops.get(w, shiftLocal);
		w.write(Instruction.I32_SHR_U);
		WasmVecLoops.set(w, outLocal);
	}

	// Sets up the destination groups: the caller's block for -into (same width required),
	// a fresh zeroed $vblock otherwise.
	private static void destination(WasmWriter w, boolean into, int countLocal, int kindLocal, int vbDLocal,
			int gdLocal, int vecBase) {
		if (into) {
			requireSameKind(w, kindLocal, 0);
			farrayGroups(w, 0, gdLocal);
		}
		else {
			WasmVecLoops.get(w, countLocal);
			WasmVecLoops.get(w, kindLocal);
			w.write(Instruction.CALL).writeUnsignedLeb128(vecBase + V_NEW);
			WasmVecLoops.set(w, vbDLocal);
			vblockGroups(w, vbDLocal, gdLocal);
		}
	}

	// The kernel's return value: the caller's destination farray for -into, a fresh
	// rank-1
	// farray over the new $vblock otherwise.
	private static void finish(WasmWriter w, boolean into, int countLocal, int vbDLocal) {
		if (into) {
			WasmVecLoops.get(w, 0);
		}
		else {
			makeFarray(w, countLocal, vbDLocal);
		}
	}

	// Pushes field `field` of the $farray held (as eq) in argLocal.
	static void farrayField(WasmWriter w, int argLocal, int field) {
		WasmVecLoops.get(w, argLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
		w.writeUnsignedLeb128(field);
	}

	// Pushes field `field` of the $vblock held (as eq) in vbLocal.
	static void vblockField(WasmWriter w, int vbLocal, int field) {
		WasmVecLoops.get(w, vbLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_VBLOCK);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_VBLOCK);
		w.writeUnsignedLeb128(field);
	}

	// Pushes the element count of the farray in argLocal.
	static void farrayCount(WasmWriter w, int argLocal) {
		farrayField(w, argLocal, 1);
		castVblockField(w, 0);
	}

	// Pushes the width tag of the farray in argLocal (0 = double, 1 = single).
	static void farrayKind(WasmWriter w, int argLocal) {
		farrayField(w, argLocal, 1);
		castVblockField(w, 1);
	}

	// Consumes the $vblock (as eq) on the stack and pushes the given field.
	private static void castVblockField(WasmWriter w, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_VBLOCK);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_VBLOCK);
		w.writeUnsignedLeb128(field);
	}

	// outLocal((ref null $v128arr)) = the group array of the farray in argLocal.
	static void farrayGroups(WasmWriter w, int argLocal, int outLocal) {
		farrayField(w, argLocal, 1);
		castGroups(w, outLocal);
	}

	// outLocal((ref null $v128arr)) = the group array of the $vblock (as eq) in vbLocal.
	static void vblockGroups(WasmWriter w, int vbLocal, int outLocal) {
		WasmVecLoops.get(w, vbLocal);
		castGroups(w, outLocal);
	}

	// Consumes the $vblock (as eq) on the stack and stores its groups into outLocal.
	private static void castGroups(WasmWriter w, int outLocal) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_VBLOCK);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_VBLOCK);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_V128ARR);
		WasmVecLoops.set(w, outLocal);
	}

	// gLocal = idx >> laneShift
	private static void groupIndex(WasmWriter w, int idxLocal, int gLocal, boolean single) {
		WasmVecLoops.get(w, idxLocal);
		i32Const(w, WasmVecLoops.laneShift(single));
		w.write(Instruction.I32_SHR_U);
		WasmVecLoops.set(w, gLocal);
	}

	// vLocal(v128) = groups[idx >> laneShift]
	private static void loadGroupOf(WasmWriter w, int groupsLocal, int idxLocal, int vLocal, boolean single) {
		WasmVecLoops.get(w, groupsLocal);
		WasmVecLoops.get(w, idxLocal);
		i32Const(w, WasmVecLoops.laneShift(single));
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_V128ARR);
		WasmVecLoops.set(w, vLocal);
	}

	// groups[g] = v
	private static void storeGroup(WasmWriter w, int groupsLocal, int gLocal, int vLocal) {
		WasmVecLoops.get(w, groupsLocal);
		WasmVecLoops.get(w, gLocal);
		WasmVecLoops.get(w, vLocal);
		WasmVecLoops.arraySet(w);
	}

	static void castBuckets(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	// outLocal(i32) = i31.get_s(buckets[index]) -- a dimension size.
	static void bucketI32(WasmWriter w, int bucketsLocal, int index, int outLocal) {
		WasmVecLoops.get(w, bucketsLocal);
		castBuckets(w);
		i32Const(w, index);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		WasmVecLoops.set(w, outLocal);
	}

	// Traps when the -into destination (param 0) IS the farray in otherLocal. The struct
	// identity is what `eq` compares, so ref.eq on the two $farray values is the exact
	// analogue of the vec.lisp guard.
	private static void requireNotAliased(WasmWriter w, int otherLocal) {
		WasmVecLoops.get(w, 0);
		WasmVecLoops.get(w, otherLocal);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
	}

	// Traps unless the farray in otherLocal has the same element width as kindLocal.
	// Mixing a #d and a #f operand is a hard error on every backend.
	private static void requireSameKind(WasmWriter w, int kindLocal, int otherLocal) {
		WasmVecLoops.get(w, kindLocal);
		farrayKind(w, otherLocal);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
	}

	// Pushes struct.new $farray(array.new $hash_buckets(i31(count), 1), vblock) -- a
	// fresh
	// rank-1 packed array over the given block. (The vec: kernels are rank-1 producers;
	// matvec's result is rank-1 too. This matches the JVM bridge, whose result header is
	// always [1.0, n, ...].)
	static void makeFarray(WasmWriter w, int countLocal, int vbLocal) {
		WasmVecLoops.get(w, countLocal);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		i32Const(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		WasmVecLoops.get(w, vbLocal);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FARRAY);
	}

	// Boxes the f64 on the stack into a TYPE_FLOAT struct.
	static void boxFloat(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
	}

	// Converts the (ref null eq) on the stack to an f64, mirroring
	// WasmEmitHelper.castFloatGetF64 (i31 integer / ratio / float struct). tmpLocal is a
	// (ref null eq) scratch.
	static void unboxF64(WasmWriter w, int tmpLocal) {
		WasmVecLoops.set(w, tmpLocal);
		WasmVecLoops.get(w, tmpLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.get(w, tmpLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, tmpLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.get(w, tmpLocal);
		w.write(Instruction.CALL).writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.F64_CONVERT_S_I32);
		WasmVecLoops.get(w, tmpLocal);
		w.write(Instruction.CALL).writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, tmpLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// Prepends the local declaration groups, in the fixed order the index arithmetic
	// above assumes: i32, f64, f32, v128, (ref null eq), (ref null $v128arr).
	static byte[] withLocals(byte[] body, int i32Count, int f64Count, int f32Count, int v128Count, int eqCount,
			int groupsCount) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		int groups = (i32Count > 0 ? 1 : 0) + (f64Count > 0 ? 1 : 0) + (f32Count > 0 ? 1 : 0) + (v128Count > 0 ? 1 : 0)
				+ (eqCount > 0 ? 1 : 0) + (groupsCount > 0 ? 1 : 0);
		w.writeUnsignedLeb128(groups);
		if (i32Count > 0) {
			w.writeUnsignedLeb128(i32Count);
			w.write(Type.I32);
		}
		if (f64Count > 0) {
			w.writeUnsignedLeb128(f64Count);
			w.write(Type.F64);
		}
		if (f32Count > 0) {
			w.writeUnsignedLeb128(f32Count);
			w.write(Type.F32);
		}
		if (v128Count > 0) {
			w.writeUnsignedLeb128(v128Count);
			w.write(Type.V128);
		}
		if (eqCount > 0) {
			w.writeUnsignedLeb128(eqCount);
			w.writeRefType(true, Type.EQ.code());
		}
		if (groupsCount > 0) {
			w.writeUnsignedLeb128(groupsCount);
			w.writeRefType(true, WasmLispCompiler.TYPE_V128ARR);
		}
		w.write((Object) body);
		return out.toByteArray();
	}

}
