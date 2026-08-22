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
    default: return x;
  }
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
  C[i] = bin_op(op, A[ia], B[ib]);
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

// THE AXIS FOLD IS NOT HERE, and that is a measurement rather than an omission. Two
// independent reasons, either of which is enough. `%la-fold-axis` accumulates in DOUBLE
// at both widths, so a float accumulator could not reproduce the sum bit for bit the way
// the three kernels above reproduce their members. And the amax/amin half, which needs no
// accumulator and would have been exact, does not pay: on an M4 Max the CPU fold is
// 85 us over 262144 f32 elements and 410 us over 1048576, against this backend's ~150 and
// ~380 for the same shapes -- a tie at best, and a tie is a decline. See .kb/gpu.md.

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
