// The kernels behind --gpu -- the tiled GEMMs, the GEMV, the maps and more, at f32 and f64.
// gemm.cu is the source, gemm.ptx is what nvcc makes of it, and the two are checked in
// TOGETHER and regenerated together -- from the repository root, with a CUDA toolkit
// installed. The toolkit is a DEVELOPER requirement only: at run time the NVIDIA driver
// JIT-compiles this PTX itself, and libcuda.so.1 is the whole dependency.
//
//   nvcc -arch=compute_75 -ptx -fmad=false src/main/resources/am/ik/gpu/gemm.cu -o /tmp/gemm.ptx
//   sed -n '1,12p' src/main/resources/am/ik/gpu/gemm.cu > src/main/resources/am/ik/gpu/gemm.ptx
//   cat /tmp/gemm.ptx >> src/main/resources/am/ik/gpu/gemm.ptx
//
// compute_75 (Turing, 2018) is the floor because CUDA 13 refuses to target anything older,
// not because we chose it. -fmad=false (todo-499): nvcc may otherwise contract `a * b + c`
// into one fused multiply-add anywhere, and every kernel here that promises the CPU's
// bits rounds the product and the sum separately; the products that DO fuse (the GEMMs,
// the GEMV) say so with an explicit fma(), which the flag leaves alone. See .kb/gpu.md.

#define TILE 16

template <typename T>
__device__ void gemm(const T* A, const T* B, T* C, int M, int N, int K) {
  __shared__ T As[TILE][TILE];
  __shared__ T Bs[TILE][TILE];
  int tx = threadIdx.x, ty = threadIdx.y;
  int row = blockIdx.y * TILE + ty;
  int col = blockIdx.x * TILE + tx;
  T acc = 0;
  for (int t = 0; t < (K + TILE - 1) / TILE; ++t) {
    int ac = t * TILE + tx, br = t * TILE + ty;
    As[ty][tx] = (row < M && ac < K) ? A[row * (long) K + ac] : (T) 0;
    Bs[ty][tx] = (br < K && col < N) ? B[br * (long) N + col] : (T) 0;
    __syncthreads();
    for (int k = 0; k < TILE; ++k) acc = fma(As[ty][k], Bs[k][tx], acc);
    __syncthreads();
  }
  if (row < M && col < N) C[row * (long) N + col] = acc;
}

extern "C" __global__ void gemm_f32(const float* A, const float* B, float* C, int M, int N, int K) {
  gemm<float>(A, B, C, M, N, K);
}

extern "C" __global__ void gemm_f64(const double* A, const double* B, double* C, int M, int N, int K) {
  gemm<double>(A, B, C, M, N, K);
}

// The BATCHED siblings: gridDim.z is the batch axis and each block does the same tile
// walk over one slab, so a stacked product is one launch rather than one per matrix. The
// strides are in ELEMENTS and a broadcast operand simply passes 0 -- the whole batch then
// reads the same slab, which is what linalg::%la-matmul-nd's zero batch stride means.
// C is always contiguous (M * N per batch). The gemm<T> above is called unchanged, so a
// batched cell folds k exactly as the unbatched kernel's does.
template <typename T>
__device__ void gemm_batched(const T* A, const T* B, T* C, int M, int N, int K, long long strideA, long long strideB) {
  long long z = blockIdx.z;
  gemm<T>(A + z * strideA, B + z * strideB, C + z * (long long) M * N, M, N, K);
}

extern "C" __global__ void gemm_batched_f32(const float* A, const float* B, float* C, int M, int N, int K,
                                            long long strideA, long long strideB) {
  gemm_batched<float>(A, B, C, M, N, K, strideA, strideB);
}

extern "C" __global__ void gemm_batched_f64(const double* A, const double* B, double* C, int M, int N, int K,
                                            long long strideA, long long strideB) {
  gemm_batched<double>(A, B, C, M, N, K, strideA, strideB);
}

// The REGISTER-TILED f32 siblings (2026-08-22): the same product over a 64x64 or a 128x128
// block tile, 16x16 threads, each thread owning a TM x TN patch of the output -- rows
// ty + i*16 and columns tx + j*16, so a warp's global loads and stores stay contiguous and
// its shared-memory reads are conflict-free. The 16x16 kernel above moves one element of A
// and one of B through shared memory per multiply-add; this one moves them once per TM / TN
// multiply-adds, and at a transformer's feed-forward shapes and n >= 1024 that is 2.7-4x on
// the GB10 (.kb/gpu.md, "The register-tiled f32 GEMM"). Below about half the device's
// SMs in 128x128 tiles it LOSES to the kernel above -- the grid is too small to fill the
// card -- which is why CudaGemm picks the tile per shape and keeps the 16x16 kernel for
// f64 (where the scarce double units make every tile the same speed) and for everything
// small.
//
// THE FOLD IS THE 16x16 KERNEL'S, BIT FOR BIT: every cell accumulates k ascending from +0
// through one fused multiply-add per term, over K rounded up to 16 with zero padding, which
// is exactly what gemm<T> does. So which tile ran is not observable in the result, and
// CudaGemm may choose freely. gemm_batched_f32_t* takes the batched parameter block (the
// strides) at every batch size, including 1.
template <typename T, int TM, int TN>
__device__ void gemm_tiled(const T* A, const T* B, T* C, int M, int N, int K) {
  constexpr int BM = 16 * TM, BN = 16 * TN, BK = TILE;
  __shared__ T As[BK][BM];
  __shared__ T Bs[BK][BN];
  int tx = threadIdx.x, ty = threadIdx.y;
  int tid = ty * 16 + tx;
  int row0 = blockIdx.y * BM, col0 = blockIdx.x * BN;
  T acc[TM][TN];
  for (int i = 0; i < TM; ++i)
    for (int j = 0; j < TN; ++j) acc[i][j] = 0;
  for (int t = 0; t < (K + BK - 1) / BK; ++t) {
    int k0 = t * BK;
    for (int e = tid; e < BM * BK; e += 256) {
      int m = e / BK, k = e % BK;
      int gr = row0 + m, gk = k0 + k;
      As[k][m] = (gr < M && gk < K) ? A[gr * (long) K + gk] : (T) 0;
    }
    for (int e = tid; e < BK * BN; e += 256) {
      int k = e / BN, n = e % BN;
      int gk = k0 + k, gc = col0 + n;
      Bs[k][n] = (gk < K && gc < N) ? B[gk * (long) N + gc] : (T) 0;
    }
    __syncthreads();
    for (int k = 0; k < BK; ++k) {
      T a[TM], b[TN];
      for (int i = 0; i < TM; ++i) a[i] = As[k][ty + i * 16];
      for (int j = 0; j < TN; ++j) b[j] = Bs[k][tx + j * 16];
      for (int i = 0; i < TM; ++i)
        for (int j = 0; j < TN; ++j) acc[i][j] = fma(a[i], b[j], acc[i][j]);
    }
    __syncthreads();
  }
  for (int i = 0; i < TM; ++i) {
    int r = row0 + ty + i * 16;
    if (r >= M) continue;
    for (int j = 0; j < TN; ++j) {
      int c = col0 + tx + j * 16;
      if (c < N) C[r * (long) N + c] = acc[i][j];
    }
  }
}

