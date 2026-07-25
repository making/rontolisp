package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the limb (arbitrary-precision) exact-integer runtime. An exact integer outside
 * the signed 64-bit range is a {@code TYPE_BIGINT} struct holding a {@code TYPE_LIMBS}
 * array of two's-complement little-endian 32-bit limbs, canonicalized to the minimal
 * length (at least 3 limbs -- anything shorter normalizes down through {@code _int_new}
 * to the boxed-i64 / i31 tiers, so every existing {@code ref.eq}/{@code eql} fast path
 * stays valid and a limb value only ever compares against another limb value).
 *
 * <p>
 * Two layers: the {@code _limb_*} helpers operate on raw limb arrays (passed as
 * {@code (ref null eq)}, cast internally); the {@code _big_*} helpers take Lisp values at
 * any integer tier, keep an i64 fast path first (promoting on overflow instead of
 * wrapping), and normalize every result to the narrowest tier. 32-bit limbs (not 64) so a
 * limb product fits an i64 -- core wasm has no widening 64-bit multiply -- and
 * two's-complement (not sign-magnitude) so the CL bitwise operators over negative
 * operands are plain limb-wise ops. Division is binary long division on magnitudes
 * (shift-compare-subtract), O(bits x limbs), plenty for the crypto-sized integers this
 * tier exists for. The decimal printer recurses on divmod 10^9 so no fixed-size buffer
 * limits the digit count.
 */
final class WasmBigIntRuntimeBuilder {

	private WasmBigIntRuntimeBuilder() {
	}

	// _limb_of(x) -> (ref null eq): the limb array of any exact integer. A TYPE_BIGINT
	// answers its own (shared, treat as read-only) array; an i31 or TYPE_BIGNUM widens
	// through _int_val into a fresh 2-limb array.
	static byte[] buildLimbOfBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 1 = v (i64)
		w.write(1);
		w.write(1);
		w.write(Type.I64);

