// index.js -- the whole Worker. There is nothing else in this directory's
// JavaScript: no WASI shim, no allocator, no bindings library.

import module from "./worker.wasm";

// The module imports nothing, so instantiating it is one synchronous line with
// an empty import object -- and it happens once per isolate, not per request.
//
// One line, unlike ../httpbin: this module exports no `_initialize` (--no-gc,
// and worker.lisp has no top-level forms, so there is nothing to run), and none
// of these three functions can trap and leave the instance unusable.
const lisp = new WebAssembly.Instance(module, {}).exports;

/** Decode a (pointer, length) result out of the module's linear memory. */
function readString([ptr, len]) {
  return new TextDecoder().decode(new Uint8Array(lisp.memory.buffer, ptr, len));
}

export default {
  async fetch(request) {
    const { pathname, searchParams } = new URL(request.url);
    const number = (name, fallback) => Number(searchParams.get(name) ?? fallback);

    switch (pathname) {
      case "/":
        // A `:string` result: two i32s naming bytes in linear memory.
        return text(readString(lisp.greet()));

      case "/add": {
        // A `:s32` result: JavaScript just gets a number back.
        const [a, b] = [number("a", 2), number("b", 3)];
        return text(`${a} + ${b} = ${lisp.add(a, b)}`);
      }

      case "/fib": {
        const n = number("n", 20);
        return text(`fib(${n}) = ${lisp.fib(n)}`);
      }

      default:
        return text(`no route for ${pathname}\n\ntry /, /add?a=2&b=3, /fib?n=20`, 404);
    }
  },
};

function text(body, status = 200) {
  return new Response(body + "\n", {
    status,
    headers: { "content-type": "text/plain; charset=utf-8" },
  });
}