template <typename T, int TM, int TN>
__device__ void gemm_tiled_batched(const T* A, const T* B, T* C, int M, int N, int K, long long strideA,
                                   long long strideB) {
  long long z = blockIdx.z;
  gemm_tiled<T, TM, TN>(A + z * strideA, B + z * strideB, C + z * (long long) M * N, M, N, K);
}

extern "C" __global__ void gemm_batched_f32_t4(const float* A, const float* B, float* C, int M, int N, int K,
                                               long long strideA, long long strideB) {
  gemm_tiled_batched<float, 4, 4>(A, B, C, M, N, K, strideA, strideB);
}

extern "C" __global__ void gemm_batched_f32_t8(const float* A, const float* B, float* C, int M, int N, int K,
                                               long long strideA, long long strideB) {
  gemm_tiled_batched<float, 8, 8>(A, B, C, M, N, K, strideA, strideB);
}

// The ELEMENT-WISE tier: one unary map per width, with the member selected by an OP CODE
// parameter rather than by an entry point of its own. One entry point per width keeps the
// module lookup fixed however the member set grows, and the branch is uniform across the
// whole grid, so it costs nothing measurable next to a libm call.
//
// The op codes below are mirrored by Gpu.MAP_* and the two must be changed together --
// the Java side names them, this side switches on them, and nothing links the pair. Only
// the members whose scalar cost is a libm CALL are here: the device beats the CPU on
// those by 9-124x at f64 and up to 394x at f32 (.kb/gpu.md), while sqrt / abs / negative
// / sign and the binary add / sub / mul / div move one or three streams for one machine
// instruction and cannot pay for a round trip. They are declines, measured as such.
template <typename T>
__device__ T map_op(int op, T x) {
  switch (op) {
    case 0: return exp(x);
    case 1: return log(x);
    case 2: return tanh(x);
    case 3: return sin(x);
    case 4: return cos(x);
    case 5: return tan(x);
    case 6: return asin(x);
    case 7: return acos(x);
    case 8: return atan(x);
    case 9: return sinh(x);
    case 10: return cosh(x);
    case 11: return erf(x);
    // The four members the element-wise tier REFUSED as a round trip -- one machine
    // instruction over one stream -- and takes since .todo/491 over a RESIDENT operand,
    // where there is no trip to pay for. Each computes in DOUBLE and narrows on the store,
    // which lands on the CPU kernel's bits: a correctly rounded double sqrt narrowed to
    // float IS the correctly rounded float sqrt (innocuous double rounding), and the other
    // three are exact. sign is Math.signum: NaN stays NaN, a signed zero keeps its sign.
    case 12: {
      // Math.sqrt's NaN is the canonical positive one; the device's carries the sign
      // bit, which a bit-identity claim cannot allow through.
      double r = sqrt((double) x);
      return (T) (r != r ? __longlong_as_double(0x7ff8000000000000LL) : r);
    }
    case 13: return (T) fabs((double) x);
    case 14: return (T) (-(double) x);
    case 15: {
      double d = (double) x;
      return (T) (d > 0.0 ? 1.0 : (d < 0.0 ? -1.0 : d));
    }
    // Unreachable: Gpu.map declines an op code it does not name, so the identity here
    // is a belt-and-braces answer rather than a member. It must NOT fall into a real
    // member -- a wrong op code answering erf() would be a silently wrong result.
    default: return x;
  }
}

template <typename T>
__device__ void map(const T* A, T* C, int n, int op) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i < n) C[i] = map_op<T>(op, A[i]);
}

extern "C" __global__ void map_f32(const float* A, float* C, int n, int op) {
  map<float>(A, C, n, op);
}

extern "C" __global__ void map_f64(const double* A, double* C, int n, int op) {
  map<double>(A, C, n, op);
}

// The STRIDED tier: the three shapes whose CPU twin is a SCALAR ODOMETER walk rather than
// a lane loop -- a BROADCAST binary op, an AXIS fold, and an axes TRANSPOSE. The
// element-wise tier refused the binary ops because it measured them at EQUAL shapes, where
// the CPU runs a lane loop and a round trip cannot win; at unequal shapes the CPU walks an
// odometer element by element and the same round trip wins by 3-8x (.kb/gpu.md).
//
// All three compute in DOUBLE at both widths and narrow only on the store, which is the
// `%la-bcast-loop` / `%la-fold-axis` rule verbatim -- so unlike the element-wise tier
// above they are BIT-IDENTICAL to the scalar defun. `meta` carries the layout: the output
// dims, then one per-axis source stride per operand, all in ELEMENTS.

#define STRIDED_BLOCK 256

// The layout rides BY VALUE in the kernel parameter block rather than in a device
// buffer: a buffer took one pooled allocation and one synchronous host-to-device copy
// per call, and that copy DRAINED the null-stream queue -- every strided launch
// serialized behind the previous kernel's whole runtime (.kb/gpu.md). 64 ints is the
// output dims plus three stride vectors at the rank ceiling of 16.
typedef struct {
  int v[64];
} strided_meta;

// Mirrored by Gpu.BIN_* and by LinalgSimdKernels.BOP_* -- three copies of one table, and
// the last is the oracle. MAX/MIN are the strict selects `(if (> x y) x y)`, so the
// SECOND operand wins a tie and a NaN.
__device__ double bin_op(int op, double x, double y) {
  switch (op) {
    case 0: return x + y;
    case 1: return x - y;
    case 2: return x * y;
    case 3: return x / y;
    case 4: return x > y ? x : y;
    case 5: return x < y ? x : y;
    // The five comparison masks (1.0 where the relation holds, else 0.0) --
    // LinalgSimdKernels' OP_GT .. OP_EQ, the dropout mask's `greater` among them.
    case 6: return x > y ? 1.0 : 0.0;
    case 7: return x >= y ? 1.0 : 0.0;
    case 8: return x < y ? 1.0 : 0.0;
    case 9: return x <= y ? 1.0 : 0.0;
    case 10: return x == y ? 1.0 : 0.0;
    // Unreachable: Gpu declines an op code it does not name. Not a member, deliberately.
    default: return x;
  }
}

template <typename T>
__device__ void bcast(int op, const T* A, const T* B, T* C, int n, int rank, const int* meta) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i >= n) return;
  int ia = 0, ib = 0, rem = i;
  for (int k = rank - 1; k >= 0; --k) {
    int d = meta[k];
    int c = rem % d;
    rem /= d;
    ia += c * meta[rank + k];
    ib += c * meta[2 * rank + k];
  }
  C[i] = (T) bin_op(op, (double) A[ia], (double) B[ib]);
}

