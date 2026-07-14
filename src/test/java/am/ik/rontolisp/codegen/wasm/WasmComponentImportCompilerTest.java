package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@code --component} lowering of {@code rontolisp:wit-import}: the
 * directive becomes an internal {@code %component-import} form carrying the WIT text, the
 * WASM compiler turns each bound function into a canonical-ABI marshalling defun, and the
 * component gains an instance import it {@code canon lower}s. These run without wasmtime;
 * the end-to-end run against a real {@code wasi:keyvalue} host is
 * {@code examples/wit/keyvalue}.
 */
class WasmComponentImportCompilerTest {

	private static final String KV_WIT = """
			package wasi:keyvalue@0.2.0-draft;

			interface store {
			    variant error {
			        no-such-store,
			        access-denied,
			        other(string)
			    }
			    open: func(identifier: string) -> result<bucket, error>;
			    resource bucket {
			        get: func(key: string) -> result<option<list<u8>>, error>;
			        set: func(key: string, value: list<u8>) -> result<_, error>;
			    }
			}
			""";

	private static final String GL_WIT = """
			package local:webgl@0.1.0;

			interface gl {
			    clear: func(mask: s32);
			    shader-info-log: func(shader: s32) -> string;
			}
			""";

	private static WitImportDirective.Directive directive(String iface, String pkg) {
		return new WitImportDirective.Directive("kv.wit", iface, pkg, null, WitImportDirective.FieldStyle.CAMEL);
	}

	private static List<LispVal> lower(String wit, String iface, WitExportDirective.Backend backend) {
		return WitImportDirective.lower(directive(iface, "kv"), wit, "kv.wit", backend);
	}

	// The compile path as RontoLispCli assembles it: the WIT runtime (wit.lisp, which
	// defines the %wit-result envelope unwrapper the result wrappers call) is spliced in
	// when the program references it.
	private static byte[] compileComponent(String source) {
		List<LispVal> program = am.ik.rontolisp.eval.WitLibrary.process(LispReader.readAllFromString(source));
		return new WasmLispCompiler(false, true).compile(program);
	}

	private static boolean containsAscii(byte[] bytes, String needle) {
		return new String(bytes, StandardCharsets.ISO_8859_1).contains(needle);
	}

	@Test
	void lowersToAComponentImportFormCarryingTheWitText() {
		List<LispVal> forms = lower(KV_WIT, "wasi:keyvalue/store@0.2.0-draft",
				WitExportDirective.Backend.WASM_COMPONENT);
		// (defpackage kv ...), the %component-import form, then the result wrappers.
		assertThat(forms.get(0).print()).startsWith("(defpackage kv");
		String componentImport = forms.get(1).print();
		assertThat(componentImport).startsWith("(rontolisp::%component-import \"wasi:keyvalue/store@0.2.0-draft\"");
		// The WIT text travels inside the form: the WASM compiler reads no files (the
		// browser playground has no filesystem).
		assertThat(componentImport).contains("interface store");
		// Every function of this interface returns a result, so each binds an internal
		// raw
		// name and a public wrapper that unwraps the envelope (and signals the error
		// arm).
		assertThat(componentImport).contains("(\"open\" \"kv::%open\")");
		assertThat(forms.stream().map(LispVal::print))
			.anyMatch(form -> form.startsWith("(defun kv:open (identifier) (rontolisp::%wit-result (kv::%open"));
	}

	@Test
	void bindsOnlyTheMembersTheProgramCalls() {
		// The component path skips --optimize's core tree shaker, so an unused interface
		// function is pruned at the directive instead.
		List<LispVal> forms = WitImportDirective.lower(directive("wasi:keyvalue/store@0.2.0-draft", "kv"), KV_WIT,
				"kv.wit", WitExportDirective.Backend.WASM_COMPONENT, java.util.Set.of("open", "bucket-get"));
		String componentImport = forms.get(1).print();
		assertThat(componentImport).contains("(\"open\"").contains("(\"bucket-get\"");
		assertThat(componentImport).doesNotContain("bucket-set");
	}

