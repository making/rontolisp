// The probe kernels for ElementwiseCrossover.java -- NOT the shipped ones. They carry the
// candidates the shipped library DECLINES (sqrt, abs, negative, and the binary add/sub/
// mul/div zip) as well as the ones it takes, because the decline is a measurement too and
// it has to stay re-derivable. The shipped kernels are
// src/main/resources/am/ik/gpu/gemm.cu, whose op codes are its own.
//
//   nvcc -arch=compute_75 -ptx elementwise-probe.cu -o /tmp/ew.ptx
//   java --enable-native-access=ALL-UNNAMED ElementwiseCrossover.java /tmp/ew.ptx

template <typename T>
__device__ T applyOp(int op, T x) {
  switch (op) {
    case 0: return exp(x);
    case 1: return log(x);
    case 2: return tanh(x);
    case 3: return erf(x);
    case 4: return sqrt(x);
    case 5: return sin(x);
    case 6: return cos(x);
    case 7: return tan(x);
    case 8: return asin(x);
    case 9: return acos(x);
    case 10: return atan(x);
    case 11: return sinh(x);
    case 12: return cosh(x);
    case 13: return fabs(x);
    case 14: return -x;
    default: return x;
  }
}

template <typename T>
__device__ void mapk(const T* A, T* C, int n, int op) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i < n) C[i] = applyOp<T>(op, A[i]);
}

extern "C" __global__ void map_f64(const double* A, double* C, int n, int op) { mapk<double>(A, C, n, op); }
extern "C" __global__ void map_f32(const float* A, float* C, int n, int op) { mapk<float>(A, C, n, op); }

template <typename T>
__device__ void zipk(const T* A, const T* B, T* C, int n, int op) {
  int i = blockIdx.x * blockDim.x + threadIdx.x;
  if (i < n) {
    T x = A[i], y = B[i];
    switch (op) {
      case 0: C[i] = x + y; break;
      case 1: C[i] = x - y; break;
      case 2: C[i] = x * y; break;
      default: C[i] = x / y; break;
    }
  }
}

extern "C" __global__ void zip_f64(const double* A, const double* B, double* C, int n, int op) { zipk<double>(A, B, C, n, op); }
extern "C" __global__ void zip_f32(const float* A, const float* B, float* C, int n, int op) { zipk<float>(A, B, C, n, op); }
