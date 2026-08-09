# `--no-wasi`: `random` traps the whole instance at `_initialize`

Difficulty: Medium

A `--no-wasi` reactor whose program reaches `random` **at load time** cannot be
instantiated at all: `_initialize` traps with a bare `RuntimeError: unreachable`,
before any exported function can be called, and with nothing in the message
naming `random`. The Worker in `examples/cloudflare-workers/httpbin-clack/`
cannot grow its middleware variant because of exactly this.

## The measurement

```bash
cat > p.lisp <<'EOF'
(ql:quickload "lack-request")
(rontolisp:wasm-export 'ping :params '() :returns :s32)
(defun ping () 42)
EOF
rontolisp p.lisp -o p.wasm --no-wasi --optimize
node -e 'const m=new WebAssembly.Instance(new WebAssembly.Module(require("fs").readFileSync("p.wasm")),{});
         try{m.exports._initialize();console.log("OK",m.exports.ping())}catch(e){console.log("TRAP",e.message)}'
# -> TRAP unreachable
```

Narrowed system by system (each probe is the three lines above with one
`ql:quickload`), the chain is

```
lack-request -> http-body -> fast-http -> smart-buffer
```

and the single offending form is `smart-buffer/src/smart-buffer.lisp`:

```lisp
(defvar *temporary-directory*
  (... (merge-pathnames (format nil "smart-buffer-~36R" (random (expt 36 8)))
                        (uiop:default-temporary-directory))))
```

`quri`, `cl-ppcre`, `yason`, `circular-streams`, `flexi-streams`, `babel`,
`cl-utilities`, `trivial-gray-streams`, `alexandria`, `proc-parse` and `xsubseq`
all instantiate fine; `smart-buffer`, `fast-http`, `http-body` and
`lack-request` all trap.

Confirmed in the disassembly: the top-level init function reaches
`i32.const 120 / i32.const 8 / call 4`, and function 4 is one of the
`(param i32 i32) (result i32)` stubs `--no-wasi` leaves as bare `unreachable` --
i.e. `wasi_snapshot_preview1.random_get`.

**Only the reactor build is affected.** The identical program is fine on the
interpreter, on the JVM, and as a Preview 1 `_start` module (`--optimize`, no
`--no-wasi`) -- there `random` has a real `random_get` behind it. The reactor is
the one shape whose top-level forms run inside an export the host calls.

## Why this is worth fixing rather than documenting

`.kb`/the READMEs already say `--no-wasi` makes `print`, `random`,
`get-universal-time` and `uiop:getenv` trap "when called". That is a fair
contract for a form the *user* wrote. It is a poor one for a form inside a
library the user quickloaded, because:

- it fires at **load** time, so the instance never exists and there is no
  `handler-case` anywhere that could see it;
- the diagnostic is `unreachable` with no name -- finding `smart-buffer` from it
  took a per-system bisection plus a `wasm-tools print`;
- the compiler already KNOWS at build time that this module reaches `random`, so
  it can say so instead of leaving a trap behind.

## Options, in rough order of ambition

1. **Diagnose it at compile time.** Warn (or error) when a `--no-wasi` build
   reaches a WASI-only primitive from a TOP-LEVEL form -- the reachability
   information is the tree-shaker's, which already runs under `--optimize`.
   Cheapest, and it would have turned the bisection above into one line of build
   output. Does not make the program work.
2. **Give `--no-wasi` `random` an entropy source that is not WASI.** A host can
   supply one trivially (a Worker's `index.js` has `crypto.getRandomValues`), so
   the options are: an OPTIONAL import the host may provide, or a seed poked
   into linear memory before `_initialize`, or a deterministic built-in PRNG
   seeded from a constant. The third keeps "zero imports", which is the whole
   selling point of the `--no-wasi` Worker examples, at the price of every
   instance of an isolate sharing a sequence. Pick deliberately and write the
   reason down.
3. **Make the stubs call-time errors rather than bare `unreachable`** -- the
   todo-195 call-time-error policy, applied to the `--no-wasi` WASI stubs. The
   trap becomes a Lisp condition naming `random`, which a `handler-case`
   around the offending load could ignore. On its own it does not save this
   case (the form is a `defvar` initialiser at top level, outside any handler),
   but it makes every OTHER `--no-wasi` trap self-describing.

1 and 3 are complementary and neither breaks anything; 2 is the only one that
makes `lack-request` load.

## Done when

- `(ql:quickload "lack-request")` in a `--no-wasi` program either instantiates,
  or fails at BUILD time with a message naming the primitive and the system --
  verified on V8 (node is enough), not inferred.
- `examples/cloudflare-workers/httpbin-clack/` can drop the "Middleware,
  `lack:builder`, sessions" caveat from its README, or the caveat states the
  new, better diagnostic.
- The `.kb` file for the `--no-wasi` backend records what `random` does there
  and why, so the next visitor can tell whether the reason still holds.

## A second consumer is now blocked on this (2026-08-09, todo-300)

`examples/cloudflare-workers/hello-ningle/` is a complete, working ningle
application -- interpreter, JVM and Preview 1 all green through its
`check.lisp`, and the same routes serve under `wasmtime serve` -- whose
`--no-wasi` Worker build is the ONE thing that does not run: ningle reads every
request through `lack-request`, so it inherits exactly the chain measured above
and `_initialize` traps. Its README and the `cloudflare-workers/README.md` row
say so and point at `httpbin-clack`'s measurement rather than repeating it.

So the "Done when" below now has a second, sharper acceptance case: when
`lack-request` instantiates, that directory becomes deployable with no source
change, and its README loses the blockquote at the top. It also raises the
stakes on option 2 -- the routing library a Clack user is most likely to reach
for cannot be put on a Worker at all today.

## Related

`examples/cloudflare-workers/httpbin-clack/README.md` (the measurement above,
in the "Middleware" section), `examples/cloudflare-workers/httpbin/README.md`
(the "Limitations" list this contradicts in spirit), `.todo/280`, `.todo/281`
(the other reason a Worker cannot just call `clackup`).

Since `--component --no-wasi` landed (todo-298), the same failure class also
exists one step EARLIER on the reactor component: its top level runs from the
core start section, so this trap kills **instantiation itself** (`wasmtime`
reports it before any export exists, and jco's `instantiate(...)` throws) --
there is no `_initialize` call to place a try/catch around. Whatever fix is
chosen here must cover both entry shapes; the diagnosis option (1) is
entry-shape-independent, which is one more reason to start there.
