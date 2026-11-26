/*
 * The fdlibm functions in this file are Sun's C fdlibm 5.3 (https://www.netlib.org/fdlibm/),
 * rewritten into the subset WasmFdlibmRuntimeBuilder compiles. The notices of the files
 * they come from are kept here.
 *
 * e_exp.c, e_pow.c:
 * ====================================================
 * Copyright (C) 2004 by Sun Microsystems, Inc. All rights reserved.
 *
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 *
 * k_tan.c:
 * ====================================================
 * Copyright 2004 Sun Microsystems, Inc.  All Rights Reserved.
 *
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 *
 * s_expm1.c, e_log.c, s_log1p.c, e_log10.c, s_sin.c, s_cos.c, s_tan.c, k_sin.c,
 * k_cos.c, e_rem_pio2.c, k_rem_pio2.c, e_asin.c, e_acos.c, s_atan.c, e_atan2.c,
 * e_sinh.c, e_cosh.c, s_tanh.c, e_hypot.c, s_cbrt.c, e_remainder.c, e_fmod.c,
 * s_scalbn.c:
 * ====================================================
 * Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 *
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 *
 * Third-party: fdlibm 5.3 (Sun's notices above). Notice: THIRD-PARTY-NOTICES.txt, "fdlibm".
 */
package am.ik.rontolisp.codegen.wasm;

/**
 * The source of every function of {@code WasmFdlibmRuntimeBuilder}, by the name it is
 * declared under, in the subset that builder compiles.
 *
 * <p>
 * Provenance ({@code .kb/java-frontend-provenance.md}): the fdlibm functions are written
 * from Sun's C fdlibm 5.3, file by file, and from nothing else. A function keeps the C
 * file's constants as the decimal literals it spells them with, its variable names, its
 * operand order and its grouping, which is what makes the bits {@code StrictMath}'s. What
 * changes is what the subset has no form for:
 * <ul>
 * <li>{@code switch} is an if-chain, {@code for} a {@code while}, {@code goto recompute}
 * a {@code while (1)} with {@code continue};</li>
 * <li>a conversion C makes silently is a written cast;</li>
 * <li>an {@code unsigned} operand is an {@code int} taken through {@code >>>},
 * {@code ult} or {@code ule} where its signedness shows;</li>
 * <li>{@code scalbn} by a power of two that keeps the value normal is a multiplication by
 * {@code pow2(n)}, and pow's last scaling is the {@code scalbn} here;</li>
 * <li>the reduction's arrays ({@code y}, {@code tx}, {@code f}, {@code q}, {@code fq},
 * {@code iq}) and its two integer tables live in linear memory, and
 * {@code __kernel_rem_pio2} is specialized to the one precision its caller asks for
 * ({@code prec = 2}).</li>
 * </ul>
 * The bit-level operations ({@code ulp} through {@code get_exponent}) are no fdlibm: each
 * is written here from the {@code StrictMath} method's specified behaviour.
 */
final class WasmFdlibmSources {

	private WasmFdlibmSources() {
	}

	/** {@code two_over_pi} of {@code e_rem_pio2.c}: the 24-bit chunks of 2/pi. */
	static final int[] TWO_OVER_PI = { 0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62, 0x95993C, 0x439041,
			0xFE5163, 0xABDEBB, 0xC561B7, 0x246E3A, 0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129,
			0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C, 0x7026B4, 0x5F7E41, 0x3991D6, 0x398353, 0x39F49C, 0x845F8B,
			0xBDF928, 0x3B1FF8, 0x97FFDE, 0x05980F, 0xEF2F11, 0x8B5A0A, 0x6D1F6D, 0x367ECF, 0x27CB09, 0xB74F46,
			0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5, 0xF17B3D, 0x0739F7, 0x8A5292, 0xEA6BFB, 0x5FB11F, 0x8D5D08,
			0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3, 0x91615E, 0xE61B08, 0x659985, 0x5F14A0,
			0x68408D, 0xFFD880, 0x4D7327, 0x310606, 0x1556CA, 0x73A8C9, 0x60E27B, 0xC08C6B };

	/** {@code npio2_hw} of {@code e_rem_pio2.c}: the high words of n*pi/2, n = 1..32. */
	static final int[] NPIO2_HW = { 0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, 0x4025FDBB,
			0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C, 0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB,
			0x403AB41B, 0x403C463A, 0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C, 0x4043A28C,
			0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB, 0x404858EB, 0x404921FB };

	/**
	 * The source of one function.
	 * @param name the name the function is declared under
	 * @return its source text
	 */
	static String of(String name) {
		return switch (name) {
			case "exp" -> EXP;
			case "expm1" -> EXPM1;
			case "log" -> LOG;
			case "log1p" -> LOG1P;
			case "sin" -> SIN;
			case "cos" -> COS;
			case "tan" -> TAN;
			case "k_sin" -> K_SIN;
			case "k_cos" -> K_COS;
			case "k_tan" -> K_TAN;
			case "rem_pio2" -> REM_PIO2;
			case "krem" -> K_REM_PIO2;
			case "asin" -> ASIN;
			case "acos" -> ACOS;
			case "atan" -> ATAN;
			case "atan2" -> ATAN2;
			case "sinh" -> SINH;
			case "cosh" -> COSH;
			case "tanh" -> TANH;
			case "pow" -> POW;
			case "hypot" -> HYPOT;
			case "log10" -> LOG10;
			case "cbrt" -> CBRT;
			case "remainder" -> IEEE_REMAINDER;
			case "fmod" -> FMOD;
			case "ulp" -> ULP;
			case "signum" -> SIGNUM;
			case "next_up" -> NEXT_UP;
			case "next_down" -> NEXT_DOWN;
			case "next_after" -> NEXT_AFTER;
			case "copy_sign" -> COPY_SIGN;
			case "get_exponent" -> GET_EXPONENT;
			case "scalbn" -> SCALB;
			default -> throw new IllegalArgumentException("no fdlibm source named " + name);
		};
	}

	// e_exp.c: __ieee754_exp
	private static final String EXP = """
			const double one = 1.0;
			const double[] halF = {0.5, -0.5};
			const double huge = 1.0e+300;
			const double twom1000 = 9.33263618503218878990e-302;
			const double o_threshold = 7.09782712893383973096e+02;
			const double u_threshold = -7.45133219101941108420e+02;
			const double[] ln2HI = {6.93147180369123816490e-01, -6.93147180369123816490e-01};
			const double[] ln2LO = {1.90821492927058770002e-10, -1.90821492927058770002e-10};
			const double invln2 = 1.44269504088896338700e+00;
			const double P1 = 1.66666666666666019037e-01;
			const double P2 = -2.77777777770155933842e-03;
			const double P3 = 6.61375632143793436117e-05;
			const double P4 = -1.65339022054652515390e-06;
			const double P5 = 4.13813679705723846039e-08;
			double exp(double x) {
			  double y, hi, lo, c, t;
			  int k, xsb, hx;
			  hx = __HI(x);
			  xsb = (hx >>> 31) & 1;
			  hx &= 0x7fffffff;
			  if (hx >= 0x40862E42) {
			    if (hx >= 0x7ff00000) {
			      if (((hx & 0xfffff) | __LO(x)) != 0) return x + x;
			      else return (xsb == 0) ? x : 0.0;
			    }
			    if (x > o_threshold) return huge * huge;
			    if (x < u_threshold) return twom1000 * twom1000;
			  }
			  if (hx > 0x3fd62e42) {
			    if (hx < 0x3FF0A2B2) {
			      hi = x - ln2HI[xsb];
			      lo = ln2LO[xsb];
			      k = 1 - xsb - xsb;
			    } else {
			      k = (int) (invln2 * x + halF[xsb]);
			      t = (double) k;
			      hi = x - t * ln2HI[0];
			      lo = t * ln2LO[0];
			    }
			    x = hi - lo;
			  }
			  else if (hx < 0x3e300000) {
			    if (huge + x > one) return one + x;
			  }
			  else k = 0;
			  t = x * x;
			  c = x - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
			  if (k == 0) return one - ((x * c) / (c - 2.0) - x);
			  else y = one - ((lo - (x * c) / (2.0 - c)) - hi);
			  if (k >= -1021) {
			    __HI(y) += (k << 20);
			    return y;
			  } else {
			    __HI(y) += ((k + 1000) << 20);
			    return y * twom1000;
			  }
			}
			""";

