# Ship the --blas downcall registration in compiled outputs

Difficulty: Medium

Split from a29 (whose objc: half stays macOS-only). A native image built from a compiled
jar reads only the jar's own `META-INF/native-image/`, not rontolisp's. `--gpu` ships its
downcall shapes there (`am/ik/gpu/reachability-metadata.json` ->
`META-INF/native-image/rontolisp-gpu/<program>/`, `JvmGpuRuntimeBuilder.nativeImageMetadataPath`,
.kb/gpu.md "Native image"); without it the image refused the CUDA binding and silently ran
on the CPU.

`--blas`: `ShippedBridgeNativeImageE2eTest` still builds its image with the tracing agent's
config. The shapes are closed (gemm/gemv at both widths, gemm critical and plain), so ship
them the same way and make a `--blas` jar build with no configuration. Pin the shipped file
EQUAL to `JvmBlasTemplate`'s bound shapes, as `NativeImageForeignConfigTest` does for the
GPU drivers. Verifiable on Linux x86_64.
