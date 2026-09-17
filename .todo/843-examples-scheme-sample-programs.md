# examples: a scheme directory of Scheme sample programs in the examples suite

Difficulty: Medium

`examples/` has no `.scm` program, so the experimental Scheme front end
(`.kb/scheme-frontend.md`) has no practical, self-contained showcase and `ExamplesE2eTest`
never runs one on the four backends.

## To do

1. a new examples scheme directory with a README (en; follow the other directories' shape) and a row in
   `examples/README.md`. Programs original to the repo (do not copy SICP corpus files --
   license), each exercising what the front end supports today, e.g.:
   - a SICP-style classic: symbolic differentiation, the eight queens, Huffman trees;
   - streams (`cons-stream`, infinite primes/sieve) -- only once `.todo/831` has landed;
   - a small metacircular evaluator reading its program from a list (not stdin unless
     `.todo/832` has landed);
   - console I/O, tail-recursive loops that stay within the documented limits.
2. An `examples/examples.yaml` entry per program, on every backend it runs on (normally
   interpreter, jvm, wasm, and the component leg), with the expected output checked.
   Run `-Drontolisp.examples=true -Drontolisp.examples.only=scheme`.
3. A program that needs an unsupported feature is not written around silently: file a
   todo for the gap (claim the number) and leave the program out until it lands.
4. Check whether the `format` command handles `.scm`; if not, keep the style consistent by hand.