	// s_expm1.c: expm1
	private static final String EXPM1 = """
			const double one = 1.0;
			const double huge = 1.0e+300;
			const double tiny = 1.0e-300;
			const double o_threshold = 7.09782712893383973096e+02;
			const double ln2_hi = 6.93147180369123816490e-01;
			const double ln2_lo = 1.90821492927058770002e-10;
			const double invln2 = 1.44269504088896338700e+00;
			const double Q1 = -3.33333333333331316428e-02;
			const double Q2 = 1.58730158725481460165e-03;
			const double Q3 = -7.93650757867487942473e-05;
			const double Q4 = 4.00821782732936239552e-06;
			const double Q5 = -2.01099218183624371326e-07;
			double expm1(double x) {
			  double y, hi, lo, c, t, e, hxs, hfx, r1;
			  int k, xsb, hx;
			  hx = __HI(x);
			  xsb = hx & 0x80000000;
			  if (xsb == 0) y = x; else y = -x;
			  hx &= 0x7fffffff;
			  if (hx >= 0x4043687A) {
			    if (hx >= 0x40862E42) {
			      if (hx >= 0x7ff00000) {
			        if (((hx & 0xfffff) | __LO(x)) != 0) return x + x;
			        else return (xsb == 0) ? x : -1.0;
			      }
			      if (x > o_threshold) return huge * huge;
			    }
			    if (xsb != 0) {
			      if (x + tiny < 0.0) return tiny - one;
			    }
			  }
			  if (hx > 0x3fd62e42) {
			    if (hx < 0x3FF0A2B2) {
			      if (xsb == 0) {
			        hi = x - ln2_hi;
			        lo = ln2_lo;
			        k = 1;
			      } else {
			        hi = x + ln2_hi;
			        lo = -ln2_lo;
			        k = -1;
			      }
			    } else {
			      k = (int) (invln2 * x + ((xsb == 0) ? 0.5 : -0.5));
			      t = (double) k;
			      hi = x - t * ln2_hi;
			      lo = t * ln2_lo;
			    }
			    x = hi - lo;
			    c = (hi - x) - lo;
			  }
			  else if (hx < 0x3c900000) {
			    t = huge + x;
			    return x - (t - (huge + x));
			  }
			  else k = 0;
			  hfx = 0.5 * x;
			  hxs = x * hfx;
			  r1 = one + hxs * (Q1 + hxs * (Q2 + hxs * (Q3 + hxs * (Q4 + hxs * Q5))));
			  t = 3.0 - r1 * hfx;
			  e = hxs * ((r1 - t) / (6.0 - x * t));
			  if (k == 0) return x - (x * e - hxs);
			  else {
			    e = (x * (e - c) - c);
			    e -= hxs;
			    if (k == -1) return 0.5 * (x - e) - 0.5;
			    if (k == 1)
			      if (x < -0.25) return -2.0 * (e - (x + 0.5));
			      else return one + 2.0 * (x - e);
			    if (k <= -2 || k > 56) {
			      y = one - (e - x);
			      __HI(y) += (k << 20);
			      return y - one;
			    }
			    t = one;
			    if (k < 20) {
			      __HI(t) = 0x3ff00000 - (0x200000 >> k);
			      y = t - (e - x);
			      __HI(y) += (k << 20);
			    } else {
			      __HI(t) = ((0x3ff - k) << 20);
			      y = x - (e + t);
			      y += one;
			      __HI(y) += (k << 20);
			    }
			  }
			  return y;
			}
			""";

	// e_log.c: __ieee754_log
	private static final String LOG = """
			const double ln2_hi = 6.93147180369123816490e-01;
			const double ln2_lo = 1.90821492927058770002e-10;
			const double two54 = 1.80143985094819840000e+16;
			const double Lg1 = 6.666666666666735130e-01;
			const double Lg2 = 3.999999999940941908e-01;
			const double Lg3 = 2.857142874366239149e-01;
			const double Lg4 = 2.222219843214978396e-01;
			const double Lg5 = 1.818357216161805012e-01;
			const double Lg6 = 1.531383769920937332e-01;
			const double Lg7 = 1.479819860511658591e-01;
			const double zero = 0.0;
			double log(double x) {
			  double hfsq, f, s, z, R, w, t1, t2, dk;
			  int k, hx, i, j, lx;
			  hx = __HI(x);
			  lx = __LO(x);
			  k = 0;
			  if (hx < 0x00100000) {
			    if (((hx & 0x7fffffff) | lx) == 0) return -two54 / zero;
			    if (hx < 0) return (x - x) / zero;
			    k -= 54;
			    x *= two54;
			    hx = __HI(x);
			  }
			  if (hx >= 0x7ff00000) return x + x;
			  k += (hx >> 20) - 1023;
			  hx &= 0x000fffff;
			  i = (hx + 0x95f64) & 0x100000;
			  __HI(x) = hx | (i ^ 0x3ff00000);
			  k += (i >> 20);
			  f = x - 1.0;
			  if ((0x000fffff & (2 + hx)) < 3) {
			    if (f == zero)
			      if (k == 0) return zero;
			      else {
			        dk = (double) k;
			        return dk * ln2_hi + dk * ln2_lo;
			      }
			    R = f * f * (0.5 - 0.33333333333333333 * f);
			    if (k == 0) return f - R;
			    else {
			      dk = (double) k;
			      return dk * ln2_hi - ((R - dk * ln2_lo) - f);
			    }
			  }
			  s = f / (2.0 + f);
			  dk = (double) k;
			  z = s * s;
			  i = hx - 0x6147a;
			  w = z * z;
			  j = 0x6b851 - hx;
			  t1 = w * (Lg2 + w * (Lg4 + w * Lg6));
			  t2 = z * (Lg1 + w * (Lg3 + w * (Lg5 + w * Lg7)));
			  i |= j;
			  R = t2 + t1;
			  if (i > 0) {
			    hfsq = 0.5 * f * f;
			    if (k == 0) return f - (hfsq - s * (hfsq + R));
			    else return dk * ln2_hi - ((hfsq - (s * (hfsq + R) + dk * ln2_lo)) - f);
			  } else {
			    if (k == 0) return f - s * (f - R);
			    else return dk * ln2_hi - ((s * (f - R) - dk * ln2_lo) - f);
			  }
			}
			""";

	// s_log1p.c: log1p
	private static final String LOG1P = """
			const double ln2_hi = 6.93147180369123816490e-01;
			const double ln2_lo = 1.90821492927058770002e-10;
			const double two54 = 1.80143985094819840000e+16;
			const double Lp1 = 6.666666666666735130e-01;
			const double Lp2 = 3.999999999940941908e-01;
			const double Lp3 = 2.857142874366239149e-01;
			const double Lp4 = 2.222219843214978396e-01;
			const double Lp5 = 1.818357216161805012e-01;
			const double Lp6 = 1.531383769920937332e-01;
			const double Lp7 = 1.479819860511658591e-01;
			const double zero = 0.0;
			double log1p(double x) {
			  double hfsq, f, c, s, z, R, u;
			  int k, hx, hu, ax;
			  hx = __HI(x);
			  ax = hx & 0x7fffffff;
			  k = 1;
			  if (hx < 0x3FDA827A) {
			    if (ax >= 0x3ff00000) {
			      if (x == -1.0) return -two54 / zero;
			      else return (x - x) / (x - x);
			    }
			    if (ax < 0x3e200000) {
			      if (two54 + x > zero && ax < 0x3c900000) return x;
			      else return x - x * x * 0.5;
			    }
			    if (hx > 0 || hx <= 0xbfd2bec3) {
			      k = 0;
			      f = x;
			      hu = 1;
			    }
			  }
			  if (hx >= 0x7ff00000) return x + x;
			  if (k != 0) {
			    if (hx < 0x43400000) {
			      u = 1.0 + x;
			      hu = __HI(u);
			      k = (hu >> 20) - 1023;
			      c = (k > 0) ? 1.0 - (u - x) : x - (u - 1.0);
			      c /= u;
			    } else {
			      u = x;
			      hu = __HI(u);
			      k = (hu >> 20) - 1023;
			      c = 0.0;
			    }
			    hu &= 0x000fffff;
			    if (hu < 0x6a09e) {
			      __HI(u) = hu | 0x3ff00000;
			    } else {
			      k += 1;
			      __HI(u) = hu | 0x3fe00000;
			      hu = (0x00100000 - hu) >> 2;
			    }
			    f = u - 1.0;
			  }
			  hfsq = 0.5 * f * f;
			  if (hu == 0) {
			    if (f == zero)
			      if (k == 0) return zero;
			      else {
			        c += (double) k * ln2_lo;
			        return (double) k * ln2_hi + c;
			      }
			    R = hfsq * (1.0 - 0.66666666666666666 * f);
			    if (k == 0) return f - R;
			    else return (double) k * ln2_hi - ((R - ((double) k * ln2_lo + c)) - f);
			  }
			  s = f / (2.0 + f);
			  z = s * s;
			  R = z * (Lp1 + z * (Lp2 + z * (Lp3 + z * (Lp4 + z * (Lp5 + z * (Lp6 + z * Lp7))))));
			  if (k == 0) return f - (hfsq - s * (hfsq + R));
			  else return (double) k * ln2_hi - ((hfsq - (s * (hfsq + R) + ((double) k * ln2_lo + c))) - f);
			}
			""";

