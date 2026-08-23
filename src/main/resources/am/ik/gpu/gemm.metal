// The kernels behind --gpu on Apple Silicon -- the same member set gemm.cu carries, at
// SINGLE FLOAT ONLY. This file is the whole artifact: unlike the CUDA half there is no
// generated sibling to check in beside it and no toolchain to run, because Metal's
// compiler is in the OS and newLibraryWithSource:options:error: compiles this text at run
// time (~35 ms the first time a given text is ever seen on a machine, 2-3 ms on every
// later process, because the OS caches it the way the NVIDIA driver caches PTX).
//
// WHY THERE IS NO f64 HALF. MSL rejects `double` outright -- "'double' is not supported
// in Metal" -- so a double-float operand is a hard decline on this backend rather than a
// slower path. See .kb/gpu.md.
//
// The kernels are compiled with MTLMathModeSafe (MetalDriver sets it, falling back to
// setFastMathEnabled:NO on an OS without the newer selector). That is not a preference:
// the relaxed default flushes denormals and reassociates, and the strided tier below
// claims BIT-IDENTITY with the scalar defun, which neither survives.

#include <metal_stdlib>
using namespace metal;

#define TILE 16

// The STACKED matrix product. gridDim.z is the batch axis and each threadgroup does the
// same tile walk over one slab, so a stacked product is one dispatch rather than one per
// matrix -- which matters more here than it does on CUDA, because Metal's floor is per
// COMMAND BUFFER (~77 us) rather than per launch. The strides are in ELEMENTS and a
// broadcast operand simply passes 0, so the whole batch then reads the same slab.
//
// The rank-2 product does NOT come here: MetalGemm dispatches that one through
// MPSMatrixMultiplication, which is in the OS and is 1.5-4.5x this kernel from n=512 up.
// This kernel is what serves the batch axis MPS cannot be handed with a zero stride.
kernel void gemm_batched_f32(device const float* A [[buffer(0)]],
                             device const float* B [[buffer(1)]],
                             device float* C [[buffer(2)]],
                             constant int* dims [[buffer(3)]],
                             uint3 tid [[thread_position_in_threadgroup]],
                             uint3 gp [[threadgroup_position_in_grid]]) {
  threadgroup float As[TILE][TILE];
  threadgroup float Bs[TILE][TILE];
  int M = dims[0], N = dims[1], K = dims[2], strideA = dims[3], strideB = dims[4];
  int z = int(gp.z);
  const device float* Ab = A + (long) z * strideA;
  const device float* Bb = B + (long) z * strideB;
  device float* Cb = C + (long) z * M * N;
  int tx = int(tid.x), ty = int(tid.y);
  int row = int(gp.y) * TILE + ty;
  int col = int(gp.x) * TILE + tx;
  float acc = 0.0f;
  int tiles = (K + TILE - 1) / TILE;
  for (int t = 0; t < tiles; ++t) {
    int ac = t * TILE + tx, br = t * TILE + ty;
    As[ty][tx] = (row < M && ac < K) ? Ab[row * (long) K + ac] : 0.0f;
    Bs[ty][tx] = (br < K && col < N) ? Bb[br * (long) N + col] : 0.0f;
    threadgroup_barrier(mem_flags::mem_threadgroup);
    for (int k = 0; k < TILE; ++k) acc += As[ty][k] * Bs[k][tx];
    threadgroup_barrier(mem_flags::mem_threadgroup);
  }
  if (row < M && col < N) Cb[row * (long) N + col] = acc;
}

// ---------------------------------------------------------------------------------------
// THE RESIDENT TIER (.todo/494, the Apple half of .todo/491): the members whose CPU twin
// is a LANE loop and which a round trip could therefore never win -- the equal-shape binary
// ops and the comparison masks, the array-with-scalar forms, the three-way select behind
// torch:masked-fill, the fused Adam update, the strided copy behind reshape / transpose /
// slice / concatenate, and the axis fold. MetalGemm offers every one of them ONLY over an
// operand that is already on the device (a lazy result, or an operand a recent call
// uploaded): then there is no trip, the result stays on the device until the host first
// reads it, and what the CPU would have paid is the download of the operand plus the loop.
// (Measured, the lazy mode does not pay on this backend -- a tie at the notebook's shapes,
// a loss at the book's -- so the interceptors leave it off here and in practice the only
// resident operand is a GEMV's matrix; the tier is built, bit-identical and pinned for the
// day the command buffers stop waiting. See .kb/gpu.md.)
//
// ALL OF THEM ARE BIT-IDENTICAL TO THE CPU KERNELS, whose rule for exactly these members
// is compute-in-f64-and-narrow-on-the-store (LinalgSimdKernels, JvmSimdVectorTemplate:
// `(float) laApply(op, x, s)`, laAdamStep, foldAxis). MSL has no f64 type, so each kernel
// earns the claim one of two ways:
//   - where both operands are floats (zip, where, copy, the scalar forms whose scalar is
//     exactly a float), the bcast_f32 argument above applies verbatim: +, -, * over two
//     floats are exact in binary64 so one float rounding is the same rounding, / is
//     innocuous double rounding at these widths, and a select moves bits;
//   - where the arithmetic is GENUINELY f64 -- a scalar that is not a float (`(linalg:mul
//     grad 0.1d0)`), every step of the Adam update (its rule is ten f64 numbers), and the
//     sum fold, which accumulates in f64 -- the kernel runs IEEE binary64 in SOFTWARE,
//     below: 64-bit integer arithmetic, round-to-nearest-even, subnormals and the specials
//     included, so the result is the CPU's to the bit rather than close to it. It is
//     slower than a float op by a hundred-odd integer instructions, which a memory-bound
//     member over a resident operand does not notice (measured: .kb/gpu.md), and it is
//     the only way these members can be on this backend without giving up the contract
//     every interceptor test asserts as EQUALITY.
// The word `double` does not appear in any code line of this file on purpose: MSL rejects
// the type, and GpuDeclineTest asserts its absence on every machine. The tier's kernels
// are at the END of the file; the software binary64 they run on is HERE, because the
// element-wise tier's own sqrt and every float kernel's flush guard (below) need it too.

