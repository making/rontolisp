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
//
// A TRANSPOSED OPERAND IS READ IN PLACE (todo-631). `ta` / `tb` say that the operand's
// last two axes are exchanged: its M x K (or K x N) matrix is STORED K x M (or N x K),
// and the kernel indexes it that way rather than being handed a gather's copy of it --
// which is what the linear backward's `g . b^T` and `a^T . g` used to pay a whole strided
// pass for. The TILE the fold reads is the same tile either way; only the address the
// staging load comes from changes, so every cell still folds k ascending through the same
// products and the result is bit-identical to the plain product of the transposed copy.
// What does change is the load PATTERN: a transposed operand is walked down its columns,
// so the staging swaps the roles of the two thread indices to keep a SIMD group's global
// reads contiguous, and the tiles carry one column of padding so the transposed store is
// bank-conflict-free. gemm.cu carries the same two flags, in the same staging.
kernel void gemm_batched_f32(device const float* A [[buffer(0)]],
                             device const float* B [[buffer(1)]],
                             device float* C [[buffer(2)]],
                             constant int* dims [[buffer(3)]],
                             uint3 tid [[thread_position_in_threadgroup]],
                             uint3 gp [[threadgroup_position_in_grid]]) {
  threadgroup float As[TILE][TILE + 1];
  threadgroup float Bs[TILE][TILE + 1];
  int M = dims[0], N = dims[1], K = dims[2], strideA = dims[3], strideB = dims[4];
  int ta = dims[5], tb = dims[6];
  int z = int(gp.z);
  const device float* Ab = A + (long) z * strideA;
  const device float* Bb = B + (long) z * strideB;
  device float* Cb = C + (long) z * M * N;
  int tx = int(tid.x), ty = int(tid.y);
  int row0 = int(gp.y) * TILE, col0 = int(gp.x) * TILE;
  int row = row0 + ty;
  int col = col0 + tx;
  float acc = 0.0f;
  int tiles = (K + TILE - 1) / TILE;
  for (int t = 0; t < tiles; ++t) {
    int ac = t * TILE + tx, br = t * TILE + ty;
    if (ta) {
      int ak = t * TILE + ty, am = row0 + tx;
      As[tx][ty] = (am < M && ak < K) ? Ab[ak * (long) M + am] : 0.0f;
    }
    else {
      As[ty][tx] = (row < M && ac < K) ? Ab[row * (long) K + ac] : 0.0f;
    }
    if (tb) {
      int bk = t * TILE + tx, bn = col0 + ty;
      Bs[tx][ty] = (bk < K && bn < N) ? Bb[bn * (long) K + bk] : 0.0f;
    }
    else {
      Bs[ty][tx] = (br < K && col < N) ? Bb[br * (long) N + col] : 0.0f;
    }
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

// --- THE FUSED TIER (todo-636) -----------------------------------------------------
// The compositions `torch.lisp` spells as a chain of `linalg:` members -- the exact GELU
// and its adjoint, the last-axis softmax and its adjoint, the last-axis log-softmax and
// its adjoint, layer-norm's normalization and its adjoint -- each as ONE kernel. What a
// fused kernel buys is the memory passes it removes: a chain of five bandwidth-bound
// passes over an activation is five times the traffic of one, and on THIS backend it also
// removes four of five COMMAND BUFFERS, each of which is a ~77 us floor and a full wait.
//
// THE CONTRACT IS THE CHAIN'S, ROUNDING FOR ROUNDING, and here that is an argument rather
// than an inheritance. gemm.cu computes every member boundary as `(T)((double) a op
// (double) b)`; MSL has no double, so each boundary is taken one of two ways, exactly as
// the strided and resident tiers take theirs:
//   - both operands floats -> the float operation under the flush guard (R_ADD and its
//     siblings below), which IS that rounding by the bcast_f32 argument;
//   - an operand the float grid does not hold -- 1/sqrt(2), 2/sqrt(pi), the layer-norm
//     eps, a row length past 2^24 -- or an accumulator the chain keeps in f64 -> the
//     software binary64 route, which is the CPU's arithmetic by construction.
// So a fused member lands on the bits the member chain would have produced, whichever
// rung of the chain ran where. MetalGpuTest asserts EQUALITY against the chain run member
// by member, libm members included.
//
// The row kernels keep ONE THREAD PER ROW, because a sequential f64 fold has no
// lane-parallel form that keeps its bits. Thirty-two threads reading thirty-two rows read
// addresses a row apart, so the rows go through a TRANSPOSED TILE in threadgroup memory,
// thirty-two columns at a time: the SIMD group loads column c of its thirty-two rows with
// one coalesced instruction per row, each lane then walks its own row across the tile
// (stride 33, conflict-free), and a result goes back the same way. gemm.cu's row kernels
// have the same layout and the same reason.
#define ROW_SIMDS 2

struct row_tile {
  float v[32][33];
};

// The chain's member boundaries at this width. The op codes are bin_op's.
#define R_ADD(a, b) bin_op_exact(0, (a), (b))
#define R_SUB(a, b) bin_op_exact(1, (a), (b))
#define R_MUL(a, b) bin_op_exact(2, (a), (b))
#define R_DIV(a, b) bin_op_exact(3, (a), (b))

// The same boundary against a double the float grid does not hold: the operation is the
// software f64 one over the widened float, narrowed on the store -- `(T)((double) a op s)`
// literally, and what scal_f32's inexact path already does for the same scalars.
static inline float wide_op(int op, float a, f64 s) {
  return f64_to_f(bin_op_f64(op, f64_from_f(a), s));
}

static inline float wide_op_swapped(int op, f64 s, float a) {
  return f64_to_f(bin_op_f64(op, s, f64_from_f(a)));
}

#define F64_SQRT2 0x3ff6a09e667f3bcdul
#define F64_TWO_OVER_SQRT_PI 0x3ff20dd750429b6dul

// A row length divides an f64 accumulator in the chain (`(linalg:div (linalg:sum r) n)`);
// every length a row kernel is offered at is far below 2^24 and therefore exactly a
// float, but the kernel does not assume it.
static inline float divide_by_length(float a, int len) {
  return len < (1 << 24) ? R_DIV(a, float(len)) : wide_op(3, a, f64_from_f(float(len)));
}

// Columns [c0, c0 + 32) of rows [row0, row0 + 32) into the tile; a cell outside the
// operand reads as zero and is never stored back. The barriers make the tile safe to
// reuse: nobody loads before every lane has finished the previous chunk, nobody reads
// before every column has landed. The whole SIMD group shares one tile, so the barrier is
// the SIMD group's.
static inline void tile_load(threadgroup row_tile& tile, device const float* A, int rows, int len, int row0, int c0,
                             int lane) {
  simdgroup_barrier(mem_flags::mem_threadgroup);
  int col = c0 + lane;
  for (int r = 0; r < 32; ++r) {
    int row = row0 + r;
    tile.v[r][lane] = (row < rows && col < len) ? A[(long) row * len + col] : 0.0f;
  }
  simdgroup_barrier(mem_flags::mem_threadgroup);
}

static inline void tile_store(threadgroup row_tile& tile, device float* C, int rows, int len, int row0, int c0,
                              int lane) {
  simdgroup_barrier(mem_flags::mem_threadgroup);
  int col = c0 + lane;
  for (int r = 0; r < 32; ++r) {
    int row = row0 + r;
    if (row < rows && col < len) C[(long) row * len + col] = tile.v[r][lane];
  }
  simdgroup_barrier(mem_flags::mem_threadgroup);
}

// x * (1 + erf(x / sqrt 2)) / 2 as `torch:gelu` composes it: mul by 0.5, div by sqrt 2,
// erf, add to 1, mul -- five members, five roundings. The divisor is not a float, so that
// one boundary is the software f64 route, which is the route the chain's own scal_f32
// takes for it.
static inline float gelu_forward(float x) {
  float t1 = R_MUL(x, 0.5f);
  float t2 = wide_op(3, x, F64_SQRT2);
  float t3 = erf1(t2);
  float t4 = R_ADD(1.0f, t3);
  return R_MUL(t1, t4);
}

kernel void gelu_f32(device const float* X [[buffer(0)]],
                     device float* C [[buffer(1)]],
                     constant int* args [[buffer(2)]],
                     uint i [[thread_position_in_grid]]) {
  int n = args[0];
  if (int(i) < n) C[i] = gelu_forward(X[i]);
}

// The tape's backward through those five members, in the tape's order: the outer mul
// hands g * t4 to the 0.5-branch and g * t1 to the erf branch; erf's adjoint is
// g * (2/sqrt(pi)) * exp(-t2^2); the div branch lands on x first (b) and the mul branch
// second (a), each after the gradient x had already accumulated (OLD, when args[1] says
// there is one).
kernel void gelu_grad_f32(device const float* G [[buffer(0)]],
                          device const float* X [[buffer(1)]],
                          device const float* OLD [[buffer(2)]],
                          device float* C [[buffer(3)]],
                          constant int* args [[buffer(4)]],
                          uint i [[thread_position_in_grid]]) {
  int n = args[0], hasOld = args[1];
  if (int(i) >= n) return;
  float x = X[i], g = G[i];
  float t1 = R_MUL(x, 0.5f);
  float t2 = wide_op(3, x, F64_SQRT2);
  float t3 = erf1(t2);
  float t4 = R_ADD(1.0f, t3);
  float g1 = R_MUL(g, t4);
  float g4 = R_MUL(g, t1);
  float sq = R_MUL(t2, t2);
  float ng = as_type<float>(as_type<uint>(sq) ^ 0x80000000u);
  float ex = exp(ng);
  float sc = wide_op_swapped(2, F64_TWO_OVER_SQRT_PI, ex);
  float g2 = R_MUL(g4, sc);
  float b = wide_op(3, g2, F64_SQRT2);
  float a = R_MUL(g1, 0.5f);
  float acc = hasOld != 0 ? R_ADD(OLD[i], b) : b;
  C[i] = R_ADD(acc, a);
}

// `linalg:softmax` over the last axis: amax (the strict fold, seeded with the first
// element), the broadcast sub, exp at the width, the sum fold, the broadcast div. Three
// passes over the row: the max, exp into the result with the sum, the division in place.
kernel void softmax_f32(device const float* A [[buffer(0)]],
                        device float* C [[buffer(1)]],
                        constant int* args [[buffer(2)]],
                        uint tid [[thread_position_in_threadgroup]],
                        uint gid [[threadgroup_position_in_grid]]) {
  threadgroup row_tile tiles[ROW_SIMDS];
  int rows = args[0], len = args[1];
  int simd = int(tid) >> 5, lane = int(tid) & 31;
  threadgroup row_tile& tile = tiles[simd];
  int row0 = (int(gid) * ROW_SIMDS + simd) * 32;
  if (row0 >= rows) return;
  float m = 0.0f;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, A, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float x = tile.v[lane][j];
      if (c0 + j == 0 || flt_gt(x, m)) m = x;
    }
  }
  f64 sum = 0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, A, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float s = R_SUB(tile.v[lane][j], m);
      float e = exp(s);
      tile.v[lane][j] = e;
      sum = f64_add(sum, f64_from_f(e));
    }
    tile_store(tile, C, rows, len, row0, c0, lane);
  }
  float d = f64_to_f(sum);
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, C, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) tile.v[lane][j] = R_DIV(tile.v[lane][j], d);
    tile_store(tile, C, rows, len, row0, c0, lane);
  }
}

