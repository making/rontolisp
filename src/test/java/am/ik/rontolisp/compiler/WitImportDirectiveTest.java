package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective.Backend;
import am.ik.rontolisp.compiler.WitImportDirective.Directive;
import am.ik.rontolisp.compiler.WitImportDirective.FieldStyle;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import org.jspecify.annotations.Nullable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WitImportDirective}: the
 * {@code (rontolisp:wit-import "kv.wit" :interface "wasi:keyvalue/store@0.2.0" :package
 * kv)} directive and its lowering -- <em>one WIT, a different implementation per backend,
 * zero source changes</em>.
 *
 * <p>
 * The two lowerings are what the whole design rests on, so they are pinned as printed
 * s-expressions: on Preview 1 WASM one {@code rontolisp:wasm-import} per WIT function
 * (literally the hand-written import block, which is why the emitted module stays
 * byte-identical to it), and on the interpreter and the JVM an ORDINARY {@code defun} per
 * function dispatching through {@code rontolisp::%wit-call} (which is why
 * {@code #'kv:open} / {@code funcall} / {@code mapcar} need no extra wiring).
 *
 * <p>
 * Every contract violation must be a compile error naming the WIT <strong>file and
 * line</strong> -- without it the same mismatch only surfaces as a missing-import trap at
 * instantiation -- so each error leg pins the location too.
 */
class WitImportDirectiveTest {

	private static final String WIT = "kv.wit";

	private static final String STORE = "wasi:keyvalue/store@0.2.0";

	private static final String API = "example:app/api@0.1.0";

	// wasi:keyvalue's store, trimmed to the types the Preview 1 boundary carries: a
	// freestanding func plus a resource with a constructor, methods and a static func --
	// every shape the name mapping has a rule for.
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

	// A freestanding host interface, the shape a browser/JS host binds.
	private static final String GL = """
			package example:gfx@0.1.0;

			interface gl {
			  create-shader: func(source: string) -> u32;
			  set-uniform: func(name: string, value: f64);
			  is-ready: func() -> bool;
			}
			""";

	// wasi:http in miniature: `outgoing-handler` uses `error-code` from `types`, and
	// `error-code`'s `DNS-error` case carries a `DNS-error-payload` record that
	// `outgoing-handler` never imported. The gate walks type structure, so it meets that
	// payload -- and can only resolve it in the interface that DEFINES the variant.
	private static final String HTTP = """
			package wasi:http@0.2.0;

			interface types {
			  record DNS-error-payload {
			    rcode: option<string>,
			    info-code: option<u16>
			  }

			  variant error-code {
			    DNS-timeout,
			    DNS-error(DNS-error-payload),
			    connection-refused,
			    internal-error(option<string>)
			  }

			  resource outgoing-request;

			  resource future-incoming-response;
			}

			interface outgoing-handler {
			  use types.{outgoing-request, future-incoming-response, error-code};

			  handle: func(request: outgoing-request) -> result<future-incoming-response, error-code>;
			}
			""";

	private static final String TWO_INTERFACES = """
			package example:app@0.1.0;

			interface api {
			  ping: func();
			}

			interface admin {
			  reset: func();
			}
			""";

	private static LispCons form(String source) {
		return (LispCons) LispReader.readFromString(source);
	}

	// An interface whose body starts at WIT line 4, so the line an error names is easy to
	// read off the test.
	private static String iface(String body) {
		return "package example:app@0.1.0;\n\ninterface api {\n" + body + "\n}\n";
	}

	private static List<LispVal> lower(String wit, Backend backend, Directive directive) {
		return WitImportDirective.lower(directive, wit, WIT, backend);
	}

	// The `api` interface of iface(...), bound without a package.
	private static List<LispVal> lowerApi(String body, Backend backend) {
		return lower(iface(body), backend, new Directive(WIT, API, null, null, FieldStyle.CAMEL));
	}

	// The keyvalue store, with a filter over the resource DROPS -- the one thing the WIT
	// does not decide, since a drop is not a WIT function and binds only when the program
	// names it. Every function binds (no member filter).
	private static List<LispVal> lowerKeyvalue(Backend backend, @Nullable Set<String> dropFilter) {
		return WitImportDirective.lower(new Directive(WIT, STORE, "kv", null, FieldStyle.CAMEL), KEYVALUE, WIT, backend,
				null, dropFilter);
	}

	private static String printed(List<LispVal> forms) {
		return String.join("\n", forms.stream().map(LispVal::print).toList());
	}

	@Test
	void isDirectiveRecognizesTheQualifiedForm() {
		assertThat(WitImportDirective.isDirective(form("(rontolisp:wit-import \"kv.wit\" :interface \"store\")")))
			.isTrue();
		assertThat(WitImportDirective.isDirective(form("(rontolisp:wasm-import 'f :from \"m\")"))).isFalse();
		assertThat(WitImportDirective.isDirective(form("(rontolisp:wit-export \"w.wit\")"))).isFalse();
		assertThat(WitImportDirective.isDirective(form("(defun f (x) x)"))).isFalse();
	}

	@Test
	void parsesTheInterfaceAloneAndDefaultsToCamelCaseFields() {
		// :camel is the default -- the JavaScript convention, and what jco produces.
		assertThat(WitImportDirective.parse(form("(rontolisp:wit-import \"wit/kv.wit\" :interface \"" + STORE + "\")")))
			.isEqualTo(new Directive("wit/kv.wit", STORE, null, null, FieldStyle.CAMEL));
	}

	@Test
	void parsesEveryOption() {
		assertThat(WitImportDirective.parse(form("(rontolisp:wit-import \"wit/kv.wit\" :interface \"" + STORE + "\" "
				+ ":package kv :from \"keyvalue\" :field-style :kebab)")))
			.isEqualTo(new Directive("wit/kv.wit", STORE, "KV", "keyvalue", FieldStyle.KEBAB));
	}

	@Test
	void parsesADesignatorWrittenAsABareSymbol() {
		// A name may be written as a symbol in the WIT's own spelling, not just a string.
		assertThat(WitImportDirective
			.parse(form("(rontolisp:wit-import \"kv.wit\" :interface store :package kv :from keyvalue)")))
			.isEqualTo(new Directive("kv.wit", "store", "KV", "keyvalue", FieldStyle.CAMEL));
	}

	@Test
	void requiresTheInterfaceOption() {
		// The path alone does not say WHAT to bind: a .wit file may declare several
		// interfaces, so :interface is required rather than guessed.
		assertThatThrownBy(() -> WitImportDirective.parse(form("(rontolisp:wit-import \"kv.wit\")")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("rontolisp:wit-import requires :interface")
			.hasMessageContaining("wasi:keyvalue/store@0.2.0");
		assertThatThrownBy(() -> WitImportDirective.parse(form("(rontolisp:wit-import \"kv.wit\" :package kv)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("rontolisp:wit-import requires :interface");
	}

	@Test
	void rejectsANonStringPath() {
		assertThatThrownBy(() -> WitImportDirective.parse(form("(rontolisp:wit-import kv :interface \"store\")")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("expects a WIT file path string");
		assertThatThrownBy(() -> WitImportDirective.parse(form("(rontolisp:wit-import)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("expects a WIT file path string");
	}

	@Test
	void rejectsAnUnknownOption() {
		assertThatThrownBy(
				() -> WitImportDirective.parse(form("(rontolisp:wit-import \"kv.wit\" :interface \"s\" :world w)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Unknown rontolisp:wit-import option :WORLD");
	}

	@Test
	void rejectsANonKeywordOptionAndAMissingValue() {
		assertThatThrownBy(() -> WitImportDirective.parse(form("(rontolisp:wit-import \"kv.wit\" store)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Expected a keyword option");
		assertThatThrownBy(() -> WitImportDirective.parse(form("(rontolisp:wit-import \"kv.wit\" :interface)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Missing value for :INTERFACE");
	}

	@Test
	void rejectsAKeywordAsANameAndAnUnknownFieldStyle() {
		assertThatThrownBy(() -> WitImportDirective.parse(form("(rontolisp:wit-import \"kv.wit\" :interface :store)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("rontolisp:wit-import :interface expects a name");
		assertThatThrownBy(() -> WitImportDirective
			.parse(form("(rontolisp:wit-import \"kv.wit\" :interface \"s\" :field-style :snake)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining(":field-style expects :camel or :kebab");
		assertThatThrownBy(() -> WitImportDirective
			.parse(form("(rontolisp:wit-import \"kv.wit\" :interface \"s\" :field-style \"camel\")")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining(":field-style expects :camel or :kebab");
	}

	@Test
	void lowersEachWitFunctionIntoAProviderDefunOnTheInterpreterAndJvm() {
		// An ORDINARY defun per WIT function -- that is what makes #'kv:bucket-get,
		// funcall, mapcar and eval work with no extra wiring. The resource mapping is
		// visible here: `bucket.get` -> `bucket-get` with the handle as the leading
		// `self`,
		// `constructor(bucket)` -> `bucket-new`, a static func -> `bucket-count`. The WIT
		// parameter names become the lambda list verbatim.
		List<LispVal> forms = lower(KEYVALUE, Backend.OTHER, new Directive(WIT, STORE, "kv", null, FieldStyle.CAMEL));
		assertThat(printed(forms)).isEqualTo(
				"(DEFPACKAGE kv (:USE CL) (:EXPORT open bucket-new bucket-get bucket-set bucket-count))\n(DEFUN kv:open (identifier) (RONTOLISP::%WIT-CALL \"wasi:keyvalue/store@0.2.0\" \"open\" identifier))\n(DEFUN kv:bucket-new (name) (RONTOLISP::%WIT-CALL \"wasi:keyvalue/store@0.2.0\" \"bucket-new\" name))\n(DEFUN kv:bucket-get (self key) (RONTOLISP::%WIT-CALL \"wasi:keyvalue/store@0.2.0\" \"bucket-get\" self key))\n(DEFUN kv:bucket-set (self key value) (RONTOLISP::%WIT-CALL \"wasi:keyvalue/store@0.2.0\" \"bucket-set\" self key value))\n(DEFUN kv:bucket-count (prefix) (RONTOLISP::%WIT-CALL \"wasi:keyvalue/store@0.2.0\" \"bucket-count\" prefix))");
	}

	@Test
	void thePackageDesignatorKeepsItsCase() {
		// rontolisp symbols are case-preserving, so the synthesized defpackage must name
		// the package EXACTLY as written: lowercasing it here (while the bindings keep
		// their case) defined `KV:open` in a package named `kv`, and every call site then
		// failed to resolve with "No such package: KV".
		List<LispVal> forms = lower(KEYVALUE, Backend.OTHER, new Directive(WIT, STORE, "KV", null, FieldStyle.CAMEL));
		assertThat(printed(forms)).startsWith("(DEFPACKAGE KV (:USE CL) (:EXPORT open")
			.contains("(DEFUN KV:open (identifier) ");
	}

	@Test
	void bindsTheNamesInTheCurrentPackageWithoutThePackageOption() {
		// No :package -> no defpackage, and the bindings keep their bare names.
		List<LispVal> forms = lower(KEYVALUE, Backend.OTHER, new Directive(WIT, STORE, null, null, FieldStyle.CAMEL));
		assertThat(forms).hasSize(5);
		assertThat(printed(forms)).doesNotContain("DEFPACKAGE")
			.startsWith("(DEFUN open (identifier) (RONTOLISP::%WIT-CALL \"" + STORE + "\" \"open\" identifier))");
	}

	@Test
	void lowersEachWitFunctionIntoAWasmImportOnPreview1() {
		// The same WIT, the same names -- but each binding is now the wasm-import
		// directive
		// a hand-written program would carry. The default import module is the
		// interface's
		// bare name; the field is the camelCase spelling of the Lisp member name (so a
		// resource method's field is bucketGet, matching a jco-transpiled host).
		List<LispVal> forms = lower(KEYVALUE, Backend.WASM_GC, new Directive(WIT, STORE, null, null, FieldStyle.CAMEL));
		assertThat(printed(forms)).isEqualTo(
				"(RONTOLISP:WASM-IMPORT (QUOTE open) :FROM \"store\" :AS \"open\" :PARAMS (QUOTE (:STRING)) :RETURNS :INT)\n(RONTOLISP:WASM-IMPORT (QUOTE bucket-new) :FROM \"store\" :AS \"bucketNew\" :PARAMS (QUOTE (:STRING)) :RETURNS :INT)\n(RONTOLISP:WASM-IMPORT (QUOTE bucket-get) :FROM \"store\" :AS \"bucketGet\" :PARAMS (QUOTE (:INT :STRING)) :RETURNS :STRING)\n(RONTOLISP:WASM-IMPORT (QUOTE bucket-set) :FROM \"store\" :AS \"bucketSet\" :PARAMS (QUOTE (:INT :STRING :STRING)) :RETURNS :VOID)\n(RONTOLISP:WASM-IMPORT (QUOTE bucket-count) :FROM \"store\" :AS \"bucketCount\" :PARAMS (QUOTE (:STRING)) :RETURNS :INT)");
	}

	@Test
	void lowersAFreestandingInterfaceWithTheDefaultCamelCaseFields() {
		// The name mapping the docs pin: create-shader -> the import field createShader.
		// A func with no parameters lowers to an empty :params list, and one with no
		// result
		// to :returns :VOID.
		List<LispVal> forms = lower(GL, Backend.WASM_GC,
				new Directive(WIT, "example:gfx/gl@0.1.0", null, null, FieldStyle.CAMEL));
		assertThat(printed(forms)).isEqualTo(
				"(RONTOLISP:WASM-IMPORT (QUOTE create-shader) :FROM \"gl\" :AS \"createShader\" :PARAMS (QUOTE (:STRING)) :RETURNS :INT)\n(RONTOLISP:WASM-IMPORT (QUOTE set-uniform) :FROM \"gl\" :AS \"setUniform\" :PARAMS (QUOTE (:STRING :FLOAT)) :RETURNS :VOID)\n(RONTOLISP:WASM-IMPORT (QUOTE is-ready) :FROM \"gl\" :AS \"isReady\" :PARAMS (QUOTE NIL) :RETURNS :BOOL)");
	}

	@Test
	void kebabFieldStyleKeepsTheWitLabelVerbatimAndFromOverridesTheModule() {
		List<LispVal> forms = lower(GL, Backend.WASM_GC,
				new Directive(WIT, "example:gfx/gl@0.1.0", null, "graphics", FieldStyle.KEBAB));
		assertThat(printed(forms)).isEqualTo(
				"(RONTOLISP:WASM-IMPORT (QUOTE create-shader) :FROM \"graphics\" :AS \"create-shader\" :PARAMS (QUOTE (:STRING)) :RETURNS :INT)\n(RONTOLISP:WASM-IMPORT (QUOTE set-uniform) :FROM \"graphics\" :AS \"set-uniform\" :PARAMS (QUOTE (:STRING :FLOAT)) :RETURNS :VOID)\n(RONTOLISP:WASM-IMPORT (QUOTE is-ready) :FROM \"graphics\" :AS \"is-ready\" :PARAMS (QUOTE NIL) :RETURNS :BOOL)");
	}

	@Test
	void lowersAFreestandingInterfaceIntoPackageQualifiedDefuns() {
		// The same GL interface on the interpreter: gl:create-shader, and the member name
		// the provider receives is the BARE one ("create-shader"), not the qualified
		// symbol.
		List<LispVal> forms = lower(GL, Backend.OTHER,
				new Directive(WIT, "example:gfx/gl@0.1.0", "gl", null, FieldStyle.CAMEL));
		assertThat(printed(forms)).isEqualTo(
				"(DEFPACKAGE gl (:USE CL) (:EXPORT create-shader set-uniform is-ready))\n(DEFUN gl:create-shader (source) (RONTOLISP::%WIT-CALL \"example:gfx/gl@0.1.0\" \"create-shader\" source))\n(DEFUN gl:set-uniform (name value) (RONTOLISP::%WIT-CALL \"example:gfx/gl@0.1.0\" \"set-uniform\" name value))\n(DEFUN gl:is-ready NIL (RONTOLISP::%WIT-CALL \"example:gfx/gl@0.1.0\" \"is-ready\"))");
	}

	@Test
	void bindsAnInterfaceNamedByAnUnversionedIdOrABareName() {
		// The reference is resolved by WitResolver, so the unversioned id and the bare
		// name name the same interface -- and each lowers to the CANONICAL id, which is
		// what the provider registry is keyed by. Lowering the reference as written
		// instead would compile cleanly and then die at runtime ("No provider is bound
		// for the WIT interface admin") for two of the three spellings of one interface.
		Directive full = new Directive(WIT, "example:app/admin@0.1.0", null, null, FieldStyle.CAMEL);
		Directive unversioned = new Directive(WIT, "example:app/admin", null, null, FieldStyle.CAMEL);
		Directive bare = new Directive(WIT, "admin", null, null, FieldStyle.CAMEL);
		String canonical = "(DEFUN reset NIL (RONTOLISP::%WIT-CALL \"example:app/admin@0.1.0\" \"reset\"))";
		assertThat(printed(lower(TWO_INTERFACES, Backend.OTHER, full))).isEqualTo(canonical);
		assertThat(printed(lower(TWO_INTERFACES, Backend.OTHER, unversioned))).isEqualTo(canonical);
		assertThat(printed(lower(TWO_INTERFACES, Backend.OTHER, bare))).isEqualTo(canonical);
	}

	@Test
	void reportsAnUnknownInterfaceNamingTheOnesTheFileDefines() {
		Directive directive = new Directive(WIT, "nope", null, null, FieldStyle.CAMEL);
		assertThatThrownBy(() -> lower(TWO_INTERFACES, Backend.OTHER, directive))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("kv.wit: no interface 'nope' (found: example:app/api@0.1.0, example:app/admin@0.1.0)");
	}

	@Test
	void carriesTheResourceHandleAsAnInteger() {
		// A WIT resource is an opaque integer handle (the settled mapping), whether it is
		// referenced as `borrow<bucket>`, `own<bucket>` or by its bare name.
		String body = """
				resource bucket {
				  get: func(key: string) -> string;
				}
				size: func(b: borrow<bucket>) -> u32;
				drop-it: func(b: own<bucket>);
				owner: func(b: bucket) -> string;""";
		assertThat(printed(lowerApi(body, Backend.WASM_GC))).isEqualTo(
				"(RONTOLISP:WASM-IMPORT (QUOTE bucket-get) :FROM \"api\" :AS \"bucketGet\" :PARAMS (QUOTE (:INT :STRING)) :RETURNS :STRING)\n(RONTOLISP:WASM-IMPORT (QUOTE size) :FROM \"api\" :AS \"size\" :PARAMS (QUOTE (:INT)) :RETURNS :INT)\n(RONTOLISP:WASM-IMPORT (QUOTE drop-it) :FROM \"api\" :AS \"dropIt\" :PARAMS (QUOTE (:INT)) :RETURNS :VOID)\n(RONTOLISP:WASM-IMPORT (QUOTE owner) :FROM \"api\" :AS \"owner\" :PARAMS (QUOTE (:INT)) :RETURNS :STRING)");
	}

	@Test
	void bindsAResourceDropOnlyWhenTheProgramNamesIt() {
		// A resource is released by its interface's drop, which WIT declares no function
		// for -- so WitResolver.functions never yields one, and a program that receives a
		// handle would have no way to give it back. It binds as `<resource>-drop`,
		// symmetric with the `<resource>-new` a constructor binds.
		//
		// And ONLY when the program names it, on every backend: a drop is not a WIT
		// function, so it is outside the "Preview 1 binds every function" convention --
		// and that one rule is what keeps every artifact that existed before drops
		// byte-identical, since nothing in one references a `-drop` name.
		assertThat(printed(lowerKeyvalue(Backend.OTHER, Set.of("bucket-get")))).doesNotContain("bucket-drop");
		assertThat(printed(lowerKeyvalue(Backend.OTHER, Set.of("bucket-drop")))).contains("kv:bucket-drop");
		// No filter at all = every resource's drop: --no-prune / --dynamic, and the
		// interpreter, which produces no artifact to keep identical.
		assertThat(printed(lowerKeyvalue(Backend.OTHER, null))).contains("kv:bucket-drop");
		// The 5-arg overload binds NONE, which is what leaves every existing caller --
		// and
		// every artifact it built -- exactly as it was.
		assertThat(printed(WitImportDirective.lower(new Directive(WIT, STORE, "kv", null, FieldStyle.CAMEL), KEYVALUE,
				WIT, Backend.OTHER, null)))
			.doesNotContain("bucket-drop");
	}

	@Test
	void lowersAResourceDropIntoAProviderDefunOnTheInterpreterAndJvm() {
		// An ordinary provider defun, like every other binding, and the bound name joins
		// the package's exports. The core does NOT decide what a drop MEANS -- the
		// provider does (a Java store closes a connection, a Lisp one releases a handle
		// into its table; one with nothing to release just returns nil).
		List<LispVal> forms = lowerKeyvalue(Backend.OTHER, Set.of("bucket-drop"));
		assertThat(printed(forms))
			.startsWith("(DEFPACKAGE kv (:USE CL) "
					+ "(:EXPORT open bucket-new bucket-get bucket-set bucket-count bucket-drop))")
			.endsWith("(DEFUN kv:bucket-drop (self) "
					+ "(RONTOLISP::%WIT-CALL \"wasi:keyvalue/store@0.2.0\" \"bucket-drop\" self))");
	}

	@Test
	void lowersAResourceDropIntoANoOpDefunOnPreview1() {
		// A no-op defun and NO rontolisp:wasm-import -- the absence is the point. A
		// Preview 1 handle is an opaque integer the host handed over, so there is nothing
		// on the guest side to release; importing a `[resource-drop]bucket` field would
		// INVENT a host function the interface never declared, breaking both the
		// byte-identity-with-a-hand-written-import-block property and the browser demos'
		// hand-written JS import objects.
		List<LispVal> forms = lowerKeyvalue(Backend.WASM_GC, Set.of("bucket-drop"));
		assertThat(printed(forms)).isEqualTo(
				"(DEFPACKAGE kv (:USE CL) (:EXPORT open bucket-new bucket-get bucket-set bucket-count bucket-drop))\n(RONTOLISP:WASM-IMPORT (QUOTE kv:open) :FROM \"store\" :AS \"open\" :PARAMS (QUOTE (:STRING)) :RETURNS :INT)\n(RONTOLISP:WASM-IMPORT (QUOTE kv:bucket-new) :FROM \"store\" :AS \"bucketNew\" :PARAMS (QUOTE (:STRING)) :RETURNS :INT)\n(RONTOLISP:WASM-IMPORT (QUOTE kv:bucket-get) :FROM \"store\" :AS \"bucketGet\" :PARAMS (QUOTE (:INT :STRING)) :RETURNS :STRING)\n(RONTOLISP:WASM-IMPORT (QUOTE kv:bucket-set) :FROM \"store\" :AS \"bucketSet\" :PARAMS (QUOTE (:INT :STRING :STRING)) :RETURNS :VOID)\n(RONTOLISP:WASM-IMPORT (QUOTE kv:bucket-count) :FROM \"store\" :AS \"bucketCount\" :PARAMS (QUOTE (:STRING)) :RETURNS :INT)\n(DEFUN kv:bucket-drop (self) self NIL)");
	}

	@Test
	void carriesAResourceDropAsAKeywordMemberOfTheComponentImportForm() {
		// On --component a drop is a SECOND emission kind (`canon resource.drop` produces
		// a core function with no component function behind it), so the internal form has
		// to tell it apart from a bound function: the (:drop "bucket" "kv:bucket-drop")
		// member's keyword head is what does it, against a ("member" "lisp-name") pair.
		assertThat(lowerKeyvalue(Backend.WASM_COMPONENT, Set.of("bucket-drop")).get(1).print())
			.startsWith("(RONTOLISP::%COMPONENT-IMPORT \"wasi:keyvalue/store@0.2.0\"")
			.endsWith("(:DROP \"bucket\" \"kv:bucket-drop\"))");
		assertThat(lowerKeyvalue(Backend.WASM_COMPONENT, Set.of()).get(1).print()).doesNotContain(":DROP");
	}

	@Test
	void reportsAResourceThatDeclaresAMethodCalledDropAgainstTheWitLine() {
		// The drop's name is SYNTHESIZED, so it goes through the same duplicate check
		// every
		// binding does: `bucket.drop` already binds `bucket-drop` in the flat Lisp-2
		// function namespace, and the two cannot both have it. Reported whether or not
		// the
		// program names the drop, because the interface cannot be bound coherently either
		// way.
		String body = """
				resource bucket {
				  drop: func();
				}""";
		assertThatThrownBy(() -> lowerApi(body, Backend.OTHER)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("kv.wit:4: interface 'api' binds 'bucket-drop' twice");
	}

	@Test
	void lowersAnAsyncFuncMemberWithAsyncTOnPreview1() {
		// An `async func` member answers a future on every backend: the interpreter/JVM
		// bind it as an async-defun, --component as a real subtask future, and Preview 1
		// lowers it to `wasm-import :async t` -- the settled degenerate future, so
		// `futurep` agrees everywhere and the declaration is not reactor-specific.
		assertThat(printed(lowerApi("  pull: async func(url: string) -> string;", Backend.WASM_GC))).isEqualTo(
				"(RONTOLISP:WASM-IMPORT (QUOTE pull) :FROM \"api\" :AS \"pull\" :PARAMS (QUOTE (:STRING)) :RETURNS :STRING :ASYNC T)");
		// A plain member stays exactly as it was -- the option is absent, not nil, so
		// every pre-:async artifact keeps its byte identity.
		assertThat(printed(lowerApi("  pull: func(url: string) -> string;", Backend.WASM_GC))).doesNotContain(":ASYNC");
	}

	@Test
	void carriesAByteStringAcrossThePreview1Boundary() {
		// list<u8> is a byte-per-char rontolisp string (the settled mapping), so it
		// crosses
		// as :STRING -- the same designator as a WIT string.
		assertThat(printed(lowerApi("  put: func(data: list<u8>) -> list<u8>;", Backend.WASM_GC))).isEqualTo(
				"(RONTOLISP:WASM-IMPORT (QUOTE put) :FROM \"api\" :AS \"put\" :PARAMS (QUOTE (:STRING)) :RETURNS :STRING)");
	}

	@Test
	void unescapesAPercentEscapedWitLabel() {
		// %type / %flags are legal WIT (they occur in the WASI WIT itself): the % is
		// source
		// escaping, and the component-model label is the bare word.
		assertThat(printed(lowerApi("  emit: func(%type: string);", Backend.OTHER)))
			.isEqualTo("(DEFUN emit (type) (RONTOLISP::%WIT-CALL \"" + API + "\" \"emit\" type))");
	}

	@Test
	void resolvesATypeBroughtInByAUseClause() {
		String wit = """
				package example:app@0.1.0;

				interface types {
				  record point { x: s32, y: s32 }
				}

				interface api {
				  use types.{point};

				  save: func(p: point);
				}
				""";
		Directive directive = new Directive(WIT, API, null, null, FieldStyle.CAMEL);
		// On the interpreter every representation crosses, so the record simply binds.
		assertThat(printed(lower(wit, Backend.OTHER, directive)))
			.isEqualTo("(DEFUN save (p) (RONTOLISP::%WIT-CALL \"" + API + "\" \"save\" p))");
		// And the WASM leg proves the name was RESOLVED through the use clause rather
		// than
		// reported as undefined: it is refused as a record (PLIST), not as an unknown
		// type.
		assertThatThrownBy(() -> lower(wit, Backend.WASM_GC, directive))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("kv.wit:10: 'save': the WIT type of parameter 'p' does not cross the Preview 1 "
					+ "WASM import boundary")
			.hasMessageContaining("PLIST");
	}

	@Test
	void theComponentGateWalksAUsedVariantIntoTheScopeThatDefinesIt() {
		// The gate walks type structure, and the walk crosses a use clause here:
		// `handle`'s
		// result is a `result<_, error-code>`, `error-code` belongs to `types`, and its
		// DNS-error case carries a `DNS-error-payload` that `outgoing-handler` never
		// imported. Judged in the scope the walk STARTED in, that payload is an undefined
		// type and the whole component import is refused -- which is what this used to
		// do.
		// Judged in the scope that WROTE it, it is a record, and the canonical ABI
		// marshals
		// it.
		Directive directive = new Directive(WIT, "wasi:http/outgoing-handler@0.2.0", null, null, FieldStyle.CAMEL);
		List<LispVal> forms = WitImportDirective.lower(directive, HTTP, WIT, Backend.WASM_COMPONENT);
		// A result-returning function is bound as the raw envelope member plus the public
		// wrapper that unwraps it (and signals the error arm).
		assertThat(forms).hasSize(2);
		assertThat(forms.get(0).print())
			.startsWith("(RONTOLISP::%COMPONENT-IMPORT \"wasi:http/outgoing-handler@0.2.0\" \"package wasi:http@0.2.0;")
			.endsWith("(\"handle\" \"%handle\"))");
		assertThat(forms.get(1).print())
			.isEqualTo("(DEFUN handle (request) (RONTOLISP::%WIT-RESULT (%handle request)))");
	}

	@Test
	void theComponentGateStillReportsAnUndefinedTypeAgainstTheWitLine() {
		// Following a use clause is not the same as searching every interface in the
		// file:
		// `DNS-error-payload` is defined in `types`, but `outgoing-handler` imported
		// `error-code` alone, so writing the payload type in ITS signature is an
		// undefined
		// reference -- resolving it anyway would silently bind a same-named type from
		// whichever interface happened to be nearest.
		String wit = """
				package wasi:http@0.2.0;

				interface types {
				  record DNS-error-payload {
				    rcode: option<string>
				  }

				  variant error-code {
				    DNS-error(DNS-error-payload)
				  }
				}

				interface outgoing-handler {
				  use types.{error-code};

				  describe: func(p: DNS-error-payload) -> string;
				}
				""";
		Directive directive = new Directive(WIT, "wasi:http/outgoing-handler@0.2.0", null, null, FieldStyle.CAMEL);
		assertThatThrownBy(() -> WitImportDirective.lower(directive, wit, WIT, Backend.WASM_COMPONENT))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("kv.wit:16: 'describe': the WIT type of parameter 'p' is 'DNS-error-payload', which the file "
					+ "does not define (nor import with a use clause)");
		// A name nothing defines anywhere is reported the same way, from the same gate.
		assertThatThrownBy(() -> lowerApi("  save: func(p: point);", Backend.WASM_COMPONENT))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("kv.wit:4: 'save': the WIT type of parameter 'p' is 'point', which the file does not define "
					+ "(nor import with a use clause)");
	}

	@Test
	void bindsTheRichTypesOnTheInterpreterAndJvm() {
		// The boundary there is an ordinary Lisp call, so every settled representation
		// crosses: a record is a plist, an option is the value or nil, a result's ok arm
		// is
		// the value (its error arm signals rontolisp:wit-error, which the provider does).
		String body = """
				record point { x: s32, y: s32 }
				find: func(key: string) -> option<point>;
				fetch: func(url: string) -> result<string, string>;
				save: func(p: point) -> result<_, string>;
				now: func() -> u64;""";
		assertThat(printed(lowerApi(body, Backend.OTHER))).isEqualTo(
				"(DEFUN find (key) (RONTOLISP::%WIT-CALL \"example:app/api@0.1.0\" \"find\" key))\n(DEFUN fetch (url) (RONTOLISP::%WIT-CALL \"example:app/api@0.1.0\" \"fetch\" url))\n(DEFUN save (p) (RONTOLISP::%WIT-CALL \"example:app/api@0.1.0\" \"save\" p))\n(DEFUN now NIL (RONTOLISP::%WIT-CALL \"example:app/api@0.1.0\" \"now\"))");
	}

	@Test
	void refusesATypeThePreview1BoundaryCannotMarshalNamingItsSettledRepresentation() {
		// Only the flat set rontolisp:wasm-import can carry crosses on Preview 1. The
		// error
		// names the settled house representation, so it says what the value WOULD be
		// rather
		// than just refusing -- and it must NOT cite a .todo file, which is deleted the
		// moment the work lands.
		assertThatThrownBy(() -> lowerApi("  find: func(key: string) -> option<string>;", Backend.WASM_GC))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("kv.wit:4: 'find': the WIT type of the result does not cross the Preview 1 WASM "
					+ "import boundary")
			.hasMessageContaining("NIL_OR_VALUE")
			.hasMessageNotContaining(".todo");
		assertThatThrownBy(() -> lowerApi("  fetch: func(url: string) -> result<string, string>;", Backend.WASM_GC))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("kv.wit:4: 'fetch': the WIT type of the result does not cross")
			.hasMessageContaining("RESULT")
			.hasMessageNotContaining(".todo");
		// s64/u64: the wasm-GC backend's integers are i31ref, so a wide int is refused
		// here
		// even though the interpreter and the JVM bind it (see the test above).
		assertThatThrownBy(() -> lowerApi("  now: func() -> u64;", Backend.WASM_GC))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("kv.wit:4: 'now': the WIT type of the result does not cross")
			.hasMessageContaining("BIGNUM_INT");
		// A parameter is checked the same way, and named as such.
		assertThatThrownBy(() -> lowerApi("  save: func(tags: list<string>);", Backend.WASM_GC))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("kv.wit:4: 'save': the WIT type of parameter 'tags' does not cross")
			.hasMessageContaining("LIST");
	}

	@Test
	void refusesAStreamOrAFutureOnEveryNonComponentBackend() {
		// A stream/future needs the component-model async ABI, so only --component can
		// marshal it; the interpreter, the JVM and Preview 1 WASM have no rontolisp value
		// for it and refuse it, naming the WIT line.
		for (Backend backend : new Backend[] { Backend.OTHER, Backend.WASM_GC }) {
			assertThatThrownBy(() -> lowerApi("  read: func(handle: u32) -> stream<u8>;", backend)).as("%s", backend)
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessage("kv.wit:4: 'read': the WIT type of the result is a stream or a future, which only the "
						+ "--component backend can marshal (through the canonical ABI's async built-ins); the "
						+ "interpreter, the JVM and Preview 1 WASM have no rontolisp value for it");
			assertThatThrownBy(() -> lowerApi("  wait: func(f: future<string>);", backend)).as("%s", backend)
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessage("kv.wit:4: 'wait': the WIT type of parameter 'f' is a stream or a future, which only the "
						+ "--component backend can marshal (through the canonical ABI's async built-ins); the "
						+ "interpreter, the JVM and Preview 1 WASM have no rontolisp value for it");
		}
	}

	@Test
	void acceptsAStreamAndAFutureAcrossTheComponentBoundary() {
		// On --component the canonical ABI marshals the async value types: a stream<u8>
		// crosses as a byte-stream handle and a future<result<...>> as a readable handle,
		// so the front-end admits them (the wrapper is driven off the WIT text carried in
		// the %component-import form, not a flat designator).
		String body = """
				enum error-code { failed }
				send: func(body: stream<u8>) -> future<result<_, error-code>>;""";
		List<LispVal> forms = lowerApi(body, Backend.WASM_COMPONENT);
		assertThat(printed(forms)).contains("RONTOLISP::%COMPONENT-IMPORT").contains("send");
	}

	@Test
	void refusesAStreamWhoseElementIsNotU8OnTheComponentBoundary() {
		// Only a byte stream crosses in this release; a stream of a richer element has no
		// canonical read yet, so it is a friendly compile error rather than a codegen
		// throw.
		assertThatThrownBy(() -> lowerApi("  drain: func(src: stream<string>);", Backend.WASM_COMPONENT))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("is a stream whose element is not u8")
			.hasMessageContaining("only stream<u8>");
	}

	@Test
	void refusesABareFutureWithNoPayloadOnTheComponentBoundary() {
		// future.read needs a payload type to lift, so a bare (unparameterized) future is
		// refused with the WIT line named.
		assertThatThrownBy(() -> lowerApi("  wait: func(f: future);", Backend.WASM_COMPONENT))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("is a bare future with no payload type");
	}

	// An interface in the wasi:http@0.3 style: async type ALIASES anchor the built-in
	// names (`type body-stream = stream<u8>` binds body-stream-read and friends).
	private static final String ASYNC_ALIAS_BODY = """
			resource fields {
			  get: func(name: string) -> string;
			}
			type trailers = fields;
			enum error-code { failed }
			type body-stream = stream<u8>;
			type trailers-future = future<result<option<trailers>, error-code>>;
			probe: func(f: fields) -> u32;""";

	@Test
	void anAsyncAliasBindsItsBuiltInsOnlyWhenTheProgramNamesThem() {
		// Like a drop, an async built-in is not a WIT function: it binds ONLY when the
		// program references it, which is what keeps every artifact that existed before
		// async built-ins byte-identical. Referenced ops become (:async alias op name)
		// members of the %component-import form.
		List<LispVal> forms = WitImportDirective.lower(new Directive(WIT, API, null, null, FieldStyle.CAMEL),
				iface(ASYNC_ALIAS_BODY), WIT, Backend.WASM_COMPONENT,
				Set.of("probe", "body-stream-read", "trailers-future-read"),
				Set.of("probe", "body-stream-read", "trailers-future-read"));
		String printed = printed(forms);
		assertThat(printed).contains("(:ASYNC \"body-stream\" \"read\" \"body-stream-read\")")
			.contains("(:ASYNC \"trailers-future\" \"read\" \"trailers-future-read\")");
		assertThat(printed).doesNotContain("body-stream-write").doesNotContain("drop-readable");
	}

	@Test
	void anUnreferencedAsyncAliasBindsNothing() {
		// The drop rule applies: with a filter in force, an async member the program
		// never names binds nothing -- the byte-identity guard for every artifact that
		// existed before async built-ins.
		List<LispVal> filtered = WitImportDirective.lower(new Directive(WIT, API, null, null, FieldStyle.CAMEL),
				iface(ASYNC_ALIAS_BODY), WIT, Backend.WASM_COMPONENT, Set.of("probe"), Set.of("probe"));
		assertThat(printed(filtered)).doesNotContain(":ASYNC");
		// A bind-everything pass (no filter, i.e. --no-prune) binds them all on
		// --component, like a drop...
		List<LispVal> unfiltered = WitImportDirective.lower(new Directive(WIT, API, null, null, FieldStyle.CAMEL),
				iface(ASYNC_ALIAS_BODY), WIT, Backend.WASM_COMPONENT, null, null);
		assertThat(printed(unfiltered)).contains("(:ASYNC \"body-stream\" \"new\"")
			.contains("(:ASYNC \"trailers-future\" \"drop-writable\"");
		// ...but SKIPS them on a backend without the async ABI: the alias itself is
		// legal WIT, and a program that never touches the built-ins must keep compiling
		// everywhere -- including the interpreter and the JVM.
		for (Backend backend : new Backend[] { Backend.OTHER, Backend.WASM_GC }) {
			List<LispVal> forms = WitImportDirective.lower(new Directive(WIT, API, null, null, FieldStyle.CAMEL),
					iface(ASYNC_ALIAS_BODY), WIT, backend, null, null);
			assertThat(printed(forms)).as("%s", backend).doesNotContain(":async").doesNotContain("body-stream-read");
		}
	}

	@Test
	void anAsyncBuiltInReferencedOffTheComponentBackendIsAClearError() {
		// The async canonical built-ins are a component-model mechanism; a program that
		// names one on the interpreter, the JVM or Preview 1 WASM gets the reason, not
		// an undefined-function fallout.
		for (Backend backend : new Backend[] { Backend.OTHER, Backend.WASM_GC }) {
			assertThatThrownBy(() -> WitImportDirective.lower(new Directive(WIT, API, null, null, FieldStyle.CAMEL),
					iface(ASYNC_ALIAS_BODY), WIT, backend, Set.of("probe", "body-stream-read"),
					Set.of("probe", "body-stream-read")))
				.as("%s", backend)
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessageContaining("body-stream-read")
				.hasMessageContaining("only the --component backend");
		}
	}

	@Test
	void anAsyncAliasToANonU8StreamIsRefusedWhenBound() {
		String body = "  type wide = stream<string>;\n  probe: func() -> u32;";
		assertThatThrownBy(() -> WitImportDirective.lower(new Directive(WIT, API, null, null, FieldStyle.CAMEL),
				iface(body), WIT, Backend.WASM_COMPONENT, Set.of("probe", "wide-read"), Set.of("probe", "wide-read")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("names a stream whose element is not u8");
	}

	@Test
	void anAsyncAliasToABareFutureIsRefusedWhenBound() {
		String body = "  type signal = future;\n  probe: func() -> u32;";
		assertThatThrownBy(
				() -> WitImportDirective.lower(new Directive(WIT, API, null, null, FieldStyle.CAMEL), iface(body), WIT,
						Backend.WASM_COMPONENT, Set.of("probe", "signal-read"), Set.of("probe", "signal-read")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("names a bare future with no payload type");
	}

	@Test
	void reportsANamedTypeTheFileDoesNotDefineAgainstTheWitLine() {
		assertThatThrownBy(() -> lowerApi("  save: func(p: point);", Backend.OTHER))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("kv.wit:4: 'save': the WIT type of parameter 'p' is 'point', which the file does not define "
					+ "(nor import with a use clause)");
	}

	@Test
	void reportsAnInterfaceWithNoFunctionsAgainstItsWitLine() {
		// A binding-less import is always a mistake (a typo in :interface, or a
		// types-only
		// interface), so it is an error rather than a silent no-op.
		assertThatThrownBy(() -> lowerApi("  record point { x: s32, y: s32 }", Backend.OTHER))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("kv.wit:3: interface 'api' declares no functions");
	}

	@Test
	void reportsADuplicateBindingAgainstTheWitLine() {
		// The flat Lisp-2 function namespace has one `bucket-get`: a freestanding func of
		// that name and a `bucket.get` method cannot both bind.
		String body = """
				bucket-get: func(key: string) -> string;
				resource bucket {
				  get: func(key: string) -> string;
				}""";
		assertThatThrownBy(() -> lowerApi(body, Backend.OTHER)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("kv.wit:6: interface 'api' binds 'bucket-get' twice");
	}

	@Test
	void reportsAResourceMethodParameterNamedSelfAgainstTheWitLine() {
		// rontolisp passes the handle as the first argument of a method, under the name
		// `self`: a WIT parameter of that name would be shadowed by it.
		String body = """
				resource bucket {
				  get: func(self: string) -> string;
				}""";
		assertThatThrownBy(() -> lowerApi(body, Backend.OTHER)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("kv.wit:5: 'bucket-get' declares a parameter named 'self'")
			.hasMessageContaining("the resource handle");
	}

	@Test
	void rejectsTheNoGcBackend() {
		// --no-gc emits a plain MVP module with no imports at all, so the directive
		// cannot
		// mean anything there -- and says so before it even reads the WIT.
		assertThatThrownBy(
				() -> lower(KEYVALUE, Backend.WASM_NO_GC, new Directive(WIT, STORE, "kv", null, FieldStyle.CAMEL)))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("rontolisp:wit-import is not supported with --no-gc: the scalar backend emits a plain MVP "
					+ "module with no imports");
	}

	@Test
	void reportsAWitSyntaxErrorAgainstTheFile() {
		assertThatThrownBy(() -> lower("package example:app@0.1.0;\n\ninterface api {\n  oops:\n}\n", Backend.OTHER,
				new Directive(WIT, API, null, null, FieldStyle.CAMEL)))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("kv.wit: ")
			.hasMessageContaining("at line ")
			.hasCauseInstanceOf(am.ik.wit.WitParseException.class);
	}

}
