package am.ik.rontolisp.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.wit.WitFunc;
import am.ik.wit.WitItem;
import am.ik.wit.WitParser;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@code examples/browser/webgl-common/gl-imports.js} against
 * {@code examples/browser/webgl-common/gl.wit}: the browser demos' host import object is
 * GENERATED from the same file their Lisp binds with {@code rontolisp:wit-import}, so the
 * two sides of the boundary cannot drift apart. Before this, each page hand-wrote its
 * import object against gl.lisp's declarations with nothing checking the two agreed.
 *
 * <p>
 * The field names come from {@link WitImportDirective.FieldStyle#CAMEL} -- the very
 * function the Preview 1 lowering uses -- so a page can only be handed the names the
 * module actually imports. {@link WitImportDirectiveTest} pins that lowering itself; this
 * test pins the JavaScript half.
 *
 * <p>
 * Regenerate after editing {@code gl.wit} with
 * {@code ./mvnw -Drontolisp.gl.fix=true -Dtest=GlImportObjectTest#fixGlImports test}.
 */
class GlImportObjectTest {

	private static final Path WIT = Path.of("examples/browser/webgl-common/gl.wit");

	private static final Path JS = Path.of("examples/browser/webgl-common/gl-imports.js");

	private static final String FIX = "./mvnw -Drontolisp.gl.fix=true -Dtest=GlImportObjectTest#fixGlImports test";

	@Test
	@DisabledIfSystemProperty(named = "rontolisp.gl.fix", matches = "true")
	void theCheckedInImportObjectIsWhatGlWitGenerates() throws IOException {
		assertThat(Files.readString(JS, StandardCharsets.UTF_8)).as("%s is stale -- regenerate it with: %s", JS, FIX)
			.isEqualTo(generate());
	}

	/**
	 * Maintenance helper: rewrites the generated import object from {@code gl.wit}.
	 * Enabled only with {@code -Drontolisp.gl.fix=true}.
	 * @throws IOException if the files cannot be read or written
	 */
	@Test
	@EnabledIfSystemProperty(named = "rontolisp.gl.fix", matches = "true")
	void fixGlImports() throws IOException {
		Files.writeString(JS, generate(), StandardCharsets.UTF_8);
		System.out.println("Wrote " + JS);
	}

	// --- the generator ---------------------------------------------------------------

	// How one WIT type crosses to JavaScript. A NAMED type resolving to an alias of s32
	// is the page's object table handle (gl.wit's `type shader = s32`); a bare s32 is a
	// plain value. That distinction is the whole reason the aliases exist -- the two
	// lower identically, so only the name tells a reader, or this generator, which
	// integers have to be looked up in `handles`.
	private enum Cross {

		HANDLE, VALUE, BOOL, STRING

	}

	private static String generate() throws IOException {
		WitResolver resolver = new WitResolver(WitParser.parse(Files.readString(WIT, StandardCharsets.UTF_8)));
		StringBuilder out = new StringBuilder();
		out.append("""
				// GENERATED from gl.wit -- do not edit.
				//
				// The host side of the WebGL2 boundary the examples/browser/webgl-* demos are
				// written against: one entry per function of gl.wit, which is the same file
				// gl.lisp binds with rontolisp:wit-import. Both halves of every name, and the
				// handle/string plumbing around it, are derived from that one declaration, so
				// the page can no longer provide a field the module does not import (or spell
				// one differently) without the WIT saying so.
				//
				// Each factory below takes the page's own host plumbing and returns a plain
				// object of plain functions, so a page spreads it into its import object and
				// adds its own demo-specific staging entries beside it:
				//
				//   gl: { ...glImports({ gl, handles, addHandle, str, retStr }),
				//         setVertex: (i, x, y) => { ... } },
				//
				// A later property wins, so a page that needs a different implementation of a
				// generated entry can simply restate it after the spread.
				//
				// Regenerate with:
				//   ./mvnw -Drontolisp.gl.fix=true -Dtest=GlImportObjectTest#fixGlImports test
				""");
		for (String id : resolver.interfaceIds()) {
			WitItem.InterfaceDef iface = resolver.findInterface(id);
			if (iface == null) {
				continue;
			}
			out.append('\n').append(interfaceFactory(resolver, iface));
		}
		return out.toString();
	}

	// One `export function <name>Imports({ ... })` per WIT interface: the host object it
	// dispatches to is named after the interface, and the rest of the dependencies are
	// exactly the ones its functions turn out to need.
	private static String interfaceFactory(WitResolver resolver, WitItem.InterfaceDef iface) {
		List<String> entries = new ArrayList<>();
		Set<String> deps = new LinkedHashSet<>();
		deps.add(iface.name());
		for (WitResolver.Func func : WitResolver.functions(iface)) {
			entries.add(entry(resolver, iface, func.def(), deps));
		}
		StringBuilder out = new StringBuilder();
		out.append("/** The `").append(iface.name()).append("` import module, one entry per gl.wit function. */\n");
		out.append("export function ")
			.append(iface.name())
			.append("Imports({ ")
			.append(String.join(", ", deps))
			.append(" }) {\n  return {\n");
		for (String entry : entries) {
			out.append(wrapped(entry)).append(",\n");
		}
		out.append("  };\n}\n");
		return out.toString();
	}

	// An entry too long for one line breaks after the arrow, onto a continuation indent
	// --
	// the shape the demos' hand-written import objects already used for their widest
	// entries (vertexAttribPointer was always two lines).
	private static String wrapped(String entry) {
		String oneLine = "    " + entry;
		if (oneLine.length() + 1 <= 100) {
			return oneLine;
		}
		int arrow = entry.indexOf(") => ");
		return "    " + entry.substring(0, arrow + 4) + "\n      " + entry.substring(arrow + 5);
	}

	// `createShader: (kind) => addHandle(gl.createShader(kind)),` -- the JS parameter
	// list, the arguments the host call gets, and the wrapper around its result are each
	// a direct reading of the WIT signature.
	private static String entry(WitResolver resolver, WitItem.InterfaceDef iface, WitItem.FuncDef def,
			Set<String> deps) {
		String field = WitImportDirective.FieldStyle.CAMEL.apply(def.name());
		List<String> params = new ArrayList<>();
		List<String> args = new ArrayList<>();
		for (WitFunc.Param param : def.func().params()) {
			String name = WitImportDirective.FieldStyle.CAMEL.apply(param.name());
			switch (cross(resolver, iface, param.type())) {
				case STRING -> {
					// A string crosses Preview 1 as the (pointer, length) pair the page
					// reads out of the module's exported linear memory.
					params.add(name);
					params.add(name + "Len");
					args.add("str(" + name + ", " + name + "Len)");
					deps.add("str");
				}
				case HANDLE -> {
					params.add(name);
					args.add("handles[" + name + "]");
					deps.add("handles");
				}
				case BOOL -> {
					params.add(name);
					args.add("!!" + name);
				}
				case VALUE -> {
					params.add(name);
					args.add(name);
				}
			}
		}
		String call = iface.name() + "." + field + "(" + String.join(", ", args) + ")";
		WitType result = def.func().result();
		String body = switch (result == null ? Cross.VALUE : cross(resolver, iface, result)) {
			// A returned handle is registered in the page's table; the module only ever
			// sees the index. A returned string is copied back through the module's bump
			// allocator -- and WebGL answers null rather than "" for an empty info log.
			case HANDLE -> {
				deps.add("addHandle");
				yield "addHandle(" + call + ")";
			}
			case STRING -> {
				deps.add("retStr");
				yield "retStr(" + call + " ?? \"\")";
			}
			default -> call;
		};
		return field + ": (" + String.join(", ", params) + ") => " + body;
	}

	private static Cross cross(WitResolver resolver, WitItem.InterfaceDef iface, WitType type) {
		return switch (type) {
			case WitType.Prim prim -> switch (prim.name()) {
				case "string" -> Cross.STRING;
				case "bool" -> Cross.BOOL;
				case "s32", "u32", "s16", "u16", "s8", "u8", "f32", "f64" -> Cross.VALUE;
				default -> throw new IllegalStateException(
						WIT + ": the WIT type '" + prim.name() + "' does not cross to a browser import object");
			};
			case WitType.Named named -> {
				// gl.wit's handle aliases. Anything else named would need a real
				// representation decision, which this generator deliberately does not
				// invent.
				WitItem resolved = resolver.resolveType(iface, named.name());
				if (resolved instanceof WitItem.TypeAlias alias && alias.target() instanceof WitType.Prim prim
						&& prim.name().equals("s32")) {
					yield Cross.HANDLE;
				}
				throw new IllegalStateException(
						WIT + ": '" + named.name() + "' is not an s32 handle alias, so it has no import-object shape");
			}
			default -> throw new IllegalStateException(
					WIT + ": the WIT type " + type + " does not cross to a browser import object");
		};
	}

}
