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
		// blank line between a world's import block and its export block.
		String wit = WitEmitter.emit(WitEmitter.VARIANT_NOGC_PRINT, List.of(parse("(rontolisp:wasm-export 'hello)")));
		assertThat(wit).contains("""
				  import wasi:cli/stdout@0.2.0;

				  export hello: func();
				}
				""");
		assertThat(wit).contains("package wasi:io@0.2.0 {");
	}

	@Test
	void serveVariantsCarryTheFixedHandlerExportAndTheUseClause() {
		for (String variant : new String[] { WitEmitter.VARIANT_HTTP_SERVER, WitEmitter.VARIANT_HTTP_SERVER_CLIENT }) {
			String wit = WitEmitter.emit(variant, List.of());
			assertThat(wit).as(variant).contains("  export wasi:http/incoming-handler@0.2.0;");
			// wasm-tools component wit omits this use clause and prints an unparseable
			// interface; the template deliberately restores it (the upstream
			// wasi:http/handler.wit has it) so the emitted file stays consumable.
			assertThat(wit).as(variant).contains("    use types.{incoming-request, response-outparam};");
			assertThat(wit).as(variant).doesNotContain("func(p0");
		}
	}

	@Test
	void everyVariantTemplateLoadsAndOpensTheRootWorld() {
		for (String variant : new String[] { WitEmitter.VARIANT_BASE, WitEmitter.VARIANT_HTTP_CLIENT,
				WitEmitter.VARIANT_SOCKETS, WitEmitter.VARIANT_HTTP_SERVER, WitEmitter.VARIANT_HTTP_SERVER_CLIENT,
				WitEmitter.VARIANT_NOGC, WitEmitter.VARIANT_NOGC_PRINT }) {
			String wit = WitEmitter.emit(variant, List.of());
			assertThat(wit).as(variant).startsWith("package root:component;\n\nworld root {\n");
		}
	}

	@Test
	void variantImportSurfacesMatchTheirBlobSets() {
		assertThat(WitEmitter.emit(WitEmitter.VARIANT_BASE, List.of())).doesNotContain("wasi:http")
			.doesNotContain("wasi:sockets");
		assertThat(WitEmitter.emit(WitEmitter.VARIANT_HTTP_CLIENT, List.of()))
			.contains("  import wasi:http/outgoing-handler@0.2.0;");
		assertThat(WitEmitter.emit(WitEmitter.VARIANT_SOCKETS, List.of()))
			.contains("  import wasi:sockets/types@0.3.0;");
		assertThat(WitEmitter.emit(WitEmitter.VARIANT_NOGC, List.of())).doesNotContain("import");
	}

	@Test
	void unknownVariantIsAClearError() {
		assertThatThrownBy(() -> WitEmitter.emit("nope", List.of())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Missing WIT definition for variant");
	}

}