extern "C" __global__ void bcast_f32(int op, const float* A, const float* B, float* C, int n, int rank,
                                     strided_meta meta) {
  bcast<float>(op, A, B, C, n, rank, meta.v);
}

extern "C" __global__ void bcast_f64(int op, const double* A, const double* B, double* C, int n, int rank,
                                     strided_meta meta) {
  bcast<double>(op, A, B, C, n, rank, meta.v);
}

// The axes transpose, and any other pure permuted copy: one source stride per OUTPUT axis.
template <typename T>
__device__ void gather(const T* A, T* C, int n, int rank, const int* meta) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i >= n) return;
  int ia = 0, rem = i;
  for (int k = rank - 1; k >= 0; --k) {
    int d = meta[k];
    int c = rem % d;
    rem /= d;
    ia += c * meta[rank + k];
  }
  C[i] = A[ia];
}

extern "C" __global__ void gather_f32(const float* A, float* C, int n, int rank, strided_meta meta) {
  gather<float>(A, C, n, rank, meta.v);
}

extern "C" __global__ void gather_f64(const double* A, double* C, int n, int rank, strided_meta meta) {
  gather<double>(A, C, n, rank, meta.v);
}

// The axis fold: out[o * inner + j] folds A[(o * len + k) * inner + j] over k ASCENDING,
// which is `%la-fold-axis`'s own order. Op 0 (sum) seeds with the defun's 0; ops 1 and 2
// (amax / amin) seed with the FIRST element and compare strictly, so the accumulator wins
// a tie and a NaN -- the defun's `(if (> x acc) x acc)`. The caller declines len == 0,
// which the defun answers with an error.
template <typename T>
__device__ void fold(int op, const T* A, T* C, int outer, int len, int inner) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  int total = outer * inner;
  if (i >= total) return;
  int base = (i / inner) * len * inner + (i % inner);
  double acc;
  int k;
  if (op == 0) {
    acc = 0.0;
    k = 0;
  } else {
    acc = (double) A[base];
    k = 1;
  }
  for (; k < len; ++k) {
    double x = (double) A[base + k * inner];
    if (op == 0) {
      acc += x;
    } else if (op == 1) {
      if (x > acc) acc = x;
    } else {
      if (x < acc) acc = x;
    }
  }
  C[i] = (T) acc;
}

extern "C" __global__ void fold_f32(int op, const float* A, float* C, int outer, int len, int inner) {
  fold<float>(op, A, C, outer, len, inner);
}

extern "C" __global__ void fold_f64(int op, const double* A, double* C, int outer, int len, int inner) {
  fold<double>(op, A, C, outer, len, inner);
}

// The seeded GENERATOR fill behind linalg:rand / randn / uniform (linalg::%la-rng-fill):
// Wichmann-Hill, three integer LCGs combined into one uniform double, spelled operation
// for operation as the scalar defun spells it -- the same divides, the same
// left-associated sum, the same frac-by-compares -- because linalg:seed promises one
// seed reproduces one sequence on EVERY backend. Every arithmetic step is a _rn
// intrinsic so nvcc cannot contract `lo + span * u` into an FMA, which would move the
// low bit; the integer half is exact by construction.
//
// What makes it a kernel at all is the closed form. The defun (and every CPU kernel)
// walks the sequence in order, one state update per draw; thread i instead jumps
// straight to the state its element starts from -- s_k = a^k * s mod m, square-and-
// multiply over k = i * draws steps -- and then draws exactly as the defun would from
// there. The host advances the END state the same way (Gpu.rngAdvance), so the
// generator continues where a sequential fill would have left it.
__device__ int wh_pow(int a, long long e, int m) {
  long long r = 1, b = a % m;
  while (e > 0) {
    if (e & 1) r = r * b % m;
    b = b * b % m;
    e >>= 1;
  }
  return (int) r;
}

__device__ double wh_next(int* s1, int* s2, int* s3) {
  *s1 = 171 * *s1 % 30269;
  *s2 = 172 * *s2 % 30307;
  *s3 = 170 * *s3 % 30323;
  double u = __dadd_rn(__dadd_rn(__ddiv_rn((double) *s1, 30269.0), __ddiv_rn((double) *s2, 30307.0)),
                       __ddiv_rn((double) *s3, 30323.0));
  return u >= 2.0 ? __dsub_rn(u, 2.0) : (u >= 1.0 ? __dsub_rn(u, 1.0) : u);
}

// The jump is taken in two hops (todo-499): the BLOCK's part, a^(first element of the
// block * draws), is one square-and-multiply chain computed by one thread and shared,
// and each thread then jumps its own offset inside the block, whose exponent is at most
// 255 * 12 and so twelve bits. Both hops are the same exact integer arithmetic (a^(p+q)
// = a^p * a^q mod m), so the state every element starts from is unchanged bit for bit;
// what changes is the integer work per element, which was the whole cost of the fill
// (compute-bound, 1.4 ms for 6.3 M single floats against 0.2 for a memory pass) and is
// now roughly half. Every thread reaches the barrier: the bounds test comes after it.
template <typename T>
__device__ void rng_fill(T* out, int n, int mode, double lo, double span, int s1, int s2, int s3) {
  __shared__ int base[3];
  int draws = mode == 1 ? 12 : 1;
  if (threadIdx.x == 0) {
    long long first = (long long) blockIdx.x * blockDim.x * draws;
    base[0] = wh_pow(171, first, 30269);
    base[1] = wh_pow(172, first, 30307);
    base[2] = wh_pow(170, first, 30323);
  }
  __syncthreads();
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i >= n) return;
  long long mine = (long long) threadIdx.x * draws;
  int t1 = (int) ((long long) s1 * base[0] % 30269 * wh_pow(171, mine, 30269) % 30269);
  int t2 = (int) ((long long) s2 * base[1] % 30307 * wh_pow(172, mine, 30307) % 30307);
  int t3 = (int) ((long long) s3 * base[2] % 30323 * wh_pow(170, mine, 30323) % 30323);
  double v;
  if (mode == 1) {
    double acc = 0.0;
    for (int j = 0; j < 12; ++j) acc = __dadd_rn(acc, wh_next(&t1, &t2, &t3));
    v = __dsub_rn(acc, 6.0);
  } else if (mode == 0) {
    v = wh_next(&t1, &t2, &t3);
  } else {
    v = __dadd_rn(lo, __dmul_rn(span, wh_next(&t1, &t2, &t3)));
  }
  out[i] = (T) v;
}

extern "C" __global__ void rng_fill_f32(float* out, int n, int mode, double lo, double span, int s1, int s2,
                                        int s3) {
  rng_fill<float>(out, n, mode, lo, span, s1, s2, s3);
}

extern "C" __global__ void rng_fill_f64(double* out, int n, int mode, double lo, double span, int s1, int s2,
                                        int s3) {
  rng_fill<double>(out, n, mode, lo, span, s1, s2, s3);
}

