package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Emits the Schubfach shortest-decimal runtime shared by the two WASM backends: the digit
 * selection ({@code _f64_dec} / {@code _f32_dec}), its helpers ({@code _schub_umulhi},
 * {@code _schub_g}, {@code _schub_rop}), and the formatter ({@code _dec_fmt}) that
 * renders (digits, exponent) as the {@code FloatText} spelling into linear memory. Every
 * body is pure i32/i64/f64 arithmetic plus loads from the {@link SchubfachTables} blob
 * and byte stores into caller-provided buffers, so the same emission serves the GC
 * backend and {@code --no-gc}; only the function indices and the table/buffer addresses
 * are parameters. The algorithm is the mirror in {@link SchubfachTables}, instruction for
 * instruction; change them together ({@code SchubfachTablesTest} pins the mirror against
 * {@code FloatText}).
 */
final class WasmSchubfachRuntimeBuilder {

	private WasmSchubfachRuntimeBuilder() {
	}

	private static final long MASK_32 = 0xFFFFFFFFL;

	private static final long MASK_63 = (1L << 63) - 1;

	/**
	 * {@code _schub_umulhi (a i64, b i64) -> i64}: the high 64 bits of the unsigned
	 * 128-bit product, via 32-bit halves.
	 * @return the function body
	 */
	static byte[] buildUmulhiBody() {
		Asm a = new Asm();
		a.locals(new int[] { 6, Type.I64.code() });
		// 2=aL 3=aH 4=bL 5=bH 6=t/w2 7=w1/t2/k
		a.get(0).i64(MASK_32).op(Instruction.I64_AND).set(2);
		a.get(0).i64(32).op(Instruction.I64_SHR_U).set(3);
		a.get(1).i64(MASK_32).op(Instruction.I64_AND).set(4);
		a.get(1).i64(32).op(Instruction.I64_SHR_U).set(5);
		// t = (aL*bL) >>> 32
		a.get(2).get(4).op(Instruction.I64_MUL).i64(32).op(Instruction.I64_SHR_U).set(6);
		// t1 = aH*bL + t
		a.get(3).get(4).op(Instruction.I64_MUL).get(6).op(Instruction.I64_ADD).set(6);
		// w1 = t1 & M32 ; w2 = t1 >>> 32
		a.get(6).i64(MASK_32).op(Instruction.I64_AND).set(7);
		a.get(6).i64(32).op(Instruction.I64_SHR_U).set(6);
		// t2 = aL*bH + w1 ; k = t2 >>> 32
		a.get(2).get(5).op(Instruction.I64_MUL).get(7).op(Instruction.I64_ADD).set(7);
		a.get(7).i64(32).op(Instruction.I64_SHR_U).set(7);
		// aH*bH + w2 + k
		a.get(3).get(5).op(Instruction.I64_MUL).get(6).op(Instruction.I64_ADD).get(7).op(Instruction.I64_ADD);
		a.end();
		return a.finish();
	}

	/**
	 * {@code _schub_rop (g1 i64, g0 i64, cp i64) -> i64}: the rounded odd product of the
	 * 126-bit g and cp (Schubfach figure 9).
	 * @param umulhiIdx the {@code _schub_umulhi} function index
	 * @return the function body
	 */
	static byte[] buildRopBody(int umulhiIdx) {
		Asm a = new Asm();
		a.locals(new int[] { 2, Type.I64.code() });
		// 3=z 4=y1
		a.get(1).get(2).call(umulhiIdx).set(3); // x1
		a.get(0).get(2).call(umulhiIdx).set(4); // y1
		// z = ((g1*cp) >>> 1) + x1
		a.get(0).get(2).op(Instruction.I64_MUL).i64(1).op(Instruction.I64_SHR_U).get(3).op(Instruction.I64_ADD).set(3);
		// (y1 + (z >>> 63)) | (((z & M63) + M63) >>> 63)
		a.get(4).get(3).i64(63).op(Instruction.I64_SHR_U).op(Instruction.I64_ADD);
		a.get(3)
			.i64(MASK_63)
			.op(Instruction.I64_AND)
			.i64(MASK_63)
			.op(Instruction.I64_ADD)
			.i64(63)
			.op(Instruction.I64_SHR_U);
		a.op(Instruction.I64_OR);
		a.end();
		return a.finish();
	}