	@Test
	void aFlatInterfaceNeedsNoResultWrapper() {
		List<LispVal> forms = lower(GL_WIT, "local:webgl/gl", WitExportDirective.Backend.WASM_COMPONENT);
		// No function here returns a result, so each binds its public name directly.
		assertThat(forms.get(1).print()).contains("(\"clear\" \"kv:clear\")")
			.contains("(\"shader-info-log\" \"kv:shader-info-log\")");
		assertThat(forms.stream().map(LispVal::print)).noneMatch(form -> form.contains("%wit-result"));
	}

	@Test
	void componentImportsTheInterfaceAndLowersItsFunctions() {
		// The WIT text is a Lisp string literal inside the internal form (a `%`-heavy
		// source, so it is concatenated rather than String.format-ed).
		String witLiteral = "\"" + KV_WIT.replace("\n", "\\n").replace("\"", "\\\"") + "\"";
		byte[] component = compileComponent("(defpackage kv (:use cl) (:export open))\n"
				+ "(rontolisp::%component-import \"wasi:keyvalue/store@0.2.0-draft\" " + witLiteral
				+ " (\"open\" \"kv::%open\") (\"bucket-get\" \"kv::%bucket-get\"))\n"
				+ "(defun kv:open (identifier) (rontolisp::%wit-result (kv::%open identifier)))\n"
				+ "(print (kv:open \"\"))\n");
		// The component declares the interface as an instance import, and the core module
		// imports its canonical-ABI function names from it.
		assertThat(containsAscii(component, "wasi:keyvalue/store@0.2.0-draft")).isTrue();
		assertThat(containsAscii(component, "[method]bucket.get")).isTrue();
		assertThat(containsAscii(component, "open")).isTrue();
	}

	@Test
	void anImportFreeComponentIsUnchanged() {
		// The wiring emits nothing without an import, so no index shifts: the guard that
		// keeps every existing component byte-identical.
		byte[] plain = compileComponent("(print (+ 1 2))");
		assertThat(containsAscii(plain, "wasi:keyvalue")).isFalse();
	}

	@Test
	void rejectsAStreamOrFutureAtTheBoundary() {
		String streamWit = """
				package local:x@0.1.0;

				interface s {
				    read: func() -> stream<u8>;
				}
				""";
		assertThatThrownBy(() -> lower(streamWit, "local:x/s", WitExportDirective.Backend.WASM_COMPONENT))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("stream or a future");
	}

	@Test
	void rejectsARichParameterNamingTheWitLine() {
		String recordParamWit = """
				package local:x@0.1.0;

				interface s {
				    record point {
				        x: s32,
				        y: s32,
				    }
				    move-to: func(p: point);
				}
				""";
		assertThatThrownBy(() -> lower(recordParamWit, "local:x/s", WitExportDirective.Backend.WASM_COMPONENT))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("kv.wit:8")
			.hasMessageContaining("does not cross the component import boundary as a parameter yet");
	}

	@Test
	void richResultsCrossTheComponentBoundary() {
		// The lift side is recursive, so a record / variant / list / option result binds
		// where the same type would be refused as a parameter.
		String richWit = """
				package local:x@0.1.0;

				interface s {
				    record point {
				        x: s32,
				        y: s32,
				    }
				    enum color {
				        red,
				        green,
				    }
				    where-is: func(name: string) -> option<point>;
				    palette: func() -> list<color>;
				}
				""";
		List<LispVal> forms = lower(richWit, "local:x/s", WitExportDirective.Backend.WASM_COMPONENT);
		assertThat(forms.get(1).print()).contains("(\"where-is\" \"kv:where-is\")")
			.contains("(\"palette\" \"kv:palette\")");
	}

}
