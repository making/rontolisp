# Ship the java: bridge as a class file, not a defineClass blob

Difficulty: Medium

Measured 2026-09-26 (GraalVM 25.0.4): a -o prog.jar using java: fails under
native-image at _javaInit (Lookup.defineClass: "Classes cannot be defined at
runtime"), with or without native-image-agent config
(experimental-class-define-support records nothing). With the bridge added to
the jar as a plain RontoLispJavaBridge.class and _javaInit reduced to bind(),
an agent-generated config builds a working 15.5MB binary. Caveat measured: the
config covers only paths the agent run took; a double argument not seen during
tracing fails with MissingReflectionRegistrationError for Math.max(double,double).

a14 note: the bridge is now emitted only when a site needs it (JvmJavaSites.needsBridge:
a site left to run time, a java:proxy, a function argument; a mispredicted one retries
through the _javaInit gate); resolved sites are direct calls and --java-static output
carries no bridge at all (native-image with no metadata, JavaStaticNativeImageE2eTest).
This item is about the programs that still need it.

Plan:
- Jar/war output: write the renamed bridge as a regular entry; _javaInit only
  binds. .class output: write it beside the class (precedent: runtime classes).
- Same audit for the other defineClass blobs (geom, gpu, blas, simd, objc, ffi).
- Native E2E: jar + agent config runs; document the coverage caveat.

Part of the java: static-compilation series (a12 -> a13 -> a14 -> a15/a16),
modelled on Clojure (type hints, param tags, warn-on-reflection), with the
interpreter following the same selection rule.