// --- IEEE binary64 in software ---------------------------------------------------------
// A value is its bit pattern in a ulong (`f64`). Arithmetic unpacks to sign / exponent /
// 53-bit significand, works in a 128-bit integer (two ulongs) so that every intermediate
// is exact or carries a sticky bit, and packs through ONE rounding step shared by every
// operation -- which is what makes the emulation correct: no operation rounds twice.

typedef ulong f64;

#define F64_QUIET (1ul << 51)
#define F64_NAN 0x7ff8000000000000ul
#define F64_INF 0x7ff0000000000000ul
#define F64_ONE 0x3ff0000000000000ul

struct u128 {
  ulong hi;
  ulong lo;
};

static inline uint clz64(ulong x) {
  uint hi = uint(x >> 32);
  return hi != 0 ? clz(hi) : 32 + clz(uint(x));
}

// 32 x 32 -> 64 without relying on a 64-bit multiply.
static inline ulong umul64(uint a, uint b) {
  return (ulong(mulhi(a, b)) << 32) | ulong(a * b);
}

static inline u128 u128_shl(u128 v, uint s) {
  u128 r;
  if (s == 0) return v;
  if (s >= 64) {
    r.hi = v.lo << (s - 64);
    r.lo = 0;
  } else {
    r.hi = (v.hi << s) | (v.lo >> (64 - s));
    r.lo = v.lo << s;
  }
  return r;
}

// Shifts right, OR-ing every bit shifted out into `sticky`.
static inline u128 u128_shr_sticky(u128 v, uint s, thread bool& sticky) {
  u128 r;
  if (s == 0) return v;
  if (s >= 128) {
    sticky = sticky || (v.hi | v.lo) != 0;
    r.hi = 0;
    r.lo = 0;
  } else if (s >= 64) {
    uint t = s - 64;
    sticky = sticky || v.lo != 0 || (t != 0 && (v.hi & ((1ul << t) - 1)) != 0);
    r.hi = 0;
    r.lo = v.hi >> t;
  } else {
    sticky = sticky || (v.lo & ((1ul << s) - 1)) != 0;
    r.lo = (v.lo >> s) | (v.hi << (64 - s));
    r.hi = v.hi >> s;
  }
  return r;
}

static inline u128 u128_add(u128 a, u128 b) {
  u128 r;
  r.lo = a.lo + b.lo;
  r.hi = a.hi + b.hi + (r.lo < a.lo ? 1ul : 0ul);
  return r;
}

static inline u128 u128_sub(u128 a, u128 b) {
  u128 r;
  r.lo = a.lo - b.lo;
  r.hi = a.hi - b.hi - (a.lo < b.lo ? 1ul : 0ul);
  return r;
}

static inline uint u128_clz(u128 v) {
  return v.hi != 0 ? clz64(v.hi) : 64 + clz64(v.lo);
}

struct f64u {
  ulong sign;  // 0 or 1
  int e;       // unbiased exponent of the leading bit (finite nonzero)
  ulong m;     // 53-bit significand with the hidden bit in place (finite nonzero)
  int kind;    // 0 finite nonzero, 1 zero, 2 infinity, 3 NaN
};

static inline f64u f64_unpack(f64 x) {
  f64u u;
  u.sign = x >> 63;
  uint ef = uint((x >> 52) & 0x7ff);
  ulong man = x & 0xfffffffffffffull;
  if (ef == 0x7ff) {
    u.kind = man == 0 ? 2 : 3;
    u.e = 0;
    u.m = man;
    return u;
  }
  if (ef == 0) {
    if (man == 0) {
      u.kind = 1;
      u.e = 0;
      u.m = 0;
      return u;
    }
    uint lz = clz64(man) - 11;
    u.kind = 0;
    u.m = man << lz;
    u.e = -1022 - int(lz);
    return u;
  }
  u.kind = 0;
  u.m = man | (1ul << 52);
  u.e = int(ef) - 1023;
  return u;
}

// The ONE rounding step: packs sign and the value m * 2^e_lsb (plus something strictly
// between 0 and one unit of m's last place when `sticky`) to the nearest binary64, ties to
// even, overflowing to infinity and underflowing through the subnormals.
static inline f64 f64_pack(ulong sign, u128 m, int e_lsb, bool sticky) {
  if ((m.hi | m.lo) == 0) return sign << 63;
  uint lz = u128_clz(m);
  int E = e_lsb + 127 - int(lz);
  m = u128_shl(m, lz);
  if (E < -1022) {
    uint sh = uint(-1022 - E);
    m = u128_shr_sticky(m, sh > 200 ? 200 : sh, sticky);
    ulong mant = m.hi >> 11;
    bool round = ((m.hi >> 10) & 1) != 0;
    bool st = sticky || (m.hi & 0x3ff) != 0 || m.lo != 0;
    if (round && (st || (mant & 1) != 0)) mant += 1;
    return (sign << 63) | mant;
  }
  if (E > 1023) return (sign << 63) | F64_INF;
  ulong mant = m.hi >> 11;
  bool round = ((m.hi >> 10) & 1) != 0;
  bool st = sticky || (m.hi & 0x3ff) != 0 || m.lo != 0;
  if (round && (st || (mant & 1) != 0)) mant += 1;
  ulong bits = (ulong(E + 1023) << 52) + (mant - (1ul << 52));
  if ((bits >> 52) >= 2047) bits = F64_INF;
  return (sign << 63) | bits;
}

