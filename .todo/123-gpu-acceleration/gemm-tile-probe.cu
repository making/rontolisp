// gemm-tile-probe.cu -- the 16x16 one-output-per-thread GEMM gemm.cu carries today
// against register-tiled candidates that fold each cell in the SAME k order with the
// SAME zero padding (K rounded up to 16), so the bits must match exactly. Times the
// step's own stacked shapes and the guide's n x n table.
//
//   nvcc -O3 -arch=native -o gemm-tile-probe gemm-tile-probe.cu && ./gemm-tile-probe
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <cuda_runtime.h>

#define TILE 16

template <typename T>
__device__ void gemm_old(const T* A, const T* B, T* C, int M, int N, int K) {
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
template <typename T>
__global__ void gemm_old_batched(const T* A, const T* B, T* C, int M, int N, int K, long long sA, long long sB) {
  long long z = blockIdx.z;
  gemm_old<T>(A + z * sA, B + z * sB, C + z * (long long) M * N, M, N, K);
}

// Register-tiled: a BM x BN block tile, 16x16 threads, each thread TM x TN outputs at
// rows ty + i*16 and columns tx + j*16 (so a warp's loads and stores are contiguous and
// the shared reads are conflict-free). BK = 16 with zero padding, which is exactly the
// old kernel's k sequence per cell. acc = fma(a, b, acc) explicitly.
template <typename T, int TM, int TN>
__device__ void gemm_reg(const T* A, const T* B, T* C, int M, int N, int K) {
  constexpr int BM = 16 * TM, BN = 16 * TN, BK = 16;
  __shared__ T As[BK][BM];  // As[k][m]
  __shared__ T Bs[BK][BN];  // Bs[k][n]
  int tx = threadIdx.x, ty = threadIdx.y;
  int tid = ty * 16 + tx;
  int row0 = blockIdx.y * BM, col0 = blockIdx.x * BN;
  T acc[TM][TN];
  for (int i = 0; i < TM; ++i) for (int j = 0; j < TN; ++j) acc[i][j] = 0;
  int tiles = (K + BK - 1) / BK;
  for (int t = 0; t < tiles; ++t) {
    int k0 = t * BK;
    // A tile: BM rows x BK cols, BM*BK elements over 256 threads -> TM each, consecutive
    // threads along k (coalesced rows of 16).
    for (int e = tid; e < BM * BK; e += 256) {
      int m = e / BK, k = e % BK;
      int gr = row0 + m, gk = k0 + k;
      As[k][m] = (gr < M && gk < K) ? A[gr * (long) K + gk] : (T) 0;
    }
    // B tile: BK rows x BN cols, consecutive threads along n (coalesced).
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
      for (int i = 0; i < TM; ++i) for (int j = 0; j < TN; ++j) acc[i][j] = fma(a[i], b[j], acc[i][j]);
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
__global__ void gemm_reg_batched(const T* A, const T* B, T* C, int M, int N, int K, long long sA, long long sB) {
  long long z = blockIdx.z;
  gemm_reg<T, TM, TN>(A + z * sA, B + z * sB, C + z * (long long) M * N, M, N, K);
}

template <typename T>
static void fill(std::vector<T>& v, unsigned seed) {
  unsigned s = seed;
  for (auto& x : v) { s = s * 1664525u + 1013904223u; x = (T) ((int) (s >> 8) % 2001 - 1000) / (T) 997.0; }
}

template <typename T>
static void run(const char* label, int batch, int M, int N, int K, bool bcastB) {
  std::vector<T> hA((size_t) batch * M * K), hB((size_t) (bcastB ? 1 : batch) * K * N), hC((size_t) batch * M * N), hR(hC.size());
  fill(hA, 1); fill(hB, 2);
  T *A, *B, *C, *R;
  cudaMalloc(&A, hA.size() * sizeof(T)); cudaMalloc(&B, hB.size() * sizeof(T));
  cudaMalloc(&C, hC.size() * sizeof(T)); cudaMalloc(&R, hC.size() * sizeof(T));
  cudaMemcpy(A, hA.data(), hA.size() * sizeof(T), cudaMemcpyHostToDevice);
  cudaMemcpy(B, hB.data(), hB.size() * sizeof(T), cudaMemcpyHostToDevice);
  long long sA = (long long) M * K, sB = bcastB ? 0 : (long long) K * N;
  dim3 blk(16, 16);
  dim3 gOld((N + 15) / 16, (M + 15) / 16, batch);
  dim3 g2((N + 31) / 32, (M + 31) / 32, batch);
  dim3 g4((N + 63) / 64, (M + 63) / 64, batch);
  dim3 g8((N + 127) / 128, (M + 127) / 128, batch);
  cudaEvent_t e0, e1; cudaEventCreate(&e0); cudaEventCreate(&e1);
  auto time = [&](auto launch, int reps) {
    launch(); cudaDeviceSynchronize();
    cudaEventRecord(e0); for (int i = 0; i < reps; ++i) launch(); cudaEventRecord(e1); cudaEventSynchronize(e1);
    float ms; cudaEventElapsedTime(&ms, e0, e1); return ms * 1000.0 / reps;
  };
  int reps = 20;
  double tOld = time([&] { gemm_old_batched<T><<<gOld, blk>>>(A, B, R, M, N, K, sA, sB); }, reps);
  cudaMemcpy(hR.data(), R, hR.size() * sizeof(T), cudaMemcpyDeviceToHost);
  auto check = [&](const char* name) {
    cudaMemcpy(hC.data(), C, hC.size() * sizeof(T), cudaMemcpyDeviceToHost);
    size_t bad = 0; for (size_t i = 0; i < hC.size(); ++i) if (memcmp(&hC[i], &hR[i], sizeof(T)) != 0) bad++;
    if (bad) printf("  !! %s differs from old on %zu of %zu cells\n", name, bad, hC.size());
  };
  double t2 = time([&] { gemm_reg_batched<T, 2, 2><<<g2, blk>>>(A, B, C, M, N, K, sA, sB); }, reps); check("2x2");
  double t4 = time([&] { gemm_reg_batched<T, 4, 4><<<g4, blk>>>(A, B, C, M, N, K, sA, sB); }, reps); check("4x4");
  double t8 = time([&] { gemm_reg_batched<T, 8, 8><<<g8, blk>>>(A, B, C, M, N, K, sA, sB); }, reps); check("8x8");
  double flop = 2.0 * batch * M * (double) N * K;
  printf("%-34s old %8.1f us | 2x2 %8.1f (%4.1fx) | 4x4 %8.1f (%4.1fx) | 8x8 %8.1f (%4.1fx) | best %.0f GFLOP/s\n", label, tOld, t2, tOld / t2, t4, tOld / t4, t8, tOld / t8, flop / (std::min(std::min(t2, t4), t8) * 1e3));
  cudaFree(A); cudaFree(B); cudaFree(C); cudaFree(R);
}

int main(int argc, char** argv) {
  int dev = 0; cudaDeviceProp pr; cudaGetDeviceProperties(&pr, dev);
  printf("device %s, %d SMs\n", pr.name, pr.multiProcessorCount);
  if (argc > 1) {
    printf("== threshold zone (blocks8 = ceil(M/128)*ceil(N/128)*batch) ==\n");
    run<float>("1 x 256x256 . 256x1024 (b8=8)", 1, 256, 1024, 256, false);
    run<float>("1 x 512x512 . 512x512 (b8=16)", 1, 512, 512, 512, false);
    run<float>("1 x 384x384 . 384x384 (b8=9)", 1, 384, 384, 384, false);
    run<float>("1 x 768x768 . 768x768 (b8=36)", 1, 768, 768, 768, false);
    run<float>("1 x 512x512 . 512x1024 (b8=32)", 1, 512, 1024, 512, false);
    run<float>("1 x 512x2048 . 2048x512 (b8=16,K2048)", 1, 512, 512, 2048, false);
    run<float>("4 x 256x256 . 256x384 (b8=24)", 4, 256, 384, 256, true);
    run<float>("4 x 256x384 . 384x512 (b8=32)", 4, 256, 512, 384, true);
    run<float>("8 x 256x192 . 192x256 (b8=32,K192)", 8, 256, 256, 192, false);
    run<float>("16 x 128x64 . 64x128 (b8=16,K64)", 16, 128, 128, 64, false);
    run<float>("64 x 64x512 . 512x64 (book attn, b8=64,small tiles)", 64, 64, 64, 512, false);
    run<float>("64 x 64x512 . 512x2048 (book ffn, b8=1024)", 64, 64, 2048, 512, true);
    run<float>("64 x 64x2048 . 2048x512 (book ffn down)", 64, 64, 512, 2048, true);
    run<float>("12 x 256x256 . 256x256 (guide 12x256)", 12, 256, 256, 256, false);
    run<float>("16 x 128x128 . 128x128 (guide 16x128)", 16, 128, 128, 128, false);
    run<float>("16 x 64x64 . 64x64 (guide 16x64)", 16, 64, 64, 64, false);
    return 0;
  }
  printf("== f32, the step's stacked shapes (batch 4) ==\n");
  run<float>("4 x 256x384 . 384x192 (Q proj, bcast W)", 4, 256, 192, 384, true);
  run<float>("4 x 256x192 . 192x256 (scores)", 4, 256, 256, 192, false);
  run<float>("4 x 256x256 . 256x192 (attn.V)", 4, 256, 192, 256, false);
  run<float>("4 x 256x384 . 384x384 (linear-o)", 4, 256, 384, 384, true);
  run<float>("4 x 256x384 . 384x1536 (mlp up)", 4, 256, 1536, 384, true);
  run<float>("4 x 256x1536 . 1536x384 (mlp down)", 4, 256, 384, 1536, true);
  run<float>("4 x 256x384 . 384x138 (head)", 4, 138, 384, 256, true);
  run<float>("4 x 384x256 . 256x1536 (grad W up)", 4, 384, 1536, 256, false);
  printf("== f32, n x n ==\n");
  for (int n : {64, 128, 256, 512, 1024, 2048}) { char l[64]; snprintf(l, 64, "%d x %d", n, n); run<float>(l, 1, n, n, n, false); }
  printf("== f64, n x n ==\n");
  for (int n : {64, 128, 256, 512, 1024, 2048}) { char l[64]; snprintf(l, 64, "%d x %d", n, n); run<double>(l, 1, n, n, n, false); }
  printf("== edges (odd sizes) ==\n");
  run<float>("3 x 33x17 . 17x45", 3, 33, 45, 17, false);
  run<double>("2 x 65x130 . 130x67", 2, 65, 67, 130, true);
  run<float>("1 x 1x1 . 1x1", 1, 1, 1, 1, false);
  run<float>("5 x 100x7 . 7x100", 5, 100, 100, 7, false);
  return 0;
}
