package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the unboxed-fixnum ({@code _fx_*}) helpers behind the integer expression-tree
 * fusion ({@link WasmIntFusionCompiler}). Inside a fused arithmetic/bitwise expression
 * the intermediates stay raw {@code i64} on the wasm stack -- no {@code _int_new} box per
 * operation -- and these helpers carry the two pieces the raw representation cannot
 * express inline without scratch locals: the guarded unbox of a leaf value and the
 * overflow checks of {@code + - * ash}.
 *
 * <p>
 * Every checked helper returns {@code (i64 result, i32 flag)}; a non-zero flag means the
 * fused fast path must bail to its boxed fallback (the leaf is not an i64-tier integer,
 * or the operation left the signed 64-bit range and must promote through the
 * {@code _big_*} runtime). The checks mirror {@link WasmBigIntRuntimeBuilder} exactly --
 * the fallback recomputes the whole tree from the saved leaves, so a bail costs
 * recomputation but never changes a result.
 */
final class WasmFxRuntimeBuilder {

	private WasmFxRuntimeBuilder() {
	}

	// _fx_val(x) -> (i64, i32 ok): an i31's value or a TYPE_BIGNUM's field with ok = 1;
	// any other value (float, ratio, TYPE_BIGINT, non-number) answers ok = 0 instead of
	// trapping like _int_val, so the fused fast path can bail to its boxed fallback.
	static byte[] buildFxValBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		w.write(0); // no extra locals

		b.get(0);
		b.refTestHeap(Type.I31.code());
		b.ifVoid();
		b.get(0);
		b.refCastHeap(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I64_EXTEND_S_I32);
		b.i32c(1);
		w.write(Instruction.RETURN);
		b.end();

		b.get(0);
		b.refTestHeap(WasmLispCompiler.TYPE_BIGNUM);
		b.ifVoid();
		b.get(0);
		b.refCastHeap(WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
		w.writeSignedLeb128(0);
		b.i32c(1);
		w.write(Instruction.RETURN);
		b.end();

		b.i64c(0);
		b.i32c(0);
		b.end();
		return b.toByteArray();
	}

	// _fx_add / _fx_sub(a, b) -> (i64, i32 ovf): the sign-trick overflow test of
	// _big_add/_big_sub (add: ovf when ((a^r)&(b^r)) < 0; sub: when ((a^b)&(a^r)) < 0).
	static byte[] buildFxAddBody(boolean sub) {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 2 = rr (i64)
		w.write(1);
		w.write(1);
		w.write(Type.I64);
		final int rr = 2;

		b.get(0);
		b.get(1);
		w.write(sub ? Instruction.I64_SUB : Instruction.I64_ADD);
		b.set(rr);

		b.get(rr);
		b.get(0);
		b.get(sub ? 1 : rr);
		w.write(Instruction.I64_XOR);
		b.get(sub ? 0 : 1);
		b.get(rr);
		w.write(Instruction.I64_XOR);
		w.write(Instruction.I64_AND);
		b.i64c(0);
		w.write(Instruction.I64_LT_S);
		b.end();
		return b.toByteArray();
	}

	// _fx_mul(a, b) -> (i64, i32 ovf): the _big_mul clz guard -- the product is taken
	// only when the magnitude bit counts guarantee it fits (clz(x ^ x>>63) sums >= 66);
	// the borderline band bails (the boxed fallback may still answer an i64).
	static byte[] buildFxMulBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		w.write(0); // no extra locals

		emitClzMag(b, 0);
		emitClzMag(b, 1);
		w.write(Instruction.I64_ADD);
		b.i64c(66);
		w.write(Instruction.I64_GE_S);
		b.ifVoid();
		b.get(0);
		b.get(1);
		w.write(Instruction.I64_MUL);
		b.i32c(0);
		w.write(Instruction.RETURN);
		b.end();