// The exact widening of a float, payloads and signed zeros included.
static inline f64 f64_from_f(float f) {
  uint b = as_type<uint>(f);
  ulong sign = ulong(b >> 31);
  uint ef = (b >> 23) & 0xff;
  uint man = b & 0x7fffff;
  if (ef == 0xff) {
    if (man == 0) return (sign << 63) | F64_INF;
    return (sign << 63) | F64_INF | F64_QUIET | (ulong(man) << 29);
  }
  u128 m;
  m.hi = 0;
  if (ef == 0) {
    if (man == 0) return sign << 63;
    m.lo = ulong(man);
    return f64_pack(sign, m, -149, false);
  }
  m.lo = ulong(man | 0x800000u);
  return f64_pack(sign, m, int(ef) - 150, false);
}

// The narrowing `(float) d`: round to nearest even into the float grid, subnormals and
// overflow included, a NaN keeping the top of its payload quieted.
static inline float f64_to_f(f64 x) {
  f64u u = f64_unpack(x);
  uint sign = uint(u.sign) << 31;
  if (u.kind == 1) return as_type<float>(sign);
  if (u.kind == 2) return as_type<float>(sign | 0x7f800000u);
  if (u.kind == 3) return as_type<float>(sign | 0x7fc00000u | uint(u.m >> 29));
  int E = u.e;
  ulong m = u.m;
  if (E < -126) {
    uint sh = uint(-126 - E);
    bool st = false;
    if (sh > 63) {
      st = m != 0;
      m = 0;
    } else {
      st = (m & ((1ul << sh) - 1)) != 0;
      m >>= sh;
    }
    uint mant = uint(m >> 29);
    bool round = ((m >> 28) & 1) != 0;
    st = st || (m & 0xfffffff) != 0;
    if (round && (st || (mant & 1) != 0)) mant += 1;
    return as_type<float>(sign | mant);
  }
  if (E > 127) return as_type<float>(sign | 0x7f800000u);
  uint mant = uint(m >> 29);
  bool round = ((m >> 28) & 1) != 0;
  bool st = (m & 0xfffffff) != 0;
  if (round && (st || (mant & 1) != 0)) mant += 1;
  uint bits = (uint(E + 127) << 23) + (mant - 0x800000u);
  if ((bits >> 23) >= 255) bits = 0x7f800000u;
  return as_type<float>(sign | bits);
}

static inline f64 f64_add(f64 a, f64 b) {
  f64u ua = f64_unpack(a), ub = f64_unpack(b);
  if (ua.kind == 3) return a | F64_QUIET;
  if (ub.kind == 3) return b | F64_QUIET;
  if (ua.kind == 2) return ub.kind == 2 && ua.sign != ub.sign ? F64_NAN : a;
  if (ub.kind == 2) return b;
  if (ua.kind == 1) return ub.kind == 1 ? (ua.sign & ub.sign) << 63 : b;
  if (ub.kind == 1) return a;
  if (ua.e < ub.e || (ua.e == ub.e && ua.m < ub.m)) {
    f64u t = ua;
    ua = ub;
    ub = t;
  }
  uint d = uint(ua.e - ub.e);
  u128 A, B;
  A.hi = ua.m;
  A.lo = 0;
  B.hi = ub.m;
  B.lo = 0;
  bool sticky = false;
  B = u128_shr_sticky(B, d > 200 ? 200 : d, sticky);
  u128 R;
  if (ua.sign == ub.sign) {
    R = u128_add(A, B);
  } else {
    R = u128_sub(A, B);
    if (sticky) {
      // A - (B + delta) with 0 < delta < 1 unit is (R - 1) + (1 - delta): the integer part
      // drops by one and the sticky bit still says "and something below".
      u128 one;
      one.hi = 0;
      one.lo = 1;
      R = u128_sub(R, one);
    }
    if ((R.hi | R.lo) == 0 && !sticky) return 0;  // exact cancellation is +0
  }
  return f64_pack(ua.sign, R, ua.e - 116, sticky);
}

static inline f64 f64_neg(f64 a) {
  return a ^ (1ul << 63);
}

static inline f64 f64_sub(f64 a, f64 b) {
  f64u ub = f64_unpack(b);
  if (ub.kind == 3) {
    f64u ua = f64_unpack(a);
    return ua.kind == 3 ? a | F64_QUIET : b | F64_QUIET;
  }
  return f64_add(a, f64_neg(b));
}

static inline f64 f64_mul(f64 a, f64 b) {
  f64u ua = f64_unpack(a), ub = f64_unpack(b);
  if (ua.kind == 3) return a | F64_QUIET;
  if (ub.kind == 3) return b | F64_QUIET;
  ulong sign = ua.sign ^ ub.sign;
  if (ua.kind == 2 || ub.kind == 2) return ua.kind == 1 || ub.kind == 1 ? F64_NAN : (sign << 63) | F64_INF;
  if (ua.kind == 1 || ub.kind == 1) return sign << 63;
  uint a0 = uint(ua.m), a1 = uint(ua.m >> 32), b0 = uint(ub.m), b1 = uint(ub.m >> 32);
  ulong p00 = umul64(a0, b0), p01 = umul64(a0, b1), p10 = umul64(a1, b0), p11 = umul64(a1, b1);
  ulong mid = p01 + p10;  // < 2^54: no carry out
  u128 P;
  P.lo = p00 + (mid << 32);
  P.hi = p11 + (mid >> 32) + (P.lo < p00 ? 1ul : 0ul);
  return f64_pack(sign, P, ua.e + ub.e - 104, false);
}