	// e_log10.c: __ieee754_log10
	private static final String LOG10 = """
			const double two54 = 1.80143985094819840000e+16;
			const double ivln10 = 4.34294481903251816668e-01;
			const double log10_2hi = 3.01029995663611771306e-01;
			const double log10_2lo = 3.69423907715893078616e-13;
			const double zero = 0.0;
			double log10(double x) {
			  double y, z;
			  int i, k, hx, lx;
			  hx = __HI(x);
			  lx = __LO(x);
			  k = 0;
			  if (hx < 0x00100000) {
			    if (((hx & 0x7fffffff) | lx) == 0) return -two54 / zero;
			    if (hx < 0) return (x - x) / zero;
			    k -= 54;
			    x *= two54;
			    hx = __HI(x);
			  }
			  if (hx >= 0x7ff00000) return x + x;
			  k += (hx >> 20) - 1023;
			  i = (k & 0x80000000) >>> 31;
			  hx = (hx & 0x000fffff) | ((0x3ff - i) << 20);
			  y = (double) (k + i);
			  __HI(x) = hx;
			  z = y * log10_2lo + ivln10 * log(x);
			  return z + y * log10_2hi;
			}
			""";

	// s_sin.c: sin
	private static final String SIN = """
			double sin(double x) {
			  double z = 0.0;
			  int n, ix;
			  ix = __HI(x);
			  ix &= 0x7fffffff;
			  if (ix <= 0x3fe921fb) return k_sin(x, z, 0);
			  else if (ix >= 0x7ff00000) return x - x;
			  else {
			    n = rem_pio2(x) & 3;
			    if (n == 0) return k_sin(y[0], y[1], 1);
			    if (n == 1) return k_cos(y[0], y[1]);
			    if (n == 2) return -k_sin(y[0], y[1], 1);
			    return -k_cos(y[0], y[1]);
			  }
			}
			""";

	// s_cos.c: cos
	private static final String COS = """
			double cos(double x) {
			  double z = 0.0;
			  int n, ix;
			  ix = __HI(x);
			  ix &= 0x7fffffff;
			  if (ix <= 0x3fe921fb) return k_cos(x, z);
			  else if (ix >= 0x7ff00000) return x - x;
			  else {
			    n = rem_pio2(x) & 3;
			    if (n == 0) return k_cos(y[0], y[1]);
			    if (n == 1) return -k_sin(y[0], y[1], 1);
			    if (n == 2) return -k_cos(y[0], y[1]);
			    return k_sin(y[0], y[1], 1);
			  }
			}
			""";

	// s_tan.c: tan
	private static final String TAN = """
			double tan(double x) {
			  double z = 0.0;
			  int n, ix;
			  ix = __HI(x);
			  ix &= 0x7fffffff;
			  if (ix <= 0x3fe921fb) return k_tan(x, z, 1);
			  else if (ix >= 0x7ff00000) return x - x;
			  else {
			    n = rem_pio2(x);
			    return k_tan(y[0], y[1], 1 - ((n & 1) << 1));
			  }
			}
			""";

	// k_sin.c: __kernel_sin
	private static final String K_SIN = """
			const double half = 5.00000000000000000000e-01;
			const double S1 = -1.66666666666666324348e-01;
			const double S2 = 8.33333333332248946124e-03;
			const double S3 = -1.98412698298579493134e-04;
			const double S4 = 2.75573137070700676789e-06;
			const double S5 = -2.50507602534068634195e-08;
			const double S6 = 1.58969099521155010221e-10;
			double k_sin(double x, double y, int iy) {
			  double z, r, v;
			  int ix;
			  ix = __HI(x) & 0x7fffffff;
			  if (ix < 0x3e400000) {
			    if ((int) x == 0) return x;
			  }
			  z = x * x;
			  v = z * x;
			  r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
			  if (iy == 0) return x + v * (S1 + z * r);
			  else return x - ((z * (half * y - v * r) - y) - v * S1);
			}
			""";

	// k_cos.c: __kernel_cos
	private static final String K_COS = """
			const double one = 1.00000000000000000000e+00;
			const double C1 = 4.16666666666666019037e-02;
			const double C2 = -1.38888888888741095749e-03;
			const double C3 = 2.48015872894767294178e-05;
			const double C4 = -2.75573143513906633035e-07;
			const double C5 = 2.08757232129817482790e-09;
			const double C6 = -1.13596475577881948265e-11;
			double k_cos(double x, double y) {
			  double a, hz, z, r, qx;
			  int ix;
			  ix = __HI(x) & 0x7fffffff;
			  if (ix < 0x3e400000) {
			    if (((int) x) == 0) return one;
			  }
			  z = x * x;
			  r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
			  if (ix < 0x3FD33333) return one - (0.5 * z - (z * r - x * y));
			  else {
			    if (ix > 0x3fe90000) {
			      qx = 0.28125;
			    } else {
			      __HI(qx) = ix - 0x00200000;
			      __LO(qx) = 0;
			    }
			    hz = 0.5 * z - qx;
			    a = one - qx;
			    return a - (hz - (z * r - x * y));
			  }
			}
			""";

	// k_tan.c: __kernel_tan
	private static final String K_TAN = """
			const double[] T = {
			  3.33333333333334091986e-01,
			  1.33333333333201242699e-01,
			  5.39682539762260521377e-02,
			  2.18694882948595424599e-02,
			  8.86323982359930005737e-03,
			  3.59207910759131235356e-03,
			  1.45620945432529025516e-03,
			  5.88041240820264096874e-04,
			  2.46463134818469906812e-04,
			  7.81794442939557092300e-05,
			  7.14072491382608190305e-05,
			  -1.85586374855275456654e-05,
			  2.59073051863633712884e-05
			};
			const double one = 1.00000000000000000000e+00;
			const double pio4 = 7.85398163397448278999e-01;
			const double pio4lo = 3.06161699786838301793e-17;
			double k_tan(double x, double y, int iy) {
			  double z, r, v, w, s, a, t;
			  int ix, hx;
			  hx = __HI(x);
			  ix = hx & 0x7fffffff;
			  if (ix < 0x3e300000) {
			    if ((int) x == 0) {
			      if (((ix | __LO(x)) | (iy + 1)) == 0) return one / fabs(x);
			      else {
			        if (iy == 1) return x;
			        else {
			          w = x + y;
			          z = w;
			          __LO(z) = 0;
			          v = y - (z - x);
			          a = -one / w;
			          t = a;
			          __LO(t) = 0;
			          s = one + t * z;
			          return t + a * (s + t * v);
			        }
			      }
			    }
			  }
			  if (ix >= 0x3FE59428) {
			    if (hx < 0) {
			      x = -x;
			      y = -y;
			    }
			    z = pio4 - x;
			    w = pio4lo - y;
			    x = z + w;
			    y = 0.0;
			  }
			  z = x * x;
			  w = z * z;
			  r = T[1] + w * (T[3] + w * (T[5] + w * (T[7] + w * (T[9] + w * T[11]))));
			  v = z * (T[2] + w * (T[4] + w * (T[6] + w * (T[8] + w * (T[10] + w * T[12])))));
			  s = z * x;
			  r = y + z * (s * (r + v) + y);
			  r += T[0] * s;
			  w = x + r;
			  if (ix >= 0x3FE59428) {
			    v = (double) iy;
			    return (double) (1 - ((hx >> 30) & 2)) * (v - 2.0 * (x - (w * w / (w + v) - r)));
			  }
			  if (iy == 1) return w;
			  else {
			    z = w;
			    __LO(z) = 0;
			    v = r - (z - x);
			    a = -1.0 / w;
			    t = a;
			    __LO(t) = 0;
			    s = 1.0 + t * z;
			    return t + a * (s + t * v);
			  }
			}
			""";

