# Argument evaluation order is LEFT TO RIGHT on every backend

**Invariant: the argument forms of any call, and the element forms of `list` (and of
everything that lowers onto `list`), are evaluated left to right on the interpreter, the
JVM and both WASM backends.** Common Lisp specifies it; a program whose argument forms
have side effects observes the same sequence everywhere. This closed `.todo/014`
(2026-07-31) after it had shipped three separate field sightings.

## Why it broke, and only for some forms

Direct calls, `funcall`, arithmetic, `vector` and `list*` were already left to right:
their emitters push operands onto the stack in source order, which is also the order the
call consumes them.

`list` is the exception, and the reason is structural: a cons chain has to be LINKED from
the LAST element backwards (`(cons a (cons b (cons c nil)))`). `Jvm/WasmListCompiler`
walked the arguments in that consumption order and compiled each expression as it went,
so the SIDE EFFECTS ran right to left while the result value came out correct -- which is
exactly why it stayed latent for so long.

Everything that lowers onto `list` inherited it:

- backquote with unquotes -- `` `(:a ,(f) :b ,(g)) ``
- `make-array :initial-contents (list ...)`

The three sightings, all the same defect:

1. `(list (funcall *bump* 0) (funcall *bump* 0) (funcall *bump* 1))` answered `(1 2 1)`
   interpreted and `(2 1 1)` compiled (the original `.todo/014` report).
2. The `--component` socket layer: `(print (list (rb sock) (rb4 sock) (rb4 sock)))` --
   each helper doing sequential `read-byte`s -- consumed the wire bytes in reverse. It
   was briefly misfiled as an async-scheduler bug (the deleted `.todo/176`). Note the
   contrast that proves it was not the async machinery: reads promoted to
   `rontolisp:await` are hoisted into sequenced bindings by `WasmAwaitNormalizer` and
   were always correct; only un-promoted plain-call arguments reversed.
3. local-time's TZif reader. `%tz-read-header` builds its plist with
   `` `(:utc-count ,(%read-binary-integer inf 4) :wall-count ,(...) ...) `` off a binary
   stream, so the six header fields were read in reverse: `/etc/localtime` decoded with
   `type-count 0` instead of `1` and the timezone came out with an EMPTY subzone vector,
   which then blew up as an out-of-bounds `elt` deep inside `%subzone-as-of` -- three
   call levels away from the actual bug.

## The fix

`compiler/ArgumentOrder.isOrderIndependent` (backend-shared, backend-free) says whether an
argument form can be reordered against its siblings. `Jvm/WasmListCompiler` pre-evaluate
every argument that CANNOT into a temp slot, in source order, and then link the chain from
the temps.

The predicate is deliberately narrow -- self-evaluating literals, `nil`/`t`/keywords, and
`(quote DATUM)`. **A bare variable reference is NOT order-independent**: an earlier
argument may `setq` it, and hoisting the read past the other arguments would then read a
value a LATER argument stored. The narrowness is what keeps an all-literal `(list 1 2 3)`
emitting the bytes it always did, which is the overwhelmingly common case.

The JVM emitter also stopped allocating a fresh accumulator slot per element (it now
reuses one), so the fix costs FEWER locals than the old code on a literal list and one
extra per effectful element otherwise. That matters because `.todo/137` (JVM local-slot
overflow) is still open.

## Pinning

- ci-spec case `argument-evaluation-order-left-to-right` (all four backends) -- the
  `.todo/014` reproduction plus the backquote and `make-array :initial-contents` shapes,
  and the direct-call shape that was always correct so it stays correct.
- `JvmLispCompilerTest#compileArgumentFormsEvaluateLeftToRight`,
  `WasmLispCompilerIntegrationTest#argumentFormsEvaluateLeftToRight`.

`.todo/014` also noted that the older evaluation-order-INDEPENDENT tests
(`compileArrayCapturedInClosure` on both compilers, `arrayCapturedInClosure` in
`LispEvaluatorTest`, the `arrays-cross-backend` ci-spec case) were written to sequence
every step through a top-level `defparameter` precisely to route around this. They can now
be simplified back to the natural single-form shape; they are left alone deliberately,
because a test that pins the *old* workaround shape still passes and costs nothing.
