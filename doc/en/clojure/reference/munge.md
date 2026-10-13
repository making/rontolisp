# munge

`(munge s)`

The `str` of `s` with every character munged for filenames and class names, like the
oracle's: `-` is `_`, every other special character its `_NAME_` (`+` is `_PLUS_`, `*`
`_STAR_`, `?` `_QMARK_`, `!` `_BANG_`, `<` `_LT_`, `>` `_GT_`, `=` `_EQ_`, `'` `_SQUOTE_`, `"`
`_DQUOTE_`, `/` `_SLASH_`, `:` `_COLON_`, `~` `_TILDE_`, `@` `_CIRCA_`, `#` `_SHARP_`, `%`
`_PERCENT_`, `^` `_CARET_`, `&` `_AMPERSAND_`, `|` `_BAR_`, `{` `_LBRACE_`, `}` `_RBRACE_`,
`\` `_BSLASH_`). As a value one argument.

```clojure
(println (munge "foo-bar") (munge "+")) ; foo_bar _PLUS_
```