	/**
	 * {@code _schub_g (k i32) -> (i64 g1, i64 g0)}: the 126-bit power-of-ten
	 * approximation, recomposed from the sparse table (see {@link SchubfachTables}).
	 * @param umulhiIdx the {@code _schub_umulhi} function index
	 * @param blobBase the linear-memory address of the {@link SchubfachTables#blob()}
	 * @return the function body
	 */
	static byte[] buildGBody(int umulhiIdx, int blobBase) {
		int sparseBase = blobBase + SchubfachTables.SPARSE_OFF;
		int pow5Base = blobBase + SchubfachTables.POW5_OFF;
		int corrBase = blobBase + SchubfachTables.CORR_OFF;
		Asm a = new Asm();
		a.locals(new int[] { 3, Type.I32.code() }, new int[] { 10, Type.I64.code() });
		// i32: 1=idx 2=m/j 3=addr ; i64: 4=gsLo 5=gsHi 6=p5 7=lo 8=mid 9=hi 10=t/shift
		// 11=gLo 12=gHi 13=d
		a.get(0).i32(-SchubfachTables.K_MIN).op(Instruction.I32_ADD).set(1);
		a.get(1)
			.i32(SchubfachTables.PERIOD - 1)
			.op(Instruction.I32_ADD)
			.i32(SchubfachTables.PERIOD)
			.op(Instruction.I32_DIV_U)
			.set(2); // m
		a.get(2).i32(16).op(Instruction.I32_MUL).i32(sparseBase).op(Instruction.I32_ADD).set(3);
		a.get(3).load64(0).set(4);
		a.get(3).load64(8).set(5);
		// j = m*PERIOD - idx
		a.get(2).i32(SchubfachTables.PERIOD).op(Instruction.I32_MUL).get(1).op(Instruction.I32_SUB).set(2);
		a.get(2).i32(8).op(Instruction.I32_MUL).i32(pow5Base).op(Instruction.I32_ADD).load64(0).set(6);
		// lo = gsLo * p5 ; mid0 = umulhi(gsLo, p5) ; t = gsHi * p5 ; hi = umulhi(gsHi,
		// p5)
		a.get(4).get(6).op(Instruction.I64_MUL).set(7);
		a.get(4).get(6).call(umulhiIdx).set(8);
		a.get(5).get(6).op(Instruction.I64_MUL).set(10);
		a.get(5).get(6).call(umulhiIdx).set(9);
		// mid = mid0 + t, carry into hi
		a.get(8).get(10).op(Instruction.I64_ADD).set(11); // newMid in 11
		a.get(11).get(8).op(Instruction.I64_LT_U).ifVoid();
		a.get(9).i64(1).op(Instruction.I64_ADD).set(9);
		a.end();
		a.get(11).set(8);
		// shift = (hi == 0 ? 2 - clz(mid) : 66 - clz(hi))
		a.get(9).op(Instruction.I64_EQZ).ifI64();
		a.i64(2).get(8).op(Instruction.I64_CLZ).op(Instruction.I64_SUB);
		a.elseOp();
		a.i64(66).get(9).op(Instruction.I64_CLZ).op(Instruction.I64_SUB);
		a.end();
		a.set(10);
		a.get(10).op(Instruction.I64_EQZ).ifVoid();
		a.get(7).set(11);
		a.get(8).set(12);
		a.elseOp();
		// gLo = (lo >>> shift) | (mid << (64 - shift))
		a.get(7).get(10).op(Instruction.I64_SHR_U);
		a.get(8).i64(64).get(10).op(Instruction.I64_SUB).op(Instruction.I64_SHL);
		a.op(Instruction.I64_OR).set(11);
		// gHi = (mid >>> shift) | (hi << (64 - shift))
		a.get(8).get(10).op(Instruction.I64_SHR_U);
		a.get(9).i64(64).get(10).op(Instruction.I64_SUB).op(Instruction.I64_SHL);
		a.op(Instruction.I64_OR).set(12);
		a.end();
		// d = ((corr byte >> ((idx & 3) * 2)) & 3) - 1, sign-extended to i64
		a.get(1).i32(2).op(Instruction.I32_SHR_U).i32(corrBase).op(Instruction.I32_ADD).load8u();
		a.get(1).i32(3).op(Instruction.I32_AND).i32(1).op(Instruction.I32_SHL).op(Instruction.I32_SHR_U);
		a.i32(3).op(Instruction.I32_AND).i32(1).op(Instruction.I32_SUB).op(Instruction.I64_EXTEND_S_I32).set(13);
		// newLo = gLo + d (in 10)
		a.get(11).get(13).op(Instruction.I64_ADD).set(10);
		// if d == 1 && newLo == 0: gHi++
		a.get(13).i64(1).op(Instruction.I64_EQ).get(10).op(Instruction.I64_EQZ).op(Instruction.I32_AND).ifVoid();
		a.get(12).i64(1).op(Instruction.I64_ADD).set(12);
		a.end();
		// if d == -1 && gLo == 0: gHi--
		a.get(13).i64(-1).op(Instruction.I64_EQ).get(11).op(Instruction.I64_EQZ).op(Instruction.I32_AND).ifVoid();
		a.get(12).i64(1).op(Instruction.I64_SUB).set(12);
		a.end();
		a.get(10).set(11);
		// g1 = (gHi << 1) | (gLo >>> 63) ; g0 = gLo & MASK63
		a.get(12).i64(1).op(Instruction.I64_SHL).get(11).i64(63).op(Instruction.I64_SHR_U).op(Instruction.I64_OR);
		a.get(11).i64(MASK_63).op(Instruction.I64_AND);
		a.end();
		return a.finish();
	}

