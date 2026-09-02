package am.ik.rontolisp.codegen.wasm;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Emits the float arm of {@code mod} / {@code rem}: the EXACT Common Lisp float
 * remainder, shared byte-for-byte by the wasm-GC runtime helpers
 * ({@link WasmRatioRuntimeBuilder}'s {@code _rat_rem} / {@code _rat_mod}) and by
 * {@link NoGcWasmCompiler}, so the two backends cannot drift apart.
 *
 * <p>
 * CLHS defines {@code rem} as the remainder of {@code truncate} and {@code mod} as the
 * remainder of {@code floor}, both {@code a - b*q} for a quotient that "always represents
 * a mathematical integer". That quantity is exact and, for float operands, always
 * representable: {@code |a - b*q| < |b|} and the difference of two representable
 * multiples of the same exponent scale is itself representable, which is why IEEE 754's
 * {@code fmod} is defined as an exact operation with no rounding. Evaluating the formula
 * as written in f64 is NOT exact -- {@code a/b} rounds, so above 2^53 {@code trunc(a/b)}
 * is not the mathematical quotient and {@code b*q} rounds again; {@code (rem 1d18 7.0)}
 * came out {@code 0.0} where the true remainder is {@code 1.0} (10^6 = 1 mod 7). The loop
 * below computes the exact value instead, matching Java's {@code DREM} (the interpreter
 * and the JVM backend) at every magnitude.
 *
 * <p>
 * The reduction is the textbook exact {@code fmod}: scale {@code |b|} up by powers of two
 * while it still fits under {@code |a|}, then walk back down subtracting where it fits.
 * Every step is exact -- doubling and halving a power-of-two multiple of {@code |b|} is
 * exact, and each subtraction runs under the invariant {@code d <= x < 2d}, which is
 * Sterbenz's lemma -- and it terminates in {@code exponent(a) - exponent(b) + 1} steps.
 *
 * <p>
 * An INFINITE divisor is the other end of the same formula and needs no loop: the
 * truncating quotient of a finite dividend is the integer zero, so the remainder is
 * {@code a - b*0 = a}, which is also IEEE 754's definition of {@code fmod(x, inf)}.
 * Computing {@code a - b*trunc(a/b)} in f64 instead multiplies {@code inf * 0.0} and
 * answers NaN. {@code mod}'s divisor-sign correction then turns the opposite-sign case
 * into {@code a + b}, i.e. an infinity -- the limit of {@code floor}'s quotient being -1
 * rather than 0 there. SBCL signals on an infinite operand, so there is no upstream
 * oracle; this is the value the CLHS formula denotes, and it is what the interpreter and
 * the JVM already answer.
 *
 * <p>
 * The sign of a ZERO remainder is todo-652's rule and survives unchanged: {@code -0.0}
 * only when the dividend is {@code -0.0} and the divisor is positive, {@code +0.0}
 * otherwise. It used to fall out of adding {@code +0.0} to the f64 quotient; with an
 * exact reduction there is no quotient to coerce, so the three-step re-derivation is
 * spelled out below exactly as the JVM's {@code _frem} spells it.
 */
final class WasmFmodRuntimeBuilder {

	private WasmFmodRuntimeBuilder() {
	}

	/**
	 * Emits the exact float remainder, leaving one f64 on the stack.
	 * @param w the writer to emit into
	 * @param mod true for {@code mod} (the result takes the divisor's sign), false for
	 * {@code rem} (the dividend's)
	 * @param a the local holding the dividend (f64), already set
	 * @param b the local holding the divisor (f64), already set
	 * @param x a scratch f64 local (the running remainder magnitude)
	 * @param d a scratch f64 local (the scaled divisor magnitude)
	 * @param r a scratch f64 local (the result being corrected)
	 */
	static void emitRemainder(WasmWriter w, boolean mod, int a, int b, int x, int d, int r) {
		// x = |a|, d = |b|
		local(w, Instruction.GET_LOCAL, a);
		w.write(Instruction.F64_ABS);
		local(w, Instruction.SET_LOCAL, x);
		local(w, Instruction.GET_LOCAL, b);
		w.write(Instruction.F64_ABS);
		local(w, Instruction.SET_LOCAL, d);

		// if (|a| < |b|) the remainder IS the dividend -- which also covers the
		// infinite divisor over a finite dividend (the quotient is the integer zero).
		local(w, Instruction.GET_LOCAL, x);
		local(w, Instruction.GET_LOCAL, d);
		w.write(Instruction.F64_LT);
		w.write(Instruction.IF, Type.F64);
		local(w, Instruction.GET_LOCAL, a);
		w.write(Instruction.ELSE);

		// Everything the reduction cannot handle is a NaN: either operand NaN (the
		// comparison above is unordered, so they arrive here), an infinite dividend,
		// and a zero divisor -- the same non-trapping float policy (/ 1.0 0.0) follows.
		local(w, Instruction.GET_LOCAL, x);
		local(w, Instruction.GET_LOCAL, x);
		w.write(Instruction.F64_NE);
		local(w, Instruction.GET_LOCAL, d);
		local(w, Instruction.GET_LOCAL, d);
		w.write(Instruction.F64_NE);
		w.write(Instruction.I32_OR);
		local(w, Instruction.GET_LOCAL, x);
		w.write(Instruction.F64_CONST);
		w.writeF64(Double.POSITIVE_INFINITY);
		w.write(Instruction.F64_EQ);
		local(w, Instruction.GET_LOCAL, d);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, Type.F64);
		w.write(Instruction.F64_CONST);
		w.writeF64(Double.NaN);
		w.write(Instruction.ELSE);

