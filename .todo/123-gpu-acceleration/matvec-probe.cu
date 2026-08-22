// The GEMV kernel candidates behind .todo/475 (vec:matvec on the device), compiled by
// MatvecCrossover.java's header command and loaded by it. Two accumulators are kept here
// because the choice between them is a measurement: `gemv_f32_acc32` sums in float (the
// --simd lane kernel's width), `gemv_*_acc64` sums in double (the scalar vec.lisp defun's
// rule, and what gemm.cu ships). One WARP per row, lane l walking columns l, l+32, ...,
// then a shuffle tree; the whole warp shares one row, so the early return is warp-uniform.
//
//   nvcc -arch=compute_75 -ptx matvec-probe.cu -o matvec-probe.ptx

extern "C" __global__ void gemv_f32_acc32(const float* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  const float* w = W + (long long) row * cols;
  float acc = 0.0f;
  for (int j = lane; j < cols; j += 32) acc += w[j] * x[j];
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = acc;
}

extern "C" __global__ void gemv_f32_acc64(const float* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  const float* w = W + (long long) row * cols;
  double acc = 0.0;
  for (int j = lane; j < cols; j += 32) acc += (double) w[j] * (double) x[j];
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = (float) acc;
}

extern "C" __global__ void gemv_f64_acc64(const double* W, const double* x, double* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  const double* w = W + (long long) row * cols;
  double acc = 0.0;
  for (int j = lane; j < cols; j += 32) acc += w[j] * x[j];
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = acc;
}

// The float4 variant of the double-accumulating f32 kernel: four columns per lane per
// iteration (128 contiguous bytes per warp-load), for the question of whether the plain
// kernel is already at the device's bandwidth on the 36.8 MB classifier head. cols must
// be a multiple of 4 and W 16-byte aligned, which the probe arranges.
extern "C" __global__ void gemv_f32_acc64_v4(const float* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  const float4* w = (const float4*) (W + (long long) row * cols);
  const float4* xv = (const float4*) x;
  double acc = 0.0;
  int quads = cols >> 2;
  for (int j = lane; j < quads; j += 32) {
    float4 a = w[j], b = xv[j];
    acc += (double) a.x * (double) b.x;
    acc += (double) a.y * (double) b.y;
    acc += (double) a.z * (double) b.z;
    acc += (double) a.w * (double) b.w;
  }
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = (float) acc;
}