	/**
	 * {@code _f64_dec (v f64) -> (i64 digits, i32 k)}: the shortest decimal of a finite
	 * positive nonzero double, trailing zeros stripped.
	 * @param schubGIdx the {@code _schub_g} function index
	 * @param ropIdx the {@code _schub_rop} function index
	 * @return the function body
	 */
	static byte[] buildF64DecBody(int schubGIdx, int ropIdx) {
		Asm a = new Asm();
		a.locals(new int[] { 13, Type.I64.code() }, new int[] { 7, Type.I32.code() });
		// i64: 1=t 2=c 3=f 4=cb 5=cbl 6=g1 7=g0 8=vb 9=vbl 10=vbr 11=s 12=tt 13=tmp
		// i32: 14=bq 15=q 16=kk 17=h 18=out 19=dk 20=mq/scratch
		a.blockVoid(); // $strip
		a.get(0).op(Instruction.I64_REINTERPRET_F64).set(13);
		a.get(13).i64((1L << 52) - 1).op(Instruction.I64_AND).set(1);
		a.get(13)
			.i64(52)
			.op(Instruction.I64_SHR_U)
			.op(Instruction.I32_WRAP_I64)
			.i32(0x7FF)
			.op(Instruction.I32_AND)
			.set(14);
		a.get(14).ifVoid();
		{
			// normal: mq = 1075 - bq, c = 2^52 | t
			a.i32(1075).get(14).op(Instruction.I32_SUB).set(20);
			a.i64(1L << 52).get(1).op(Instruction.I64_OR).set(2);
			a.get(20)
				.i32(0)
				.op(Instruction.I32_GT_S)
				.get(20)
				.i32(53)
				.op(Instruction.I32_LT_S)
				.op(Instruction.I32_AND)
				.ifVoid();
			{
				a.get(2).get(20).op(Instruction.I64_EXTEND_U_I32).op(Instruction.I64_SHR_U).set(3);
				a.get(3)
					.get(20)
					.op(Instruction.I64_EXTEND_U_I32)
					.op(Instruction.I64_SHL)
					.get(2)
					.op(Instruction.I64_EQ)
					.ifVoid();
				// small integer: f = c >> mq, kk = 0 (locals are zero-initialized)
				a.br(3); // -> $strip
				a.end();
			}
			a.end();
			a.i32(0).get(20).op(Instruction.I32_SUB).set(15);
		}
		a.elseOp();
		{
			// subnormal: q = -1074; c = t (or 10t when t < C_TINY)
			a.i32(-1074).set(15);
			a.get(1).i64(3).op(Instruction.I64_LT_U).ifVoid();
			a.get(1).i64(10).op(Instruction.I64_MUL).set(2);
			a.i32(-1).set(19);
			a.elseOp();
			a.get(1).set(2);
			a.end();
		}
		a.end();
		emitToDecimalTail(a, schubGIdx, ropIdx, 1L << 52, -1074, 2, false);
		a.end(); // $strip
		emitStripAndReturn(a);
		return a.finish();
	}