		b.i64c(0);
		b.i32c(1);
		b.end();
		return b.toByteArray();
	}

	// _fx_ash(v, s) -> (i64, i32 ovf): CL ash on the i64 range. A right shift (s < 0)
	// clamps the count at 63 (the value shifts down to its sign) and cannot overflow; a
	// left shift is taken only when it shifts back to the input, else it bails to the
	// promoting _big_ash fallback.
	static byte[] buildFxAshBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 2 = r (i64)
		w.write(1);
		w.write(1);
		w.write(Type.I64);
		final int r = 2;

		// s == 0 -> (v, 0)
		b.get(1);
		w.write(Instruction.I64_EQZ);
		b.ifVoid();
		b.get(0);
		b.i32c(0);
		w.write(Instruction.RETURN);
		b.end();

		// s < 0 -> v >> min(-s, 63); the comparison runs before the negation so the
		// s = i64.min edge never negates onto itself.
		b.get(1);
		b.i64c(0);
		w.write(Instruction.I64_LT_S);
		b.ifVoid();
		b.get(0);
		b.get(1);
		b.i64c(-63);
		w.write(Instruction.I64_LE_S);
		w.write(Instruction.IF);
		w.write(Type.I64);
		b.i64c(63);
		b.els();
		b.i64c(0);
		b.get(1);
		w.write(Instruction.I64_SUB);
		b.end();
		w.write(Instruction.I64_SHR_S);
		b.i32c(0);
		w.write(Instruction.RETURN);
		b.end();

		// s > 62 -> only v = 0 stays representable
		b.get(1);
		b.i64c(62);
		w.write(Instruction.I64_GT_S);
		b.ifVoid();
		b.get(0);
		w.write(Instruction.I64_EQZ);
		b.ifVoid();
		b.i64c(0);
		b.i32c(0);
		w.write(Instruction.RETURN);
		b.end();
		b.i64c(0);
		b.i32c(1);
		w.write(Instruction.RETURN);
		b.end();

		// r = v << s, kept only when it shifts back to v
		b.get(0);
		b.get(1);
		w.write(Instruction.I64_SHL);
		b.set(r);
		b.get(r);
		b.get(1);
		w.write(Instruction.I64_SHR_S);
		b.get(0);
		w.write(Instruction.I64_EQ);
		b.ifVoid();
		b.get(r);
		b.i32c(0);
		w.write(Instruction.RETURN);
		b.end();

		b.i64c(0);
		b.i32c(1);
		b.end();
		return b.toByteArray();
	}

	// _fx_mod(a, b) -> i64: CL mod (the result takes the divisor's sign). Traps
	// explicitly on b = 0 so the fast path keeps the generic _big_mod trap shape. In
	// range for any i64 operands (|r| < |b|; rem_s(i64.min, -1) is a defined 0).
	static byte[] buildFxModBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 2 = r (i64)
		w.write(1);
		w.write(1);
		w.write(Type.I64);
		final int r = 2;

		emitZeroDivisorTrap(b);
		b.get(0);
		b.get(1);
		w.write(Instruction.I64_REM_S);
		b.set(r);
		b.get(r);
		b.i64c(0);
		w.write(Instruction.I64_NE);
		b.get(r);
		b.get(1);
		w.write(Instruction.I64_XOR);
		b.i64c(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.I32_AND);
		b.ifVoid();
		b.get(r);
		b.get(1);
		w.write(Instruction.I64_ADD);
		b.set(r);
		b.end();
		b.get(r);
		b.end();
		return b.toByteArray();
	}

	// _fx_rem(a, b) -> i64: truncating remainder, the divisor-zero trap made explicit
	// to match the generic _big_divrem shape.
	static byte[] buildFxRemBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		w.write(0); // no extra locals

		emitZeroDivisorTrap(b);
		b.get(0);
		b.get(1);
		w.write(Instruction.I64_REM_S);
		b.end();
		return b.toByteArray();
	}

	// _iv_set(arr, idx, val): the packed integer-vector raw store -- width dispatch via
	// ref.test, then array.set with the i64 value wrapped to i32 (array.set itself
	// truncates further for the i8/i16 widths: the mask-to-width store semantics).
	// Traps (ref.cast) when arr is not a packed integer vector; the compiler only calls
	// it behind a testIntVector guard.
	static byte[] buildIvSetBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		w.write(0); // no extra locals

		b.get(0);
		b.refTestHeap(WasmLispCompiler.TYPE_I8ARR);
		b.ifVoid();
		b.get(0);
		b.refCastHeap(WasmLispCompiler.TYPE_I8ARR);
		b.get(1);
		b.get(2);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_I8ARR);
		w.write(Instruction.RETURN);
		b.end();

		b.get(0);
		b.refTestHeap(WasmLispCompiler.TYPE_I16ARR);
		b.ifVoid();
		b.get(0);
		b.refCastHeap(WasmLispCompiler.TYPE_I16ARR);
		b.get(1);
		b.get(2);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_I16ARR);
		w.write(Instruction.RETURN);
		b.end();

		b.get(0);
		b.refCastHeap(WasmLispCompiler.TYPE_I32ARR);
		b.get(1);
		b.get(2);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_I32ARR);
		b.end();
		return b.toByteArray();
	}

	// Pushes clz(x ^ (x >> 63)) for the i64 in the given local (64 minus the magnitude
	// bit length), the same probe as WasmBigIntRuntimeBuilder.emitClzMag.
	private static void emitClzMag(BodyWriter b, int slot) {
		WasmWriter w = b.w;
		b.get(slot);
		b.get(slot);
		b.i64c(63);
		w.write(Instruction.I64_SHR_S);
		w.write(Instruction.I64_XOR);
		w.write(Instruction.I64_CLZ);
	}

	private static void emitZeroDivisorTrap(BodyWriter b) {
		WasmWriter w = b.w;
		b.get(1);
		w.write(Instruction.I64_EQZ);
		b.ifVoid();
		w.write(Instruction.UNREACHABLE);
		b.end();
	}

	/**
	 * {@code _t_sym () -> eqref}: returns the symbol {@code t}, building it through
	 * {@code _str_build} on the first call and caching it in the module global at
	 * {@code tSymGlobalIndex}. The cached instance carries the same id (the intern offset
	 * of "T") and bytes as a per-site build, so identity and printing are unchanged --
	 * what changes is that a comparison returning true no longer allocates (todo 194
	 * stage 3).
	 */
	static byte[] buildTSymBody(int tOffset, int tLength, int tSymGlobalIndex) {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		w.write(0); // no extra locals
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(tSymGlobalIndex);
		w.write(Instruction.REF_IS_NULL);
		b.ifVoid();
		b.i32c(tOffset);
		b.i32c(tLength);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_STR_BUILD);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(tSymGlobalIndex);
		b.end();
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(tSymGlobalIndex);
		b.end();
		return b.toByteArray();
	}

	private static final class BodyWriter {

		final ByteArrayOutputStream out = new ByteArrayOutputStream();

		final WasmWriter w = new WasmWriter(this.out);

		void get(int slot) {
			this.w.write(Instruction.GET_LOCAL);
			this.w.writeSignedLeb128(slot);
		}

		void set(int slot) {
			this.w.write(Instruction.SET_LOCAL);
			this.w.writeSignedLeb128(slot);
		}

		void i32c(int v) {
			this.w.write(Instruction.I32_CONST);
			this.w.writeSignedLeb128(v);
		}

		void i64c(long v) {
			this.w.write(Instruction.I64_CONST);
			this.w.writeSignedLeb128(v);
		}

		void ifVoid() {
			this.w.write(Instruction.IF, 0x40);
		}

		void els() {
			this.w.write(Instruction.ELSE);
		}

		void end() {
			this.w.write(Instruction.END);
		}

		void refTestHeap(int heapType) {
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			this.w.writeHeapType(heapType);
		}

		void refCastHeap(int heapType) {
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			this.w.writeHeapType(heapType);
		}

		byte[] toByteArray() {
			return this.out.toByteArray();
		}

	}

}