static inline f64 f64_div(f64 a, f64 b) {
  f64u ua = f64_unpack(a), ub = f64_unpack(b);
  if (ua.kind == 3) return a | F64_QUIET;
  if (ub.kind == 3) return b | F64_QUIET;
  ulong sign = ua.sign ^ ub.sign;
  if (ua.kind == 2) return ub.kind == 2 ? F64_NAN : (sign << 63) | F64_INF;
  if (ub.kind == 2) return sign << 63;
  if (ub.kind == 1) return ua.kind == 1 ? F64_NAN : (sign << 63) | F64_INF;
  if (ua.kind == 1) return sign << 63;
  // Restoring division: q = floor(m_a * 2^55 / m_b), 55 or 56 bits, the remainder sticky.
  ulong rem = ua.m, q = 0;
  if (rem >= ub.m) {
    rem -= ub.m;
    q = 1;
  }
  for (int i = 0; i < 55; ++i) {
    rem <<= 1;
    q <<= 1;
    if (rem >= ub.m) {
      rem -= ub.m;
      q |= 1;
    }
  }
  u128 Q;
  Q.hi = 0;
  Q.lo = q;
  return f64_pack(sign, Q, ua.e - ub.e - 55, rem != 0);
}

static inline f64 f64_sqrt(f64 a) {
  f64u ua = f64_unpack(a);
  if (ua.kind == 3) return a | F64_QUIET;
  if (ua.kind == 1) return a;
  if (ua.sign != 0) return F64_NAN;
  if (ua.kind == 2) return a;
  ulong m = ua.m;
  int e = ua.e;
  if ((e & 1) != 0) {
    m <<= 1;
    e -= 1;
  }
  // Digit-by-digit: root = floor(sqrt(m * 2^58)), 56 bits, remainder sticky. The radicand
  // is aligned so its 112 bits fill the top of the 128 and two come off per step.
  u128 R;
  R.hi = 0;
  R.lo = m;
  R = u128_shl(R, 74);
  ulong root = 0, rem = 0;
  for (int i = 0; i < 56; ++i) {
    ulong two = R.hi >> 62;
    R = u128_shl(R, 2);
    rem = (rem << 2) | two;
    ulong trial = (root << 2) | 1ul;
    root <<= 1;
    if (rem >= trial) {
      rem -= trial;
      root |= 1;
    }
  }
  u128 Q;
  Q.hi = 0;
  Q.lo = root;
  return f64_pack(0, Q, (e - 52) / 2 - 29, rem != 0);
}

// -2 unordered, else the sign of a - b with the two zeros equal.
static inline int f64_cmp(f64 a, f64 b) {
  f64u ua = f64_unpack(a), ub = f64_unpack(b);
  if (ua.kind == 3 || ub.kind == 3) return -2;
  if (ua.kind == 1 && ub.kind == 1) return 0;
  if (ua.sign != ub.sign) return ua.sign != 0 ? -1 : 1;
  ulong ma = a & 0x7fffffffffffffffull, mb = b & 0x7fffffffffffffffull;
  int c = ma < mb ? -1 : (ma > mb ? 1 : 0);
  return ua.sign != 0 ? -c : c;
}

// bin_op over two f64 values: the CPU's laApply, operation for operation.
static inline f64 bin_op_f64(int op, f64 x, f64 y) {
  switch (op) {
    case 0: return f64_add(x, y);
    case 1: return f64_sub(x, y);
    case 2: return f64_mul(x, y);
    case 3: return f64_div(x, y);
    case 4: return f64_cmp(x, y) == 1 ? x : y;
    case 5: return f64_cmp(x, y) == -1 ? x : y;
    case 6: return f64_cmp(x, y) == 1 ? F64_ONE : 0;
    case 7: { int c = f64_cmp(x, y); return c == 1 || c == 0 ? F64_ONE : 0; }
    case 8: return f64_cmp(x, y) == -1 ? F64_ONE : 0;
    case 9: { int c = f64_cmp(x, y); return c == -1 || c == 0 ? F64_ONE : 0; }
    case 10: return f64_cmp(x, y) == 0 ? F64_ONE : 0;
    default: return x;
  }
}

// THIS GPU FLUSHES SUBNORMAL FLOATS TO ZERO in every float operation, compare included,
// MTLMathModeSafe or not (measured: a subnormal operand through `x * 7.0f`, `x > 0.0f`,
// fabs, sqrt all answer as if x were zero). The CPU kernels do not, and the bit-identity
// the strided and resident tiers claim has to hold for those bits too, so every kernel
// below that does float arithmetic guards it: an operand that is subnormal, or a result
// that lands in the subnormal range (where the hardware has flushed it), is recomputed
// on the software f64 route, which works on the bits and never flushes. The guard is two
// compares an element; the members that only MOVE bits (the gather, the copy, where) need
// nothing, and abs / negative / sign are written as bit operations for the same reason.
static inline bool is_subnormal(float x) {
  uint b = as_type<uint>(x);
  return (b & 0x7f800000u) == 0 && (b & 0x7fffffu) != 0;
}