	// e_rem_pio2.c: __ieee754_rem_pio2, answering through the y array
	private static final String REM_PIO2 = """
			const double zero = 0.00000000000000000000e+00;
			const double half = 5.00000000000000000000e-01;
			const double two24 = 1.67772160000000000000e+07;
			const double invpio2 = 6.36619772367581382433e-01;
			const double pio2_1 = 1.57079632673412561417e+00;
			const double pio2_1t = 6.07710050650619224932e-11;
			const double pio2_2 = 6.07710050630396597660e-11;
			const double pio2_2t = 2.02226624879595063154e-21;
			const double pio2_3 = 2.02226624871116645580e-21;
			const double pio2_3t = 8.47842766036889956997e-32;
			int rem_pio2(double x) {
			  double z, w, t, r, fn;
			  int e0, i, j, nx, n, ix, hx;
			  hx = __HI(x);
			  ix = hx & 0x7fffffff;
			  if (ix <= 0x3fe921fb) {
			    y[0] = x;
			    y[1] = 0.0;
			    return 0;
			  }
			  if (ix < 0x4002d97c) {
			    if (hx > 0) {
			      z = x - pio2_1;
			      if (ix != 0x3ff921fb) {
			        y[0] = z - pio2_1t;
			        y[1] = (z - y[0]) - pio2_1t;
			      } else {
			        z -= pio2_2;
			        y[0] = z - pio2_2t;
			        y[1] = (z - y[0]) - pio2_2t;
			      }
			      return 1;
			    } else {
			      z = x + pio2_1;
			      if (ix != 0x3ff921fb) {
			        y[0] = z + pio2_1t;
			        y[1] = (z - y[0]) + pio2_1t;
			      } else {
			        z += pio2_2;
			        y[0] = z + pio2_2t;
			        y[1] = (z - y[0]) + pio2_2t;
			      }
			      return -1;
			    }
			  }
			  if (ix <= 0x413921fb) {
			    t = fabs(x);
			    n = (int) (t * invpio2 + half);
			    fn = (double) n;
			    r = t - fn * pio2_1;
			    w = fn * pio2_1t;
			    if (n < 32 && ix != npio2_hw[n - 1]) {
			      y[0] = r - w;
			    } else {
			      j = ix >> 20;
			      y[0] = r - w;
			      i = j - ((__HI(y[0]) >> 20) & 0x7ff);
			      if (i > 16) {
			        t = r;
			        w = fn * pio2_2;
			        r = t - w;
			        w = fn * pio2_2t - ((t - r) - w);
			        y[0] = r - w;
			        i = j - ((__HI(y[0]) >> 20) & 0x7ff);
			        if (i > 49) {
			          t = r;
			          w = fn * pio2_3;
			          r = t - w;
			          w = fn * pio2_3t - ((t - r) - w);
			          y[0] = r - w;
			        }
			      }
			    }
			    y[1] = (r - y[0]) - w;
			    if (hx < 0) {
			      y[0] = -y[0];
			      y[1] = -y[1];
			      return -n;
			    }
			    else return n;
			  }
			  if (ix >= 0x7ff00000) {
			    y[1] = x - x;
			    y[0] = y[1];
			    return 0;
			  }
			  __LO(z) = __LO(x);
			  e0 = (ix >> 20) - 1046;
			  __HI(z) = ix - (e0 << 20);
			  i = 0;
			  while (i < 2) {
			    tx[i] = (double) ((int) (z));
			    z = (z - tx[i]) * two24;
			    i += 1;
			  }
			  tx[2] = z;
			  nx = 3;
			  while (tx[nx - 1] == zero) nx -= 1;
			  n = krem(e0, nx);
			  if (hx < 0) {
			    y[0] = -y[0];
			    y[1] = -y[1];
			    return -n;
			  }
			  return n;
			}
			""";

	// k_rem_pio2.c: __kernel_rem_pio2(tx, y, e0, nx, 2, ipio2)
	private static final String K_REM_PIO2 = """
			const double[] PIo2 = {
			  1.57079625129699707031e+00,
			  7.54978941586159635335e-08,
			  5.39030252995776476554e-15,
			  3.28200341580791294123e-22,
			  1.27065575308067607349e-29,
			  1.22933308981111328932e-36,
			  2.73370053816464559624e-44,
			  2.16741683877804819444e-51
			};
			const double zero = 0.0;
			const double one = 1.0;
			const double two24 = 1.67772160000000000000e+07;
			const double twon24 = 5.96046447753906250000e-08;
			int krem(int e0, int nx) {
			  int jz, jx, jv, jp, jk, carry, n, i, j, k, m, q0, ih;
			  double z, fw;
			  // init_jk[prec] for prec = 2
			  jk = 4;
			  jp = jk;
			  jx = nx - 1;
			  jv = (e0 - 3) / 24;
			  if (jv < 0) jv = 0;
			  q0 = e0 - 24 * (jv + 1);
			  j = jv - jx;
			  m = jx + jk;
			  i = 0;
			  while (i <= m) {
			    f[i] = (j < 0) ? zero : (double) ipio2[j];
			    i += 1;
			    j += 1;
			  }
			  i = 0;
			  while (i <= jk) {
			    j = 0;
			    fw = 0.0;
			    while (j <= jx) {
			      fw += tx[j] * f[jx + i - j];
			      j += 1;
			    }
			    q[i] = fw;
			    i += 1;
			  }
			  jz = jk;
			  // recompute:
			  while (1) {
			    i = 0;
			    j = jz;
			    z = q[jz];
			    while (j > 0) {
			      fw = (double) ((int) (twon24 * z));
			      iq[i] = (int) (z - two24 * fw);
			      z = q[j - 1] + fw;
			      i += 1;
			      j -= 1;
			    }
			    z = z * pow2(q0);
			    z -= 8.0 * floor(z * 0.125);
			    n = (int) z;
			    z -= (double) n;
			    ih = 0;
			    if (q0 > 0) {
			      i = (iq[jz - 1] >> (24 - q0));
			      n += i;
			      iq[jz - 1] -= i << (24 - q0);
			      ih = iq[jz - 1] >> (23 - q0);
			    }
			    else if (q0 == 0) ih = iq[jz - 1] >> 23;
			    else if (z >= 0.5) ih = 2;
			    if (ih > 0) {
			      n += 1;
			      carry = 0;
			      i = 0;
			      while (i < jz) {
			        j = iq[i];
			        if (carry == 0) {
			          if (j != 0) {
			            carry = 1;
			            iq[i] = 0x1000000 - j;
			          }
			        } else iq[i] = 0xffffff - j;
			        i += 1;
			      }
			      if (q0 > 0) {
			        if (q0 == 1) iq[jz - 1] &= 0x7fffff;
			        else if (q0 == 2) iq[jz - 1] &= 0x3fffff;
			      }
			      if (ih == 2) {
			        z = one - z;
			        if (carry != 0) z -= pow2(q0);
			      }
			    }
			    if (z == zero) {
			      j = 0;
			      i = jz - 1;
			      while (i >= jk) {
			        j |= iq[i];
			        i -= 1;
			      }
			      if (j == 0) {
			        k = 1;
			        while (iq[jk - k] == 0) k += 1;
			        i = jz + 1;
			        while (i <= jz + k) {
			          f[jx + i] = (double) ipio2[jv + i];
			          j = 0;
			          fw = 0.0;
			          while (j <= jx) {
			            fw += tx[j] * f[jx + i - j];
			            j += 1;
			          }
			          q[i] = fw;
			          i += 1;
			        }
			        jz += k;
			        continue;
			      }
			    }
			    break;
			  }
			  if (z == 0.0) {
			    jz -= 1;
			    q0 -= 24;
			    while (iq[jz] == 0) {
			      jz -= 1;
			      q0 -= 24;
			    }
			  } else {
			    z = z * pow2(-q0);
			    if (z >= two24) {
			      fw = (double) ((int) (twon24 * z));
			      iq[jz] = (int) (z - two24 * fw);
			      jz += 1;
			      q0 += 24;
			      iq[jz] = (int) fw;
			    } else iq[jz] = (int) z;
			  }
			  fw = pow2(q0);
			  i = jz;
			  while (i >= 0) {
			    q[i] = fw * (double) iq[i];
			    fw *= twon24;
			    i -= 1;
			  }
			  i = jz;
			  while (i >= 0) {
			    fw = 0.0;
			    k = 0;
			    while (k <= jp && k <= jz - i) {
			      fw += PIo2[k] * q[i + k];
			      k += 1;
			    }
			    fq[jz - i] = fw;
			    i -= 1;
			  }
			  fw = 0.0;
			  i = jz;
			  while (i >= 0) {
			    fw += fq[i];
			    i -= 1;
			  }
			  y[0] = (ih == 0) ? fw : -fw;
			  fw = fq[0] - fw;
			  i = 1;
			  while (i <= jz) {
			    fw += fq[i];
			    i += 1;
			  }
			  y[1] = (ih == 0) ? fw : -fw;
			  return n & 7;
			}
			""";

