# 531. `:script-name` is fixed empty, so a war under a context path misroutes

Difficulty: High (a key of the shared Clack environment gains a real value on
one transport and must keep its current one on the other four -- so it is the
raw tuple, three constructions, the ci-spec pin and the docs, in one change)

Child of `.todo/529`. Independent of `.todo/530`: a war deployed at the ROOT
context serves correctly without this, and the defect is reachable today by
anyone who mounts one anywhere else. Filing it separately because it is a
conformance gap in the shared value model, not a war feature.

## The defect

`:SCRIPT-NAME` is hard-coded to the empty string in all three constructions of
the Clack environment:

- `src/main/resources/am/ik/rontolisp/eval/http-server.lisp:428` --
  `:SCRIPT-NAME ""` in `%http-make-env` (WASM component)
- `runtime/RontoHttpClack.java:112` -- `case RontoClackEnv.SCRIPT_NAME -> quote("")` (JVM)
- `eval/LispEvaluator.java:9002` -- `case ClackEnv.SCRIPT_NAME -> new LispString("")` (interpreter)

and `RontoClackEnv.SCRIPT_NAME`'s own javadoc states it as the contract:
*"always the empty string, never nil"*. `doc/en/guides/clack.md:58` publishes
the same.

That is correct for every transport rontolisp has today, because all four are
root-mounted: the JDK server owns its port, `wasi:http` hands over a whole
authority, the reactor is called with a bare path. It is wrong the moment an
application is MOUNTED, which is what a servlet container does by default.

Measured 2026-08-26, the `.todo/529` spike war deployed at `/myapp` on Tomcat
11:

```
$ curl -s 'http://localhost:18080/myapp/hello?q=1'
path=/myapp/hello method=GET query=q=1 ...
```

`:path-info` is `/myapp/hello` and `:script-name` is `""`. Rack and PSGI --
which Clack follows -- say the reverse: `SCRIPT_NAME` is the application's
mount point and `PATH_INFO` is the remainder, with `REQUEST_URI` keeping the
full raw target. So **every route in a ningle, tiny-routes or lack application
misses**, and the failure is a blanket 404 with nothing in it that points at
the mount point. `lack/app/mount` and the session middleware already `setf
getf` these two keys for exactly this reason (`.kb/http-server.md`); the
transport is simply never supplying the initial split.

## The fix

**The transport is the only thing that knows its mount point**, so the value
enters where the other transport facts do -- through the raw tuple, not through
a special case in the shared library.

- `runtime/RontoHttpServer.Request` gains an eleventh component, `scriptName`,
  defaulting to `""` in the `Request.of` test factory. It joins the ten
  transport facts the record already documents as "everything only this server
  can know".
- `%http-make-env`'s positional raw tuple gains `(nth 10 raw)`:
  `:SCRIPT-NAME` becomes that value, and `:PATH-INFO` becomes the
  percent-decode of the target path with the raw prefix removed.
  `:REQUEST-URI` stays the full raw target verbatim -- Rack keeps it whole, and
  a mounted application still needs to build absolute URLs.
- `LispEvaluator.buildClackEnv` and `RontoHttpClack.buildEnv` take the same
  value the same way. The `default -> throw` switch in each is what makes this
  safe: nothing compiles until all three are written
  (`.kb/http-server.md`, "The invariant").
- The four existing transports pass `""` and are byte-for-byte unchanged. The
  WASM component's `%serve-handle` tuple builder in `http.lisp` gains the
  literal; the reactor envelope may accept an optional `"script-name"` key,
  defaulting to `""` -- a host that mounts a Worker under a path has the same
  problem, and the key costs nothing.

**Where the servlet's value comes from**: `getContextPath() + getServletPath()`.
The Servlet spec leaves `getContextPath()` UNDECODED, which is what makes it a
prefix of the raw target and therefore strippable before percent-decoding -- do
not reach for `getPathInfo()`, which is decoded and would force a re-encode.
The sum also generalizes past `/*`: a servlet prefix-mapped at `/api/*` inside
context `/myapp` mounts the application at `/myapp/api`, which is exactly what
Rack means by `SCRIPT_NAME`.

Edge cases worth a test each: the root context (`getContextPath()` is `""`, so
nothing changes), a context whose path needs decoding, a request for the mount
point itself (`/myapp` with no trailing slash -- `:path-info` must be `""`, not
`/`), and a `scriptName` that is somehow not a prefix of the target, which must
degrade to today's behavior rather than signal.

## Pins and docs

- ci-spec `http-clack-environment-shape` and `http-percent-decode` run on all
  four backends and must keep passing unchanged (every one of them is
  root-mounted, so `:script-name` stays `""` there). Add a case that drives a
  non-empty script name through the shape directly, so the split is pinned on
  every backend and not only on the transport that can produce it.
- `RontoClackEnv.SCRIPT_NAME`'s javadoc, `.kb/http-server.md`'s "The
  environment contract" clause, and `doc/{en,ja}/guides/clack.md`'s table row
  all currently say "always empty". All three change together.
- The `.todo/530` war E2E gains a non-root-context leg -- it is the only
  transport that can produce the value, so it is the only place the whole path
  is exercised end to end.