// The GEMV behind vec:matvec, y = W x over a row-major rows x cols matrix (.todo/475): one
// WARP per row, lane l walking columns l, l + 32, ..., then a shuffle tree across the warp.
// A matrix-by-vector product is memory-bound -- every element of W is read once and never
// again -- so the kernel is written for coalesced reads of W (the 32 lanes of a warp read
// 32 consecutive elements) and for nothing else. What makes it worth a launch at all is
// not the kernel: it is that the matrix STAYS on the device between calls
// (DeviceResidency), so a decode step pays for x up and y down and reads W at the device's
// own bandwidth rather than over the link (.kb/gpu.md, "The GEMV, and the matrix that
// stays").
//
// The accumulator is a DOUBLE at both widths and only the store narrows, which is the
// scalar vec.lisp defun's own rule. At f32 that makes every product of two elements EXACT
// in the accumulator, so what separates this kernel from the defun is only the ORDER of a
// double sum -- which moves the narrowed float only when the sum lies within ~1e-16 of an
// f32 rounding boundary: measured, 1024 of 1024 rows bit-identical, where a float
// accumulator (the --simd lane kernel's width) lands 2.6e-7 away and ~20% faster on a
// small call. At f64 the fused multiply-add and the tree are the product's own few-ulp
// story. The whole warp shares one row, so the early return is warp-uniform and the
// full-mask shuffles below it are safe.
template <typename T>
__device__ void gemv(const T* W, const T* x, T* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  const T* w = W + (long long) row * cols;
  double acc = 0.0;
  for (int j = lane; j < cols; j += 32) acc = fma((double) w[j], (double) x[j], acc);
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = (T) acc;
}

extern "C" __global__ void gemv_f32(const float* W, const float* x, float* y, int rows, int cols) {
  gemv<float>(W, x, y, rows, cols);
}

extern "C" __global__ void gemv_f64(const double* W, const double* x, double* y, int rows, int cols) {
  gemv<double>(W, x, y, rows, cols);
}

// The RESIDENT tier (.todo/491): the members whose CPU twin is a LANE loop and which a
// round trip therefore could never win -- the equal-shape binary ops, the array-with-
// scalar forms, the three-way select behind torch:masked-fill, and the fused Adam update.
// They are offered ONLY when an operand is already on the device (Gpu checks residency
// before any of these is launched), because then there is no trip: the operand is here,
// the result stays here (a result comes home on the host's first read), and what the CPU
// would have paid is the download of the operand plus the loop. Every one computes in
// DOUBLE and narrows only on the store, which is the CPU kernel's rule for exactly these
// members (LinalgSimdKernels, JvmSimdVectorTemplate: `(float) laApply(op, x, s)`), so all
// four are BIT-IDENTICAL to it at both widths.

// out[i] = op(A[i], B[i]) over two arrays of the same shape.
template <typename T>
__device__ void zip(int op, const T* A, const T* B, T* C, int n) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i < n) C[i] = (T) bin_op(op, (double) A[i], (double) B[i]);
}

extern "C" __global__ void zip_f32(int op, const float* A, const float* B, float* C, int n) {
  zip<float>(op, A, B, C, n);
}

extern "C" __global__ void zip_f64(int op, const double* A, const double* B, double* C, int n) {
  zip<double>(op, A, B, C, n);
}

// out[i] = op(A[i], s), or op(s, A[i]) when swapped: the scalar is a DOUBLE whatever the
// array's width -- `(linalg:div scores (sqrt d-k))` over an #f activation is the shape --
// and the CPU kernel keeps it one for the same reason (laEwFS is scalar by contract).
template <typename T>
__device__ void scal(int op, const T* A, double s, T* C, int n, int swap) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i >= n) return;
  double a = (double) A[i];
  C[i] = (T) (swap ? bin_op(op, s, a) : bin_op(op, a, s));
}

extern "C" __global__ void scal_f32(int op, const float* A, double s, float* C, int n, int swap) {
  scal<float>(op, A, s, C, n, swap);
}

extern "C" __global__ void scal_f64(int op, const double* A, double s, double* C, int n, int swap) {
  scal<double>(op, A, s, C, n, swap);
}

// out[i] = mask[i] != 0 ? x[i] : y[i] over three operands broadcast together, any of which
// may be a scalar (a null pointer and its double) -- linalg:where, and through it
// torch:masked-fill. `meta` is the output dims then one stride vector per operand (a
// scalar's is all zeros); the mask may be either width, or a scalar (mkind 0 / 1 / 2).
template <typename T>
__device__ void where_op(const void* M, int mkind, double ms, const T* X, double xs, const T* Y, double ys, T* C,
                         int n, int rank, const int* meta) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i >= n) return;
  int im = 0, ix = 0, iy = 0, rem = i;
  for (int k = rank - 1; k >= 0; --k) {
    int d = meta[k];
    int c = rem % d;
    rem /= d;
    im += c * meta[rank + k];
    ix += c * meta[2 * rank + k];
    iy += c * meta[3 * rank + k];
  }
  double mv = mkind == 1 ? (double) ((const float*) M)[im] : (mkind == 2 ? ((const double*) M)[im] : ms);
  double v = mv == 0.0 ? (Y != 0 ? (double) Y[iy] : ys) : (X != 0 ? (double) X[ix] : xs);
  C[i] = (T) v;
}

extern "C" __global__ void where_f32(const void* M, int mkind, double ms, const float* X, double xs, const float* Y,
                                     double ys, float* C, int n, int rank, strided_meta meta) {
  where_op<float>(M, mkind, ms, X, xs, Y, ys, C, n, rank, meta.v);
}

extern "C" __global__ void where_f64(const void* M, int mkind, double ms, const double* X, double xs,
                                     const double* Y, double ys, double* C, int n, int rank, strided_meta meta) {
  where_op<double>(M, mkind, ms, X, xs, Y, ys, C, n, rank, meta.v);
}

// Adam's fused update in place over the parameter, its gradient and the two moments,
// spelled operation for operation as linalg::%la-adam-step's CPU kernel spells it and in
// its order -- every step an _rn intrinsic so nvcc cannot contract a multiply-add into an
// FMA, which Java never does -- so the parameter and the moments land on the CPU's bits.
// With all four arrays resident (the gradient is the previous member's result, the rest
// stayed from the previous step) this is a launch with no copy at all, and the weights
// never come home until something on the host reads them.
template <typename T>
__device__ void adam(T* X, const T* G, T* Mm, T* V, int n, double lr, double lrwd, double wd, double b1, double omb1,
                     double b2, double omb2, double eps, double c1, double c2, int mode) {
  int k = blockIdx.x * blockDim.x + threadIdx.x;
  if (k >= n) return;
  double x0 = (double) X[k];
  double xv = mode == 2 ? __dsub_rn(x0, __dmul_rn(lrwd, x0)) : x0;
  double gv = mode == 1 ? __dadd_rn((double) G[k], __dmul_rn(wd, x0)) : (double) G[k];
  double mk = __dadd_rn(__dmul_rn(b1, (double) Mm[k]), __dmul_rn(omb1, gv));
  double vk = __dadd_rn(__dmul_rn(b2, (double) V[k]), __dmul_rn(__dmul_rn(omb2, gv), gv));
  Mm[k] = (T) mk;
  V[k] = (T) vk;
  X[k] = (T) __dsub_rn(xv, __ddiv_rn(__dmul_rn(lr, __ddiv_rn(mk, c1)), __dadd_rn(__dsqrt_rn(__ddiv_rn(vk, c2)), eps)));
}