// `torch:softmax`'s adjoint, out * (g - sum(g * out)): the zip mul, the sum fold, the
// broadcast sub, the zip mul. No libm anywhere.
kernel void softmax_grad_f32(device const float* G [[buffer(0)]],
                             device const float* O [[buffer(1)]],
                             device float* C [[buffer(2)]],
                             constant int* args [[buffer(3)]],
                             uint tid [[thread_position_in_threadgroup]],
                             uint gid [[threadgroup_position_in_grid]]) {
  threadgroup row_tile tiles[ROW_SIMDS][2];
  int rows = args[0], len = args[1];
  int simd = int(tid) >> 5, lane = int(tid) & 31;
  threadgroup row_tile& gt = tiles[simd][0];
  threadgroup row_tile& ot = tiles[simd][1];
  int row0 = (int(gid) * ROW_SIMDS + simd) * 32;
  if (row0 >= rows) return;
  f64 sum = 0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(gt, G, rows, len, row0, c0, lane);
    tile_load(ot, O, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float p = R_MUL(gt.v[lane][j], ot.v[lane][j]);
      sum = f64_add(sum, f64_from_f(p));
    }
  }
  float tot = f64_to_f(sum);
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(gt, G, rows, len, row0, c0, lane);
    tile_load(ot, O, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float q = R_SUB(gt.v[lane][j], tot);
      ot.v[lane][j] = R_MUL(ot.v[lane][j], q);
    }
    tile_store(ot, C, rows, len, row0, c0, lane);
  }
}

