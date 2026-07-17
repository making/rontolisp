package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WitEmitter}: the rendered text must stay line-identical to what
 * {@code wasm-tools component wit} prints for the same component (proven end-to-end in
 * {@code WitOracleE2eTest}; the serve templates deliberately add the {@code use} clause
 * that tool omits, so their output stays parseable). Compiler/CLI wiring is covered in
 * {@link WasmExportCompilerTest}, {@code NoGcWasmCompilerTest} and
 * {@code RontoLispCliTest}.
 */
class WitEmitterTest {

	private static WasmExportCompiler.Decl parse(String source) {
		return WasmExportCompiler.parse((LispCons) LispReader.readFromString(source));
	}

	@Test
	void rendersEveryExportLineShape() {
		String wit = WitEmitter.emit(WitEmitter.VARIANT_BASE,
				List.of(parse("(rontolisp:wasm-export 'pure-add :params '(:int :int) :returns :int)"),
						parse("(rontolisp:wasm-export 'scale :params '(:float) :returns :float)"),
						parse("(rontolisp:wasm-export 'evenp2 :params '(:int) :returns :bool)"),
						parse("(rontolisp:wasm-export 'greet :params '(:string) :returns :string)"),
						parse("(rontolisp:wasm-export 'swap :params '(:s-expr) :returns :s-expr)"),
						parse("(rontolisp:wasm-export 'noisy-mul :params '(:int :int) :returns :int :async t)"),
						parse("(rontolisp:wasm-export 'side-effect :params '(:int) :returns nil :as \"drop-it\")"),
						parse("(rontolisp:wasm-export 'now :params '() :returns :int)")));
		assertThat(wit).startsWith("package root:component;\n");
		// The user exports follow the fixed run export, in directive order, rendered
		// exactly the way wasm-tools prints them (p0/p1 parameter names, `async func`,
		// no result arrow on void).
		assertThat(wit).contains("""
				  export wasi:cli/run@0.3.0;
				  export pure-add: func(p0: s32, p1: s32) -> s32;
				  export scale: func(p0: f64) -> f64;
				  export evenp2: func(p0: s32) -> bool;
				  export greet: func(p0: string) -> string;
				  export swap: func(p0: string) -> string;
				  export noisy-mul: async func(p0: s32, p1: s32) -> s32;
				  export drop-it: func(p0: s32);
				  export now: func() -> s32;
				}
				""");
		// The world's imports and the referenced package definitions make the file
		// self-contained (parseable without introspecting the component).
		assertThat(wit).contains("  import wasi:cli/stdout@0.3.0;").contains("package wasi:cli@0.3.0 {");
	}

	@Test
	void rendersTheDeclaredParameterNames() {
		// :param-names (what rontolisp:wit-export fills in from the world it implements)
		// are the labels of the lifted function type, so the emitted world shows them --
		// which is what lets an implemented .wit round-trip through --emit-wit unchanged,
		// rather than coming back with p0/p1.
		String wit = WitEmitter.emit(WitEmitter.VARIANT_BASE, List.of(
				parse("(rontolisp:wasm-export 'count-vowels :params '(:string) :param-names '(s)" + " :returns :int)"),
				parse("(rontolisp:wasm-export 'shout :params '(:string :bool)"
						+ " :param-names '(text loud) :returns :string :async t)"),
				parse("(rontolisp:wasm-export 'measure :params '(:string) :returns :int)")));
		assertThat(wit).contains("""
				  export count-vowels: func(s: string) -> s32;
				  export shout: async func(text: string, loud: bool) -> string;
				  export measure: func(p0: string) -> s32;
				}
				""");
	}

	@Test
	void rendersLongAsS64OnTheNoGcVariant() {
		String wit = WitEmitter.emit(WitEmitter.VARIANT_NOGC,
				List.of(parse("(rontolisp:wasm-export 'big-add :params '(:long :long) :returns :long)")));
		assertThat(wit).isEqualTo("""
				package root:component;

				world root {
				  export big-add: func(p0: s64, p1: s64) -> s64;
				}
				""");
	}