extern "C" __global__ void adam_f32(float* X, const float* G, float* Mm, float* V, int n, double lr, double lrwd,
                                    double wd, double b1, double omb1, double b2, double omb2, double eps, double c1,
                                    double c2, int mode) {
  adam<float>(X, G, Mm, V, n, lr, lrwd, wd, b1, omb1, b2, omb2, eps, c1, c2, mode);
}

extern "C" __global__ void adam_f64(double* X, const double* G, double* Mm, double* V, int n, double lr, double lrwd,
                                    double wd, double b1, double omb1, double b2, double omb2, double eps, double c1,
                                    double c2, int mode) {
  adam<double>(X, G, Mm, V, n, lr, lrwd, wd, b1, omb1, b2, omb2, eps, c1, c2, mode);
}

// The strided COPY: out[so . c] = A[sa . c] over the output dims, one source stride and one
// DESTINATION stride per axis, either of which may be negative (a slice with a negative
// step). It is what reshape (both contiguous), the rank-2 transpose, linalg:slice /
// %la-gather-strided (a base offset and innermost-first strides) and concatenate (each
// input into its own slab of the output) are, and since .todo/491 all of them are members
// over a RESIDENT operand: a copy cannot pay for a round trip, and it does not have to --
// the operand is already here, the result stays here, and the CPU would have had to
// download the operand to copy it. A pure copy, so trivially bit-identical.
template <typename T>
__device__ void copy_strided(const T* A, T* C, int n, int rank, const int* meta) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i >= n) return;
  long long ia = 0, ic = 0;
  int rem = i;
  for (int k = rank - 1; k >= 0; --k) {
    int d = meta[k];
    int c = rem % d;
    rem /= d;
    ia += (long long) c * meta[rank + k];
    ic += (long long) c * meta[2 * rank + k];
  }
  C[ic] = A[ia];
}

extern "C" __global__ void copy_f32(const float* A, float* C, int n, int rank, strided_meta meta) {
  copy_strided<float>(A, C, n, rank, meta.v);
}

extern "C" __global__ void copy_f64(const double* A, double* C, int n, int rank, strided_meta meta) {
  copy_strided<double>(A, C, n, rank, meta.v);
}

// The INDEX tier: the three members an embedding table and a cross-entropy pick are made
// of. All three are index-driven copies with no arithmetic to reorder, so all three are
// bit-identical to the CPU kernels -- which is what lets them be offered over a RESIDENT
// operand at any size, the way the copy above is: the operand is already here, and the
// CPU would have had to download it.

// linalg:take-rows (mode 0) and linalg:gather (mode 1) -- the embedding lookup and the
// cross-entropy "pick the target logit". A pure gather:
//   mode 0: C[i * slab + k] = A[idx[i] * slab + k], over n = m * slab output elements
//   mode 1: C[i]            = A[i * slab + idx[i]], over n = m, slab = the row length
template <typename T>
__device__ void take(int mode, const T* A, T* C, const int* idx, int n, int slab) {
  int t = blockIdx.x * blockDim.x + threadIdx.x;
  if (t >= n) return;
  if (mode == 0) {
    int i = t / slab;
    C[t] = A[(long long) idx[i] * slab + (t - i * slab)];
  } else {
    C[t] = A[(long long) t * slab + idx[t]];
  }
}

extern "C" __global__ void take_f32(int mode, const float* A, float* C, const int* idx, int n, int slab) {
  take<float>(mode, A, C, idx, n, slab);
}

extern "C" __global__ void take_f64(int mode, const double* A, double* C, const int* idx, int n, int slab) {
  take<double>(mode, A, C, idx, n, slab);
}

// linalg::%la-scatter-rows, the adjoint of take-rows, IN PLACE: slab i of G is ADDED into
// slab idx[i] of Z for i ASCENDING, so a REPEATED index -- which a token embedding's
// gradient is nothing but -- accumulates in index order, and that order is the defun's
// value rather than an accident of it. Atomics would lose it. A thread per (destination
// slab, column) keeps it and needs none: the caller hands the indices over already
// GROUPED by destination (a counting sort; meta is start[rows + 1] followed by the m
// source slab numbers, ascending inside each group), so thread (r, k) walks its own
// group and no two threads ever touch one cell. Widen, add, narrow per step, which is
// what the defun's row-major-aref store does.
template <typename T>
__device__ void scatter(T* Z, const T* G, const int* meta, int rows, int slab) {
  int t = blockIdx.x * blockDim.x + threadIdx.x;
  if (t >= rows * slab) return;
  int r = t / slab, k = t - r * slab;
  int from = meta[r], to = meta[r + 1];
  if (from == to) return;
  const int* order = meta + rows + 1;
  T z = Z[t];
  for (int j = from; j < to; ++j) {
    z = (T) ((double) z + (double) G[(long long) order[j] * slab + k]);
  }
  Z[t] = z;
}

extern "C" __global__ void scatter_f32(float* Z, const float* G, const int* meta, int rows, int slab) {
  scatter<float>(Z, G, meta, rows, slab);
}

extern "C" __global__ void scatter_f64(double* Z, const double* G, const int* meta, int rows, int slab) {
  scatter<double>(Z, G, meta, rows, slab);
}

// linalg::%la-sum-squares, the first half of torch:clip-grad-norm: sum(v * v) over the
// whole array, in double at both widths. This is the ONE kernel in this file that does
// not compute the caller's own fold ORDER, and it is a deliberate break rather than an
// oversight -- the defun's contract is a single left fold, which no parallel reduction
// keeps, and every alternative was worse than the break (.kb/gpu.md, "The index tier and
// the clip norm"). Each block folds a grid-stride slice and writes one double partial;
// the host adds the partials up in block order, so the value is still a pure function of
// the length. Both steps are _rn intrinsics, so each term is rounded exactly where the
// defun rounds it and only the ASSOCIATION differs.
//
// The launcher MUST use exactly SUMSQ_BLOCK threads per block: the shared array is that
// long and the grid stride is written in terms of it, so the two are one number in two
// files (CudaGemm launches this through the strided helper, whose block size is the same
// 256).
#define SUMSQ_BLOCK 256

