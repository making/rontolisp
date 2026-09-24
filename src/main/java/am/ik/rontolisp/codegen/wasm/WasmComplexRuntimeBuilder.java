package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds WASM bytecode for the complex-number runtime helpers: the canonicalizing
 * constructor {@code _ccomplex} and the pairwise arithmetic folds
 * {@code _cadd}/{@code _csub}/{@code _cmul}/{@code _cdiv} plus the signed-zero-exact
 * {@code _cneg}. A complex value is a {@code TYPE_COMPLEX} struct (an {@code i32} tag
 * plus two real-part refs, an integer, ratio or float each, never a nested complex).
 *
 * <p>
 * The folds accept real-or-complex operands (a real counts as a zero-imagined complex,
 * like the interpreter's {@code complexReal}/{@code complexImag}) and finish through
 * {@code _ccomplex}, so exact parts stay exact through the shared {@code _rat_*} helpers
 * and a float anywhere coerces the whole step to float -- the same funnels real
 * arithmetic uses, so mistyped parts fail the way mistyped real operands do. Float parts
 * are coerced through the ONE shared {@code _as_f64}
 * ({@code .kb/wasm-shared-coercion.md}); no site inlines the numeric ladder.
 */
final class WasmComplexRuntimeBuilder {

	private WasmComplexRuntimeBuilder() {
	}

	// _ccomplex((ref null eq) re, (ref null eq) im) -> (ref null eq): the canonical
	// value for two real parts, like LispComplex.valueOf. A nested complex part (or
	// any other non-real) lands in _type_err_num ("Expected number, got: <prin1>",
	// catchable in EH mode); a float anywhere coerces both parts through _as_f64 and
	// always builds (a float zero never demotes); otherwise a rational-zero
	// imaginary part demotes to the real itself.
	static byte[] buildComplexBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: 0=re, 1=im (params), 2-3=f64 scratch for the float path.
		w.write(1);
		w.write(2);
		w.write(Type.F64);

		// A nested complex part is rejected, like SBCL (the interpreter's
		// requireReal reports the first offending part). A void guard: the call
		// never returns, so nothing may be left for the function result.
		emitIsComplex(w, 0);
		emitIsComplex(w, 1);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		emitIsComplex(w, 0);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		getLocal(w, 0);
		w.write(Instruction.ELSE);
		getLocal(w, 1);
		w.write(Instruction.END);
		call(w, WasmLispCompiler.FUNC_TYPE_ERR_NUM);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);

		// Float path: either part a float coerces both through _as_f64 (a non-number
		// part lands in _type_err_num with itself as the culprit) and always builds.
		emitIsFloat(w, 0);
		emitIsFloat(w, 1);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_AS_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_AS_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);
		emitComplexTag(w);
		getLocal(w, 2);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		getLocal(w, 3);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.ELSE);

		// Exact path: both parts must be exact integers or ratios now (a ratio is
		// normalized, so it is never zero; an exact integer zero is always an i31,
		// so the demotion test below is complete).
		emitIsReal(w, 0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_TYPE_ERR_NUM);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		emitIsReal(w, 1);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_TYPE_ERR_NUM);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);

		// A rational-zero imaginary part demotes to the real itself.
		emitIsI31Zero(w, 1);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		getLocal(w, 0);
		w.write(Instruction.ELSE);
		emitComplexTag(w);
		getLocal(w, 0);
		getLocal(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _cadd/_csub((ref null eq) a, (ref null eq) b) -> (ref null eq): part-wise
	// _rat_add/_rat_sub of the real and imaginary parts, canonicalized. A real
	// operand counts as a zero-imagined complex (its imaginary part is the i31 zero,
	// which the _rat_* float path absorbs, so contagion needs no rung here).
	static byte[] buildAddBody() {
		return buildLinearBody(WasmLispCompiler.FUNC_RAT_ADD);
	}

	static byte[] buildSubBody() {
		return buildLinearBody(WasmLispCompiler.FUNC_RAT_SUB);
	}

	private static byte[] buildLinearBody(int ratFunc) {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: 0=a, 1=b (params), 2=re, 3=im.
		w.write(1);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());

		emitComplexReal(w, 0);
		emitComplexReal(w, 1);
		call(w, ratFunc);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		emitComplexImag(w, 0);
		emitComplexImag(w, 1);
		call(w, ratFunc);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);
		getLocal(w, 2);
		getLocal(w, 3);
		call(w, WasmLispCompiler.FUNC_C_COMPLEX);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _cmul((ref null eq) a, (ref null eq) b) -> (ref null eq): (ac-bd) + (ad+bc)i
	// over the exact _rat_* helpers, canonicalized.
	static byte[] buildMulBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: 0=a, 1=b (params), 2=ra, 3=ia, 4=rb, 5=ib, 6=re, 7=im.
		w.write(1);
		w.write(6);
		w.writeRefType(true, Type.EQ.code());

		emitComplexReal(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		emitComplexImag(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);
		emitComplexReal(w, 1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4);
		emitComplexImag(w, 1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(5);
		// re = ra*rb - ia*ib
		getLocal(w, 2);
		getLocal(w, 4);
		call(w, WasmLispCompiler.FUNC_RAT_MUL);
		getLocal(w, 3);
		getLocal(w, 5);
		call(w, WasmLispCompiler.FUNC_RAT_MUL);
		call(w, WasmLispCompiler.FUNC_RAT_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(6);
		// im = ra*ib + ia*rb
		getLocal(w, 2);
		getLocal(w, 5);
		call(w, WasmLispCompiler.FUNC_RAT_MUL);
		getLocal(w, 3);
		getLocal(w, 4);
		call(w, WasmLispCompiler.FUNC_RAT_MUL);
		call(w, WasmLispCompiler.FUNC_RAT_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(7);
		getLocal(w, 6);
		getLocal(w, 7);
		call(w, WasmLispCompiler.FUNC_C_COMPLEX);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _cdiv((ref null eq) a, (ref null eq) b) -> (ref null eq): (a+bi)/(c+di). The
	// EXACT arm divides by the real denominator c^2+d^2 -- the interpreter's
	// exactDivComplex, where rationals neither round nor overflow -- and a zero
	// divisor fails inside _rat_div exactly the way a real (/ x 0) does. A FLOAT part
	// anywhere takes Smith's fold instead, the interpreter's smithDivide (see there
	// for why: the denominator form overflows where the operands do not, and a real
	// divisor must cost ONE rounding per part, not three). The JVM twin is
	// JvmComplexRuntimeBuilder.buildDiv's float tail; all three must agree.
	static byte[] buildDivBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: 0=a, 1=b (params), 2=ra, 3=ia, 4=rb, 5=ib, 6=denom, 7=re, 8=im,
		// then the float arm's raw parts 9=a, 10=b, 11=c, 12=d, 13=r, 14=den.
		w.write(2);
		w.write(7);
		w.writeRefType(true, Type.EQ.code());
		w.write(6);
		w.write(Type.F64);

		// Neither operand a complex: a plain real division, which _rat_div answers
		// exactly -- without the c^2+d^2 denominator's two extra roundings. The arm
		// is reachable because a complex-capable site only knows at RUN time whether
		// it holds a complex: (log n base) divides two logarithms, either of which
		// may have stayed real, and this is what keeps that quotient EQUAL to
		// (/ (log n) (log base)). The JVM twin (_cdiv's own head) is the same arm.
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		getLocal(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 0);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_RAT_DIV);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		emitComplexReal(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		emitComplexImag(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);
		emitComplexReal(w, 1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4);
		emitComplexImag(w, 1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(5);

		// A float part anywhere coerces all four through the ONE shared _as_f64 and
		// takes Smith's fold. The whole arm returns, so the exact code below never
		// sees a float and stays the plain _rat_* denominator form.
		emitIsFloat(w, 2);
		emitIsFloat(w, 3);
		w.write(Instruction.I32_OR);
		emitIsFloat(w, 4);
		w.write(Instruction.I32_OR);
		emitIsFloat(w, 5);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		for (int part = 0; part < 4; part++) {
			getLocal(w, 2 + part);
			call(w, WasmLispCompiler.FUNC_AS_F64);
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(9 + part);
		}
		// |c| >= |d| ? -- f64.ge answers 0 for a NaN operand, which takes the
		// mirrored arm, exactly what Java's >= does in smithDivide.
		getLocal(w, 11);
		w.write(Instruction.F64_ABS);
		getLocal(w, 12);
		w.write(Instruction.F64_ABS);
		w.write(Instruction.F64_GE);
		w.write(Instruction.IF, 0x40);
		// r = d/c, den = c + d*r, re = (a + b*r)/den, im = (b - a*r)/den. A REAL
		// divisor lands here with d zero, so both parts are ONE division.
		getLocal(w, 12);
		getLocal(w, 11);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(13);
		getLocal(w, 11);
		getLocal(w, 12);
		getLocal(w, 13);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(14);
		getLocal(w, 9);
		getLocal(w, 10);
		getLocal(w, 13);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_ADD);
		getLocal(w, 14);
		w.write(Instruction.F64_DIV);
		boxF64(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(7);
		getLocal(w, 10);
		getLocal(w, 9);
		getLocal(w, 13);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_SUB);
		getLocal(w, 14);
		w.write(Instruction.F64_DIV);
		boxF64(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(8);
		w.write(Instruction.ELSE);
		// r = c/d, den = c*r + d, re = (a*r + b)/den, im = (b*r - a)/den.
		getLocal(w, 11);
		getLocal(w, 12);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(13);
		getLocal(w, 11);
		getLocal(w, 13);
		w.write(Instruction.F64_MUL);
		getLocal(w, 12);
		w.write(Instruction.F64_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(14);
		getLocal(w, 9);
		getLocal(w, 13);
		w.write(Instruction.F64_MUL);
		getLocal(w, 10);
		w.write(Instruction.F64_ADD);
		getLocal(w, 14);
		w.write(Instruction.F64_DIV);
		boxF64(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(7);
		getLocal(w, 10);
		getLocal(w, 13);
		w.write(Instruction.F64_MUL);
		getLocal(w, 9);
		w.write(Instruction.F64_SUB);
		getLocal(w, 14);
		w.write(Instruction.F64_DIV);
		boxF64(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(8);
		w.write(Instruction.END);
		getLocal(w, 7);
		getLocal(w, 8);
		call(w, WasmLispCompiler.FUNC_C_COMPLEX);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// denom = rb*rb + ib*ib
		getLocal(w, 4);
		getLocal(w, 4);
		call(w, WasmLispCompiler.FUNC_RAT_MUL);
		getLocal(w, 5);
		getLocal(w, 5);
		call(w, WasmLispCompiler.FUNC_RAT_MUL);
		call(w, WasmLispCompiler.FUNC_RAT_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(6);
		// re = (ra*rb + ia*ib) / denom
		getLocal(w, 2);
		getLocal(w, 4);
		call(w, WasmLispCompiler.FUNC_RAT_MUL);
		getLocal(w, 3);
		getLocal(w, 5);
		call(w, WasmLispCompiler.FUNC_RAT_MUL);
		call(w, WasmLispCompiler.FUNC_RAT_ADD);
		getLocal(w, 6);
		call(w, WasmLispCompiler.FUNC_RAT_DIV);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(7);
		// im = (ia*rb - ra*ib) / denom
		getLocal(w, 3);
		getLocal(w, 4);
		call(w, WasmLispCompiler.FUNC_RAT_MUL);
		getLocal(w, 2);
		getLocal(w, 5);
		call(w, WasmLispCompiler.FUNC_RAT_MUL);
		call(w, WasmLispCompiler.FUNC_RAT_SUB);
		getLocal(w, 6);
		call(w, WasmLispCompiler.FUNC_RAT_DIV);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(8);
		getLocal(w, 7);
		getLocal(w, 8);
		call(w, WasmLispCompiler.FUNC_C_COMPLEX);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _cneg((ref null eq) a) -> (ref null eq): unary negation part-wise (a float
	// part through f64.neg, so -0.0 stays -0.0 -- a _csub-from-zero fold would
	// answer +0.0), canonicalized through _ccomplex like every other fold. A real
	// operand demotes back to itself there when exact; a float real answers a
	// float-zero-imagined complex -- the steering over-approximation both compiled
	// backends share (the JVM's _cneg ends in _ccomplex the same way).
	static byte[] buildNegBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: 0=a (param), 1=re, 2=im, 3=re', 4=im'.
		w.write(1);
		w.write(4);
		w.writeRefType(true, Type.EQ.code());

		emitComplexReal(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		emitComplexImag(w, 0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		// A float part negates through f64.neg (canonical parts are uniformly float
		// or uniformly exact, so testing the real part decides both).
		emitIsFloat(w, 1);
		w.write(Instruction.IF, 0x40);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_AS_F64);
		w.write(Instruction.F64_NEG);
		boxF64(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);
		getLocal(w, 2);
		call(w, WasmLispCompiler.FUNC_AS_F64);
		w.write(Instruction.F64_NEG);
		boxF64(w);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4);
		w.write(Instruction.ELSE);
		emitI31Zero(w);
		getLocal(w, 1);
		call(w, WasmLispCompiler.FUNC_RAT_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);
		emitI31Zero(w);
		getLocal(w, 2);
		call(w, WasmLispCompiler.FUNC_RAT_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4);
		w.write(Instruction.END);
		getLocal(w, 3);
		getLocal(w, 4);
		call(w, WasmLispCompiler.FUNC_C_COMPLEX);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _csignum((ref null eq) a) -> (ref null eq): the unit vector of a complex
	// operand (re/|z| + (im/|z|)i as floats, like the interpreter). A zero answers
	// the canonicalization of its own parts through _ccomplex (0 for exact parts,
	// #C(0.0 0.0) for float parts). Only the signum call site calls this, after
	// its own complex test, so the argument is always a complex here. The modulus
	// is scaled (m * sqrt((re/m)^2 + (im/m)^2)) so huge parts do not overflow to
	// infinity the way a naive sqrt(re^2+im^2) would.
	static byte[] buildCsignumBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: 0=a (param), 1=re, 2=im, 3=m, 4=r1, 5=r2, 6=abs (f64).
		w.write(1);
		w.write(6);
		w.write(Type.F64);

		emitComplexReal(w, 0);
		call(w, WasmLispCompiler.FUNC_AS_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		emitComplexImag(w, 0);
		call(w, WasmLispCompiler.FUNC_AS_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		// m = max(|re|, |im|).
		getLocal(w, 1);
		w.write(Instruction.F64_ABS);
		getLocal(w, 2);
		w.write(Instruction.F64_ABS);
		w.write(Instruction.F64_MAX);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);
		// A zero modulus takes the canonicalize-own-parts exit.
		getLocal(w, 3);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		emitComplexReal(w, 0);
		emitComplexImag(w, 0);
		call(w, WasmLispCompiler.FUNC_C_COMPLEX);
		w.write(Instruction.ELSE);
		// r1 = re/m, r2 = im/m, abs = m * sqrt(r1^2 + r2^2).
		getLocal(w, 1);
		getLocal(w, 3);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(4);
		getLocal(w, 2);
		getLocal(w, 3);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(5);
		getLocal(w, 3);
		getLocal(w, 4);
		getLocal(w, 4);
		w.write(Instruction.F64_MUL);
		getLocal(w, 5);
		getLocal(w, 5);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_ADD);
		w.write(Instruction.F64_SQRT);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(6);
		// _ccomplex(box(re/abs), box(im/abs)): float parts, so it always builds.
		getLocal(w, 1);
		getLocal(w, 6);
		w.write(Instruction.F64_DIV);
		boxF64(w);
		getLocal(w, 2);
		getLocal(w, 6);
		w.write(Instruction.F64_DIV);
		boxF64(w);
		call(w, WasmLispCompiler.FUNC_C_COMPLEX);
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// Pushes the real part of local[slot]: field 0 for a complex, the value itself
	// otherwise (a non-number is left for the caller's _rat_* funnel to complain
	// about, like the interpreter leaves it for its funnel).
	static void emitComplexReal(WasmWriter w, int slot) {
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.ELSE);
		getLocal(w, slot);
		w.write(Instruction.END);
	}

	// Pushes the imaginary part of local[slot]: field 1 for a complex, the i31 zero
	// otherwise (a float real takes the same zero -- the _rat_* float path absorbs
	// it -- and a non-number is left for the caller's funnel).
	static void emitComplexImag(WasmWriter w, int slot) {
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.ELSE);
		emitI31Zero(w);
		w.write(Instruction.END);
	}

	// Pushes `local[slot] is a TYPE_COMPLEX` as an i32.
	static void emitIsComplex(WasmWriter w, int slot) {
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_COMPLEX);
	}

	// Pushes `local[slot] is a TYPE_FLOAT` as an i32.
	private static void emitIsFloat(WasmWriter w, int slot) {
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_FLOAT);
	}

	// Pushes `local[slot] is a real number` (an exact integer, a ratio or a float --
	// never a complex) as an i32.
	static void emitIsReal(WasmWriter w, int slot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.I32_OR);
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_OR);
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.I32_OR);
		getLocal(w, slot);
		refTestType(w, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.I32_OR);
	}

	// Pushes `local[slot] is the i31 zero` as an i32 (a normalized exact zero is
	// always an i31: _int_new demotes bignums, _rat_new demotes ratios).
	private static void emitIsI31Zero(WasmWriter w, int slot) {
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
	}

	// Pushes the TYPE_COMPLEX tag (always zero): the structural ballast that keeps
	// the type distinct from TYPE_FARRAY under canonicalization (see
	// WasmLispCompiler.TYPE_COMPLEX).
	private static void emitComplexTag(WasmWriter w) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
	}

	// Pushes the i31 zero.
	private static void emitI31Zero(WasmWriter w) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	// Boxes the f64 on the stack into a TYPE_FLOAT struct.
	private static void boxF64(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
	}

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void refTestType(WasmWriter w, int typeIndex) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(typeIndex);
	}

	private static void call(WasmWriter w, int funcIndex) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(funcIndex);
	}

}