	/**
	 * {@code _f32_dec (v f32) -> (i64 digits, i32 k)}: the single-float width; the same g
	 * table with {@code g = g1(k) + 1} and a 64-bit rounded odd product.
	 * @param schubGIdx the {@code _schub_g} function index
	 * @param umulhiIdx the {@code _schub_umulhi} function index
	 * @return the function body
	 */
	static byte[] buildF32DecBody(int schubGIdx, int umulhiIdx) {
		Asm a = new Asm();
		a.locals(new int[] { 13, Type.I64.code() }, new int[] { 7, Type.I32.code() });
		// same slot roles as _f64_dec
		a.blockVoid(); // $strip
		a.get(0).op(Instruction.I32_REINTERPRET_F32).set(20); // bits (i32) in 20
		a.get(20).i32((1 << 23) - 1).op(Instruction.I32_AND).op(Instruction.I64_EXTEND_U_I32).set(1);
		a.get(20).i32(23).op(Instruction.I32_SHR_U).i32(0xFF).op(Instruction.I32_AND).set(14);
		a.get(14).ifVoid();
		{
			a.i32(150).get(14).op(Instruction.I32_SUB).set(20);
			a.i64(1 << 23).get(1).op(Instruction.I64_OR).set(2);
			a.get(20)
				.i32(0)
				.op(Instruction.I32_GT_S)
				.get(20)
				.i32(24)
				.op(Instruction.I32_LT_S)
				.op(Instruction.I32_AND)
				.ifVoid();
			{
				a.get(2).get(20).op(Instruction.I64_EXTEND_U_I32).op(Instruction.I64_SHR_U).set(3);
				a.get(3)
					.get(20)
					.op(Instruction.I64_EXTEND_U_I32)
					.op(Instruction.I64_SHL)
					.get(2)
					.op(Instruction.I64_EQ)
					.ifVoid();
				a.br(3);
				a.end();
			}
			a.end();
			a.i32(0).get(20).op(Instruction.I32_SUB).set(15);
		}
		a.elseOp();
		{
			a.i32(-149).set(15);
			a.get(1).i64(8).op(Instruction.I64_LT_U).ifVoid();
			a.get(1).i64(10).op(Instruction.I64_MUL).set(2);
			a.i32(-1).set(19);
			a.elseOp();
			a.get(1).set(2);
			a.end();
		}
		a.end();
		emitToDecimalTail(a, schubGIdx, umulhiIdx, 1 << 23, -149, 33, true);
		a.end(); // $strip
		emitStripAndReturn(a);
		return a.finish();
	}

