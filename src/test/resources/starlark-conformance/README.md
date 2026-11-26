# Starlark conformance corpus

`go/`, `java/` and `rust/` are `test_suite/testdata` of https://github.com/bazelbuild/starlark
at commit `c0af0ea`, unchanged, under the Apache License 2.0 (`LICENSE`). `spec/` is written
here in the same form, from the examples of that repository's `spec.md` for the types the
corpus does not cover. Run on four backends by `StarlarkSpecE2eTest#conformance`.
