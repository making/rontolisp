package am.ik.rontolisp.codegen.wasm;

import am.ik.wasm.Instruction;
import am.ik.wasm.WasmWriter;

/**
 * The vectorizable {@code vec:} kernel loops, expressed once over a <em>linear-memory
 * block</em> addressed by raw i32 locals (a data pointer past the block header, an
 * element count and the element width) rather than over any one backend's Lisp forms.
 *
 * <p>
 * Two backends drive these loops over two different block layouts:
 * {@link NoGcWasmCompiler} inlines them into a defun body over its
 * {@code [count:i32][elements]} block, and {@link WasmVecSimdRuntimeBuilder} emits them
 * inside standalone runtime helper functions over the wasm-GC {@code --simd} block
 * ({@code [count:i32][kind:i32][pad][elements]}, reached through the {@code $farray}
 * struct's {@code i31ref} data field). Both layouts put the elements at a fixed offset,
 * so a data pointer plus a count is all a loop ever needs.
 *
 * <p>
 * Every emitter here takes locals that the CALLER has already materialized (the data
 * pointers, the element count, a splat-zeroed v128 accumulator, an f64 scalar) and emits
 * only the loop, its scalar tail, and -- for the reductions -- the folded result. That
 * split is deliberate: it lets each backend set its locals up in whatever order its own
 * local allocator demands while the loop bodies stay byte-identical between them.
 *
 * <p>
 * Widths: {@code single == false} is the {@code f64x2} path (two f64 lanes per iteration,
 * {@code count >> 1} pairs, a single odd-element tail guard); {@code single == true} is
 * the {@code f32x4} path (four f32 lanes, {@code count >> 2} quads, a scalar remainder
 * LOOP over the last {@code count & 3} elements). Each width computes entirely in its own
 * native precision; the reductions promote to f64 only at the value boundary.
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
