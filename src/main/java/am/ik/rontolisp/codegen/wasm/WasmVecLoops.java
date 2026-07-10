package am.ik.rontolisp.codegen.wasm;

import am.ik.wasm.Instruction;
import am.ik.wasm.WasmWriter;

/**
 * The vectorizable {@code vec:} kernel loops, expressed once over raw locals (pointers or
 * array references, an element or group count and the element width) rather than over any
 * one backend's Lisp forms.
 *
 * <p>
 * Two backends drive these loops over two different element stores:
 *
 * <ul>
 * <li>{@link NoGcWasmCompiler} inlines the <em>linear-memory</em> bodies ({@code simd*} /
 * {@code scalar*}) into a defun body over its {@code [count:i32][elements]} block,
 * addressing it with {@code v128.load} / {@code v128.store} through a raw i32 data
 * pointer.</li>
 * <li>{@link WasmVecSimdRuntimeBuilder} emits the <em>GC group</em> bodies ({@code gc*})
 * inside standalone runtime helper functions over the wasm-GC {@code --simd} store: an
 * {@code (array (mut v128))} of lane GROUPS reached through a {@code $vblock}
 * struct.</li>
 * </ul>
 *
 * <p>
 * Every emitter here takes locals that the CALLER has already materialized (the data
 * pointers or group arrays, the element/group count, a splat-zeroed v128 accumulator, an
 * f64 scalar) and emits only the loop and -- for the reductions -- the folded result.
 * That split is deliberate: it lets each backend set its locals up in whatever order its
 * own local allocator demands while the loop bodies stay byte-identical between them.
 *
 * <p>
 * Widths: {@code single == false} is the {@code f64x2} path (two f64 lanes per
 * iteration), {@code single == true} the {@code f32x4} path (four f32 lanes). Each width
 * computes entirely in its own native precision; the reductions promote to f64 only at
 * the value boundary.
 *
 * <p>
 * <strong>Tails.</strong> The linear bodies own their scalar tail ({@code count >> shift}
 * whole vectors, then the leftover elements one at a time), because the block ends at the
 * last element. The GC group bodies have <em>no tail</em>: the last group's lanes past
 * the element count are padding that {@code array.new_default} zeroed, so
 * {@code add}/{@code sub}/{@code mul}/{@code scale} map zero to zero and
 * {@code sum}/{@code dot} fold zero in. The <em>writing</em> kernels do pay for one
 * thing: a whole-group store reaches up to {@code lanes - 1} elements past {@code count},
 * so {@link #gcSaveLastGroup} / {@link #gcRestoreLastGroupTail} bracket the loop and
 * restore those lanes from the destination's pre-loop value -- otherwise an {@code -into}
 * destination longer than its operands would lose real elements.
 */
final class WasmVecLoops {

	private WasmVecLoops() {
	}

	// --- raw instruction helpers -------------------------------------------------

	static void get(WasmWriter w, int local) {
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(local);
	}

	static void set(WasmWriter w, int local) {
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(local);
	}

