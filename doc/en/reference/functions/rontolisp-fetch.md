# rontolisp:fetch

`(rontolisp:fetch url &optional options)`

Performs an outgoing HTTP request, modeled on the JavaScript `fetch` API, and
returns a property list `(:status <integer> :body <string> :headers <alist>)`.
The optional `options` plist accepts `:method` (a string, default `"GET"`;
one of `GET`/`HEAD`/`POST`/`PUT`/`DELETE`/`OPTIONS`/`PATCH`), `:headers` (an
alist of `(name . value)` string pairs), and `:body` (a string). It runs on the
interpreter and JVM via the JDK HTTP client; on WASM it is component-only. See
[HTTP requests](../builtin-functions.md#http-requests-rontolispfetch) for the
full reference and limitations.

Because it makes a network request, it is shown here statically rather than as a
runnable example:

```console
(let ((res (rontolisp:fetch "http://example.com/")))
  (getf res :status))   ; => 200
```
