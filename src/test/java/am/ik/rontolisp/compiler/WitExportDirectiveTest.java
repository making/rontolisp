package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective.Backend;
import am.ik.rontolisp.compiler.WitExportDirective.Defuns;
import am.ik.rontolisp.compiler.WitExportDirective.Directive;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WitExportDirective}: the
 * {@code (rontolisp:wit-export "world.wit")} contract check and its lowering into the
 * {@code rontolisp:wasm-export} directives a hand-written program would carry.
 *
 * <p>
 * Every drift between the world and the program must be a compile error that names the
 * WIT <strong>file and line</strong> -- that is the whole point of the directive (without
 * it the same mismatch only surfaces as a {@code wasmtime --invoke} failure), so each
 * error leg pins the location too. The byte-identity of the lowered program is pinned in
 * {@code WitExportInlinerTest}.
 */
class WitExportDirectiveTest {

	private static final String WIT = "world.wit";

	private static LispCons form(String source) {
		return (LispCons) LispReader.readFromString(source);
	}

	private static Defuns defuns(Map<String, List<String>> lambdaLists) {
		return lambdaLists::get;
	}

	/**
	 * Lowers the world against a program defining {@code count-vowels} of one argument.
	 */
	private static List<LispVal> lower(String wit, Backend backend) {
		return lower(wit, backend, Map.of("count-vowels", List.of("s")));
	}

	private static List<LispVal> lower(String wit, Backend backend, Map<String, List<String>> program) {
		return WitExportDirective.lower(new Directive(WIT, null), wit, WIT, defuns(program), backend);
	}

	private static String printed(List<LispVal> forms) {
		return String.join("\n", forms.stream().map(LispVal::print).toList());
	}

	// A world exporting `count-vowels: func(s: string) -> s32`, the count-vowels
	// example's
	// contract.
	private static String world(String body) {
		return "package root:component;\n\nworld analyzer {\n" + body + "\n}\n";
	}

	@Test
	void aKeywordCollidingNameIsLoweredToItsBareLabel() {
		// %type / %flags / %stream are legal WIT (all three occur in the WASI WIT
		// itself):
		// the % is source escaping, and the component-model label is the bare word. A
		// world using one must lower to a valid :param-names entry, not to '%type'.
		List<LispVal> forms = lower(world("  export count-vowels: func(%type: string) -> s32;"), Backend.WASM_GC);
		assertThat(printed(forms)).isEqualTo(
				"(RONTOLISP:WASM-EXPORT (QUOTE count-vowels) :PARAMS (QUOTE (:STRING)) :PARAM-NAMES (QUOTE (type)) :RETURNS :S32)");
	}

	@Test
	void isDirectiveRecognizesTheQualifiedForm() {
		assertThat(WitExportDirective.isDirective(form("(rontolisp:wit-export \"w.wit\")"))).isTrue();
		assertThat(WitExportDirective.isDirective(form("(rontolisp:wasm-export 'f)"))).isFalse();
		assertThat(WitExportDirective.isDirective(form("(defun f (x) x)"))).isFalse();
	}

	@Test
	void parsesThePathAlone() {
		assertThat(WitExportDirective.parse(form("(rontolisp:wit-export \"wit/analyzer.wit\")")))
			.isEqualTo(new Directive("wit/analyzer.wit", null));
	}

	@Test
	void parsesTheWorldOptionAsASymbol() {
		assertThat(WitExportDirective.parse(form("(rontolisp:wit-export \"w.wit\" :world analyzer)")))
			.isEqualTo(new Directive("w.wit", "analyzer"));
	}

	@Test
	void parsesTheWorldOptionAsAString() {
		assertThat(WitExportDirective.parse(form("(rontolisp:wit-export \"w.wit\" :world \"analyzer\")")))
			.isEqualTo(new Directive("w.wit", "analyzer"));
	}