// `linalg:log-softmax` over the last axis: amax, the broadcast sub, exp at the width, the
// sum fold, the log of that sum at the width, the broadcast sub. Three passes over the
// row -- the deviation is RECOMPUTED in the third rather than stored, which is a pass
// saved and the same bits, since it is the same subtraction.
kernel void log_softmax_f32(device const float* A [[buffer(0)]],
                            device float* C [[buffer(1)]],
                            constant int* args [[buffer(2)]],
                            uint tid [[thread_position_in_threadgroup]],
                            uint gid [[threadgroup_position_in_grid]]) {
  threadgroup row_tile tiles[ROW_SIMDS];
  int rows = args[0], len = args[1];
  int simd = int(tid) >> 5, lane = int(tid) & 31;
  threadgroup row_tile& tile = tiles[simd];
  int row0 = (int(gid) * ROW_SIMDS + simd) * 32;
  if (row0 >= rows) return;
  float m = 0.0f;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, A, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float x = tile.v[lane][j];
      if (c0 + j == 0 || flt_gt(x, m)) m = x;
    }
  }
  f64 sum = 0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, A, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float s = R_SUB(tile.v[lane][j], m);
      sum = f64_add(sum, f64_from_f(exp(s)));
    }
  }
  float lg = log(f64_to_f(sum));
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, A, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float s = R_SUB(tile.v[lane][j], m);
      tile.v[lane][j] = R_SUB(s, lg);
    }
    tile_store(tile, C, rows, len, row0, c0, lane);
  }
}

