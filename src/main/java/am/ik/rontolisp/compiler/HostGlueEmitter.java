package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The JavaScript half of a {@code --no-wasi} module's host boundary, WRITTEN from the
 * program's own declarations ({@code --emit-js-glue}; {@code .kb/wasm-import.md}).
 *
 * <p>
 * The precedent is {@code examples/browser/webgl-common/gl-imports.js}: the pages' import
 * object is generated from the same WIT the module was compiled against, so the two
 * halves of the boundary cannot drift. Everything here is derivable the same way -- a
 * {@code rontolisp:wasm-import} states the import-object key ({@code :from}), the
 * property ({@code :as}) and the shape of every value crossing it; a
 * {@code rontolisp:wasm-export} states the entry point and its shape; and
 * {@link SuspendingImports} states which of those entry points must be entered through
 * {@code WebAssembly.promising} once the host suspends. What is left is the only thing a
 * declaration cannot say -- what a host function DOES -- and that is precisely what the
 * generated {@code instantiate} asks its caller for.
 *
 * <p>
 * <strong>Except where the TRANSPORT already fixed it.</strong> Two halves of a
 * {@code --no-wasi} reactor's boundary are not the program's choice at all, and where a
 * build carries both, the file writes them and the Worker is three lines
 * ({@code Surface#derivedFetch} / {@code Surface#envelopeExport}): {@code --host-fetch}
 * states both directions of {@code env.fetch} ({@link FetchResponseShape}), so its host
 * half is the same twenty lines in every program; and the reactor's entry point takes the
 * JSON envelope ({@link ReactorEnvelope}), so mapping a {@code Request} onto it and a
 * {@code Response} off it is transport work. Both are DEFAULTS, never replacements -- a
 * host still supplies its own {@code env.fetch}, or drives {@code instantiate} directly,
 * and the generated {@code worker} lays whatever it is given over the derived entries one
 * at a time. Both halves are written on the STREAMING boundary too: inside
 * {@code worker()} the reader the octets come from is the platform {@code Request} it is
 * already holding and the {@code Response} it is already building, so the body imports
 * are per-call state {@code worker()} owns rather than something to ask for; only a host
 * driving {@code instantiate} directly still owns them itself.
 *
 * <p>
 * <strong>Whether an import really suspends is the HOST's decision, not the
 * declaration's.</strong> {@code :async t} says the module TOLERATES a suspension there
 * (the call answers a future either way), and a host answering synchronously is equally
 * valid -- the shipped Workers do both in one import object. Nor is a
 * {@code WebAssembly.Suspending} wrapper free: measured on node 24 JSPI, an import that
 * answers SYNCHRONOUSLY through one still parks the stack and returns to the event loop,
 * so a second call entering meanwhile is refused by the module's re-entry guard. The
 * generated file therefore wraps exactly the entries the host marks with the
 * {@code suspending()} helper it exports, and turns the {@code promising} entries and the
 * serialisation queue on only when at least one is marked.
 */
public final class HostGlueEmitter {

	/**
	 * One {@code rontolisp:wasm-import} as the host sees it.
	 *
	 * @param module the import-object key ({@code :from})
	 * @param field the import-object property ({@code :as})
	 * @param paramTypes the declared parameter types, in order
	 * @param returnType the declared result type ({@link BoundaryType#VOID} for none)
	 */
	public record Import(String module, String field, List<BoundaryType> paramTypes, BoundaryType returnType) {
	}

	/**
	 * One {@code rontolisp:wasm-export} as the host calls it.
	 *
	 * @param exportName the WASM export name ({@code :as})
	 * @param paramTypes the declared parameter types, in order
	 * @param returnType the declared result type ({@link BoundaryType#VOID} for none)
	 * @param promising whether a call chain from it can reach an import the host may
	 * suspend, i.e. whether it has to be entered through {@code WebAssembly.promising}
	 */
	public record Export(String exportName, List<BoundaryType> paramTypes, BoundaryType returnType, boolean promising) {
	}

	/**
	 * A module's whole host-facing surface: the derived facts this emitter writes from,
	 * computed once by the backend that also prints the obligation lines.
	 *
	 * @param imports the declared host imports, in declaration order
	 * @param entropy the {@code --host-random} entropy import, which the glue IMPLEMENTS
	 * rather than asks the host for (preview1 fixes what {@code random_get(buf, len)}
	 * does, and writing into linear memory is this side's job); {@code null} without the
	 * flag
	 * @param exports the declared host-callable entry points, in declaration order
	 * @param arena whether the module exports the {@code __ronto_alloc_mark} /
	 * {@code __ronto_alloc_reset} bracket
	 * @param seedRandom whether it exports {@code __ronto_seed_random}
	 * @param setTime whether it exports {@code __ronto_set_time}
	 * @param initExport the top-level entry point a host runs once after instantiation
	 * ({@code _initialize}), or {@code null} when the module has none
	 * @param derivedFetch whether the {@code --host-fetch} import is one this file can
	 * IMPLEMENT rather than ask for: both directions of {@code env.fetch} are fixed by
	 * {@link FetchResponseShape}, so its host half is the same twenty lines in every
	 * program -- but only once the reply body rides the head too
	 * ({@code --host-boundary=envelope}). With the body out of band the host owns the
	 * reader the octets come from, which is exactly what a declaration cannot state
	 * @param envelopeExport the reactor entry point whose boundary is the JSON envelope
	 * ({@link ReactorEnvelope}) -- on EITHER body boundary, since {@code worker()} fills
	 * the body imports too -- so mapping a {@code Request} onto it and a {@code Response}
	 * off it is transport work this file can write; {@code null} when the module has no
	 * such entry point
	 * @param reentrant whether the module was compiled {@code --reentrant}: it owns its
	 * per-call state, so this file drops the serialisation queue (calls overlap freely),
	 * pops the argument staging synchronously at the call instead of after it, stages
	 * cross-call buffers through {@code __ronto_park_alloc}, and frees a
	 * {@code :string}/{@code :s-expr} result's park block with {@code __ronto_park_free}
	 * after decoding it
	 */
	public record Surface(List<Import> imports, @Nullable Import entropy, List<Export> exports, boolean arena,
			boolean seedRandom, boolean setTime, @Nullable String initExport, boolean derivedFetch,
			@Nullable String envelopeExport, boolean reentrant) {

		/**
		 * Every import-object entry, whoever implements it, grouped by module in order.
		 */
		Map<String, List<Import>> byModule() {
			Map<String, List<Import>> groups = new LinkedHashMap<>();
			for (Import imp : this.imports) {
				groups.computeIfAbsent(imp.module(), m -> new ArrayList<>()).add(imp);
			}
			if (this.entropy != null) {
				groups.computeIfAbsent(this.entropy.module(), m -> new ArrayList<>()).add(this.entropy);
			}
			// Identical declarations of one host function are ONE import slot and one
			// entry; checkNames has already refused any pair that is not identical.
			groups.replaceAll((module, list) -> {
				List<Import> unique = new ArrayList<>();
				for (Import imp : list) {
					if (unique.stream().noneMatch(seen -> seen.field().equals(imp.field()))) {
						unique.add(imp);
					}
				}
				return unique;
			});
			return groups;
		}

		/** Whether the HOST has to supply anything -- the entropy entry it never does. */
		boolean needsHost() {
			return !this.imports.isEmpty();
		}

	}

	/**
	 * The first bytes of every emitted file, and the only thing that identifies one: the
	 * CLI refuses to overwrite a {@code .js} beside the output that does not start with
	 * it, because that file is the host's own.
	 */
	public static final String MARKER = "// GENERATED by rontolisp --emit-js-glue -- do not edit.";

	/** The receive buffer a {@code :bytes}-returning EXPORT is asked to fill first. */
	private static final int BYTES_BUFFER = 65536;

	private HostGlueEmitter() {
	}

	/**
	 * Emits the host glue for the given surface.
	 * @param fileName the glue's own file name, so the usage sketch names the real import
	 * @param surface the module's declared host-facing surface
	 * @return the ES module source
	 * @throws UnsupportedOperationException if two exports would claim one JavaScript
	 * name
	 */
	public static String emit(String fileName, Surface surface) {
		checkNames(surface);
		StringBuilder out = new StringBuilder();
		header(out, fileName, surface);
		out.append("""

				const encoder = new TextEncoder();
				// ignoreBOM, because these octets are a VALUE and not a document: a
				// leading U+FEFF is a character the other side chose, and the default
				// decoder deletes it -- silently shortening a BOM-prefixed request body
				// by one character while the content-length beside it still counts three.
				const decoder = new TextDecoder("utf-8", { ignoreBOM: true });

				const SUSPENDING = Symbol.for("rontolisp.suspending");
				""");
		if (canSuspend(surface)) {
			out.append("""

					/**
					 * Marks a host function as one that answers a promise, so the wasm stack parks
					 * until it settles (JSPI). The wrapper is not free -- an import answering
					 * SYNCHRONOUSLY through one still returns to the event loop -- so mark only the
					 * entries this host really implements asynchronously.
					 *
					 * @param {Function} fn the host function
					 * @returns {object} the marked entry, to be passed as the import
					 */
					export function suspending(fn) {
					  return { [SUSPENDING]: fn };
					}
					""");
		}
		instantiate(out, surface);
		defaultHost(out, surface);
		worker(out, fileName, surface);
		return out.toString();
	}

	// Two exports whose names differ only where the camel-casing erases the difference
	// would claim one property of the returned object, and the second would win in
	// silence. Refuse instead -- an export name is the host's spelling and is the
	// program's to change.
	private static void checkNames(Surface surface) {
		Set<String> seen = new LinkedHashSet<>();
		for (Export export : surface.exports()) {
			String name = jsName(export.exportName());
			if (!identifier(name)) {
				throw new UnsupportedOperationException("--emit-js-glue: the export name '" + export.exportName()
						+ "' becomes '" + name + "', which is not a JavaScript identifier, so the glue could not"
						+ " declare an entry point for it -- give it a rontolisp:wasm-export :as alias that is one");
			}
			if (!seen.add(name)) {
				throw new UnsupportedOperationException("--emit-js-glue: the exports " + seen + " and '"
						+ export.exportName() + "' both become the JavaScript name '" + name
						+ "' -- give one of them a different rontolisp:wasm-export :as alias");
			}
		}
		// An import's module and field are written into a JS string literal AND an object
		// key, so anything but a bare name is refused rather than escaped: a host's
		// spelling has to cross exactly, and a quote in one would make the file a
		// SyntaxError the build never noticed.
		Map<String, Import> byKey = new LinkedHashMap<>();
		for (Import imp : surface.imports()) {
			if (!identifier(imp.module()) || !identifier(imp.field())) {
				throw new UnsupportedOperationException("--emit-js-glue: the import '" + imp.module() + "."
						+ imp.field() + "' does not spell a JavaScript name, so the glue cannot write an import"
						+ " object for it -- give the directive a :from / :as that does");
			}
			Import first = byKey.putIfAbsent(imp.module() + "." + imp.field(), imp);
			// The core module collapses several declarations onto ONE import slot per
			// (module, field), so two of them are ONE host function -- and only its last
			// declared shape would survive the object literal, silently unpacking every
			// caller of both. Identical shapes are the intended collapse and are merged;
			// anything else is a program that cannot mean what it says.
			if (first != null
					&& !(first.paramTypes().equals(imp.paramTypes()) && first.returnType() == imp.returnType())) {
				throw new UnsupportedOperationException("--emit-js-glue: two rontolisp:wasm-import declarations share"
						+ " the host function '" + imp.module() + "." + imp.field() + "' with different shapes ("
						+ signature(first.paramTypes(), first.returnType()) + " and "
						+ signature(imp.paramTypes(), imp.returnType())
						+ "), but the module imports it ONCE -- give one of them a different :as field");
			}
		}
	}

	// A bare JavaScript name: what may be written as an identifier AND as an unquoted
	// object key. Deliberately narrow (no unicode escapes, no reserved words) -- this
	// decides what the emitter refuses, and a name it cannot write is the program's to
	// change.
	private static boolean identifier(String name) {
		if (name.isEmpty() || RESERVED.contains(name) || Character.isDigit(name.charAt(0))) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean ascii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
			if (!ascii && c != '_' && c != '$') {
				return false;
			}
		}
		return true;
	}

	/** The words a generated identifier may not be. */
	private static final Set<String> RESERVED = Set.of("await", "break", "case", "catch", "class", "const", "continue",
			"debugger", "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for",
			"function", "if", "implements", "import", "in", "instanceof", "interface", "let", "new", "null", "package",
			"private", "protected", "public", "return", "static", "super", "switch", "this", "throw", "true", "try",
			"typeof", "var", "void", "while", "with", "yield");

	// The file's own explanation: what it is, what it asks its caller for, and the call
	// shape a reader needs before reading any of it. Derived like the rest -- the sketch
	// names THIS module's imports and entry points, not a generic pair of them.
	private static void header(StringBuilder out, String fileName, Surface surface) {
		out.append(MARKER).append("""

				//
				// The host half of this module's boundary, derived from the program's own
				// declarations: one import-object entry per rontolisp:wasm-import, one entry
				// point per rontolisp:wasm-export, and every piece of linear-memory plumbing
				// between them -- the (ptr, len) pair a :string crosses as""");
		out.append(hasReader(surface) ? """
				, the __ronto_alloc
				// bracket around a call, and the read(2) cursor a :bytes result is pulled
				// through.
				""" : """
				 and the __ronto_alloc
				// bracket around a call.
				""");
		out.append("""
				//
				// What a declaration cannot state is what a host function DOES, so that is the
				// one thing this file asks for: `host` is a plain function per import, keyed by
				// import module and field, taking and answering ordinary JavaScript values --
				// never a (ptr, len) pair""");
		out.append(hasReader(surface) ? """
				, and, where an entry answers `chunk` below, a
				// Uint8Array or a string with null for the end of them.
				""" : ".\n");
		out.append("//\n");
		out.append("//   import { instantiate")
			.append(canSuspend(surface) ? ", suspending" : "")
			.append(" } from \"./")
			.append(fileName)
			.append("\";\n//\n");
		out.append("//   const lisp = instantiate(module").append(surface.needsHost() ? ", {\n" : ");\n");
		String module = null;
		for (Import imp : surface.imports()) {
			if (!imp.module().equals(module)) {
				if (module != null) {
					out.append("//     },\n");
				}
				module = imp.module();
				out.append("//     ").append(jsKey(module)).append(": {\n");
			}
			out.append("//       ").append(jsKey(imp.field())).append(": ").append(sketch(imp)).append(",");
			// An entry the file IMPLEMENTS is still the host's to override, so it stays
			// in the sketch -- with the note that supplying it is optional.
			String answered = answeredBy(surface, imp);
			out.append(answered == null ? "\n" : "   // or leave it to " + answered + "\n");
		}
		if (module != null) {
			out.append("//     },\n//   });\n");
		}
		for (Export export : surface.exports()) {
			out.append("//   ")
				.append(export.promising() ? "await " : "")
				.append("lisp.")
				.append(jsName(export.exportName()))
				.append("(")
				.append(callSketch(export))
				.append(");\n");
		}
		if (canSuspend(surface) && surface.reentrant()) {
			out.append("""
					//
					// A host that suspends marks its own entries -- suspending(async (...) => ...)
					// -- and every entry point above then answers a promise: the marked imports are
					// wrapped in WebAssembly.Suspending and each entry point that can reach one is
					// entered through WebAssembly.promising. This module was compiled --reentrant:
					// it owns its per-call state, so calls are NOT serialised -- overlap them
					// freely (what overlaps is the parked time; one stack still runs at a time).
					// A read import's remainder is keyed by its arguments only, so two overlapped
					// calls pulling one source through IDENTICAL arguments are the host's own
					// hazard to serialise.
					""");
		}
		else if (canSuspend(surface)) {
			out.append("""
					//
					// A host that suspends marks its own entries -- suspending(async (...) => ...)
					// -- and every entry point above then answers a promise: the marked imports are
					// wrapped in WebAssembly.Suspending, each entry point that can reach one is
					// entered through WebAssembly.promising, and calls are serialised onto one
					// promise chain, because a suspended module returns to the host's event loop
					// and a re-entered export refuses with a trap rather than corrupting both
					// calls. Host state that belongs to ONE such call is set inside that section:
					""");
			String first = jsName(surface.exports().get(0).exportName());
			out.append("//\n//   await lisp.serially(async (entry) => { ...; return entry.")
				.append(first)
				.append("(...) });\n");
		}
		if (surface.envelopeExport() != null) {
			out.append("""
					//
					// This module's boundary is the reactor envelope, so the Request/Response half
					// is derivable too and `worker` below is it:
					//
					""");
			out.append("//   import module from \"./").append(moduleFile(fileName)).append("\";\n");
			out.append("//   import { worker } from \"./").append(fileName).append("\";\n//\n");
			// The three-line form is only honest when this file implements every import
			// the module has: with one left over, worker(module) instantiates and fails
			// at the first request, and the host has to be handed in.
			out.append(selfContained(surface) ? "//   export default worker(module);\n"
					: "//   export default worker(module, { host });   // the imports above are still yours\n");
		}
		out.append("""
				//
				// Regenerate it, never edit it: --emit-js-glue on the compile that wrote the
				// .wasm beside it.
				""");
	}

	// The comment sketch of one host entry: what it is handed and what it answers, in
	// the JavaScript values the wrappers below deal in -- never the (ptr, len) pairs,
	// which are this file's business and not the host's.
	private static String sketch(Import imp) {
		List<String> params = new ArrayList<>();
		for (int i = 0; i < imp.paramTypes().size(); i++) {
			params.add(jsValueName(imp.paramTypes().get(i), i));
		}
		String args = "(" + String.join(", ", params) + ")";
		return args + " => " + switch (imp.returnType()) {
			// A :bytes result is a SOURCE of chunks: the module owns the receive buffer,
			// so what a host answers is the next chunk, and null at the end of them.
			case BYTES -> "chunk";
			case VOID -> "{}";
			case STRING, S_EXPR -> "text";
			case BOOL -> "flag";
			default -> "number";
		};
	}

	private static String callSketch(Export export) {
		List<String> params = new ArrayList<>();
		for (int i = 0; i < export.paramTypes().size(); i++) {
			params.add(jsValueName(export.paramTypes().get(i), i));
		}
		return String.join(", ", params);
	}

	private static String jsValueName(BoundaryType type, int index) {
		String base = switch (type) {
			case STRING, S_EXPR -> "text";
			case BYTES -> "chunk";
			case BOOL -> "flag";
			// An i64 crosses as a BigInt, and a plain number there is a TypeError.
			case S64, U64 -> "bigint";
			default -> "number";
		};
		return index == 0 ? base : base + (index + 1);
	}

	private static void instantiate(StringBuilder out, Surface surface) {
		out.append("""

				/**
				 * Instantiates the module against this host and returns its callable surface.
				 *
				 * @param {WebAssembly.Module} module the compiled module
				""");
		if (surface.needsHost()) {
			out.append(" * @param {object} host one function per import, keyed by module and field\n");
		}
		out.append(" * @returns {object} `exports`");
		out.append(surface.exports().isEmpty() ? ", the module's own\n */\n"
				: ", plus one entry point per rontolisp:wasm-export\n */\n");
		out.append("export function instantiate(module")
			.append(surface.needsHost() ? ", host = {}" : "")
			.append(") {\n  let exports;\n");
		memoryHelpers(out, surface);
		imports(out, surface);
		out.append("  const instance = new WebAssembly.Instance(module, imports);\n  exports = instance.exports;\n");
		startup(out, surface);
		exports(out, surface);
		out.append("}\n");
	}

	// --host-fetch's host half: what env.fetch DOES is fixed in both directions by
	// FetchResponseShape (the request record in, the response record out, the reserved
	// error key on a throw), so it is the same in every program and is written here
	// rather than asked for -- on BOTH boundaries. Where the reply body is out of band
	// the platform Response's own reader is where the octets come from, so the same
	// function that opened it answers the pull too, and the pair is still derivable.
	private static void defaultHost(StringBuilder out, Surface surface) {
		if (!writesFetch(surface)) {
			return;
		}
		boolean pulled = pullsReplyBody(surface);
		// --reentrant: one reader per REPLY rather than one cursor. Calls overlap, so
		// "the reply the last fetch opened" names nothing -- the host mints an id per
		// fetch, hands it back in the head's reserved "body-id" key, and every pull
		// says which reply it drains. Nothing is superseded, so no lisp.drop is needed
		// and the instance thunk goes with it.
		boolean perReply = pulled && surface.reentrant();
		out.append("""

				/**
				 * The half of this boundary the module's own declarations FIX, ready to hand to
				 * `instantiate` -- or to leave to `worker` below, which passes it for you. What
				 * a host is still free to do is override it: whatever it supplies wins, entry by
				 * entry.
				 *
				""");
		if (pulled && !perReply) {
			out.append("""
					 * @param {Function} lisp a thunk answering the instantiated object, or null
					 *   before there is one. Only the reply-body cursor needs it: a second fetch
					 *   inside ONE call REPLACES the reply this file is reading, and the octets
					 *   the glue is still holding belong to a reply nobody may read again --
					 *   which only this side can see.
					""");
		}
		out.append(" * @returns {object} the import-object entries this file implements itself\n */\n");
		out.append("export function defaultHost(").append(pulled && !perReply ? "lisp" : "").append(") {\n");
		if (perReply) {
			out.append("""
					  // One reader per reply, keyed by the id the head carries back: overlapped
					  // calls -- and a second fetch inside one call -- each drain their own. A
					  // drained reader is dropped; one nobody drains stays until the platform
					  // reclaims its stream.
					  let replySerial = 0;
					  const upstreams = new Map();
					""");
		}
		else if (pulled) {
			out.append("""
					  // The reply this file is currently reading. The generated cursor holds what
					  // of a chunk did not fit; this is only WHERE the octets come from.
					  let upstream = null;
					""");
		}
		out.append("  return {\n");
		out.append("    ").append(jsKey(FetchResponseShape.HOST_IMPORT_MODULE)).append(": {\n");
		out.append("      ")
			.append(jsKey(FetchResponseShape.HOST_IMPORT_FIELD))
			.append(": suspending(async (head) => {\n");
		out.append("        const request = JSON.parse(head);\n");
		if (pulled && !perReply) {
			out.append("        upstream = null;\n");
			out.append("        lisp?.()?.drop(\"")
				.append(FetchResponseShape.HOST_IMPORT_MODULE)
				.append('.')
				.append(FetchResponseShape.HOST_BODY_IMPORT_FIELD)
				.append("\");\n");
		}
		out.append("        try {\n");
		out.append("          const response = await fetch(request.").append(requestField("url")).append(", {\n");
		for (FetchResponseShape.Field field : FetchResponseShape.requestFields()) {
			if (!"url".equals(field.name())) {
				out.append("            ")
					.append(field.name())
					.append(": request.")
					.append(requestField(field.name()))
					.append(",\n");
			}
		}
		out.append("          });\n");
		if (perReply) {
			out.append("          const id = ++replySerial;\n");
			out.append("          // The reader IS the body; the module pulls it BY THIS ID afterwards.\n");
			out.append("          if (response.body) upstreams.set(id, response.body.getReader());\n");
		}
		else if (pulled) {
			out.append("          // The reader IS the body; the module pulls it after this returns.\n");
			out.append("          upstream = response.body ? response.body.getReader() : null;\n");
		}
		out.append("          return JSON.stringify({\n");
		for (FetchResponseShape.Field field : FetchResponseShape.responseFields()) {
			String value = switch (field.name()) {
				case "status" -> "response.status";
				// An ARRAY of pairs, never an object: a name may repeat.
				case "headers" -> "[...response.headers]";
				// Out of band, the head carries no body at all -- the key's ABSENCE is
				// what puts the module's stream over the import below.
				case "body" -> pulled ? null : "await response.text()";
				default -> throw new UnsupportedOperationException(
						"--emit-js-glue: the http-plist response record grew a field this host half does not answer: "
								+ field.name());
			};
			if (value != null) {
				out.append("            ").append(field.name()).append(": ").append(value).append(",\n");
			}
		}
		if (perReply) {
			out.append("            ").append(jsKey(FetchResponseShape.HOST_BODY_ID_KEY)).append(": id,\n");
		}
		out.append("          });\n");
		out.append("        } catch (error) {\n");
		out.append("          // The error arm becomes a Lisp condition at the fetch CALL; throwing\n");
		out.append("          // here would trap the instance instead, and take the request with it.\n");
		out.append("          return JSON.stringify({ ")
			.append(jsKey(FetchResponseShape.HOST_ENVELOPE_ERROR_KEY))
			.append(": String(error) });\n");
		out.append("        }\n      }),\n");
		if (perReply) {
			out.append("      ").append(jsKey(FetchResponseShape.HOST_BODY_IMPORT_FIELD)).append(": suspending(\n");
			out.append("""
					        // The next chunk of the reply the id names, null at the end of it --
					        // and for an id whose reply is already drained or was never opened.
					        // Reading a ReadableStream is asynchronous, so this one really does
					        // suspend; a read that THROWS becomes the negative count the module
					        // signals at the drain, which the glue answers on our behalf.
					        async (id) => {
					          const upstream = upstreams.get(id);
					          if (!upstream) return null;
					          const { value, done } = await upstream.read();
					          if (done) {
					            upstreams.delete(id);
					            return null;
					          }
					          return value;
					        },
					      ),
					""");
		}
		else if (pulled) {
			out.append("      ").append(jsKey(FetchResponseShape.HOST_BODY_IMPORT_FIELD)).append(": suspending(\n");
			out.append("""
					        // The next chunk of the reply the last fetch opened, null at the end
					        // of it. Reading a ReadableStream is asynchronous, so this one really
					        // does suspend; a read that THROWS becomes the negative count the
					        // module signals at the drain, which the glue answers on our behalf.
					        async () => {
					          if (!upstream) return null;
					          const { value, done } = await upstream.read();
					          if (done) {
					            upstream = null;
					            return null;
					          }
					          return value;
					        },
					      ),
					""");
		}
		out.append("    },\n  };\n}\n");
	}

	// Every request-record field is answered from the same-named property of the parsed
	// envelope; the switch exists so a field this half does not carry fails the BUILD
	// rather than crossing as undefined.
	private static String requestField(String name) {
		return switch (name) {
			case "url", "method", "headers", "body" -> name;
			default -> throw new UnsupportedOperationException(
					"--emit-js-glue: the http-plist request record grew a field this host half does not send: " + name);
		};
	}

	// Whether the --host-fetch host half above is written at all. A JSPI wrapper is what
	// lets a promise answer a synchronous import, so a build where no export is entered
	// through `promising` has nowhere to put one -- there the host supplies its own
	// env.fetch and `instantiate` says so by name if it forgets.
	private static boolean writesFetch(Surface surface) {
		return surface.derivedFetch() && canSuspend(surface);
	}

	// Whether the reply body is out of band, i.e. --host-boundary=streaming. Read off
	// the import rather than a flag: the import IS the boundary.
	private static boolean pullsReplyBody(Surface surface) {
		return has(surface, FetchResponseShape.HOST_BODY_IMPORT_FIELD);
	}

	// The same question for the reactor's own two bodies.
	private static boolean reactorBodiesOutOfBand(Surface surface) {
		return has(surface, ReactorEnvelope.REQUEST_BODY_FIELD) || has(surface, ReactorEnvelope.RESPONSE_BODY_FIELD);
	}

	private static boolean has(Surface surface, String field) {
		return surface.imports()
			.stream()
			.anyMatch(i -> ReactorEnvelope.HOST_MODULE.equals(i.module()) && field.equals(i.field()));
	}

	// Whether this import is one this file answers, and which half answers it:
	// defaultHost() owns what --host-fetch fixes, worker() owns the reactor's bodies --
	// because those are per-CALL state, and the call is worker()'s.
	private static @Nullable String answeredBy(Surface surface, Import imp) {
		if (!ReactorEnvelope.HOST_MODULE.equals(imp.module())) {
			return null;
		}
		if (writesFetch(surface) && (FetchResponseShape.HOST_IMPORT_FIELD.equals(imp.field())
				|| FetchResponseShape.HOST_BODY_IMPORT_FIELD.equals(imp.field()))) {
			return "defaultHost()";
		}
		if (surface.envelopeExport() != null && (ReactorEnvelope.REQUEST_BODY_FIELD.equals(imp.field())
				|| ReactorEnvelope.RESPONSE_BODY_FIELD.equals(imp.field()))) {
			return "worker()";
		}
		return null;
	}

	// Whether this file answers EVERY import the module has, so `worker(module)` really
	// is the whole Worker. A program may import something of its own, and a --host-fetch
	// build whose only fetch sits on the LOAD path imports env.fetch while no export is
	// promising -- so no host half is written for it, and a caller taking the three-line
	// sketch at its word would get a 500 on every request from an import nobody supplied.
	private static boolean selfContained(Surface surface) {
		return surface.imports().stream().allMatch(imp -> answeredBy(surface, imp) != null);
	}

	// The whole Worker, over an entry point whose boundary is the envelope and nothing
	// else. Mapping a Request onto that envelope and a Response off it is TRANSPORT work
	// -- the keys are ReactorEnvelope's, fixed by the shared normalizer on the other side
	// -- so it is written here rather than copied into every host. The one thing that is
	// not transport work is which header carries the client address, and that is the one
	// thing left to the caller.
	private static void worker(StringBuilder out, String fileName, Surface surface) {
		String export = surface.envelopeExport();
		if (export == null) {
			return;
		}
		String entry = jsName(export);
		boolean suspends = canSuspend(surface);
		boolean whole = selfContained(surface);
		out.append("\n/**\n * This module as a fetch handler")
			.append(whole ? " -- the whole Worker, with nothing left over" : "")
			.append(":\n *\n");
		out.append(" *   import module from \"./").append(moduleFile(fileName)).append("\";\n");
		out.append(" *   import { worker } from \"./").append(fileName).append("\";\n *\n");
		out.append(whole ? " *   export default worker(module);\n"
				: " *   export default worker(module, { host });   // the imports are still yours\n");
		out.append("""
				 *
				 * A Request becomes the envelope the module's entry point takes, the head it
				 * answers becomes a Response, and the instance is created on the FIRST REQUEST
				 * (a Worker forbids drawing entropy in global scope) and retired if a call ever
				 * traps.
				 *
				 * @param {WebAssembly.Module} module the compiled module
				""");
		out.append(" * @param {object} [options] ");
		if (!surface.needsHost()) {
			// Nothing to supply: this module imports nothing at all.
			out.append("`remoteAddr` -- ");
		}
		else if (writesFetch(surface)) {
			out.append("`host` -- import entries, laid over defaultHost()'s\n *   one at a time; `remoteAddr` -- ");
		}
		else {
			out.append("`host` -- one plain function per import, keyed by\n"
					+ " *   module and field, as `instantiate` takes it; `remoteAddr` -- ");
		}
		out.append("""
				(request, env, ctx) => the client
				 *   address, since which header carries it is the platform's business and not
				 *   this file's (`(r) => r.headers.get("cf-connecting-ip")` on Cloudflare)
				 * @returns {object} `{ fetch(request, env, ctx) }`
				 */
				export function worker(module, options = {}) {
				""");
		out.append("""
				  let instance = null;
				  // Set when a call TRAPPED: that instance skipped its arena reset and its Lisp
				  // state may be half-written, so nothing else may run on it. A Lisp ERROR is not
				  // this -- the transport answers 500 itself and the instance is fine.
				  let poisoned = false;
				""");
		workerBodyState(out, surface);
		workerHost(out, surface);
		out.append("""
				  const live = () => {
				    if (poisoned) {
				      instance = null;
				      poisoned = false;
				    }
				""");
		out.append("    return (instance ??= instantiate(module")
			.append(surface.needsHost() ? ", host" : "")
			.append("));\n  };\n");
		envelopeRequest(out, surface);
		boolean bodies = reactorBodiesOutOfBand(surface);
		boolean keyedBodies = bodies && surface.reentrant();
		out.append("""

				  return {
				    // EVERYTHING is inside the try, not just the module call: reading an
				    // aborted upload rejects, and `new Response` throws on a status or a
				    // header an application is free to produce (0, 999, a newline in a
				    // value). Outside it those escape as an unhandled rejection, which the
				    // platform answers with its own error page and nothing in the log.
				    async fetch(request, env, ctx) {
				      let entered = false;
				""");
		if (keyedBodies) {
			// Declared OUTSIDE the try so the finally below can retire the call's body
			// state on every path, thrown mappings included; 0 is never minted.
			out.append("      let callId = 0;\n");
		}
		out.append("""
				      try {
				        const remoteAddr = await options.remoteAddr?.(request, env, ctx);
				        const octets = request.body
				          ? new Uint8Array(await request.arrayBuffer())
				          : null;
				""");
		if (keyedBodies) {
			out.append("""
					        callId = ++callSerial;
					        const input = envelope(request, octets, remoteAddr, callId);
					        requestBodies.set(callId, octets);
					        responseChunks.set(callId, []);
					""");
		}
		else {
			out.append("        const input = envelope(request, octets, remoteAddr);\n");
		}
		if (suspends && surface.reentrant()) {
			// No queue to take: the module owns its per-call state, so the call enters
			// directly and overlaps with whatever else is in flight. live() runs
			// synchronously right before the entry, so no parked call can poison the
			// instance between the two.
			out.append("        entered = true;\n        const head = JSON.parse(await live().")
				.append(entry)
				.append("(input));\n");
		}
		else if (suspends) {
			out.append("""
					        const head = JSON.parse(
					          await live().serially((lisp) => {
					            // Re-read INSIDE the critical section: the instance was bound at
					            // admission, and a call parked ahead of this one can poison it
					            // before this one runs. Refusing is the whole point -- a
					            // half-unwound instance answers wrong rather than failing, and
					            // the module's own re-entry guard is cleared by the landing pad
					            // on exactly the path that poisons it.
					            if (poisoned) throw new Error("instance discarded by an earlier trap");
					""");
			if (bodies) {
				out.append("""
						            // Per-call state, set HERE and not beside the call: a suspended
						            // handler returns to the event loop, so the next request would
						            // otherwise move it under this one.
						            requestBody = octets;
						            responseChunks = [];
						""");
			}
			out.append("            entered = true;\n");
			out.append("            return lisp.").append(entry).append("(input);\n");
			out.append("          }),\n        );\n");
		}
		else {
			// Nothing can be parked, so nothing can have poisoned the instance between
			// binding it and calling it: a synchronous call cannot be interleaved.
			if (bodies) {
				out.append("        requestBody = octets;\n        responseChunks = [];\n");
			}
			out.append("        entered = true;\n        const head = JSON.parse(live().")
				.append(entry)
				.append("(input));\n");
		}
		envelopeResponse(out, surface);
		out.append("      } catch (error) {\n");
		out.append("        console.error(\"").append(export).append(" failed:\", error);\n");
		out.append("""
				        // Only a call that ENTERED the module can have left it half-written.
				        // A mapping, or a Response the platform refused, says nothing about
				        // the instance, and discarding it would cost the next request a
				        // reinstantiation for someone else's bad header.
				        if (entered) poisoned = true;
				        return new Response("internal error\\n", { status: 500 });
				      }
				""");
		if (keyedBodies) {
			out.append("""
					      finally {
					        // The call's body state goes with the call, on every path -- the
					        // Response above reads the chunks before this runs.
					        requestBodies.delete(callId);
					        responseChunks.delete(callId);
					      }
					""");
		}
		out.append("""
				    },
				  };
				}
				""");
	}

	// The reactor's two body imports are per-CALL state -- the octets of the request
	// being served, and the chunks the answer is made of -- so they belong to worker(),
	// which owns the call, and not to defaultHost(), which is built once. --reentrant:
	// calls OVERLAP, so the state is keyed by the CALL ID the envelope carries and the
	// body imports lead with, instead of being "the one call running below".
	private static void workerBodyState(StringBuilder out, Surface surface) {
		if (!reactorBodiesOutOfBand(surface)) {
			return;
		}
		if (surface.reentrant()) {
			out.append("""
					  // The request bodies the module pulls and the response bodies coming back,
					  // keyed by the call id worker() mints per request: overlapped calls each
					  // pull their own and collect their own.
					  let callSerial = 0;
					  const requestBodies = new Map();
					  const responseChunks = new Map();
					  const collected = (id) => {
					    const chunks = responseChunks.get(id) ?? [];
					    const all = new Uint8Array(
					      chunks.reduce((n, chunk) => n + chunk.length, 0),
					    );
					    let at = 0;
					    for (const chunk of chunks) {
					      all.set(chunk, at);
					      at += chunk.length;
					    }
					    return all;
					  };
					""");
			return;
		}
		out.append("""
				  // The request body the module pulls, and the response body coming back the
				  // same way. Both belong to the ONE call running below, which is where they
				  // are set.
				  let requestBody = null;
				  let responseChunks = [];
				  const collected = () => {
				    const all = new Uint8Array(
				      responseChunks.reduce((n, chunk) => n + chunk.length, 0),
				    );
				    let at = 0;
				    for (const chunk of responseChunks) {
				      all.set(chunk, at);
				      at += chunk.length;
				    }
				    return all;
				  };
				""");
	}

	// The import object worker() instantiates with: the halves this file implements,
	// with whatever the caller supplied laid over them ENTRY BY ENTRY -- a host that
	// wants its own env.fetch must not thereby lose the rest of `env`.
	private static void workerHost(StringBuilder out, Surface surface) {
		if (!surface.needsHost()) {
			return;
		}
		boolean fetch = writesFetch(surface);
		boolean bodies = reactorBodiesOutOfBand(surface);
		if (!fetch && !bodies) {
			out.append("  const host = options.host ?? {};\n");
			return;
		}
		out.append("  const base = ");
		// defaultHost() takes the instance because ITS cursor is the one a second fetch
		// inside one call supersedes; the body entries below need no such thing --
		// and the --reentrant shape needs neither, its readers being keyed per reply.
		out.append(
				fetch ? "defaultHost(" + (pullsReplyBody(surface) && !surface.reentrant() ? "() => instance" : "") + ")"
						: "{}")
			.append(";\n");
		if (bodies && surface.reentrant()) {
			out.append("  base.").append(jsKey(ReactorEnvelope.HOST_MODULE)).append(" = {\n");
			out.append("    ...(base.").append(jsKey(ReactorEnvelope.HOST_MODULE)).append(" ?? {}),\n");
			out.append("    ").append(jsKey(ReactorEnvelope.REQUEST_BODY_FIELD)).append(": (id) => {\n");
			out.append("""
					      // Handed over ONCE per call: a chunk source that never answers null is
					      // one the module pulls forever.
					      const chunk = requestBodies.get(id) ?? null;
					      if (chunk) requestBodies.set(id, null);
					      return chunk;
					    },
					""");
			out.append("    ")
				.append(jsKey(ReactorEnvelope.RESPONSE_BODY_FIELD))
				.append(": (id, chunk) => responseChunks.get(id)?.push(chunk),\n  };\n");
		}
		else if (bodies) {
			out.append("  base.").append(jsKey(ReactorEnvelope.HOST_MODULE)).append(" = {\n");
			out.append("    ...(base.").append(jsKey(ReactorEnvelope.HOST_MODULE)).append(" ?? {}),\n");
			out.append("    ").append(jsKey(ReactorEnvelope.REQUEST_BODY_FIELD)).append(": () => {\n");
			out.append("""
					      // Handed over ONCE: a chunk source that never answers null is one the
					      // module pulls forever.
					      const chunk = requestBody;
					      requestBody = null;
					      return chunk;
					    },
					""");
			out.append("    ")
				.append(jsKey(ReactorEnvelope.RESPONSE_BODY_FIELD))
				.append(": (chunk) => responseChunks.push(chunk),\n  };\n");
		}
		out.append("""
				  const given = options.host ?? {};
				  const host = {};
				  for (const key of new Set([...Object.keys(base), ...Object.keys(given)])) {
				    host[key] = { ...(base[key] ?? {}), ...(given[key] ?? {}) };
				  }
				""");
	}

	// A Request -> the request head, key by key. The keys are the envelope's own
	// (ReactorEnvelope.REQUEST_KEYS): a key this mapping does not answer fails the BUILD,
	// so growing the envelope cannot silently drop one here.
	private static void envelopeRequest(StringBuilder out, Surface surface) {
		boolean inBandBody = !reactorBodiesOutOfBand(surface);
		boolean keyedBodies = reactorBodiesOutOfBand(surface) && surface.reentrant();
		out.append("""

				  // The request head. `target` stays RAW -- path and query still joined and
				  // still percent-encoded -- because the shared normalizer on the other side
				  // owns that split, and a pre-split path leaves the query string nil.
				""");
		out.append("  const envelope = (request, octets, remoteAddr")
			.append(keyedBodies ? ", callId" : "")
			.append(") => {\n");
		out.append("""
				    const url = new URL(request.url);
				    const headers = Object.fromEntries(request.headers);
				    // A body with no content-length is a body the request parser does not read,
				    // and a chunked request carries none -- so set it from the octets we have.
				    if (octets?.length) headers["content-length"] = String(octets.length);
				    const head = {
				""");
		List<String> conditional = new ArrayList<>();
		for (String key : ReactorEnvelope.REQUEST_KEYS) {
			switch (key) {
				case "method" -> out.append("      method: request.method,\n");
				case "target" -> out.append("      target: url.pathname + url.search,\n");
				case "headers" -> out.append("      headers,\n");
				case "scheme" -> out.append("      scheme: url.protocol.replace(\":\", \"\"),\n");
				// The body key rides the head only where the module has no import to pull
				// it through; out of band it would be a second copy the transport
				// ignores.
				// ABSENT rather than empty either way -- the envelope's own default fills
				// it.
				case "body" -> {
					if (inBandBody) {
						conditional.add("    if (octets?.length) head." + key + " = decoder.decode(octets);");
					}
				}
				case "remote-addr" ->
					conditional.add("    if (remoteAddr != null) head[\"" + key + "\"] = remoteAddr;");
				// The call's identity, present exactly where the body imports lead with
				// it (--reentrant streaming): the transport threads it back to them.
				case "call-id" -> {
					if (keyedBodies) {
						out.append("      \"").append(key).append("\": callId,\n");
					}
				}
				default -> throw new UnsupportedOperationException(
						"--emit-js-glue: the reactor request envelope grew a key this mapping does not fill: " + key);
			}
		}
		out.append("    };\n");
		for (String line : conditional) {
			out.append(line).append('\n');
		}
		out.append("    return JSON.stringify(head);\n  };\n");
	}

	// The response head -> a Response, key by key, same rule as above.
	private static void envelopeResponse(StringBuilder out, Surface surface) {
		Map<String, String> reply = new LinkedHashMap<>();
		for (String key : ReactorEnvelope.RESPONSE_KEYS) {
			reply.put(key, switch (key) {
				case "status" -> "head.status ?? 200";
				case "headers" -> "head.headers ?? []";
				// Out of band the key is ABSENT and the chunks are the body -- except on
				// the error arm, which answers in band on purpose and must WIN over
				// whatever crossed before it, which is what `??` gets right.
				case "body" -> reactorBodiesOutOfBand(surface)
						? "head.body ?? collected(" + (surface.reentrant() ? "callId" : "") + ")" : "head.body";
				default -> throw new UnsupportedOperationException(
						"--emit-js-glue: the reactor response envelope grew a key this mapping does not read: " + key);
			});
		}
		out.append("""
				        // Headers as an ARRAY of pairs, which keeps two Set-Cookie two.
				        // An EMPTY body becomes null whichever shape it arrived in: 204/205/304
				        // may only be constructed with a null body, and "" and a zero-length
				        // Uint8Array are the same response as none.
				""");
		out.append("        const body = ").append(replyKey(reply, "body")).append(";\n");
		out.append("        return new Response(body?.length ? body : null, {\n");
		out.append("          status: ").append(replyKey(reply, "status")).append(",\n");
		out.append("          headers: ").append(replyKey(reply, "headers")).append(",\n        });\n");
	}

	// The .wasm this glue was written beside: the flag names the glue after the module
	// (out.wasm -> out.js), so the sketch can name the real file rather than a
	// placeholder.
	private static String moduleFile(String fileName) {
		return (fileName.endsWith(".js") ? fileName.substring(0, fileName.length() - ".js".length()) : fileName)
				+ ".wasm";
	}

	private static String replyKey(Map<String, String> reply, String key) {
		String value = reply.get(key);
		if (value == null) {
			throw new UnsupportedOperationException("--emit-js-glue: the reactor response envelope no longer carries '"
					+ key + "', which this mapping is written around");
		}
		return value;
	}

	// Only the helpers this module's declarations actually reach are written: a program
	// that never crosses a string is not handed a decoder to read past.
	private static void memoryHelpers(StringBuilder out, Surface surface) {
		boolean readString = surface.imports()
			.stream()
			.anyMatch(i -> i.paramTypes().stream().anyMatch(HostGlueEmitter::isText))
				|| surface.exports().stream().anyMatch(e -> isText(e.returnType()));
		boolean readBytes = surface.imports().stream().anyMatch(i -> i.paramTypes().contains(BoundaryType.BYTES))
				|| surface.exports().stream().anyMatch(e -> e.returnType() == BoundaryType.BYTES);
		// --reentrant: an import's text RESULT is staged in a park block the MODULE
		// frees after copying it out -- a bump allocation would leak, since the
		// synchronous bracket around the call closes before the host's answer exists.
		boolean writeParkString = surface.reentrant()
				&& surface.imports().stream().anyMatch(i -> isText(i.returnType()));
		boolean writeString = surface.exports()
			.stream()
			.anyMatch(e -> e.paramTypes().stream().anyMatch(HostGlueEmitter::isText))
				|| (!surface.reentrant() && surface.imports().stream().anyMatch(i -> isText(i.returnType())));
		boolean reserve = surface.exports().stream().anyMatch(e -> e.returnType() == BoundaryType.BYTES);
		boolean write = surface.exports().stream().anyMatch(e -> e.paramTypes().contains(BoundaryType.BYTES));
		boolean pulls = surface.imports().stream().anyMatch(i -> i.returnType() == BoundaryType.BYTES);
		if (readString) {
			out.append("""

					  // Every view is built where it is used, never held: growing the module's
					  // memory DETACHES the buffer behind every view taken before the growth, and a
					  // suspending host resumes after growths it never saw.
					  const readString = (ptr, len) =>
					    decoder.decode(new Uint8Array(exports.memory.buffer, ptr, len));
					""");
		}
		if (readBytes) {
			out.append("""

					  // Octets, COPIED: the module pops the staging behind the pointer the moment
					  // the call returns, so a chunk not taken by then is one the host never gets.
					  const readBytes = (ptr, len) =>
					    new Uint8Array(exports.memory.buffer.slice(ptr, ptr + len));
					""");
		}
		if (writeString || write) {
			out.append("""

					  // Bytes the HOST hands over live in the module's own bump allocator, and
					  // cross as the (ptr, len) pair every memory-typed value is.
					  const write = (octets) => {
					    const ptr = exports.__ronto_alloc(octets.length);
					    new Uint8Array(exports.memory.buffer, ptr, octets.length).set(octets);
					    return [ptr, octets.length];
					  };
					""");
		}
		if (writeString) {
			out.append("  const writeString = (value) => write(encoder.encode(String(value)));\n");
		}
		if (writeParkString) {
			out.append("""
					  // An import's text answer lives in a park block until the MODULE has copied
					  // it out -- which it frees itself with __ronto_park_free.
					  const writeParkString = (value) => {
					    const octets = encoder.encode(String(value));
					    const ptr = exports.__ronto_park_alloc(octets.length);
					    new Uint8Array(exports.memory.buffer, ptr, octets.length).set(octets);
					    return [ptr, octets.length];
					  };
					""");
		}
		if (reserve && surface.reentrant()) {
			// The receive buffer lives across the WHOLE call (the export fills it at the
			// end), so under overlap it must be a park block -- the decode frees it.
			out.append("  const reserve = (n) => [exports.__ronto_park_alloc(n), n];\n");
		}
		else if (reserve) {
			out.append("  const reserve = (n) => [exports.__ronto_alloc(n), n];\n");
		}
		if (pulls || write) {
			out.append("""
					  const octets = (chunk) =>
					    typeof chunk === "string" ? encoder.encode(chunk) : chunk;
					""");
		}
	}

	// The import object, and the two things every entry in it needs: a way to hand the
	// host's answer back in either shape, and a way to know which of the entries the host
	// marked as suspending.
	private static void imports(StringBuilder out, Surface surface) {
		Map<String, List<Import>> groups = surface.byModule();
		if (groups.isEmpty()) {
			out.append("""

					  // This module imports nothing: instantiating it is the whole boundary.
					  const imports = {};
					""");
			return;
		}
		if (surface.needsHost()) {
			hostPlumbing(out, surface);
		}
		out.append("\n  const imports = {\n");
		for (Map.Entry<String, List<Import>> group : groups.entrySet()) {
			out.append("    ").append(jsKey(group.getKey())).append(": {\n");
			for (Import imp : group.getValue()) {
				if (imp.equals(surface.entropy())) {
					entropyEntry(out, imp);
				}
				else {
					importEntry(out, surface, imp);
				}
			}
			out.append("    },\n");
		}
		out.append("  };\n");
	}

	// preview1's random_get(buf, len) -> errno, implemented rather than asked for: what
	// it does is fixed, and the module's memory is already this file's to write.
	private static void entropyEntry(StringBuilder out, Import imp) {
		out.append("      // ")
			.append(signature(imp.paramTypes(), imp.returnType()))
			.append(" -- the --host-random entropy source, implemented here\n");
		out.append("      ").append(jsKey(imp.field())).append(": (ptr, len) => {\n");
		out.append("""
				        crypto.getRandomValues(new Uint8Array(exports.memory.buffer, ptr, len));
				        return 0;
				      },
				""");
	}

	// What every host-supplied entry needs: a way to hand an answer back in either shape,
	// and a way to know which entries the host marked as suspending.
	private static void hostPlumbing(StringBuilder out, Surface surface) {
		out.append("""

				  // A host answers a value, or -- from an entry it marked suspending -- a promise
				  // of one. Both shapes ride one expression, which is what lets this file drive a
				  // synchronous host and a JSPI host without being written twice.
				  const marked = new Set();
				  const unmarked = (what) =>
				    what +
				    " answered a promise; wrap it in suspending() so the wasm stack parks" +
				    " until it settles";
				  const settle = (what, value, next) => {
				    if (typeof value?.then === "function") {
				      if (!marked.has(what)) {
				        // Reported before it is thrown: this throw crosses back into wasm,
				        // where a catch_all landing pad turns it into whatever the module
				        // makes of a failed import.
				        console.error(unmarked(what));
				        throw new TypeError(unmarked(what));
				      }
				      return value.then(next);
				    }
				    return next(value);
				  };

				  // The entries the module really imports: --optimize shakes out an import the
				  // program never calls, and a host should not have to know which survived.
				  const linked = new Set(
				    WebAssembly.Module.imports(module).map((i) => i.module + "." + i.name),
				  );
				  const bind = (moduleName, field, wrap) => {
				    const key = moduleName + "." + field;
				    const given = (host[moduleName] ?? {})[field];
				    if (given == null) {
				      if (!linked.has(key)) return undefined;
				      throw new TypeError("host." + key + " is missing; this module imports it");
				    }
				    if (given[SUSPENDING] === undefined) {
				      // An async function that was never marked is the one mistake worth
				      // catching HERE, where the report is a plain stack and not whatever
				      // the module makes of an import that threw.
				      if (given.constructor?.name === "AsyncFunction") {
				        throw new TypeError(unmarked("host." + key));
				      }
				      return wrap(key, given);
				    }
				    marked.add(key);
				    return new WebAssembly.Suspending(wrap(key, given[SUSPENDING]));
				  };
				""");
		if (surface.imports().stream().anyMatch(i -> i.returnType() == BoundaryType.BYTES) && surface.reentrant()) {
			out.append("""

					  // A :bytes RESULT is the read(2) shape: the MODULE owns the buffer and asks
					  // for up to `cap` octets, so what a host answers is the next CHUNK and this
					  // holds whatever did not fit. Calls OVERLAP (--reentrant), so the remainders
					  // are KEYED by the arguments that asked for them -- per call/reply id on the
					  // id-carrying body protocol -- and each overlapped pull keeps its own; two
					  // overlapped calls pulling one source through IDENTICAL arguments are the
					  // host's own hazard to serialise. A remainder is dropped with the end of its
					  // stream, never at the next entry: another call may be mid-pull.
					  const readers = new Map();
					  const reader = (what, source) => {
					    const rests = new Map();
					    readers.set(what, () => rests.clear());
					    const drain = (key, rest, ptr, cap) => {
					      const n = Math.min(cap, rest.length);
					      new Uint8Array(exports.memory.buffer, ptr, n).set(rest.subarray(0, n));
					      const left = rest.subarray(n);
					      if (left.length === 0) rests.delete(key);
					      else rests.set(key, left);
					      return n;
					    };
					    // A read that FAILS answers a NEGATIVE count. Throwing would trap the
					    // instance; the count is an error channel the module turns into a Lisp
					    // condition where the octets are consumed.
					    const failed = (error) => {
					      console.error(what + " failed:", error);
					      return -1;
					    };
					    return (args, ptr, cap) => {
					      const key = JSON.stringify(args);
					      const rest = rests.get(key);
					      if (rest !== undefined) return drain(key, rest, ptr, cap);
					      try {
					        const answer = settle(what, source(...args), (chunk) => {
					          if (chunk == null) return 0;
					          const value = octets(chunk);
					          return value.length === 0 ? 0 : drain(key, value, ptr, cap);
					        });
					        return typeof answer?.then === "function"
					          ? answer.then(undefined, failed)
					          : answer;
					      } catch (error) {
					        return failed(error);
					      }
					    };
					  };

					  // What a read import left over, thrown away on demand -- with no argument,
					  // every remainder of every import.
					  const drop = (key) =>
					    key === undefined ? readers.forEach((f) => f()) : readers.get(key)?.();
					""");
		}
		else if (surface.imports().stream().anyMatch(i -> i.returnType() == BoundaryType.BYTES)) {
			out.append("""

					  // A :bytes RESULT is the read(2) shape: the MODULE owns the buffer and asks
					  // for up to `cap` octets, so what a host answers is the next CHUNK and this
					  // holds whatever did not fit. That remainder is the read side's only state,
					  // and it is why a host supplies chunks rather than a reader -- which source
					  // they come from (a ReadableStream, a Uint8Array) is all that is left to it.
					  const readers = new Map();
					  const reader = (what, source) => {
					    let rest = null;
					    let from = null;
					    // A body the module did not drain belongs to the call that could have and
					    // to no other, so every cursor is dropped at the next entry below -- and a
					    // host whose SOURCE moves inside one call (a new upstream reply opened by
					    // another import) drops this one itself with lisp.drop(key), because what
					    // did not fit is held here and nothing else can see the source move.
					    readers.set(what, () => {
					      rest = null;
					      from = null;
					    });
					    const drain = (ptr, cap) => {
					      const n = Math.min(cap, rest.length);
					      new Uint8Array(exports.memory.buffer, ptr, n).set(rest.subarray(0, n));
					      rest = rest.subarray(n);
					      return n;
					    };
					    // A read that FAILS answers a NEGATIVE count. Throwing would trap the
					    // instance; the count is an error channel the module turns into a Lisp
					    // condition where the octets are consumed, which is where every other
					    // backend reports a transfer that broke mid-body.
					    const failed = (error) => {
					      console.error(what + " failed:", error);
					      return -1;
					    };
					    return (args, ptr, cap) => {
					      // The remainder belongs to the arguments that asked for it: a source
					      // selected by argument must not be served the previous one's octets.
					      const key = JSON.stringify(args);
					      if (from !== key) {
					        rest = null;
					        from = key;
					      }
					      if (rest !== null && rest.length !== 0) return drain(ptr, cap);
					      try {
					        const answer = settle(what, source(...args), (chunk) => {
					          rest = chunk == null ? new Uint8Array(0) : octets(chunk);
					          return rest.length === 0 ? 0 : drain(ptr, cap);
					        });
					        return typeof answer?.then === "function"
					          ? answer.then(undefined, failed)
					          : answer;
					      } catch (error) {
					        return failed(error);
					      }
					    };
					  };

					  // What a read import left over, thrown away on demand. A host calls it when
					  // the SOURCE behind that import moves under it INSIDE one call -- a new
					  // upstream reply, say -- since the remainder is held above and nothing else
					  // can see the source move. With no argument it drops every one of them.
					  const drop = (key) =>
					    key === undefined ? readers.forEach((f) => f()) : readers.get(key)?.();
					""");
		}
	}

	// One import-object property: the host's own function with the (ptr, len) pairs
	// unpacked on the way in and its answer staged back into linear memory on the way
	// out. `bind` decides whether the result is wrapped in WebAssembly.Suspending.
	private static void importEntry(StringBuilder out, Surface surface, Import imp) {
		List<String> params = new ArrayList<>();
		List<String> args = new ArrayList<>();
		for (int i = 0; i < imp.paramTypes().size(); i++) {
			appendParam(params, args, imp.paramTypes().get(i), i);
		}
		out.append("      // ").append(signature(imp.paramTypes(), imp.returnType())).append('\n');
		out.append("      ")
			.append(jsKey(imp.field()))
			.append(": bind(\"")
			.append(imp.module())
			.append("\", \"")
			.append(imp.field())
			.append("\", (what, call) => {\n");
		if (imp.returnType() == BoundaryType.BYTES) {
			// The receive buffer the module passes rides last and never reaches the host:
			// answering chunks is the whole contract, and the copy is this file's job.
			out.append("        const read = reader(what, call);\n");
			out.append("        return (")
				.append(String.join(", ", concat(params, "ptr", "cap")))
				.append(") => read([")
				.append(String.join(", ", args))
				.append("], ptr, cap);\n      }),\n");
			return;
		}
		out.append("        return (")
			.append(String.join(", ", params))
			.append(") =>\n          settle(what, call(")
			.append(String.join(", ", args))
			.append("), ")
			.append(importResult(imp.returnType(), surface.reentrant()))
			.append(");\n      }),\n");
	}

	// How the host's answer is handed back to the module. --reentrant: a text answer
	// crosses in a park block the module frees, never a bump allocation the closed
	// bracket cannot reclaim.
	private static String importResult(BoundaryType type, boolean reentrant) {
		return switch (type) {
			case STRING, S_EXPR -> reentrant ? "writeParkString" : "writeString";
			case BOOL -> "(value) => (value ? 1 : 0)";
			case VOID -> "() => undefined";
			default -> "(value) => value";
		};
	}

	// One declared parameter -> the JavaScript parameters it arrives in, and the value
	// the host is handed. A memory-typed one crosses as a (ptr, len) pair, which is
	// exactly the part a host should never have to unpack for itself.
	private static void appendParam(List<String> params, List<String> args, BoundaryType type, int index) {
		String name = "p" + index;
		switch (type) {
			case STRING, S_EXPR -> {
				params.add(name);
				params.add(name + "Len");
				args.add("readString(" + name + ", " + name + "Len)");
			}
			case BYTES -> {
				params.add(name);
				params.add(name + "Len");
				args.add("readBytes(" + name + ", " + name + "Len)");
			}
			case BOOL -> {
				params.add(name);
				args.add("!!" + name);
			}
			default -> {
				params.add(name);
				args.add(name);
			}
		}
	}

	// Everything a --no-wasi module needs from its host between instantiation and its
	// first call: the entropy and the clock it cannot import, then its own top level.
	private static void startup(StringBuilder out, Surface surface) {
		if (surface.seedRandom()) {
			out.append("""
					  // Entropy, BEFORE the top level runs: a --no-wasi module imports none, so its
					  // `random` starts from a constant and every instance would draw one sequence.
					  // Seeding here also covers the draws a library makes while it LOADS. A Worker
					  // forbids this in global scope, so instantiate on the first request.
					  exports.__ronto_seed_random(
					    new BigUint64Array(crypto.getRandomValues(new Uint8Array(8)).buffer)[0],
					  );
					""");
		}
		if (surface.setTime()) {
			out.append("""
					  // And a clock, nanoseconds since the Unix epoch, for the same reason and
					  // before the same line: until one is set the clock built-ins signal rather
					  // than report 1970.
					  exports.__ronto_set_time(BigInt(Date.now()) * 1000000n);
					""");
		}
		if (surface.initExport() != null) {
			out.append("  exports.").append(surface.initExport()).append("();\n");
		}
	}

	// The entry points, and the two things a call through one owes the module: the arena
	// bracket around whatever it stages, and -- once a host import can suspend -- the one
	// promise chain the module's re-entry guard demands.
	private static void exports(StringBuilder out, Surface surface) {
		if (surface.exports().isEmpty()) {
			out.append("""

					  // This module exports no rontolisp:wasm-export: its whole program is the top
					  // level that has just run.
					""");
			out.append(hasReader(surface) ? "  return { exports, drop };\n" : "  return { exports };\n");
			return;
		}
		boolean suspends = surface.exports().stream().anyMatch(Export::promising);
		out.append('\n');
		if (suspends) {
			out.append("""
					  // The entry points a call chain can reach a suspending import from -- the
					  // list the build prints -- entered through WebAssembly.promising exactly when
					  // the host marked one. An unmarked host never pays for the promise.
					  const suspends = marked.size !== 0;
					""");
			if (surface.reentrant()) {
				out.append("""
						  // --reentrant: NO serialisation queue. The module owns its per-call state
						  // (task-scoped dynamic bindings, park-block staging), so overlapped calls
						  // into one instance are the point of the build.
						""");
			}
		}
		for (Export export : surface.exports()) {
			if (export.promising()) {
				out.append("  const ")
					.append(entryName(export))
					.append(" = suspends\n    ? WebAssembly.promising(exports[\"")
					.append(export.exportName())
					.append("\"])\n    : exports[\"")
					.append(export.exportName())
					.append("\"];\n");
			}
			else {
				out.append("  const ")
					.append(entryName(export))
					.append(" = exports[\"")
					.append(export.exportName())
					.append("\"];\n");
			}
		}
		if (suspends && !surface.reentrant()) {
			out.append("""

					  // One Lisp call at a time. A suspended call returns to the host's event loop,
					  // and the module's own re-entry guard TRAPS a second entry rather than let two
					  // calls share its allocator and its dynamic bindings -- so the queue is the
					  // contract, not a nicety. `.then(work, work)` because one rejected call must
					  // not wedge the chain behind it.
					  let queue = Promise.resolve();
					  const queued = (work) => {
					    const done = queue.then(work, work);
					    queue = done.then(
					      () => {},
					      () => {},
					    );
					    return done;
					  };
					  // A bare entry point only needs the queue when a host marked something: a
					  // synchronous call cannot be interleaved, and paying a promise for it would
					  // make every host asynchronous. `serially` below always takes it, because
					  // the work it runs awaits and a second request WOULD land inside it.
					  const serialised = (work) => (suspends ? queued(work) : work());
					""");
		}
		call(out, surface, suspends);
		for (Export export : surface.exports()) {
			entryPoint(out, surface, export);
		}
		String run = suspends && !surface.reentrant() ? "serialised" : "(work) => work()";
		if (suspends && !surface.reentrant()) {
			out.append("""

					  // Host state that belongs to ONE call -- what the module pulls DURING it,
					  // and what the call leaves behind -- is set and read inside `work`, which
					  // runs in the same critical section: a suspended call returns to the event
					  // loop, so setting it beside the call instead would let the next request
					  // move it under this one. The entry points `work` is handed enter the module
					  // directly, because the queue they would take is the one they are in.
					""");
			out.append("  const inside = {\n");
			for (Export export : surface.exports()) {
				out.append("    ")
					.append(jsKey(jsName(export.exportName())))
					.append(": ")
					.append(factoryName(export))
					.append("((work) => work()),\n");
			}
			out.append("  };\n  const serially = (work) => queued(() => work(inside));\n");
		}
		out.append("\n  return {\n    exports,\n");
		for (Export export : surface.exports()) {
			out.append("    ")
				.append(jsKey(jsName(export.exportName())))
				.append(": ")
				.append(factoryName(export))
				.append("(")
				.append(run)
				.append("),\n");
		}
		if (hasReader(surface)) {
			out.append("    drop,\n");
		}
		if (suspends && !surface.reentrant()) {
			out.append("    serially,\n");
		}
		out.append("  };\n");
	}

	private static void call(StringBuilder out, Surface surface, boolean suspends) {
		if (surface.reentrant()) {
			out.append("""

					  // One call into the module (--reentrant, so calls may OVERLAP). The
					  // argument staging is popped SYNCHRONOUSLY the moment the entry call
					  // starts -- the wrapper boxes its parameters before its first suspension,
					  // and by decode time another overlapped call may hold staging of its own
					  // above the mark (the reset clamps to the module's park floor, so a park
					  // block carved meanwhile survives the pop). Anything that must outlive
					  // this synchronous window crosses in park blocks instead: `reserve`d
					  // receive buffers, and the module's own :string/:s-expr results, which
					  // `decode` frees with __ronto_park_free.
					  const call = (run, entry, stage, decode) => {
					    const work = () => {
					""");
		}
		else {
			out.append("""

					  // One call into the module: stage the arguments, enter, decode the result out
					  // of the scratch it sits in, and only THEN pop the arena that scratch is in.
					  // A promising entry answers a promise, so the tail rides `then`; a synchronous
					  // one runs the same expression inline. `run` is how the call reaches the
					  // module -- through the queue, or straight through when it is already inside
					  // the one call the queue admits.
					  const call = (run, entry, stage, decode) => {
					    const work = () => {
					""");
		}
		if (surface.imports().stream().anyMatch(i -> i.returnType() == BoundaryType.BYTES) && !surface.reentrant()) {
			// Under overlap another in-flight call may be mid-pull; its cursor is not
			// this call's to drop.
			out.append("""
					      readers.forEach((drop) => drop());
					""");
		}
		if (surface.setTime()) {
			out.append("""
					      // The module's clock moves only when the host moves it, so move it per
					      // call. Not a workaround for a frozen clock -- a Worker's own Date.now()
					      // is frozen for the duration of a request as a timing-attack mitigation,
					      // so a value that changes once per call is what the platform has.
					      exports.__ronto_set_time(BigInt(Date.now()) * 1000000n);
					""");
		}
		if (surface.arena() && surface.reentrant()) {
			out.append("""
					      const mark = exports.__ronto_alloc_mark();
					      const raw = entry(...stage());
					      exports.__ronto_alloc_reset(mark);
					      return typeof raw?.then === "function" ? raw.then(decode) : decode(raw);
					    };
					    return run(work);
					  };
					""");
			out.append('\n');
			return;
		}
		if (surface.arena()) {
			out.append("""
					      const mark = exports.__ronto_alloc_mark();
					      const done = (raw) => {
					        const value = decode(raw);
					        exports.__ronto_alloc_reset(mark);
					        return value;
					      };
					""");
		}
		else {
			out.append("      const done = decode;\n");
		}
		out.append("""
				      // A TRAP skips the pop above and leaves the module's state half-written
				      // with it, so a host that keeps serving instantiates again rather than
				      // calling back into this instance.
				      const raw = entry(...stage());
				      return typeof raw?.then === "function" ? raw.then(done) : done(raw);
				    };
				    return run(work);
				  };
				""");
		out.append('\n');
	}

	private static void entryPoint(StringBuilder out, Surface surface, Export export) {
		List<String> params = new ArrayList<>();
		List<String> staged = new ArrayList<>();
		List<String> setup = new ArrayList<>();
		for (int i = 0; i < export.paramTypes().size(); i++) {
			String name = "p" + i;
			params.add(name);
			switch (export.paramTypes().get(i)) {
				case STRING, S_EXPR -> {
					setup.add("const a" + i + " = writeString(" + name + ");");
					staged.add("a" + i + "[0], a" + i + "[1]");
				}
				case BYTES -> {
					setup.add("const a" + i + " = write(octets(" + name + "));");
					staged.add("a" + i + "[0], a" + i + "[1]");
				}
				case BOOL -> staged.add(name + " ? 1 : 0");
				default -> staged.add(name);
			}
		}
		out.append("  /** `")
			.append(export.exportName())
			.append("` -- ")
			.append(signature(export.paramTypes(), export.returnType()))
			.append(" */\n");
		out.append("  const ")
			.append(factoryName(export))
			.append(" =\n    (run) =>\n    (")
			.append(String.join(", ", params))
			.append(") => {\n");
		if (export.returnType() == BoundaryType.BYTES) {
			bytesEntryPoint(out, surface, export, setup, staged);
			return;
		}
		out.append("      return call(\n        run,\n        ")
			.append(entryName(export))
			.append(",\n        () => {\n");
		for (String line : setup) {
			out.append("          ").append(line).append('\n');
		}
		out.append("          return [").append(String.join(", ", staged)).append("];\n        },\n        ");
		out.append(exportResult(export.returnType(), surface.reentrant())).append(",\n      );\n    };\n\n");
	}

	// The mirror of the import side's read(2) shape: the CALLER passes the buffer, and an
	// undersized one is answered with the value's FULL length -- so the only sound reply
	// to a short answer is to ask again with exactly that much, never to truncate.
	// --reentrant: the receive buffer is a park block (it lives across the whole call,
	// which may park), freed here after the copy -- or before the retry.
	private static void bytesEntryPoint(StringBuilder out, Surface surface, Export export, List<String> setup,
			List<String> staged) {
		out.append("      let at = 0;\n      const pull = (cap) =>\n        call(\n          run,\n          ")
			.append(entryName(export))
			.append(",\n          () => {\n");
		for (String line : setup) {
			out.append("            ").append(line).append('\n');
		}
		out.append("            const out = reserve(cap);\n            at = out[0];\n            return [")
			.append(String.join(", ", concat(staged, "out[0]", "out[1]")))
			.append("];\n          },\n          ");
		if (surface.reentrant()) {
			out.append("""
					(n) => {
					            const value = n > cap ? n : readBytes(at, n);
					            exports.__ronto_park_free(at);
					            return value;
					          },
					        );
					""");
		}
		else {
			out.append("(n) => (n > cap ? n : readBytes(at, n)),\n        );\n");
		}
		out.append("""
				      // A short answer is the value's full length, so ask once more for that much.
				      const grow = (value) => (typeof value === "number" ? pull(value) : value);
				""");
		out.append("      const first = pull(").append(BYTES_BUFFER).append(");\n");
		out.append("      return typeof first?.then === \"function\" ? first.then(grow) : grow(first);\n    };\n\n");
	}

	// --reentrant: a text result's (ptr, len) is a park block the module staged
	// (_park_str_result) precisely so nothing tramples it before this decode runs --
	// freeing it is the decode's other half.
	private static String exportResult(BoundaryType type, boolean reentrant) {
		return switch (type) {
			case STRING, S_EXPR -> reentrant ? """
					([ptr, len]) => {
					          const value = readString(ptr, len);
					          exports.__ronto_park_free(ptr);
					          return value;
					        }""" : "([ptr, len]) => readString(ptr, len)";
			case BOOL -> "(value) => value !== 0";
			case VOID -> "() => undefined";
			default -> "(value) => value";
		};
	}

	// The two locals an export declares. `$` is what keeps them out of the way of the
	// fixed helper names above (`call`, `settle`, `bind`, `write`, ... -- none carries
	// one), and an export's OWN name never becomes a local at all: the entry points are
	// PROPERTIES of the returned object, so an export called `call` cannot shadow the
	// function that enters the module. checkNames covers the remaining case, two exports
	// whose camel-cased names are the same.
	// Whether a suspension can reach an entry point at all -- the only case in which the
	// marking protocol, the promising entries and the queue exist. A module with imports
	// but no reachable suspension gets none of them, and must not be told to mark one.
	private static boolean canSuspend(Surface surface) {
		return surface.exports().stream().anyMatch(Export::promising);
	}

	private static boolean hasReader(Surface surface) {
		return surface.imports().stream().anyMatch(i -> i.returnType() == BoundaryType.BYTES);
	}

	private static String factoryName(Export export) {
		return "make$" + jsName(export.exportName());
	}

	private static String entryName(Export export) {
		return "entry$" + jsName(export.exportName());
	}

	private static List<String> concat(List<String> items, String... more) {
		List<String> all = new ArrayList<>(items);
		all.addAll(List.of(more));
		return all;
	}

	// (:string) -> :string. The designators are the reader's, so they arrive upcased;
	// a comment quoting the declaration should read the way the source was written.
	private static String signature(List<BoundaryType> paramTypes, BoundaryType returnType) {
		List<String> names = new ArrayList<>();
		paramTypes.forEach(t -> names.add(designator(t)));
		return "(" + String.join(", ", names) + ") -> " + designator(returnType);
	}

	private static String designator(BoundaryType type) {
		return type.designator().toLowerCase(java.util.Locale.ROOT);
	}

	private static boolean isText(BoundaryType type) {
		return type == BoundaryType.STRING || type == BoundaryType.S_EXPR;
	}

	// handle-request -> handleRequest: the same spelling the WIT lowering gives a host
	// field, so one name never means two things across this boundary.
	private static String jsName(String exportName) {
		return WitImportDirective.FieldStyle.CAMEL.apply(exportName);
	}

	// A property name that is not a plain identifier is QUOTED rather than mangled: an
	// import field is the host's own spelling and has to cross exactly.
	private static String jsKey(String name) {
		if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
			return "\"" + name + "\"";
		}
		for (int i = 1; i < name.length(); i++) {
			if (!Character.isJavaIdentifierPart(name.charAt(i))) {
				return "\"" + name + "\"";
			}
		}
		return name;
	}

}
