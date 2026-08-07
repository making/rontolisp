# Clack + `lack-request` does not compile: the Gray subclass precedes its base class

Difficulty: Medium

A program that quickloads BOTH a Clack server and `lack-request` fails to compile on
the JVM and on both WASM backends:

```
DEFCLASS: unknown superclass RONTOLISP:FUNDAMENTAL-BINARY-INPUT-STREAM
(a superclass must be defined by defclass first)
```

The interpreter runs the same program fine. This is the combination that gets a
served body parsed into parameters -- `lack/request:request-parameters`,
`request-body-parameters`, sessions, CSRF -- i.e. the reason to reach for Clack at
all rather than reading `:raw-body` by hand.

## A checked-in test is red, and has been since the Clack cutover

`LackEcosystemE2eTest#lackBuilderParsesARealServedBodyOnJvm` compiles exactly this
shape. Run its program through the CLI verbatim:

```bash
cat > builder-clackup.lisp <<'EOF'
(ql:quickload "clack")
(ql:quickload "lack")
(ql:quickload "lack-request")
(defvar *handler*
  (clack:clackup
   (lack:builder
    (lambda (env)
      (let ((req (lack/request:make-request env)))
        (list 200 '(:content-type "text/plain")
              (list (format nil "params=~A"
                            (lack/request:request-body-parameters req)))))))
   :server :rontolisp :port 18099 :silent t :debug nil))
EOF
rontolisp builder-clackup.lisp                        # interpreter: OK
rontolisp builder-clackup.lisp -o BuilderClackup.class # JVM: the DEFCLASS error
```

It went unnoticed because the class is `@EnabledIfEnvironmentVariable(RONTOLISP_LACK_E2E)`
AND `@Testcontainers(disabledWithoutDocker = true)` -- so with Docker down the
whole class skips, including the interpreter and JVM legs, which need no container
at all. `RONTOLISP_LACK_E2E=1 ./mvnw -Dtest=LackEcosystemE2eTest#lackBuilder... test`
reports `Tests run: 2, Skipped: 2, BUILD SUCCESS`.

`http-server.lisp` and `HttpServerLibrary.java` were both ADDED by `5b22fb0d` (the
`rontolisp:http-handler` -> Clack cutover) and `http-request-body-stream` did not
exist before it, so the regression dates from that commit.

## Why it happens

Three placement decisions that are each locally right and collide:

1. `HttpServerLibrary.process` PREPENDS `http-server.lisp` to the whole program
   (`out.addAll(forms()); out.addAll(program)`). Its buffered-body half contains
   `(defclass rontolisp::http-request-body-stream (rontolisp:fundamental-binary-input-stream) ...)`
   (`http-server.lisp:209`).
2. The Gray BASE classes (`gray.lisp:34`) reach the program from the
   `trivial-gray-streams` shim -- `lack-request` -> `http-body` -> `circular-streams`
   -> `trivial-gray-streams` -- and `ShimLibraries.forms` special-cases that name to
   prepend `GrayStreamsLibrary.protocolForms()` to the shim's own forms. That lands
   them at the SHIM'S SPLICE SITE, i.e. wherever the `ql:quickload` sits, mid-program.
3. `GrayStreamsLibrary.process` runs later (it sees the whole program, after
   `HttpServerLibrary.process`) but only prepends `protocolForms()` when
   `program.stream().noneMatch(GrayStreamsLibrary::definesProtocol)`. The shim splice
   already defines them, so it declines -- and nothing moves them.

Net: the subclass is at the top of the program, its base class is in the middle.

Without `lack-request` nothing defines the protocol, `GrayStreamsLibrary.process`
prepends it, and it lands ABOVE the http-server forms -- which is why every existing
Clack example compiles.

## Which half is at fault, measured

Each row is the same `(ql:quickload "lack-request")` program compiled `--component`,
varying only what the program names:

| program names | buffered half kept? | result |
| --- | --- | --- |
| nothing from the server library | not spliced at all | OK, 5,083,807 B |
| `%http-make-env` only | no (`keepBuffered` false) | OK, 4,430,543 B |
| `%http-body-stream` | yes | **the DEFCLASS error** |
| `clack:clackup` (`:raw-body :buffered`) | yes | **the DEFCLASS error** |

So it is exactly `HttpServerLibrary`'s buffered-body half -- the one form that
subclasses the Gray protocol -- and `HttpLibrary.usesBufferedBody` is true for every
`clackup` program by construction (the shim asks for `:raw-body :buffered`).

## The fix

Recommended: make **`GrayStreamsLibrary.process` HOIST** the protocol forms to the
front when they are already present but positioned after a form that subclasses
them, instead of declining outright. It runs after `HttpServerLibrary.process` and
already owns the "where does the protocol go" question, so this keeps ONE owner for
placement and touches the compile path only.

Do NOT fix it by dropping the `ShimLibraries` special case: that prepend is also
what the INTERPRETER's `loadSystem` relies on (it evaluates shim forms in order at
run time, with no `GrayStreamsLibrary.process` in play), so removing it would break
the leg that currently works.

The narrower alternative -- have `HttpServerLibrary.process` insert its forms after
the last Gray-protocol-defining form rather than at index 0 -- also works, but makes
one library's splice position depend on another's, which is the coupling that
produced this bug.

Whichever is chosen, a program that does not quickload a Gray shim must come out
BYTE-IDENTICAL (the hoist has to be conditional on the protocol actually appearing
after a subclassing form).

## Done when

- A failing test exists FIRST and is not Docker-gated: the interpreter and JVM legs
  of this shape need no container, so the regression must be pinned somewhere that
  runs in a normal `./mvnw test`. Splitting the container-free legs of
  `LackEcosystemE2eTest` out from the `@Testcontainers` class is the honest fix for
  the invisibility, and is part of this item -- a green suite that skips the only
  test covering the bug is what let this ship.
- `builder-clackup.lisp` above compiles and runs on all four backends, and
  `LackEcosystemE2eTest` gains the WASM legs for the builder-over-clackup exercise
  it currently only has for the interpreter and the JVM.
- `examples/net/httpbin-clack.lisp` still compiles unchanged, and a program with no
  Gray shim in its dependency closure produces a byte-identical module.
- `.kb/lack.md` says the chain runs on all four backends; it does so STANDALONE but
  did not alongside a Clack server. Record the combination explicitly there (and the
  re-evaluation trigger for the splice ordering) once it holds.

## Related

`.kb/lack.md`, `.kb/clack.md`, `.kb/gray-streams.md`, `.kb/http-server.md`
(the `:raw-body :buffered` contract and the module-size filter that makes the
buffered half conditional in the first place).