template <typename T>
__device__ void sumsq(const T* A, double* P, int n) {
  __shared__ double s[SUMSQ_BLOCK];
  int t = threadIdx.x;
  double acc = 0.0;
  for (long long i = (long long) blockIdx.x * SUMSQ_BLOCK + t; i < n; i += (long long) SUMSQ_BLOCK * gridDim.x) {
    double v = (double) A[i];
    acc = __dadd_rn(acc, __dmul_rn(v, v));
  }
  s[t] = acc;
  __syncthreads();
  for (int w = SUMSQ_BLOCK / 2; w > 0; w >>= 1) {
    if (t < w) s[t] = __dadd_rn(s[t], s[t + w]);
    __syncthreads();
  }
  if (t == 0) P[blockIdx.x] = s[0];
}

extern "C" __global__ void sumsq_f32(const float* A, double* P, int n) {
  sumsq<float>(A, P, n);
}

extern "C" __global__ void sumsq_f64(const double* A, double* P, int n) {
  sumsq<double>(A, P, n);
}

// The FUSED tier (.todo/499): the four compositions a transformer step spent a third of
// its device time on -- GELU, softmax, layer-norm and the dropout mask -- each as ONE
// kernel where the `torch.lisp` composition launched five to fourteen `linalg:` members,
// one full memory pass each. What a fused kernel buys on this card is the passes it
// removes and nothing else: the launches were already pipelined (.kb/gpu.md, "The launch
// pipeline"), so a chain of five bandwidth-bound passes over a 100 MB activation is five
// times the traffic of one.
//
// EVERY ARITHMETIC STEP BELOW IS THE COMPOSITION'S, IN THE COMPOSITION'S ORDER, NARROWED
// WHERE THE COMPOSITION NARROWED. A `linalg:` member computes in double and stores at the
// operand's width, so a chain of members rounds at every member boundary; the fused
// kernels reproduce each of those roundings (the `(T)` casts are the member boundaries),
// keep every axis fold ascending and sequential in a double accumulator, and evaluate
// exp / erf at the operand width exactly as `map_op` does. So a fused member lands on the
// bits the chain of device members would have produced, and -- for the members with no
// libm in them (softmax's adjoint, layer-norm, the mask) -- on the CPU defun's bits.
// The layer-norm and GELU adjoints replay the TAPE's composition too: the order the
// reverse-mode walk accumulates one input's several contributions in is part of the
// value, and `OLD` -- the gradient the input had accumulated before this node -- is folded
// in where the tape would have folded it (.kb/torch.md).
//
// The arithmetic is written through these five, each one double operation. Left to the
// operators alone nvcc contracted `a * b + c` into a fused multiply-add wherever the
// operands were doubles -- at f64 every `(T)` boundary is a no-op, so `acc += dev * dev`
// rounded once where the chain rounds twice: measured, one ulp in a layer-norm row and
// in a softmax adjoint. The first fix was the `__dmul_rn` family, which never contracts
// -- and DOUBLED these kernels (gelu_grad 1.7 -> 3.5 ms at the book's feed-forward
// shape), because the intrinsics also switch off everything else the compiler does with
// a double expression. So the file is compiled with -fmad=false instead (the header), the
// products that should fuse say fma() explicitly, and these stay plain operators.
#define F_ADD(a, b) ((double) (a) + (double) (b))
#define F_SUB(a, b) ((double) (a) - (double) (b))
#define F_MUL(a, b) ((double) (a) * (double) (b))
#define F_DIV(a, b) ((double) (a) / (double) (b))
#define F_NEG(a) (-(double) (a))

// x * (1 + erf(x / sqrt 2)) / 2 as `torch:gelu` composes it: mul by 0.5, div by sqrt 2,
// erf, add to 1, mul -- five members, five roundings.
template <typename T>
__device__ T gelu_forward(T x) {
  T t1 = (T) F_MUL(x, 0.5);
  T t2 = (T) F_DIV(x, 1.4142135623730951);
  T t3 = erf(t2);
  T t4 = (T) F_ADD(1.0, t3);
  return (T) F_MUL(t1, t4);
}

template <typename T>
__device__ void gelu(const T* X, T* C, int n) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i < n) C[i] = gelu_forward<T>(X[i]);
}

extern "C" __global__ void gelu_f32(const float* X, float* C, int n) {
  gelu<float>(X, C, n);
}

extern "C" __global__ void gelu_f64(const double* X, double* C, int n) {
  gelu<double>(X, C, n);
}

// The tape's backward through those five members, in the tape's order: the outer mul
// hands g * t4 to the 0.5-branch and g * t1 to the erf branch; erf's adjoint is
// g * (2/sqrt(pi)) * exp(-t2^2); the div branch lands on x first (B) and the mul branch
// second (A), each after the gradient x had already accumulated (OLD, or nothing).
template <typename T>
__device__ void gelu_grad(const T* G, const T* X, const T* OLD, T* C, int n) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i >= n) return;
  T x = X[i], g = G[i];
  T t1 = (T) F_MUL(x, 0.5);
  T t2 = (T) F_DIV(x, 1.4142135623730951);
  T t3 = erf(t2);
  T t4 = (T) F_ADD(1.0, t3);
  T g1 = (T) F_MUL(g, t4);
  T g4 = (T) F_MUL(g, t1);
  T sq = (T) F_MUL(t2, t2);
  T ng = (T) F_NEG(sq);
  T ex = exp(ng);
  T sc = (T) F_MUL(1.1283791670955126, ex);
  T g2 = (T) F_MUL(g4, sc);
  T b = (T) F_DIV(g2, 1.4142135623730951);
  T a = (T) F_MUL(g1, 0.5);
  T acc = OLD != 0 ? (T) F_ADD(OLD[i], b) : b;
  C[i] = (T) F_ADD(acc, a);
}

extern "C" __global__ void gelu_grad_f32(const float* G, const float* X, const float* OLD, float* C, int n) {
  gelu_grad<float>(G, X, OLD, C, n);
}

extern "C" __global__ void gelu_grad_f64(const double* G, const double* X, const double* OLD, double* C, int n) {
  gelu_grad<double>(G, X, OLD, C, n);
}

// THE ROW KERNELS' LAYOUT. A row kernel keeps one THREAD per row, the fold kernel's own
// pattern, because a sequential double fold has no lane-parallel form that keeps its bits
// and the fp64 pipe is used fully only when thirty-two rows advance in one warp
// instruction. But thirty-two threads reading thirty-two rows read addresses a row apart,
// and writing them that way is worse (a warp store touches thirty-two lines for four
// bytes each): measured, a thread-per-row softmax over global memory was SLOWER than the
// five-member chain it replaced. So the rows go through a TRANSPOSED TILE in shared
// memory, thirty-two columns at a time: the warp loads column c of its thirty-two rows
// with one coalesced instruction per row, each lane then walks its own row across the
// tile (stride 33, conflict-free), and a result goes back the same way. Every pass over
// the rows is then a coalesced stream, whatever the row length, and the tile is what
// bounds the block: ROW_WARPS warps, each with up to two tiles, under the 48 KB static
// limit at f64. CudaGemm launches these at ROW_WARPS * 32 threads, and at nothing else.
#define ROW_WARPS 2