// `torch:log-softmax`'s adjoint, g - exp(out) * sum(g): the sum fold, exp of the forward
// result at the width, the broadcast mul, the zip sub. Two passes over the row.
kernel void log_softmax_grad_f32(device const float* G [[buffer(0)]],
                                 device const float* O [[buffer(1)]],
                                 device float* C [[buffer(2)]],
                                 constant int* args [[buffer(3)]],
                                 uint tid [[thread_position_in_threadgroup]],
                                 uint gid [[threadgroup_position_in_grid]]) {
  threadgroup row_tile tiles[ROW_SIMDS][2];
  int rows = args[0], len = args[1];
  int simd = int(tid) >> 5, lane = int(tid) & 31;
  threadgroup row_tile& gt = tiles[simd][0];
  threadgroup row_tile& ot = tiles[simd][1];
  int row0 = (int(gid) * ROW_SIMDS + simd) * 32;
  if (row0 >= rows) return;
  f64 sum = 0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(gt, G, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) sum = f64_add(sum, f64_from_f(gt.v[lane][j]));
  }
  float tot = f64_to_f(sum);
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(gt, G, rows, len, row0, c0, lane);
    tile_load(ot, O, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float e = exp(ot.v[lane][j]);
      float p = R_MUL(e, tot);
      gt.v[lane][j] = R_SUB(gt.v[lane][j], p);
    }
    tile_store(gt, C, rows, len, row0, c0, lane);
  }
}

// The row's mean and standard deviation as the torch composition computes them: the sum
// fold divided by the length, the sum fold of the squared deviations divided by the
// length, plus eps, the square root -- every one a member boundary. eps is a double the
// float grid generally does not hold, so that boundary is the software f64 one.
static inline void row_stats(threadgroup row_tile& tile, device const float* X, int rows, int len, int row0, int lane,
                             f64 eps, thread float& mu, thread float& sd) {
  f64 acc = 0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, X, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) acc = f64_add(acc, f64_from_f(tile.v[lane][j]));
  }
  mu = divide_by_length(f64_to_f(acc), len);
  acc = 0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, X, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float dev = R_SUB(tile.v[lane][j], mu);
      acc = f64_add(acc, f64_from_f(R_MUL(dev, dev)));
    }
  }
  float v = divide_by_length(f64_to_f(acc), len);
  sd = map_op(12, wide_op(0, v, eps));
}

// `torch:layer-norm`'s normalization (x - mean) / sqrt(var + eps) over the last axis, as
// its torch composition spells it (row_stats), then the broadcast division.
kernel void layer_norm_f32(device const float* X [[buffer(0)]],
                           device float* C [[buffer(1)]],
                           constant int* args [[buffer(2)]],
                           constant ulong* eps [[buffer(3)]],
                           uint tid [[thread_position_in_threadgroup]],
                           uint gid [[threadgroup_position_in_grid]]) {
  threadgroup row_tile tiles[ROW_SIMDS];
  int rows = args[0], len = args[1];
  int simd = int(tid) >> 5, lane = int(tid) & 31;
  threadgroup row_tile& tile = tiles[simd];
  int row0 = (int(gid) * ROW_SIMDS + simd) * 32;
  if (row0 >= rows) return;
  float mu, sd;
  row_stats(tile, X, rows, len, row0, lane, eps[0], mu, sd);
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, X, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float dev = R_SUB(tile.v[lane][j], mu);
      tile.v[lane][j] = R_DIV(dev, sd);
    }
    tile_store(tile, C, rows, len, row0, c0, lane);
  }
}

