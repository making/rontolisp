package am.ik.rontolisp.compiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective.Backend;
import am.ik.rontolisp.compiler.WitImportDirective.FieldStyle;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The naming hook both WIT directives take ({@code :names}, {@link WitNamingHook}) and
 * the {@code describe} half each offers a front end that binds names before the lowering
 * runs -- the Clojure lowering's {@code rontolisp.wit}. A table names the Lisp side only:
 * the WIT labels stay the provider's member, the Preview 1 field and the component's
 * names, so the per-backend lowerings are the ones every Common Lisp program gets, under
 * other names.
 */
class WitNamingHookTest {

	private static final String WIT = "kv.wit";

	private static final String STORE = "wasi:keyvalue/store@0.2.0";

	// wasi:keyvalue's store trimmed to the Preview 1 boundary: a freestanding func and a
	// resource with a constructor, methods and a static func.
	private static final String KEYVALUE = """
			package wasi:keyvalue@0.2.0;

			interface store {
			  open: func(identifier: string) -> u32;

			  resource bucket {
			    constructor(name: string);
			    get: func(key: string) -> string;
			    set: func(key: string, value: string);
			    count: static func(prefix: string) -> u32;
			  }
			}
			""";

	// A result-returning and an async member, whose component bindings carry internal
	// names, and a record no Preview 1 import carries.
	private static final String API = """
			package example:app@0.1.0;

			interface api {
			  record point { x: s32, y: s32 }

			  fetch: func(url: string) -> result<string, string>;
			  later: async func(n: u32) -> u32;
			  plot: func(p: point);
			  ready: func() -> bool;
			}
			""";

	private static WitImportDirective.Directive named(String iface, Map<String, String> names) {
		return new WitImportDirective.Directive(WIT, iface, null, null, FieldStyle.CAMEL, names);
	}

	private static String printed(List<LispVal> forms) {
		return String.join("\n", forms.stream().map(LispVal::print).toList());
	}

	private static WitTypeMapper.Shape shape(WitTypeMapper.@Nullable Shape shape) {
		return Objects.requireNonNull(shape);
	}