	// e_asin.c: __ieee754_asin
	private static final String ASIN = """
			const double one = 1.00000000000000000000e+00;
			const double huge = 1.000e+300;
			const double pio2_hi = 1.57079632679489655800e+00;
			const double pio2_lo = 6.12323399573676603587e-17;
			const double pio4_hi = 7.85398163397448278999e-01;
			const double pS0 = 1.66666666666666657415e-01;
			const double pS1 = -3.25565818622400915405e-01;
			const double pS2 = 2.01212532134862925881e-01;
			const double pS3 = -4.00555345006794114027e-02;
			const double pS4 = 7.91534994289814532176e-04;
			const double pS5 = 3.47933107596021167570e-05;
			const double qS1 = -2.40339491173441421878e+00;
			const double qS2 = 2.02094576023350569471e+00;
			const double qS3 = -6.88283971605453293030e-01;
			const double qS4 = 7.70381505559019352791e-02;
			double asin(double x) {
			  double t, w, p, q, c, r, s;
			  int hx, ix;
			  hx = __HI(x);
			  ix = hx & 0x7fffffff;
			  if (ix >= 0x3ff00000) {
			    if (((ix - 0x3ff00000) | __LO(x)) == 0) return x * pio2_hi + x * pio2_lo;
			    return (x - x) / (x - x);
			  } else if (ix < 0x3fe00000) {
			    if (ix < 0x3e400000) {
			      if (huge + x > one) return x;
			    } else
			      t = x * x;
			    p = t * (pS0 + t * (pS1 + t * (pS2 + t * (pS3 + t * (pS4 + t * pS5)))));
			    q = one + t * (qS1 + t * (qS2 + t * (qS3 + t * qS4)));
			    w = p / q;
			    return x + x * w;
			  }
			  w = one - fabs(x);
			  t = w * 0.5;
			  p = t * (pS0 + t * (pS1 + t * (pS2 + t * (pS3 + t * (pS4 + t * pS5)))));
			  q = one + t * (qS1 + t * (qS2 + t * (qS3 + t * qS4)));
			  s = sqrt(t);
			  if (ix >= 0x3FEF3333) {
			    w = p / q;
			    t = pio2_hi - (2.0 * (s + s * w) - pio2_lo);
			  } else {
			    w = s;
			    __LO(w) = 0;
			    c = (t - w * w) / (s + w);
			    r = p / q;
			    p = 2.0 * s * r - (pio2_lo - 2.0 * c);
			    q = pio4_hi - 2.0 * w;
			    t = pio4_hi - (p - q);
			  }
			  if (hx > 0) return t; else return -t;
			}
			""";

	// e_acos.c: __ieee754_acos
	private static final String ACOS = """
			const double one = 1.00000000000000000000e+00;
			const double pi = 3.14159265358979311600e+00;
			const double pio2_hi = 1.57079632679489655800e+00;
			const double pio2_lo = 6.12323399573676603587e-17;
			const double pS0 = 1.66666666666666657415e-01;
			const double pS1 = -3.25565818622400915405e-01;
			const double pS2 = 2.01212532134862925881e-01;
			const double pS3 = -4.00555345006794114027e-02;
			const double pS4 = 7.91534994289814532176e-04;
			const double pS5 = 3.47933107596021167570e-05;
			const double qS1 = -2.40339491173441421878e+00;
			const double qS2 = 2.02094576023350569471e+00;
			const double qS3 = -6.88283971605453293030e-01;
			const double qS4 = 7.70381505559019352791e-02;
			double acos(double x) {
			  double z, p, q, r, w, s, c, df;
			  int hx, ix;
			  hx = __HI(x);
			  ix = hx & 0x7fffffff;
			  if (ix >= 0x3ff00000) {
			    if (((ix - 0x3ff00000) | __LO(x)) == 0) {
			      if (hx > 0) return 0.0;
			      else return pi + 2.0 * pio2_lo;
			    }
			    return (x - x) / (x - x);
			  }
			  if (ix < 0x3fe00000) {
			    if (ix <= 0x3c600000) return pio2_hi + pio2_lo;
			    z = x * x;
			    p = z * (pS0 + z * (pS1 + z * (pS2 + z * (pS3 + z * (pS4 + z * pS5)))));
			    q = one + z * (qS1 + z * (qS2 + z * (qS3 + z * qS4)));
			    r = p / q;
			    return pio2_hi - (x - (pio2_lo - x * r));
			  } else if (hx < 0) {
			    z = (one + x) * 0.5;
			    p = z * (pS0 + z * (pS1 + z * (pS2 + z * (pS3 + z * (pS4 + z * pS5)))));
			    q = one + z * (qS1 + z * (qS2 + z * (qS3 + z * qS4)));
			    s = sqrt(z);
			    r = p / q;
			    w = r * s - pio2_lo;
			    return pi - 2.0 * (s + w);
			  } else {
			    z = (one - x) * 0.5;
			    s = sqrt(z);
			    df = s;
			    __LO(df) = 0;
			    c = (z - df * df) / (s + df);
			    p = z * (pS0 + z * (pS1 + z * (pS2 + z * (pS3 + z * (pS4 + z * pS5)))));
			    q = one + z * (qS1 + z * (qS2 + z * (qS3 + z * qS4)));
			    r = p / q;
			    w = r * s + c;
			    return 2.0 * (df + w);
			  }
			}
			""";

	// s_atan.c: atan
	private static final String ATAN = """
			const double[] atanhi = {
			  4.63647609000806093515e-01,
			  7.85398163397448278999e-01,
			  9.82793723247329054082e-01,
			  1.57079632679489655800e+00
			};
			const double[] atanlo = {
			  2.26987774529616870924e-17,
			  3.06161699786838301793e-17,
			  1.39033110312309984516e-17,
			  6.12323399573676603587e-17
			};
			const double[] aT = {
			  3.33333333333329318027e-01,
			  -1.99999999998764832476e-01,
			  1.42857142725034663711e-01,
			  -1.11111104054623557880e-01,
			  9.09088713343650656196e-02,
			  -7.69187620504482999495e-02,
			  6.66107313738753120669e-02,
			  -5.83357013379057348645e-02,
			  4.97687799461593236017e-02,
			  -3.65315727442169155270e-02,
			  1.62858201153657823623e-02
			};
			const double one = 1.0;
			const double huge = 1.0e300;
			double atan(double x) {
			  double w, s1, s2, z;
			  int ix, hx, id;
			  hx = __HI(x);
			  ix = hx & 0x7fffffff;
			  if (ix >= 0x44100000) {
			    if (ix > 0x7ff00000 || (ix == 0x7ff00000 && (__LO(x) != 0))) return x + x;
			    if (hx > 0) return atanhi[3] + atanlo[3];
			    else return -atanhi[3] - atanlo[3];
			  }
			  if (ix < 0x3fdc0000) {
			    if (ix < 0x3e200000) {
			      if (huge + x > one) return x;
			    }
			    id = -1;
			  } else {
			    x = fabs(x);
			    if (ix < 0x3ff30000) {
			      if (ix < 0x3fe60000) {
			        id = 0;
			        x = (2.0 * x - one) / (2.0 + x);
			      } else {
			        id = 1;
			        x = (x - one) / (x + one);
			      }
			    } else {
			      if (ix < 0x40038000) {
			        id = 2;
			        x = (x - 1.5) / (one + 1.5 * x);
			      } else {
			        id = 3;
			        x = -1.0 / x;
			      }
			    }
			  }
			  z = x * x;
			  w = z * z;
			  s1 = z * (aT[0] + w * (aT[2] + w * (aT[4] + w * (aT[6] + w * (aT[8] + w * aT[10])))));
			  s2 = w * (aT[1] + w * (aT[3] + w * (aT[5] + w * (aT[7] + w * aT[9]))));
			  if (id < 0) return x - x * (s1 + s2);
			  else {
			    z = atanhi[id] - ((x * (s1 + s2) - atanlo[id]) - x);
			    return (hx < 0) ? -z : z;
			  }
			}
			""";

