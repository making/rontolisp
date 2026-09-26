# Ship the --blas and objc: downcall registration in compiled outputs

Difficulty: Medium

A native image built from a compiled jar reads only the jar's own
`META-INF/native-image/`, not rontolisp's. `--gpu` now ships its downcall shapes there
(`am/ik/gpu/reachability-metadata.json` -> `META-INF/native-image/rontolisp-gpu/<program>/`,
`JvmGpuRuntimeBuilder.nativeImageMetadataPath`, .kb/gpu.md "Native image"); before that
the image refused the CUDA binding and silently ran every member on the CPU.

- `--blas`: `ShippedBridgeNativeImageE2eTest` still needs the tracing agent's config. The
  shapes are closed (gemm/gemv at both widths, gemm critical and plain), so the same
  mechanism would make a `--blas` jar build with no configuration. Pin the shipped file
  EQUAL to `JvmBlasTemplate`'s bound shapes, as `NativeImageForeignConfigTest` does for
  the GPU drivers.
- `objc:`: `anObjcJarRunsAsANativeImageThatHandsThreadZeroToAppKit` builds its image
  with the agent's config too. The closed objc_msgSend table in rontolisp's
  reachability-metadata.json is what a compiled `objc:` jar needs; shipping that subset
  would make the image build with no configuration (verify on macOS).