	@Test
	void rejectsANonStringPath() {
		assertThatThrownBy(() -> WitExportDirective.parse(form("(rontolisp:wit-export analyzer)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("expects a WIT file path string");
		assertThatThrownBy(() -> WitExportDirective.parse(form("(rontolisp:wit-export)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("expects a WIT file path string");
	}

	@Test
	void rejectsAnUnknownOption() {
		assertThatThrownBy(() -> WitExportDirective.parse(form("(rontolisp:wit-export \"w.wit\" :package foo)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Unknown rontolisp:wit-export option :PACKAGE");
	}

	@Test
	void rejectsANonKeywordOptionAndAMissingValue() {
		assertThatThrownBy(() -> WitExportDirective.parse(form("(rontolisp:wit-export \"w.wit\" analyzer)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Expected a keyword option");
		assertThatThrownBy(() -> WitExportDirective.parse(form("(rontolisp:wit-export \"w.wit\" :world)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Missing value for :WORLD");
	}

	@Test
	void rejectsAKeywordAsTheWorldName() {
		assertThatThrownBy(() -> WitExportDirective.parse(form("(rontolisp:wit-export \"w.wit\" :world :analyzer)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining(":world expects a world name");
	}

	@Test
	void lowersAnExportIntoTheEquivalentWasmExportDirective() {
		// The synthesized form is exactly what a hand-written program carries --
		// including
		// the WIT's own parameter names, which is why an implemented world round-trips
		// through --emit-wit with its parameter names intact.
		List<LispVal> forms = lower(world("  export count-vowels: func(s: string) -> s32;"), Backend.WASM_GC);
		assertThat(printed(forms)).isEqualTo(
				"(RONTOLISP:WASM-EXPORT (QUOTE count-vowels) :PARAMS (QUOTE (:STRING)) :PARAM-NAMES (QUOTE (s)) :RETURNS :S32)");
	}

	@Test
	void lowersEveryBoundaryTypeAndTheVoidResult() {
		List<LispVal> forms = lower("""
				package root:component;

				world analyzer {
				  export mix: func(a: s32, b: f64, c: bool, d: string) -> bool;
				  export ping: func();
				}
				""", Backend.WASM_GC, Map.of("mix", List.of("a", "b", "c", "d"), "ping", List.of()));
		assertThat(printed(forms)).isEqualTo(
				"(RONTOLISP:WASM-EXPORT (QUOTE mix) :PARAMS (QUOTE (:S32 :FLOAT :BOOL :STRING)) :PARAM-NAMES (QUOTE (a b c d)) :RETURNS :BOOL)\n(RONTOLISP:WASM-EXPORT (QUOTE ping) :PARAMS (QUOTE NIL) :PARAM-NAMES (QUOTE NIL) :RETURNS :VOID)");
	}

	@Test
	void lowersAnAsyncFuncIntoAnAsyncExport() {
		// The :async t lift is stated by the WIT rather than guessed: a sync-lifted
		// export
		// doing I/O traps at run time ("cannot block a synchronous task").
		List<LispVal> forms = lower(world("  export count-vowels: async func(s: string) -> s32;"), Backend.WASM_GC);
		assertThat(printed(forms)).isEqualTo(
				"(RONTOLISP:WASM-EXPORT (QUOTE count-vowels) :PARAMS (QUOTE (:STRING)) :PARAM-NAMES (QUOTE (s)) :RETURNS :S32 :ASYNC T)");
	}

	@Test
	void lowersS64OnlyWhereTheBackendCanCarryIt() {
		String wit = world("  export count-vowels: func(s: s64) -> s64;");
		// --no-gc's value model is unboxed i64, so s64 crosses the boundary as :S64
		// (the internal designator is upcased, matching the reader's upcase spelling
		// of any source :s64 token; the legacy :long spells the same type).
		assertThat(printed(lower(wit, Backend.WASM_NO_GC))).isEqualTo(
				"(RONTOLISP:WASM-EXPORT (QUOTE count-vowels) :PARAMS (QUOTE (:S64)) :PARAM-NAMES (QUOTE (s)) :RETURNS :S64)");
		// The interpreter/JVM check the contract but export nothing, so no backend rule
		// applies.
		assertThat(printed(lower(wit, Backend.OTHER))).contains(":S64");
		// The wasm-GC backend's integers are i31ref: it cannot represent an i64 at all.
		assertThatThrownBy(() -> lower(wit, Backend.WASM_GC)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit:4: export 'count-vowels': s64 (s) requires --no-gc "
					+ "(the wasm-GC backend's integers are i31ref)");
	}

	@Test
	void reportsAMissingDefunAgainstTheWitLine() {
		assertThatThrownBy(() -> lower(world("  export count-vowels: func(s: string) -> s32;"), Backend.WASM_GC,
				Map.of("shout", List.of("s"))))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit:4: export 'count-vowels' has no matching (defun count-vowels ...) in the program");
	}

	@Test
	void reportsAnArityMismatchAgainstTheWitLine() {
		assertThatThrownBy(() -> lower(world("  export count-vowels: func(s: string, from: s32) -> s32;"),
				Backend.WASM_GC, Map.of("count-vowels", List.of("s"))))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit:4: export 'count-vowels' declares 2 parameter(s), "
					+ "but (defun count-vowels ...) takes 1");
	}

	@Test
	void reportsALambdaListMarkerAgainstTheWitLine() {
		// The component boundary passes a fixed parameter list; an exported defun cannot
		// have &optional / &rest / &key parameters.
		for (String marker : new String[] { "&optional", "&rest", "&key" }) {
			assertThatThrownBy(() -> lower(world("  export count-vowels: func(s: string) -> s32;"), Backend.WASM_GC,
					Map.of("count-vowels", List.of("s", marker, "extra"))))
				.as(marker)
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessage("world.wit:4: export 'count-vowels' maps to (defun count-vowels (" + marker + " ...)): "
						+ "an exported function takes required parameters only");
		}
	}

	@Test
	void reportsAWitTypeOutsideTheBoundarySubsetAgainstTheWitLine() {
		// The representation of every WIT type is settled (WitTypeMapper), but only the
		// ones with a component-model scalar/string lift can cross the boundary today;
		// the error says what the value WOULD be rather than just refusing.
		assertThatThrownBy(() -> lower(world("  export count-vowels: func(s: list<u8>) -> s32;"), Backend.WASM_GC))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("world.wit:4: export 'count-vowels': the WIT type of s is not supported at the "
					+ "export boundary yet (supported: s8, s16, s32, s64, u8, u16, u32, u64, f64, bool, string)")
			// The settled house representation is named, so the error says what the value
			// WOULD be -- but it must not cite a .todo file, which is deleted the moment
			// the work lands.
			.hasMessageContaining("BYTE_STRING")
			.hasMessageNotContaining(".todo");
		// The result is checked the same way, and named as such. f32 stays out: rontolisp
		// has no internal single-precision float (.kb/wasm-export-no-wasi.md).
		assertThatThrownBy(() -> lower(world("  export count-vowels: func(s: string) -> f32;"), Backend.WASM_GC))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("world.wit:4:")
			.hasMessageContaining("the WIT type of the result is not supported");
	}

	@Test
	void lowersTheWholeFixedWidthIntegerFamily() {
		// The export boundary's accepted set is the import side's: every WIT fixed-width
		// integer, spelled the way WIT spells it. The canonical tutorial world's u32 is
		// the case that made this necessary.
		List<LispVal> forms = lower("""
				package root:component;

				world numbers {
				  export narrow: func(a: s8, b: s16, c: u8, d: u16) -> u8;
				  export wide: func(a: s32, b: u32) -> u32;
				}
				""", Backend.WASM_GC, Map.of("narrow", List.of("a", "b", "c", "d"), "wide", List.of("a", "b")));
		assertThat(printed(forms)).isEqualTo(
				"(RONTOLISP:WASM-EXPORT (QUOTE narrow) :PARAMS (QUOTE (:S8 :S16 :U8 :U16)) :PARAM-NAMES (QUOTE (a b c d)) :RETURNS :U8)\n"
						+ "(RONTOLISP:WASM-EXPORT (QUOTE wide) :PARAMS (QUOTE (:S32 :U32)) :PARAM-NAMES (QUOTE (a b)) :RETURNS :U32)");
	}

	@Test
	void lowersTheTutorialAdderWorldVerbatim() {
		// The component-model tutorial world every newcomer starts from, unedited: it is
		// the reason the export boundary had to learn the unsigned types.
		List<LispVal> forms = lower("""
				package docs:adder@0.1.0;

				interface add {
				  add: func(x: u32, y: u32) -> u32;
				}

				world adder {
				  export add;
				}
				""", Backend.WASM_COMPONENT, Map.of("add", List.of("x", "y")));
		assertThat(printed(forms)).isEqualTo("(RONTOLISP:WASM-EXPORT (QUOTE add) :PARAMS (QUOTE (:U32 :U32)) "
				+ ":PARAM-NAMES (QUOTE (x y)) :RETURNS :U32 :INTERFACE \"docs:adder/add@0.1.0\")");
	}

	@Test
	void lowersU64OnlyWhereTheBackendCanCarryIt() {
		// u64 rides the same i64 core type as s64, so it inherits the same rule: the
		// wasm-GC backends carry integers as i31ref, widening to a float that is exact
		// only below 2^53, so a 64-bit boundary type is refused there.
		String wit = world("  export count-vowels: func(s: u64) -> u64;");
		assertThat(printed(lower(wit, Backend.WASM_NO_GC))).isEqualTo(
				"(RONTOLISP:WASM-EXPORT (QUOTE count-vowels) :PARAMS (QUOTE (:U64)) :PARAM-NAMES (QUOTE (s)) :RETURNS :U64)");
		assertThatThrownBy(() -> lower(wit, Backend.WASM_GC)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit:4: export 'count-vowels': u64 (s) requires --no-gc "
					+ "(the wasm-GC backend's integers are i31ref)");
	}

	@Test
	void rejectsAnAsyncFuncOnTheNoGcBackend() {
		assertThatThrownBy(
				() -> lower(world("  export count-vowels: async func(s: string) -> s32;"), Backend.WASM_NO_GC))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit:4: export 'count-vowels' is an async func, which --no-gc --component cannot lift "
					+ "(the adapter-free reactor has no async machinery)");
	}

	@Test
	void rejectsTheReservedRunExportName() {
		assertThatThrownBy(() -> lower(world("  export run: func(s: string) -> s32;"), Backend.WASM_GC,
				Map.of("run", List.of("s"))))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit:4: export 'run' collides with the component's wasi:cli/run entry point; "
					+ "rename it in the world");
	}

	@Test
	void rejectsAnExportOfAnInterfaceTheFileDoesNotDefine() {
		// An interface this file does not define (a bare wasi:* reference) has no
		// functions
		// to check; a program's wasi:http/incoming-handler export comes from
		// rontolisp:http-handler, not from a world implemented function by function.
		assertThatThrownBy(() -> lower(world("  export wasi:http/incoming-handler@0.2.0;"), Backend.WASM_GC))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith(
					"world.wit:4: export 'wasi:http/incoming-handler@0.2.0' names an interface this file does not define")
			.hasMessageContaining("rontolisp:http-handler");
	}

	@Test
	void lowersAnExportOfASameFileInterfaceIntoInstanceGroupedFunctions() {
		// `export add;` names an interface DEFINED IN THIS FILE: each of its functions is
		// checked against the program and lowered into a wasm-export carrying the
		// interface's fully-qualified id, so the backend bundles them into one exported
		// component instance (`export docs:adder/add@0.1.0`).
		String wit = """
				package docs:adder@0.1.0;

				interface add {
				  add: func(x: s32, y: s32) -> s32;
				}

				world adder {
				  export add;
				}
				""";
		List<LispVal> forms = WitExportDirective.lower(new Directive(WIT, "adder"), wit, WIT,
				defuns(Map.of("add", List.of("x", "y"))), Backend.WASM_COMPONENT);
		assertThat(printed(forms)).isEqualTo("(RONTOLISP:WASM-EXPORT (QUOTE add) :PARAMS (QUOTE (:S32 :S32)) "
				+ ":PARAM-NAMES (QUOTE (x y)) :RETURNS :S32 :INTERFACE \"docs:adder/add@0.1.0\")");
	}

	@Test
	void lowersAnInlineInterfaceExportUnderItsPlainName() {
		// `export calc: interface { ... }` bundles the inline interface's functions under
		// the plain export name.
		List<LispVal> forms = lower(world("""
				export calc: interface {
				  add: func(x: s32, y: s32) -> s32;
				  negate: func(x: s32) -> s32;
				}"""), Backend.WASM_COMPONENT, Map.of("add", List.of("x", "y"), "negate", List.of("x")));
		assertThat(printed(forms)).isEqualTo("(RONTOLISP:WASM-EXPORT (QUOTE add) :PARAMS (QUOTE (:S32 :S32)) "
				+ ":PARAM-NAMES (QUOTE (x y)) :RETURNS :S32 :INTERFACE \"calc\")\n"
				+ "(RONTOLISP:WASM-EXPORT (QUOTE negate) :PARAMS (QUOTE (:S32)) :PARAM-NAMES (QUOTE (x)) "
				+ ":RETURNS :S32 :INTERFACE \"calc\")");
	}

	@Test
	void checksArityOfASameFileInterfaceMember() {
		// The contract check reaches interface members too: a defun of the wrong arity is
		// a
		// compile error naming the WIT line, exactly as for a freestanding export.
		String wit = """
				package docs:adder@0.1.0;

				interface add {
				  add: func(x: s32, y: s32) -> s32;
				}

				world adder {
				  export add;
				}
				""";
		assertThatThrownBy(() -> WitExportDirective.lower(new Directive(WIT, "adder"), wit, WIT,
				defuns(Map.of("add", List.of("x"))), Backend.WASM_COMPONENT))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit:8: export 'add' declares 2 parameter(s), but (defun add ...) takes 1");
	}

	@Test
	void rejectsAnInlineFunctionImport() {
		// A world's interface imports are the fixed WASI surface (ignored), but an inline
		// function import is a binding request the export path cannot honor -- and must
		// not silently drop.
		assertThatThrownBy(() -> lower("""
				package root:component;

				world analyzer {
				  import log: func(message: string);
				  export count-vowels: func(s: string) -> s32;
				}
				""", Backend.WASM_GC)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("world.wit:4: import 'log':")
			.hasMessageContaining("rontolisp:wit-import")
			.hasMessageContaining("rontolisp:wasm-import");
	}

	@Test
	void ignoresInterfaceImportsAndTypeDefinitions() {
		// The WASI import surface is fixed per component variant, and a type definition
		// only describes a signature: neither is part of the export contract.
		List<LispVal> forms = lower("""
				package root:component;

				world analyzer {
				  import wasi:cli/stdout@0.3.0;
				  use wasi:clocks/monotonic-clock@0.3.0.{duration};
				  export count-vowels: func(s: string) -> s32;
				}
				""", Backend.WASM_GC);
		assertThat(forms).hasSize(1);
		assertThat(printed(forms)).contains("(QUOTE count-vowels)");
	}

	@Test
	void rejectsADuplicateExport() {
		assertThatThrownBy(() -> lower("""
				package root:component;

				world analyzer {
				  export count-vowels: func(s: string) -> s32;
				  export count-vowels: func(s: string) -> s32;
				}
				""", Backend.WASM_GC)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit:5: duplicate export 'count-vowels'");
	}

	@Test
	void rejectsAWorldWithNoExports() {
		assertThatThrownBy(() -> lower("""
				package root:component;

				world analyzer {
				  import wasi:cli/stdout@0.3.0;
				}
				""", Backend.WASM_GC)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit:3: world 'analyzer' declares no exports");
	}

	@Test
	void selectsTheNamedWorld() {
		String wit = """
				package root:component;

				world other {
				  export shout: func(s: string) -> string;
				}

				world analyzer {
				  export count-vowels: func(s: string) -> s32;
				}
				""";
		List<LispVal> forms = WitExportDirective.lower(new Directive(WIT, "analyzer"), wit, WIT,
				defuns(Map.of("count-vowels", List.of("s"))), Backend.WASM_GC);
		assertThat(printed(forms)).contains("(QUOTE count-vowels)").doesNotContain("shout");
	}

	@Test
	void rejectsAWorldNameTheFileDoesNotHave() {
		assertThatThrownBy(() -> WitExportDirective.lower(new Directive(WIT, "nope"),
				world("  export count-vowels: func(s: string) -> s32;"), WIT,
				defuns(Map.of("count-vowels", List.of("s"))), Backend.WASM_GC))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit: no world named 'nope' (found: analyzer)");
	}

	@Test
	void rejectsAnAmbiguousFileWithoutTheWorldOption() {
		assertThatThrownBy(() -> lower("""
				package root:component;

				world first {
				  export count-vowels: func(s: string) -> s32;
				}

				world second {
				  export count-vowels: func(s: string) -> s32;
				}
				""", Backend.WASM_GC)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit: the file declares 2 worlds (first, second); name one with :world");
	}

	@Test
	void rejectsAFileWithNoWorld() {
		assertThatThrownBy(() -> lower("""
				package root:component;

				interface analyzer {
				  count-vowels: func(s: string) -> s32;
				}
				""", Backend.WASM_GC)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit: the file declares no world");
	}

	@Test
	void reportsAWitSyntaxErrorAgainstTheFile() {
		assertThatThrownBy(
				() -> lower("package root:component;\n\nworld analyzer {\n  export oops:\n}\n", Backend.WASM_GC))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("world.wit: ")
			.hasMessageContaining("at line ")
			.hasCauseInstanceOf(am.ik.wit.WitParseException.class);
	}

	@Test
	void findsAWorldInsideAPackageBlock() {
		List<LispVal> forms = lower("""
				package root:component {
				  world analyzer {
				    export count-vowels: func(s: string) -> s32;
				  }
				}
				""", Backend.WASM_GC);
		assertThat(printed(forms)).contains("(QUOTE count-vowels)");
	}

}