	// The shared toDecimal tail: from locals c(2), q(15), dk(19) computes f(3), kk(16).
	// Falls through to the caller's $strip end after setting f and kk; the sp10 and
	// uin/win exits br out of the enclosing $strip block (depth accounting below
	// assumes this code is emitted directly inside that block).
	private static void emitToDecimalTail(Asm a, int schubGIdx, int mulIdx, long cMin, int qMin, int hOffset,
			boolean single) {
		// out = c & 1
		a.get(2).i64(1).op(Instruction.I64_AND).op(Instruction.I32_WRAP_I64).set(18);
		// cb = c << 2
		a.get(2).i64(2).op(Instruction.I64_SHL).set(4);
		// regular spacing?
		a.get(2)
			.i64(cMin)
			.op(Instruction.I64_NE)
			.get(15)
			.i32(qMin)
			.op(Instruction.I32_EQ)
			.op(Instruction.I32_OR)
			.ifVoid();
		a.get(4).i64(2).op(Instruction.I64_SUB).set(5);
		// kk = flog10pow2(q)
		a.get(15)
			.op(Instruction.I64_EXTEND_S_I32)
			.i64(661_971_961_083L)
			.op(Instruction.I64_MUL)
			.i64(41)
			.op(Instruction.I64_SHR_S)
			.op(Instruction.I32_WRAP_I64)
			.set(16);
		a.elseOp();
		a.get(4).i64(1).op(Instruction.I64_SUB).set(5);
		// kk = flog10threeQuartersPow2(q)
		a.get(15)
			.op(Instruction.I64_EXTEND_S_I32)
			.i64(661_971_961_083L)
			.op(Instruction.I64_MUL)
			.i64(-274_743_187_321L)
			.op(Instruction.I64_ADD)
			.i64(41)
			.op(Instruction.I64_SHR_S)
			.op(Instruction.I32_WRAP_I64)
			.set(16);
		a.end();
		// h = q + flog2pow10(-kk) + hOffset
		a.i32(0)
			.get(16)
			.op(Instruction.I32_SUB)
			.op(Instruction.I64_EXTEND_S_I32)
			.i64(913_124_641_741L)
			.op(Instruction.I64_MUL)
			.i64(38)
			.op(Instruction.I64_SHR_S)
			.op(Instruction.I32_WRAP_I64)
			.get(15)
			.op(Instruction.I32_ADD)
			.i32(hOffset)
			.op(Instruction.I32_ADD)
			.set(17);
		// g
		a.get(16).call(schubGIdx);
		if (single) {
			// g = g1 + 1; g0 unused
			a.op(Instruction.DROP);
			a.i64(1).op(Instruction.I64_ADD).set(6);
		}
		else {
			a.set(7).set(6);
		}
		// vb / vbl / vbr
		emitRop(a, mulIdx, single, w -> w.get(4));
		a.set(8);
		emitRop(a, mulIdx, single, w -> w.get(5));
		a.set(9);
		emitRop(a, mulIdx, single, w -> w.get(4).i64(2).op(Instruction.I64_ADD));
		a.set(10);
		// s = vb >> 2
		a.get(8).i64(2).op(Instruction.I64_SHR_U).set(11);
		a.get(11).i64(100).op(Instruction.I64_GE_U).ifVoid();
		{
			// sp10 = (s / 10) * 10 (in 12)
			a.get(11).i64(10).op(Instruction.I64_DIV_U).i64(10).op(Instruction.I64_MUL).set(12);
			// upin = vbl + out <= sp10 << 2 (in 20)
			a.get(9)
				.get(18)
				.op(Instruction.I64_EXTEND_U_I32)
				.op(Instruction.I64_ADD)
				.get(12)
				.i64(2)
				.op(Instruction.I64_SHL)
				.op(Instruction.I64_LE_U)
				.set(20);
			// wpin = (tp10 << 2) + out <= vbr
			a.get(12)
				.i64(10)
				.op(Instruction.I64_ADD)
				.i64(2)
				.op(Instruction.I64_SHL)
				.get(18)
				.op(Instruction.I64_EXTEND_U_I32)
				.op(Instruction.I64_ADD)
				.get(10)
				.op(Instruction.I64_LE_U);
			a.get(20).op(Instruction.I32_NE).ifVoid();
			// f = upin ? sp10 : sp10 + 10 -- and k is WITHOUT dk on this exit
			a.get(12).get(12).i64(10).op(Instruction.I64_ADD).get(20).op(Instruction.SELECT).set(3);
			a.br(2); // -> $strip
			a.end();
		}
		a.end();
		// tt = s + 1
		a.get(11).i64(1).op(Instruction.I64_ADD).set(12);
		// uin (in 20)
		a.get(9)
			.get(18)
			.op(Instruction.I64_EXTEND_U_I32)
			.op(Instruction.I64_ADD)
			.get(11)
			.i64(2)
			.op(Instruction.I64_SHL)
			.op(Instruction.I64_LE_U)
			.set(20);
		// win
		a.get(12)
			.i64(2)
			.op(Instruction.I64_SHL)
			.get(18)
			.op(Instruction.I64_EXTEND_U_I32)
			.op(Instruction.I64_ADD)
			.get(10)
			.op(Instruction.I64_LE_U);
		a.get(20).op(Instruction.I32_NE).ifVoid();
		a.get(11).get(12).get(20).op(Instruction.SELECT).set(3);
		a.get(16).get(19).op(Instruction.I32_ADD).set(16);
		a.br(1); // -> $strip
		a.end();
		// cmp = vb - ((s + tt) << 1)
		a.get(8).get(11).get(12).op(Instruction.I64_ADD).i64(1).op(Instruction.I64_SHL).op(Instruction.I64_SUB).set(13);
		// f = (cmp < 0 | (cmp == 0 & s even)) ? s : tt
		a.get(11).get(12);
		a.get(13).i64(0).op(Instruction.I64_LT_S);
		a.get(13)
			.op(Instruction.I64_EQZ)
			.get(11)
			.i64(1)
			.op(Instruction.I64_AND)
			.op(Instruction.I64_EQZ)
			.op(Instruction.I32_AND);
		a.op(Instruction.I32_OR).op(Instruction.SELECT).set(3);
		a.get(16).get(19).op(Instruction.I32_ADD).set(16);
	}