	// e_atan2.c: __ieee754_atan2
	private static final String ATAN2 = """
			const double tiny = 1.0e-300;
			const double zero = 0.0;
			const double pi_o_4 = 7.8539816339744827900E-01;
			const double pi_o_2 = 1.5707963267948965580E+00;
			const double pi = 3.1415926535897931160E+00;
			const double pi_lo = 1.2246467991473531772E-16;
			double atan2(double y, double x) {
			  double z;
			  int k, m, hx, hy, ix, iy, lx, ly;
			  hx = __HI(x);
			  ix = hx & 0x7fffffff;
			  lx = __LO(x);
			  hy = __HI(y);
			  iy = hy & 0x7fffffff;
			  ly = __LO(y);
			  if (((ix | ((lx | -lx) >>> 31)) > 0x7ff00000) || ((iy | ((ly | -ly) >>> 31)) > 0x7ff00000)) return x + y;
			  if ((hx - 0x3ff00000 | lx) == 0) return atan(y);
			  m = ((hy >> 31) & 1) | ((hx >> 30) & 2);
			  if ((iy | ly) == 0) {
			    if (m == 0 || m == 1) return y;
			    if (m == 2) return pi + tiny;
			    return -pi - tiny;
			  }
			  if ((ix | lx) == 0) return (hy < 0) ? -pi_o_2 - tiny : pi_o_2 + tiny;
			  if (ix == 0x7ff00000) {
			    if (iy == 0x7ff00000) {
			      if (m == 0) return pi_o_4 + tiny;
			      if (m == 1) return -pi_o_4 - tiny;
			      if (m == 2) return 3.0 * pi_o_4 + tiny;
			      return -3.0 * pi_o_4 - tiny;
			    } else {
			      if (m == 0) return zero;
			      if (m == 1) return -zero;
			      if (m == 2) return pi + tiny;
			      return -pi - tiny;
			    }
			  }
			  if (iy == 0x7ff00000) return (hy < 0) ? -pi_o_2 - tiny : pi_o_2 + tiny;
			  k = (iy - ix) >> 20;
			  if (k > 60) z = pi_o_2 + 0.5 * pi_lo;
			  else if (hx < 0 && k < -60) z = 0.0;
			  else z = atan(fabs(y / x));
			  if (m == 0) return z;
			  if (m == 1) {
			    __HI(z) ^= 0x80000000;
			    return z;
			  }
			  if (m == 2) return pi - (z - pi_lo);
			  return (z - pi_lo) - pi;
			}
			""";

	// e_sinh.c: __ieee754_sinh
	private static final String SINH = """
			const double one = 1.0;
			const double shuge = 1.0e307;
			double sinh(double x) {
			  double t, w, h;
			  int ix, jx, lx;
			  jx = __HI(x);
			  ix = jx & 0x7fffffff;
			  if (ix >= 0x7ff00000) return x + x;
			  h = 0.5;
			  if (jx < 0) h = -h;
			  if (ix < 0x40360000) {
			    if (ix < 0x3e300000)
			      if (shuge + x > one) return x;
			    t = expm1(fabs(x));
			    if (ix < 0x3ff00000) return h * (2.0 * t - t * t / (t + one));
			    return h * (t + t / (t + one));
			  }
			  if (ix < 0x40862E42) return h * exp(fabs(x));
			  lx = __LO(x);
			  if (ix < 0x408633CE || (ix == 0x408633ce) && ule(lx, 0x8fb9f87d)) {
			    w = exp(0.5 * fabs(x));
			    t = h * w;
			    return t * w;
			  }
			  return x * shuge;
			}
			""";

	// e_cosh.c: __ieee754_cosh
	private static final String COSH = """
			const double one = 1.0;
			const double half = 0.5;
			const double huge = 1.0e300;
			double cosh(double x) {
			  double t, w;
			  int ix, lx;
			  ix = __HI(x);
			  ix &= 0x7fffffff;
			  if (ix >= 0x7ff00000) return x * x;
			  if (ix < 0x3fd62e43) {
			    t = expm1(fabs(x));
			    w = one + t;
			    if (ix < 0x3c800000) return w;
			    return one + (t * t) / (w + w);
			  }
			  if (ix < 0x40360000) {
			    t = exp(fabs(x));
			    return half * t + half / t;
			  }
			  if (ix < 0x40862E42) return half * exp(fabs(x));
			  lx = __LO(x);
			  if (ix < 0x408633CE || (ix == 0x408633ce) && ule(lx, 0x8fb9f87d)) {
			    w = exp(half * fabs(x));
			    t = half * w;
			    return t * w;
			  }
			  return huge * huge;
			}
			""";

	// s_tanh.c: tanh
	private static final String TANH = """
			const double one = 1.0;
			const double two = 2.0;
			const double tiny = 1.0e-300;
			double tanh(double x) {
			  double t, z;
			  int jx, ix;
			  jx = __HI(x);
			  ix = jx & 0x7fffffff;
			  if (ix >= 0x7ff00000) {
			    if (jx >= 0) return one / x + one;
			    else return one / x - one;
			  }
			  if (ix < 0x40360000) {
			    if (ix < 0x3c800000) return x * (one + x);
			    if (ix >= 0x3ff00000) {
			      t = expm1(two * fabs(x));
			      z = one - two / (t + two);
			    } else {
			      t = expm1(-two * fabs(x));
			      z = -t / (t + two);
			    }
			  } else {
			    z = one - tiny;
			  }
			  return (jx >= 0) ? z : -z;
			}
			""";

