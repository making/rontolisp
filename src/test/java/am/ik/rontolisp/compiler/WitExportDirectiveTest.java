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
				"(RONTOLISP:WASM-EXPORT '|count-vowels| :PARAMS '(:STRING) :PARAM-NAMES '(|type|) :RETURNS :S32)");
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
		// and the program a reactor
		assertThat(WitExportDirective.parse(form("(rontolisp:wit-export \"w.wit\" :world \"analyzer\" :reactor t)")))
			.isEqualTo(new Directive("w.wit", "analyzer", null, true));
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
		assertThatThrownBy(() -> WitExportDirective.parse(form("(rontolisp:wit-export \"w.wit\" :reactor 1)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("rontolisp:wit-export :reactor expects t or nil");
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
		assertThat(printed(forms))
			.isEqualTo("(RONTOLISP:WASM-EXPORT '|count-vowels| :PARAMS '(:STRING) :PARAM-NAMES '(|s|) :RETURNS :S32)");
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
				"(RONTOLISP:WASM-EXPORT '|mix| :PARAMS '(:S32 :FLOAT :BOOL :STRING) :PARAM-NAMES '(|a| |b| |c| |d|) :RETURNS :BOOL)\n(RONTOLISP:WASM-EXPORT '|ping| :PARAMS 'NIL :PARAM-NAMES 'NIL :RETURNS :VOID)");
	}

	@Test
	void lowersAnAsyncFuncIntoAnAsyncExport() {
		// The :async t lift is stated by the WIT rather than guessed: a sync-lifted
		// export
		// doing I/O traps at run time ("cannot block a synchronous task").
		List<LispVal> forms = lower(world("  export count-vowels: async func(s: string) -> s32;"), Backend.WASM_GC);
		assertThat(printed(forms)).isEqualTo(
				"(RONTOLISP:WASM-EXPORT '|count-vowels| :PARAMS '(:STRING) :PARAM-NAMES '(|s|) :RETURNS :S32 :ASYNC T)");
	}

	@Test
	void lowersS64OnlyWhereTheBackendCanCarryIt() {
		String wit = world("  export count-vowels: func(s: s64) -> s64;");
		// --no-gc's value model is unboxed i64, so s64 crosses the boundary as :S64
		// (the internal designator is upcased, matching the reader's upcase spelling
		// of any source :s64 token; the legacy :long spells the same type).
		assertThat(printed(lower(wit, Backend.WASM_NO_GC)))
			.isEqualTo("(RONTOLISP:WASM-EXPORT '|count-vowels| :PARAMS '(:S64) :PARAM-NAMES '(|s|) :RETURNS :S64)");
		// The interpreter/JVM check the contract but export nothing, so no backend rule
		// applies.
		assertThat(printed(lower(wit, Backend.OTHER))).contains(":S64");
		// The wasm-GC backends carry it too: the boxed exact-integer representation
		// holds the full signed 64-bit range.
		assertThat(printed(lower(wit, Backend.WASM_GC))).contains(":S64");
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
		for (String marker : new String[] { "&OPTIONAL", "&REST", "&KEY" }) {
			assertThatThrownBy(() -> lower(world("  export count-vowels: func(s: string) -> s32;"), Backend.WASM_GC,
					Map.of("count-vowels", List.of("s", marker, "extra"))))
				.as(marker)
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessage("world.wit:4: export 'count-vowels' maps to (defun count-vowels (" + marker + " ...)): "
						+ "an exported function takes required parameters only");
		}
	}

	@Test
	void lowersATypeBeyondTheFlatSetIntoTheCanonicalFormOrRefusesItAgainstTheWitLine() {
		// An export whose types the flat designators do not cover is the WIT's own
		// function: the form names its place -- the world, the label, the line -- and
		// carries the text, and the backend marshals it through the canonical ABI. A
		// type alias of a flat type stays flat, the hand-written form with the WIT
		// beside it: a component of a world that names types declares them, and types
		// each export from the WIT.
		String wit = """
				package root:component;

				world analyzer {
				  type count = s32;
				  record word { text: string, weight: f32 }
				  flags perms { r, w }
				  export count-vowels: func(s: list<u8>) -> count;
				  export longest: func(ws: list<word>) -> result<word, string>;
				  export size: func(s: string) -> count;
				}
				""";
		Map<String, List<String>> program = Map.of("count-vowels", List.of("s"), "longest", List.of("ws"), "size",
				List.of("s"));
		List<LispVal> forms = WitExportDirective.lower(new Directive(WIT, null, null, false, true, false), wit, WIT,
				defuns(program), Backend.WASM_COMPONENT);
		assertThat(forms.stream().map(form -> form.print().replaceAll(":WIT \"[^\"]*\"", ":WIT \"...\"")))
			.containsExactly(
					"(RONTOLISP:WASM-EXPORT '|count-vowels| :AS \"count-vowels\" :WIT \"...\" :WIT-WORLD \"analyzer\""
							+ " :WIT-AT \"world.wit:7\" :OCTETS T :PARAM-NAMES '(|s|))",
					// a result at the top is the envelope a defun around the function
					// makes: the ok arm alone here, where nothing can signal the error
					// arm
					"(DEFUN |longest%wit-export| (P0) (CONS :OK (|longest| P0)))",
					"(RONTOLISP:WASM-EXPORT '|longest%wit-export| :AS \"longest\" :WIT \"...\" :WIT-WORLD \"analyzer\""
							+ " :WIT-AT \"world.wit:8\" :OCTETS T :PARAM-NAMES '(|ws|))",
					"(RONTOLISP:WASM-EXPORT '|size| :PARAMS '(:STRING) :WIT \"...\" :WIT-WORLD \"analyzer\""
							+ " :WIT-AT \"world.wit:9\" :PARAM-NAMES '(|s|) :RETURNS :S32)");
		// The scalar backend's component declares the types too. A core module has
		// none to declare, and the interpreter and the JVM compile no export: the
		// hand-written form alone.
		String aliased = """
				package root:component;

				interface ops {
				  type id = u32;
				  next: func(x: id) -> id;
				}

				world analyzer {
				  export ops;
				}
				""";
		for (Backend backend : Backend.values()) {
			boolean component = backend == Backend.WASM_COMPONENT || backend == Backend.WASM_NO_GC_COMPONENT;
			assertThat(printed(lower(aliased, backend, Map.of("next", List.of("x")))).replaceAll(":WIT \"[^\"]*\"",
					":WIT \"...\""))
				.as(backend.name())
				.isEqualTo("(RONTOLISP:WASM-EXPORT '|next| :PARAMS '(:U32) "
						+ (component ? ":WIT \"...\" :WIT-WORLD \"analyzer\" :WIT-AT \"world.wit:9\" " : "")
						+ ":PARAM-NAMES '(|x|) :RETURNS :U32 :INTERFACE \"root:component/ops\")");
		}
		// The text is the whole document, which the backend reads no file for.
		assertThat(forms.get(0).print()).contains("record word {").contains("world analyzer {");
		// A program holding the WIT run time may signal rontolisp:wit-error: the error
		// arm. The interpreter and the JVM compile no export, so no defun is made.
		Map<String, List<String>> signalling = new java.util.HashMap<>(program);
		signalling.put("RONTOLISP:WIT-PROVIDE", List.of("INTERFACE", "PROVIDER"));
		assertThat(printed(lower(wit, Backend.WASM_COMPONENT, signalling))).contains(
				"(DEFUN |longest%wit-export| (P0) (HANDLER-CASE (CONS :OK (|longest| P0)) (RONTOLISP:WIT-ERROR (E)"
						+ " (CONS :ERROR (RONTOLISP:WIT-ERROR-PAYLOAD E)))))");
		assertThat(printed(lower(wit, Backend.OTHER, program))).doesNotContain("DEFUN");
		// Under :envelope t the function answers the envelope itself (a front end's
		// wrapper makes it), so the export names the function.
		Directive envelope = WitExportDirective.parse(form("(rontolisp:wit-export \"world.wit\" :envelope t)"));
		assertThat(envelope.envelope()).isTrue();
		assertThat(printed(WitExportDirective.lower(envelope, wit, WIT, defuns(signalling), Backend.WASM_COMPONENT)))
			.doesNotContain("DEFUN")
			.contains("(RONTOLISP:WASM-EXPORT '|longest| :AS \"longest\"");
		// The scalar backend has no value a list could be, and says so at the line.
		assertThatThrownBy(() -> lower(wit, Backend.WASM_NO_GC_COMPONENT, program))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith(
					"world.wit:7: export 'count-vowels': the WIT type of s is not one --no-gc can carry "
							+ "(s8, s16, s32, s64, u8, u16, u32, u64, f64, bool, string)")
			.hasMessageContaining("BYTE_STRING")
			// a message cites no .todo file, which is deleted the moment the work lands
			.hasMessageNotContaining(".todo");
		// What no export carries is named as it nests, and the result as such. A type
		// of another interface is carried as the scope's own (a value type is
		// structural), all but what holds a resource, which is nominal.
		String clock = """
				package root:component;

				interface clock {
				  type duration = u64;
				  record span { from: instant, to: instant }
				  record instant { seconds: duration }
				  resource timer;
				  record alarm { at: instant, by: borrow<timer> }
				}

				interface timers {
				  use clock.{span};
				  longer: func(a: span, b: span) -> span;
				}

				world analyzer {
				  use clock.{%s};
				  flags perms { r, w }
				  export count-vowels: %s;
				}
				""";
		for (var refusal : Map
			.of("func() -> list<stream<u8>>", "the WIT type of the result holds a stream", "func(d: option<alarm>)",
					"the WIT type of d holds a handle of the resource 'timer'")
			.entrySet()) {
			assertThatThrownBy(() -> lower(clock.formatted("duration, alarm", refusal.getKey()), Backend.WASM_COMPONENT,
					Map.of("count-vowels", refusal.getKey().contains("()") ? List.of() : List.of("p"))))
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessageStartingWith("world.wit:19: export 'count-vowels': " + refusal.getValue())
				.hasMessageContaining("which an export does not carry");
		}
		for (String carried : List.of("func(d: option<instant>) -> duration", "func(p: perms) -> perms")) {
			assertThat(printed(lower(clock.formatted("duration, instant", carried), Backend.WASM_COMPONENT,
					Map.of("count-vowels", List.of("d")))))
				.startsWith("(RONTOLISP:WASM-EXPORT '|count-vowels| :AS \"count-vowels\" :WIT ");
		}
		// A world's types are imports of the component: a record a used type holds is
		// one the clause names, where an interface (whose types are its exports) brings
		// it under its own name.
		assertThatThrownBy(() -> lower(clock.formatted("span", "func(d: span) -> u64"), Backend.WASM_COMPONENT,
				Map.of("count-vowels", List.of("d"))))
			.hasMessage("world.wit:17: use clock brings the type 'instant', which the world does not name (a world's "
					+ "types are imports of the component); name it in the clause ('instant')");
		assertThat(printed(lower(
				clock.formatted("duration", "func(d: duration) -> u64")
					.replace("export count-vowels", "export timers;\n  export count-vowels"),
				Backend.WASM_COMPONENT, Map.of("count-vowels", List.of("d"), "longer", List.of("a", "b")))))
			.contains("(RONTOLISP:WASM-EXPORT '|longer| :AS \"longer\" :WIT ");
		// A resource of an exported interface is the component's own: its functions are
		// exports under the canonical ABI's names, each implemented by the function its
		// member name spells (as an import of the interface binds it), a method taking
		// the value its handle stands for first. The destructor is a core export the
		// resource type names: the slot store's own, or a defun calling the program's
		// <resource>-drop where there is one. A handle of it crosses anywhere a value
		// does.
		String counters = """
				package root:component;

				interface counters {
				  resource counter {
				    constructor(start: u32);
				    add: func(n: u32) -> u32;
				    merge: static func(a: counter, b: borrow<counter>) -> counter;
				    count: static func() -> u32;
				  }
				  resource token;
				  record pair { a: counter, b: token }
				  split: func(p: pair) -> list<counter>;
				%s}

				interface tags {
				  resource counter { name: func() -> string; }
				}

				world analyzer {
				  export counters;%s
				}
				""";
		Map<String, List<String>> counting = new java.util.HashMap<>(
				Map.of("counter-new", List.of("start"), "COUNTER-ADD", List.of("self", "n"), "counter-merge",
						List.of("a", "b"), "counter-count", List.of(), "split", List.of("p")));
		String ops = "\"...\" :WIT-WORLD \"analyzer\" :WIT-AT \"world.wit:20\"";
		String iface = " :INTERFACE \"root:component/counters\")";
		assertThat(lower(counters.formatted("", ""), Backend.WASM_COMPONENT, counting).stream()
			.map(form -> form.print().replaceAll(":WIT \"[^\"]*\"", ":WIT \"...\""))).containsExactly(
					"(RONTOLISP:WASM-EXPORT 'RONTOLISP::%WIT-REP-FREE :AS \"[dtor]counter\" :PARAMS '(:S32)" + iface,
					"(RONTOLISP:WASM-EXPORT '|counter-new| :AS \"[constructor]counter\" :WIT " + ops
							+ " :PARAM-NAMES '(|start|)" + iface,
					"(RONTOLISP:WASM-EXPORT 'COUNTER-ADD :AS \"[method]counter.add\" :WIT " + ops
							+ " :PARAM-NAMES '(|self| |n|)" + iface,
					"(RONTOLISP:WASM-EXPORT '|counter-merge| :AS \"[static]counter.merge\" :WIT " + ops
							+ " :PARAM-NAMES '(|a| |b|)" + iface,
					// a flat function of a resource is the flat wrapper under its name
					"(RONTOLISP:WASM-EXPORT '|counter-count| :AS \"[static]counter.count\" :PARAMS 'NIL :WIT " + ops
							+ " :PARAM-NAMES 'NIL :RETURNS :U32" + iface,
					"(RONTOLISP:WASM-EXPORT 'RONTOLISP::%WIT-REP-FREE :AS \"[dtor]token\" :PARAMS '(:S32)" + iface,
					"(RONTOLISP:WASM-EXPORT '|split| :AS \"split\" :WIT " + ops + " :PARAM-NAMES '(|p|)" + iface);
		counting.put("counter-drop", List.of("c"));
		List<LispVal> dropping = lower(counters.formatted("", ""), Backend.WASM_COMPONENT, counting);
		assertThat(printed(dropping))
			.startsWith("(DEFUN |counter%wit-dtor| (REP) (LET ((VALUE (RONTOLISP::%WIT-REP-FREE REP))) (IF (EQ VALUE"
					+ " 'RONTOLISP::%WIT-REP-TAKEN) NIL (|counter-drop| VALUE))))\n"
					+ "(RONTOLISP:WASM-EXPORT '|counter%wit-dtor| :AS \"[dtor]counter\" :PARAMS '(:S32)" + iface);
		assertThat(WitExportDirective.exportsResource(dropping)).isTrue();
		assertThat(WitExportDirective.exportsResource(forms)).isFalse();
		// The interpreter and the JVM check the functions and compile no export: no
		// destructor is made, and a handle is the value itself.
		assertThat(printed(lower(counters.formatted("", ""), Backend.OTHER, counting)))
			.doesNotContain("[dtor]", "DEFUN")
			.contains("(RONTOLISP:WASM-EXPORT 'COUNTER-ADD :AS \"[method]counter.add\" :WIT ");
		// What a program gets wrong is said against the export's line, by the function
		// that implements it.
		Map<String, List<String>> wrong = new java.util.HashMap<>(counting);
		wrong.put("COUNTER-ADD", List.of("n"));
		assertThatThrownBy(() -> lower(counters.formatted("", ""), Backend.WASM_COMPONENT, wrong)).hasMessage(
				"world.wit:20: export '[method]counter.add' declares 2 parameter(s), but (defun counter-add ...) takes 1");
		wrong.remove("COUNTER-ADD");
		assertThatThrownBy(() -> lower(counters.formatted("", ""), Backend.WASM_COMPONENT, wrong)).hasMessage(
				"world.wit:20: export '[method]counter.add' has no matching (defun counter-add ...) in the program");
		counting.put("counter-drop", List.of("c", "d"));
		assertThatThrownBy(() -> lower(counters.formatted("", ""), Backend.OTHER, counting))
			.hasMessage("world.wit:20: the destructor of the resource 'counter' maps to (defun counter-drop ...), "
					+ "which takes one required parameter, the value");
		counting.remove("counter-drop");
		for (var refusal : Map
			.of("  counter-add: func(n: u32) -> u32;\n",
					"exports '[method]counter.add' and 'counter-add' of interface 'root:component/counters' are both "
							+ "implemented by (defun counter-add ...); rename one in the WIT",
					"  peek: func() -> option<borrow<counter>>;\n",
					"export 'peek': the WIT type of the result holds a borrowed handle of the resource 'counter' in "
							+ "a result, which an export does not carry")
			.entrySet()) {
			Map<String, List<String>> peeking = new java.util.HashMap<>(counting);
			peeking.put("peek", List.of());
			assertThatThrownBy(() -> lower(counters.formatted(refusal.getKey(), ""), Backend.WASM_COMPONENT, peeking))
				.hasMessageStartingWith("world.wit:21: " + refusal.getValue());
		}
		// Only a component of the default backend defines a resource type, and a front
		// end that binds its own names has no value for a handle.
		for (Backend backend : List.of(Backend.WASM_GC, Backend.WASM_NO_GC, Backend.WASM_NO_GC_COMPONENT)) {
			assertThatThrownBy(() -> lower(counters.formatted("", ""), backend, counting)).as(backend.name())
				.hasMessageStartingWith("world.wit:20: interface 'root:component/counters' declares the resource "
						+ "'counter', which only a component of the default backend exports");
		}
		assertThatThrownBy(() -> WitExportDirective.describe(new Directive(WIT, null), counters.formatted("", ""), WIT))
			.hasMessageStartingWith("world.wit:20: interface 'root:component/counters' declares the resource "
					+ "'counter', which this language does not export");
		// Two interfaces' resources of one name are two resources: with the functions
		// named by a table, each has its own destructor.
		Map<String, String> table = new java.util.HashMap<>();
		for (String member : List.of("counter-new", "counter-add", "counter-merge", "counter-count", "split",
				"counter-drop")) {
			table.put("root:component/counters#" + member, member);
		}
		table.put("root:component/tags#counter-name", "tag-name");
		table.put("root:component/tags#counter-drop", "tag-drop");
		counting.put("COUNTER-ADD", List.of("self", "n"));
		counting.put("counter-add", List.of("self", "n"));
		counting.put("counter-drop", List.of("c"));
		counting.put("tag-name", List.of("self"));
		counting.put("tag-drop", List.of("c"));
		assertThat(printed(WitExportDirective.lower(new Directive(WIT, null, table, false),
				counters.formatted("", "\n  export tags;"), WIT, defuns(counting), Backend.WASM_COMPONENT)))
			.contains("(DEFUN |counter%wit-dtor| (REP) ", "(|counter-drop| VALUE)",
					"(DEFUN |counter%wit-dtor%1| (REP) ", "(|tag-drop| VALUE)",
					"(RONTOLISP:WASM-EXPORT '|counter%wit-dtor%1| :AS \"[dtor]counter\" :PARAMS '(:S32) :INTERFACE"
							+ " \"root:component/tags\")",
					"(RONTOLISP:WASM-EXPORT '|tag-name| :AS \"[method]counter.name\" :WIT ");
		// A resource no exported interface declares is an import of the component,
		// whose handles an export does not carry: the world's own, and another
		// interface's, named in a use clause or held by a type of it.
		assertThatThrownBy(() -> lower(
				clock.formatted("duration", "func(d: duration) -> u64")
					.replace("export count-vowels", "resource thing;\n  export count-vowels")
					.replace("func(d: duration)", "func(d: borrow<thing>)"),
				Backend.WASM_COMPONENT, Map.of("count-vowels", List.of("d"))))
			.hasMessageStartingWith("world.wit:20: export 'count-vowels': the WIT type of d holds a handle of the "
					+ "resource 'thing' (no interface the world exports declares it), which an export does not carry");
		// An async export stays on the flat types.
		assertThatThrownBy(
				() -> lower(world("  export count-vowels: async func(s: list<u8>) -> s32;"), Backend.WASM_COMPONENT))
			.hasMessageStartingWith("world.wit:4: export 'count-vowels' is an async func, and an async export carries "
					+ "the flat types only");
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
				"(RONTOLISP:WASM-EXPORT '|narrow| :PARAMS '(:S8 :S16 :U8 :U16) :PARAM-NAMES '(|a| |b| |c| |d|) :RETURNS :U8)\n"
						+ "(RONTOLISP:WASM-EXPORT '|wide| :PARAMS '(:S32 :U32) :PARAM-NAMES '(|a| |b|) :RETURNS :U32)");
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
		assertThat(printed(forms)).isEqualTo("(RONTOLISP:WASM-EXPORT '|add| :PARAMS '(:U32 :U32) "
				+ ":PARAM-NAMES '(|x| |y|) :RETURNS :U32 :INTERFACE \"docs:adder/add@0.1.0\")");
	}

	@Test
	void lowersU64OnEveryWasmBackend() {
		// u64 rides the same i64 core type as s64 on every backend; a value at or
		// above 2^63 is the wrapper's run-time exact-or-trap concern, not a lowering
		// rule.
		String wit = world("  export count-vowels: func(s: u64) -> u64;");
		assertThat(printed(lower(wit, Backend.WASM_NO_GC)))
			.isEqualTo("(RONTOLISP:WASM-EXPORT '|count-vowels| :PARAMS '(:U64) :PARAM-NAMES '(|s|) :RETURNS :U64)");
		assertThat(printed(lower(wit, Backend.WASM_GC))).contains(":U64");
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
		assertThat(printed(forms)).isEqualTo("(RONTOLISP:WASM-EXPORT '|add| :PARAMS '(:S32 :S32) "
				+ ":PARAM-NAMES '(|x| |y|) :RETURNS :S32 :INTERFACE \"docs:adder/add@0.1.0\")");
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
		assertThat(printed(forms)).isEqualTo("(RONTOLISP:WASM-EXPORT '|add| :PARAMS '(:S32 :S32) "
				+ ":PARAM-NAMES '(|x| |y|) :RETURNS :S32 :INTERFACE \"calc\")\n"
				+ "(RONTOLISP:WASM-EXPORT '|negate| :PARAMS '(:S32) :PARAM-NAMES '(|x|) "
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
		assertThat(printed(forms)).contains("'|count-vowels|");
	}

	@Test
	void rejectsADuplicateExport() {
		// The reader refuses a twice-exported name first, in wasm-tools' words --
		// the directive's own duplicate check only sees what the reader accepts.
		assertThatThrownBy(() -> lower("""
				package root:component;

				world analyzer {
				  export count-vowels: func(s: string) -> s32;
				  export count-vowels: func(s: string) -> s32;
				}
				""", Backend.WASM_GC)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("world.wit: export `count-vowels` conflicts with prior func of same name at line 5, column 10");
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
		assertThat(printed(forms)).contains("'|count-vowels|").doesNotContain("shout");
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
		assertThat(printed(forms)).contains("'|count-vowels|");
	}

}