	// vb-family rounded odd product: double calls _schub_rop(g1, g0, cp); single
	// inlines the 64-bit variant using _schub_umulhi(g, cp).
	private static void emitRop(Asm a, int mulIdx, boolean single, java.util.function.Consumer<Asm> pushCbVariant) {
		if (!single) {
			a.get(6).get(7);
			pushCbVariant.accept(a);
			a.get(17).op(Instruction.I64_EXTEND_S_I32).op(Instruction.I64_SHL);
			a.call(mulIdx);
			return;
		}
		// x1 = umulhi(g, cp) (in 13)
		a.get(6);
		pushCbVariant.accept(a);
		a.get(17).op(Instruction.I64_EXTEND_S_I32).op(Instruction.I64_SHL);
		a.call(mulIdx).set(13);
		// (x1 >>> 31) | (((x1 & M32) + M32) >>> 32)
		a.get(13).i64(31).op(Instruction.I64_SHR_U);
		a.get(13)
			.i64(MASK_32)
			.op(Instruction.I64_AND)
			.i64(MASK_32)
			.op(Instruction.I64_ADD)
			.i64(32)
			.op(Instruction.I64_SHR_U);
		a.op(Instruction.I64_OR);
	}

	// strip trailing zeros of f(3) into kk(16), then push (f, kk) as the results.
	private static void emitStripAndReturn(Asm a) {
		a.blockVoid();
		a.loopVoid();
		a.get(3).i64(10).op(Instruction.I64_REM_U).i64(0).op(Instruction.I64_NE).brIf(1);
		a.get(3).i64(10).op(Instruction.I64_DIV_U).set(3);
		a.get(16).i32(1).op(Instruction.I32_ADD).set(16);
		a.br(0);
		a.end();
		a.end();
		a.get(3).get(16);
		a.end();
	}

