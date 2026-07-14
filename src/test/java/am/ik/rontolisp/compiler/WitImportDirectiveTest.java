package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective.Backend;
import am.ik.rontolisp.compiler.WitImportDirective.Directive;
import am.ik.rontolisp.compiler.WitImportDirective.FieldStyle;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

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
			.isEqualTo(new Directive("wit/kv.wit", STORE, "kv", "keyvalue", FieldStyle.KEBAB));
	}

	@Test
	void parsesADesignatorWrittenAsABareSymbol() {
		// A name may be written as a symbol in the WIT's own spelling, not just a string.
		assertThat(WitImportDirective
			.parse(form("(rontolisp:wit-import \"kv.wit\" :interface store :package kv :from keyvalue)")))
			.isEqualTo(new Directive("kv.wit", "store", "kv", "keyvalue", FieldStyle.CAMEL));
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
			.hasMessageContaining("Unknown rontolisp:wit-import option :world");
	}

	@Test
	void rejectsANonKeywordOptionAndAMissingValue() {
		assertThatThrownBy(() -> WitImportDirective.parse(form("(rontolisp:wit-import \"kv.wit\" store)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Expected a keyword option");
		assertThatThrownBy(() -> WitImportDirective.parse(form("(rontolisp:wit-import \"kv.wit\" :interface)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("Missing value for :interface");
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
		assertThat(printed(forms)).isEqualTo("""
				(defpackage kv (:use cl) (:export open bucket-new bucket-get bucket-set bucket-count))
				(defun kv:open (identifier) \
				(rontolisp::%wit-call "wasi:keyvalue/store@0.2.0" "open" identifier))
				(defun kv:bucket-new (name) \
				(rontolisp::%wit-call "wasi:keyvalue/store@0.2.0" "bucket-new" name))
				(defun kv:bucket-get (self key) \
				(rontolisp::%wit-call "wasi:keyvalue/store@0.2.0" "bucket-get" self key))
				(defun kv:bucket-set (self key value) \
				(rontolisp::%wit-call "wasi:keyvalue/store@0.2.0" "bucket-set" self key value))
				(defun kv:bucket-count (prefix) \
				(rontolisp::%wit-call "wasi:keyvalue/store@0.2.0" "bucket-count" prefix))""");
	}

	@Test
	void thePackageDesignatorKeepsItsCase() {
		// rontolisp symbols are case-preserving, so the synthesized defpackage must name
		// the package EXACTLY as written: lowercasing it here (while the bindings keep
		// their case) defined `KV:open` in a package named `kv`, and every call site then
		// failed to resolve with "No such package: KV".
		List<LispVal> forms = lower(KEYVALUE, Backend.OTHER, new Directive(WIT, STORE, "KV", null, FieldStyle.CAMEL));
		assertThat(printed(forms)).startsWith("(defpackage KV (:use cl) (:export open")
			.contains("(defun KV:open (identifier) ");
	}

	@Test
	void bindsTheNamesInTheCurrentPackageWithoutThePackageOption() {
		// No :package -> no defpackage, and the bindings keep their bare names.
		List<LispVal> forms = lower(KEYVALUE, Backend.OTHER, new Directive(WIT, STORE, null, null, FieldStyle.CAMEL));
		assertThat(forms).hasSize(5);
		assertThat(printed(forms)).doesNotContain("defpackage")
			.startsWith("(defun open (identifier) (rontolisp::%wit-call \"" + STORE + "\" \"open\" identifier))");
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
		assertThat(printed(forms)).isEqualTo("""
				(rontolisp:wasm-import (quote open) :from "store" :as "open" \
				:params (quote (:string)) :returns :int)
				(rontolisp:wasm-import (quote bucket-new) :from "store" :as "bucketNew" \
				:params (quote (:string)) :returns :int)
				(rontolisp:wasm-import (quote bucket-get) :from "store" :as "bucketGet" \
				:params (quote (:int :string)) :returns :string)
				(rontolisp:wasm-import (quote bucket-set) :from "store" :as "bucketSet" \
				:params (quote (:int :string :string)) :returns :void)
				(rontolisp:wasm-import (quote bucket-count) :from "store" :as "bucketCount" \
				:params (quote (:string)) :returns :int)""");
	}

	@Test
	void lowersAFreestandingInterfaceWithTheDefaultCamelCaseFields() {
		// The name mapping the docs pin: create-shader -> the import field createShader.
		// A func with no parameters lowers to an empty :params list, and one with no
		// result
		// to :returns :void.
		List<LispVal> forms = lower(GL, Backend.WASM_GC,
				new Directive(WIT, "example:gfx/gl@0.1.0", null, null, FieldStyle.CAMEL));
		assertThat(printed(forms)).isEqualTo("""
				(rontolisp:wasm-import (quote create-shader) :from "gl" :as "createShader" \
				:params (quote (:string)) :returns :int)
				(rontolisp:wasm-import (quote set-uniform) :from "gl" :as "setUniform" \
				:params (quote (:string :float)) :returns :void)
				(rontolisp:wasm-import (quote is-ready) :from "gl" :as "isReady" \
				:params (quote nil) :returns :bool)""");
	}

	@Test
	void kebabFieldStyleKeepsTheWitLabelVerbatimAndFromOverridesTheModule() {
		List<LispVal> forms = lower(GL, Backend.WASM_GC,
				new Directive(WIT, "example:gfx/gl@0.1.0", null, "graphics", FieldStyle.KEBAB));
		assertThat(printed(forms)).isEqualTo("""
				(rontolisp:wasm-import (quote create-shader) :from "graphics" :as "create-shader" \
				:params (quote (:string)) :returns :int)
				(rontolisp:wasm-import (quote set-uniform) :from "graphics" :as "set-uniform" \
				:params (quote (:string :float)) :returns :void)
				(rontolisp:wasm-import (quote is-ready) :from "graphics" :as "is-ready" \
				:params (quote nil) :returns :bool)""");
	}

	@Test
	void lowersAFreestandingInterfaceIntoPackageQualifiedDefuns() {
		// The same GL interface on the interpreter: gl:create-shader, and the member name
		// the provider receives is the BARE one ("create-shader"), not the qualified
		// symbol.
		List<LispVal> forms = lower(GL, Backend.OTHER,
				new Directive(WIT, "example:gfx/gl@0.1.0", "gl", null, FieldStyle.CAMEL));
		assertThat(printed(forms)).isEqualTo("""
				(defpackage gl (:use cl) (:export create-shader set-uniform is-ready))
				(defun gl:create-shader (source) \
				(rontolisp::%wit-call "example:gfx/gl@0.1.0" "create-shader" source))
				(defun gl:set-uniform (name value) \
				(rontolisp::%wit-call "example:gfx/gl@0.1.0" "set-uniform" name value))
				(defun gl:is-ready nil (rontolisp::%wit-call "example:gfx/gl@0.1.0" "is-ready"))""");
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
		String canonical = "(defun reset nil (rontolisp::%wit-call \"example:app/admin@0.1.0\" \"reset\"))";
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
		assertThat(printed(lowerApi(body, Backend.WASM_GC))).isEqualTo("""
				(rontolisp:wasm-import (quote bucket-get) :from "api" :as "bucketGet" \
				:params (quote (:int :string)) :returns :string)
				(rontolisp:wasm-import (quote size) :from "api" :as "size" \
				:params (quote (:int)) :returns :int)
				(rontolisp:wasm-import (quote drop-it) :from "api" :as "dropIt" \
				:params (quote (:int)) :returns :void)
				(rontolisp:wasm-import (quote owner) :from "api" :as "owner" \
				:params (quote (:int)) :returns :string)""");
	}

	@Test
	void carriesAByteStringAcrossThePreview1Boundary() {
		// list<u8> is a byte-per-char rontolisp string (the settled mapping), so it
		// crosses
		// as :string -- the same designator as a WIT string.
		assertThat(printed(lowerApi("  put: func(data: list<u8>) -> list<u8>;", Backend.WASM_GC)))
			.isEqualTo("(rontolisp:wasm-import (quote put) :from \"api\" :as \"put\" "
					+ ":params (quote (:string)) :returns :string)");
	}

	@Test
	void unescapesAPercentEscapedWitLabel() {
		// %type / %flags are legal WIT (they occur in the WASI WIT itself): the % is
		// source
		// escaping, and the component-model label is the bare word.
		assertThat(printed(lowerApi("  emit: func(%type: string);", Backend.OTHER)))
			.isEqualTo("(defun emit (type) (rontolisp::%wit-call \"" + API + "\" \"emit\" type))");
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
			.isEqualTo("(defun save (p) (rontolisp::%wit-call \"" + API + "\" \"save\" p))");
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
		assertThat(printed(lowerApi(body, Backend.OTHER))).isEqualTo("""
				(defun find (key) (rontolisp::%wit-call "example:app/api@0.1.0" "find" key))
				(defun fetch (url) (rontolisp::%wit-call "example:app/api@0.1.0" "fetch" url))
				(defun save (p) (rontolisp::%wit-call "example:app/api@0.1.0" "save" p))
				(defun now nil (rontolisp::%wit-call "example:app/api@0.1.0" "now"))""");
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
	void refusesAStreamOrAFutureOnEveryBackend() {
		// Unlike the types above, a stream/future has no rontolisp value on ANY backend
		// --
		// it needs language-level async -- so the interpreter refuses it too.
		for (Backend backend : new Backend[] { Backend.OTHER, Backend.WASM_GC }) {
			assertThatThrownBy(() -> lowerApi("  read: func(handle: u32) -> stream<u8>;", backend)).as("%s", backend)
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessage("kv.wit:4: 'read': the WIT type of the result is a stream or a future, which has no "
						+ "rontolisp value on any backend (it needs language-level async)");
			assertThatThrownBy(() -> lowerApi("  wait: func(f: future<string>);", backend)).as("%s", backend)
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessage("kv.wit:4: 'wait': the WIT type of parameter 'f' is a stream or a future, which has no "
						+ "rontolisp value on any backend (it needs language-level async)");
		}
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
