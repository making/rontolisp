# e29. Ring-compatible util namespaces for the Clojure front end

Difficulty: Medium

After e28, a Ring app still writes responses, parameter parsing and routing by hand:
ring-core cannot be loaded (no Clojars dependency loading; its Java interop would not run
on wasm). Ship the commonly used pure-function subset as built-in namespaces, the
counterpart of the built-in Clack shims.

Candidates, in order of use:

- `ring.util.response`: `response`, `status`, `header`, `content-type`, `redirect`,
  `not-found`, `bad-request`, `get-header`, `set-cookie`.
- `ring.middleware.params` (`wrap-params`: query string and urlencoded form into
  `:query-params`/`:form-params`/`:params`), `ring.middleware.keyword-params`,
  `ring.util.codec` (`url-decode`, `form-decode`).
- `ring.middleware.content-type`, `ring.middleware.not-modified`: only if cheap.

Decide first how a built-in namespace is shipped: Clojure source as a resource read
through the namespace loader (behaves exactly like user code, lowering unchanged) versus
lowering rows (`ClojureNamespaceLowering`, like `clojure.string`). Prefer the resource
unless measured size or a missing core verb says otherwise. Each var follows the oracle
(ring-core) behaviour, pinned on all four backends; names outside the subset refused by
name. Docs in `doc/en` + `doc/ja`.
