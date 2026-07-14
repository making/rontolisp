package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.rontolisp.reader.LispReader;
import am.ik.wasm.ComponentWriter;
import am.ik.wasm.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

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

	// Two interfaces of ONE document where the second `use`s the first's RESOURCE -- the
	// shape wasi:http/types has (it does not define `input-stream`, it uses
	// wasi:io/streams'
	// one), reduced to what a unit test can hold.
	private static final String TOKENS_WIT = """
			package local:x@0.1.0;

			interface tokens {
			    resource token {
			        value: func() -> u32;
			    }
			}

			interface bag {
			    use tokens.{token};
			    take: func() -> token;
			}
			""";

	private static final String TOKENS_ID = "local:x/tokens@0.1.0";

	private static final String BAG_ID = "local:x/bag@0.1.0";

	// The prefix of an instance-type declaration that aliases a type IN from the
	// enclosing
	// component: alias (0x02) / sort type (0x03) / target outer (0x02). Its operands (the
	// scope count and the component type index) follow.
	private static final byte[] ALIAS_OUTER_TYPE = { 0x02, 0x03, 0x02 };

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
		return compileForms(LispReader.readAllFromString(source));
	}

	// The same path, from forms a directive lowered rather than from source text.
	private static byte[] compileForms(List<LispVal> forms) {
		return new WasmLispCompiler(false, true).compile(am.ik.rontolisp.eval.WitLibrary.process(forms));
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

	private static boolean containsBytes(byte[] haystack, byte[] needle) {
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
				return true;
			}
		}
		return false;
	}

	// The WIT text as a Lisp string literal, the way it travels inside the internal
	// %component-import form (a `%`-heavy source, so it is concatenated rather than
	// String.format-ed).
	private static String witLiteral(String wit) {
		return "\"" + wit.replace("\n", "\\n").replace("\"", "\\\"") + "\"";
	}

	// The %component-import form of one interface, with its ("member" "lisp-name") pairs
	// (none = a type-only import: an interface imported purely for a type another one
	// uses).
	private static String importForm(String ifaceId, String wit, String... members) {
		return "(rontolisp::%component-import \"" + ifaceId + "\" " + witLiteral(wit) + " " + String.join(" ", members)
				+ ")\n";
	}

	private static WasmComponentImportCompiler.Import parseImport(String ifaceId, String wit, String... members) {
		return WasmComponentImportCompiler
			.parse((LispCons) LispReader.readAllFromString(importForm(ifaceId, wit, members)).get(0));
	}

	/** One top-level component section: its id and its payload. */
	private record Section(int id, byte[] payload) {
	}

	// The component's top-level sections, in order. Splitting them is what makes an
	// assertion about the emitted bytes precise: the same declaration bytes occur inside
	// the embedded core module and inside the fixed WASI import blob (which has `alias
	// outer` declarations of its own), so a search over the whole binary proves nothing.
	private static List<Section> sections(byte[] component) {
		List<Section> sections = new ArrayList<>();
		int pos = 8; // "\0asm" + version 0x0d / layer 0x01
		while (pos < component.length) {
			int id = component[pos++] & 0xff;
			int size = 0;
			int shift = 0;
			int b;
			do {
				b = component[pos++] & 0xff;
				size |= (b & 0x7f) << shift;
				shift += 7;
			}
			while ((b & 0x80) != 0);
			assertThat(pos + size).as("section %d overruns the component", id).isLessThanOrEqualTo(component.length);
			sections.add(new Section(id, Arrays.copyOfRange(component, pos, pos + size)));
			pos += size;
		}
		return sections;
	}

	// The section index of the component import of an interface (one entry per section:
	// appendUserImports writes them one interface at a time).
	private static int importSectionIndex(List<Section> sections, String ifaceId) {
		for (int i = 0; i < sections.size(); i++) {
			Section section = sections.get(i);
			if (section.id() == ComponentWriter.SEC_IMPORT && containsAscii(section.payload(), ifaceId)) {
				return i;
			}
		}
		throw new AssertionError("the component does not import '" + ifaceId + "'");
	}

	// The section index of an interface's instance TYPE: written immediately before the
	// import that references it.
	private static int instanceTypeSectionIndex(List<Section> sections, String ifaceId) {
		for (int i = importSectionIndex(sections, ifaceId) - 1; i >= 0; i--) {
			if (sections.get(i).id() == ComponentWriter.SEC_TYPE) {
				return i;
			}
		}
		throw new AssertionError("no instance type precedes the import of '" + ifaceId + "'");
	}

	// The program's own core module: the LAST of the component's embedded core modules
	// (the
	// memory module and the WASI adapter precede it in every blob variant).
	private static byte[] coreModule(List<Section> sections) {
		byte[] core = null;
		for (Section section : sections) {
			if (section.id() == ComponentWriter.SEC_CORE_MODULE) {
				core = section.payload();
			}
		}
		return Objects.requireNonNull(core, "the component embeds no core module");
	}

	// The bytes a core module's import section carries for a FUNC import of (module,
	// field): the two length-prefixed names, then importdesc 0x00. Both names are short,
	// so
	// each length is a one-byte LEB128. (The type index that follows is not checked here
	// --
	// theComponentImportingADropValidates is what cross-checks it against the canon
	// resource.drop, which is the only place the two sides can disagree.)
	private static byte[] coreFuncImport(String module, String field) {
		byte[] m = module.getBytes(StandardCharsets.UTF_8);
		byte[] f = field.getBytes(StandardCharsets.UTF_8);
		byte[] entry = new byte[m.length + f.length + 3];
		entry[0] = (byte) m.length;
		System.arraycopy(m, 0, entry, 1, m.length);
		entry[1 + m.length] = (byte) f.length;
		System.arraycopy(f, 0, entry, 2 + m.length, f.length);
		entry[entry.length - 1] = 0x00; // importdesc: func
		return entry;
	}

	// The component type index an instance import points at -- the trailing `0x05 <type>`
	// externdesc of the single entry its section carries. Every index here is a one-byte
	// LEB128 (a component has a few dozen types, not a few hundred).
	private static int importedInstanceTypeIndex(List<Section> sections, String ifaceId) {
		byte[] payload = sections.get(importSectionIndex(sections, ifaceId)).payload();
		assertThat(payload[payload.length - 2]).as("externdesc: instance").isEqualTo((byte) 0x05);
		return payload[payload.length - 1];
	}

	// The keyvalue program the component tests compile: one interface, two bound members,
	// and (unlike the tokens/bag pair below) nothing used from another interface.
	private static byte[] keyvalueComponent() {
		return compileComponent("(defpackage kv (:use cl) (:export open))\n"
				+ importForm("wasi:keyvalue/store@0.2.0-draft", KV_WIT, "(\"open\" \"kv::%open\")",
						"(\"bucket-get\" \"kv::%bucket-get\")")
				+ "(defun kv:open (identifier) (rontolisp::%wit-result (kv::%open identifier)))\n"
				+ "(print (kv:open \"\"))\n");
	}

	// A program over the tokens/bag pair, with the two %component-import forms written in
	// the given source order.
	private static byte[] tokensAndBagComponent(boolean providerFirst) {
		String tokens = importForm(TOKENS_ID, TOKENS_WIT, "(\"token-value\" \"tk:token-value\")");
		String bag = importForm(BAG_ID, TOKENS_WIT, "(\"take\" \"bg:take\")");
		return compileComponent(
				"(defpackage tk (:use cl) (:export token-value))\n" + "(defpackage bg (:use cl) (:export take))\n"
						+ (providerFirst ? tokens + bag : bag + tokens) + "(print (tk:token-value (bg:take)))\n");
	}

	// A program that binds the `token` resource's DROP -- the one binding WIT declares no
	// function for -- alongside one of its methods.
	private static byte[] tokensDropComponent() {
		return compileComponent(
				"(defpackage tk (:use cl) (:export token-value token-drop))\n"
						+ importForm(TOKENS_ID, TOKENS_WIT, "(\"token-value\" \"tk:token-value\")",
								"(:drop \"token\" \"tk:token-drop\")")
						+ "(print (tk:token-value 1))\n(tk:token-drop 1)\n");
	}

	// The same program as the DIRECTIVE lowers it, with and without a call to the drop --
	// which is the only thing that decides whether one is bound at all.
	private static byte[] tokensComponentNaming(boolean drop) {
		List<LispVal> forms = new ArrayList<>(WitImportDirective.lower(
				new WitImportDirective.Directive("x.wit", TOKENS_ID, "tk", null, WitImportDirective.FieldStyle.CAMEL),
				TOKENS_WIT, "x.wit", WitExportDirective.Backend.WASM_COMPONENT, java.util.Set.of("token-value"),
				drop ? java.util.Set.of("token-drop") : java.util.Set.of()));
		forms.addAll(LispReader.readAllFromString("(print (tk:token-value 1))" + (drop ? "\n(tk:token-drop 1)" : "")));
		return compileForms(forms);
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
		byte[] component = keyvalueComponent();
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
		// Not one section, not one type index: without this, adding the import machinery
		// to
		// the builder would have moved every hardcoded index the fixed WASI blobs are
		// wired
		// with, and every component ever emitted would have changed shape.
		ComponentWriter writer = new ComponentWriter();
		byte[] preamble = writer.toByteArray();
		WasmComponentBuilder.appendUserImports(writer, List.of(), 0, 0, 0, 0);
		assertThat(writer.toByteArray()).isEqualTo(preamble);
		assertThat(WasmComponentBuilder.userImportTypes(List.of())).isZero();
	}

	@Test
	void anInterfaceThatUsesNoForeignTypeProjectsNothing() {
		// The cross-interface projection is INERT for an interface that uses nothing from
		// another: it costs no alias and no extra component type, so the type cursor the
		// fixed WASI wiring downstream is compiled against shifts by the import count
		// alone
		// -- which is what keeps every component emitted before the projection existed
		// byte-identical.
		WasmComponentImportCompiler.Import kv = parseImport("wasi:keyvalue/store@0.2.0-draft", KV_WIT,
				"(\"open\" \"kv::%open\")", "(\"bucket-get\" \"kv::%bucket-get\")");
		assertThat(WitComponentTypeEncoder.foreignResourcesOf(kv)).isEmpty();
		assertThat(WasmComponentBuilder.userImportTypes(List.of(kv))).isEqualTo(1);
		// Its `bucket` is its own, so the instance type DECLARES the resource rather than
		// aliasing one in from the enclosing component.
		List<Section> sections = sections(keyvalueComponent());
		byte[] instanceType = sections.get(instanceTypeSectionIndex(sections, "wasi:keyvalue/store@0.2.0-draft"))
			.payload();
		assertThat(instanceType).containsSequence(ComponentWriter.instanceDeclExportResource("bucket"));
		assertThat(containsBytes(instanceType, ALIAS_OUTER_TYPE)).isFalse();
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

	@Test
	void aUsedResourceIsTheDefiningInterfacesOwnType() {
		// A component-model resource is NOMINAL, so `bag`'s `token` must BE the `token`
		// of
		// `tokens`: the component projects it out of tokens' imported instance and bag's
		// instance type points AT that projection. Declaring a second `(sub resource)` of
		// the same name inside bag -- which is what a structural walk through the `use`
		// clause produces -- mints an unrelated type instead: the host's real `bag`
		// instance
		// has no such export to satisfy, and a handle minted by one interface would index
		// the other's table.
		List<Section> sections = sections(tokensAndBagComponent(true));
		// The provider is imported FIRST: the projection can only name an instance that
		// already exists.
		assertThat(importSectionIndex(sections, TOKENS_ID)).isLessThan(importSectionIndex(sections, BAG_ID));
		int tokensType = importedInstanceTypeIndex(sections, TOKENS_ID);
		int bagType = importedInstanceTypeIndex(sections, BAG_ID);
		// The three component types the pair costs: tokens' instance type, the `token`
		// projected out of its instance, then bag's instance type.
		assertThat(bagType).isEqualTo(tokensType + 2);
		// The projection: one alias of the type export "token" out of tokens' instance,
		// written right before bag's instance type. The instance index is the only
		// operand
		// left free here -- it counts the blob variant's own fixed WASI imports.
		Section projection = sections.get(instanceTypeSectionIndex(sections, BAG_ID) - 1);
		assertThat(projection.id()).isEqualTo(ComponentWriter.SEC_ALIAS);
		int tokensInstance = projection.payload()[3];
		assertThat(projection.payload())
			.isEqualTo(ComponentWriter.vec(List.of(ComponentWriter.aliasInstanceType(tokensInstance, "token"))));
		byte[] tokensInstanceType = sections.get(instanceTypeSectionIndex(sections, TOKENS_ID)).payload();
		byte[] bagInstanceType = sections.get(instanceTypeSectionIndex(sections, BAG_ID)).payload();
		// tokens DEFINES the resource; bag ALIASES the projected type in (and re-exports
		// it
		// under the name its `use` clause gives it) instead of declaring a second one.
		assertThat(tokensInstanceType).containsSequence(ComponentWriter.instanceDeclExportResource("token"));
		assertThat(bagInstanceType).containsSequence(ComponentWriter.instanceDeclAliasOuterType(1, tokensType + 1));
		assertThat(containsBytes(bagInstanceType, ComponentWriter.instanceDeclExportResource("token"))).isFalse();
	}

	@Test
	@EnabledIf("am.ik.rontolisp.codegen.wasm.WitOracleE2eTest#wasmToolsIsAvailable")
	void theCrossInterfaceComponentValidates(@TempDir Path tempDir) throws Exception {
		// Resource identity across two instance imports is the component model's rule,
		// not
		// ours, so the byte-level pins above are only worth what a real validator says of
		// them. Runs where a wasm-tools binary is on PATH (like WitOracleE2eTest).
		Path file = tempDir.resolve("tokens-bag.wasm");
		Files.write(file, tokensAndBagComponent(true));
		Process process = new ProcessBuilder("wasm-tools", "validate", "--features", "all", file.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as(output).isZero();
	}

	@Test
	void theProviderIsImportedFirstWhicheverOrderTheDirectivesAreWrittenIn() {
		// Source order is the user's business; the wiring's order is not negotiable (a
		// dependent's instance type aliases a type out of an instance that must already
		// exist). WasmComponentImportCompiler.inDependencyOrder sorts the imports ONCE,
		// where they are collected, so the component types, the synthesized core
		// instances,
		// the core module's import fields and its instantiation arguments cannot disagree
		// about it -- and the two spellings emit the very same component.
		assertThat(tokensAndBagComponent(false)).isEqualTo(tokensAndBagComponent(true));
	}

	@Test
	void anInterfaceMayBeImportedForItsTypesAlone() {
		// wasi:io/error exists in a fetch component purely to own the `error` resource
		// that
		// wasi:io/streams' `stream-error` carries: nothing calls its one function.
		// Rejecting
		// an import whose functions the program never calls would make that component
		// unbuildable, so the component path defers the judgement to appendUserImports --
		// the only place that sees every import and can tell a type provider from a
		// mistake.
		List<LispVal> forms = WitImportDirective.lower(directive(TOKENS_ID, "tk"), TOKENS_WIT, "kv.wit",
				WitExportDirective.Backend.WASM_COMPONENT, java.util.Set.of());
		assertThat(forms.get(1).print()).startsWith("(rontolisp::%component-import \"" + TOKENS_ID + "\"")
			.doesNotContain("token-value");
		// On every other backend an interface is a set of callable functions and nothing
		// else, so an unused one stays a mistake worth naming.
		assertThatThrownBy(() -> WitImportDirective.lower(directive(TOKENS_ID, "tk"), TOKENS_WIT, "kv.wit",
				WitExportDirective.Backend.WASM_GC, java.util.Set.of()))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("the program calls none of its functions");
		// And the component really imports it, with a resource-only instance type: an
		// instance can only be projected from for a name it EXPORTS, so the resource has
		// to
		// be declared even though no signature of this interface mentions it.
		byte[] component = compileComponent(
				"(defpackage bg (:use cl) (:export take))\n" + importForm(TOKENS_ID, TOKENS_WIT)
						+ importForm(BAG_ID, TOKENS_WIT, "(\"take\" \"bg:take\")") + "(print (bg:take))\n");
		List<Section> sections = sections(component);
		assertThat(sections.get(instanceTypeSectionIndex(sections, TOKENS_ID)).payload()).isEqualTo(ComponentWriter.vec(
				List.of(ComponentWriter.instanceTypeOf(List.of(ComponentWriter.instanceDeclExportResource("token"))))));
	}

	@Test
	void rejectsAnInterfaceImportNothingUses() {
		// The type-only exemption above is not a blanket one: an interface the program
		// neither calls nor uses a type of is dead weight the component would still have
		// to
		// be given at instantiation.
		assertThatThrownBy(() -> compileComponent(importForm("local:webgl/gl@0.1.0", GL_WIT) + "(print 1)\n"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("local:webgl/gl@0.1.0")
			.hasMessageContaining(
					"the program calls none of its functions, and no other imported interface uses its types");
	}

	@Test
	void rejectsAUsedResourceWhoseOwningInterfaceIsNotImported() {
		// Nothing downstream would say so in words: the encoder would have no component
		// type
		// index to alias `token` in from, and a structural re-declaration is exactly the
		// wrong answer (see aUsedResourceIsTheDefiningInterfacesOwnType). So the error
		// names
		// the interface to add.
		assertThatThrownBy(() -> compileComponent("(defpackage bg (:use cl) (:export take))\n"
				+ importForm(BAG_ID, TOKENS_WIT, "(\"take\" \"bg:take\")") + "(print (bg:take))\n"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("cannot bind the resource 'token', which it uses from '" + TOKENS_ID + "'")
			.hasMessageContaining("(rontolisp:wit-import ... :interface \"" + TOKENS_ID + "\")");
	}

	@Test
	void dropsAResourceThroughCanonResourceDropAndAnOrdinaryCoreImport() {
		// A drop is not a WIT function, so there is no instance function to alias and
		// nothing to `canon lower`: the outer side is a SECOND emission kind -- project
		// the
		// resource type out of the instance that owns it, then `canon resource.drop` it,
		// which produces a CORE function with no component function behind it. The core
		// side, by contrast, is an ordinary host import, so the placeholder-ordinal /
		// WasmImportInjector machinery is reused unchanged. The two sides meet BY NAME
		// through the synthesized core instance, so their orders are independent.
		List<Section> sections = sections(tokensDropComponent());
		int imported = importSectionIndex(sections, TOKENS_ID);
		int tokensType = importedInstanceTypeIndex(sections, TOKENS_ID);
		// Right after the one import, in this order: the bound functions' aliases, their
		// canon lowers, then the drop's alias and its canon, then the core instance.
		Section alias = sections.get(imported + 3);
		Section canon = sections.get(imported + 4);
		Section coreInstance = sections.get(imported + 5);
		assertThat(alias.id()).isEqualTo(ComponentWriter.SEC_ALIAS);
		// The instance index is the only operand left free here -- it counts the blob
		// variant's own fixed WASI imports.
		int instance = alias.payload()[3];
		assertThat(alias.payload())
			.isEqualTo(ComponentWriter.vec(List.of(ComponentWriter.aliasInstanceType(instance, "token"))));
		// `canon resource.drop` names a TYPE, and it is the one that projection produced:
		// the index right after the instance type the import points at.
		assertThat(canon.id()).isEqualTo(ComponentWriter.SEC_CANON);
		assertThat(canon.payload())
			.isEqualTo(ComponentWriter.vec(List.of(ComponentWriter.canonResourceDrop(tokensType + 1))));
		assertThat(coreInstance.id()).isEqualTo(ComponentWriter.SEC_CORE_INSTANCE);
		assertThat(containsAscii(coreInstance.payload(), "[resource-drop]token")).isTrue();
		// The core module imports it like any other host function: module = the canonical
		// interface id, field = "[resource-drop]token", type (func (param i32)) -- the
		// i31
		// handle, unboxed.
		assertThat(containsBytes(coreModule(sections), coreFuncImport(TOKENS_ID, "[resource-drop]token"))).isTrue();
		assertThat(WasmComponentImportCompiler.dropParamTypes()).containsExactly(Type.I32);
	}

	@Test
	void aDropCostsACoreFunctionAndNoComponentFunction() {
		// THE trap. `canon resource.drop` produces a core function directly, so a drop
		// adds
		// to the CORE function index space and not to the component one -- and the single
		// number the user imports used to cost becomes two. canonLift's operand and
		// appendFuncExports' core cursor must count the drops;
		// componentInstanceFromFunc's
		// must not. Get it backwards and you get a component that VALIDATES while lifting
		// the wrong core function.
		WasmComponentImportCompiler.Import tokens = parseImport(TOKENS_ID, TOKENS_WIT,
				"(\"token-value\" \"tk:token-value\")", "(:drop \"token\" \"tk:token-drop\")");
		assertThat(tokens.decls()).hasSize(1);
		assertThat(tokens.drops()).hasSize(1);
		assertThat(WasmComponentBuilder.userImportFuncs(List.of(tokens))).isEqualTo(1);
		assertThat(WasmComponentBuilder.userImportCoreFuncs(List.of(tokens))).isEqualTo(2);
		// The TYPE space grows too: the instance type, plus the `token` the drop's canon
		// names.
		assertThat(WasmComponentBuilder.userImportTypes(List.of(tokens))).isEqualTo(2);
	}

	@Test
	@EnabledIf("am.ik.rontolisp.codegen.wasm.WitOracleE2eTest#wasmToolsIsAvailable")
	void theComponentImportingADropValidates(@TempDir Path tempDir) throws Exception {
		// The counter split above is only worth what a real validator says of it: a
		// mis-split lift yields a component that fails to validate -- or, worse, one that
		// validates while lifting the wrong function. This is also what cross-checks the
		// core import's (func (param i32)) against the type `canon resource.drop` gives
		// its
		// core function. Runs where a wasm-tools binary is on PATH (like
		// WitOracleE2eTest).
		Path file = tempDir.resolve("token-drop.wasm");
		Files.write(file, tokensDropComponent());
		Process process = new ProcessBuilder("wasm-tools", "validate", "--features", "all", file.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as(output).isZero();
	}

	@Test
	void aProgramThatNamesNoDropCompilesToTheBytesItAlwaysDid() {
		// The whole byte-identity property rests on ONE rule: a drop binds only when the
		// program names it. So the same source, compiled without the `-drop` call, must
		// reach the backend with no drop member at all -- and come out as the very bytes
		// a
		// component built before drops existed did, which is what the hand-written import
		// form (the shape the directive emitted then) stands in for here.
		byte[] preDrops = compileComponent("(defpackage tk (:use cl) (:export token-value))\n"
				+ importForm(TOKENS_ID, TOKENS_WIT, "(\"token-value\" \"tk:token-value\")")
				+ "(print (tk:token-value 1))\n");
		assertThat(tokensComponentNaming(false)).isEqualTo(preDrops);
		assertThat(containsAscii(preDrops, "resource-drop")).isFalse();
		// And naming it really does move the bytes -- otherwise the pin above would hold
		// for a feature that does nothing.
		assertThat(tokensComponentNaming(true)).isNotEqualTo(preDrops);
	}

	@Test
	void aResourceThatIsOnlyDroppedIsStillDeclaredInTheInstanceType() {
		// The type encoder declares a resource LAZILY -- only when a bound function's
		// signature reaches one -- and a program may bind nothing of an interface but a
		// drop. The drop has to force the declaration: `canon resource.drop` projects the
		// resource out of this instance, and an instance can only be projected from for a
		// name it EXPORTS. It also makes such an import legal at all, since an interface
		// whose functions the program never calls is otherwise dead weight.
		byte[] component = compileComponent("(defpackage tk (:use cl) (:export token-drop))\n"
				+ importForm(TOKENS_ID, TOKENS_WIT, "(:drop \"token\" \"tk:token-drop\")") + "(tk:token-drop 1)\n");
		List<Section> sections = sections(component);
		assertThat(sections.get(instanceTypeSectionIndex(sections, TOKENS_ID)).payload()).isEqualTo(ComponentWriter.vec(
				List.of(ComponentWriter.instanceTypeOf(List.of(ComponentWriter.instanceDeclExportResource("token"))))));
		assertThat(containsAscii(component, "[resource-drop]token")).isTrue();
	}

}