		b.get(0);
		b.refTest(WasmLispCompiler.TYPE_BIGINT);
		b.ifVoid();
		b.get(0);
		b.refCast(WasmLispCompiler.TYPE_BIGINT);
		b.structGet(WasmLispCompiler.TYPE_BIGINT, 0);
		w.write(Instruction.RETURN);
		b.end();

		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(1);
		// [lo, hi]
		b.get(1);
		w.write(Instruction.I32_WRAP_I64);
		b.get(1);
		b.i64c(32);
		w.write(Instruction.I64_SHR_S);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_FIXED);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_LIMBS);
		w.writeSignedLeb128(2);

		b.end();
		return b.toByteArray();
	}

	// _limb_new(arr) -> (ref null eq): canonicalize a limb array to the narrowest tier.
	// Strips redundant sign-extension top limbs; a result of one or two limbs becomes an
	// i64 through _int_new (which demotes to i31 when possible), three or more becomes a
	// TYPE_BIGINT (re-sliced to the canonical length when limbs were stripped).
	static byte[] buildLimbNewBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 1 = a (ref null $limbs), 2 = len, 3 = n (i32), 4 = out (ref null
		// $limbs)
		w.write(3);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		w.write(2);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		final int a = 1, len = 2, n = 3, out = 4;

		b.get(0);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(a);
		b.get(a);
		b.arrayLen();
		b.tee(len);
		b.set(n);

		// while (n > 1 && a[n-1] == a[n-2] >> 31) n--
		b.block();
		b.loop();
		b.get(n);
		b.i32c(1);
		w.write(Instruction.I32_LE_S);
		b.brIf(1);
		b.get(a);
		b.get(n);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.arrayGet();
		b.get(a);
		b.get(n);
		b.i32c(2);
		w.write(Instruction.I32_SUB);
		b.arrayGet();
		b.i32c(31);
		w.write(Instruction.I32_SHR_S);
		w.write(Instruction.I32_NE);
		b.brIf(1);
		b.get(n);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(n);
		b.br(0);
		b.end();
		b.end();

		// n <= 2: build the i64 and normalize through _int_new
		b.get(n);
		b.i32c(2);
		w.write(Instruction.I32_LE_S);
		b.ifVoid();
		b.get(n);
		b.i32c(1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.write(Type.I64);
		b.get(a);
		b.i32c(0);
		b.arrayGet();
		w.write(Instruction.I64_EXTEND_S_I32);
		w.write(Instruction.ELSE);
		b.get(a);
		b.i32c(0);
		b.arrayGet();
		w.write(Instruction.I64_EXTEND_U_I32);
		b.get(a);
		b.i32c(1);
		b.arrayGet();
		w.write(Instruction.I64_EXTEND_S_I32);
		b.i64c(32);
		w.write(Instruction.I64_SHL);
		w.write(Instruction.I64_OR);
		b.end();
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.RETURN);
		b.end();

		// n == len: wrap the array as-is
		b.get(n);
		b.get(len);
		w.write(Instruction.I32_EQ);
		b.ifVoid();
		b.get(a);
		b.structNew(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.RETURN);
		b.end();

		// re-slice to n limbs
		b.get(n);
		b.arrayNewDefault();
		b.set(out);
		b.get(out);
		b.i32c(0);
		b.get(a);
		b.i32c(0);
		b.get(n);
		b.arrayCopy();
		b.get(out);
		b.structNew(WasmLispCompiler.TYPE_BIGINT);

		b.end();
		return b.toByteArray();
	}

	// _limb_get(arr, i) -> i32: limb i, or the sign word for an index past the top.
	static byte[] buildLimbGetBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 2 = a (ref null $limbs), 3 = len (i32)
		w.write(2);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		w.write(1);
		w.write(Type.I32);
		final int a = 2, len = 3;

		b.get(0);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(a);
		b.get(a);
		b.arrayLen();
		b.set(len);

		b.get(1);
		b.get(len);
		w.write(Instruction.I32_LT_S);
		b.ifVoid();
		b.get(a);
		b.get(1);
		b.arrayGet();
		w.write(Instruction.RETURN);
		b.end();

		b.get(a);
		b.get(len);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.arrayGet();
		b.i32c(31);
		w.write(Instruction.I32_SHR_S);

		b.end();
		return b.toByteArray();
	}

	// _limb_addsub(a, b, sub) -> array: two's-complement a + b (sub = 0) or a - b
	// (sub = 1, as a + ~b + 1), into a fresh max(len)+1-limb array (never normalized --
	// callers finish through _limb_new).
	static byte[] buildLimbAddsubBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// params: 0=a, 1=b, 2=sub. locals: 3=n, 4=i (i32), 5=carry, 6=t (i64),
		// 7=out (ref null $limbs)
		w.write(3);
		w.write(2);
		w.write(Type.I32);
		w.write(2);
		w.write(Type.I64);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		final int n = 3, i = 4, carry = 5, t = 6, out = 7;

		// n = max(len(a), len(b)) + 1, via the i temp
		b.get(0);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.arrayLen();
		b.set(n);
		b.get(1);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.arrayLen();
		b.set(i);
		b.get(i);
		b.get(n);
		w.write(Instruction.I32_GT_S);
		b.ifVoid();
		b.get(i);
		b.set(n);
		b.end();
		b.get(n);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(n);

		b.get(n);
		b.arrayNewDefault();
		b.set(out);

		// carry = sub ? 1 : 0
		b.i64c(1);
		b.i64c(0);
		b.get(2);
		w.write(Instruction.SELECT);
		b.set(carry);

		b.i32c(0);
		b.set(i);
		b.block();
		b.loop();
		b.get(i);
		b.get(n);
		w.write(Instruction.I32_GE_S);
		b.brIf(1);
		// t = extU(get(a,i)) + extU(get(b,i) ^ (sub ? -1 : 0)) + carry
		b.get(0);
		b.get(i);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		w.write(Instruction.I64_EXTEND_U_I32);
		b.get(1);
		b.get(i);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		b.i32c(-1);
		b.i32c(0);
		b.get(2);
		w.write(Instruction.SELECT);
		w.write(Instruction.I32_XOR);
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.I64_ADD);
		b.get(carry);
		w.write(Instruction.I64_ADD);
		b.set(t);
		// out[i] = (i32) t; carry = t >>u 32
		b.get(out);
		b.get(i);
		b.get(t);
		w.write(Instruction.I32_WRAP_I64);
		b.arraySet();
		b.get(t);
		b.i64c(32);
		w.write(Instruction.I64_SHR_U);
		b.set(carry);
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(i);
		b.br(0);
		b.end();
		b.end();

		b.get(out);
		b.end();
		return b.toByteArray();
	}

	// _limb_neg(arr) -> array: 0 - arr, via _limb_addsub with a one-limb zero.
	static byte[] buildLimbNegBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		w.write(0);
		b.i32c(0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_FIXED);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_LIMBS);
		w.writeSignedLeb128(1);
		b.get(0);
		b.i32c(1);
		b.call(WasmLispCompiler.FUNC_LIMB_ADDSUB);
		b.end();
		return b.toByteArray();
	}

	// _limb_copy(arr) -> array: fresh mutable copy (the in-place divmod printer needs
	// one; a TYPE_BIGINT's own array must never be mutated).
	static byte[] buildLimbCopyBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 1 = a (ref null $limbs), 2 = len (i32), 3 = out (ref null $limbs)
		w.write(3);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		final int a = 1, len = 2, out = 3;

		b.get(0);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(a);
		b.get(a);
		b.arrayLen();
		b.set(len);
		b.get(len);
		b.arrayNewDefault();
		b.set(out);
		b.get(out);
		b.i32c(0);
		b.get(a);
		b.i32c(0);
		b.get(len);
		b.arrayCopy();
		b.get(out);
		b.end();
		return b.toByteArray();
	}

	// _limb_mul(a, b) -> array: schoolbook unsigned product over 32-bit limbs (each
	// partial product fits an i64), then the two's-complement sign corrections --
	// subtract b << 32m when a is negative and a << 32n when b is negative -- so the
	// m+n-limb result is the exact signed product.
	static byte[] buildLimbMulBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// params: 0=a, 1=b. locals: 2=aT, 3=bT (ref null $limbs), 4=m, 5=n, 6=i, 7=j
		// (i32), 8=ai, 9=carry, 10=t (i64), 11=out (ref null $limbs)
		w.write(4);
		w.write(2);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		w.write(4);
		w.write(Type.I32);
		w.write(3);
		w.write(Type.I64);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		final int aT = 2, bT = 3, m = 4, n = 5, i = 6, j = 7, ai = 8, carry = 9, t = 10, out = 11;

		b.get(0);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(aT);
		b.get(1);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(bT);
		b.get(aT);
		b.arrayLen();
		b.set(m);
		b.get(bT);
		b.arrayLen();
		b.set(n);
		b.get(m);
		b.get(n);
		w.write(Instruction.I32_ADD);
		b.arrayNewDefault();
		b.set(out);

		// outer i over a's limbs
		b.i32c(0);
		b.set(i);
		b.block();
		b.loop();
		b.get(i);
		b.get(m);
		w.write(Instruction.I32_GE_S);
		b.brIf(1);
		b.get(aT);
		b.get(i);
		b.arrayGet();
		w.write(Instruction.I64_EXTEND_U_I32);
		b.set(ai);
		b.i64c(0);
		b.set(carry);
		b.i32c(0);
		b.set(j);
		// inner j over b's limbs
		b.block();
		b.loop();
		b.get(j);
		b.get(n);
		w.write(Instruction.I32_GE_S);
		b.brIf(1);
		// t = extU(out[i+j]) + ai * extU(bT[j]) + carry
		b.get(out);
		b.get(i);
		b.get(j);
		w.write(Instruction.I32_ADD);
		b.arrayGet();
		w.write(Instruction.I64_EXTEND_U_I32);
		b.get(ai);
		b.get(bT);
		b.get(j);
		b.arrayGet();
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.I64_MUL);
		w.write(Instruction.I64_ADD);
		b.get(carry);
		w.write(Instruction.I64_ADD);
		b.set(t);
		b.get(out);
		b.get(i);
		b.get(j);
		w.write(Instruction.I32_ADD);
		b.get(t);
		w.write(Instruction.I32_WRAP_I64);
		b.arraySet();
		b.get(t);
		b.i64c(32);
		w.write(Instruction.I64_SHR_U);
		b.set(carry);
		b.get(j);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(j);
		b.br(0);
		b.end();
		b.end();
		// out[i+n] = (i32) carry
		b.get(out);
		b.get(i);
		b.get(n);
		w.write(Instruction.I32_ADD);
		b.get(carry);
		w.write(Instruction.I32_WRAP_I64);
		b.arraySet();
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(i);
		b.br(0);
		b.end();
		b.end();

		// if a < 0: out[m .. m+n-1] -= b
		b.get(aT);
		b.get(m);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.arrayGet();
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.ifVoid();
		emitMulSignCorrection(b, out, m, bT, n, j, t, carry);
		b.end();

		// if b < 0: out[n .. n+m-1] -= a
		b.get(bT);
		b.get(n);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.arrayGet();
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.ifVoid();
		emitMulSignCorrection(b, out, n, aT, m, j, t, carry);
		b.end();

		b.get(out);
		b.end();
		return b.toByteArray();
	}

	// out[base .. base+count-1] -= sub's limbs, with borrow, wrapping past the top
	// (which is exactly the mod-2^(32(m+n)) arithmetic the sign correction wants).
	private static void emitMulSignCorrection(BodyWriter b, int out, int base, int subArr, int count, int j, int t,
			int borrow) {
		WasmWriter w = b.w;
		b.i64c(0);
		b.set(borrow);
		b.i32c(0);
		b.set(j);
		b.block();
		b.loop();
		b.get(j);
		b.get(count);
		w.write(Instruction.I32_GE_S);
		b.brIf(1);
		// t = extU(out[base+j]) - extU(sub[j]) - borrow
		b.get(out);
		b.get(base);
		b.get(j);
		w.write(Instruction.I32_ADD);
		b.arrayGet();
		w.write(Instruction.I64_EXTEND_U_I32);
		b.get(subArr);
		b.get(j);
		b.arrayGet();
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.I64_SUB);
		b.get(borrow);
		w.write(Instruction.I64_SUB);
		b.set(t);
		b.get(out);
		b.get(base);
		b.get(j);
		w.write(Instruction.I32_ADD);
		b.get(t);
		w.write(Instruction.I32_WRAP_I64);
		b.arraySet();
		// borrow = t < 0
		b.get(t);
		b.i64c(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.I64_EXTEND_U_I32);
		b.set(borrow);
		b.get(j);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(j);
		b.br(0);
		b.end();
		b.end();
	}

	// _limb_cmp(a, b) -> -1/0/1: signed comparison. Different sign words decide
	// directly; same-sign values compare lexicographically unsigned from the top with
	// sign-extension past each array's length (a two's-complement property).
	static byte[] buildLimbCmpBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// params 0=a, 1=b. locals: 2=sa, 3=sb, 4=i, 5=ua, 6=ub (i32)
		w.write(1);
		w.write(5);
		w.write(Type.I32);
		final int sa = 2, sb = 3, i = 4, ua = 5, ub = 6;

		// sa/sb: sign words via _limb_get at a huge index
		b.get(0);
		b.i32c(Integer.MAX_VALUE);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		b.set(sa);
		b.get(1);
		b.i32c(Integer.MAX_VALUE);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		b.set(sb);
		b.get(sa);
		b.get(sb);
		w.write(Instruction.I32_NE);
		b.ifVoid();
		b.get(sa);
		b.get(sb);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.RETURN);
		b.end();

		// i = max(len a, len b) - 1, via the ua/ub temps
		b.get(0);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.arrayLen();
		b.set(ua);
		b.get(1);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.arrayLen();
		b.set(ub);
		b.get(ub);
		b.get(ua);
		w.write(Instruction.I32_GT_S);
		b.ifVoid();
		b.get(ub);
		b.set(ua);
		b.end();
		b.get(ua);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(i);

		b.block();
		b.loop();
		b.get(i);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.brIf(1);
		b.get(0);
		b.get(i);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		b.set(ua);
		b.get(1);
		b.get(i);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		b.set(ub);
		b.get(ua);
		b.get(ub);
		w.write(Instruction.I32_NE);
		b.ifVoid();
		b.i32c(-1);
		b.i32c(1);
		b.get(ua);
		b.get(ub);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.SELECT);
		w.write(Instruction.RETURN);
		b.end();
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(i);
		b.br(0);
		b.end();
		b.end();

		b.i32c(0);
		b.end();
		return b.toByteArray();
	}

	// _limb_shl(arr, bits) -> array: left shift by a non-negative count into a fresh
	// len + bits/32 + 1 array (the extra limb keeps the sign word).
	static byte[] buildLimbShlBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// params 0=arr, 1=bits. locals: 2=ls, 3=bs, 4=n, 5=i, 6=src (i32), 7=t (i64),
		// 8=out (ref null $limbs)
		w.write(3);
		w.write(5);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I64);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		final int ls = 2, bs = 3, n = 4, i = 5, src = 6, t = 7, out = 8;

		b.get(1);
		b.i32c(5);
		w.write(Instruction.I32_SHR_S);
		b.set(ls);
		b.get(1);
		b.i32c(31);
		w.write(Instruction.I32_AND);
		b.set(bs);
		b.get(0);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.arrayLen();
		b.get(ls);
		w.write(Instruction.I32_ADD);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(n);
		b.get(n);
		b.arrayNewDefault();
		b.set(out);

		b.i32c(0);
		b.set(i);
		b.block();
		b.loop();
		b.get(i);
		b.get(n);
		w.write(Instruction.I32_GE_S);
		b.brIf(1);
		b.get(i);
		b.get(ls);
		w.write(Instruction.I32_SUB);
		b.set(src);
		// t = (extU(g(src)) << bs) | ((extU(g(src-1)) << bs) >> 32); g(k) = 0 for k<0
		emitShlSourceLimb(b, src, 0);
		w.write(Instruction.I64_EXTEND_U_I32);
		b.get(bs);
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.I64_SHL);
		emitShlSourceLimb(b, src, 1);
		w.write(Instruction.I64_EXTEND_U_I32);
		b.get(bs);
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.I64_SHL);
		b.i64c(32);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I64_OR);
		b.set(t);
		b.get(out);
		b.get(i);
		b.get(t);
		w.write(Instruction.I32_WRAP_I64);
		b.arraySet();
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(i);
		b.br(0);
		b.end();
		b.end();

		b.get(out);
		b.end();
		return b.toByteArray();
	}

	// Pushes g(src - delta): 0 for a negative index, else _limb_get(arr, src - delta)
	// (which sign-extends past the top).
	private static void emitShlSourceLimb(BodyWriter b, int src, int delta) {
		WasmWriter w = b.w;
		b.get(src);
		b.i32c(delta);
		w.write(Instruction.I32_SUB);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF);
		w.write(Type.I32);
		b.i32c(0);
		w.write(Instruction.ELSE);
		b.get(0);
		b.get(src);
		b.i32c(delta);
		w.write(Instruction.I32_SUB);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		b.end();
	}

	// _limb_shr(arr, bits) -> array: arithmetic right shift by a non-negative count
	// (already clamped by the caller so ls stays a sane i32).
	static byte[] buildLimbShrBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// params 0=arr, 1=bits. locals: 2=ls, 3=bs, 4=n, 5=i (i32), 6=t (i64),
		// 7=out (ref null $limbs)
		w.write(3);
		w.write(4);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I64);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		final int ls = 2, bs = 3, n = 4, i = 5, t = 6, out = 7;

		b.get(1);
		b.i32c(5);
		w.write(Instruction.I32_SHR_S);
		b.set(ls);
		b.get(1);
		b.i32c(31);
		w.write(Instruction.I32_AND);
		b.set(bs);
		// n = max(len - ls, 1)
		b.get(0);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.arrayLen();
		b.get(ls);
		w.write(Instruction.I32_SUB);
		b.set(n);
		b.get(n);
		b.i32c(1);
		w.write(Instruction.I32_LT_S);
		b.ifVoid();
		b.i32c(1);
		b.set(n);
		b.end();
		b.get(n);
		b.arrayNewDefault();
		b.set(out);

		b.i32c(0);
		b.set(i);
		b.block();
		b.loop();
		b.get(i);
		b.get(n);
		w.write(Instruction.I32_GE_S);
		b.brIf(1);
		// t = ((extS(get(i+ls+1)) << 32) | extU(get(i+ls))) >>s bs
		b.get(0);
		b.get(i);
		b.get(ls);
		w.write(Instruction.I32_ADD);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		w.write(Instruction.I64_EXTEND_S_I32);
		b.i64c(32);
		w.write(Instruction.I64_SHL);
		b.get(0);
		b.get(i);
		b.get(ls);
		w.write(Instruction.I32_ADD);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.I64_OR);
		b.get(bs);
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.I64_SHR_S);
		b.set(t);
		b.get(out);
		b.get(i);
		b.get(t);
		w.write(Instruction.I32_WRAP_I64);
		b.arraySet();
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(i);
		b.br(0);
		b.end();
		b.end();

		b.get(out);
		b.end();
		return b.toByteArray();
	}

	// _limb_divrem_mag(u, v, which) -> array: binary long division on non-negative
	// values (the caller pre-negates); reads u bit-by-bit from the top, shifting the
	// remainder window left and subtracting v whenever it fits. which = 0 answers the
	// quotient, 1 the remainder. Neither input array is mutated.
	static byte[] buildLimbDivremMagBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// params 0=u, 1=v, 2=which. locals: 3=uT, 4=vT, 5=q, 6=r (ref null $limbs),
		// 7=lu, 8=lv, 9=lr, 10=i, 11=j, 12=bit, 13=ge, 14=nc, 15=rv, 16=vv (i32),
		// 17=t (i64)
		w.write(3);
		w.write(4);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		w.write(10);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I64);
		final int uT = 3, vT = 4, q = 5, r = 6, lu = 7, lv = 8, lr = 9, i = 10, j = 11, bit = 12, ge = 13, nc = 14,
				rv = 15, vv = 16, t = 17;

		b.get(0);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(uT);
		b.get(1);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(vT);
		b.get(uT);
		b.arrayLen();
		b.set(lu);
		b.get(vT);
		b.arrayLen();
		b.set(lv);
		b.get(lv);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(lr);
		b.get(lu);
		b.arrayNewDefault();
		b.set(q);
		b.get(lr);
		b.arrayNewDefault();
		b.set(r);

		// i = lu*32 - 1
		b.get(lu);
		b.i32c(5);
		w.write(Instruction.I32_SHL);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(i);

		b.block();
		b.loop();
		b.get(i);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.brIf(1);

		// bit = (u[i>>5] >>u (i&31)) & 1
		b.get(uT);
		b.get(i);
		b.i32c(5);
		w.write(Instruction.I32_SHR_S);
		b.arrayGet();
		b.get(i);
		b.i32c(31);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_SHR_U);
		b.i32c(1);
		w.write(Instruction.I32_AND);
		b.set(bit);

		// r = (r << 1) | bit, in place
		b.i32c(0);
		b.set(j);
		b.block();
		b.loop();
		b.get(j);
		b.get(lr);
		w.write(Instruction.I32_GE_S);
		b.brIf(1);
		b.get(r);
		b.get(j);
		b.arrayGet();
		b.i32c(31);
		w.write(Instruction.I32_SHR_U);
		b.set(nc);
		b.get(r);
		b.get(j);
		b.get(r);
		b.get(j);
		b.arrayGet();
		b.i32c(1);
		w.write(Instruction.I32_SHL);
		b.get(bit);
		w.write(Instruction.I32_OR);
		b.arraySet();
		b.get(nc);
		b.set(bit);
		b.get(j);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(j);
		b.br(0);
		b.end();
		b.end();

		// ge = (r >= v) unsigned, scanning from the top
		b.i32c(1);
		b.set(ge);
		b.get(lr);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(j);
		b.block();
		b.loop();
		b.get(j);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.brIf(1);
		b.get(r);
		b.get(j);
		b.arrayGet();
		b.set(rv);
		emitDivisorLimb(b, vT, j, lv);
		b.set(vv);
		b.get(rv);
		b.get(vv);
		w.write(Instruction.I32_NE);
		b.ifVoid();
		b.get(rv);
		b.get(vv);
		w.write(Instruction.I32_GT_U);
		b.set(ge);
		b.br(2);
		b.end();
		b.get(j);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(j);
		b.br(0);
		b.end();
		b.end();

		// if (ge) { r -= v; q |= 1 << i }
		b.get(ge);
		b.ifVoid();
		b.i32c(0);
		b.set(bit); // borrow
		b.i32c(0);
		b.set(j);
		b.block();
		b.loop();
		b.get(j);
		b.get(lr);
		w.write(Instruction.I32_GE_S);
		b.brIf(1);
		b.get(r);
		b.get(j);
		b.arrayGet();
		w.write(Instruction.I64_EXTEND_U_I32);
		emitDivisorLimb(b, vT, j, lv);
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.I64_SUB);
		b.get(bit);
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.I64_SUB);
		b.set(t);
		b.get(r);
		b.get(j);
		b.get(t);
		w.write(Instruction.I32_WRAP_I64);
		b.arraySet();
		b.get(t);
		b.i64c(0);
		w.write(Instruction.I64_LT_S);
		b.set(bit);
		b.get(j);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(j);
		b.br(0);
		b.end();
		b.end();
		// q[i>>5] |= 1 << (i&31)
		b.get(q);
		b.get(i);
		b.i32c(5);
		w.write(Instruction.I32_SHR_S);
		b.get(q);
		b.get(i);
		b.i32c(5);
		w.write(Instruction.I32_SHR_S);
		b.arrayGet();
		b.i32c(1);
		b.get(i);
		b.i32c(31);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		b.arraySet();
		b.end();

		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(i);
		b.br(0);
		b.end();
		b.end();

		// which ? r : q
		b.get(2);
		b.ifEq();
		b.get(r);
		b.els();
		b.get(q);
		b.end();
		b.end();
		return b.toByteArray();
	}

	// Pushes v[j] for j < lv, else 0 (the remainder window is one limb longer than v).
	private static void emitDivisorLimb(BodyWriter b, int vT, int j, int lv) {
		WasmWriter w = b.w;
		b.get(j);
		b.get(lv);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF);
		w.write(Type.I32);
		b.get(vT);
		b.get(j);
		b.arrayGet();
		w.write(Instruction.ELSE);
		b.i32c(0);
		b.end();
	}

	// _limb_divmod_small(arr, d) -> i32 rem: divide a NON-NEGATIVE value in place by a
	// small positive divisor (the decimal printer's 10^9). Treats limbs as unsigned.
	static byte[] buildLimbDivmodSmallBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// params 0=arr, 1=d. locals: 2=aT (ref null $limbs), 3=i (i32), 4=cur, 5=r
		// (i64)
		w.write(3);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		w.write(1);
		w.write(Type.I32);
		w.write(2);
		w.write(Type.I64);
		final int aT = 2, i = 3, cur = 4, r = 5;

		b.get(0);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(aT);
		b.get(aT);
		b.arrayLen();
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(i);
		b.i64c(0);
		b.set(r);

		b.block();
		b.loop();
		b.get(i);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.brIf(1);
		// cur = (r << 32) | extU(aT[i])
		b.get(r);
		b.i64c(32);
		w.write(Instruction.I64_SHL);
		b.get(aT);
		b.get(i);
		b.arrayGet();
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.I64_OR);
		b.set(cur);
		// aT[i] = (i32)(cur /u d); r = cur %u d
		b.get(aT);
		b.get(i);
		b.get(cur);
		b.get(1);
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.I64_DIV_U);
		w.write(Instruction.I32_WRAP_I64);
		b.arraySet();
		b.get(cur);
		b.get(1);
		w.write(Instruction.I64_EXTEND_U_I32);
		w.write(Instruction.I64_REM_U);
		b.set(r);
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(i);
		b.br(0);
		b.end();
		b.end();

		b.get(r);
		w.write(Instruction.I32_WRAP_I64);
		b.end();
		return b.toByteArray();
	}

	// _big_add / _big_sub(a, b): exact addition/subtraction over any integer tier. The
	// i64 fast path detects overflow with the sign trick and promotes to the limb path
	// instead of wrapping.
	static byte[] buildBigAddBody(boolean sub) {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 2=va, 3=vb, 4=rr (i64)
		w.write(1);
		w.write(3);
		w.write(Type.I64);
		final int va = 2, vb = 3, rr = 4;

		emitNeitherBigint(b);
		b.ifVoid();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(va);
		b.get(1);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(vb);
		b.get(va);
		b.get(vb);
		w.write(sub ? Instruction.I64_SUB : Instruction.I64_ADD);
		b.set(rr);
		// no overflow: add -> ((va^rr)&(vb^rr)) >= 0; sub -> ((va^vb)&(va^rr)) >= 0
		b.get(va);
		b.get(sub ? vb : rr);
		w.write(Instruction.I64_XOR);
		b.get(sub ? va : vb);
		b.get(rr);
		w.write(Instruction.I64_XOR);
		w.write(Instruction.I64_AND);
		b.i64c(0);
		w.write(Instruction.I64_GE_S);
		b.ifVoid();
		b.get(rr);
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.RETURN);
		b.end();
		b.end();

		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.get(1);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.i32c(sub ? 1 : 0);
		b.call(WasmLispCompiler.FUNC_LIMB_ADDSUB);
		b.call(WasmLispCompiler.FUNC_LIMB_NEW);
		b.end();
		return b.toByteArray();
	}

	// _big_mul(a, b): exact multiplication. The i64 fast path runs only when the
	// magnitude bit counts guarantee no overflow (clz(x ^ x>>63) sums >= 66); the
	// borderline band takes the limb path and normalizes back down.
	static byte[] buildBigMulBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 2=va, 3=vb (i64)
		w.write(1);
		w.write(2);
		w.write(Type.I64);
		final int va = 2, vb = 3;

		emitNeitherBigint(b);
		b.ifVoid();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(va);
		b.get(1);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(vb);
		emitClzMag(b, va);
		emitClzMag(b, vb);
		w.write(Instruction.I64_ADD);
		b.i64c(66);
		w.write(Instruction.I64_GE_S);
		b.ifVoid();
		b.get(va);
		b.get(vb);
		w.write(Instruction.I64_MUL);
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.RETURN);
		b.end();
		b.end();

		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.get(1);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.call(WasmLispCompiler.FUNC_LIMB_MUL);
		b.call(WasmLispCompiler.FUNC_LIMB_NEW);
		b.end();
		return b.toByteArray();
	}

	// Pushes clz(x ^ (x >> 63)) for the i64 in the given local: the leading-sign-bit
	// count, i.e. 64 minus the magnitude bit length.
	private static void emitClzMag(BodyWriter b, int slot) {
		WasmWriter w = b.w;
		b.get(slot);
		b.get(slot);
		b.i64c(63);
		w.write(Instruction.I64_SHR_S);
		w.write(Instruction.I64_XOR);
		w.write(Instruction.I64_CLZ);
	}

	// _big_neg(x): exact negation; i64.min promotes to the limb tier instead of
	// wrapping onto itself.
	static byte[] buildBigNegBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 1=v (i64)
		w.write(1);
		w.write(1);
		w.write(Type.I64);

		b.get(0);
		b.refTest(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_EQZ);
		b.ifVoid();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(1);
		b.get(1);
		b.i64c(Long.MIN_VALUE);
		w.write(Instruction.I64_NE);
		b.ifVoid();
		b.i64c(0);
		b.get(1);
		w.write(Instruction.I64_SUB);
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.RETURN);
		b.end();
		b.end();

		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.call(WasmLispCompiler.FUNC_LIMB_NEG);
		b.call(WasmLispCompiler.FUNC_LIMB_NEW);
		b.end();
		return b.toByteArray();
	}

	// _big_divrem(a, b, which): truncating quotient (0) / remainder (1) at any tier.
	// The i64 fast path traps on b = 0 (rem_s/div_s) and routes the i64.min / -1
	// overflow edge to the limb path; the limb path divides magnitudes and applies the
	// truncating sign rules (remainder takes the dividend's sign).
	static byte[] buildBigDivremBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// params 0=a, 1=b, 2=which. locals: 3=va, 4=vb (i64), 5=sa, 6=sb (i32),
		// 7=ma, 8=mb, 9=res (ref null eq)
		w.write(3);
		w.write(2);
		w.write(Type.I64);
		w.write(2);
		w.write(Type.I32);
		w.write(3);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		final int va = 3, vb = 4, sa = 5, sb = 6, ma = 7, mb = 8, res = 9;

		emitNeitherBigint(b);
		b.ifVoid();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(va);
		b.get(1);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(vb);
		// the i64.min / -1 edge overflows div_s; promote it to the limb path
		b.get(va);
		b.i64c(Long.MIN_VALUE);
		w.write(Instruction.I64_EQ);
		b.get(vb);
		b.i64c(-1);
		w.write(Instruction.I64_EQ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_EQZ);
		b.ifVoid();
		b.get(2);
		w.write(Instruction.IF);
		w.write(Type.I64);
		b.get(va);
		b.get(vb);
		w.write(Instruction.I64_REM_S);
		w.write(Instruction.ELSE);
		b.get(va);
		b.get(vb);
		w.write(Instruction.I64_DIV_S);
		b.end();
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.RETURN);
		b.end();
		b.end();

		// a zero divisor still traps on the limb path
		b.get(1);
		b.refTest(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_EQZ);
		b.ifVoid();
		b.get(1);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		w.write(Instruction.I64_EQZ);
		b.ifVoid();
		w.write(Instruction.UNREACHABLE);
		b.end();
		b.end();

		// signs, magnitudes
		b.get(0);
		emitI31Zero(b);
		b.call(WasmLispCompiler.FUNC_BIG_CMP);
		b.set(sa);
		b.get(1);
		emitI31Zero(b);
		b.call(WasmLispCompiler.FUNC_BIG_CMP);
		b.set(sb);
		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.set(ma);
		b.get(sa);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.ifVoid();
		b.get(ma);
		b.call(WasmLispCompiler.FUNC_LIMB_NEG);
		b.set(ma);
		b.end();
		b.get(1);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.set(mb);
		b.get(sb);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.ifVoid();
		b.get(mb);
		b.call(WasmLispCompiler.FUNC_LIMB_NEG);
		b.set(mb);
		b.end();

		b.get(ma);
		b.get(mb);
		b.get(2);
		b.call(WasmLispCompiler.FUNC_LIMB_DIVREM_MAG);
		b.call(WasmLispCompiler.FUNC_LIMB_NEW);
		b.set(res);

		// remainder: sign of the dividend; quotient: negative when the signs differ
		b.get(2);
		w.write(Instruction.IF);
		w.write(Type.I32);
		b.get(sa);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.ELSE);
		b.get(sa);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.get(sb);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_NE);
		b.end();
		b.ifVoid();
		b.get(res);
		b.call(WasmLispCompiler.FUNC_BIG_NEG);
		b.set(res);
		b.end();

		b.get(res);
		b.end();
		return b.toByteArray();
	}

	// _big_mod(a, b): Common Lisp mod -- the truncating remainder shifted by the
	// divisor when it is nonzero and the signs differ.
	static byte[] buildBigModBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 2=r (ref null eq)
		w.write(1);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		final int r = 2;

		b.get(0);
		b.get(1);
		b.i32c(1);
		b.call(WasmLispCompiler.FUNC_BIG_DIVREM);
		b.set(r);
		b.get(r);
		emitI31Zero(b);
		w.write(Instruction.REF_EQ);
		b.ifVoid();
		b.get(r);
		w.write(Instruction.RETURN);
		b.end();
		// (r < 0) != (b < 0) ?
		b.get(r);
		emitI31Zero(b);
		b.call(WasmLispCompiler.FUNC_BIG_CMP);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.get(1);
		emitI31Zero(b);
		b.call(WasmLispCompiler.FUNC_BIG_CMP);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_NE);
		b.ifVoid();
		b.get(r);
		b.get(1);
		b.call(WasmLispCompiler.FUNC_BIG_ADD);
		w.write(Instruction.RETURN);
		b.end();
		b.get(r);
		b.end();
		return b.toByteArray();
	}

	// _big_cmp(a, b) -> -1/0/1 over exact integers at any tier.
	static byte[] buildBigCmpBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 2=va, 3=vb (i64)
		w.write(1);
		w.write(2);
		w.write(Type.I64);
		final int va = 2, vb = 3;

		emitNeitherBigint(b);
		b.ifVoid();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(va);
		b.get(1);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(vb);
		b.get(va);
		b.get(vb);
		w.write(Instruction.I64_GT_S);
		b.get(va);
		b.get(vb);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.RETURN);
		b.end();

		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.get(1);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.call(WasmLispCompiler.FUNC_LIMB_CMP);
		b.end();
		return b.toByteArray();
	}

	// _big_and/_big_or/_big_xor(a, b): limb-wise with sign extension (two's complement
	// makes the CL negative-operand semantics fall out); both-i64 operands stay i64.
	static byte[] buildBigBitopBody(int i64Opcode) {
		int i32Opcode = i64Opcode == Instruction.I64_AND ? Instruction.I32_AND
				: i64Opcode == Instruction.I64_OR ? Instruction.I32_OR : Instruction.I32_XOR;
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 2=aArr, 3=bArr (ref null eq), 4=n, 5=i (i32), 6=out (ref null
		// $limbs)
		w.write(3);
		w.write(2);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(2);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		final int aArr = 2, bArr = 3, n = 4, i = 5, out = 6;

		emitNeitherBigint(b);
		b.ifVoid();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.get(1);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		w.write(i64Opcode);
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.RETURN);
		b.end();

		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.set(aArr);
		b.get(1);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.set(bArr);
		b.get(aArr);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.arrayLen();
		b.set(n);
		b.get(bArr);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.arrayLen();
		b.set(i);
		b.get(i);
		b.get(n);
		w.write(Instruction.I32_GT_S);
		b.ifVoid();
		b.get(i);
		b.set(n);
		b.end();
		b.get(n);
		b.arrayNewDefault();
		b.set(out);

		b.i32c(0);
		b.set(i);
		b.block();
		b.loop();
		b.get(i);
		b.get(n);
		w.write(Instruction.I32_GE_S);
		b.brIf(1);
		b.get(out);
		b.get(i);
		b.get(aArr);
		b.get(i);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		b.get(bArr);
		b.get(i);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		w.write(i32Opcode);
		b.arraySet();
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(i);
		b.br(0);
		b.end();
		b.end();

		b.get(out);
		b.call(WasmLispCompiler.FUNC_LIMB_NEW);
		b.end();
		return b.toByteArray();
	}

	// _big_not(x): lognot = x XOR -1 at any tier.
	static byte[] buildBigNotBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 1=aT (ref null $limbs), 2=n, 3=i (i32), 4=out (ref null $limbs)
		w.write(3);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		w.write(2);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		final int aT = 1, n = 2, i = 3, out = 4;

		b.get(0);
		b.refTest(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_EQZ);
		b.ifVoid();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.i64c(-1);
		w.write(Instruction.I64_XOR);
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.RETURN);
		b.end();

		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(aT);
		b.get(aT);
		b.arrayLen();
		b.set(n);
		b.get(n);
		b.arrayNewDefault();
		b.set(out);
		b.i32c(0);
		b.set(i);
		b.block();
		b.loop();
		b.get(i);
		b.get(n);
		w.write(Instruction.I32_GE_S);
		b.brIf(1);
		b.get(out);
		b.get(i);
		b.get(aT);
		b.get(i);
		b.arrayGet();
		b.i32c(-1);
		w.write(Instruction.I32_XOR);
		b.arraySet();
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(i);
		b.br(0);
		b.end();
		b.end();
		b.get(out);
		b.call(WasmLispCompiler.FUNC_LIMB_NEW);
		b.end();
		return b.toByteArray();
	}

	// _big_ash(x, count): arithmetic shift at any tier. A left shift whose result
	// still fits i64 stays inline; anything wider goes through the limb shifters. A
	// left count past 2^25 traps (a runaway allocation guard); a right count clamps at
	// the value's width, answering the sign word.
	static byte[] buildBigAshBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// params 0=x, 1=count. locals: 2=c, 3=va, 4=m (i64), 5=arr (ref null eq)
		w.write(2);
		w.write(3);
		w.write(Type.I64);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		final int c = 2, va = 3, m = 4, arr = 5;

		b.get(1);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(c);
		b.get(c);
		b.i64c(0);
		w.write(Instruction.I64_GE_S);
		b.ifVoid();
		// left shift
		b.get(0);
		b.refTest(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_EQZ);
		b.ifVoid();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(va);
		// bits(va) + c <= 62 -> stays i64
		b.i64c(64);
		emitClzMag(b, va);
		w.write(Instruction.I64_SUB);
		b.get(c);
		w.write(Instruction.I64_ADD);
		b.i64c(62);
		w.write(Instruction.I64_LE_S);
		b.ifVoid();
		b.get(va);
		b.get(c);
		w.write(Instruction.I64_SHL);
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.RETURN);
		b.end();
		b.end();
		b.get(c);
		b.i64c(1L << 25);
		w.write(Instruction.I64_GT_S);
		b.ifVoid();
		w.write(Instruction.UNREACHABLE);
		b.end();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.get(c);
		w.write(Instruction.I32_WRAP_I64);
		b.call(WasmLispCompiler.FUNC_LIMB_SHL);
		b.call(WasmLispCompiler.FUNC_LIMB_NEW);
		w.write(Instruction.RETURN);
		b.end();

		// right shift: m = -c
		b.i64c(0);
		b.get(c);
		w.write(Instruction.I64_SUB);
		b.set(m);
		b.get(0);
		b.refTest(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_EQZ);
		b.ifVoid();
		b.get(m);
		b.i64c(63);
		w.write(Instruction.I64_GT_S);
		b.ifVoid();
		b.i64c(63);
		b.set(m);
		b.end();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.get(m);
		w.write(Instruction.I64_SHR_S);
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.RETURN);
		b.end();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.set(arr);
		// clamp m at len*32 + 32
		b.get(m);
		b.get(arr);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.arrayLen();
		b.i32c(5);
		w.write(Instruction.I32_SHL);
		b.i32c(32);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I64_EXTEND_S_I32);
		b.tee(va);
		w.write(Instruction.I64_GT_S);
		b.ifVoid();
		b.get(va);
		b.set(m);
		b.end();
		b.get(arr);
		b.get(m);
		w.write(Instruction.I32_WRAP_I64);
		b.call(WasmLispCompiler.FUNC_LIMB_SHR);
		b.call(WasmLispCompiler.FUNC_LIMB_NEW);
		b.end();
		return b.toByteArray();
	}

	// _big_intlen(x): Common Lisp integer-length at any tier -- the magnitude bit
	// count over the value's ones' complement when negative.
	static byte[] buildBigIntlenBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 1=v (i64), 2=aT (ref null $limbs), 3=s, 4=i, 5=t (i32)
		w.write(3);
		w.write(1);
		w.write(Type.I64);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		w.write(3);
		w.write(Type.I32);
		final int v = 1, aT = 2, s = 3, i = 4, t = 5;

		b.get(0);
		b.refTest(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_EQZ);
		b.ifVoid();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(v);
		b.i64c(64);
		emitClzMag(b, v);
		w.write(Instruction.I64_SUB);
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.RETURN);
		b.end();

		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(aT);
		b.get(aT);
		b.get(aT);
		b.arrayLen();
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.tee(i);
		b.arrayGet();
		b.i32c(31);
		w.write(Instruction.I32_SHR_S);
		b.set(s);
		b.block();
		b.loop();
		b.get(i);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.brIf(1);
		b.get(aT);
		b.get(i);
		b.arrayGet();
		b.get(s);
		w.write(Instruction.I32_XOR);
		b.tee(t);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.I32_EQZ);
		b.ifVoid();
		// i*32 + 32 - clz32(t)
		b.get(i);
		b.i32c(5);
		w.write(Instruction.I32_SHL);
		b.i32c(32);
		w.write(Instruction.I32_ADD);
		b.get(t);
		w.write(Instruction.I32_CLZ);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I64_EXTEND_U_I32);
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		w.write(Instruction.RETURN);
		b.end();
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(i);
		b.br(0);
		b.end();
		b.end();
		b.i64c(0);
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		b.end();
		return b.toByteArray();
	}

	// _big_logbitp(idx, n) -> i32 bit: the two's-complement bit at idx, sign-extended
	// past the top (matching the i64 tier's clamped arithmetic shift).
	static byte[] buildBigLogbitpBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// params 0=idx, 1=n. locals: 2=c, 3=v (i64), 4=arr (ref null eq)
		w.write(2);
		w.write(2);
		w.write(Type.I64);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		final int c = 2, v = 3, arr = 4;

		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.set(c);
		b.get(1);
		b.refTest(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_EQZ);
		b.ifVoid();
		b.get(c);
		b.i64c(63);
		w.write(Instruction.I64_GT_S);
		b.ifVoid();
		b.i64c(63);
		b.set(c);
		b.end();
		b.get(1);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		b.get(c);
		w.write(Instruction.I64_SHR_S);
		b.i64c(1);
		w.write(Instruction.I64_AND);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.RETURN);
		b.end();

		b.get(1);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.set(arr);
		// clamp the limb index into the sign word for a huge idx
		b.get(c);
		b.get(arr);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.arrayLen();
		b.i32c(5);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I64_EXTEND_S_I32);
		b.tee(v);
		w.write(Instruction.I64_GE_S);
		b.ifVoid();
		b.get(v);
		b.set(c);
		b.end();
		b.get(arr);
		b.get(c);
		b.i64c(5);
		w.write(Instruction.I64_SHR_S);
		w.write(Instruction.I32_WRAP_I64);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		b.get(c);
		b.i64c(31);
		w.write(Instruction.I64_AND);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_SHR_U);
		b.i32c(1);
		w.write(Instruction.I32_AND);
		b.end();
		return b.toByteArray();
	}

	// _big_gcd(a, b): the non-negative gcd via Euclid over _big_divrem remainders.
	static byte[] buildBigGcdBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 2=t (ref null eq)
		w.write(1);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		final int t = 2;

		emitAbsIntoParam(b, 0);
		emitAbsIntoParam(b, 1);
		b.block();
		b.loop();
		b.get(1);
		emitI31Zero(b);
		w.write(Instruction.REF_EQ);
		b.brIf(1);
		b.get(0);
		b.get(1);
		b.i32c(1);
		b.call(WasmLispCompiler.FUNC_BIG_DIVREM);
		b.set(t);
		b.get(1);
		b.set(0);
		b.get(t);
		b.set(1);
		b.br(0);
		b.end();
		b.end();
		b.get(0);
		b.end();
		return b.toByteArray();
	}

	// param slot = abs(param slot) via _big_cmp / _big_neg.
	private static void emitAbsIntoParam(BodyWriter b, int slot) {
		WasmWriter w = b.w;
		b.get(slot);
		emitI31Zero(b);
		b.call(WasmLispCompiler.FUNC_BIG_CMP);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.ifVoid();
		b.get(slot);
		b.call(WasmLispCompiler.FUNC_BIG_NEG);
		b.set(slot);
		b.end();
	}

	// _big_grow(acc, radix, digit): acc*radix + digit at any tier -- the emitted
	// reader's accumulator step (composed from _big_mul / _big_add, whose i64 fast
	// paths keep ordinary tokens cheap).
	static byte[] buildBigGrowBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		w.write(0);
		b.get(0);
		b.get(1);
		w.write(Instruction.I64_EXTEND_S_I32);
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		b.call(WasmLispCompiler.FUNC_BIG_MUL);
		b.get(2);
		w.write(Instruction.I64_EXTEND_S_I32);
		b.call(WasmLispCompiler.FUNC_INT_NEW);
		b.call(WasmLispCompiler.FUNC_BIG_ADD);
		b.end();
		return b.toByteArray();
	}

	// _big_to_f64(x): float approximation of a limb integer (top-down limb
	// accumulation over exact 2^32 scalings; may differ from a correctly-rounded
	// conversion in the last ulp).
	static byte[] buildBigToF64Body() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 1=aT (ref null $limbs), 2=s, 3=i (i32), 4=acc (f64)
		w.write(3);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		w.write(2);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.F64);
		final int aT = 1, s = 2, i = 3, acc = 4;

		b.get(0);
		b.refTest(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_EQZ);
		b.ifVoid();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_INT_VAL);
		w.write(Instruction.F64_CONVERT_S_I64);
		w.write(Instruction.RETURN);
		b.end();

		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(aT);
		b.get(aT);
		b.get(aT);
		b.arrayLen();
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.arrayGet();
		b.i32c(31);
		w.write(Instruction.I32_SHR_S);
		b.set(s);
		b.get(s);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.ifVoid();
		b.get(aT);
		b.call(WasmLispCompiler.FUNC_LIMB_NEG);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(aT);
		b.end();

		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		b.set(acc);
		b.get(aT);
		b.arrayLen();
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(i);
		b.block();
		b.loop();
		b.get(i);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.brIf(1);
		b.get(acc);
		w.write(Instruction.F64_CONST);
		w.writeF64(4294967296.0);
		w.write(Instruction.F64_MUL);
		b.get(aT);
		b.get(i);
		b.arrayGet();
		w.write(Instruction.F64_CONVERT_U_I32);
		w.write(Instruction.F64_ADD);
		b.set(acc);
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(i);
		b.br(0);
		b.end();
		b.end();

		b.get(s);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.ifVoid();
		b.get(acc);
		w.write(Instruction.F64_NEG);
		b.set(acc);
		b.end();
		b.get(acc);
		b.end();
		return b.toByteArray();
	}

	// _big_print(x): the sign, then the magnitude digits via _big_print_mag on a
	// fresh mutable copy (a TYPE_BIGINT's own limbs are never mutated). Everything
	// funnels through _write_str so princ-to-string / format capture works.
	static byte[] buildBigPrintBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 1=a0 (ref null eq), 2=la, 3=s (i32)
		w.write(2);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(2);
		w.write(Type.I32);
		final int a0 = 1, la = 2, s = 3;

		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.set(a0);
		b.get(a0);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.arrayLen();
		b.set(la);
		b.get(a0);
		b.get(la);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.call(WasmLispCompiler.FUNC_LIMB_GET);
		b.i32c(31);
		w.write(Instruction.I32_SHR_S);
		b.set(s);

		b.get(s);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.ifVoid();
		b.i32c(WasmLispCompiler.PRINT_BUF_OFFSET);
		b.i32c('-');
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		b.i32c(WasmLispCompiler.PRINT_BUF_OFFSET);
		b.i32c(1);
		b.call(WasmLispCompiler.FUNC_WRITE_STR);
		b.get(a0);
		b.call(WasmLispCompiler.FUNC_LIMB_NEG);
		b.set(a0);
		w.write(Instruction.ELSE);
		b.get(a0);
		b.call(WasmLispCompiler.FUNC_LIMB_COPY);
		b.set(a0);
		b.end();

		b.get(a0);
		b.call(WasmLispCompiler.FUNC_BIG_PRINT_MAG);
		b.end();
		return b.toByteArray();
	}

	// _big_print_mag(arr): recursive decimal renderer over divmod 10^9 -- divide in
	// place, recurse on the (shrinking) quotient, then render this 9-digit chunk. The
	// leading chunk prints unpadded through _print_i32_no_nl.
	static byte[] buildBigPrintMagBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 1=r (i32), 2=aT (ref null $limbs), 3=i, 4=nz (i32)
		w.write(3);
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		w.write(2);
		w.write(Type.I32);
		final int r = 1, aT = 2, i = 3, nz = 4;

		b.get(0);
		b.i32c(1000000000);
		b.call(WasmLispCompiler.FUNC_LIMB_DIVMOD_SMALL);
		b.set(r);

		b.get(0);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(aT);
		b.get(aT);
		b.arrayLen();
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(i);
		b.i32c(0);
		b.set(nz);
		b.block();
		b.loop();
		b.get(i);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.brIf(1);
		b.get(aT);
		b.get(i);
		b.arrayGet();
		b.ifVoid();
		b.i32c(1);
		b.set(nz);
		b.br(2);
		b.end();
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(i);
		b.br(0);
		b.end();
		b.end();

		b.get(nz);
		b.ifVoid();
		b.get(0);
		b.call(WasmLispCompiler.FUNC_BIG_PRINT_MAG);
		b.get(r);
		b.call(WasmLispCompiler.FUNC_BIG_PAD9);
		w.write(Instruction.RETURN);
		b.end();
		b.get(r);
		b.call(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		b.end();
		return b.toByteArray();
	}

	// _big_pad9(r): nine zero-padded decimal digits of r (0 <= r < 10^9) through
	// _write_str.
	static byte[] buildBigPad9Body() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 1=j (i32)
		w.write(1);
		w.write(1);
		w.write(Type.I32);
		final int j = 1;

		b.i32c(8);
		b.set(j);
		b.block();
		b.loop();
		b.get(j);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.brIf(1);
		b.i32c(WasmLispCompiler.PRINT_BUF_OFFSET);
		b.get(j);
		w.write(Instruction.I32_ADD);
		b.get(0);
		b.i32c(10);
		w.write(Instruction.I32_REM_U);
		b.i32c(48);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		b.get(0);
		b.i32c(10);
		w.write(Instruction.I32_DIV_U);
		b.set(0);
		b.get(j);
		b.i32c(1);
		w.write(Instruction.I32_SUB);
		b.set(j);
		b.br(0);
		b.end();
		b.end();
		b.i32c(WasmLispCompiler.PRINT_BUF_OFFSET);
		b.i32c(9);
		b.call(WasmLispCompiler.FUNC_WRITE_STR);
		b.end();
		return b.toByteArray();
	}

	// _big_eq(a, b) -> i32: value equality of two TYPE_BIGINTs. Canonical limbs make
	// this a plain length + limb-wise comparison.
	static byte[] buildBigEqBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 2=aT, 3=bT (ref null $limbs), 4=la, 5=i (i32)
		w.write(2);
		w.write(2);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		w.write(2);
		w.write(Type.I32);
		final int aT = 2, bT = 3, la = 4, i = 5;

		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(aT);
		b.get(1);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(bT);
		b.get(aT);
		b.arrayLen();
		b.tee(la);
		b.get(bT);
		b.arrayLen();
		w.write(Instruction.I32_NE);
		b.ifVoid();
		b.i32c(0);
		w.write(Instruction.RETURN);
		b.end();
		b.i32c(0);
		b.set(i);
		b.block();
		b.loop();
		b.get(i);
		b.get(la);
		w.write(Instruction.I32_GE_S);
		b.brIf(1);
		b.get(aT);
		b.get(i);
		b.arrayGet();
		b.get(bT);
		b.get(i);
		b.arrayGet();
		w.write(Instruction.I32_NE);
		b.ifVoid();
		b.i32c(0);
		w.write(Instruction.RETURN);
		b.end();
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(i);
		b.br(0);
		b.end();
		b.end();
		b.i32c(1);
		b.end();
		return b.toByteArray();
	}

	// _big_hash(x) -> i32: fold the limbs (h = h*31 + limb), consistent with _big_eq.
	static byte[] buildBigHashBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// locals: 1=aT (ref null $limbs), 2=la, 3=i, 4=h (i32)
		w.write(2);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(WasmLispCompiler.TYPE_LIMBS);
		w.write(3);
		w.write(Type.I32);
		final int aT = 1, la = 2, i = 3, h = 4;

		b.get(0);
		b.call(WasmLispCompiler.FUNC_LIMB_OF);
		b.refCast(WasmLispCompiler.TYPE_LIMBS);
		b.set(aT);
		b.get(aT);
		b.arrayLen();
		b.set(la);
		b.i32c(0);
		b.set(h);
		b.i32c(0);
		b.set(i);
		b.block();
		b.loop();
		b.get(i);
		b.get(la);
		w.write(Instruction.I32_GE_S);
		b.brIf(1);
		b.get(h);
		b.i32c(31);
		w.write(Instruction.I32_MUL);
		b.get(aT);
		b.get(i);
		b.arrayGet();
		w.write(Instruction.I32_ADD);
		b.set(h);
		b.get(i);
		b.i32c(1);
		w.write(Instruction.I32_ADD);
		b.set(i);
		b.br(0);
		b.end();
		b.end();
		b.get(h);
		b.end();
		return b.toByteArray();
	}

	// _big_fdiv(a, b, mode): exact integer division over any tier -- mode 0 =
	// truncate, 1 = floor, 2 = ceiling, 3 = round to nearest with ties to even. The
	// fused lowering of `(truncate (/ a b))` and friends for exact-integer operands
	// (the ratio intermediate cannot hold limb components). Composed from the other
	// _big_* helpers; traps on b = 0 like _big_divrem.
	static byte[] buildBigFdivBody() {
		BodyWriter b = new BodyWriter();
		WasmWriter w = b.w;
		// params 0=a, 1=b, 2=mode. locals: 3=q, 4=r, 5=rm, 6=bm (ref null eq),
		// 7=sd, 8=c (i32)
		w.write(2);
		w.write(4);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(2);
		w.write(Type.I32);
		final int q = 3, r = 4, rm = 5, bm = 6, sd = 7, c = 8;

		b.get(0);
		b.get(1);
		b.i32c(0);
		b.call(WasmLispCompiler.FUNC_BIG_DIVREM);
		b.set(q);
		b.get(0);
		b.get(1);
		b.i32c(1);
		b.call(WasmLispCompiler.FUNC_BIG_DIVREM);
		b.set(r);
		// even division, or plain truncation: the truncating quotient is the answer
		b.get(r);
		emitI31Zero(b);
		w.write(Instruction.REF_EQ);
		b.get(2);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.I32_OR);
		b.ifVoid();
		b.get(q);
		w.write(Instruction.RETURN);
		b.end();

		// sd = the operand signs differ (both nonzero here: r != 0)
		b.get(0);
		emitI31Zero(b);
		b.call(WasmLispCompiler.FUNC_BIG_CMP);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.get(1);
		emitI31Zero(b);
		b.call(WasmLispCompiler.FUNC_BIG_CMP);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_NE);
		b.set(sd);

		b.get(2);
		b.i32c(1);
		w.write(Instruction.I32_EQ);
		b.ifVoid();
		// floor: q - 1 when the signs differ
		b.get(sd);
		b.ifVoid();
		b.get(q);
		emitI31One(b);
		b.call(WasmLispCompiler.FUNC_BIG_SUB);
		w.write(Instruction.RETURN);
		b.end();
		b.get(q);
		w.write(Instruction.RETURN);
		b.end();

		b.get(2);
		b.i32c(2);
		w.write(Instruction.I32_EQ);
		b.ifVoid();
		// ceiling: q + 1 when the signs agree
		b.get(sd);
		b.ifVoid();
		b.get(q);
		w.write(Instruction.RETURN);
		b.end();
		b.get(q);
		emitI31One(b);
		b.call(WasmLispCompiler.FUNC_BIG_ADD);
		w.write(Instruction.RETURN);
		b.end();

		// round: over the floor quotient qf, compare 2*|a - qf*b| against |b|
		b.get(sd);
		b.ifVoid();
		b.get(q);
		emitI31One(b);
		b.call(WasmLispCompiler.FUNC_BIG_SUB);
		b.set(q);
		b.end();
		b.get(0);
		b.get(q);
		b.get(1);
		b.call(WasmLispCompiler.FUNC_BIG_MUL);
		b.call(WasmLispCompiler.FUNC_BIG_SUB);
		b.set(rm);
		emitAbsIntoParam(b, rm);
		b.get(1);
		b.set(bm);
		emitAbsIntoParam(b, bm);
		b.get(rm);
		b.get(rm);
		b.call(WasmLispCompiler.FUNC_BIG_ADD);
		b.get(bm);
		b.call(WasmLispCompiler.FUNC_BIG_CMP);
		b.set(c);
		b.get(c);
		b.i32c(0);
		w.write(Instruction.I32_LT_S);
		b.ifVoid();
		b.get(q);
		w.write(Instruction.RETURN);
		b.end();
		b.get(c);
		b.i32c(0);
		w.write(Instruction.I32_GT_S);
		b.ifVoid();
		b.get(q);
		emitI31One(b);
		b.call(WasmLispCompiler.FUNC_BIG_ADD);
		w.write(Instruction.RETURN);
		b.end();
		// tie: to even
		emitI31Zero(b);
		b.get(q);
		b.call(WasmLispCompiler.FUNC_BIG_LOGBITP);
		b.ifVoid();
		b.get(q);
		emitI31One(b);
		b.call(WasmLispCompiler.FUNC_BIG_ADD);
		w.write(Instruction.RETURN);
		b.end();
		b.get(q);
		b.end();
		return b.toByteArray();
	}

	// Pushes the i31 one.
	private static void emitI31One(BodyWriter b) {
		b.i32c(1);
		b.w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	// Pushes `!(a is TYPE_BIGINT || b is TYPE_BIGINT)` over params 0 and 1.
	private static void emitNeitherBigint(BodyWriter b) {
		WasmWriter w = b.w;
		b.get(0);
		b.refTest(WasmLispCompiler.TYPE_BIGINT);
		b.get(1);
		b.refTest(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_EQZ);
	}

	// Pushes the i31 zero.
	private static void emitI31Zero(BodyWriter b) {
		b.i32c(0);
		b.w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	// The stack-machine building block for the little emission DSL below.
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

		void tee(int slot) {
			this.w.write(Instruction.TEE_LOCAL);
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

		void call(int funcIndex) {
			this.w.write(Instruction.CALL);
			this.w.writeSignedLeb128(funcIndex);
		}

		void block() {
			this.w.write(Instruction.BLOCK, 0x40);
		}

		void loop() {
			this.w.write(Instruction.LOOP, 0x40);
		}

		void brIf(int depth) {
			this.w.write(Instruction.BR_IF, depth);
		}

		void br(int depth) {
			this.w.write(Instruction.BR, depth);
		}

		void ifVoid() {
			this.w.write(Instruction.IF, 0x40);
		}

		void ifEq() {
			this.w.write(Instruction.IF);
			this.w.write(Type.REFNULL.code());
			this.w.writeHeapType(Type.EQ.code());
		}

		void els() {
			this.w.write(Instruction.ELSE);
		}

		void end() {
			this.w.write(Instruction.END);
		}

		void refTest(int typeIndex) {
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			this.w.writeHeapType(typeIndex);
		}

		void refCast(int typeIndex) {
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			this.w.writeHeapType(typeIndex);
		}

		void structNew(int typeIndex) {
			this.w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			this.w.writeSignedLeb128(typeIndex);
		}

		void structGet(int typeIndex, int field) {
			this.w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			this.w.writeSignedLeb128(typeIndex);
			this.w.writeSignedLeb128(field);
		}

		void arrayGet() {
			this.w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
			this.w.writeSignedLeb128(WasmLispCompiler.TYPE_LIMBS);
		}

		void arraySet() {
			this.w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
			this.w.writeSignedLeb128(WasmLispCompiler.TYPE_LIMBS);
		}

		void arrayLen() {
			this.w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		}

		void arrayNewDefault() {
			this.w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
			this.w.writeSignedLeb128(WasmLispCompiler.TYPE_LIMBS);
		}

		void arrayCopy() {
			this.w.write(Instruction.GC_PREFIX, Instruction.ARRAY_COPY);
			this.w.writeSignedLeb128(WasmLispCompiler.TYPE_LIMBS);
			this.w.writeSignedLeb128(WasmLispCompiler.TYPE_LIMBS);
		}

		byte[] toByteArray() {
			return this.out.toByteArray();
		}

	}

}
