# catch / throw special forms

Goal: standard `catch`/`throw` with dynamic (runtime-evaluated) tags on all
backends. Today both are entirely absent -- the only non-local exits are
`block`/`return-from` (lexical) and `tagbody`/`go` (`.kb/do-return-block.md`).

Concrete consumer: `postmodern/json-encoder.lisp` -- 4 `catch` / 5 `throw`
sites, all with the quoted tag `'all-caps` (camel-case detection bails out of
a `map nil` closure). Blocks `.todo/202-postmodern-non-mop-milestone.md`.

## Notes

- Tags are compared with `eq` at runtime; the json-encoder tags are constant
  quoted symbols, so a first cut may restrict to symbol tags if that keeps the
  lowering on the existing `%nlx-tag` machinery -- but say so in the docs and
  keep full dynamic tags as the target.
- The wasm backends already have a block-exit throw/catch scheme (EH mode) for
  cross-lambda `return-from`; `catch`/`throw` should reuse it. A program using
  `catch` will then need `-W exceptions=y` like the other EH forms -- document
  alongside them.
- `unwind-protect` cleanups must run when a `throw` unwinds through them; add
  that to the pinning test from the start.
- Interaction with the condition system: `throw` through a `handler-case`
  region must not be caught as a condition.
- Also wanted by `alexandria` utilities and the wider library ecosystem, so
  pin behavior in `ci-spec.yaml`, not just unit tests.