	static void i32Const(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST).writeSignedLeb128(value);
	}

	/**
	 * Pushes an f32 constant as its widening f64 constant narrowed with
	 * {@code f32.demote_f64} (an exact round-trip), so no f32 immediate encoding is
	 * needed.
	 */
	static void f32Const(WasmWriter w, float value) {
		w.write(Instruction.F64_CONST).writeF64(value);
		w.write(Instruction.F32_DEMOTE_F64);
	}

	/**
	 * Emits the SIMD prefix (0xFD) then the u32-LEB sub-opcode. Sub-opcodes above 127
	 * (e.g. {@code f64x2.add} = 0xF0) MUST use the LEB path, so this never uses the
	 * single-byte writer.
	 */
	static void simd(WasmWriter w, int subOpcode) {
		w.write(Instruction.SIMD_PREFIX);
		w.writeUnsignedLeb128(subOpcode);
	}

	/**
	 * {@code v128.load} with a byte-aligned memarg (align 0, offset 0). Byte alignment is
	 * always valid, so correctness never depends on the block's actual alignment.
	 */
	static void simdLoad(WasmWriter w) {
		simd(w, Instruction.V128_LOAD);
		w.write(0x00, 0x00);
	}

	static void simdStore(WasmWriter w) {
		simd(w, Instruction.V128_STORE);
		w.write(0x00, 0x00);
	}

	/** {@code ptr += bytes}. */
	static void advancePtr(WasmWriter w, int ptrLocal, int bytes) {
		get(w, ptrLocal);
		i32Const(w, bytes);
		w.write(Instruction.I32_ADD);
		set(w, ptrLocal);
	}

	/** Maps an f64x2 lane op to its f32x4 sibling. */
	static int f32x4Of(int f64x2Op) {
		return switch (f64x2Op) {
			case Instruction.F64X2_ADD -> Instruction.F32X4_ADD;
			case Instruction.F64X2_SUB -> Instruction.F32X4_SUB;
			case Instruction.F64X2_MUL -> Instruction.F32X4_MUL;
			case Instruction.F64X2_DIV -> Instruction.F32X4_DIV;
			default ->
				throw new IllegalArgumentException("no f32x4 sibling for f64x2 op 0x" + Integer.toHexString(f64x2Op));
		};
	}

	/** Maps an f64 scalar arithmetic op to its f32 sibling. */
	static int f32ScalarOf(int f64Op) {
		return switch (f64Op) {
			case Instruction.F64_ADD -> Instruction.F32_ADD;
			case Instruction.F64_SUB -> Instruction.F32_SUB;
			case Instruction.F64_MUL -> Instruction.F32_MUL;
			default -> throw new IllegalArgumentException("no f32 sibling for f64 op 0x" + Integer.toHexString(f64Op));
		};
	}

	/** The element stride in bytes: 4 for single-float, 8 for double-float. */
	static int stride(boolean single) {
		return single ? 4 : 8;
	}

	// --- loop scaffolding --------------------------------------------------------

	/**
	 * Opens the SIMD loop header over {@code count >> laneShift} vector groups
	 * ({@code laneShift} 1 = f64x2 pairs, 2 = f32x4 quads): block/loop, break when the
	 * group count is zero. Leaves the block+loop open (the caller emits the body then
	 * {@link #closeLoop}).
	 */
	static void openSimdLoop(WasmWriter w, int countLocal, int remLocal, int laneShift) {
		get(w, countLocal);
		i32Const(w, laneShift);
		w.write(Instruction.I32_SHR_U);
		set(w, remLocal);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, remLocal);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
	}

	/**
	 * Opens a loop over ALL {@code count} elements (one element per iteration): the
	 * v128-free counterpart of {@link #openSimdLoop}'s group loop.
	 */
	static void openScalarCountLoop(WasmWriter w, int countLocal, int remLocal) {
		get(w, countLocal);
		set(w, remLocal);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, remLocal);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
	}

	/**
	 * Opens a scalar remainder loop over the last {@code count & mask} elements. Used for
	 * the f32x4 tail (mask 3, up to 3 leftover), where the f64x2 single odd-element guard
	 * does not suffice.
	 */
	static void openScalarTailLoop(WasmWriter w, int countLocal, int remLocal, int mask) {
		get(w, countLocal);
		i32Const(w, mask);
		w.write(Instruction.I32_AND);
		set(w, remLocal);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, remLocal);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
	}

	/** Closes a loop opened above: {@code rem--}, branch back, end loop, end block. */
	static void closeLoop(WasmWriter w, int remLocal) {
		get(w, remLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, remLocal);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	/**
	 * Opens {@code if (count & 1) { ...tail... }} (void blocktype); the caller emits the
	 * body and the matching {@code end}. Handles the last element of an odd-length f64x2
	 * run.
	 */
	static void openOddTailGuard(WasmWriter w, int countLocal) {
		get(w, countLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
	}

	/** {@code acc(v128) = f64x2.splat(0.0)}. */
	static void splatZero(WasmWriter w, int accLocal) {
		w.write(Instruction.F64_CONST).writeF64(0.0);
		simd(w, Instruction.F64X2_SPLAT);
		set(w, accLocal);
	}

	/** {@code acc(v128) = f32x4.splat(0.0f)}. */
	static void splatZeroF32(WasmWriter w, int accLocal) {
		f32Const(w, 0.0f);
		simd(w, Instruction.F32X4_SPLAT);
		set(w, accLocal);
	}

	/** Splat-zeroes {@code acc} at the accumulator's lane width. */
	static void splatZero(WasmWriter w, int accLocal, boolean single) {
		if (single) {
			splatZeroF32(w, accLocal);
		}
		else {
			splatZero(w, accLocal);
		}
	}

	/** {@code sum(f64) = lane0(acc) + lane1(acc)}. */
	static void horizontalAdd(WasmWriter w, int accLocal, int sumLocal) {
		get(w, accLocal);
		simd(w, Instruction.F64X2_EXTRACT_LANE);
		w.write(0x00);
		get(w, accLocal);
		simd(w, Instruction.F64X2_EXTRACT_LANE);
		w.write(0x01);
		w.write(Instruction.F64_ADD);
		set(w, sumLocal);
	}

	/** {@code sum(f32) = lane0 + lane1 + lane2 + lane3 of acc}. */
	static void horizontalAddF32(WasmWriter w, int accLocal, int sumLocal) {
		get(w, accLocal);
		simd(w, Instruction.F32X4_EXTRACT_LANE);
		w.write(0x00);
		for (int lane = 1; lane < 4; lane++) {
			get(w, accLocal);
			simd(w, Instruction.F32X4_EXTRACT_LANE);
			w.write(lane);
			w.write(Instruction.F32_ADD);
		}
		set(w, sumLocal);
	}

	// --- v128 kernel bodies ------------------------------------------------------
	//
	// Preconditions for all four: the pointer locals already hold DATA pointers (past the
	// block header) and countLocal the element count. The emitters advance the pointers
	// and clobber remLocal / tremLocal / accLocal / sumLocal; they leave the pointers
	// past the end of their runs.

	/**
	 * {@code dst[i] = op(a[i], b[i])} over v128 lanes plus a scalar tail.
	 * {@code tremLocal} is used only by the f32x4 remainder loop (pass -1 for f64x2).
	 */
	static void simdMap2(WasmWriter w, int dp, int ap, int bp, int count, int rem, int trem, boolean single,
			int f64x2Op, int f64Op) {
		int laneOp = single ? f32x4Of(f64x2Op) : f64x2Op;
		int scalarOp = single ? f32ScalarOf(f64Op) : f64Op;
		int loadOp = single ? Instruction.F32_LOAD : Instruction.F64_LOAD;
		int storeOp = single ? Instruction.F32_STORE : Instruction.F64_STORE;
		openSimdLoop(w, count, rem, single ? 2 : 1);
		// mem[dp] = <lane op>(v128.load ap, v128.load bp)
		get(w, dp);
		get(w, ap);
		simdLoad(w);
		get(w, bp);
		simdLoad(w);
		simd(w, laneOp);
		simdStore(w);
		advancePtr(w, ap, 16);
		advancePtr(w, bp, 16);
		advancePtr(w, dp, 16);
		closeLoop(w, rem);
		if (single) {
			// scalar remainder over the last count & 3 elements
			openScalarTailLoop(w, count, trem, 3);
			emitMap2Element(w, dp, ap, bp, loadOp, storeOp, scalarOp);
			advancePtr(w, ap, 4);
			advancePtr(w, bp, 4);
			advancePtr(w, dp, 4);
			closeLoop(w, trem);
		}
		else {
			openOddTailGuard(w, count);
			emitMap2Element(w, dp, ap, bp, loadOp, storeOp, scalarOp);
			w.write(Instruction.END); // if
		}
	}

	/**
	 * {@code dst[i] = (if (cmp a[i] b[i]) a[i] b[i])} over v128 lanes plus a scalar tail
	 * (todo 109 Phase 3): the {@code --no-gc} lowering of {@code vec:maximum} /
	 * {@code vec:minimum}, the select sibling of {@link #simdMap2}. bitselect over a
	 * {@code gt} / {@code lt} lane mask, never the IEEE lane min/max -- the second
	 * operand wins any false comparison (NaN and the -0.0/0.0 tie included), matching the
	 * strict-comparison contract every other backend's defun states.
	 */
	static void simdMap2Select(WasmWriter w, int dp, int ap, int bp, int count, int rem, int trem, boolean single,
			boolean greater) {
		openSimdLoop(w, count, rem, single ? 2 : 1);
		get(w, dp);
		get(w, ap);
		simdLoad(w);
		get(w, bp);
		simdLoad(w);
		get(w, ap);
		simdLoad(w);
		get(w, bp);
		simdLoad(w);
		simd(w, laneCompare(single, greater));
		simd(w, Instruction.V128_BITSELECT);
		simdStore(w);
		advancePtr(w, ap, 16);
		advancePtr(w, bp, 16);
		advancePtr(w, dp, 16);
		closeLoop(w, rem);
		if (single) {
			openScalarTailLoop(w, count, trem, 3);
			emitMap2SelectElement(w, dp, ap, bp, single, greater);
			advancePtr(w, ap, 4);
			advancePtr(w, bp, 4);
			advancePtr(w, dp, 4);
			closeLoop(w, trem);
		}
		else {
			openOddTailGuard(w, count);
			emitMap2SelectElement(w, dp, ap, bp, single, greater);
			w.write(Instruction.END); // if
		}
	}

	/** The one-element select body: {@code select(a[i], b[i], cmp(a[i], b[i]))}. */
	private static void emitMap2SelectElement(WasmWriter w, int dp, int ap, int bp, boolean single, boolean greater) {
		int loadOp = single ? Instruction.F32_LOAD : Instruction.F64_LOAD;
		int storeOp = single ? Instruction.F32_STORE : Instruction.F64_STORE;
		get(w, dp);
		get(w, ap);
		w.write(loadOp, 0x00, 0x00);
		get(w, bp);
		w.write(loadOp, 0x00, 0x00);
		get(w, ap);
		w.write(loadOp, 0x00, 0x00);
		get(w, bp);
		w.write(loadOp, 0x00, 0x00);
		if (greater) {
			w.write(single ? Instruction.F32_GT : Instruction.F64_GT);
		}
		else {
			w.write(single ? Instruction.F32_LT : Instruction.F64_LT);
		}
		w.write(Instruction.SELECT);
		w.write(storeOp, 0x00, 0x00);
	}

	/**
	 * {@code dst[i] = v[i] * s} over v128 lanes plus a scalar tail. {@code sLocal} is an
	 * f64 local; on the f32 path it is narrowed per use so the product is computed in
	 * f32.
	 */
	static void simdScale(WasmWriter w, int dp, int vp, int count, int rem, int trem, int sLocal, boolean single) {
		openSimdLoop(w, count, rem, single ? 2 : 1);
		// mem[dp] = <lane mul>(v128.load vp, <lane splat> s)
		get(w, dp);
		get(w, vp);
		simdLoad(w);
		get(w, sLocal);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
			simd(w, Instruction.F32X4_SPLAT);
			simd(w, Instruction.F32X4_MUL);
		}
		else {
			simd(w, Instruction.F64X2_SPLAT);
			simd(w, Instruction.F64X2_MUL);
		}
		simdStore(w);
		advancePtr(w, vp, 16);
		advancePtr(w, dp, 16);
		closeLoop(w, rem);
		if (single) {
			openScalarTailLoop(w, count, trem, 3);
			emitScaleElement(w, dp, vp, sLocal, true);
			advancePtr(w, vp, 4);
			advancePtr(w, dp, 4);
			closeLoop(w, trem);
		}
		else {
			openOddTailGuard(w, count);
			emitScaleElement(w, dp, vp, sLocal, false);
			w.write(Instruction.END); // if
		}
	}

	/**
	 * Horizontal sum over v128 lanes plus the scalar tail; leaves the total as an
	 * <strong>f64</strong> on the stack. {@code accLocal} must already be splat-zeroed at
	 * this width; {@code sumLocal} is f64 for the double path and f32 for the single
	 * path.
	 */
	static void simdSum(WasmWriter w, int vp, int count, int rem, int trem, int accLocal, int sumLocal,
			boolean single) {
		openSimdLoop(w, count, rem, single ? 2 : 1);
		// acc = <lane add>(acc, v128.load vp)
		get(w, accLocal);
		get(w, vp);
		simdLoad(w);
		simd(w, single ? Instruction.F32X4_ADD : Instruction.F64X2_ADD);
		set(w, accLocal);
		advancePtr(w, vp, 16);
		closeLoop(w, rem);
		if (single) {
			horizontalAddF32(w, accLocal, sumLocal);
			openScalarTailLoop(w, count, trem, 3);
			get(w, sumLocal);
			get(w, vp);
			w.write(Instruction.F32_LOAD, 0x00, 0x00);
			w.write(Instruction.F32_ADD);
			set(w, sumLocal);
			advancePtr(w, vp, 4);
			closeLoop(w, trem);
			get(w, sumLocal);
			w.write(Instruction.F64_PROMOTE_F32);
		}
		else {
			horizontalAdd(w, accLocal, sumLocal);
			openOddTailGuard(w, count);
			get(w, sumLocal);
			get(w, vp);
			w.write(Instruction.F64_LOAD, 0x00, 0x00);
			w.write(Instruction.F64_ADD);
			set(w, sumLocal);
			w.write(Instruction.END); // if
			get(w, sumLocal);
		}
	}

	/**
	 * Lane-wise multiply-accumulate plus the scalar tail; leaves {@code sum(a_i * b_i)}
	 * as an <strong>f64</strong> on the stack. Same accumulator contract as
	 * {@link #simdSum}.
	 */
	static void simdDot(WasmWriter w, int ap, int bp, int count, int rem, int trem, int accLocal, int sumLocal,
			boolean single) {
		openSimdLoop(w, count, rem, single ? 2 : 1);
		// acc = <lane add>(acc, <lane mul>(v128.load ap, v128.load bp))
		get(w, accLocal);
		get(w, ap);
		simdLoad(w);
		get(w, bp);
		simdLoad(w);
		simd(w, single ? Instruction.F32X4_MUL : Instruction.F64X2_MUL);
		simd(w, single ? Instruction.F32X4_ADD : Instruction.F64X2_ADD);
		set(w, accLocal);
		advancePtr(w, ap, 16);
		advancePtr(w, bp, 16);
		closeLoop(w, rem);
		if (single) {
			horizontalAddF32(w, accLocal, sumLocal);
			openScalarTailLoop(w, count, trem, 3);
			get(w, sumLocal);
			get(w, ap);
			w.write(Instruction.F32_LOAD, 0x00, 0x00);
			get(w, bp);
			w.write(Instruction.F32_LOAD, 0x00, 0x00);
			w.write(Instruction.F32_MUL);
			w.write(Instruction.F32_ADD);
			set(w, sumLocal);
			advancePtr(w, ap, 4);
			advancePtr(w, bp, 4);
			closeLoop(w, trem);
			get(w, sumLocal);
			w.write(Instruction.F64_PROMOTE_F32);
		}
		else {
			horizontalAdd(w, accLocal, sumLocal);
			openOddTailGuard(w, count);
			get(w, sumLocal);
			get(w, ap);
			w.write(Instruction.F64_LOAD, 0x00, 0x00);
			get(w, bp);
			w.write(Instruction.F64_LOAD, 0x00, 0x00);
			w.write(Instruction.F64_MUL);
			w.write(Instruction.F64_ADD);
			set(w, sumLocal);
			w.write(Instruction.END); // if
			get(w, sumLocal);
		}
	}

	// --- linear-memory unary ufunc bodies (todo 109, --no-gc only) -----------------
	//
	// The --no-gc lowering of the arithmetic unary vec: kernels (sqrt / abs / square /
	// negative / reciprocal + their -into siblings). --no-gc has NO vec.lisp defun to
	// mirror, so unlike gcMap1's U_NEG / U_ABS (which reproduce the wasm-GC defun's
	// 0 - x semantics) these use the NATIVE IEEE instructions (f64.neg keeps
	// (vec:negative #d(0.0)) at -0.0, f64.abs maps -0.0 to 0.0 -- the same edges the
	// interpreter / JVM defuns produce). U_SQUARE exists only here: on the defun-driven
	// backends vec:square rides the mul kernels. The v128 and scalar lowerings compute
	// identical results (every op is exact or correctly rounded per element). exp and
	// sign are not driven through these bodies: their --no-gc lowering is a dedicated
	// element loop over the raw-f64 emitters (NoGcWasmCompiler.compileSimdUnaryF64).

	/** {@code v * v}: the one unary op with no gcMap1 form (square is mul there). */
	static final int U_SQUARE = 4;

	/**
	 * {@code dst[i] = uop(v[i])} over v128 lanes plus a scalar tail (the unary sibling of
	 * {@link #simdMap2}). {@code tremLocal} is used only by the f32x4 remainder loop
	 * (pass -1 for f64x2).
	 */
	static void simdMap1(WasmWriter w, int dp, int vp, int count, int rem, int trem, boolean single, int uop) {
		openSimdLoop(w, count, rem, single ? 2 : 1);
		get(w, dp);
		if (uop == U_RECIP) {
			// The dividend group goes UNDER the operand: splat(1) / v.
			splatConstOne(w, single);
		}
		get(w, vp);
		simdLoad(w);
		switch (uop) {
			case U_SQRT -> simd(w, single ? Instruction.F32X4_SQRT : Instruction.F64X2_SQRT);
			case U_ABS -> simd(w, single ? Instruction.F32X4_ABS : Instruction.F64X2_ABS);
			case U_NEG -> simd(w, single ? Instruction.F32X4_NEG : Instruction.F64X2_NEG);
			case U_SQUARE -> {
				get(w, vp);
				simdLoad(w);
				simd(w, single ? Instruction.F32X4_MUL : Instruction.F64X2_MUL);
			}
			case U_RECIP -> simd(w, single ? Instruction.F32X4_DIV : Instruction.F64X2_DIV);
			case U_RELU -> {
				// bitselect picks v where the mask (v > 0) is set, 0 elsewhere -- the
				// (if (> x 0.0) x 0.0) select the other backends' defun states, never
				// the IEEE lane max (a NaN lane becomes 0.0 here).
				splatConstZero(w, single);
				get(w, vp);
				simdLoad(w);
				splatConstZero(w, single);
				simd(w, single ? Instruction.F32X4_GT : Instruction.F64X2_GT);
				simd(w, Instruction.V128_BITSELECT);
			}
			default -> throw new IllegalArgumentException("no linear unary lane op " + uop);
		}
		simdStore(w);
		advancePtr(w, vp, 16);
		advancePtr(w, dp, 16);
		closeLoop(w, rem);
		if (single) {
			openScalarTailLoop(w, count, trem, 3);
			emitMap1Element(w, dp, vp, true, uop);
			advancePtr(w, vp, 4);
			advancePtr(w, dp, 4);
			closeLoop(w, trem);
		}
		else {
			openOddTailGuard(w, count);
			emitMap1Element(w, dp, vp, false, uop);
			w.write(Instruction.END); // if
		}
	}

	/** {@code dst[i] = uop(v[i])} over a plain one-element-per-iteration loop. */
	static void scalarMap1(WasmWriter w, int dp, int vp, int count, int rem, boolean single, int uop) {
		int stride = stride(single);
		openScalarCountLoop(w, count, rem);
		emitMap1Element(w, dp, vp, single, uop);
		advancePtr(w, vp, stride);
		advancePtr(w, dp, stride);
		closeLoop(w, rem);
	}

	private static void emitMap1Element(WasmWriter w, int dp, int vp, boolean single, int uop) {
		int loadOp = single ? Instruction.F32_LOAD : Instruction.F64_LOAD;
		int storeOp = single ? Instruction.F32_STORE : Instruction.F64_STORE;
		get(w, dp);
		if (uop == U_RECIP) {
			if (single) {
				f32Const(w, 1.0f);
			}
			else {
				w.write(Instruction.F64_CONST).writeF64(1.0);
			}
		}
		get(w, vp);
		w.write(loadOp, 0x00, 0x00);
		switch (uop) {
			case U_SQRT -> w.write(single ? Instruction.F32_SQRT : Instruction.F64_SQRT);
			case U_ABS -> w.write(single ? Instruction.F32_ABS : Instruction.F64_ABS);
			case U_NEG -> w.write(single ? Instruction.F32_NEG : Instruction.F64_NEG);
			case U_SQUARE -> {
				get(w, vp);
				w.write(loadOp, 0x00, 0x00);
				w.write(single ? Instruction.F32_MUL : Instruction.F64_MUL);
			}
			case U_RECIP -> w.write(single ? Instruction.F32_DIV : Instruction.F64_DIV);
			case U_RELU -> {
				// select(x, 0, x > 0); the operand is reloaded for the comparison.
				pushZero(w, single);
				get(w, vp);
				w.write(loadOp, 0x00, 0x00);
				pushZero(w, single);
				w.write(single ? Instruction.F32_GT : Instruction.F64_GT);
				w.write(Instruction.SELECT);
			}
			default -> throw new IllegalArgumentException("no linear unary element op " + uop);
		}
		w.write(storeOp, 0x00, 0x00);
	}

	/** Pushes a scalar zero constant at the given width. */
	private static void pushZero(WasmWriter w, boolean single) {
		if (single) {
			f32Const(w, 0.0f);
		}
		else {
			w.write(Instruction.F64_CONST).writeF64(0.0);
		}
	}

	// --- scalar (v128-free) kernel bodies ----------------------------------------
	//
	// One element per iteration, no 0xFD opcode: the lowering a runtime lacking the SIMD
	// proposal can run. The block layout is identical, so these compute the same result
	// over the same memory (element-wise bit-for-bit; reductions modulo summation order).

	/** {@code dst[i] = op(a[i], b[i])} over a plain one-element-per-iteration loop. */
	static void scalarMap2(WasmWriter w, int dp, int ap, int bp, int count, int rem, boolean single, int f64Op) {
		int loadOp = single ? Instruction.F32_LOAD : Instruction.F64_LOAD;
		int storeOp = single ? Instruction.F32_STORE : Instruction.F64_STORE;
		int op = single ? f32ScalarOf(f64Op) : f64Op;
		int stride = stride(single);
		openScalarCountLoop(w, count, rem);
		emitMap2Element(w, dp, ap, bp, loadOp, storeOp, op);
		advancePtr(w, ap, stride);
		advancePtr(w, bp, stride);
		advancePtr(w, dp, stride);
		closeLoop(w, rem);
	}

	/**
	 * {@code dst[i] = (if (cmp a[i] b[i]) a[i] b[i])} over a plain
	 * one-element-per-iteration loop (the v128-free sibling of {@link #simdMap2Select}).
	 */
	static void scalarMap2Select(WasmWriter w, int dp, int ap, int bp, int count, int rem, boolean single,
			boolean greater) {
		int stride = stride(single);
		openScalarCountLoop(w, count, rem);
		emitMap2SelectElement(w, dp, ap, bp, single, greater);
		advancePtr(w, ap, stride);
		advancePtr(w, bp, stride);
		advancePtr(w, dp, stride);
		closeLoop(w, rem);
	}

	/** {@code dst[i] = v[i] * s} over a plain one-element-per-iteration loop. */
	static void scalarScale(WasmWriter w, int dp, int vp, int count, int rem, int sLocal, boolean single) {
		int stride = stride(single);
		openScalarCountLoop(w, count, rem);
		emitScaleElement(w, dp, vp, sLocal, single);
		advancePtr(w, vp, stride);
		advancePtr(w, dp, stride);
		closeLoop(w, rem);
	}

	/**
	 * A left-to-right scalar sum, leaving the f64 total on the stack. {@code sumLocal} is
	 * f64 for the double path and f32 for the single path; this emitter initializes it.
	 */
	static void scalarSum(WasmWriter w, int vp, int count, int rem, int sumLocal, boolean single) {
		int stride = stride(single);
		if (single) {
			f32Const(w, 0.0f);
		}
		else {
			w.write(Instruction.F64_CONST).writeF64(0.0);
		}
		set(w, sumLocal);
		openScalarCountLoop(w, count, rem);
		get(w, sumLocal);
		get(w, vp);
		w.write(single ? Instruction.F32_LOAD : Instruction.F64_LOAD, 0x00, 0x00);
		w.write(single ? Instruction.F32_ADD : Instruction.F64_ADD);
		set(w, sumLocal);
		advancePtr(w, vp, stride);
		closeLoop(w, rem);
		get(w, sumLocal);
		if (single) {
			w.write(Instruction.F64_PROMOTE_F32);
		}
	}

	/**
	 * A left-to-right scalar sum of {@code a[i]*b[i]}, leaving the f64 total on the
	 * stack.
	 */
	static void scalarDot(WasmWriter w, int ap, int bp, int count, int rem, int accLocal, boolean single) {
		int stride = stride(single);
		if (single) {
			f32Const(w, 0.0f);
		}
		else {
			w.write(Instruction.F64_CONST).writeF64(0.0);
		}
		set(w, accLocal);
		openScalarCountLoop(w, count, rem);
		get(w, accLocal);
		get(w, ap);
		w.write(single ? Instruction.F32_LOAD : Instruction.F64_LOAD, 0x00, 0x00);
		get(w, bp);
		w.write(single ? Instruction.F32_LOAD : Instruction.F64_LOAD, 0x00, 0x00);
		w.write(single ? Instruction.F32_MUL : Instruction.F64_MUL);
		w.write(single ? Instruction.F32_ADD : Instruction.F64_ADD);
		set(w, accLocal);
		advancePtr(w, ap, stride);
		advancePtr(w, bp, stride);
		closeLoop(w, rem);
		get(w, accLocal);
		if (single) {
			w.write(Instruction.F64_PROMOTE_F32);
		}
	}

	// --- wasm-GC group (v128 array) kernel bodies --------------------------------
	//
	// Preconditions for all four: the group locals hold (ref null $v128arr) group arrays,
	// ngroupsLocal the REAL group count ceil(count / lanes) -- never the trailing zero
	// sentinel group -- and gLocal is a free i32 the emitter uses as its group cursor.
	// No scalar tail: see the class comment.

	/** The number of lanes per group at this width: 2 f64 or 4 f32. */
	static int lanes(boolean single) {
		return single ? 4 : 2;
	}

	/** {@code log2(lanes)}: the shift from an element index to its group index. */
	static int laneShift(boolean single) {
		return single ? 2 : 1;
	}

	/** {@code array.get $v128arr (arr, idx)}: pushes one lane group. */
	static void groupGet(WasmWriter w, int arrLocal, int idxLocal) {
		get(w, arrLocal);
		get(w, idxLocal);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_V128ARR);
	}

	/**
	 * Opens a loop over group indices {@code [0, ngroups)}, leaving the cursor in
	 * {@code gLocal}. Pairs with {@link #closeGroupLoop}.
	 */
	static void openGroupLoop(WasmWriter w, int ngroupsLocal, int gLocal) {
		i32Const(w, 0);
		set(w, gLocal);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, gLocal);
		get(w, ngroupsLocal);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
	}

	/** Closes a group loop: {@code g++}, branch back, end loop, end block. */
	static void closeGroupLoop(WasmWriter w, int gLocal) {
		get(w, gLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, gLocal);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	// --- the unary ufunc lane forms (todo 109) ------------------------------------
	// The four wasm-lane-safe unary operations, keyed so gcMap1 owns every opcode
	// choice. Each mirrors the wasm DEFUN's own scalar semantics (the per-backend
	// bit-identity contract), not java.lang.Math's:
	//
	// U_SQRT f64.sqrt is what WasmSqrtCompiler emits; the lane sqrt is correctly
	// rounded at both widths (f32: the widen-compute-narrow round trip is
	// exact), so it is bit-identical.
	// U_NEG the wasm variable-path unary minus is 0 - x (a todo-108 residual:
	// (- 0.0) is 0.0 here, unlike interpreter/JVM), so the lane form is
	// sub-from-splat-zero, NOT f64x2.neg.
	// U_RECIP (/ 1.0 x): div-from-splat-one; f32 lane div is exact by the
	// 53 >= 2*24+2 double-rounding bound.
	// U_ABS the wasm abs variable path is `x < 0 ? 0 - x : x` (keeps -0.0), so
	// the lane form is bitselect(0 - v, v, v < 0), NOT f64x2.abs (which
	// would map -0.0 to 0.0 and diverge from the defun).
	//
	// exp and signum have no lane form at all; their wasm-GC kernels walk elements
	// through _v_get / _v_set (see WasmVecSimdRuntimeBuilder), and the --no-gc
	// lowering walks the linear block with the same raw-f64 emitters
	// (NoGcWasmCompiler.compileSimdUnaryF64).

	static final int U_SQRT = 0;

	static final int U_NEG = 1;

	static final int U_RECIP = 2;

	static final int U_ABS = 3;

	/**
	 * {@code (if (> x 0.0) x 0.0)} (todo 109 Phase 3): bitselect(v, 0, v > 0), the
	 * strict-comparison select the {@code vec:relu} defun spells out -- never the IEEE
	 * lane max, whose NaN handling differs (here a NaN or -0.0 lane becomes 0.0, the
	 * false-comparison arm). The zero bound is exactly representable at both widths, so
	 * the f32 lane compare equals the defun's widened compare and the lane form is
	 * bit-identical, the same argument as U_ABS's {@code v < 0} mask.
	 */
	static final int U_RELU = 5;

	/** {@code dst[g] = uop(v[g])} over whole lane groups (unary sibling of gcMap2). */
	static void gcMap1(WasmWriter w, int gd, int gv, int ngroups, int g, int count, int rem, int old, int cur,
			boolean single, int uop) {
		gcSaveLastGroup(w, gd, ngroups, count, rem, old, single);
		openGroupLoop(w, ngroups, g);
		get(w, gd);
		get(w, g);
		switch (uop) {
			case U_SQRT -> {
				groupGet(w, gv, g);
				simd(w, single ? Instruction.F32X4_SQRT : Instruction.F64X2_SQRT);
			}
			case U_NEG -> {
				splatConstZero(w, single);
				groupGet(w, gv, g);
				simd(w, single ? Instruction.F32X4_SUB : Instruction.F64X2_SUB);
			}
			case U_RECIP -> {
				splatConstOne(w, single);
				groupGet(w, gv, g);
				simd(w, single ? Instruction.F32X4_DIV : Instruction.F64X2_DIV);
			}
			case U_ABS -> {
				// bitselect picks (0 - v) where the mask (v < 0) is set, v elsewhere.
				splatConstZero(w, single);
				groupGet(w, gv, g);
				simd(w, single ? Instruction.F32X4_SUB : Instruction.F64X2_SUB);
				groupGet(w, gv, g);
				groupGet(w, gv, g);
				splatConstZero(w, single);
				simd(w, single ? Instruction.F32X4_LT : Instruction.F64X2_LT);
				simd(w, Instruction.V128_BITSELECT);
			}
			case U_RELU -> {
				// bitselect picks v where the mask (v > 0) is set, 0 elsewhere.
				groupGet(w, gv, g);
				splatConstZero(w, single);
				groupGet(w, gv, g);
				splatConstZero(w, single);
				simd(w, single ? Instruction.F32X4_GT : Instruction.F64X2_GT);
				simd(w, Instruction.V128_BITSELECT);
			}
			default -> throw new IllegalArgumentException("no unary lane op " + uop);
		}
		arraySet(w);
		closeGroupLoop(w, g);
		gcRestoreLastGroupTail(w, gd, ngroups, rem, old, cur, single);
	}

	/**
	 * {@code dst[g] = (if (cmp a[g] b[g]) a[g] b[g])} over whole lane groups (todo 109
	 * Phase 3): bitselect(a, b, a &gt; b) for {@code vec:maximum} ({@code greater}),
	 * bitselect(a, b, a &lt; b) for {@code vec:minimum} -- the strict-comparison selects
	 * the defuns spell out, never the IEEE lane min/max (whose NaN and -0.0 handling
	 * differ; here the SECOND operand wins any false comparison). A select only copies
	 * input bits and an f32 lane compare equals the defun's widened f64 compare, so both
	 * widths are bit-identical to the scalar oracle. Padding lanes compute select(0, 0) =
	 * 0; the save/restore bracket still guards a destination longer than the operands.
	 */
	static void gcMap2Select(WasmWriter w, int gd, int ga, int gb, int ngroups, int g, int count, int rem, int old,
			int cur, boolean single, boolean greater) {
		gcSaveLastGroup(w, gd, ngroups, count, rem, old, single);
		openGroupLoop(w, ngroups, g);
		get(w, gd);
		get(w, g);
		groupGet(w, ga, g);
		groupGet(w, gb, g);
		groupGet(w, ga, g);
		groupGet(w, gb, g);
		simd(w, laneCompare(single, greater));
		simd(w, Instruction.V128_BITSELECT);
		arraySet(w);
		closeGroupLoop(w, g);
		gcRestoreLastGroupTail(w, gd, ngroups, rem, old, cur, single);
	}

	/** The lane-mask comparison opcode for a select: {@code gt} or {@code lt}. */
	private static int laneCompare(boolean single, boolean greater) {
		if (greater) {
			return single ? Instruction.F32X4_GT : Instruction.F64X2_GT;
		}
		return single ? Instruction.F32X4_LT : Instruction.F64X2_LT;
	}

	/** Pushes a zero lane group at the given width. */
	private static void splatConstZero(WasmWriter w, boolean single) {
		if (single) {
			f32Const(w, 0.0f);
			simd(w, Instruction.F32X4_SPLAT);
		}
		else {
			w.write(Instruction.F64_CONST).writeF64(0.0);
			simd(w, Instruction.F64X2_SPLAT);
		}
	}

	/** Pushes an all-ones (1.0) lane group at the given width. */
	private static void splatConstOne(WasmWriter w, boolean single) {
		if (single) {
			f32Const(w, 1.0f);
			simd(w, Instruction.F32X4_SPLAT);
		}
		else {
			w.write(Instruction.F64_CONST).writeF64(1.0);
			simd(w, Instruction.F64X2_SPLAT);
		}
	}

	/**
	 * Opens a loop over ascending element indices {@code [0, count)}, leaving the cursor
	 * in {@code iLocal}. Pairs with {@link #closeIndexLoop}. The element-loop counterpart
	 * of {@link #openGroupLoop} for the kernels that walk {@code _v_get} /
	 * {@code _v_set}.
	 */
	static void openIndexLoop(WasmWriter w, int iLocal, int countLocal) {
		i32Const(w, 0);
		set(w, iLocal);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, iLocal);
		get(w, countLocal);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
	}

	/** Closes an index loop: {@code i++}, branch back, end loop, end block. */
	static void closeIndexLoop(WasmWriter w, int iLocal) {
		get(w, iLocal);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, iLocal);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	/** {@code dst[g] = op(a[g], b[g])} over whole lane groups. */
	static void gcMap2(WasmWriter w, int gd, int ga, int gb, int ngroups, int g, int count, int rem, int old, int cur,
			boolean single, int f64x2Op) {
		int laneOp = single ? f32x4Of(f64x2Op) : f64x2Op;
		gcSaveLastGroup(w, gd, ngroups, count, rem, old, single);
		openGroupLoop(w, ngroups, g);
		get(w, gd);
		get(w, g);
		groupGet(w, ga, g);
		groupGet(w, gb, g);
		simd(w, laneOp);
		arraySet(w);
		closeGroupLoop(w, g);
		gcRestoreLastGroupTail(w, gd, ngroups, rem, old, cur, single);
	}

	/**
	 * {@code dst[g] = v[g] * splat(s)} over whole lane groups. {@code sLocal} is an f64
	 * local, narrowed per use on the f32 path so the product is computed in f32.
	 */
	static void gcScale(WasmWriter w, int gd, int gv, int ngroups, int g, int count, int rem, int old, int cur,
			int sLocal, boolean single) {
		gcSaveLastGroup(w, gd, ngroups, count, rem, old, single);
		openGroupLoop(w, ngroups, g);
		get(w, gd);
		get(w, g);
		groupGet(w, gv, g);
		get(w, sLocal);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
			simd(w, Instruction.F32X4_SPLAT);
			simd(w, Instruction.F32X4_MUL);
		}
		else {
			simd(w, Instruction.F64X2_SPLAT);
			simd(w, Instruction.F64X2_MUL);
		}
		arraySet(w);
		closeGroupLoop(w, g);
		gcRestoreLastGroupTail(w, gd, ngroups, rem, old, cur, single);
	}

	// --- preserving the destination past the operand count ------------------------
	//
	// A group-at-a-time write covers ceil(count / lanes) * lanes elements, up to lanes-1
	// MORE than the `count` the scalar vec.lisp defun writes. Those extra lanes are the
	// destination's own zero padding when it is exactly `count` long (writing op(0,0) = 0
	// there is a no-op), but a DESTINATION LONGER THAN THE OPERANDS -- which the -into
	// contract explicitly allows -- has real elements in them. So the last written group
	// is
	// blended: lanes >= count % lanes keep their original destination value. This also
	// makes `(vec:scale v s)` with a non-finite s leave the padding at zero instead of
	// NaN.
	//
	// remLocal = count % lanes; oldLocal holds the destination's last group as it was
	// BEFORE the loop (read before, so an `out` aliased with an operand still sees its
	// own
	// pre-op lanes). Both no-ops when count is a whole multiple of the lane count.

	/** Saves {@code dst[ngroups-1]} into {@code oldLocal} when a partial group exists. */
	/**
	 * {@code dst[i] = op(v[i], s)} -- or {@code op(s, v[i])} when {@code reversed} --
	 * over whole {@code f64x2} lane groups. The {@code linalg:} scalar-broadcast kernels
	 * (todo-107); {@link #gcScale} is the {@code op = mul, reversed = false} special case
	 * {@code vec:} already had.
	 *
	 * <p>
	 * DOUBLE WIDTH ONLY. A single-float array broadcast against a genuine f64 scalar has
	 * to widen each element, compute in f64 and narrow once -- {@code linalg::%la-bcast}
	 * routes it through {@code emap}, whose element read widens and whose write narrows
	 * -- and splatting {@code f32.demote_f64(s)} into f32 lanes would not do that. The
	 * single-float kernels therefore walk elements through {@code _v_get} /
	 * {@code _v_set}, which promote and demote for free.
	 *
	 * <p>
	 * The save/restore bracket is not optional here as it is for {@link #gcMap2}: an
	 * operation over the last group's zero padding does NOT map zero to zero
	 * ({@code 0 - s = -s}, {@code s / 0 = inf}), so the destination's padding lanes must
	 * be put back, or a later {@code sum} over the result would fold the garbage in.
	 */
	static void gcBroadcastF64(WasmWriter w, int gd, int gv, int ngroups, int g, int count, int rem, int old, int cur,
			int sLocal, int f64x2Op, boolean reversed) {
		gcSaveLastGroup(w, gd, ngroups, count, rem, old, false);
		openGroupLoop(w, ngroups, g);
		get(w, gd);
		get(w, g);
		if (reversed) {
			get(w, sLocal);
			simd(w, Instruction.F64X2_SPLAT);
			groupGet(w, gv, g);
		}
		else {
			groupGet(w, gv, g);
			get(w, sLocal);
			simd(w, Instruction.F64X2_SPLAT);
		}
		simd(w, f64x2Op);
		arraySet(w);
		closeGroupLoop(w, g);
		gcRestoreLastGroupTail(w, gd, ngroups, rem, old, cur, false);
	}

	/**
	 * {@code dst[i] = (if (cmp v[i] s) v[i] s)} -- or the {@code (cmp s v[i])} select
	 * when {@code reversed} -- over whole {@code f64x2} lane groups: the {@code linalg:}
	 * maximum/minimum scalar broadcasts (todo 109 Phase 3), the select sibling of
	 * {@link #gcBroadcastF64}. DOUBLE WIDTH ONLY, like that one: the single-float kernels
	 * walk elements widened through {@code _v_get} / {@code _v_set} so the comparison
	 * sees the FULL double scalar. The save/restore bracket matters here too: a select
	 * over the padding can answer {@code s} (select(0, s, 0 &gt; s)), so the
	 * destination's padding lanes must be put back.
	 */
	static void gcBroadcastSelectF64(WasmWriter w, int gd, int gv, int ngroups, int g, int count, int rem, int old,
			int cur, int sLocal, boolean greater, boolean reversed) {
		gcSaveLastGroup(w, gd, ngroups, count, rem, old, false);
		openGroupLoop(w, ngroups, g);
		get(w, gd);
		get(w, g);
		if (reversed) {
			get(w, sLocal);
			simd(w, Instruction.F64X2_SPLAT);
			groupGet(w, gv, g);
			get(w, sLocal);
			simd(w, Instruction.F64X2_SPLAT);
			groupGet(w, gv, g);
		}
		else {
			groupGet(w, gv, g);
			get(w, sLocal);
			simd(w, Instruction.F64X2_SPLAT);
			groupGet(w, gv, g);
			get(w, sLocal);
			simd(w, Instruction.F64X2_SPLAT);
		}
		simd(w, laneCompare(false, greater));
		simd(w, Instruction.V128_BITSELECT);
		arraySet(w);
		closeGroupLoop(w, g);
		gcRestoreLastGroupTail(w, gd, ngroups, rem, old, cur, false);
	}

	static void gcSaveLastGroup(WasmWriter w, int gd, int ngroups, int count, int rem, int old, boolean single) {
		get(w, count);
		i32Const(w, lanes(single) - 1);
		w.write(Instruction.I32_AND);
		set(w, rem);
		get(w, rem);
		w.write(Instruction.IF, 0x40);
		get(w, gd);
		lastGroupIndex(w, ngroups);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_V128ARR);
		set(w, old);
		w.write(Instruction.END);
	}

	/**
	 * Restores lanes {@code [rem, lanes)} of {@code dst[ngroups-1]} from
	 * {@code oldLocal}, so the group loop's overhang cannot clobber the destination.
	 */
	static void gcRestoreLastGroupTail(WasmWriter w, int gd, int ngroups, int rem, int old, int cur, boolean single) {
		int extract = single ? Instruction.F32X4_EXTRACT_LANE : Instruction.F64X2_EXTRACT_LANE;
		int replace = single ? Instruction.F32X4_REPLACE_LANE : Instruction.F64X2_REPLACE_LANE;
		get(w, rem);
		w.write(Instruction.IF, 0x40);
		get(w, gd);
		lastGroupIndex(w, ngroups);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_V128ARR);
		set(w, cur);
		// lane 0 is always a real element (rem >= 1 inside this guard)
		for (int lane = 1; lane < lanes(single); lane++) {
			i32Const(w, lane);
			get(w, rem);
			w.write(Instruction.I32_GE_U);
			w.write(Instruction.IF, 0x40);
			get(w, cur);
			get(w, old);
			simd(w, extract);
			w.write(lane);
			simd(w, replace);
			w.write(lane);
			set(w, cur);
			w.write(Instruction.END);
		}
		get(w, gd);
		lastGroupIndex(w, ngroups);
		get(w, cur);
		arraySet(w);
		w.write(Instruction.END);
	}

	/** Pushes {@code ngroups - 1}, the index of the last written group. */
	private static void lastGroupIndex(WasmWriter w, int ngroups) {
		get(w, ngroups);
		i32Const(w, 1);
		w.write(Instruction.I32_SUB);
	}

	/**
	 * Horizontal sum over whole lane groups, leaving the total as an <strong>f64</strong>
	 * on the stack. {@code accLocal} must already be splat-zeroed at this width.
	 */
	static void gcSum(WasmWriter w, int gv, int ngroups, int g, int accLocal, int sumLocal, boolean single) {
		openGroupLoop(w, ngroups, g);
		get(w, accLocal);
		groupGet(w, gv, g);
		simd(w, single ? Instruction.F32X4_ADD : Instruction.F64X2_ADD);
		set(w, accLocal);
		closeGroupLoop(w, g);
		finishReduction(w, accLocal, sumLocal, single);
	}

	/**
	 * Lane-wise multiply-accumulate over whole lane groups, leaving
	 * {@code sum(a_i * b_i)} as an <strong>f64</strong> on the stack.
	 */
	static void gcDot(WasmWriter w, int ga, int gb, int ngroups, int g, int accLocal, int sumLocal, boolean single) {
		openGroupLoop(w, ngroups, g);
		get(w, accLocal);
		groupGet(w, ga, g);
		groupGet(w, gb, g);
		simd(w, single ? Instruction.F32X4_MUL : Instruction.F64X2_MUL);
		simd(w, single ? Instruction.F32X4_ADD : Instruction.F64X2_ADD);
		set(w, accLocal);
		closeGroupLoop(w, g);
		finishReduction(w, accLocal, sumLocal, single);
	}

	/** Folds the accumulator's lanes and leaves the total as an f64 on the stack. */
	static void finishReduction(WasmWriter w, int accLocal, int sumLocal, boolean single) {
		if (single) {
			horizontalAddF32(w, accLocal, sumLocal);
			get(w, sumLocal);
			w.write(Instruction.F64_PROMOTE_F32);
		}
		else {
			horizontalAdd(w, accLocal, sumLocal);
			get(w, sumLocal);
		}
	}

	/** {@code array.set $v128arr}: consumes the array, the index and the lane group. */
	static void arraySet(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_V128ARR);
	}

	// --- shared single-element bodies --------------------------------------------

	private static void emitMap2Element(WasmWriter w, int dp, int ap, int bp, int loadOp, int storeOp, int op) {
		get(w, dp);
		get(w, ap);
		w.write(loadOp, 0x00, 0x00);
		get(w, bp);
		w.write(loadOp, 0x00, 0x00);
		w.write(op);
		w.write(storeOp, 0x00, 0x00);
	}

	private static void emitScaleElement(WasmWriter w, int dp, int vp, int sLocal, boolean single) {
		get(w, dp);
		get(w, vp);
		w.write(single ? Instruction.F32_LOAD : Instruction.F64_LOAD, 0x00, 0x00);
		get(w, sLocal);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F32_MUL);
		}
		else {
			w.write(Instruction.F64_MUL);
		}
		w.write(single ? Instruction.F32_STORE : Instruction.F64_STORE, 0x00, 0x00);
	}

}