// The tape's backward through that composition, with g the gradient of the normalized
// output. x feeds four nodes -- the two means and the two subtractions (torch:var takes
// its own mean) -- and the reverse walk reaches them as dev2 (a1, through the squared
// deviations), the variance's mean (a2), dev (a3) and the mean (a4), adding each into x's
// gradient in that order after whatever it already held (OLD). The reductions along the
// way are the sum fold over the row, sequential, of the negated per-element terms; the two
// mean adjoints stretch a scalar back over the row through a broadcast add onto zeros,
// whose one visible effect (-0.0 + 0.0 = +0.0) is kept. gemm.cu's kernel is this one.
kernel void layer_norm_grad_f32(device const float* G [[buffer(0)]],
                                device const float* X [[buffer(1)]],
                                device const float* OLD [[buffer(2)]],
                                device float* C [[buffer(3)]],
                                constant int* args [[buffer(4)]],
                                constant ulong* eps [[buffer(5)]],
                                uint tid [[thread_position_in_threadgroup]],
                                uint gid [[threadgroup_position_in_grid]]) {
  threadgroup row_tile tiles[ROW_SIMDS][2];
  int rows = args[0], len = args[1], hasOld = args[2];
  int simd = int(tid) >> 5, lane = int(tid) & 31;
  threadgroup row_tile& xt = tiles[simd][0];
  threadgroup row_tile& gt = tiles[simd][1];
  int row0 = (int(gid) * ROW_SIMDS + simd) * 32;
  if (row0 >= rows) return;
  float mu, sd;
  row_stats(xt, X, rows, len, row0, lane, eps[0], mu, sd);
  // The division's adjoints: g / sd towards dev (a3), and towards sd the fold of
  // -((g * dev) / (sd * sd)); the subtraction's adjoint towards the mean, the fold of
  // -(g / sd).
  float q = R_MUL(sd, sd);
  f64 acc_sd = 0, acc_mu = 0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(xt, X, rows, len, row0, c0, lane);
    tile_load(gt, G, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float dev = R_SUB(xt.v[lane][j], mu);
      float gk = gt.v[lane][j];
      float rr = R_DIV(R_MUL(gk, dev), q);
      acc_sd = f64_add(acc_sd, f64_from_f(as_type<float>(as_type<uint>(rr) ^ 0x80000000u)));
      float gdev = R_DIV(gk, sd);
      acc_mu = f64_add(acc_mu, f64_from_f(as_type<float>(as_type<uint>(gdev) ^ 0x80000000u)));
    }
  }
  float g_sd = f64_to_f(acc_sd);
  float g_mu = f64_to_f(acc_mu);
  // sqrt's adjoint g / (2 sd), the division by the length, the broadcast onto zeros.
  float g_ve = R_DIV(g_sd, R_MUL(2.0f, sd));
  float g_sq = R_ADD(0.0f, divide_by_length(g_ve, len));
  // dev2 * dev2's adjoint reaches dev2 twice (g_sq * dev2, added to itself), which is a1;
  // the variance's own mean gets the fold of -a1 and hands back a2.
  f64 acc_m2 = 0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(xt, X, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float p = R_MUL(g_sq, R_SUB(xt.v[lane][j], mu));
      float gd2 = R_ADD(p, p);
      acc_m2 = f64_add(acc_m2, f64_from_f(as_type<float>(as_type<uint>(gd2) ^ 0x80000000u)));
    }
  }
  float a2 = divide_by_length(R_ADD(0.0f, f64_to_f(acc_m2)), len);
  float a4 = divide_by_length(R_ADD(0.0f, g_mu), len);
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(xt, X, rows, len, row0, c0, lane);
    tile_load(gt, G, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float p = R_MUL(g_sq, R_SUB(xt.v[lane][j], mu));
      float gd2 = R_ADD(p, p);
      float gdev = R_DIV(gt.v[lane][j], sd);
      xt.v[lane][j] = gd2;
      gt.v[lane][j] = gdev;
    }
    if (hasOld != 0) {
      int row = row0 + lane;
      for (int j = 0; j < 32 && c0 + j < len; ++j) {
        if (row < rows) {
          float o = OLD[(long) row * len + c0 + j];
          xt.v[lane][j] = R_ADD(o, xt.v[lane][j]);
        }
      }
    }
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      float a = R_ADD(xt.v[lane][j], a2);
      a = R_ADD(a, gt.v[lane][j]);
      gt.v[lane][j] = R_ADD(a, a4);
    }
    tile_store(gt, C, rows, len, row0, c0, lane);
  }
}