	// e_pow.c: __ieee754_pow
	private static final String POW = """
			const double[] bp = {1.0, 1.5};
			const double[] dp_h = {0.0, 5.84962487220764160156e-01};
			const double[] dp_l = {0.0, 1.35003920212974897128e-08};
			const double zero = 0.0;
			const double one = 1.0;
			const double two = 2.0;
			const double two53 = 9007199254740992.0;
			const double huge = 1.0e300;
			const double tiny = 1.0e-300;
			const double L1 = 5.99999999999994648725e-01;
			const double L2 = 4.28571428578550184252e-01;
			const double L3 = 3.33333329818377432918e-01;
			const double L4 = 2.72728123808534006489e-01;
			const double L5 = 2.30660745775561754067e-01;
			const double L6 = 2.06975017800338417784e-01;
			const double P1 = 1.66666666666666019037e-01;
			const double P2 = -2.77777777770155933842e-03;
			const double P3 = 6.61375632143793436117e-05;
			const double P4 = -1.65339022054652515390e-06;
			const double P5 = 4.13813679705723846039e-08;
			const double lg2 = 6.93147180559945286227e-01;
			const double lg2_h = 6.93147182464599609375e-01;
			const double lg2_l = -1.90465429995776804525e-09;
			const double ovt = 8.0085662595372944372e-0017;
			const double cp = 9.61796693925975554329e-01;
			const double cp_h = 9.61796700954437255859e-01;
			const double cp_l = -7.02846165095275826516e-09;
			const double ivln2 = 1.44269504088896338700e+00;
			const double ivln2_h = 1.44269502162933349609e+00;
			const double ivln2_l = 1.92596299112661746887e-08;
			double pow(double x, double y) {
			  double z, ax, z_h, z_l, p_h, p_l;
			  double y1, t1, t2, r, s, t, u, v, w;
			  double ss, s2, s_h, s_l, t_h, t_l;
			  int i, j, k, yisint, n;
			  int hx, hy, ix, iy, lx, ly;
			  hx = __HI(x);
			  lx = __LO(x);
			  hy = __HI(y);
			  ly = __LO(y);
			  ix = hx & 0x7fffffff;
			  iy = hy & 0x7fffffff;
			  if ((iy | ly) == 0) return one;
			  if (ix > 0x7ff00000 || ((ix == 0x7ff00000) && (lx != 0)) || iy > 0x7ff00000 || ((iy == 0x7ff00000) && (ly != 0)))
			    return x + y;
			  yisint = 0;
			  if (hx < 0) {
			    if (iy >= 0x43400000) yisint = 2;
			    else if (iy >= 0x3ff00000) {
			      k = (iy >> 20) - 0x3ff;
			      if (k > 20) {
			        j = ly >>> (52 - k);
			        if ((j << (52 - k)) == ly) yisint = 2 - (j & 1);
			      } else if (ly == 0) {
			        j = iy >> (20 - k);
			        if ((j << (20 - k)) == iy) yisint = 2 - (j & 1);
			      }
			    }
			  }
			  if (ly == 0) {
			    if (iy == 0x7ff00000) {
			      if (((ix - 0x3ff00000) | lx) == 0) return y - y;
			      else if (ix >= 0x3ff00000) return (hy >= 0) ? y : zero;
			      else return (hy < 0) ? -y : zero;
			    }
			    if (iy == 0x3ff00000) {
			      if (hy < 0) return one / x; else return x;
			    }
			    if (hy == 0x40000000) return x * x;
			    if (hy == 0x3fe00000) {
			      if (hx >= 0) return sqrt(x);
			    }
			  }
			  ax = fabs(x);
			  if (lx == 0) {
			    if (ix == 0x7ff00000 || ix == 0 || ix == 0x3ff00000) {
			      z = ax;
			      if (hy < 0) z = one / z;
			      if (hx < 0) {
			        if (((ix - 0x3ff00000) | yisint) == 0) {
			          z = (z - z) / (z - z);
			        } else if (yisint == 1)
			          z = -z;
			      }
			      return z;
			    }
			  }
			  n = (hx >> 31) + 1;
			  if ((n | yisint) == 0) return (x - x) / (x - x);
			  s = one;
			  if ((n | (yisint - 1)) == 0) s = -one;
			  if (iy > 0x41e00000) {
			    if (iy > 0x43f00000) {
			      if (ix <= 0x3fefffff) return (hy < 0) ? huge * huge : tiny * tiny;
			      if (ix >= 0x3ff00000) return (hy > 0) ? huge * huge : tiny * tiny;
			    }
			    if (ix < 0x3fefffff) return (hy < 0) ? s * huge * huge : s * tiny * tiny;
			    if (ix > 0x3ff00000) return (hy > 0) ? s * huge * huge : s * tiny * tiny;
			    t = ax - one;
			    w = (t * t) * (0.5 - t * (0.3333333333333333333333 - t * 0.25));
			    u = ivln2_h * t;
			    v = t * ivln2_l - w * ivln2;
			    t1 = u + v;
			    __LO(t1) = 0;
			    t2 = v - (t1 - u);
			  } else {
			    n = 0;
			    if (ix < 0x00100000) {
			      ax *= two53;
			      n -= 53;
			      ix = __HI(ax);
			    }
			    n += ((ix) >> 20) - 0x3ff;
			    j = ix & 0x000fffff;
			    ix = j | 0x3ff00000;
			    if (j <= 0x3988E) k = 0;
			    else if (j < 0xBB67A) k = 1;
			    else {
			      k = 0;
			      n += 1;
			      ix -= 0x00100000;
			    }
			    __HI(ax) = ix;
			    u = ax - bp[k];
			    v = one / (ax + bp[k]);
			    ss = u * v;
			    s_h = ss;
			    __LO(s_h) = 0;
			    t_h = zero;
			    __HI(t_h) = ((ix >> 1) | 0x20000000) + 0x00080000 + (k << 18);
			    t_l = ax - (t_h - bp[k]);
			    s_l = v * ((u - s_h * t_h) - s_h * t_l);
			    s2 = ss * ss;
			    r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6)))));
			    r += s_l * (s_h + ss);
			    s2 = s_h * s_h;
			    t_h = 3.0 + s2 + r;
			    __LO(t_h) = 0;
			    t_l = r - ((t_h - 3.0) - s2);
			    u = s_h * t_h;
			    v = s_l * t_h + t_l * ss;
			    p_h = u + v;
			    __LO(p_h) = 0;
			    p_l = v - (p_h - u);
			    z_h = cp_h * p_h;
			    z_l = cp_l * p_h + p_l * cp + dp_l[k];
			    t = (double) n;
			    t1 = (((z_h + z_l) + dp_h[k]) + t);
			    __LO(t1) = 0;
			    t2 = z_l - (((t1 - t) - dp_h[k]) - z_h);
			  }
			  y1 = y;
			  __LO(y1) = 0;
			  p_l = (y - y1) * t1 + y * t2;
			  p_h = y1 * t1;
			  z = p_l + p_h;
			  j = __HI(z);
			  i = __LO(z);
			  if (j >= 0x40900000) {
			    if (((j - 0x40900000) | i) != 0) return s * huge * huge;
			    else {
			      if (p_l + ovt > z - p_h) return s * huge * huge;
			    }
			  } else if ((j & 0x7fffffff) >= 0x4090cc00) {
			    if (((j - 0xc090cc00) | i) != 0) return s * tiny * tiny;
			    else {
			      if (p_l <= z - p_h) return s * tiny * tiny;
			    }
			  }
			  i = j & 0x7fffffff;
			  k = (i >> 20) - 0x3ff;
			  n = 0;
			  if (i > 0x3fe00000) {
			    n = j + (0x00100000 >> (k + 1));
			    k = ((n & 0x7fffffff) >> 20) - 0x3ff;
			    t = zero;
			    __HI(t) = (n & ~(0x000fffff >> k));
			    n = ((n & 0x000fffff) | 0x00100000) >> (20 - k);
			    if (j < 0) n = -n;
			    p_h -= t;
			  }
			  t = p_l + p_h;
			  __LO(t) = 0;
			  u = t * lg2_h;
			  v = (p_l - (t - p_h)) * lg2 + t * lg2_l;
			  z = u + v;
			  w = v - (z - u);
			  t = z * z;
			  t1 = z - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
			  r = (z * t1) / (t1 - two) - (w + z * w);
			  z = one - (r - z);
			  j = __HI(z);
			  j += (n << 20);
			  if ((j >> 20) <= 0) z = scalbn(z, (double) n);
			  else __HI(z) += (n << 20);
			  return s * z;
			}
			""";

	// e_hypot.c: __ieee754_hypot
	private static final String HYPOT = """
			double hypot(double x, double y) {
			  double a = x, b = y, t1, t2, y1, y2, w;
			  int j, k, ha, hb;
			  ha = __HI(x) & 0x7fffffff;
			  hb = __HI(y) & 0x7fffffff;
			  if (hb > ha) {
			    a = y;
			    b = x;
			    j = ha;
			    ha = hb;
			    hb = j;
			  } else {
			    a = x;
			    b = y;
			  }
			  __HI(a) = ha;
			  __HI(b) = hb;
			  if ((ha - hb) > 0x3c00000) {
			    return a + b;
			  }
			  k = 0;
			  if (ha > 0x5f300000) {
			    if (ha >= 0x7ff00000) {
			      w = a + b;
			      if (((ha & 0xfffff) | __LO(a)) == 0) w = a;
			      if (((hb ^ 0x7ff00000) | __LO(b)) == 0) w = b;
			      return w;
			    }
			    ha -= 0x25800000;
			    hb -= 0x25800000;
			    k += 600;
			    __HI(a) = ha;
			    __HI(b) = hb;
			  }
			  if (hb < 0x20b00000) {
			    if (hb <= 0x000fffff) {
			      if ((hb | (__LO(b))) == 0) return a;
			      t1 = 0.0;
			      __HI(t1) = 0x7fd00000;
			      b *= t1;
			      a *= t1;
			      k -= 1022;
			    } else {
			      ha += 0x25800000;
			      hb += 0x25800000;
			      k -= 600;
			      __HI(a) = ha;
			      __HI(b) = hb;
			    }
			  }
			  w = a - b;
			  if (w > b) {
			    t1 = 0.0;
			    __HI(t1) = ha;
			    t2 = a - t1;
			    w = sqrt(t1 * t1 - (b * (-b) - t2 * (a + t1)));
			  } else {
			    a = a + a;
			    y1 = 0.0;
			    __HI(y1) = hb;
			    y2 = b - y1;
			    t1 = 0.0;
			    __HI(t1) = ha + 0x00100000;
			    t2 = a - t1;
			    w = sqrt(t1 * y1 - (w * (-w) - (t1 * y2 + t2 * b)));
			  }
			  if (k != 0) {
			    t1 = 1.0;
			    __HI(t1) += (k << 20);
			    return t1 * w;
			  } else return w;
			}
			""";

	// s_cbrt.c: cbrt
	private static final String CBRT = """
			const int B1 = 715094163;
			const int B2 = 696219795;
			const double C = 5.42857142857142815906e-01;
			const double D = -7.05306122448979611050e-01;
			const double E = 1.41428571428571436819e+00;
			const double F = 1.60714285714285720630e+00;
			const double G = 3.57142857142857150787e-01;
			double cbrt(double x) {
			  int hx, sign;
			  double r, s, t = 0.0, w;
			  hx = __HI(x);
			  sign = hx & 0x80000000;
			  hx ^= sign;
			  if (hx >= 0x7ff00000) return (x + x);
			  if ((hx | __LO(x)) == 0) return (x);
			  __HI(x) = hx;
			  if (hx < 0x00100000) {
			    __HI(t) = 0x43500000;
			    t *= x;
			    __HI(t) = __HI(t) / 3 + B2;
			  }
			  else __HI(t) = hx / 3 + B1;
			  r = t * t / x;
			  s = C + r * t;
			  t *= G + F / (s + E + D / s);
			  __LO(t) = 0;
			  __HI(t) += 0x00000001;
			  s = t * t;
			  r = x / s;
			  w = t + t;
			  r = (r - t) / (w + r);
			  t = t + t * r;
			  __HI(t) |= sign;
			  return (t);
			}
			""";

