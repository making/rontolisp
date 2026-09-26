# Interpreter and JVM bridges disagree on character marshalling

Difficulty: Low

(java:static "java.lang.Character" "charCount" #\a): the interpreter signals
"No matching method", the compiled class prints 1. JavaBridgeTemplate.marshal
accepts a character for int/Integer (and refuses a supplementary code point for
char); eval/JavaInterop's LispChar arm does neither (it also truncates a
supplementary code point with a (char) cast). Add the failing interpreter case
to JavaInteropTest (and the mirror in JvmJavaInteropCompilerTest), then align
eval/JavaInterop with the template's rules and pin both.
