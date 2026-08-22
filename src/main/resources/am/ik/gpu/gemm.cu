// The kernels behind --gpu -- the tiled GEMMs, the GEMV, the maps and more, at f32 and f64.
// gemm.cu is the source, gemm.ptx is what nvcc makes of it, and the two are checked in
// TOGETHER and regenerated together -- from the repository root, with a CUDA toolkit
// installed. The toolkit is a DEVELOPER requirement only: at run time the NVIDIA driver
// JIT-compiles this PTX itself, and libcuda.so.1 is the whole dependency.
//
//   nvcc -arch=compute_75 -ptx src/main/resources/am/ik/gpu/gemm.cu -o /tmp/gemm.ptx
//   sed -n '1,12p' src/main/resources/am/ik/gpu/gemm.cu > src/main/resources/am/ik/gpu/gemm.ptx
//   cat /tmp/gemm.ptx >> src/main/resources/am/ik/gpu/gemm.ptx
//
// compute_75 (Turing, 2018) is the floor because CUDA 13 refuses to target anything older,
// not because we chose it. See .kb/gpu.md.

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
    for (int k = 0; k < TILE; ++k) acc += As[ty][k] * Bs[k][tx];
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
                                     const int* meta) {
  bcast<float>(op, A, B, C, n, rank, meta);
}

extern "C" __global__ void bcast_f64(int op, const double* A, const double* B, double* C, int n, int rank,
                                     const int* meta) {
  bcast<double>(op, A, B, C, n, rank, meta);
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

extern "C" __global__ void gather_f32(const float* A, float* C, int n, int rank, const int* meta) {
  gather<float>(A, C, n, rank, meta);
}

extern "C" __global__ void gather_f64(const double* A, double* C, int n, int rank, const int* meta) {
  gather<double>(A, C, n, rank, meta);
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

template <typename T>
__device__ void rng_fill(T* out, int n, int mode, double lo, double span, int s1, int s2, int s3) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i >= n) return;
  long long steps = (long long) i * (mode == 1 ? 12 : 1);
  int t1 = (int) ((long long) s1 * wh_pow(171, steps, 30269) % 30269);
  int t2 = (int) ((long long) s2 * wh_pow(172, steps, 30307) % 30307);
  int t3 = (int) ((long long) s3 * wh_pow(170, steps, 30323) % 30323);
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
// (CudaResidency), so a decode step pays for x up and y down and reads W at the device's
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
  for (int j = lane; j < cols; j += 32) acc += (double) w[j] * (double) x[j];
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = (T) acc;
}

extern "C" __global__ void gemv_f32(const float* W, const float* x, float* y, int rows, int cols) {
  gemv<float>(W, x, y, rows, cols);
}

extern "C" __global__ void gemv_f64(const double* W, const double* x, double* y, int rows, int cols) {
  gemv<double>(W, x, y, rows, cols);
}
