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

	// The same path in SERVE mode: the CLI splices the %http-dispatch wrapper last (after
	// every library pass), then compiles the core in serve mode.
	private static byte[] compileServeComponent(String source) {
		List<LispVal> program = am.ik.rontolisp.cli.HttpHandlerInliner
			.inline(am.ik.rontolisp.eval.WitLibrary.process(LispReader.readAllFromString(source)));
		return new WasmLispCompiler(false, true, false, false, true).compile(program);
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
	void followsATypeAliasWhoseTargetIsItselfAName() {
		// `type headers = fields` -- the shape every wasi:http-flavored interface writes.
		// WitTypeMapper classifies a type STRUCTURALLY, so only the resolver can follow
		// an
		// alias to a named definition; the directive must do that before asking for the
		// representation.
		String aliasWit = """
				package local:x@0.1.0;

				interface s {
				    resource fields {
				        constructor();
				    }
				    type headers = fields;
				    send: func(h: headers) -> u32;
				}
				""";
		assertThat(lower(aliasWit, "local:x/s", WitExportDirective.Backend.WASM_COMPONENT).get(1).print())
			.contains("(\"send\" \"kv:send\")");
		// Preview 1 wants the same alias resolved down to its flat designator.
		assertThat(lower(aliasWit, "local:x/s", WitExportDirective.Backend.WASM_GC).stream().map(LispVal::print))
			.anyMatch(form -> form.contains("wasm-import") && form.contains("send") && form.contains(":int"));
	}

	@Test
	void rejectsAUserImportTheWasiSurfaceAlreadyCarries() {
		// The component would then carry the same instance import name twice -- invalid,
		// and
		// nothing downstream says so in words.
		String fsWit = """
				package wasi:filesystem@0.3.0;

				interface types {
				    resource descriptor {
				        sync: func();
				    }
				}
				""";
		String witLiteral = "\"" + fsWit.replace("\n", "\\n").replace("\"", "\\\"") + "\"";
		assertThatThrownBy(() -> compileComponent("(defpackage fs (:use cl) (:export descriptor-sync))\n"
				+ "(rontolisp::%component-import \"wasi:filesystem/types@0.3.0\" " + witLiteral
				+ " (\"descriptor-sync\" \"fs:descriptor-sync\"))\n" + "(fs:descriptor-sync 1)\n"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("wasi:filesystem/types@0.3.0")
			.hasMessageContaining("already imports that interface as part of its own WASI surface");
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

	// A probe interface over every parameter shape the canonical ABI flattens: a variant
	// with a payload-less and a string-payload case, an enum, a record, a tuple, an
	// option,
	// and a result whose arms carry a record and a string.
	private static final String RICH_PARAM_WIT = """
			package local:x@0.1.0;

			interface s {
			    variant method {
			        get,
			        post,
			        other(string)
			    }
			    enum color {
			        red,
			        green
			    }
			    record point {
			        x: s32,
			        y: s32,
			    }
			    resource thing {
			        constructor();
			        set-method: func(m: method) -> result<_, string>;
			        paint: func(c: color);
			        move-to: func(p: point);
			        span: func(t: tuple<s32, string>);
			        nudge: func(p: option<point>);
			        send: func(r: result<point, string>);
			    }
			}
			""";

	@Test
	void richParametersCrossTheComponentBoundary() {
		// Parameters flatten, so every shape the canonical ABI flattens crosses: a
		// variant
		// (payload-less or payload-bearing), an enum, a record, a tuple, an option, a
		// result.
		List<LispVal> forms = lower(RICH_PARAM_WIT, "local:x/s", WitExportDirective.Backend.WASM_COMPONENT);
		assertThat(forms.get(1).print()).contains("(\"thing-paint\" \"kv:thing-paint\")")
			.contains("(\"thing-move-to\" \"kv:thing-move-to\")")
			.contains("(\"thing-span\" \"kv:thing-span\")")
			.contains("(\"thing-nudge\" \"kv:thing-nudge\")")
			.contains("(\"thing-send\" \"kv:thing-send\")")
			// set-method returns a result, so it keeps the raw name + wrapper split.
			.contains("(\"thing-set-method\" \"kv::%thing-set-method\")");
	}

	@Test
	void lowersRichParametersThroughTheCanonicalAbi() {
		String witLiteral = "\"" + RICH_PARAM_WIT.replace("\n", "\\n").replace("\"", "\\\"") + "\"";
		byte[] component = compileComponent("(defpackage kv (:use cl) (:export thing-new thing-set-method thing-paint "
				+ "thing-move-to thing-span thing-nudge thing-send))\n" + "(rontolisp::%component-import \"local:x/s\" "
				+ witLiteral + " (\"thing-new\" \"kv:thing-new\") (\"thing-set-method\" \"kv::%thing-set-method\")"
				+ " (\"thing-paint\" \"kv:thing-paint\") (\"thing-move-to\" \"kv:thing-move-to\")"
				+ " (\"thing-span\" \"kv:thing-span\") (\"thing-nudge\" \"kv:thing-nudge\")"
				+ " (\"thing-send\" \"kv:thing-send\"))\n"
				+ "(defun kv:thing-set-method (self m) (rontolisp::%wit-result (kv::%thing-set-method self m)))\n"
				+ "(let ((th (kv:thing-new)))\n" + "  (kv:thing-set-method th :get)\n"
				+ "  (kv:thing-set-method th '(:other . \"PATCH\"))\n" + "  (kv:thing-paint th :red)\n"
				+ "  (kv:thing-move-to th '(:x 1 :y 2))\n" + "  (kv:thing-span th '(1 \"a\"))\n"
				+ "  (kv:thing-nudge th nil)\n" + "  (kv:thing-send th '(:ok :x 1 :y 2))\n"
				+ "  (kv:thing-send th '(:error . \"boom\")))\n");
		assertThat(containsAscii(component, "local:x/s")).isTrue();
		assertThat(containsAscii(component, "[method]thing.set-method")).isTrue();
		assertThat(containsAscii(component, "[method]thing.send")).isTrue();
	}

	// The shape that broke the first cut of this: `wasi:http`'s `error-code` -- 39 cases,
	// payloads that are records of options of strings -- inside a `result` ARGUMENT. It
	// is
	// the ONE call that sends a serve-mode response, so it is the whole point of the
	// work.
	private static final String DEEP_VARIANT_WIT = """
			package local:x@0.1.0;

			interface s {
			    record dns-error-payload {
			        rcode: option<string>,
			        info-code: option<u16>,
			    }
			    record field-size-payload {
			        field-name: option<string>,
			        field-size: option<u32>,
			    }
			    variant error-code {
			        dns-timeout,
			        dns-error(dns-error-payload),
			        http-request-body-size(option<u64>),
			        http-request-header-size(option<field-size-payload>),
			        http-response-trailer-size(field-size-payload),
			        internal-error(option<string>),
			    }
			    resource response-outparam {
			        set: static func(param: response-outparam, response: result<u32, error-code>);
			    }
			}
			""";

	@Test
	void lowersAParameterThatNestsThroughResultVariantOptionAndRecord() {
		// The scratch a wrapper needs is a property of how deeply its parameter types
		// NEST,
		// so the local pools are measured, not fixed: this one reaches
		// result -> variant -> option -> record -> option<string>, five levels, and a
		// fixed
		// pool sized for anything shallower simply runs out.
		String witLiteral = "\"" + DEEP_VARIANT_WIT.replace("\n", "\\n").replace("\"", "\\\"") + "\"";
		byte[] component = compileComponent("(defpackage kv (:use cl) (:export response-outparam-set))\n"
				+ "(rontolisp::%component-import \"local:x/s\" " + witLiteral
				+ " (\"response-outparam-set\" \"kv:response-outparam-set\"))\n"
				+ "(kv:response-outparam-set 1 '(:ok . 200))\n"
				+ "(kv:response-outparam-set 1 '(:error :dns-error :rcode \"NXDOMAIN\" :info-code 3))\n"
				+ "(kv:response-outparam-set 1 '(:error :http-request-header-size :field-name \"x\" :field-size 9))\n");
		assertThat(containsAscii(component, "[static]response-outparam.set")).isTrue();
	}

	@Test
	void rejectsAnEmptyRecordNamingTheWitLine() {
		// The component model has no empty record type, so this would otherwise surface
		// as
		// an unreadable component rather than a compile error.
		String emptyRecordWit = """
				package local:x@0.1.0;

				interface s {
				    record nothing {
				    }
				    take: func(n: nothing);
				}
				""";
		assertThatThrownBy(() -> lower(emptyRecordWit, "local:x/s", WitExportDirective.Backend.WASM_COMPONENT))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("kv.wit:6")
			.hasMessageContaining("declares no fields");
	}

	@Test
	void rejectsAListParameterNamingTheWitLine() {
		// A list<T> parameter would have to be written into linear memory as a canonical
		// array, which is a different mechanism from flattening -- so it is still refused
		// (list<u8> crosses, as a byte string).
		String listParamWit = """
				package local:x@0.1.0;

				interface s {
				    plot: func(xs: list<s32>);
				    write: func(bytes: list<u8>);
				}
				""";
		assertThatThrownBy(() -> lower(listParamWit, "local:x/s", WitExportDirective.Backend.WASM_COMPONENT))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("kv.wit:4")
			.hasMessageContaining("is a list, which does not cross the component import boundary as a parameter yet");
	}

	@Test
	void rejectsAFlagsParameterNamingTheWitLine() {
		String flagsParamWit = """
				package local:x@0.1.0;

				interface s {
				    flags perm {
				        read,
				        write,
				    }
				    chmod: func(p: perm);
				}
				""";
		assertThatThrownBy(() -> lower(flagsParamWit, "local:x/s", WitExportDirective.Backend.WASM_COMPONENT))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("kv.wit:8")
			.hasMessageContaining("involves flags");
	}

	@Test
	void rejectsAnOptionNestedInsideAnOptionParameter() {
		// Both `none` and `some(none)` would be nil: the shape has no rontolisp value.
		String nestedOptionWit = """
				package local:x@0.1.0;

				interface s {
				    maybe: func(v: option<option<s32>>);
				}
				""";
		assertThatThrownBy(() -> lower(nestedOptionWit, "local:x/s", WitExportDirective.Backend.WASM_COMPONENT))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("kv.wit:4")
			.hasMessageContaining("nests an option directly inside an option");
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

	@Test
	void aServedComponentImportsTheInterfaceAlongsideItsFixedWasiHttpSurface() {
		// A rontolisp:http-handler component wires TWO adapters (the preview1 bridge
		// before the core, the serve adapter after it), so its index bookkeeping is its
		// own -- but the user import rides the same appendUserImports wiring as the other
		// three variants, and the fixed wasi:http/incoming-handler export survives it.
		String witLiteral = "\"" + KV_WIT.replace("\n", "\\n").replace("\"", "\\\"") + "\"";
		byte[] component = compileServeComponent("(defpackage kv (:use cl) (:export open))\n"
				+ "(rontolisp::%component-import \"wasi:keyvalue/store@0.2.0-draft\" " + witLiteral
				+ " (\"open\" \"kv::%open\") (\"bucket-get\" \"kv::%bucket-get\"))\n"
				+ "(defun kv:open (identifier) (rontolisp::%wit-result (kv::%open identifier)))\n"
				+ "(defun handle (request) (list :status 200 :body (kv:open \"\")))\n"
				+ "(rontolisp:http-handler 'handle)\n");
		assertThat(containsAscii(component, "wasi:keyvalue/store@0.2.0-draft")).isTrue();
		assertThat(containsAscii(component, "[method]bucket.get")).isTrue();
		assertThat(containsAscii(component, "wasi:http/incoming-handler@0.2.0")).isTrue();
	}

}