	private static Map<String, String> table(String... pairs) {
		Map<String, String> names = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			names.put(pairs[i], pairs[i + 1]);
		}
		return names;
	}

	@Test
	void aTableBindsEachListedMemberUnderItsNameOnEveryBackend() {
		WitImportDirective.Directive directive = named(STORE,
				table("open", "c%x/open", "bucket-get", "c%x/bucket-get"));
		// the interpreter and the JVM: the provider is dispatched with the WIT member
		assertThat(printed(WitImportDirective.lower(directive, KEYVALUE, WIT, Backend.OTHER))).isEqualTo("""
				(DEFUN |c%x/open| (|identifier|) (RONTOLISP::%WIT-CALL "wasi:keyvalue/store@0.2.0" "open" |identifier|))
				(DEFUN |c%x/bucket-get| (|self| |key|) (RONTOLISP::%WIT-CALL "wasi:keyvalue/store@0.2.0" \
				"bucket-get" |self| |key|))""");
		// Preview 1: the host field stays the WIT label's camelCase
		assertThat(printed(WitImportDirective.lower(directive, KEYVALUE, WIT, Backend.WASM_GC))).isEqualTo("""
				(RONTOLISP:WASM-IMPORT '|c%x/open| :FROM "store" :AS "open" :PARAMS '(:STRING) :RETURNS :INT)
				(RONTOLISP:WASM-IMPORT '|c%x/bucket-get| :FROM "store" :AS "bucketGet" :PARAMS '(:INT :STRING) \
				:RETURNS :STRING)""");
		// --component: the component import names the WIT member, the core binding the
		// table's name
		assertThat(printed(WitImportDirective.lower(directive, KEYVALUE, WIT, Backend.WASM_COMPONENT)))
			.contains("(\"open\" \"c%x/open\") (\"bucket-get\" \"c%x/bucket-get\"))");
	}

	@Test
	void aMemberTheTableLeavesOutIsNeitherBoundNorChecked() {
		// plot takes a record, which no Preview 1 import carries: left out, it binds
		// nothing and refuses nothing; named, Preview 1 refuses it at its WIT line
		assertThat(printed(WitImportDirective.lower(named("example:app/api", table("ready", "c%a/ready")), API, WIT,
				Backend.WASM_GC)))
			.isEqualTo("(RONTOLISP:WASM-IMPORT '|c%a/ready| :FROM \"api\" :AS \"ready\" :PARAMS 'NIL :RETURNS :BOOL)");
		assertThatThrownBy(() -> WitImportDirective.lower(named("example:app/api", table("plot", "c%a/plot")), API, WIT,
				Backend.WASM_GC))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("kv.wit:8: 'plot': the WIT type of parameter 'p' does not cross the Preview 1");
	}

	@Test
	void theInternalNamesFollowTheBoundName() {
		// a result's raw binding and an async member's start and lift are the bound name
		// behind a lone-% suffix, never a name of the table's front end
		String component = printed(
				WitImportDirective.lower(named("example:app/api", table("fetch", "c%a/fetch", "later", "c%a/later")),
						API, WIT, Backend.WASM_COMPONENT));
		assertThat(component).contains("(\"fetch\" \"c%a/fetch%raw\")")
			.contains("(:ASYNC-CALL \"later\" \"c%a/later%start\" \"c%a/later%lift\")")
			.contains("(DEFUN |c%a/fetch| (|url|) (RONTOLISP::%WIT-RESULT (|c%a/fetch%raw| |url|)))")
			.contains("(DEFUN |c%a/later| (|n|) (RONTOLISP::%SUBTASK-FUTURE (|c%a/later%start| |n|) "
					+ "#'|c%a/later%lift|))");
	}

	@Test
	void aReferenceFilterReadsTheBoundName() {
		// --component binds the members the program names, and a program that spells its
		// own names names the BOUND one -- so does a drop's filter
		WitImportDirective.Directive directive = named(STORE,
				table("open", "c%x/open", "bucket-get", "c%x/bucket-get", "bucket-drop", "c%x/bucket-drop"));
		String bound = printed(WitImportDirective.lower(directive, KEYVALUE, WIT, Backend.WASM_COMPONENT,
				Set.of("c%x/bucket-get"), Set.of("c%x/bucket-drop")));
		assertThat(bound).contains("(\"bucket-get\" \"c%x/bucket-get\")")
			.contains("(:DROP \"bucket\" \"c%x/bucket-drop\")")
			.doesNotContain("\"open\"");
		// the WIT labels themselves name nothing under a table
		assertThat(printed(WitImportDirective.lower(directive, KEYVALUE, WIT, Backend.WASM_COMPONENT,
				Set.of("bucket-get", "open"), Set.of("bucket-drop"))))
			.doesNotContain("\"bucket-get\" \"c%x")
			.doesNotContain(":DROP");
	}

	/**
	 * A front end that reads a result's envelope itself names the raw binding alone: the
	 * component binds the member, and the public wrapper unwrapping it through
	 * {@code %wit-result} -- with the wit.lisp runtime that call splices -- is left out
	 * until the program names the bound name too.
	 */
	@Test
	void aFrontEndReadingAResultsEnvelopeNamesTheRawBindingAlone() {
		WitImportDirective.Directive directive = named("example:app/api", table("fetch", "c%a/fetch"));
		String raw = printed(WitImportDirective.lower(directive, API, WIT, Backend.WASM_COMPONENT,
				Set.of("c%a/fetch%raw"), Set.of()));
		assertThat(raw).contains("(\"fetch\" \"c%a/fetch%raw\")").doesNotContain("%WIT-RESULT");
		String both = printed(WitImportDirective.lower(directive, API, WIT, Backend.WASM_COMPONENT,
				Set.of("c%a/fetch%raw", "c%a/fetch"), Set.of()));
		assertThat(both).contains("(\"fetch\" \"c%a/fetch%raw\")")
			.contains("(DEFUN |c%a/fetch| (|url|) (RONTOLISP::%WIT-RESULT (|c%a/fetch%raw| |url|)))");
	}

	@Test
	void theTableIsParsedFromTheDirectiveAndTakesNoPackage() {
		WitImportDirective.Directive parsed = WitImportDirective.parse((LispCons) LispReader.readFromString(
				"(rontolisp:wit-import \"kv.wit\" :interface \"wasi:keyvalue/store\" :names ((\"open\" \"c%x/open\")))"));
		assertThat(parsed.names()).containsExactly(Map.entry("open", "c%x/open"));
		assertThatThrownBy(() -> WitImportDirective.parse((LispCons) LispReader.readFromString(
				"(rontolisp:wit-import \"kv.wit\" :interface \"x\" :package kv :names ((\"open\" \"o\")))")))
			.hasMessageContaining(":names spells every binding itself, so it takes no :package");
		assertThatThrownBy(() -> WitImportDirective.parse((LispCons) LispReader
			.readFromString("(rontolisp:wit-import \"kv.wit\" :interface \"x\" :names ((open \"o\")))")))
			.hasMessageContaining(":names expects a list of (\"label\" \"lisp-name\") string pairs");
		assertThatThrownBy(() -> WitImportDirective.parse((LispCons) LispReader.readFromString(
				"(rontolisp:wit-import \"kv.wit\" :interface \"x\" :names ((\"open\" \"o\") (\"open\" \"p\")))")))
			.hasMessageContaining(":names names 'open' twice");
	}

	@Test
	void describeNamesEveryMemberTheLoweringBindsAndHowItIsBound() {
		WitImportDirective.Description described = WitImportDirective
			.describe(new WitImportDirective.Directive(WIT, STORE, null, null, FieldStyle.CAMEL), KEYVALUE, WIT);
		assertThat(described.id()).isEqualTo(STORE);
		assertThat(described.members().stream().map(WitImportDirective.Member::name)).containsExactly("open",
				"bucket-new", "bucket-get", "bucket-set", "bucket-count", "bucket-drop");
		// a table naming every member the description lists binds exactly those names:
		// one enumeration, two readers
		Map<String, String> identity = new LinkedHashMap<>();
		for (WitImportDirective.Member member : described.members()) {
			identity.put(member.name(), member.name());
		}
		List<String> defined = WitImportDirective
			.lower(named(STORE, identity), KEYVALUE, WIT, Backend.OTHER, null, null)
			.stream()
			.map(form -> ((LispSymbol) ((LispCons) ((LispCons) form).cdr()).car()).name())
			.toList();
		assertThat(defined).containsExactlyElementsOf(identity.keySet());
		WitImportDirective.Member get = described.members().get(2);
		assertThat(get.origin()).isEqualTo(WitImportDirective.Origin.FUNCTION);
		assertThat(get.params().stream().map(param -> param.name() + ":" + param.shape().rep()))
			.containsExactly("self:HANDLE", "key:STRING");
		assertThat(shape(get.result()).rep()).isEqualTo(WitTypeMapper.Rep.STRING);
		assertThat(shape(described.members().get(1).result()).rep()).as("a constructor answers its handle")
			.isEqualTo(WitTypeMapper.Rep.HANDLE);
		assertThat(described.members().get(5).origin()).isEqualTo(WitImportDirective.Origin.DROP);
	}

	@Test
	void describeSpellsAnOptionsElementAndAResultsOkArm() {
		WitImportDirective.Description described = WitImportDirective
			.describe(new WitImportDirective.Directive(WIT, "example:app/api", null, null, FieldStyle.CAMEL), API, WIT);
		WitImportDirective.Member fetch = described.members().get(0);
		assertThat(shape(fetch.result()).rep()).isEqualTo(WitTypeMapper.Rep.RESULT);
		assertThat(shape(shape(fetch.result()).element()).rep()).isEqualTo(WitTypeMapper.Rep.STRING);
		assertThat(shape(fetch.result()).wit()).isEqualTo("result<string, string>");
		assertThat(described.members().get(1).async()).isTrue();
		assertThat(described.members().get(2).params().get(0).shape().rep()).isEqualTo(WitTypeMapper.Rep.PLIST);
		assertThat(shape(described.members().get(3).result()).rep()).isEqualTo(WitTypeMapper.Rep.BOOLEAN);
		assertThat(described.members().get(3).line()).isEqualTo(9);
	}

	/**
	 * A described type holds every type nested in it, each resolved in the interface that
	 * writes it (a variant used from another interface carries that interface's record),
	 * aliases followed. A nested type naming no definition has no representation, and
	 * only the member reaching it is affected -- the interpreter's lowering never looks
	 * inside a record, so neither does the description's check.
	 */
	@Test
	void describeSpellsEveryNestedTypeInTheScopeItIsWrittenIn() {
		String wit = """
				package example:app@0.1.0;

				interface types {
				  record point { x: s32, y: s32 }
				  variant shape { empty, dot(point) }
				}

				interface api {
				  use types.{shape};
				  type octet = u8;
				  record half { x: missing }
				  draw: func(s: shape, raw: list<octet>) -> result<list<shape>, tuple<s32, string>>;
				  broken: func(h: half);
				}
				""";
		List<WitImportDirective.Member> members = WitImportDirective
			.describe(new WitImportDirective.Directive(WIT, "example:app/api", null, null, FieldStyle.CAMEL), wit, WIT)
			.members();
		WitTypeMapper.Shape shape = members.get(0).params().get(0).shape();
		assertThat(shape.rep()).isEqualTo(WitTypeMapper.Rep.TAGGED_LIST);
		assertThat(shape.parts().stream().map(WitTypeMapper.Part::label)).containsExactly("empty", "dot");
		assertThat(shape.parts().get(0).shape()).isNull();
		WitTypeMapper.Shape point = shape(shape.parts().get(1).shape());
		assertThat(point.rep()).isEqualTo(WitTypeMapper.Rep.PLIST);
		assertThat(point.parts().stream().map(part -> part.label() + ":" + shape(part.shape()).rep()))
			.containsExactly("x:INT", "y:INT");
		assertThat(members.get(0).params().get(1).shape().rep()).as("a list of an alias of u8 is a byte string")
			.isEqualTo(WitTypeMapper.Rep.BYTE_STRING);
		WitTypeMapper.Shape result = shape(members.get(0).result());
		assertThat(shape(result.element()).rep()).isEqualTo(WitTypeMapper.Rep.LIST);
		assertThat(shape(shape(result.element()).element()).rep()).isEqualTo(WitTypeMapper.Rep.TAGGED_LIST);
		assertThat(shape(result.error()).parts().stream().map(part -> shape(part.shape()).rep()))
			.containsExactly(WitTypeMapper.Rep.INT, WitTypeMapper.Rep.STRING);
		WitTypeMapper.Shape half = members.get(1).params().get(0).shape();
		assertThat(half.rep()).isEqualTo(WitTypeMapper.Rep.PLIST);
		assertThat(shape(half.parts().get(0).shape()).rep()).isEqualTo(WitTypeMapper.Rep.UNSUPPORTED);
	}

	// A world's exports, lowered and described.
	private static final String GREETER = """
			package local:greeter;

			world greeter {
			  export greet: func(name: string) -> string;
			  export is-short: func(name: string) -> bool;
			}
			""";

	@Test
	void anExportTableImplementsEachLabelByItsFunctionUnderTheLabel() {
		Map<String, List<String>> program = Map.of("c%g/greet", List.of("c%name"), "c%g/is-short%export",
				List.of("#:wasm-in0"));
		List<LispVal> forms = WitExportDirective.lower(
				new WitExportDirective.Directive("greeter.wit", null,
						table("greet", "c%g/greet", "is-short", "c%g/is-short%export")),
				GREETER, "greeter.wit", program::get, Backend.WASM_GC);
		assertThat(printed(forms)).isEqualTo(
				"""
						(RONTOLISP:WASM-EXPORT '|c%g/greet| :AS "greet" :PARAMS '(:STRING) :PARAM-NAMES '(|name|) :RETURNS :STRING)
						(RONTOLISP:WASM-EXPORT '|c%g/is-short%export| :AS "is-short" :PARAMS '(:STRING) :PARAM-NAMES '(|name|) \
						:RETURNS :BOOL)""");
		assertThatThrownBy(() -> WitExportDirective.lower(
				new WitExportDirective.Directive("greeter.wit", null, table("greet", "c%g/greet")), GREETER,
				"greeter.wit", program::get, Backend.WASM_GC))
			.hasMessage("greeter.wit:5: export 'is-short' has no matching (defun is-short ...) in the program");
	}

	@Test
	void describeAWorldChecksEverythingLowerChecksBeforeTheProgram() {
		WitExportDirective.WorldDescription described = WitExportDirective
			.describe(new WitExportDirective.Directive("greeter.wit", null), GREETER, "greeter.wit");
		assertThat(described.name()).isEqualTo("greeter");
		assertThat(described.exports().stream().map(export -> export.name() + ":" + shape(export.result()).rep()))
			.containsExactly("greet:STRING", "is-short:BOOLEAN");
		assertThat(described.exports().get(1).line()).isEqualTo(5);
		// the refusals lower makes before it reads a function, in its words
		assertThatThrownBy(() -> WitExportDirective.describe(new WitExportDirective.Directive("w.wit", null), """
				package local:w;
				world w {
				  export run: func();
				}
				""", "w.wit")).hasMessageContaining("w.wit:3: export 'run' collides with the component's wasi:cli/run");
		assertThatThrownBy(() -> WitExportDirective.describe(new WitExportDirective.Directive("w.wit", null), """
				package local:w;
				world w {
				  export pts: func() -> list<s32>;
				}
				""", "w.wit"))
			.hasMessageContaining("w.wit:3: export 'pts': the WIT type of the result is not supported");
		assertThatThrownBy(() -> WitExportDirective.describe(new WitExportDirective.Directive("w.wit", null), """
				package local:w;
				world w {
				  export a: func();
				  export a: func();
				}
				""", "w.wit")).hasMessageContaining("w.wit:4: duplicate export 'a'");
	}

}