template <typename T>
struct row_tile {
  T v[32][33];
};

// Columns [c0, c0 + 32) of rows [row0, row0 + 32) into the tile; a cell outside the
// operand reads as zero and is never stored back. The barriers make the tile safe to
// reuse: nobody loads before every lane has finished the previous chunk, nobody reads
// before every column has landed.
template <typename T>
__device__ void tile_load(row_tile<T>& tile, const T* A, int rows, int len, int row0, int c0, int lane) {
  __syncwarp();
  int col = c0 + lane;
  for (int r = 0; r < 32; ++r) {
    int row = row0 + r;
    tile.v[r][lane] = (row < rows && col < len) ? A[(long long) row * len + col] : (T) 0;
  }
  __syncwarp();
}

template <typename T>
__device__ void tile_store(row_tile<T>& tile, T* C, int rows, int len, int row0, int c0, int lane) {
  __syncwarp();
  int col = c0 + lane;
  for (int r = 0; r < 32; ++r) {
    int row = row0 + r;
    if (row < rows && col < len) C[(long long) row * len + col] = tile.v[r][lane];
  }
  __syncwarp();
}

// `linalg:softmax` over the last axis: amax (the strict fold, seeded with the first
// element), the broadcast sub, exp at the width, the sum fold, the broadcast div. Three
// passes over the row: the max, exp into the result with the sum, the division in place.
template <typename T>
__device__ void softmax_rows(const T* A, T* C, int rows, int len) {
  __shared__ row_tile<T> tiles[ROW_WARPS];
  int warp = threadIdx.x >> 5, lane = threadIdx.x & 31;
  row_tile<T>& tile = tiles[warp];
  int row0 = (blockIdx.x * ROW_WARPS + warp) * 32;
  if (row0 >= rows) return;
  double m = 0.0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, A, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      double x = (double) tile.v[lane][j];
      if (c0 + j == 0 || x > m) m = x;
    }
  }
  T mt = (T) m;
  double sum = 0.0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, A, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      T s = (T) F_SUB(tile.v[lane][j], mt);
      T e = exp(s);
      tile.v[lane][j] = e;
      sum = F_ADD(sum, e);
    }
    tile_store(tile, C, rows, len, row0, c0, lane);
  }
  T d = (T) sum;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, C, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) tile.v[lane][j] = (T) F_DIV(tile.v[lane][j], d);
    tile_store(tile, C, rows, len, row0, c0, lane);
  }
}

extern "C" __global__ void softmax_f32(const float* A, float* C, int rows, int len) {
  softmax_rows<float>(A, C, rows, len);
}

extern "C" __global__ void softmax_f64(const double* A, double* C, int rows, int len) {
  softmax_rows<double>(A, C, rows, len);
}

// `torch:softmax`'s adjoint, out * (g - sum(g * out)): the zip mul, the sum fold, the
// broadcast sub, the zip mul. No libm anywhere, so bit-identical to the CPU chain.
template <typename T>
__device__ void softmax_grad_rows(const T* G, const T* O, T* C, int rows, int len) {
  __shared__ row_tile<T> tiles[ROW_WARPS][2];
  int warp = threadIdx.x >> 5, lane = threadIdx.x & 31;
  row_tile<T>& gt = tiles[warp][0];
  row_tile<T>& ot = tiles[warp][1];
  int row0 = (blockIdx.x * ROW_WARPS + warp) * 32;
  if (row0 >= rows) return;
  double sum = 0.0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(gt, G, rows, len, row0, c0, lane);
    tile_load(ot, O, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      T p = (T) F_MUL(gt.v[lane][j], ot.v[lane][j]);
      sum = F_ADD(sum, p);
    }
  }
  T tot = (T) sum;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(gt, G, rows, len, row0, c0, lane);
    tile_load(ot, O, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      T q = (T) F_SUB(gt.v[lane][j], tot);
      ot.v[lane][j] = (T) F_MUL(ot.v[lane][j], q);
    }
    tile_store(ot, C, rows, len, row0, c0, lane);
  }
}

extern "C" __global__ void softmax_grad_f32(const float* G, const float* O, float* C, int rows, int len) {
  softmax_grad_rows<float>(G, O, C, rows, len);
}

extern "C" __global__ void softmax_grad_f64(const double* G, const double* O, double* C, int rows, int len) {
  softmax_grad_rows<double>(G, O, C, rows, len);
}

// The map kernel's sqrt (case 12): correctly rounded in double, narrowed, the NaN made
// canonical because Math.sqrt's is.
template <typename T>
__device__ T sqrt_like_map(T x) {
  double r = sqrt((double) x);
  return (T) (r != r ? __longlong_as_double(0x7ff8000000000000LL) : r);
}

// The row's mean and standard deviation as the torch composition computes them: the sum
// fold divided by the length, the sum fold of the squared deviations divided by the
// length, plus eps, the square root -- every one a member boundary.
template <typename T>
__device__ void row_stats(row_tile<T>& tile, const T* X, int rows, int len, int row0, int lane, double eps, T& mu,
                          T& sd) {
  double n = (double) len;
  double acc = 0.0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, X, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) acc = F_ADD(acc, tile.v[lane][j]);
  }
  mu = (T) F_DIV((T) acc, n);
  acc = 0.0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, X, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      T dev = (T) F_SUB(tile.v[lane][j], mu);
      T sq = (T) F_MUL(dev, dev);
      acc = F_ADD(acc, sq);
    }
  }
  T v = (T) F_DIV((T) acc, n);
  T ve = (T) F_ADD(v, eps);
  sd = sqrt_like_map<T>(ve);
}

// `torch:layer-norm`'s normalization (x - mean) / sqrt(var + eps) over the last axis, as
// its torch composition spells it (row_stats), then the broadcast division.
template <typename T>
__device__ void layer_norm_rows(const T* X, T* C, int rows, int len, double eps) {
  __shared__ row_tile<T> tiles[ROW_WARPS];
  int warp = threadIdx.x >> 5, lane = threadIdx.x & 31;
  row_tile<T>& tile = tiles[warp];
  int row0 = (blockIdx.x * ROW_WARPS + warp) * 32;
  if (row0 >= rows) return;
  T mu, sd;
  row_stats(tile, X, rows, len, row0, lane, eps, mu, sd);
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(tile, X, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      T dev = (T) F_SUB(tile.v[lane][j], mu);
      tile.v[lane][j] = (T) F_DIV(dev, sd);
    }
    tile_store(tile, C, rows, len, row0, c0, lane);
  }
}

extern "C" __global__ void layer_norm_f32(const float* X, float* C, int rows, int len, double eps) {
  layer_norm_rows<float>(X, C, rows, len, eps);
}

extern "C" __global__ void layer_norm_f64(const double* X, double* C, int rows, int len, double eps) {
  layer_norm_rows<double>(X, C, rows, len, eps);
}

