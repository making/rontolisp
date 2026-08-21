// The kernels behind --gpu -- the tiled GEMMs and the element-wise maps, at f32 and f64.
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

// The ELEMENT-WISE tier (phase 4b): one unary map per width, with the member selected by
// an OP CODE parameter rather than by an entry point of its own. One entry point per
// width keeps the module lookup fixed however the member set grows, and the branch is
// uniform across the whole grid, so it costs nothing measurable next to a libm call.
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