// An order key for a float that the flush cannot touch: the bits, negatives reflected
// and both zeros at 0, so that key(x) < key(y) iff x < y for non-NaN x, y.
static inline int flt_key(float x) {
  int b = as_type<int>(x);
  if ((b & 0x7fffffff) == 0) return 0;
  return b < 0 ? b ^ 0x7fffffff : b;
}

static inline bool flt_nan(float x) {
  return (as_type<uint>(x) & 0x7fffffffu) > 0x7f800000u;
}

// x > y and x < y as IEEE says them -- false when either is NaN -- without the flush.
static inline bool flt_gt(float x, float y) {
  return !flt_nan(x) && !flt_nan(y) && flt_key(x) > flt_key(y);
}

static inline bool flt_lt(float x, float y) {
  return !flt_nan(x) && !flt_nan(y) && flt_key(x) < flt_key(y);
}

// The ELEMENT-WISE tier, with the member as an OP CODE parameter exactly as gemm.cu has
// it. The op codes are mirrored by Gpu.MAP_* and by gemm.cu, and the three must be
// changed together -- nothing links them. The default is the identity rather than a
// member, so a mirror that ever slipped cannot silently answer some other function.
// MSL has no erf -- it is the one member of the tier with no builtin on this device --
// so this is `linalg::%la-erf-1`'s OWN series (A&S 7.1.6, all terms positive) at float
// width, in the defun's order of operations. That makes the Metal erf closer to the
// oracle than the CUDA one is, not further: CUDA calls the device libm's erf, which is a
// different algorithm and lands ~4.5 ulps from this series at f64.
static inline float erf1(float x) {
  float ax = fabs(x);
  if (ax >= 6.0f) return x < 0.0f ? -1.0f : 1.0f;
  float term = 1.0f, total = 1.0f, xx = 2.0f * ax * ax;
  for (int n = 1; n <= 200; ++n) {
    term = term * xx / (2.0f * float(n) + 1.0f);
    total = total + term;
    if (term < 1.0e-8f * total) break;
  }
  float v = 1.1283791670955126f * ax * exp(-(ax * ax)) * total;
  return x < 0.0f ? -v : v;
}

// MSL's own tanh and sinh lose their relative precision NEAR ZERO -- measured against the
// f64 oracle narrowed to float, both carry an absolute error floor of ~3.4e-8, which is
// 1.8e-4 relative at x = 2e-4 and grows without bound as x -> 0. That is the shape of an
// exp-based formula cancelling, and it is the one place on this backend where a device
// libm is not merely a DIFFERENT last ulp but wrong in digits a user can see. Both are
// odd functions with an x + O(x^3) expansion, so below |x| = 1/4 the Maclaurin series to
// x^9 is exact to ~1e-11 relative and the builtin takes over above it, where its absolute
// floor is already under 1.4e-7 relative. (The other ten members measure 1.7e-7 to 9.7e-7
// worst relative over their whole domains and need nothing.)
#define SMALL_ARGUMENT 0.25f

static inline float tanh1(float x) {
  if (fabs(x) >= SMALL_ARGUMENT) return tanh(x);
  float xx = x * x;
  return x * (1.0f + xx * (-1.0f / 3.0f + xx * (2.0f / 15.0f + xx * (-17.0f / 315.0f + xx * (62.0f / 2835.0f)))));
}

static inline float sinh1(float x) {
  if (fabs(x) >= SMALL_ARGUMENT) return sinh(x);
  float xx = x * x;
  return x * (1.0f + xx * (1.0f / 6.0f + xx * (1.0f / 120.0f + xx * (1.0f / 5040.0f + xx * (1.0f / 362880.0f)))));
}

static inline float map_op(int op, float x) {
  switch (op) {
    case 0: return exp(x);
    case 1: return log(x);
    case 2: return tanh1(x);
    case 3: return sin(x);
    case 4: return cos(x);
    case 5: return tan(x);
    case 6: return asin(x);
    case 7: return acos(x);
    case 8: return atan(x);
    case 9: return sinh1(x);
    case 10: return cosh(x);
    case 11: return erf1(x);
    // The four members the element-wise tier REFUSED as a round trip and takes since
    // .todo/494 over a RESIDENT operand only, where there is no trip to pay for. The CPU
    // kernel computes each in f64 and narrows on the store; in float every one of them
    // lands on the same bits: a correctly rounded float sqrt IS the correctly rounded f64
    // sqrt narrowed to float (innocuous double rounding, and MTLMathModeSafe makes sqrt
    // correctly rounded), and abs, negation and sign move bits. sign is Math.signum: NaN
    // stays NaN, a signed zero keeps its sign.
    case 12: {
      // A subnormal operand is flushed to zero by this GPU's float sqrt (below), so it
      // takes the software f64 route, whose sqrt is correctly rounded like Math.sqrt's.
      // Math.sqrt's NaN is the canonical positive one; the device's carries the sign
      // bit, which a bit-identity claim cannot allow through.
      if (is_subnormal(x)) return f64_to_f(f64_sqrt(f64_from_f(x)));
      float r = precise::sqrt(x);
      return r != r ? as_type<float>(0x7fc00000u) : r;
    }
    case 13: return as_type<float>(as_type<uint>(x) & 0x7fffffffu);
    case 14: return as_type<float>(as_type<uint>(x) ^ 0x80000000u);
    case 15: {
      uint b = as_type<uint>(x);
      if ((b & 0x7fffffffu) == 0 || (b & 0x7fffffffu) > 0x7f800000u) return x;  // a signed zero, a NaN
      return (b >> 31) != 0 ? -1.0f : 1.0f;
    }
    default: return x;
  }
}