// The tape's backward through that composition, with g the gradient of the normalized
// output. x feeds four nodes -- the two means and the two subtractions (torch:var takes
// its own mean) -- and the reverse walk reaches them as dev2 (A1, through the squared
// deviations), the variance's mean (A2), dev (A3) and the mean (A4), adding each into
// x's gradient in that order after whatever it already held (OLD). The reductions along
// the way (the adjoints of the two subtractions' broadcast mean, and of the division's
// broadcast sd) are the sum fold over the row, sequential, of the negated per-element
// terms; the two mean adjoints stretch a scalar back over the row through a broadcast add
// onto zeros, whose one visible effect (-0.0 + 0.0 = +0.0) is kept. Two tiles: x and g;
// once a chunk's terms are computed, x's tile holds the first term (with OLD folded onto
// it, read directly -- the one strided read, over an operand present only under a
// residual) and g's the result.
template <typename T>
__device__ void layer_norm_grad_rows(const T* G, const T* X, const T* OLD, T* C, int rows, int len, double eps) {
  __shared__ row_tile<T> tiles[ROW_WARPS][2];
  int warp = threadIdx.x >> 5, lane = threadIdx.x & 31;
  row_tile<T>& xt = tiles[warp][0];
  row_tile<T>& gt = tiles[warp][1];
  int row0 = (blockIdx.x * ROW_WARPS + warp) * 32;
  if (row0 >= rows) return;
  double n = (double) len;
  T mu, sd;
  row_stats(xt, X, rows, len, row0, lane, eps, mu, sd);
  // The division's adjoints: g / sd towards dev (A3), and towards sd the fold of
  // -((g * dev) / (sd * sd)); the subtraction's adjoint towards the mean, the fold of
  // -(g / sd).
  T q = (T) F_MUL(sd, sd);
  double acc_sd = 0.0, acc_mu = 0.0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(xt, X, rows, len, row0, c0, lane);
    tile_load(gt, G, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      T dev = (T) F_SUB(xt.v[lane][j], mu);
      T gk = gt.v[lane][j];
      T t = (T) F_MUL(gk, dev);
      T rr = (T) F_DIV(t, q);
      T nr = (T) F_NEG(rr);
      acc_sd = F_ADD(acc_sd, nr);
      T gdev = (T) F_DIV(gk, sd);
      T ng = (T) F_NEG(gdev);
      acc_mu = F_ADD(acc_mu, ng);
    }
  }
  T g_sd = (T) acc_sd;
  T g_mu = (T) acc_mu;
  // sqrt's adjoint g / (2 sd), the division by the length, the broadcast onto zeros.
  T two_sd = (T) F_MUL(2.0, sd);
  T g_ve = (T) F_DIV(g_sd, two_sd);
  T g_s = (T) F_DIV(g_ve, n);
  T g_sq = (T) F_ADD(0.0, g_s);
  // dev2 * dev2's adjoint reaches dev2 twice (g_sq * dev2, added to itself), which is A1;
  // the variance's own mean gets the fold of -A1 and hands back A2.
  double acc_m2 = 0.0;
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(xt, X, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      T dev = (T) F_SUB(xt.v[lane][j], mu);
      T p = (T) F_MUL(g_sq, dev);
      T gd2 = (T) F_ADD(p, p);
      T nn = (T) F_NEG(gd2);
      acc_m2 = F_ADD(acc_m2, nn);
    }
  }
  T g_m2 = (T) acc_m2;
  T a2 = (T) F_DIV((T) F_ADD(0.0, g_m2), n);
  T a4 = (T) F_DIV((T) F_ADD(0.0, g_mu), n);
  for (int c0 = 0; c0 < len; c0 += 32) {
    tile_load(xt, X, rows, len, row0, c0, lane);
    tile_load(gt, G, rows, len, row0, c0, lane);
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      T dev = (T) F_SUB(xt.v[lane][j], mu);
      T p = (T) F_MUL(g_sq, dev);
      T gd2 = (T) F_ADD(p, p);
      T gdev = (T) F_DIV(gt.v[lane][j], sd);
      xt.v[lane][j] = gd2;
      gt.v[lane][j] = gdev;
    }
    if (OLD != 0) {
      int row = row0 + lane;
      for (int j = 0; j < 32 && c0 + j < len; ++j) {
        if (row < rows) {
          T o = OLD[(long long) row * len + c0 + j];
          xt.v[lane][j] = (T) F_ADD(o, xt.v[lane][j]);
        }
      }
    }
    for (int j = 0; j < 32 && c0 + j < len; ++j) {
      T a = xt.v[lane][j];
      a = (T) F_ADD(a, a2);
      a = (T) F_ADD(a, gt.v[lane][j]);
      gt.v[lane][j] = (T) F_ADD(a, a4);
    }
    tile_store(gt, C, rows, len, row0, c0, lane);
  }
}

extern "C" __global__ void layer_norm_grad_f32(const float* G, const float* X, const float* OLD, float* C, int rows,
                                               int len, double eps) {
  layer_norm_grad_rows<float>(G, X, OLD, C, rows, len, eps);
}

extern "C" __global__ void layer_norm_grad_f64(const double* G, const double* X, const double* OLD, double* C,
                                               int rows, int len, double eps) {
  layer_norm_grad_rows<double>(G, X, OLD, C, rows, len, eps);
}

// The inverted-dropout mask (rand > p) / (1 - p) as `torch:dropout` composes it: the
// generator's uniform draw stored at the width (`rng_fill` mode 0), the comparison mask
// (a scalar `greater`, in double over the STORED draw), the division by the survival
// span the caller computed. Two hops for the jump, as in rng_fill.
template <typename T>
__device__ void dropout_mask(T* out, int n, double p, double span, int s1, int s2, int s3) {
  __shared__ int base[3];
  if (threadIdx.x == 0) {
    long long first = (long long) blockIdx.x * blockDim.x;
    base[0] = wh_pow(171, first, 30269);
    base[1] = wh_pow(172, first, 30307);
    base[2] = wh_pow(170, first, 30323);
  }
  __syncthreads();
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i >= n) return;
  long long mine = threadIdx.x;
  int t1 = (int) ((long long) s1 * base[0] % 30269 * wh_pow(171, mine, 30269) % 30269);
  int t2 = (int) ((long long) s2 * base[1] % 30307 * wh_pow(172, mine, 30307) % 30307);
  int t3 = (int) ((long long) s3 * base[2] % 30323 * wh_pow(170, mine, 30323) % 30323);
  T u = (T) wh_next(&t1, &t2, &t3);
  T m = (T) ((double) u > p ? 1.0 : 0.0);
  out[i] = (T) F_DIV(m, span);
}

extern "C" __global__ void dropout_mask_f32(float* out, int n, double p, double span, int s1, int s2, int s3) {
  dropout_mask<float>(out, n, p, span, s1, s2, s3);
}

extern "C" __global__ void dropout_mask_f64(double* out, int n, double p, double span, int s1, int s2, int s3) {
  dropout_mask<double>(out, n, p, span, s1, s2, s3);
}
