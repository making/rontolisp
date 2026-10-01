# Raw `\u` in a Java comment breaks the build and the formatter

Found during b47 (which reshaped the Clojure string `\u` escape errors): a raw
backslash-u inside a Java comment -- block or line -- is an `illegal unicode
escape` compile error (javac preprocesses `\u` everywhere, comments included),
and `spring-javaformat:apply` first MANGLES the comment silently (spaces eaten,
` * ` prefixes stripped, words reflowed) before a later `validate`/compile
fails on it. The b47 work javadoc and one test comment both hit this; the fix
was to spell comments without a raw backslash-u (`backslash-u`,
`{@code truncated}`, `\\u` inside string literals is fine -- the formatter
never touches literal contents).

## Acceptance

- `.kb/session-workflow.md` or the java-standards skill records the rule: never
  write a raw `\u` in a `.java` file outside a string literal (use `\\u` even in
  comments, or words like `backslash-u`).
- Optional enforcement: a test or build step that greps `src/main` + `src/test`
  for a raw `\u` outside string literals and fails with the rule.

## Depends on

b47 (whose mangled javadoc exposed it).