kernel void map_f32(device const float* A [[buffer(0)]],
                    device float* C [[buffer(1)]],
                    constant int* args [[buffer(2)]],
                    uint i [[thread_position_in_grid]]) {
  int n = args[0], op = args[1];
  if (int(i) < n) C[i] = map_op(op, A[i]);
}

// The STRIDED tier: a BROADCAST binary op, a strided GATHER (the axes transpose) and an
// AXIS fold. `meta` carries the layout -- the output dims, then one per-axis source
// stride per operand, all in ELEMENTS.
//
// THESE ARE BIT-IDENTICAL TO THE SCALAR DEFUN, and on this backend that is an argument
// rather than an inheritance. gemm.cu computes in `double` and narrows on the store,
// which is `%la-bcast-loop`'s own rule; MSL has no double, so this half computes in
// `float` and the claim has to be earned:
//   +, - and * over two floats are EXACT in binary64, so rounding the exact result once
//   to float -- which is what a float operation does -- is what the defun's
//   compute-in-double-then-narrow produces.
//   / is the double-rounding case, and it is innocuous here: binary64 carries 53 bits
//   and 53 >= 2 * 24 + 2, which is the classical bound under which rounding to the
//   intermediate width and then to the target agrees with rounding once.
//   The strict selects and the gather move values, so no rounding happens at all.

// Mirrored by Gpu.BIN_*, by gemm.cu and by LinalgSimdKernels.BOP_*, and the last is the
// oracle. MAX/MIN are the strict selects `(if (> x y) x y)`, so the SECOND operand wins
// a tie and a NaN.
static inline float bin_op(int op, float x, float y) {
  switch (op) {
    case 0: return x + y;
    case 1: return x - y;
    case 2: return x * y;
    case 3: return x / y;
    case 4: return x > y ? x : y;
    case 5: return x < y ? x : y;
    case 6: return x > y ? 1.0f : 0.0f;
    case 7: return x >= y ? 1.0f : 0.0f;
    case 8: return x < y ? 1.0f : 0.0f;
    case 9: return x <= y ? 1.0f : 0.0f;
    case 10: return x == y ? 1.0f : 0.0f;
    default: return x;
  }
}

// bin_op with the flush guard: the float result unless an operand is subnormal or the
// result sits where the hardware flushes, in which case the software f64 route answers.
// For the eleven ops the f64 route IS the CPU's arithmetic, so either way it is the
// CPU's bits.
static inline float bin_op_exact(int op, float x, float y) {
  float r = bin_op(op, x, y);
  if (is_subnormal(x) || is_subnormal(y) || fabs(r) < 1.17549435e-38f)
    return f64_to_f(bin_op_f64(op, f64_from_f(x), f64_from_f(y)));
  return r;
}

kernel void bcast_f32(device const float* A [[buffer(0)]],
                      device const float* B [[buffer(1)]],
                      device float* C [[buffer(2)]],
                      constant int* meta [[buffer(3)]],
                      constant int* args [[buffer(4)]],
                      uint i [[thread_position_in_grid]]) {
  int op = args[0], n = args[1], rank = args[2];
  if (int(i) >= n) return;
  int ia = 0, ib = 0, rem = int(i);
  for (int k = rank - 1; k >= 0; --k) {
    int d = meta[k];
    int c = rem % d;
    rem /= d;
    ia += c * meta[rank + k];
    ib += c * meta[2 * rank + k];
  }
  C[i] = bin_op_exact(op, A[ia], B[ib]);
}

kernel void gather_f32(device const float* A [[buffer(0)]],
                       device float* C [[buffer(1)]],
                       constant int* meta [[buffer(2)]],
                       constant int* args [[buffer(3)]],
                       uint i [[thread_position_in_grid]]) {
  int n = args[0], rank = args[1];
  if (int(i) >= n) return;
  int ia = 0, rem = int(i);
  for (int k = rank - 1; k >= 0; --k) {
    int d = meta[k];
    int c = rem % d;
    rem /= d;
    ia += c * meta[rank + k];
  }
  C[i] = A[ia];
}

// THE AXIS FOLD IS NOT A ROUND-TRIP MEMBER HERE, and that is a measurement rather than an
// omission. Two independent reasons, either of which is enough. `%la-fold-axis`
// accumulates in DOUBLE at both widths, so a float accumulator could not reproduce the sum
// bit for bit the way the three kernels above reproduce their members. And the amax/amin
// half, which needs no accumulator and would have been exact, does not pay: on an M4 Max
// the CPU fold is 85 us over 262144 f32 elements and 410 us over 1048576, against this
// backend's ~150 and ~380 for the same shapes -- a tie at best, and a tie is a decline.
// MetalGemm's fold threshold is therefore Long.MAX_VALUE. Since .todo/494 the fold IS a
// member over a RESIDENT operand (fold_f32, at the end of the file: the sum in software
// binary64, so bit-identical after all; amax/amin as bit moves), because over an operand
// that is already here the trip the refusal measured is not paid and the alternative is
// bringing the operand home for the CPU's fold. See .kb/gpu.md.

