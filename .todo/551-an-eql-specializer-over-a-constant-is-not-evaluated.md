# 551. An `(eql <constant>)` specializer is taken as the symbol, not evaluated

Difficulty: Medium

CLHS 7.6.2: the form in an `(eql form)` parameter specializer is EVALUATED, at the time
the method is defined. rontolisp evaluates a quoted form (`(eql 'x)` -> the symbol `x`)
and self-evaluating literals, but a bare symbol is taken as the symbol itself -- so a
specializer naming a CONSTANT dispatches on the wrong object, silently. No error at
definition; the only symptom is a "no applicable method" much later.

```lisp
(defconstant +k+ 22)
(defgeneric g (a b))
(defmethod g (a (b (eql +k+))) (list :const a b))
(g 1 22)   ; => No applicable method: G on INTEGER  (SBCL: (:CONST 1 22))
```

Found by the cl+ssl probe (`.kb/cffi.md`): upstream's `x509.lisp` writes one
`(defmethod decode-asn1-string (asn1-string (type (eql +v-asn1-utf8string+))))` per ASN.1
string type, so certificate handling is unreachable even though the TLS handshake before
it completes. The idiom -- a table of constants, a method per constant -- is common well
beyond cl+ssl.

## Where it is

`macro/LispMacroExpander.parseEqlSpecializerValue`. It accepts a bare `LispSymbol` as the
eql VALUE; that branch is what has to consult a constant table instead.

## The design question this opens

`parseSpecializer` is static and reaches only the `ClosRegistry` the defmethod walk
carries -- `macro` sits below `eval`, so the macro-time evaluator is out of reach. The
narrow fix is a table of top-level `defconstant` values (literal value forms only, in
source order, which is enough for every consumer seen) recorded as the program is walked
and consulted here. Decide where that table belongs before writing it: hanging it off
`ClosRegistry` is the only threading-free spot today and is poor cohesion.

Keep the current lenience for a bare symbol that names NO constant (existing sources
spell `(eql foo)` meaning the symbol, which is not CL but is what they get today), or
decide deliberately to drop it -- the choice belongs in `.kb/clos.md` either way.

Expansion is shared, so one fix covers all four backends; pin it with a ci-spec case
beside the existing `clos-defgeneric-defmethod-eql-dispatch`.