	@Test
	void separatesImportsFromExportsWithOneBlankLineOnTheNoGcPrintVariant() {
		// The no-gc print template's world ends on an import line; wasm-tools prints one
		// blank line between a world's import block and its export block. Every export
		// of a printing program is an async lift, so the emitted item says `async func`
		// even though the directive itself never carries :async.
		String wit = WitEmitter.emit(WitEmitter.VARIANT_NOGC_PRINT, List.of(parse("(rontolisp:wasm-export 'hello)")));
		assertThat(wit).contains("""
				  import wasi:cli/stdout@0.3.0;

				  export hello: async func();
				}
				""");
		assertThat(wit).contains("package wasi:cli@0.3.0 {");
	}

	@Test
	void serveVariantCarriesTheFixedHandlerExportAndTheUseClause() {
		String wit = WitEmitter.emit(WitEmitter.VARIANT_HTTP_SERVER, List.of());
		assertThat(wit).contains("  export wasi:http/handler@0.3.0;");
		// wasm-tools component wit omits this use clause and prints an unparseable
		// interface; the template deliberately restores it (the upstream
		// wasi:http worlds.wit has it) so the emitted file stays consumable.
		assertThat(wit).contains("    use types.{request, response, error-code};");
		assertThat(wit).contains("    handle: async func(request: request) -> result<response, error-code>;");
		assertThat(wit).doesNotContain("func(p0");
	}

	@Test
	void aServeWorldCarriesTheUserImportsToo() {
		// A served component's imports are no longer only the fixed wasi:http surface:
		// a rontolisp:wit-import joins the world's import block (before the fixed
		// handler export), and its package definition is appended, pruned to the
		// members the program binds.
		String wit = WitEmitter.emit(WitEmitter.VARIANT_HTTP_SERVER, List.of(), List.of(keyvalueImport()));
		assertThat(wit).contains("""
				  import wasi:cli/stderr@0.3.0;
				  import wasi:keyvalue/store@0.2.0-draft;

				  export wasi:http/handler@0.3.0;
				""");
		assertThat(wit).contains("package wasi:keyvalue@0.2.0-draft {")
			.contains("    open: func(identifier: string) -> result<bucket, error>;");
	}

	// The keyvalue store interface as the --component lowering hands it to the emitter:
	// the directive's %component-import form, parsed back into an Import.
	private static WasmComponentImportCompiler.Import keyvalueImport() {
		String wit = """
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
				    }
				}
				""";
		List<LispVal> forms = am.ik.rontolisp.compiler.WitImportDirective.lower(
				new am.ik.rontolisp.compiler.WitImportDirective.Directive("kv.wit", "wasi:keyvalue/store@0.2.0-draft",
						"kv", null, am.ik.rontolisp.compiler.WitImportDirective.FieldStyle.CAMEL),
				wit, "kv.wit", am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT);
		return WasmComponentImportCompiler.parse((LispCons) forms.get(1));
	}

	@Test
	void serveVariantPrintsTheAsyncBodyTypes() {
		// The 0.3 http surface is async end-to-end: client.send is an async func and
		// both body directions carry stream<u8> contents plus a trailers future. Pin
		// the emitted text (the printer's canonical rendering of those types), not
		// just the fixture bytes.
		String wit = WitEmitter.emit(WitEmitter.VARIANT_HTTP_SERVER, List.of());
		assertThat(wit).contains("    send: async func(request: request) -> result<response, error-code>;");
		assertThat(wit).contains("contents: option<stream<u8>>");
		assertThat(wit).contains("future<result<option<trailers>, error-code>>");
	}

	@Test
	void everyVariantTemplateLoadsAndOpensTheRootWorld() {
		for (String variant : new String[] { WitEmitter.VARIANT_BASE, WitEmitter.VARIANT_HTTP_SERVER,
				WitEmitter.VARIANT_NOGC, WitEmitter.VARIANT_NOGC_PRINT }) {
			String wit = WitEmitter.emit(variant, List.of());
			assertThat(wit).as(variant).startsWith("package root:component;\n\nworld root {\n");
		}
	}

	@Test
	void variantImportSurfacesMatchTheirBlobSets() {
		assertThat(WitEmitter.emit(WitEmitter.VARIANT_BASE, List.of())).doesNotContain("wasi:http")
			.doesNotContain("wasi:sockets");
		assertThat(WitEmitter.emit(WitEmitter.VARIANT_NOGC, List.of())).doesNotContain("import");
	}

	@Test
	void unknownVariantIsAClearError() {
		assertThatThrownBy(() -> WitEmitter.emit("nope", List.of())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Missing WIT definition for variant");
	}

}