// THE GEMV behind vec:matvec -- the one member outside linalg:, and the one whose worth is
// decided by RESIDENCY rather than size: a matrix-by-vector product is one pass over W, so
// it pays only over a matrix that is already on the device, which is what the cache in
// MetalGemm keeps (DeviceResidency, the same class the CUDA half uses). One SIMD-group per
// row, lane l walking columns l, l + width, ..., then a shuffle tree across the group; the
// whole group shares one row, so the early return is group-uniform and the full-width
// shuffles below it are safe. Eight rows per threadgroup at the 32-wide SIMD-group of
// every Apple GPU, and the host derives that from threadExecutionWidth rather than
// assuming it.
//
// THE ACCUMULATOR IS COMPENSATED, AND THAT IS HOW IT LANDS ON THE DEFUN'S BITS WITHOUT A
// DOUBLE. gemm.cu sums in double at both widths and narrows on the store, which is the
// scalar vec.lisp defun's own rule and what puts the CUDA result on the defun's bits
// (1024 of 1024 rows). MSL has no double to do that with, and a plain float sum lands on
// 229 of 1024 rows (worst 2.9e-7) -- the --simd lane kernel's contract, a different
// order. So this kernel keeps the running sum as a float-float PAIR: the product's own
// rounding error is recovered exactly with an fma (p = a*b, pe = fma(a, b, -p), so that
// a*b == p + pe exactly), and each addition is Knuth's TwoSum, whose error term goes into
// the low half. The pair carries ~48 bits, against the 53 of a double; measured at
// 1024x768 over inexact data it is bit-identical to the double-accumulated oracle on
// 1024 of 1024 rows, and it costs nothing the memory-bound pass can see (the same ~90 us
// a resident call as the plain sum, .kb/gpu.md). The contraction pragma is what makes the
// error-free transforms mean what they say: a compiler free to fuse `hi + p` into an fma
// across statements would compute a different `s` than the `p` the error term was taken
// against. The Metal compiler does not do that at its defaults (measured, same 1024 rows),
// but the pragma is the rule rather than the observation, and it costs nothing. It applies
// from here to the end of the file, which is this kernel and nothing else.
#pragma METAL fp contract(off)

kernel void gemv_f32(device const float* W [[buffer(0)]],
                     device const float* x [[buffer(1)]],
                     device float* y [[buffer(2)]],
                     constant int* args [[buffer(3)]],
                     uint lane [[thread_index_in_simdgroup]],
                     uint width [[threads_per_simdgroup]],
                     uint sg [[simdgroup_index_in_threadgroup]],
                     uint sgs [[simdgroups_per_threadgroup]],
                     uint tg [[threadgroup_position_in_grid]]) {
  int rows = args[0], cols = args[1];
  int row = int(tg * sgs + sg);
  if (row >= rows) return;
  device const float* w = W + (long) row * cols;
  float hi = 0.0f, lo = 0.0f;
  for (int j = int(lane); j < cols; j += int(width)) {
    float a = w[j], b = x[j];
    float p = a * b;
    float pe = fma(a, b, -p);
    float s = hi + p;
    float bv = s - hi;
    float err = (hi - (s - bv)) + (p - bv);
    hi = s;
    lo += err + pe;
  }
  for (uint off = width / 2; off > 0; off >>= 1) {
    float ohi = simd_shuffle_down(hi, off);
    float olo = simd_shuffle_down(lo, off);
    float s = hi + ohi;
    float bv = s - hi;
    float err = (hi - (s - bv)) + (ohi - bv);
    hi = s;
    lo += olo + err;
  }
  if (lane == 0) y[row] = hi + lo;
}

// --- the resident tier's kernels (the header is beside the software binary64 above) --

// out[i] = op(A[i], B[i]) over two arrays of the same shape. Two floats: the bcast_f32
// argument, so float arithmetic lands on the CPU's bits.
kernel void zip_f32(device const float* A [[buffer(0)]],
                    device const float* B [[buffer(1)]],
                    device float* C [[buffer(2)]],
                    constant int* args [[buffer(3)]],
                    uint i [[thread_position_in_grid]]) {
  int op = args[0], n = args[1];
  if (int(i) < n) C[i] = bin_op_exact(op, A[i], B[i]);
}

// out[i] = op(A[i], s), or op(s, A[i]) when swapped. The scalar is an f64 whatever the
// array's width (laEwFS is scalar by contract): when it is exactly a float (args[3], which
// the host decides -- `(linalg:div scores (sqrt d-k))`, an integer, a single-float
// literal) the float path is the CPU's bits by the bcast argument; otherwise the software
// f64 path is, by construction.
kernel void scal_f32(device const float* A [[buffer(0)]],
                     device float* C [[buffer(1)]],
                     constant int* args [[buffer(2)]],
                     constant ulong* scalar [[buffer(3)]],
                     uint i [[thread_position_in_grid]]) {
  int op = args[0], n = args[1], swap = args[2], exact = args[3];
  if (int(i) >= n) return;
  float a = A[i];
  if (exact != 0) {
    float s = as_type<float>(uint(scalar[1]));
    C[i] = swap != 0 ? bin_op_exact(op, s, a) : bin_op_exact(op, a, s);
  } else {
    f64 s = scalar[0], x = f64_from_f(a);
    C[i] = f64_to_f(swap != 0 ? bin_op_f64(op, s, x) : bin_op_f64(op, x, s));
  }
}