	// e_remainder.c: __ieee754_remainder
	private static final String IEEE_REMAINDER = """
			const double zero = 0.0;
			double remainder(double x, double p) {
			  int hx, hp, sx, lx, lp;
			  double p_half;
			  hx = __HI(x);
			  lx = __LO(x);
			  hp = __HI(p);
			  lp = __LO(p);
			  sx = hx & 0x80000000;
			  hp &= 0x7fffffff;
			  hx &= 0x7fffffff;
			  if ((hp | lp) == 0) return (x * p) / (x * p);
			  if ((hx >= 0x7ff00000) || ((hp >= 0x7ff00000) && (((hp - 0x7ff00000) | lp) != 0))) return (x * p) / (x * p);
			  if (hp <= 0x7fdfffff) x = fmod(x, p + p);
			  if (((hx - hp) | (lx - lp)) == 0) return zero * x;
			  x = fabs(x);
			  p = fabs(p);
			  if (hp < 0x00200000) {
			    if (x + x > p) {
			      x -= p;
			      if (x + x >= p) x -= p;
			    }
			  } else {
			    p_half = 0.5 * p;
			    if (x > p_half) {
			      x -= p;
			      if (x >= p_half) x -= p;
			    }
			  }
			  __HI(x) ^= sx;
			  return x;
			}
			""";

	// e_fmod.c: __ieee754_fmod
	private static final String FMOD = """
			const double one = 1.0;
			const double[] Zero = {0.0, -0.0};
			double fmod(double x, double y) {
			  int n, hx, hy, hz, ix, iy, sx, i, lx, ly, lz;
			  hx = __HI(x);
			  lx = __LO(x);
			  hy = __HI(y);
			  ly = __LO(y);
			  sx = hx & 0x80000000;
			  hx ^= sx;
			  hy &= 0x7fffffff;
			  if ((hy | ly) == 0 || (hx >= 0x7ff00000) || ((hy | ((ly | -ly) >>> 31)) > 0x7ff00000)) return (x * y) / (x * y);
			  if (hx <= hy) {
			    if ((hx < hy) || ult(lx, ly)) return x;
			    if (lx == ly) return Zero[sx >>> 31];
			  }
			  if (hx < 0x00100000) {
			    if (hx == 0) {
			      ix = -1043;
			      i = lx;
			      while (i > 0) {
			        ix -= 1;
			        i <<= 1;
			      }
			    } else {
			      ix = -1022;
			      i = (hx << 11);
			      while (i > 0) {
			        ix -= 1;
			        i <<= 1;
			      }
			    }
			  } else ix = (hx >> 20) - 1023;
			  if (hy < 0x00100000) {
			    if (hy == 0) {
			      iy = -1043;
			      i = ly;
			      while (i > 0) {
			        iy -= 1;
			        i <<= 1;
			      }
			    } else {
			      iy = -1022;
			      i = (hy << 11);
			      while (i > 0) {
			        iy -= 1;
			        i <<= 1;
			      }
			    }
			  } else iy = (hy >> 20) - 1023;
			  if (ix >= -1022) hx = 0x00100000 | (0x000fffff & hx);
			  else {
			    n = -1022 - ix;
			    if (n <= 31) {
			      hx = (hx << n) | (lx >>> (32 - n));
			      lx <<= n;
			    } else {
			      hx = lx << (n - 32);
			      lx = 0;
			    }
			  }
			  if (iy >= -1022) hy = 0x00100000 | (0x000fffff & hy);
			  else {
			    n = -1022 - iy;
			    if (n <= 31) {
			      hy = (hy << n) | (ly >>> (32 - n));
			      ly <<= n;
			    } else {
			      hy = ly << (n - 32);
			      ly = 0;
			    }
			  }
			  n = ix - iy;
			  while (n != 0) {
			    n -= 1;
			    hz = hx - hy;
			    lz = lx - ly;
			    if (ult(lx, ly)) hz -= 1;
			    if (hz < 0) {
			      hx = hx + hx + (lx >>> 31);
			      lx = lx + lx;
			    } else {
			      if ((hz | lz) == 0) return Zero[sx >>> 31];
			      hx = hz + hz + (lz >>> 31);
			      lx = lz + lz;
			    }
			  }
			  hz = hx - hy;
			  lz = lx - ly;
			  if (ult(lx, ly)) hz -= 1;
			  if (hz >= 0) {
			    hx = hz;
			    lx = lz;
			  }
			  if ((hx | lx) == 0) return Zero[sx >>> 31];
			  while (hx < 0x00100000) {
			    hx = hx + hx + (lx >>> 31);
			    lx = lx + lx;
			    iy -= 1;
			  }
			  if (iy >= -1022) {
			    hx = ((hx - 0x00100000) | ((iy + 1023) << 20));
			    __HI(x) = hx | sx;
			    __LO(x) = lx;
			  } else {
			    n = -1022 - iy;
			    if (n <= 20) {
			      lx = (lx >>> n) | (hx << (32 - n));
			      hx >>= n;
			    } else if (n <= 31) {
			      lx = (hx << (32 - n)) | (lx >>> n);
			      hx = sx;
			    } else {
			      lx = hx >> (n - 32);
			      hx = sx;
			    }
			    __HI(x) = hx | sx;
			    __LO(x) = lx;
			    x *= one;
			  }
			  return x;
			}
			""";

	// s_scalbn.c: scalbn, its exponent arriving as an exact double
	private static final String SCALB = """
			const double two54 = 1.80143985094819840000e+16;
			const double twom54 = 5.55111512312578270212e-17;
			const double huge = 1.0e+300;
			const double tiny = 1.0e-300;
			double scalbn(double x, double fn) {
			  int k, hx, lx, n;
			  n = (int) fn;
			  hx = __HI(x);
			  lx = __LO(x);
			  k = (hx & 0x7ff00000) >> 20;
			  if (k == 0) {
			    if ((lx | (hx & 0x7fffffff)) == 0) return x;
			    x *= two54;
			    hx = __HI(x);
			    k = ((hx & 0x7ff00000) >> 20) - 54;
			    if (n < -50000) return tiny * x;
			  }
			  if (k == 0x7ff) return x + x;
			  k = k + n;
			  if (k > 0x7fe) return huge * copysign(huge, x);
			  if (k > 0) {
			    __HI(x) = (hx & 0x800fffff) | (k << 20);
			    return x;
			  }
			  if (k <= -54)
			    if (n > 50000) return huge * copysign(huge, x);
			    else return tiny * copysign(tiny, x);
			  k += 54;
			  __HI(x) = (hx & 0x800fffff) | (k << 20);
			  return x * twom54;
			}
			""";

	// --- the bit-level operations, from StrictMath's specified behaviour ---------------

	// The distance to the next double of larger magnitude: a NaN stays a NaN, an
	// infinity answers +infinity, and below 2^-1022 it is the smallest positive double.
	private static final String ULP = """
			double ulp(double d) {
			  int e;
			  e = (__HI(d) >> 20) & 0x7ff;
			  if (e == 0x7ff) return fabs(d);
			  if (e > 52) return FROM_BITS((long) (e - 52) << 52);
			  if (e == 0) return FROM_BITS(1L);
			  return FROM_BITS(1L << (e - 1));
			}
			""";

	private static final String SIGNUM = """
			double signum(double d) {
			  if (d > 0.0) return 1.0;
			  if (d < 0.0) return -1.0;
			  return d;
			}
			""";

	// Next to a positive double is the next bit pattern up, next to a negative one the
	// next down; either zero steps to the smallest positive double.
	private static final String NEXT_UP = """
			double next_up(double d) {
			  long b;
			  if (d != d) return d;
			  if (d == 0.0) return FROM_BITS(1L);
			  b = BITS(d);
			  if (d < 0.0) return FROM_BITS(b - 1L);
			  if (b == 0x7ff0000000000000L) return d;
			  return FROM_BITS(b + 1L);
			}
			""";

	private static final String NEXT_DOWN = """
			double next_down(double d) {
			  long b;
			  if (d != d) return d;
			  if (d == 0.0) return FROM_BITS(0x8000000000000001L);
			  b = BITS(d);
			  if (d > 0.0) return FROM_BITS(b - 1L);
			  if (b == 0xfff0000000000000L) return d;
			  return FROM_BITS(b + 1L);
			}
			""";

	private static final String NEXT_AFTER = """
			double next_after(double start, double direction) {
			  if (start != start || direction != direction) return start + direction;
			  if (start == direction) return direction;
			  if (start < direction) return next_up(start);
			  return next_down(start);
			}
			""";

	// StrictMath reads a NaN sign argument as positive.
	private static final String COPY_SIGN = """
			double copy_sign(double magnitude, double sign) {
			  if (sign != sign) return fabs(magnitude);
			  return copysign(magnitude, sign);
			}
			""";

	// The unbiased exponent field: 1024 for a NaN or an infinity, -1023 below 2^-1022.
	private static final String GET_EXPONENT = """
			int get_exponent(double d) {
			  return ((__HI(d) >> 20) & 0x7ff) - 1023;
			}
			""";

}
