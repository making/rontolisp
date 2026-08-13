# `rontolisp:fetch` sends no `User-Agent` on the component backend

Difficulty: Medium

`rontolisp:fetch` sends a DIFFERENT request on the component backend than on the
interpreter and the JVM, and the difference is invisible until a server rejects it.
The interpreter/JVM path goes through `java.net.http` (`HttpSupport.requestAsync`),
which injects `User-Agent: Java-http-client/<jdk>` whenever the caller set none. The
component path (`http.lisp` `%fetch-send`) puts exactly the caller's headers into
`fields` and nothing else, so a caller-silent request goes out with no `User-Agent`
at all.

## Repro

```lisp
(let ((res (rontolisp:await (rontolisp:fetch "https://httpbingo.org/get"))))
  (print (getf res :status))
  (print (rontolisp:await (rontolisp:read-all (getf res :body)))))
```

| backend | result |
| --- | --- |
| interpreter | `200` + the JSON echo (`"User-Agent": ["Java-http-client/25.0.4"]`) |
| JVM | same |
| component (`--component`, `wasmtime -S http`) | `402` + `""` |

The 402 is the origin's, not ours -- fly.io's edge answers `402 Payment Required`
with `content-length: 0` to a `User-Agent`-less request, which reproduces outside
rontolisp entirely:

```
printf 'GET /get HTTP/1.1\r\nhost: httpbingo.org\r\nconnection: close\r\n\r\n' \
  | openssl s_client -quiet -connect httpbingo.org:443 -servername httpbingo.org
# HTTP/1.1 402 Payment Required
curl -s -o /dev/null -w '%{http_code}\n' -H 'User-Agent:' https://httpbingo.org/get   # 402
```

Adding `("User-Agent" . "...")` to the fetch options makes the component print the
same body as the interpreter, so nothing in the body/stream path is at fault: the
plists, `%stream-new`, `read-all` and the close protocol all behave. What the user
sees is an empty string, because an empty body IS what a 402 carries.

## What to decide

The essential fix is that fetch sends the SAME request on every backend, i.e. a
default `User-Agent: rontolisp/<version>` (`Version` / `VersionInfo`) when the caller
supplied none -- which means setting it EXPLICITLY on the JDK path too, overriding
the `Java-http-client` default, not only adding it in `http.lisp`. Points to settle
before writing it:

- Case-insensitive "did the caller already set one" test, on both paths (HTTP field
  names are case-insensitive; `%http-add-headers` compares nothing today).
- `--host-fetch` (Preview 1 reactors) and the browser playground
  (`Target_HttpSupport`, `fetch()`) delegate to a host that supplies its own agent
  string and, in the browser, forbids overriding it. Those two are documented
  exceptions, not backends to force into line.
- Whether the default is suppressible (an explicit empty value = send none).

## When it is fixed

- A `ci-spec.yaml` case cannot reach the network, so the gate is the existing
  fetch E2E pair plus an assertion on the request the local test server RECEIVED --
  find it near the other four-backend fetch tests.
- `.kb/fetch-http.md` currently says nothing about request headers we add on the
  caller's behalf; the default and the two exceptions above go there, with the
  re-evaluation trigger.
- `doc/{en,ja}/reference/functions/rontolisp-fetch.md` gains the same one line.