	/**
	 * {@code _dec_fmt (digits i64, k i32, scratch i32, out i32) -> i32}: renders
	 * {@code digits * 10^k} (digits without trailing zeros, value positive) as the
	 * FloatText spelling at {@code out}, using {@code scratch} for the digit bytes (up to
	 * 17), and returns the text length (at most 24 bytes).
	 * @return the function body
	 */
	static byte[] buildDecFmtBody() {
		Asm a = new Asm();
		a.locals(new int[] { 5, Type.I32.code() }, new int[] { 1, Type.I64.code() });
		// params: 0=digits 1=k 2=scratch 3=out ; locals: 4=n 5=e 6=pos 7=cnt 8=src
		// 9=t(i64)
		// n = digit count
		a.i32(1).set(4);
		a.get(0).set(9);
		a.blockVoid();
		a.loopVoid();
		a.get(9).i64(10).op(Instruction.I64_LT_U).brIf(1);
		a.get(9).i64(10).op(Instruction.I64_DIV_U).set(9);
		a.get(4).i32(1).op(Instruction.I32_ADD).set(4);
		a.br(0);
		a.end();
		a.end();
		// scratch[0..n) = digits, MSD first
		a.get(0).set(9);
		a.get(4).set(7);
		a.blockVoid();
		a.loopVoid();
		a.get(7).op(Instruction.I32_EQZ).brIf(1);
		a.get(7).i32(1).op(Instruction.I32_SUB).set(7);
		a.get(2).get(7).op(Instruction.I32_ADD);
		a.get(9).i64(10).op(Instruction.I64_REM_U).op(Instruction.I32_WRAP_I64).i32('0').op(Instruction.I32_ADD);
		a.store8();
		a.get(9).i64(10).op(Instruction.I64_DIV_U).set(9);
		a.br(0);
		a.end();
		a.end();
		// e = k + n - 1
		a.get(1).get(4).op(Instruction.I32_ADD).i32(1).op(Instruction.I32_SUB).set(5);
		// pos = 0 (locals are zero-initialized)
		a.get(5)
			.i32(0)
			.op(Instruction.I32_GE_S)
			.get(5)
			.i32(7)
			.op(Instruction.I32_LT_S)
			.op(Instruction.I32_AND)
			.ifVoid();
		{
			// plain notation
			a.get(4).get(5).i32(1).op(Instruction.I32_ADD).op(Instruction.I32_LE_S).ifVoid();
			{
				// all n digits, then (e + 1 - n) zeros, then ".0"
				a.i32(0).set(8);
				a.get(4).set(7);
				emitCopyLoop(a);
				a.get(5).i32(1).op(Instruction.I32_ADD).get(4).op(Instruction.I32_SUB).set(7);
				emitZeros(a);
				emitPutChar(a, '.');
				emitPutChar(a, '0');
			}
			a.elseOp();
			{
				// first e+1 digits, '.', the remaining n-e-1
				a.i32(0).set(8);
				a.get(5).i32(1).op(Instruction.I32_ADD).set(7);
				emitCopyLoop(a);
				emitPutChar(a, '.');
				a.get(5).i32(1).op(Instruction.I32_ADD).set(8);
				a.get(4).get(5).op(Instruction.I32_SUB).i32(1).op(Instruction.I32_SUB).set(7);
				emitCopyLoop(a);
			}
			a.end();
		}
		a.elseOp();
		{
			a.get(5)
				.i32(-3)
				.op(Instruction.I32_GE_S)
				.get(5)
				.i32(0)
				.op(Instruction.I32_LT_S)
				.op(Instruction.I32_AND)
				.ifVoid();
			{
				// 0.0...0digits
				emitPutChar(a, '0');
				emitPutChar(a, '.');
				a.i32(-1).get(5).op(Instruction.I32_SUB).set(7);
				emitZeros(a);
				a.i32(0).set(8);
				a.get(4).set(7);
				emitCopyLoop(a);
			}
			a.elseOp();
			{
				// scientific: d1 '.' (rest | '0') 'e' exponent
				a.i32(0).set(8);
				a.i32(1).set(7);
				emitCopyLoop(a);
				emitPutChar(a, '.');
				a.get(4).i32(1).op(Instruction.I32_EQ).ifVoid();
				emitPutChar(a, '0');
				a.elseOp();
				a.i32(1).set(8);
				a.get(4).i32(1).op(Instruction.I32_SUB).set(7);
				emitCopyLoop(a);
				a.end();
				emitPutChar(a, 'e');
				a.get(5).i32(0).op(Instruction.I32_LT_S).ifVoid();
				emitPutChar(a, '-');
				a.i32(0).get(5).op(Instruction.I32_SUB).set(5);
				a.end();
				// up to 3 exponent digits, MSD first
				a.get(5).i32(100).op(Instruction.I32_GE_S).ifVoid();
				a.get(3).get(6).op(Instruction.I32_ADD);
				a.get(5).i32(100).op(Instruction.I32_DIV_U).i32('0').op(Instruction.I32_ADD).store8();
				a.get(6).i32(1).op(Instruction.I32_ADD).set(6);
				a.get(5).i32(100).op(Instruction.I32_REM_U).set(5);
				a.get(3).get(6).op(Instruction.I32_ADD);
				a.get(5).i32(10).op(Instruction.I32_DIV_U).i32('0').op(Instruction.I32_ADD).store8();
				a.get(6).i32(1).op(Instruction.I32_ADD).set(6);
				a.get(5).i32(10).op(Instruction.I32_REM_U).set(5);
				a.elseOp();
				a.get(5).i32(10).op(Instruction.I32_GE_S).ifVoid();
				a.get(3).get(6).op(Instruction.I32_ADD);
				a.get(5).i32(10).op(Instruction.I32_DIV_U).i32('0').op(Instruction.I32_ADD).store8();
				a.get(6).i32(1).op(Instruction.I32_ADD).set(6);
				a.get(5).i32(10).op(Instruction.I32_REM_U).set(5);
				a.end();
				a.end();
				a.get(3).get(6).op(Instruction.I32_ADD);
				a.get(5).i32('0').op(Instruction.I32_ADD).store8();
				a.get(6).i32(1).op(Instruction.I32_ADD).set(6);
			}
			a.end();
		}
		a.end();
		a.get(6);
		a.end();
		return a.finish();
	}

	// out[pos++] = scratch[src++], cnt times (locals 6=pos, 7=cnt, 8=src)
	private static void emitCopyLoop(Asm a) {
		a.blockVoid();
		a.loopVoid();
		a.get(7).op(Instruction.I32_EQZ).brIf(1);
		a.get(3).get(6).op(Instruction.I32_ADD);
		a.get(2).get(8).op(Instruction.I32_ADD).load8u();
		a.store8();
		a.get(6).i32(1).op(Instruction.I32_ADD).set(6);
		a.get(8).i32(1).op(Instruction.I32_ADD).set(8);
		a.get(7).i32(1).op(Instruction.I32_SUB).set(7);
		a.br(0);
		a.end();
		a.end();
	}

