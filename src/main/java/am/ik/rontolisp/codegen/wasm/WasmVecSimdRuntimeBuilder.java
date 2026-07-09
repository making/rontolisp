package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * The wasm-GC {@code --simd} vector runtime: a bump allocator for the packed float-array
 * arena plus twelve hand-assembled v128 kernels ({@code _vec_add} ..
 * {@code _vec_matvec_into}).
 *
 * <h2>Why standalone functions</h2>
 *
 * {@code v128.load} / {@code v128.store} address LINEAR memory, but a wasm-GC packed
 * float array is a GC object. Under {@code --simd} the {@code $farray} struct therefore
 * keeps its {@code dims} bucket array on the GC heap while its {@code data} field --
 * which is {@code (ref null eq)}, so the TYPE SECTION IS UNCHANGED -- holds an
 * {@code i31ref} pointer into a linear-memory block:
 *
 * <pre>
 *   ptr+0  i32 count    element count (the product of the dims)
 *   ptr+4  i32 kind     0 = double-float (f64), 1 = single-float (f32)
 *   ptr+8  i32 pad      reserved (keeps the element area 16-byte aligned)
 *   ptr+12 i32 pad
 *   ptr+16 elements     count * (kind ? 4 : 8) bytes
 * </pre>
 *
 * The kernels then need v128 / f64 / f32 / i32 locals -- which a defun body cannot have,
 * since {@link WasmLispCompiler} declares every extra local of a compiled body as one
 * {@code (ref null eq)} group. So each kernel is emitted here as a standalone runtime
 * function with hand-written local declarations, taking and returning
 * {@code (ref null eq)} exactly like a compiled Lisp function, and
 * {@link WasmExprCompiler} rewrites a {@code (vec:dot a b)} call site into a
 * {@code call $_vec_dot}. This mirrors the JVM {@code --simd} bridge: only the seven
 * vectorizable kernels (and their five {@code -into} siblings) are intercepted; every
 * other {@code vec:} member keeps running the scalar {@code vec.lisp} defun over the same
 * -- now linear -- packed surface.
 *
 * <h2>The arena</h2>
 *
 * {@code _vec_alloc} is a bump allocator over the single word at
 * {@link WasmLispCompiler#VEC_HEAP_PTR_ADDR}. The first call seeds it with the CURRENT
 * memory size, so the arena starts above every page anything statically reserves -- the
 * program's own string/intern heap, {@code getenv}'s page 3, the fetch scratch in page 4,
 * and (in component mode, where the memory is imported from the shared {@code mem}
 * module) the adapter scratch. It then grows the memory by whole pages. Nothing is ever
 * freed within a run: as on {@code --no-gc}, the high-water mark equals the peak live
 * set, which is why the destination-passing {@code -into} kernels matter here and why a
 * mark/reset of that one word is a complete arena pop.
 *
 * <p>
 * Widths are decided at RUNTIME from the {@code kind} header word (the GC path told them
 * apart with {@code ref.test $f32arr}), so one kernel serves both {@code #d} and
 * {@code #f} vectors; each branch runs the loop from {@link WasmVecLoops} at its own
 * native precision. Mixing widths in one call traps, matching the JVM bridge's hard
 * error.
 */
final class WasmVecSimdRuntimeBuilder {

	private WasmVecSimdRuntimeBuilder() {
	}

	/** Byte offset of the element data inside a packed block (a 16-byte header). */
	static final int DATA_OFF = 16;

	// Function indices, relative to WasmLispCompiler.FUNC_VEC_BASE. Emitted (and only
	// emitted) under --simd, between the string-GC helpers and the user defuns.
	static final int ALLOC = 0;

	static final int ADD = 1;

	static final int SUB = 2;

	static final int MUL = 3;

	static final int SCALE = 4;

	static final int SUM = 5;

	static final int DOT = 6;

	static final int MATVEC = 7;

	static final int ADD_INTO = 8;

	static final int SUB_INTO = 9;

	static final int MUL_INTO = 10;

	static final int SCALE_INTO = 11;

	static final int MATVEC_INTO = 12;

	/**
	 * The number of functions this builder contributes (shifts {@code FUNC_USER_BASE}).
	 */
	static final int FUNC_COUNT = 13;

	/**
	 * Emits the type index of each function, in emission order (for the function
	 * section).
	 */
	static int typeIndexOf(int vecFunc) {
		return switch (vecFunc) {
			// _vec_alloc (i32) -> i32 reuses TYPE_LOOKUP's shape.
			case ALLOC -> WasmLispCompiler.TYPE_LOOKUP;
			// One eq param -> eq.
			case SUM -> WasmLispCompiler.TYPE_CALLABLE_BASE;
			// Three eq params -> eq (the -into kernels).
			case ADD_INTO, SUB_INTO, MUL_INTO, SCALE_INTO, MATVEC_INTO -> WasmLispCompiler.TYPE_CALLABLE_BASE + 2;
			// Two eq params -> eq.
			default -> WasmLispCompiler.TYPE_CALLABLE_BASE + 1;
		};
	}

	/** Builds the body of the given helper, in {@code vecFunc} index order. */
	static byte[] build(int vecFunc, int vecBase) {
		return switch (vecFunc) {
			case ALLOC -> buildAllocBody();
			case ADD -> buildElementwise(Instruction.F64X2_ADD, Instruction.F64_ADD, false, vecBase);
			case SUB -> buildElementwise(Instruction.F64X2_SUB, Instruction.F64_SUB, false, vecBase);
			case MUL -> buildElementwise(Instruction.F64X2_MUL, Instruction.F64_MUL, false, vecBase);
			case SCALE -> buildScale(false, vecBase);
			case SUM -> buildSum();
			case DOT -> buildDot();
			case MATVEC -> buildMatvec(false, vecBase);
			case ADD_INTO -> buildElementwise(Instruction.F64X2_ADD, Instruction.F64_ADD, true, vecBase);
			case SUB_INTO -> buildElementwise(Instruction.F64X2_SUB, Instruction.F64_SUB, true, vecBase);
			case MUL_INTO -> buildElementwise(Instruction.F64X2_MUL, Instruction.F64_MUL, true, vecBase);
			case SCALE_INTO -> buildScale(true, vecBase);
			case MATVEC_INTO -> buildMatvec(true, vecBase);
			default -> throw new IllegalArgumentException("no vec: simd helper " + vecFunc);
		};
	}

	// --- _vec_alloc ---------------------------------------------------------------

	// _vec_alloc(size i32) -> i32: bump the arena at VEC_HEAP_PTR_ADDR by a 16-byte
	// -rounded size, growing linear memory by whole pages when the new top exceeds the
	// current size. A zero bump pointer means "not yet seeded": start the arena at the
	// current memory size (page-aligned, so every block is 16-byte aligned and the
	// elements at ptr+16 are too). Params: 0 = size. Locals: 1 = hp, 2 = end.
	private static byte[] buildAllocBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		// hp = load(VEC_HEAP_PTR_ADDR); if (hp == 0) hp = memory.size() << 16
		i32Const(w, WasmLispCompiler.VEC_HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		WasmVecLoops.set(w, 1);
		WasmVecLoops.get(w, 1);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		memoryBytes(w);
		WasmVecLoops.set(w, 1);
		w.write(Instruction.END);
		// end = hp + ((size + 15) & -16)
		WasmVecLoops.get(w, 1);
		WasmVecLoops.get(w, 0);
		i32Const(w, 15);
		w.write(Instruction.I32_ADD);
		i32Const(w, -16);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_ADD);
		WasmVecLoops.set(w, 2);
		// if (end > memory.size() << 16) { if (memory.grow((end - bytes + 65535) >> 16)
		// == -1) unreachable }
		WasmVecLoops.get(w, 2);
		memoryBytes(w);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.IF, 0x40);
		WasmVecLoops.get(w, 2);
		memoryBytes(w);
		w.write(Instruction.I32_SUB);
		i32Const(w, 0xffff);
		w.write(Instruction.I32_ADD);
		i32Const(w, 16);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.GROW_MEMORY, 0x00);
		i32Const(w, -1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// store(VEC_HEAP_PTR_ADDR, end); return hp
		i32Const(w, WasmLispCompiler.VEC_HEAP_PTR_ADDR);
		WasmVecLoops.get(w, 2);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		WasmVecLoops.get(w, 1);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 2, 0, 0, 0);
	}

	// Pushes the current memory size in BYTES (memory.size() << 16).
	private static void memoryBytes(WasmWriter w) {
		w.write(Instruction.CURRENT_MEMORY, 0x00);
		i32Const(w, 16);
		w.write(Instruction.I32_SHL);
	}

	// --- element-wise kernels (add / sub / mul, allocating and -into) ---------------

	// (vec:add a b) / -into: dst[i] = op(a[i], b[i]).
	//
	// Plain: params 0 = a, 1 = b. -into: params 0 = out, 1 = a, 2 = b (CL's map-into
	// order), and the destination block is the caller's -- so a loop over an -into kernel
	// never advances the arena. Aliasing `out` with an operand is intentional and safe
	// (element i depends only on element i).
	//
	// i32 locals: ap, bp, dst, count, kind, apd, bpd, dpd, rem, trem.
	private static byte[] buildElementwise(int simdOp, int scalarOp, boolean into, int vecBase) {
		int params = into ? 3 : 2;
		int a = into ? 1 : 0;
		int bArg = into ? 2 : 1;
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int ap = params, bp = params + 1, dst = params + 2, count = params + 3, kind = params + 4;
		int apd = params + 5, bpd = params + 6, dpd = params + 7, rem = params + 8, trem = params + 9;

		blockPtr(w, a, ap);
		blockPtr(w, bArg, bp);
		loadHeader(w, ap, count, kind);
		requireSameKind(w, kind, bp);
		if (into) {
			blockPtr(w, 0, dst);
			requireSameKind(w, kind, dst);
		}
		else {
			allocBlock(w, count, kind, dst, vecBase);
		}
		dataPtr(w, ap, apd);
		dataPtr(w, bp, bpd);
		dataPtr(w, dst, dpd);
		// if (kind) f32x4 else f64x2
		WasmVecLoops.get(w, kind);
		w.write(Instruction.IF, 0x40);
		WasmVecLoops.simdMap2(w, dpd, apd, bpd, count, rem, trem, true, simdOp, scalarOp);
		w.write(Instruction.ELSE);
		WasmVecLoops.simdMap2(w, dpd, apd, bpd, count, rem, trem, false, simdOp, scalarOp);
		w.write(Instruction.END);
		if (into) {
			WasmVecLoops.get(w, 0); // the destination farray, unchanged
		}
		else {
			makeFarray(w, count, dst);
		}
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 10, 0, 0, 0);
	}

	// --- scale ---------------------------------------------------------------------

	// (vec:scale v s) / -into: dst[i] = v[i] * s. i32 locals: vp, dst, count, kind, vpd,
	// dpd, rem, trem; then one f64 local for the unboxed scalar and one eq scratch for
	// the unboxing type test.
	private static byte[] buildScale(boolean into, int vecBase) {
		int params = into ? 3 : 2;
		int v = into ? 1 : 0;
		int sArg = into ? 2 : 1;
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int vp = params, dst = params + 1, count = params + 2, kind = params + 3;
		int vpd = params + 4, dpd = params + 5, rem = params + 6, trem = params + 7;
		int s = params + 8; // f64
		int box = params + 9; // (ref null eq) scratch

		blockPtr(w, v, vp);
		loadHeader(w, vp, count, kind);
		WasmVecLoops.get(w, sArg);
		unboxF64(w, box);
		WasmVecLoops.set(w, s);
		if (into) {
			blockPtr(w, 0, dst);
			requireSameKind(w, kind, dst);
		}
		else {
			allocBlock(w, count, kind, dst, vecBase);
		}
		dataPtr(w, vp, vpd);
		dataPtr(w, dst, dpd);
		WasmVecLoops.get(w, kind);
		w.write(Instruction.IF, 0x40);
		WasmVecLoops.simdScale(w, dpd, vpd, count, rem, trem, s, true);
		w.write(Instruction.ELSE);
		WasmVecLoops.simdScale(w, dpd, vpd, count, rem, trem, s, false);
		w.write(Instruction.END);
		if (into) {
			WasmVecLoops.get(w, 0);
		}
		else {
			makeFarray(w, count, dst);
		}
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 8, 1, 0, 0, 1);
	}

	// --- reductions ----------------------------------------------------------------

	// (vec:sum v) -> a boxed TYPE_FLOAT. i32: vp, count, kind, vpd, rem, trem; f64: sum;
	// f32: sumF; v128: acc.
	private static byte[] buildSum() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int vp = 1, count = 2, kind = 3, vpd = 4, rem = 5, trem = 6;
		int sum = 7; // f64
		int sumF = 8; // f32
		int acc = 9; // v128

		blockPtr(w, 0, vp);
		loadHeader(w, vp, count, kind);
		dataPtr(w, vp, vpd);
		WasmVecLoops.get(w, kind);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.splatZeroF32(w, acc);
		WasmVecLoops.simdSum(w, vpd, count, rem, trem, acc, sumF, true);
		w.write(Instruction.ELSE);
		WasmVecLoops.splatZero(w, acc);
		WasmVecLoops.simdSum(w, vpd, count, rem, trem, acc, sum, false);
		w.write(Instruction.END);
		boxFloat(w);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 6, 1, 1, 1);
	}

	// (vec:dot a b) -> a boxed TYPE_FLOAT. i32: ap, bp, count, kind, apd, bpd, rem, trem;
	// f64: sum; f32: sumF; v128: acc.
	private static byte[] buildDot() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int ap = 2, bp = 3, count = 4, kind = 5, apd = 6, bpd = 7, rem = 8, trem = 9;
		int sum = 10; // f64
		int sumF = 11; // f32
		int acc = 12; // v128

		blockPtr(w, 0, ap);
		blockPtr(w, 1, bp);
		loadHeader(w, ap, count, kind);
		requireSameKind(w, kind, bp);
		dataPtr(w, ap, apd);
		dataPtr(w, bp, bpd);
		WasmVecLoops.get(w, kind);
		w.write(Instruction.IF, Type.F64.code());
		WasmVecLoops.splatZeroF32(w, acc);
		WasmVecLoops.simdDot(w, apd, bpd, count, rem, trem, acc, sumF, true);
		w.write(Instruction.ELSE);
		WasmVecLoops.splatZero(w, acc);
		WasmVecLoops.simdDot(w, apd, bpd, count, rem, trem, acc, sum, false);
		w.write(Instruction.END);
		boxFloat(w);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 8, 1, 1, 1);
	}

	// --- matvec (GEMV) --------------------------------------------------------------

	// (vec:matvec w x) / -into: a rank-2 matrix times a rank-1 vector. Unlike --no-gc
	// (whose packed block is rank-1 only), the $farray struct carries its dims, so the
	// rows are readable: r[row] = dot(W row, x) run once per row by the SAME simdDot
	// loop, which amortizes the per-call setup over d rows.
	//
	// Plain: params 0 = W, 1 = x. -into: 0 = out, 1 = W, 2 = x. `out` may NOT alias `x`
	// (each output element folds over ALL of x), so the -into form traps on ref.eq --
	// the same guard the vec.lisp defun and the JVM/interpreter kernels raise as an
	// error.
	//
	// i32: wp, xp, dst, d, n, kind, wrow, xd, dpd, row, rem, trem, cnt, ap, bp;
	// f64: res, sum; f32: sumF; v128: acc; eq: dims.
	private static byte[] buildMatvec(boolean into, int vecBase) {
		int params = into ? 3 : 2;
		int mat = into ? 1 : 0;
		int vec = into ? 2 : 1;
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int wp = params, xp = params + 1, dst = params + 2, d = params + 3, n = params + 4, kind = params + 5;
		int wrow = params + 6, xd = params + 7, dpd = params + 8, row = params + 9;
		int rem = params + 10, trem = params + 11, cnt = params + 12, ap = params + 13, bp = params + 14;
		int res = params + 15; // f64
		int sum = params + 16; // f64
		int sumF = params + 17; // f32
		int acc = params + 18; // v128
		int dims = params + 19; // (ref null eq)

		if (into) {
			// out and x must not alias.
			WasmVecLoops.get(w, 0);
			WasmVecLoops.get(w, vec);
			w.write(Instruction.REF_EQ);
			w.write(Instruction.IF, 0x40);
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
		}
		blockPtr(w, mat, wp);
		blockPtr(w, vec, xp);
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
		WasmVecLoops.get(w, wp);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		WasmVecLoops.set(w, kind);
		requireSameKind(w, kind, xp);
		if (into) {
			blockPtr(w, 0, dst);
			requireSameKind(w, kind, dst);
		}
		else {
			allocBlock(w, d, kind, dst, vecBase);
		}
		dataPtr(w, xp, xd);
		dataPtr(w, wp, wrow);
		dataPtr(w, dst, dpd);
		WasmVecLoops.get(w, kind);
		w.write(Instruction.IF, 0x40);
		emitMatvecRows(w, d, n, wrow, xd, dpd, row, rem, trem, cnt, ap, bp, res, sumF, acc, true);
		w.write(Instruction.ELSE);
		emitMatvecRows(w, d, n, wrow, xd, dpd, row, rem, trem, cnt, ap, bp, res, sum, acc, false);
		w.write(Instruction.END);
		if (into) {
			WasmVecLoops.get(w, 0);
		}
		else {
			makeFarray(w, d, dst);
		}
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), 15, 2, 1, 1, 1);
	}

	// for (row = 0; row < d; row++) dst[row] = dot(W + row*n, x, n)
	private static void emitMatvecRows(WasmWriter w, int d, int n, int wrow, int xd, int dpd, int row, int rem,
			int trem, int cnt, int ap, int bp, int res, int sumLocal, int acc, boolean single) {
		int stride = WasmVecLoops.stride(single);
		i32Const(w, 0);
		WasmVecLoops.set(w, row);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		WasmVecLoops.get(w, row);
		WasmVecLoops.get(w, d);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// the dot loop consumes ap/bp/cnt, so reset them from the row cursors
		WasmVecLoops.get(w, wrow);
		WasmVecLoops.set(w, ap);
		WasmVecLoops.get(w, xd);
		WasmVecLoops.set(w, bp);
		WasmVecLoops.get(w, n);
		WasmVecLoops.set(w, cnt);
		WasmVecLoops.splatZero(w, acc, single);
		WasmVecLoops.simdDot(w, ap, bp, cnt, rem, trem, acc, sumLocal, single);
		WasmVecLoops.set(w, res);
		// dst[row] = res
		WasmVecLoops.get(w, dpd);
		WasmVecLoops.get(w, res);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F32_STORE, 0x00, 0x00);
		}
		else {
			w.write(Instruction.F64_STORE, 0x00, 0x00);
		}
		// wrow += n * width; dpd += width; row++
		WasmVecLoops.get(w, wrow);
		WasmVecLoops.get(w, n);
		i32Const(w, single ? 2 : 3);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
		WasmVecLoops.set(w, wrow);
		WasmVecLoops.advancePtr(w, dpd, stride);
		WasmVecLoops.get(w, row);
		i32Const(w, 1);
		w.write(Instruction.I32_ADD);
		WasmVecLoops.set(w, row);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
	}

	// --- shared emit helpers --------------------------------------------------------

	private static void i32Const(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST).writeSignedLeb128(value);
	}

	// outLocal(i32) = the linear-memory block pointer of the $farray in argLocal.
	// The data field is an i31ref; i31.get_u zero-extends its 31 payload bits, so any
	// address below 2 GiB round-trips exactly.
	private static void blockPtr(WasmWriter w, int argLocal, int outLocal) {
		farrayField(w, argLocal, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_U);
		WasmVecLoops.set(w, outLocal);
	}

	// Pushes field `field` of the $farray held (as eq) in argLocal.
	private static void farrayField(WasmWriter w, int argLocal, int field) {
		WasmVecLoops.get(w, argLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FARRAY);
		w.writeSignedLeb128(field);
	}

	private static void castBuckets(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	// outLocal(i32) = i31.get_s(buckets[index]) -- a dimension size.
	private static void bucketI32(WasmWriter w, int bucketsLocal, int index, int outLocal) {
		WasmVecLoops.get(w, bucketsLocal);
		castBuckets(w);
		i32Const(w, index);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		WasmVecLoops.set(w, outLocal);
	}

	// countLocal = block[0]; kindLocal = block[4].
	private static void loadHeader(WasmWriter w, int blockLocal, int countLocal, int kindLocal) {
		WasmVecLoops.get(w, blockLocal);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		WasmVecLoops.set(w, countLocal);
		WasmVecLoops.get(w, blockLocal);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		WasmVecLoops.set(w, kindLocal);
	}

	// Traps unless the block in otherLocal has the same element width as kindLocal.
	// Mixing a #d and a #f operand is a hard error on every backend.
	private static void requireSameKind(WasmWriter w, int kindLocal, int otherLocal) {
		WasmVecLoops.get(w, kindLocal);
		WasmVecLoops.get(w, otherLocal);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
	}

	// dstLocal = _vec_alloc(16 + (count << (3 - kind))); write the count/kind header.
	// The shift is computed from kind (0 -> 3 = f64 stride, 1 -> 2 = f32 stride), so the
	// size needs no branch.
	private static void allocBlock(WasmWriter w, int countLocal, int kindLocal, int dstLocal, int vecBase) {
		i32Const(w, DATA_OFF);
		WasmVecLoops.get(w, countLocal);
		i32Const(w, 3);
		WasmVecLoops.get(w, kindLocal);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL).writeSignedLeb128(vecBase + ALLOC);
		WasmVecLoops.set(w, dstLocal);
		WasmVecLoops.get(w, dstLocal);
		WasmVecLoops.get(w, countLocal);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		WasmVecLoops.get(w, dstLocal);
		WasmVecLoops.get(w, kindLocal);
		w.write(Instruction.I32_STORE, 0x02, 0x04);
	}

	// outLocal = blockLocal + DATA_OFF (the element data pointer).
	private static void dataPtr(WasmWriter w, int blockLocal, int outLocal) {
		WasmVecLoops.get(w, blockLocal);
		i32Const(w, DATA_OFF);
		w.write(Instruction.I32_ADD);
		WasmVecLoops.set(w, outLocal);
	}

	// Pushes struct.new $farray(array.new $hash_buckets(i31(count), 1), i31(ptr)) -- a
	// fresh rank-1 packed array over the block at ptrLocal. (The vec: kernels are rank-1
	// producers; matvec's result is rank-1 too. This matches the JVM bridge, whose result
	// header is always [1.0, n, ...].)
	private static void makeFarray(WasmWriter w, int countLocal, int ptrLocal) {
		WasmVecLoops.get(w, countLocal);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		i32Const(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		WasmVecLoops.get(w, ptrLocal);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FARRAY);
	}

	// Boxes the f64 on the stack into a TYPE_FLOAT struct.
	private static void boxFloat(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
	}

	// Converts the (ref null eq) on the stack to an f64, mirroring
	// WasmEmitHelper.castFloatGetF64 (i31 integer / ratio / float struct). tmpLocal is a
	// (ref null eq) scratch.
	private static void unboxF64(WasmWriter w, int tmpLocal) {
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
		w.write(Instruction.CALL).writeSignedLeb128(WasmLispCompiler.FUNC_RAT_NUM);
		w.write(Instruction.F64_CONVERT_S_I32);
		WasmVecLoops.get(w, tmpLocal);
		w.write(Instruction.CALL).writeSignedLeb128(WasmLispCompiler.FUNC_RAT_DEN);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.ELSE);
		WasmVecLoops.get(w, tmpLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	private static byte[] withLocals(byte[] body, int i32Count, int f64Count, int f32Count, int v128Count) {
		return withLocals(body, i32Count, f64Count, f32Count, v128Count, 0);
	}

	// Prepends the local declaration groups, in the fixed order the index arithmetic
	// above assumes: i32, f64, f32, v128, (ref null eq).
	private static byte[] withLocals(byte[] body, int i32Count, int f64Count, int f32Count, int v128Count,
			int eqCount) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		int groups = (i32Count > 0 ? 1 : 0) + (f64Count > 0 ? 1 : 0) + (f32Count > 0 ? 1 : 0) + (v128Count > 0 ? 1 : 0)
				+ (eqCount > 0 ? 1 : 0);
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
			w.write(Type.REFNULL.code());
			w.writeHeapType(Type.EQ.code());
		}
		w.write((Object) body);
		return out.toByteArray();
	}

}
