# Verify the shipped --gpu bridge class files on a real GPU

Difficulty: Medium

The --gpu bridge became program-named class files written beside the output
(`<Program>$GpuBridge` and `$Gpu*`) instead of a defineClass blob (commit 74cd71c9d,
.kb/template-class-embedding.md). It was verified only on a GPU-less Linux host, so
only the CPU fallback path ran; the device path (CUDA) never executed through the
shipped classes, under the JVM or under native-image.

Plan: on a CUDA host, run the --gpu tests and examples through `-o X.class`,
`-o x.jar` under `java -jar`, and, opt-in, ShippedBridgeNativeImageE2eTest
(-Drontolisp.native-image.e2e=true) with the device actually used (assert it did,
not the fallback; .kb/gpu.md). Fix anything that breaks (failing test first).