	// out[pos++] = '0', cnt times (local 7 = cnt)
	private static void emitZeros(Asm a) {
		a.blockVoid();
		a.loopVoid();
		a.get(7).i32(0).op(Instruction.I32_LE_S).brIf(1);
		emitPutChar(a, '0');
		a.get(7).i32(1).op(Instruction.I32_SUB).set(7);
		a.br(0);
		a.end();
		a.end();
	}

	// out[pos++] = ch
	private static void emitPutChar(Asm a, char ch) {
		a.get(3).get(6).op(Instruction.I32_ADD).i32(ch).store8();
		a.get(6).i32(1).op(Instruction.I32_ADD).set(6);
	}

	/**
	 * {@code _write_dec (digits i64, k i32) -> ()}: the GC backend's writer -- renders
	 * via {@code _dec_fmt} into the print scratch buffer and sends it through
	 * {@code _write_str} (so string capture mode keeps working).
	 * @param decFmtIdx the {@code _dec_fmt} function index
	 * @param writeStrIdx the {@code _write_str} function index
	 * @param scratchBase the digit scratch buffer address (17 bytes)
	 * @param outBase the text buffer address (24 bytes)
	 * @return the function body
	 */
	static byte[] buildWriteDecBody(int decFmtIdx, int writeStrIdx, int scratchBase, int outBase) {
		Asm a = new Asm();
		a.locals(new int[] { 1, Type.I32.code() });
		// params: 0=digits 1=k ; local 2=len
		a.get(0).get(1).i32(scratchBase).i32(outBase).call(decFmtIdx).set(2);
		a.i32(outBase).get(2).call(writeStrIdx);
		a.end();
		return a.finish();
	}

	/** The tiny fluent wasm body assembler used by this builder. */
	static final class Asm {

		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		final WasmWriter w = new WasmWriter(this.out);

		/**
		 * Declares the local groups (after the params).
		 * @param groups each {@code {count, typeCode}}
		 */
		void locals(int[]... groups) {
			this.w.writeUnsignedLeb128(groups.length);
			for (int[] g : groups) {
				this.w.writeUnsignedLeb128(g[0]);
				this.w.write(g[1]);
			}
		}

		Asm get(int local) {
			this.w.write(Instruction.GET_LOCAL);
			this.w.writeUnsignedLeb128(local);
			return this;
		}

		Asm set(int local) {
			this.w.write(Instruction.SET_LOCAL);
			this.w.writeUnsignedLeb128(local);
			return this;
		}

		Asm i32(int v) {
			this.w.write(Instruction.I32_CONST);
			this.w.writeSignedLeb128(v);
			return this;
		}

		Asm i64(long v) {
			this.w.write(Instruction.I64_CONST);
			this.w.writeSignedLeb128(v);
			return this;
		}

		Asm op(int opcode) {
			this.w.write(opcode);
			return this;
		}

		Asm call(int funcIdx) {
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(funcIdx);
			return this;
		}

		Asm load64(int offset) {
			this.w.write(Instruction.I64_LOAD);
			this.w.writeUnsignedLeb128(0);
			this.w.writeUnsignedLeb128(offset);
			return this;
		}

		Asm load8u() {
			this.w.write(Instruction.I32_LOAD8_U);
			this.w.writeUnsignedLeb128(0);
			this.w.writeUnsignedLeb128(0);
			return this;
		}

		Asm load8uNoOff() {
			return load8u();
		}

		Asm store8() {
			this.w.write(Instruction.I32_STORE8);
			this.w.writeUnsignedLeb128(0);
			this.w.writeUnsignedLeb128(0);
			return this;
		}

		Asm ifVoid() {
			this.w.write(Instruction.IF, 0x40);
			return this;
		}

		Asm ifI64() {
			this.w.write(Instruction.IF, Type.I64.code());
			return this;
		}

		Asm elseOp() {
			this.w.write(Instruction.ELSE);
			return this;
		}

		Asm blockVoid() {
			this.w.write(Instruction.BLOCK, 0x40);
			return this;
		}

		Asm loopVoid() {
			this.w.write(Instruction.LOOP, 0x40);
			return this;
		}

		Asm br(int depth) {
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(depth);
			return this;
		}

		Asm brIf(int depth) {
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(depth);
			return this;
		}

		Asm end() {
			this.w.write(Instruction.END);
			return this;
		}

		byte[] finish() {
			return this.out.toByteArray();
		}

	}

}