		// Both finite, 0 < |b| <= |a|. Scale d up while d*2 still fits under x; d*2 is
		// exact until it overflows to an infinity, which fails the test and stops the
		// loop, so no scaling step can round.
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		local(w, Instruction.GET_LOCAL, d);
		w.write(Instruction.F64_CONST);
		w.writeF64(2.0);
		w.write(Instruction.F64_MUL);
		local(w, Instruction.GET_LOCAL, x);
		w.write(Instruction.F64_GT);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		local(w, Instruction.GET_LOCAL, d);
		w.write(Instruction.F64_CONST);
		w.writeF64(2.0);
		w.write(Instruction.F64_MUL);
		local(w, Instruction.SET_LOCAL, d);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		// Walk back down: subtract d where it fits and halve, until d is back at |b|.
		// The invariant d <= x < 2d makes every subtraction exact (Sterbenz), and each
		// halving retraces a value the scaling loop already produced, so it is exact
		// too -- even for a subnormal divisor.
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		local(w, Instruction.GET_LOCAL, x);
		local(w, Instruction.GET_LOCAL, d);
		w.write(Instruction.F64_GE);
		w.write(Instruction.IF, 0x40);
		local(w, Instruction.GET_LOCAL, x);
		local(w, Instruction.GET_LOCAL, d);
		w.write(Instruction.F64_SUB);
		local(w, Instruction.SET_LOCAL, x);
		w.write(Instruction.END);
		local(w, Instruction.GET_LOCAL, d);
		local(w, Instruction.GET_LOCAL, b);
		w.write(Instruction.F64_ABS);
		w.write(Instruction.F64_LE);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		local(w, Instruction.GET_LOCAL, d);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.5);
		w.write(Instruction.F64_MUL);
		local(w, Instruction.SET_LOCAL, d);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		// IEEE fmod's magnitude with the dividend's sign; the zero correction below
		// then re-derives the one case where CLHS's exact-integer quotient disagrees.
		local(w, Instruction.GET_LOCAL, x);
		local(w, Instruction.GET_LOCAL, a);
		w.write(Instruction.F64_COPYSIGN);

		w.write(Instruction.END); // end NaN if
		w.write(Instruction.END); // end |a| < |b| if
		local(w, Instruction.SET_LOCAL, r);

		// The sign of a zero (todo-652): a - b*q with an integer q, so a zero dividend
		// leaves b*(+0) carrying the DIVISOR's sign and a nonzero dividend cancels
		// against itself as IEEE's +0.0. `a - copysign(0.0, b)` is exactly that.
		local(w, Instruction.GET_LOCAL, r);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_NE); // a NaN remainder is != 0 and passes through
		w.write(Instruction.IF, Type.F64);
		local(w, Instruction.GET_LOCAL, r);
		w.write(Instruction.ELSE);
		local(w, Instruction.GET_LOCAL, a);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_NE);
		w.write(Instruction.IF, Type.F64);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.ELSE);
		local(w, Instruction.GET_LOCAL, a);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		local(w, Instruction.GET_LOCAL, b);
		w.write(Instruction.F64_COPYSIGN);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.END);
		w.write(Instruction.END);
		if (!mod) {
			return;
		}

		// mod = rem plus the divisor when the two have opposite signs and the remainder
		// is not a zero, so mod and rem share one zero. An infinite divisor reaches here
		// with r = a, and a + (-+inf) is the infinity the table wants. The signs are
		// compared directly rather than through `r*b < 0`: that product UNDERFLOWS to a
		// zero when both operands are tiny and the correction then never fires --
		// (mod -1.2345678e-296 1d-300) answered the negative remainder. A NaN remainder
		// is not below zero, so it lands on whichever arm leaves it a NaN.
		local(w, Instruction.SET_LOCAL, r);
		local(w, Instruction.GET_LOCAL, r);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_NE);
		local(w, Instruction.GET_LOCAL, r);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_LT);
		local(w, Instruction.GET_LOCAL, b);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_LT);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, Type.F64);
		local(w, Instruction.GET_LOCAL, r);
		local(w, Instruction.GET_LOCAL, b);
		w.write(Instruction.F64_ADD);
		w.write(Instruction.ELSE);
		local(w, Instruction.GET_LOCAL, r);
		w.write(Instruction.END);
	}

	private static void local(WasmWriter w, int opcode, int index) {
		w.write(opcode);
		w.writeUnsignedLeb128(index);
	}

}