// out[i] = mask != 0 ? x : y over three operands broadcast together, any of which may be a
// scalar -- linalg:where, hence torch:masked-fill. `meta` is the output dims then one
// stride vector per operand (a scalar's is ignored); args carry n, rank, and for each
// operand whether it is an array (1) or a scalar (0), the mask scalar's truth, and the two
// value scalars already narrowed on the host exactly as the CPU narrows them. A select
// moves bits, so this is the CPU's result whatever the operands.
kernel void where_f32(device const float* M [[buffer(0)]],
                      device const float* X [[buffer(1)]],
                      device const float* Y [[buffer(2)]],
                      device float* C [[buffer(3)]],
                      constant int* meta [[buffer(4)]],
                      constant int* args [[buffer(5)]],
                      constant float* scalars [[buffer(6)]],
                      uint i [[thread_position_in_grid]]) {
  int n = args[0], rank = args[1], mArray = args[2], xArray = args[3], yArray = args[4], mTrue = args[5];
  if (int(i) >= n) return;
  int im = 0, ix = 0, iy = 0, rem = int(i);
  for (int k = rank - 1; k >= 0; --k) {
    int d = meta[k];
    int c = rem % d;
    rem /= d;
    im += c * meta[rank + k];
    ix += c * meta[2 * rank + k];
    iy += c * meta[3 * rank + k];
  }
  bool take = mArray != 0 ? (as_type<uint>(M[im]) & 0x7fffffffu) != 0 : mTrue != 0;
  C[i] = take ? (xArray != 0 ? X[ix] : scalars[0]) : (yArray != 0 ? Y[iy] : scalars[1]);
}

// Adam's fused update in place over the parameter, its gradient and the two moments,
// spelled operation for operation as linalg::%la-adam-step's CPU kernel spells it and in
// its order -- and every operation a software f64 one, because the rule is ten f64 numbers
// (the bias corrections in particular are nothing a float could hold) and the CPU kernel
// keeps mk / vk at full width between the moment store and the parameter update. `rule`
// is the raw bits of lr, lr*wd, wd, b1, 1-b1, b2, 1-b2, eps, c1, c2; args are n and the
// weight-decay mode.
kernel void adam_f32(device float* X [[buffer(0)]],
                     device const float* G [[buffer(1)]],
                     device float* Mm [[buffer(2)]],
                     device float* V [[buffer(3)]],
                     constant int* args [[buffer(4)]],
                     constant ulong* rule [[buffer(5)]],
                     uint k [[thread_position_in_grid]]) {
  int n = args[0], mode = args[1];
  if (int(k) >= n) return;
  f64 lr = rule[0], lrwd = rule[1], wd = rule[2], b1 = rule[3], omb1 = rule[4], b2 = rule[5], omb2 = rule[6],
      eps = rule[7], c1 = rule[8], c2 = rule[9];
  f64 x0 = f64_from_f(X[k]);
  f64 xv = mode == 2 ? f64_sub(x0, f64_mul(lrwd, x0)) : x0;
  f64 g = f64_from_f(G[k]);
  f64 gv = mode == 1 ? f64_add(g, f64_mul(wd, x0)) : g;
  f64 mk = f64_add(f64_mul(b1, f64_from_f(Mm[k])), f64_mul(omb1, gv));
  f64 vk = f64_add(f64_mul(b2, f64_from_f(V[k])), f64_mul(f64_mul(omb2, gv), gv));
  Mm[k] = f64_to_f(mk);
  V[k] = f64_to_f(vk);
  X[k] = f64_to_f(f64_sub(xv, f64_div(f64_mul(lr, f64_div(mk, c1)), f64_add(f64_sqrt(f64_div(vk, c2)), eps))));
}

// The strided COPY: out[originC + sc . c] = A[originA + sa . c] over the output dims, one
// source stride and one destination stride per axis, either of which may be negative (a
// slice with a negative step). What reshape, the rank-2 transpose, slice and each slab of
// a concatenation are. The two origins are element offsets into the bound slabs (args),
// rather than buffer offsets, so that a negative walk from an origin inside the slab is
// plain pointer arithmetic. A pure copy, so trivially bit-identical.
kernel void copy_f32(device const float* A [[buffer(0)]],
                     device float* C [[buffer(1)]],
                     constant int* meta [[buffer(2)]],
                     constant int* args [[buffer(3)]],
                     uint i [[thread_position_in_grid]]) {
  int n = args[0], rank = args[1], originA = args[2], originC = args[3];
  if (int(i) >= n) return;
  long ia = originA, ic = originC;
  int rem = int(i);
  for (int k = rank - 1; k >= 0; --k) {
    int d = meta[k];
    int c = rem % d;
    rem /= d;
    ia += (long) c * meta[rank + k];
    ic += (long) c * meta[2 * rank + k];
  }
  C[ic] = A[ia];
}

// The axis fold, a RESIDENT-operand member here where the measured refusal above keeps it
// off the round-trip tier: out[o * inner + j] folds A[(o * len + k) * inner + j] over k
// ASCENDING, %la-fold-axis's own order. Op 0 (sum) seeds with the defun's 0 and
// accumulates in software f64, which is the one way a float kernel reproduces a sum the
// defun accumulates in f64; ops 1 and 2 (amax / amin) seed with the FIRST element and
// compare strictly, so the accumulator wins a tie and a NaN -- `(if (> x acc) x acc)` --
// and move bits. The caller declines len == 0.
kernel void fold_f32(device const float* A [[buffer(0)]],
                     device float* C [[buffer(1)]],
                     constant int* args [[buffer(2)]],
                     uint i [[thread_position_in_grid]]) {
  int op = args[0], outer = args[1], len = args[2], inner = args[3];
  int total = outer * inner;
  if (int(i) >= total) return;
  int base = (int(i) / inner) * len * inner + (int(i) % inner);
  if (op == 0) {
    f64 acc = 0;
    for (int k = 0; k < len; ++k) acc = f64_add(acc, f64_from_f(A[base + k * inner]));
    C[i] = f64_to_f(acc);
  } else {
    float acc = A[base];
    for (int k = 1; k < len; ++k) {
      float x = A[base + k * inner];
      if (op == 1 ? flt_gt(x, acc) : flt_lt(x, acc)) acc = x;
    }
    C[i] = acc;
  }
}
