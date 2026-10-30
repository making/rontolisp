# h38. yason loads unmodified on all four backends: pin it and list it

Difficulty: Low

Measured 2026-10-30: `(ql:quickload "yason")` then `yason:parse` of
`{"a":[1,2.5,"x",true,null],"b":{"c":"d"}}` and `yason:encode` of a hash table print
identical output on the interpreter, JVM, Preview 1 and the component. No patch.

Do the integration checklist of `.todo/152` ("Method per probe", step 4), with one ci-spec
case rather than a per-backend test class (CLAUDE.md), plus the
`doc/{en,ja}/guides/asdf-systems.md` row and a `.kb/asdf.md` entry.
