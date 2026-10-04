# c96. NativeOutputE2eTest reads ci-spec.yaml without the surrogate-safe reader

Difficulty: Low

`NativeOutputE2eTest#aCiSpecSliceWithArgumentsPrintsWhatWasmtimePrints` errors at `661073d34`
(full suite, measured 2026-10-04): `IndexOutOfBoundsException: Range [1025, 1025 + 1) out of
bounds for length 1025` from snakeyaml-engine `StreamReader.extendIfTrailingHighSurrogate`.
`slice()` hands the raw resource stream to `YAMLMapper.readTree`; the ci-spec cases added since
moved a high surrogate onto a buffer boundary. This is the snakeyaml-engine 3.0.1 crash
`testsupport/YamlResources.safeReader` exists to avoid, and `NativeOutputE2eTest` is the only
`YAMLMapper` reader of a test resource that does not go through it.

Plan: read the slice through `YamlResources` (expose `safeReader`, or a `readCiSpecTree()`
helper), so the next ci-spec edit cannot re-break it. The failing test already exists (the
full suite at `661073d34`).
