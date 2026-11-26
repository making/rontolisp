package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <strong>A WIT interface the flat set does not carry is lifted here.</strong> A
 * {@code --no-wasi} module imports one through the canonical ABI, as a component's core
 * module does ({@link WitImportDirective.CanonicalCore}), so this file lifts each
 * argument off the core values and linear memory and lowers each answer back, by the
 * {@link CanonicalLayout}s the compiler's own layout calculator gives
 * ({@link WitImport}): the host deals in JavaScript values, a {@code result}'s error arm
 * a thrown {@code WitError}, a resource's value kept in a table here. And
 * {@code wasi:keyvalue}'s store is a host half this file writes too:
 * {@code defaultHost()} answers it over Workers KV, and {@code worker()} hands it the
 * Worker's namespaces and puts a call's writes after its answer, one at a time a second
 * apart per key -- Workers KV takes one write a second to a key. {@code wasi:logging}'s
 * {@code log} is one too, answered on the console method of its level.
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
	 * One import a {@code --no-wasi} module makes of a WIT interface through the
	 * canonical ABI ({@link WitImportDirective.CanonicalCore}): what a component's core
	 * module imports, which this file lifts and lowers itself, so the host is handed and
	 * answers JavaScript values -- a string, a {@code Uint8Array} for a {@code list<u8>},
	 * an array, {@code undefined} for an option's none, {@code { tag, val }} for a
	 * variant's case, an enum's case name, an object of camelCase keys for a record, its
	 * own value for a resource.
	 *
	 * @param module the import module, the interface's canonical id
	 * @param field the import field, the function's canonical-ABI name
	 * ({@code [method]bucket.get}), or a resource's {@code [resource-drop]bucket}
	 * @param signature the WIT function as the interface declares it
	 * ({@code func(key: string) -> result<option<list<u8>>, error>}), or
	 * {@code resource-drop} for a release
	 * @param paramNames the parameters' WIT names, a method's handle {@code self}
	 * @param params the parameters' layouts, in the same order
	 * @param result the result's layout, or {@code null} for none
	 * @param retptr whether the result goes through a return area the module passes last
	 * @param raises whether the result is a {@code result}: the host answers its ok
	 * value, and its error arm by throwing a {@code WitError} carrying the error
	 */
	public record WitImport(String module, String field, String signature, List<String> paramNames,
			List<CanonicalLayout> params, @Nullable CanonicalLayout result, boolean retptr, boolean raises) {

		/**
		 * Whether it releases a resource, which this file implements itself.
		 * @return {@code true} for a {@code [resource-drop]} field
		 */
		public boolean drop() {
			return this.field.startsWith(RESOURCE_DROP);
		}

	}

	/** What a resource's release is named, before the resource. */
	private static final String RESOURCE_DROP = "[resource-drop]";

	/** The {@code --no-wasi} entropy hook a host calls before {@code _initialize}. */
	public static final String SEED_RANDOM_EXPORT = "__ronto_seed_random";

	/** The {@code --no-wasi} clock hook a host calls before {@code _initialize}. */
	public static final String SET_TIME_EXPORT = "__ronto_set_time";

	/**
	 * A module's whole host-facing surface: the derived facts this emitter writes from,
	 * computed once by the backend that also prints the obligation lines.
	 *
	 * @param imports the declared host imports, in declaration order
	 * @param entropy the {@code --host-random} entropy import, which the glue IMPLEMENTS
	 * rather than asks the host for (preview1 fixes what {@code random_get(buf, len)}
	 * does, and writing into linear memory is this side's job); {@code null} without the
	 * flag
	 * @param output the {@code --host-output} standard output and error, which the glue
	 * IMPLEMENTS too (preview1 fixes what {@code fd_write(fd, iovs, iovs_len, nwritten)}
	 * does, and a JavaScript host's log is its console); {@code null} without the flag
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
	 * @param witImports the imports through the canonical ABI
	 * @param timer {@code --host-timer}'s waits ({@link HostTimer}), which the glue
	 * IMPLEMENTS: they hold the order the module is entered in, which is this file's. Not
	 * empty, the file waits for the module through JSPI -- {@code instantiate} answers a
	 * promise, {@code _initialize} and every entry point are entered through
	 * {@code WebAssembly.promising}, and a call that idles lets the next one in
	 * @param turn whether it exports {@link HostTimer#TURN_EXPORT}, the program's own
	 * turn, which the file calls where the module asks for it ({@code env.wake}) rather
	 * than offers as an entry point
	 */
	public record Surface(List<Import> imports, @Nullable Import entropy, @Nullable Import output, List<Export> exports,
			boolean arena, boolean seedRandom, boolean setTime, @Nullable String initExport, boolean derivedFetch,
			@Nullable String envelopeExport, boolean reentrant, List<WitImport> witImports, List<Import> timer,
			boolean turn) {

		/**
		 * A surface with no import through the canonical ABI.
		 * @param imports the declared host imports
		 * @param entropy the {@code --host-random} entropy import, or {@code null}
		 * @param output the {@code --host-output} output import, or {@code null}
		 * @param exports the declared host-callable entry points
		 * @param arena whether the module exports the allocation bracket
		 * @param seedRandom whether it exports {@code __ronto_seed_random}
		 * @param setTime whether it exports {@code __ronto_set_time}
		 * @param initExport the top-level entry point, or {@code null}
		 * @param derivedFetch whether this file implements {@code env.fetch}
		 * @param envelopeExport the reactor entry point, or {@code null}
		 * @param reentrant whether the module was compiled {@code --reentrant}
		 */
		public Surface(List<Import> imports, @Nullable Import entropy, @Nullable Import output, List<Export> exports,
				boolean arena, boolean seedRandom, boolean setTime, @Nullable String initExport, boolean derivedFetch,
				@Nullable String envelopeExport, boolean reentrant) {
			this(imports, entropy, output, exports, arena, seedRandom, setTime, initExport, derivedFetch,
					envelopeExport, reentrant, List.of(), List.of(), false);
		}

		/**
		 * Every import-object entry, whoever implements it, grouped by module in order.
		 */
		Map<String, List<Import>> byModule() {
			Map<String, List<Import>> groups = new LinkedHashMap<>();
			for (Import imp : this.imports) {
				groups.computeIfAbsent(imp.module(), m -> new ArrayList<>()).add(imp);
			}
			for (Import implemented : new Import[] { this.entropy, this.output }) {
				if (implemented != null) {
					groups.computeIfAbsent(implemented.module(), m -> new ArrayList<>()).add(implemented);
				}
			}
			for (Import implemented : this.timer) {
				groups.computeIfAbsent(implemented.module(), m -> new ArrayList<>()).add(implemented);
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

		/**
		 * This surface after the tree shake: a host hook the shake dropped (its cell is
		 * read by no surviving body, {@code WasmTreeShaker.HostCellHook}) is no longer an
		 * export, so the glue must not call it -- the call would throw at instantiation.
		 * @param exportNames the export names of the module as it is finally written
		 * @return the surface with each hook kept only where its export survived
		 */
		public Surface afterShake(Set<String> exportNames) {
			return new Surface(this.imports, this.entropy, this.output, this.exports, this.arena,
					this.seedRandom && exportNames.contains(SEED_RANDOM_EXPORT),
					this.setTime && exportNames.contains(SET_TIME_EXPORT), this.initExport, this.derivedFetch,
					this.envelopeExport, this.reentrant, this.witImports, this.timer, this.turn);
		}

		/**
		 * Whether the file takes a host: an import the host answers (never the entropy
		 * and output entries), or one through the canonical ABI, whose release calls a
		 * host entry of its name where there is one.
		 */
		boolean needsHost() {
			return !this.imports.isEmpty() || !this.witImports.isEmpty();
		}

		/**
		 * Whether the host waits for the module ({@code --host-timer}): the file then
		 * enters it through JSPI throughout.
		 */
		boolean waits() {
			return !this.timer.isEmpty();
		}

	}

	/**
	 * The first bytes of every emitted file, and the only thing that identifies one: the
	 * CLI refuses to overwrite a {@code .js} beside the output that does not start with
	 * it, because that file is the host's own.
	 */
	public static final String MARKER = "// GENERATED by rontolisp --emit-js-glue -- do not edit.";

	/**
	 * The file name {@link #workerEntry} is written under, beside the glue: the
	 * {@code main} wrangler's own templates name. Fixed rather than a value of the flag,
	 * because the path is already stated once, in the Worker's {@code wrangler.jsonc}.
	 */
	public static final String WORKER_ENTRY = "index.js";

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
		if (surface.witImports().stream().anyMatch(WitImport::raises)) {
			out.append("""

					/**
					 * The error arm of a WIT `result`: a host function whose WIT result is one
					 * answers its ok value, or throws one of these carrying the error's value --
					 * `{ tag: "other", val: "..." }` for a variant's case -- which the program
					 * sees as the condition a WIT error is.
					 */
					export class WitError extends Error {
					  constructor(payload) {
					    super("the error arm of a WIT result: " + (payload?.tag ?? payload));
					    this.payload = payload;
					  }
					}
					""");
		}
		instantiate(out, surface);
		if (writesSockets(surface)) {
			hostSockets(out);
		}
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
		out.append("//   const lisp = ")
			.append(surface.waits() ? "await " : "")
			.append("instantiate(module")
			.append(surface.needsHost() ? ", {\n" : ");\n");
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
		// A resource's release is this file's alone, so it is no entry a host writes.
		for (List<WitImport> group : witByModule(surface).values()) {
			for (WitImport imp : group) {
				if (imp.drop()) {
					continue;
				}
				if (!imp.module().equals(module)) {
					if (module != null) {
						out.append("//     },\n");
					}
					module = imp.module();
					out.append("//     ").append(quoted(module)).append(": {\n");
				}
				out.append("//       ").append(jsKey(imp.field())).append(": ").append(sketch(imp)).append(",");
				String answered = answeredBy(imp);
				out.append(answered == null ? "\n" : "   // or leave it to " + answered + "\n");
			}
		}
		if (module != null) {
			out.append("//     },\n//   });\n");
		}
		else if (surface.needsHost()) {
			out.append("//   });\n");
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
		else if (surface.waits() && !surface.exports().isEmpty()) {
			out.append("""
					//
					// The host waits for this module (--host-timer): instantiate answers a promise,
					// _initialize and every entry point are entered through WebAssembly.promising,
					// and calls are serialised onto one queue -- but for a call that IDLES, which
					// lets the next one in until it is rung or its time comes, and then takes its
					// turn again. A host that suspends marks its own entries, suspending(async
					// (...) => ...). Host state that belongs to ONE call is handed over with it,
					// and is `lisp.context` whenever that call runs:
					""");
			String first = jsName(surface.exports().get(0).exportName());
			out.append("//\n//   await lisp.serially((entry) => entry.").append(first).append("(...), state);\n");
			if (surface.turn()) {
				out.append("""
						//
						// What the program's threads have left with no call idling goes on in turns of
						// its own (env.wake), each a call on the same queue; `lisp.turning` is a
						// promise that settles once none is armed, for a host that has to be kept
						// alive meanwhile.
						""");
			}
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
			case OCTETS -> "octets";
			case VOID -> "{}";
			case STRING, S_EXPR -> "text";
			case BOOL -> "flag";
			default -> "number";
		};
	}

	// A canonical-ABI entry's sketch: its WIT parameters in, a JavaScript value out --
	// the ok one where the result is a `result`, whose error arm the host throws.
	private static String sketch(WitImport imp) {
		List<String> params = new ArrayList<>();
		for (String name : imp.paramNames()) {
			params.add(jsName(name));
		}
		boolean answers = imp.result() != null && !(imp.raises() && imp.result() instanceof CanonicalLayout.Cases cases
				&& cases.payloads().get(0) == null);
		return "(" + String.join(", ", params) + ") => " + (answers ? "value" : "{}");
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
			case OCTETS -> "octets";
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
		out.append(surface.waits() ? "export async function instantiate(module" : "export function instantiate(module")
			.append(surface.needsHost() ? ", host = {}" : "")
			.append(") {\n  let exports;\n");
		memoryHelpers(out, surface);
		imports(out, surface);
		out.append("  const instance = new WebAssembly.Instance(module, imports);\n  exports = instance.exports;\n");
		startup(out, surface);
		if (surface.needsHost() && !surface.waits()) {
			out.append("  loading = false;\n");
		}
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
		boolean fetch = writesFetch(surface);
		List<WitImport> answered = answered(surface);
		List<WitImport> keyvalue = answered.stream().filter(HostGlueEmitter::keyvalue).toList();
		boolean sockets = writesSockets(surface);
		if (!fetch && answered.isEmpty() && !sockets) {
			return;
		}
		boolean pulled = fetch && pullsReplyBody(surface);
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
		if (!keyvalue.isEmpty()) {
			out.append("""
					 * @param {Function} stores (identifier) => the store of wasi:keyvalue that
					 *   identifier names: a Workers KV namespace, or anything shaped like one (get,
					 *   put, delete, list); anything else is a store this host does not have
					""");
		}
		if (sockets) {
			out.append("""
					 * @param {object} [sockets] what hostSockets() answers: the module's sockets,
					 *   over connect() of cloudflare:sockets unless it was given another
					""");
		}
		out.append(" * @returns {object} the import-object entries this file implements itself\n */\n");
		List<String> parameters = new ArrayList<>();
		if (pulled && !perReply) {
			parameters.add("lisp");
		}
		if (!keyvalue.isEmpty()) {
			parameters.add("stores");
		}
		if (sockets) {
			parameters.add("sockets = hostSockets()");
		}
		out.append("export function defaultHost(").append(String.join(", ", parameters)).append(") {\n");
		if (!keyvalue.isEmpty()) {
			keyvalueHelpers(out);
		}
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
		if (fetch) {
			fetchEntries(out, pulled, perReply);
		}
		memberEntries(out, answered);
		if (sockets) {
			out.append("    ").append(jsKey(HostSockets.MODULE)).append(": sockets.tcp,\n");
		}
		out.append("  };\n}\n");
	}

	// --host-sockets' host half: the module's sockets over connect() of
	// cloudflare:sockets, which is the same in every program (compiler/HostSockets) and
	// so is written here rather than asked for. A function of its own beside
	// defaultHost(), because worker() ends a call's sockets with the call and so needs
	// the handle on them that an import object does not give.
	private static void hostSockets(StringBuilder out) {
		out.append("""

				/**
				 * The module's sockets (--host-sockets): outbound TCP connections made by
				 * `connect` -- connect() of cloudflare:sockets where none is given, or anything
				 * shaped like it: `({ hostname, port }, options)` in; `opened`, `readable`,
				 * `writable` and `close()` out. The host only connects: the module refuses a
				 * bind, a listen and an accept itself.
				 *
				 * What comes of a socket comes while the module is suspended, so every wait of
				 * the module's is `wait` below -- for the octets of a read, the peer's close, a
				 * failure, the end of a connect -- and `read` then takes what has come; a write
				 * is handed to the platform at once and `flush` answers once it is sent.
				 *
				 * @param {Function} [connect] the platform's connect
				 * @param {Function} [owner] () => whose a socket made now is: the call in the
				 *   module, where a host keeps them apart
				 * @returns {object} `tcp` -- the import entries, for defaultHost();
				 *   `expire(reason, of)` -- ends every socket open now, or those of one
				 *   owner, so that a use of one from then on fails with the reason
				 */
				export function hostSockets(connect, owner = () => undefined) {
				  const connector = async () => connect ?? (await import("cloudflare:sockets")).connect;
				  // A socket is the number the module was answered; a failed call answers -1
				  // and leaves its reason where lastError reads it.
				  const sockets = new Map();
				  let serial = 0;
				  let lastError = "";
				  const words = (error) => String(error?.message ?? error);
				  const fail = (error) => {
				    lastError = words(error);
				    return -1;
				  };
				  // Whoever waits is woken when a socket has something new, and looks again.
				  // What a wait waits on is a promise of its own call, made there and gone
				  // with it: a Worker cancels what one request left for another to settle,
				  // so nothing pending is kept between calls.
				  const waiters = new Set();
				  const rang = () => {
				    for (const wake of waiters) wake();
				    waiters.clear();
				  };
				  // Whether a socket has something for the module: its connect has ended, it
				  // failed, octets or the peer's close have come -- or it is closed, which
				  // ends whatever waited for it.
				  const has = (entry) =>
				    entry === undefined ||
				    entry.failed !== null ||
				    entry.state === "connected" ||
				    (entry.state === "open" && (entry.chunks.length > 0 || entry.eof));
				  // The index of the first socket that has, of host numbers four octets
				  // each, least significant first; -1 for none.
				  const first = (hosts) => {
				    const numbers = new DataView(hosts.buffer, hosts.byteOffset, hosts.byteLength);
				    for (let i = 0; 4 * i < hosts.length; i++) {
				      if (has(sockets.get(numbers.getInt32(4 * i, true)))) return i;
				    }
				    return -1;
				  };
				  // ONE read ahead of the module, never more: the chunk waits here until the
				  // module has taken it, so a peer the program does not read is not read
				  // into this side's memory either.
				  const pump = (entry) => {
				    if (entry.reading || entry.chunks.length > 0 || entry.eof || entry.failed !== null) return;
				    entry.reading = true;
				    // A socket that is over -- closed, or ended with its request -- wakes
				    // nobody: what comes of it then comes to a call that is not its own.
				    entry.reader.read().then(
				      ({ value, done }) => {
				        entry.reading = false;
				        if (entry.over) return;
				        if (done) entry.eof = true;
				        else if (value.length > 0) entry.chunks.push(value);
				        else return pump(entry);
				        rang();
				      },
				      (error) => {
				        entry.reading = false;
				        if (entry.over) return;
				        entry.failed ??= words(error);
				        rang();
				      },
				    );
				  };
				  // The connected socket a call is about, or null with the reason said.
				  const open = (h) => {
				    const entry = sockets.get(h);
				    if (entry === undefined) fail("Socket closed");
				    else if (entry.failed !== null) fail(entry.failed);
				    else if (entry.state !== "open") fail("Socket is not connected");
				    else return entry;
				    return null;
				  };
				  // An end of a connection as the platform says it, "host:port".
				  const end = (address) => {
				    const at = typeof address === "string" ? address.lastIndexOf(":") : -1;
				    return at < 0
				      ? null
				      : { host: address.slice(0, at).replace(/^\\[|\\]$/g, ""), port: Number(address.slice(at + 1)) };
				  };
				  const tcp = {
				    socket: () => {
				      const h = ++serial;
				      sockets.set(h, {
				        state: "new",
				        owner: owner(),
				        over: false,
				        failed: null,
				        chunks: [],
				        eof: false,
				        reading: false,
				        sent: Promise.resolve(),
				      });
				      return h;
				    },
				    // The connect only BEGINS here: the module waits for its end among
				    // whatever else it waits for, and `end` then says how it ended.
				    begin: (h, hostname, port) => {
				      const entry = sockets.get(h);
				      if (entry === undefined) return fail("Socket closed");
				      if (entry.state !== "new") return fail("already connected");
				      entry.state = "connecting";
				      entry.port = port;
				      (async () => {
				        // allowHalfOpen: the peer's close ends the reads and no more, as
				        // a socket's does everywhere else.
				        const socket = (await connector())(
				          { hostname, port },
				          { secureTransport: "off", allowHalfOpen: true },
				        );
				        entry.socket = socket;
				        entry.info = await socket.opened;
				        // over meanwhile: nobody is left to use it
				        if (entry.over) return socket.close();
				        entry.reader = socket.readable.getReader();
				        entry.writer = socket.writable.getWriter();
				        entry.state = "connected";
				        rang();
				      })().catch((error) => {
				        if (entry.over) return;
				        entry.failed ??= words(error);
				        rang();
				      });
				      return 0;
				    },
				    end: (h) => {
				      const entry = sockets.get(h);
				      if (entry === undefined) return fail("Socket closed");
				      if (entry.failed !== null) return fail(entry.failed);
				      if (entry.state !== "connected") return fail("Socket is not connected");
				      entry.state = "open";
				      pump(entry);
				      return 0;
				    },
				    // At most `max` of the octets that have come; none at the peer's close,
				    // and where the socket failed, lastError then saying why.
				    read: (h, max) => {
				      lastError = "";
				      const entry = open(h);
				      if (entry === null) return null;
				      const chunk = entry.chunks[0];
				      if (chunk === undefined) {
				        if (!entry.eof) fail("nothing has come");
				        return null;
				      }
				      if (chunk.length <= max) {
				        entry.chunks.shift();
				        pump(entry);
				        return chunk;
				      }
				      entry.chunks[0] = chunk.subarray(max);
				      return chunk.subarray(0, max);
				    },
				    readable: (h) => (sockets.get(h)?.chunks.length > 0 ? 1 : 0),
				    write: (h, octets) => {
				      const entry = open(h);
				      if (entry === null) return -1;
				      const written = entry.writer.write(octets);
				      entry.sent = entry.sent.then(() => written);
				      // a failure is flush's to report, not an unhandled rejection
				      entry.sent.catch(() => {});
				      return 0;
				    },
				    flush: suspending(async (h) => {
				      const entry = open(h);
				      if (entry === null) return -1;
				      try {
				        await entry.sent;
				        return 0;
				      } catch (error) {
				        return fail(error);
				      }
				    }),
				    // What was written is sent first; a write that failed does not stop
				    // the close. The platform's own close is not waited for: of a socket
				    // still connecting it answers only once the connect has ended.
				    close: suspending(async (h) => {
				      const entry = sockets.get(h);
				      if (entry === undefined) return;
				      sockets.delete(h);
				      const sent = entry.over ? null : entry.sent;
				      entry.over = true;
				      rang();
				      try {
				        await sent;
				      } catch {}
				      try {
				        Promise.resolve(entry.socket?.close()).catch(() => {});
				      } catch {}
				    }),
				    shutdown: (h, output) => {
				      const entry = open(h);
				      if (entry === null) return -1;
				      if (output) {
				        entry.sent = entry.sent.then(() => entry.writer.close());
				        entry.sent.catch(() => {});
				      } else {
				        entry.reader.cancel().catch(() => {});
				        entry.chunks.length = 0;
				        entry.eof = true;
				      }
				      return 0;
				    },
				    // The platform's socket has no option to set: each is kept by the
				    // module and answered back. Urgent data (5) it cannot send.
				    option: (h, option) => (option === 5 ? fail("the host's socket sends no urgent data") : 0),
				    localPort: (h) => end(sockets.get(h)?.info?.localAddress)?.port ?? 0,
				    localAddress: (h) => end(sockets.get(h)?.info?.localAddress)?.host ?? "0.0.0.0",
				    peerAddress: (h) => end(sockets.get(h)?.info?.remoteAddress)?.host ?? "0.0.0.0",
				    peerPort: (h) => {
				      const entry = sockets.get(h);
				      return end(entry?.info?.remoteAddress)?.port ?? entry?.port ?? 0;
				    },
				    poll: (hosts) => first(hosts),
				    // The one place the module waits: until a socket has something, or
				    // `millis` have passed (-1: no limit). A wait of no time still returns to
				    // the event loop, which is where anything comes from.
				    wait: suspending(async (hosts, millis) => {
				      let timer;
				      let wake;
				      const late =
				        millis < 0
				          ? null
				          : new Promise((resolve) => (timer = setTimeout(() => resolve(true), millis)));
				      try {
				        for (;;) {
				          const at = first(hosts);
				          if (at >= 0) return at;
				          const rung = new Promise((resolve) => waiters.add((wake = resolve)));
				          if ((await (late ? Promise.race([rung, late]) : rung)) === true) return first(hosts);
				        }
				      } finally {
				        clearTimeout(timer);
				        waiters.delete(wake);
				      }
				    }),
				    lastError: () => lastError,
				  };
				  // A socket with no owner was made outside any call -- while the module
				  // loaded, in whichever request made the instance -- and ends with the
				  // first call that does.
				  const expire = (reason, of) => {
				    for (const entry of sockets.values()) {
				      if (entry.over) continue;
				      if (of !== undefined && entry.owner !== undefined && entry.owner !== of) continue;
				      entry.over = true;
				      entry.failed = reason;
				      entry.chunks.length = 0;
				      // close() of a socket whose connect failed throws at once
				      try {
				        Promise.resolve(entry.socket?.close()).catch(() => {});
				      } catch {}
				    }
				  };
				  return { tcp, expire };
				}
				""");
	}

	// Whether --host-sockets' host half is written: the module imports the provider's
	// socket functions and nothing else of that module, and an entry point can suspend
	// -- a wait is a promise, which only a JSPI wrapper lets answer an import.
	private static boolean writesSockets(Surface surface) {
		boolean any = false;
		for (Import imp : surface.imports()) {
			if (HostSockets.MODULE.equals(imp.module())) {
				if (!HostSockets.declares(imp.module(), imp.field(), imp.paramTypes(), imp.returnType())) {
					return false;
				}
				any = true;
			}
		}
		return any && canSuspend(surface);
	}

	// env.fetch's two entries, as --host-fetch fixes them.
	private static void fetchEntries(StringBuilder out, boolean pulled, boolean perReply) {
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
		for (FetchResponseShape.Field field : FetchResponseShape.hostRequestFields()) {
			if ("octets".equals(field.name())) {
				// An octet body arrives one character an octet: the platform sends a
				// Uint8Array as it is.
				out.append("            body: request.")
					.append(requestField("body"))
					.append(" ?? (request.")
					.append(requestField("octets"))
					.append(" === undefined ? undefined : Uint8Array.from(request.")
					.append(requestField("octets"))
					.append(", (c) => c.charCodeAt(0))),\n");
			}
			else if (!"url".equals(field.name()) && !"body".equals(field.name())) {
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
		out.append("""
				          // The platform's fetch decodes a content coding it knows, so the octets
				          // the module reads are the decoded ones -- and the head says so, as
				          // every other transport's does: the field naming the coding goes, and
				          // the length of the coded octets with it.
				          const coding = response.headers.get("content-encoding");
				          const decoded = coding !== null
				            && coding.split(",").every((c) => /^\\s*(x-gzip|gzip|deflate|br)\\s*$/i.test(c));
				""");
		out.append("          return JSON.stringify({\n");
		for (FetchResponseShape.Field field : FetchResponseShape.responseFields()) {
			String value = switch (field.name()) {
				case "status" -> "response.status";
				// An ARRAY of pairs, never an object: a name may repeat.
				case "headers" -> "[...response.headers].filter(([name]) => !decoded"
						+ " || (name !== \"content-encoding\" && name !== \"content-length\"))";
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
		out.append("    },\n");
	}

	/** The {@code wasi:keyvalue} store {@code defaultHost()} answers over Workers KV. */
	private static final String KEYVALUE_STORE = "wasi:keyvalue/store@0.2.0-draft2";

	/** Its batch interface, answered over the same namespaces. */
	private static final String KEYVALUE_BATCH = "wasi:keyvalue/batch@0.2.0-draft2";

	/**
	 * A member of a WIT interface {@code defaultHost()} answers: {@code wasi:keyvalue}'s
	 * over Workers KV, {@code wasi:logging}'s on the console.
	 *
	 * @param signature the WIT function it answers: one the WIT spells otherwise is left
	 * to the host
	 * @param suspends whether its answer is a promise (Workers KV's are)
	 * @param entry the entry's JavaScript value
	 */
	private record Member(String signature, boolean suspends, String entry) {
	}

	/** The interface of {@code wasi:logging} {@code defaultHost()} answers. */
	private static final String LOGGING = "wasi:logging/logging@0.1.0-draft";

	/**
	 * Its {@code log}, answered synchronously on the console method of the level, which
	 * is what a Worker's logs show and filter by: trace and debug {@code console.debug}
	 * ({@code console.trace} would add a stack), info, warn and error their own, critical
	 * {@code console.error}; the context ahead of the message where there is one.
	 */
	private static final Member LOGGING_LOG = new Member("func(level: level, context: string, message: string)", false,
			"""
					(level, context, message) => {
					  const text = context === "" ? message : `${context}: ${message}`;
					  switch (level) {
					    case "trace":
					    case "debug":
					      console.debug(text);
					      break;
					    case "info":
					      console.info(text);
					      break;
					    case "warn":
					      console.warn(text);
					      break;
					    default:
					      console.error(text);
					  }
					}""");

	/**
	 * The members, by import module and field. Only {@code 0.2.0-draft2}'s, which
	 * {@code servlet-httpserver-keyvalue} and the sluice wicket speak: the first draft
	 * spells {@code list-keys}' cursor {@code u64}, and a Workers KV cursor is a string.
	 * {@code atomics} is none of them: Workers KV has no compare-and-swap and no
	 * increment, so an answer would claim an atomicity it does not have.
	 */
	private static final Map<String, Member> KEYVALUE = Map.ofEntries(
			Map.entry(KEYVALUE_STORE + " open",
					new Member("func(identifier: string) -> result<bucket, error>", false, """
							(identifier) => {
							  const bucket = stores(identifier);
							  // A store is a KV namespace; no binding of the name, or a binding
							  // that is a variable or a secret, is a store this host has not got.
							  if (typeof bucket?.get !== "function" || typeof bucket.put !== "function") {
							    throw new WitError({ tag: "no-such-store" });
							  }
							  return bucket;
							}""")),
			Map.entry(KEYVALUE_STORE + " [method]bucket.get",
					new Member("func(key: string) -> result<option<list<u8>>, error>", true, """
							suspending(
							  kv(async (bucket, key) => held(await bucket.get(key, "arrayBuffer"))),
							)""")),
			Map.entry(KEYVALUE_STORE + " [method]bucket.set",
					new Member("func(key: string, value: list<u8>) -> result<_, error>", true, """
							suspending(
							  kv(async (bucket, key, value) => {
							    await bucket.put(key, value);
							  }),
							)""")),
			Map.entry(KEYVALUE_STORE + " [method]bucket.delete",
					new Member("func(key: string) -> result<_, error>", true, """
							suspending(
							  kv(async (bucket, key) => {
							    await bucket.delete(key);
							  }),
							)""")),
			Map.entry(KEYVALUE_STORE + " [method]bucket.exists",
					new Member("func(key: string) -> result<bool, error>", true, """
							suspending(
							  kv(async (bucket, key) => (await bucket.get(key, "arrayBuffer")) !== null),
							)""")),
			Map.entry(KEYVALUE_STORE + " [method]bucket.list-keys",
					new Member("func(cursor: option<string>) -> result<key-response, error>", true, """
							suspending(
							  kv(async (bucket, cursor) => {
							    const page = await bucket.list(cursor === undefined ? {} : { cursor });
							    return {
							      keys: page.keys.map((key) => key.name),
							      cursor: page.list_complete ? undefined : page.cursor,
							    };
							  }),
							)""")),
			Map.entry(KEYVALUE_BATCH + " get-many", new Member(
					"func(bucket: borrow<bucket>, keys: list<string>) -> result<list<tuple<string, option<list<u8>>>>, error>",
					true, """
							suspending(
							  kv(async (bucket, keys) =>
							    Promise.all(
							      keys.map(async (key) => [key, held(await bucket.get(key, "arrayBuffer"))]),
							    ),
							  ),
							)""")),
			Map.entry(KEYVALUE_BATCH + " set-many", new Member(
					"func(bucket: borrow<bucket>, key-values: list<tuple<string, list<u8>>>) -> result<_, error>", true,
					"""
							suspending(
							  kv(async (bucket, pairs) => {
							    await Promise.all(pairs.map(([key, value]) => bucket.put(key, value)));
							  }),
							)""")),
			Map.entry(KEYVALUE_BATCH + " delete-many",
					new Member("func(bucket: borrow<bucket>, keys: list<string>) -> result<_, error>", true, """
							suspending(
							  kv(async (bucket, keys) => {
							    await Promise.all(keys.map((key) => bucket.delete(key)));
							  }),
							)""")));

	/**
	 * Whether the generated glue answers a canonical-ABI import synchronously -- a
	 * resource's release, which it implements itself, and a member its
	 * {@code defaultHost()} answers without a promise -- which the module may then call
	 * while it loads. Any other one may suspend: the host is JavaScript, whose I/O
	 * answers promises.
	 * @param module the import module, an interface's canonical id
	 * @param field the import field, a canonical-ABI function name
	 * @return {@code true} when the glue never suspends there
	 */
	public static boolean answersSynchronously(String module, String field) {
		if (field.startsWith(RESOURCE_DROP)) {
			return true;
		}
		Member member = member(module, field);
		return member != null && !member.suspends();
	}

	// The member defaultHost() answers at an import, or null.
	private static @Nullable Member member(String module, String field) {
		return LOGGING.equals(module) && "log".equals(field) ? LOGGING_LOG : KEYVALUE.get(module + " " + field);
	}

	// The canonical-ABI imports defaultHost() answers: wasi:keyvalue's and wasi:logging's
	// members, where the WIT is the interface's own.
	private static List<WitImport> answered(Surface surface) {
		List<WitImport> answered = new ArrayList<>();
		for (List<WitImport> group : witByModule(surface).values()) {
			for (WitImport imp : group) {
				if (answers(imp)) {
					answered.add(imp);
				}
			}
		}
		return answered;
	}

	private static boolean answers(WitImport imp) {
		Member member = member(imp.module(), imp.field());
		return member != null && member.signature().equals(imp.signature());
	}

	// Whether an import is one of wasi:keyvalue's, whose answers need the Worker's
	// stores.
	private static boolean keyvalue(WitImport imp) {
		return KEYVALUE.containsKey(imp.module() + " " + imp.field());
	}

	// What every wasi:keyvalue entry shares: a KV call that fails is the error arm's
	// `other`, and a value KV keeps is its octets.
	private static void keyvalueHelpers(StringBuilder out) {
		out.append("""
				  // wasi:keyvalue over Workers KV. A store is the KV namespace `stores` answers
				  // for its identifier, and a bucket that namespace; a value is the octets KV
				  // keeps, absent where KV answers null. A KV call that fails -- a limit, the
				  // network -- is the error arm's `other`, which the program sees as the
				  // condition a store's failure is.
				  const kv =
				    (call) =>
				    async (...args) => {
				      try {
				        return await call(...args);
				      } catch (error) {
				        throw new WitError({ tag: "other", val: String(error?.message ?? error) });
				      }
				    };
				  const held = (value) => (value === null ? undefined : new Uint8Array(value));
				""");
	}

	private static void memberEntries(StringBuilder out, List<WitImport> answered) {
		String module = null;
		for (WitImport imp : answered) {
			if (!imp.module().equals(module)) {
				if (module != null) {
					out.append("    },\n");
				}
				module = imp.module();
				out.append("    ").append(quoted(module)).append(": {\n");
			}
			String entry = Objects.requireNonNull(member(imp.module(), imp.field())).entry();
			out.append("      ")
				.append(jsKey(imp.field()))
				.append(": ")
				.append(entry.replace("\n", "\n      "))
				.append(",\n");
		}
		if (module != null) {
			out.append("    },\n");
		}
	}

	// Every request-record field is answered from the same-named property of the parsed
	// envelope; the switch exists so a field this half does not carry fails the BUILD
	// rather than crossing as undefined.
	private static String requestField(String name) {
		return switch (name) {
			case "url", "method", "headers", "body", "octets" -> name;
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
		if (HostSockets.MODULE.equals(imp.module())) {
			return writesSockets(surface) ? "defaultHost()" : null;
		}
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
		return surface.imports().stream().allMatch(imp -> answeredBy(surface, imp) != null)
				&& surface.witImports().stream().allMatch(imp -> answeredBy(imp) != null);
	}

	// Which half answers a canonical-ABI import: this file a resource's release,
	// defaultHost() the members it implements (wasi:keyvalue over Workers KV,
	// wasi:logging on the console).
	private static @Nullable String answeredBy(WitImport imp) {
		if (imp.drop()) {
			return "this file";
		}
		return answers(imp) ? "defaultHost()" : null;
	}

	/**
	 * The Worker's entry ({@code --emit-worker-entry}): the three lines that import the
	 * module and the glue and export {@code worker(module)}. Unlike the glue it is the
	 * host's file once written -- where a host takes something over -- so it carries no
	 * {@link #MARKER} and the CLI never overwrites it.
	 * @param fileName the glue's own file name, beside which the entry is written
	 * @param surface the module's declared host-facing surface
	 * @return the ES module source
	 * @throws UnsupportedOperationException when the glue has no {@code worker()}, or one
	 * that leaves an import to its caller: the three lines would not be the Worker
	 */
	public static String workerEntry(String fileName, Surface surface) {
		if (surface.envelopeExport() == null) {
			throw new UnsupportedOperationException("--emit-worker-entry: this module has no reactor entry point"
					+ " (rontolisp:http-handler), so its glue has no worker() for a Worker's " + WORKER_ENTRY
					+ " to export; instantiate() it from an entry of your own");
		}
		List<String> leftOver = new ArrayList<>(surface.imports()
			.stream()
			.filter(imp -> answeredBy(surface, imp) == null)
			.map(imp -> imp.module() + "." + imp.field())
			.distinct()
			.toList());
		surface.witImports()
			.stream()
			.filter(imp -> answeredBy(imp) == null)
			.map(imp -> imp.module() + "." + imp.field())
			.distinct()
			.forEach(leftOver::add);
		if (!leftOver.isEmpty()) {
			throw new UnsupportedOperationException("--emit-worker-entry: the glue does not implement " + leftOver
					+ ", so worker(module) alone would fail at the first request; write " + WORKER_ENTRY
					+ " yourself and hand those imports to worker(module, { host })");
		}
		return """
				// The Worker. rontolisp --emit-worker-entry wrote this file once and never
				// overwrites it, so it is yours to edit: worker() takes { remoteAddr, host }
				// as its second argument (see ./%2$s).

				import module from "./%1$s";
				import { worker } from "./%2$s";

				export default worker(module);
				""".formatted(moduleFile(fileName), fileName);
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
		List<WitImport> answered = answered(surface);
		boolean keyvalue = answered.stream().anyMatch(HostGlueEmitter::keyvalue);
		boolean sockets = writesSockets(surface);
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
		else if (keyvalue) {
			out.append("""
					`host` -- import entries, laid over defaultHost()'s
					 *   one at a time; `keyvalue` -- { identifier: a binding's name, or a KV
					 *   namespace }, the stores of wasi:keyvalue, where "" is the binding KEYVALUE
					 *   and any other identifier the binding of its own name unless named here;
					 *   `remoteAddr` -- """);
		}
		else if (writesFetch(surface) || !answered.isEmpty() || sockets) {
			out.append("`host` -- import entries, laid over defaultHost()'s\n *   one at a time; ");
			if (sockets) {
				out.append("`connect` -- what the module's sockets are made with, in\n"
						+ " *   place of connect() of cloudflare:sockets; ");
			}
			out.append("`remoteAddr` -- ");
		}
		else {
			out.append("`host` -- one plain function per import, keyed by\n"
					+ " *   module and field, as `instantiate` takes it; `remoteAddr` -- ");
		}
		out.append("""
				(request, env, ctx) => the client
				 *   address, since which header carries it is the platform's business and not
				 *   this file's (`(r) => r.headers.get("cf-connecting-ip")` on Cloudflare)
				""");
		out.append("""
				 * @returns {object} `{ fetch(request, env, ctx) }`
				 */
				export function worker(module, options = {}) {
				""");
		if (surface.waits()) {
			out.append("""
					  // The instance, as it is being made and once it is made. A call that TRAPPED
					  // retires it (discard): it skipped its arena reset and its Lisp state may be
					  // half-written, so nothing else may run on it, and a call idling in it wakes
					  // to a refusal. A Lisp ERROR is not this -- the transport answers 500 itself
					  // and the instance is fine.
					  let instance = null;
					  let made = null;
					""");
		}
		else {
			out.append("""
					  let instance = null;
					  // Set when a call TRAPPED: that instance skipped its arena reset and its Lisp
					  // state may be half-written, so nothing else may run on it. A Lisp ERROR is not
					  // this -- the transport answers 500 itself and the instance is fine.
					  let poisoned = false;
					""");
		}
		workerBodyState(out, surface);
		workerHost(out, surface);
		if (surface.waits()) {
			out.append("""
					  const live = () => {
					    if (made?.discarded) {
					      instance = null;
					      made = null;
					    }
					""");
			out.append("    return (instance ??= instantiate(module")
				.append(surface.needsHost() ? ", host" : "")
				.append(").then(\n")
				.append("""
						      (lisp) => (made = lisp),
						      (error) => {
						        instance = null;
						        throw error;
						      },
						    ));
						  };
						""");
		}
		else {
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
		}
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
		if (surface.waits()) {
			out.append("      let lisp = null;\n");
		}
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
		if (surface.waits()) {
			if (bodies) {
				out.append("""
						        // The call's own request body and response, the module's whenever it runs.
						        const call = { body: octets, chunks: [] };
						""");
			}
			else if (sockets) {
				out.append("""
						        // The call, whose own the sockets made while it is in the module are.
						        const call = {};
						""");
			}
			out.append("        lisp = await live();\n        const head = JSON.parse(\n");
			out.append(keyvalue || sockets ? "          await lisp.serially(async (inside) => {\n"
					: "          await lisp.serially((inside) => {\n");
			if (keyvalue || sockets) {
				if (keyvalue) {
					out.append("            bindings = env;\n");
				}
				out.append("            entered = true;\n");
				out.append("            try {\n");
				out.append("              return await inside.").append(entry).append("(input);\n");
				out.append("            } finally {\n");
				if (keyvalue) {
					out.append("              const puts = handOver();\n              ctx?.waitUntil?.(puts);\n");
				}
				if (sockets) {
					out.append(
							"              sockets.expire(\"Socket closed: the request that opened it has ended\", call);\n");
				}
				out.append("            }\n");
			}
			else {
				out.append("            entered = true;\n");
				out.append("            return inside.").append(entry).append("(input);\n");
			}
			out.append(bodies || sockets ? "          }, call),\n        );\n" : "          }),\n        );\n");
			if (surface.turn()) {
				out.append("""
						        // What the threads have left after the answer goes on in turns of the
						        // program's own, which the platform keeps the Worker for.
						        if (lisp.turning) ctx?.waitUntil?.(lisp.turning);
						""");
			}
		}
		else if (suspends && surface.reentrant()) {
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
					""");
			out.append(keyvalue || sockets ? "          await live().serially(async (lisp) => {\n"
					: "          await live().serially((lisp) => {\n");
			out.append("""
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
			if (keyvalue || sockets) {
				// The bindings a call's stores are opened in are its request's; its
				// writes are handed over once it is over, whichever way it ends, and
				// put behind its answer: the platform keeps the Worker for them
				// (ctx.waitUntil), so the client waits for no write. And the sockets a
				// call left open end with it.
				if (keyvalue) {
					out.append("            bindings = env;\n");
				}
				out.append("            entered = true;\n");
				out.append("            try {\n");
				out.append("              return await lisp.").append(entry).append("(input);\n");
				out.append("            } finally {\n");
				if (keyvalue) {
					out.append("              const puts = handOver();\n              ctx?.waitUntil?.(puts);\n");
				}
				if (sockets) {
					out.append(
							"              sockets.expire(\"Socket closed: the request that opened it has ended\");\n");
				}
				out.append("            }\n");
			}
			else {
				out.append("            entered = true;\n");
				out.append("            return lisp.").append(entry).append("(input);\n");
			}
			out.append("          }),\n        );\n");
		}
		else {
			// Nothing can be parked, so nothing can have poisoned the instance between
			// binding it and calling it: a synchronous call cannot be interleaved.
			if (bodies) {
				out.append("        requestBody = octets;\n        responseChunks = [];\n");
			}
			// A store the module only opens is a binding all the same; with nothing that
			// suspends there is nothing it writes.
			if (keyvalue) {
				out.append("        bindings = env;\n");
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
				""");
		out.append(
				surface.waits() ? "        if (entered) lisp.discard();\n" : "        if (entered) poisoned = true;\n");
		out.append("""
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
		if (surface.waits()) {
			out.append("""
					  // The request body the module pulls, and the response body coming back the
					  // same way, are their call's, handed to the module with it: a call that idles
					  // lets others in, whose bodies are theirs.
					  const collected = (chunks) => {
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
		List<WitImport> answered = answered(surface);
		boolean keyvalue = answered.stream().anyMatch(HostGlueEmitter::keyvalue);
		boolean sockets = writesSockets(surface);
		if (!fetch && !bodies && answered.isEmpty() && !sockets) {
			out.append("  const host = options.host ?? {};\n");
			return;
		}
		if (keyvalue) {
			keyvalueStores(out);
		}
		if (sockets) {
			out.append("""
					  // The module's sockets are this Worker's outbound connections, and a Worker's
					  // socket does not outlive the request that opened it: what a call leaves
					  // open ends with the call, so a program that kept one -- a connection in a
					  // static field, a pool -- fails at its first use of it, by name, rather
					  // than on whatever the platform makes of a stream from another request.
					""");
			// Where calls take turns in the module (--host-timer) a socket is the call's
			// that was in the module when it was made, which is whose request the
			// platform made it in.
			out.append(surface.waits() ? "  const sockets = hostSockets(options.connect, () => made?.context);\n"
					: "  const sockets = hostSockets(options.connect);\n");
		}
		out.append("  const base = ");
		// defaultHost() takes the instance because ITS cursor is the one a second fetch
		// inside one call supersedes; the body entries below need no such thing --
		// and the --reentrant shape needs neither, its readers being keyed per reply.
		List<String> arguments = new ArrayList<>();
		if (fetch && pullsReplyBody(surface) && !surface.reentrant()) {
			arguments.add(surface.waits() ? "() => made" : "() => instance");
		}
		if (keyvalue) {
			arguments.add("stores");
		}
		if (sockets) {
			arguments.add("sockets");
		}
		out.append(fetch || !answered.isEmpty() || sockets ? "defaultHost(" + String.join(", ", arguments) + ")" : "{}")
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
		else if (bodies && surface.waits()) {
			out.append("  base.").append(jsKey(ReactorEnvelope.HOST_MODULE)).append(" = {\n");
			out.append("    ...(base.").append(jsKey(ReactorEnvelope.HOST_MODULE)).append(" ?? {}),\n");
			out.append("    ").append(jsKey(ReactorEnvelope.REQUEST_BODY_FIELD)).append(": () => {\n");
			out.append("""
					      // Handed over ONCE: a chunk source that never answers null is one the
					      // module pulls forever.
					      const call = made.context;
					      const chunk = call.body;
					      call.body = null;
					      return chunk;
					    },
					""");
			out.append("    ")
				.append(jsKey(ReactorEnvelope.RESPONSE_BODY_FIELD))
				.append(": (chunk) => made.context.chunks.push(chunk),\n  };\n");
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

	// wasi:keyvalue's stores in a Worker: its KV namespace bindings, a call's writes held
	// until the call has answered, then put behind the answer -- Workers KV takes one
	// write
	// a second to a key, and a put takes a tenth of a second or more.
	private static void keyvalueStores(StringBuilder out) {
		out.append("""
				  // wasi:keyvalue's stores are this Worker's KV namespace bindings: the default
				  // store, "", is the binding KEYVALUE and any other identifier the binding of
				  // its own name -- unless options.keyvalue maps an identifier to another
				  // binding's name, or to a namespace itself.
				  let bindings = {};
				  // A call's writes wait here until it has answered, and the call reads them back
				  // from here: a session is written at every change of it, several times a
				  // request, and Workers KV takes one write a second to a key. So each key is put
				  // once a call, after its answer, by store name.
				  let writes = new Map();
				  // The writes calls handed over once they answered, by store name and key: the
				  // calls after them read a value here until it is put, and a key is put one write
				  // at a time, a second after the last -- a value handed over meanwhile replaces
				  // the one waiting.
				  const handed = new Map();
				  const stores = (identifier) => {
				    const named = options.keyvalue?.[identifier] ?? (identifier === "" ? "KEYVALUE" : identifier);
				    const namespace = typeof named === "string" ? bindings[named] : named;
				    if (typeof namespace?.get !== "function" || typeof namespace.put !== "function") {
				      return namespace;
				    }
				    const pending = () => {
				      let kept = writes.get(named);
				      if (kept === undefined) {
				        kept = { namespace, values: new Map() };
				        writes.set(named, kept);
				      }
				      return kept.values;
				    };
				    // undefined: nothing held, so the namespace answers; null: deleted.
				    const held = (key) => {
				      const kept = writes.get(named)?.values.get(key);
				      return kept !== undefined ? kept : handed.get(named)?.get(key)?.value;
				    };
				    return {
				      get: async (key, type) => {
				        const kept = held(key);
				        if (kept === undefined) return namespace.get(key, type);
				        return kept === null ? null : kept.slice().buffer;
				      },
				      put: async (key, value) => {
				        pending().set(key, new Uint8Array(value));
				      },
				      delete: async (key) => {
				        pending().set(key, null);
				      },
				      list: (query) => namespace.list(query),
				    };
				  };
				  // A write refused for coming too soon (429) -- another isolate wrote the key
				  // within the second -- is tried again a little later, as Cloudflare asks.
				  const retried = async (write) => {
				    for (let wait = 100; ; wait *= 2) {
				      try {
				        return await write();
				      } catch (error) {
				        if (wait > 1600 || !String(error?.message ?? error).includes("429")) throw error;
				        await new Promise((resolve) => setTimeout(resolve, wait));
				      }
				    }
				  };
				  // Puts a key's latest value until none waits, a second after the last put ended.
				  // Its call answered long ago, so a write that fails is logged, and the value is
				  // read from the namespace again.
				  const drain = async (key, entry) => {
				    while (entry.next) {
				      const wait = entry.at + 1000 - Date.now();
				      if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
				      entry.next = false;
				      const { namespace, value } = entry;
				      try {
				        await retried(() => (value === null ? namespace.delete(key) : namespace.put(key, value)));
				      } catch (error) {
				        console.error(`wasi:keyvalue: the write of ${key} failed:`, error);
				      }
				      entry.at = Date.now();
				    }
				    entry.value = undefined;
				    entry.run = null;
				  };
				  // A call's writes, handed over once it has answered: the promise of their puts,
				  // which never rejects.
				  const handOver = () => {
				    const done = writes;
				    writes = new Map();
				    const now = Date.now();
				    const puts = [];
				    for (const [named, { namespace, values }] of done) {
				      let entries = handed.get(named);
				      if (entries === undefined) {
				        entries = new Map();
				        handed.set(named, entries);
				      }
				      // A key put over a second ago with nothing waiting is nothing to keep.
				      for (const [key, entry] of entries) {
				        if (entry.run === null && now - entry.at >= 1000) entries.delete(key);
				      }
				      for (const [key, value] of values) {
				        let entry = entries.get(key);
				        if (entry === undefined) {
				          entry = { at: 0, run: null };
				          entries.set(key, entry);
				        }
				        Object.assign(entry, { namespace, value, next: true });
				        entry.run ??= drain(key, entry);
				        puts.push(entry.run);
				      }
				    }
				    return Promise.all(puts);
				  };
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
				// ABSENT: a fetch boundary hands over no socket, so the envelope's
				// defaults (HTTP/1.1, the Host header's name and port, no peer port)
				// stand.
				case "protocol", "local-addr", "local-port", "remote-port" -> {
				}
				// ABSENT: the emitted glue serves a whole fetch boundary, so the
				// application is root-mounted. A hand-written host that mounts it under
				// a path sends the key itself.
				case "script-name" -> {
				}
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
						? "head.body ?? collected("
								+ (surface.reentrant() ? "callId" : surface.waits() ? "call.chunks" : "") + ")"
						: "head.body";
				// Logged, never forwarded: the socket transports' report line.
				case "error" -> "head.error";
				default -> throw new UnsupportedOperationException(
						"--emit-js-glue: the reactor response envelope grew a key this mapping does not read: " + key);
			});
		}
		out.append("""
				        // A failed application's head is the plain 500 a socket transport
				        // answers; its report is the Worker's log, never the client's.
				""");
		out.append("        if (")
			.append(replyKey(reply, "error"))
			.append(" !== undefined) console.error(\"HTTP-HANDLER: handler failed:\", ")
			.append(replyKey(reply, "error"))
			.append(");\n");
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
		boolean readBytes = surface.imports()
			.stream()
			.anyMatch(i -> i.paramTypes().contains(BoundaryType.BYTES) || i.paramTypes().contains(BoundaryType.OCTETS))
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
		// An import's :octets RESULT is the :string shape over the host's octets: a
		// Uint8Array as it is, text as its UTF-8 encoding.
		boolean answersOctets = surface.imports().stream().anyMatch(i -> i.returnType() == BoundaryType.OCTETS);
		boolean writeOctets = answersOctets && !surface.reentrant();
		boolean writeParkOctets = answersOctets && surface.reentrant();
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
		if (writeString || write || writeOctets) {
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
		if (pulls || write || answersOctets) {
			out.append("""
					  const octets = (chunk) =>
					    typeof chunk === "string" ? encoder.encode(chunk) : chunk;
					""");
		}
		if (writeOctets) {
			out.append("  const writeOctets = (value) => write(octets(value));\n");
		}
		if (writeParkOctets) {
			out.append("""
					  // An import's octets answer lives in a park block until the MODULE has
					  // copied it out -- which it frees itself with __ronto_park_free.
					  const writeParkOctets = (value) => {
					    const bytes = octets(value);
					    const ptr = exports.__ronto_park_alloc(bytes.length);
					    new Uint8Array(exports.memory.buffer, ptr, bytes.length).set(bytes);
					    return [ptr, bytes.length];
					  };
					""");
		}
	}

	// The import object, and the two things every entry in it needs: a way to hand the
	// host's answer back in either shape, and a way to know which of the entries the host
	// marked as suspending.
	private static void imports(StringBuilder out, Surface surface) {
		Map<String, List<Import>> groups = surface.byModule();
		Map<String, List<WitImport>> witGroups = witByModule(surface);
		if (groups.isEmpty() && witGroups.isEmpty()) {
			out.append("""

					  // This module imports nothing: instantiating it is the whole boundary.
					  const imports = {};
					""");
			return;
		}
		if (surface.needsHost()) {
			hostPlumbing(out, surface);
		}
		if (!witGroups.isEmpty()) {
			canonicalAbi(out, surface);
		}
		if (surface.output() != null) {
			outputStreams(out);
		}
		if (surface.waits()) {
			timerPlumbing(out, surface);
		}
		out.append("\n  const imports = {\n");
		for (Map.Entry<String, List<Import>> group : groups.entrySet()) {
			out.append("    ").append(jsKey(group.getKey())).append(": {\n");
			for (Import imp : group.getValue()) {
				if (imp.equals(surface.entropy())) {
					entropyEntry(out, imp);
				}
				else if (imp.equals(surface.output())) {
					outputEntry(out, imp);
				}
				else if (surface.timer().contains(imp)) {
					timerEntry(out, surface, imp);
				}
				else {
					importEntry(out, surface, imp);
				}
			}
			out.append("    },\n");
		}
		Map<CanonicalLayout, String> layouts = layoutNames(surface);
		for (Map.Entry<String, List<WitImport>> group : witGroups.entrySet()) {
			out.append("    ").append(quoted(group.getKey())).append(": {\n");
			for (WitImport imp : group.getValue()) {
				witEntry(out, imp, layouts);
			}
			out.append("    },\n");
		}
		out.append("  };\n");
	}

	// The canonical-ABI imports grouped by module, each (module, field) once: two
	// bindings of one member are one core import.
	private static Map<String, List<WitImport>> witByModule(Surface surface) {
		Map<String, List<WitImport>> groups = new LinkedHashMap<>();
		Set<String> seen = new LinkedHashSet<>();
		for (WitImport imp : surface.witImports()) {
			if (seen.add(imp.module() + "\0" + imp.field())) {
				groups.computeIfAbsent(imp.module(), m -> new ArrayList<>()).add(imp);
			}
		}
		return groups;
	}

	// The name every distinct layout is written under, in the order the descriptors are
	// written: a layout's parts before it, so each refers to names already declared.
	private static Map<CanonicalLayout, String> layoutNames(Surface surface) {
		Map<CanonicalLayout, String> names = new LinkedHashMap<>();
		for (List<WitImport> group : witByModule(surface).values()) {
			for (WitImport imp : group) {
				imp.params().forEach(layout -> nameLayout(layout, names));
				if (imp.result() != null) {
					nameLayout(imp.result(), names);
				}
			}
		}
		return names;
	}

	private static void nameLayout(CanonicalLayout layout, Map<CanonicalLayout, String> names) {
		if (names.containsKey(layout)) {
			return;
		}
		switch (layout) {
			case CanonicalLayout.ListOf list -> nameLayout(list.element(), names);
			case CanonicalLayout.Cases cases -> cases.payloads().forEach(payload -> {
				if (payload != null) {
					nameLayout(payload, names);
				}
			});
			case CanonicalLayout.Fields fields -> fields.types().forEach(type -> nameLayout(type, names));
			default -> {
			}
		}
		names.put(layout, "wit$" + names.size());
	}

	// A layout as the descriptor the canonical-ABI helpers below read: `k` what it is,
	// `s` and `a` its size and alignment; a list's element `of`; a value of cases its
	// discriminant's width `d`, its payload's offset `o`, the case names `c` and their
	// payloads `of`, its flattening `fl`; a record's keys `f`, its fields `of` at `at`;
	// a handle's resource `r`.
	private static String descriptor(CanonicalLayout layout, Map<CanonicalLayout, String> names) {
		String head = "{ k: \"" + kind(layout) + "\", s: " + layout.size() + ", a: " + layout.align();
		return head + switch (layout) {
			case CanonicalLayout.ListOf list -> ", of: " + names.get(list.element());
			case CanonicalLayout.Cases cases -> {
				List<String> payloads = new ArrayList<>();
				for (CanonicalLayout payload : cases.payloads()) {
					payloads.add(payload == null ? "null" : names.get(payload));
				}
				List<String> labels = new ArrayList<>();
				for (String name : cases.names()) {
					// A result's cases are the JavaScript convention's ok and err.
					labels.add(quoted("result".equals(cases.kind()) && "error".equals(name) ? "err" : name));
				}
				yield ", d: " + cases.discSize() + ", o: " + cases.payloadOffset() + ", c: ["
						+ String.join(", ", labels) + "], of: [" + String.join(", ", payloads) + "], fl: ["
						+ String.join(", ", cases.flat().stream().map(HostGlueEmitter::quoted).toList()) + "]";
			}
			case CanonicalLayout.Fields fields -> {
				List<String> types = new ArrayList<>();
				fields.types().forEach(type -> types.add(names.get(type)));
				String keys = "record".equals(fields.kind()) ? ", f: ["
						+ String.join(", ", fields.names().stream().map(name -> quoted(jsName(name))).toList()) + "]"
						: "";
				yield keys + ", of: [" + String.join(", ", types) + "], at: ["
						+ String.join(", ", fields.offsets().stream().map(String::valueOf).toList()) + "]";
			}
			case CanonicalLayout.Handle handle -> ", r: " + quoted(handle.resource());
			default -> "";
		} + " }";
	}

	private static String kind(CanonicalLayout layout) {
		return switch (layout) {
			case CanonicalLayout.Scalar scalar -> scalar.kind();
			case CanonicalLayout.Text ignored -> "string";
			case CanonicalLayout.Octets ignored -> "bytes";
			case CanonicalLayout.ListOf ignored -> "list";
			case CanonicalLayout.Cases cases -> cases.kind();
			case CanonicalLayout.Fields fields -> fields.kind();
			case CanonicalLayout.Handle handle -> handle.own() ? "own" : "borrow";
		};
	}

	// A JavaScript string literal of a name the WIT grammar spells: letters, digits and
	// `-:/@.[]#`, none of which a double-quoted literal escapes.
	private static String quoted(String name) {
		return "\"" + name + "\"";
	}

	// One canonical-ABI import: the host's own function with its arguments lifted and
	// its answer lowered by the layouts, or a resource's release, which this file
	// implements itself.
	private static void witEntry(StringBuilder out, WitImport imp, Map<CanonicalLayout, String> layouts) {
		if (imp.drop()) {
			out.append("      // ")
				.append(imp.field())
				.append(" -- the resource's release: its value leaves the table, implemented here\n");
			out.append("      ").append(jsKey(imp.field())).append(": (index) => {\n");
			out.append("        const value = resourceOf(")
				.append(quoted(((CanonicalLayout.Handle) imp.params().get(0)).resource()))
				.append(", index, true);\n");
			out.append("        host[").append(quoted(imp.module())).append("]?.[").append(quoted(imp.field()));
			out.append("]?.(value);\n      },\n");
			return;
		}
		List<String> params = new ArrayList<>();
		imp.params().forEach(layout -> params.add(layouts.get(layout)));
		out.append("      // ").append(imp.field()).append(": ").append(imp.signature()).append('\n');
		out.append("      ")
			.append(jsKey(imp.field()))
			.append(": bind(")
			.append(quoted(imp.module()))
			.append(", ")
			.append(quoted(imp.field()))
			.append(", (what, call) =>\n        lifted(what, call, [")
			.append(String.join(", ", params))
			.append("], ")
			.append(imp.result() == null ? "null" : layouts.get(imp.result()))
			.append(", ")
			.append(imp.retptr())
			.append(", ")
			.append(imp.raises())
			.append("),\n      ),\n");
	}

	// The canonical ABI on this side of the boundary: lifting a WIT value off the core
	// values and linear memory a call hands over, lowering one back. Written once, read
	// off the descriptors the compiler's own layouts become -- so the two halves agree by
	// construction on every size, offset and discriminant.
	private static void canonicalAbi(StringBuilder out, Surface surface) {
		out.append(
				"""

						  // The canonical ABI on this side: a WIT value lifted off the core values and
						  // the linear memory a call hands over, an answer lowered back -- each by the
						  // descriptor of its type below (the compiler's own layouts: sizes, offsets,
						  // discriminants). A view is built per access, since lowering allocates and an
						  // allocation may grow, and so detach, the memory.
						  const view = () => new DataView(exports.memory.buffer);
						  // In the module's bump arena, above the mark the calling wrapper took: freed
						  // with the wrapper's own staging once the module has lifted the answer.
						  const alloc = (size, align) =>
						    ((exports.__ronto_alloc(size + align - 1) + align - 1) & -align) >>> 0;

						  // A resource's values stay on this side, in a table of its own; the module
						  // holds an index, handed out when the host answers a value and taken back by
						  // the resource's release (or when the host is handed the value to own).
						  const tables = new Map();
						  const table = (resource) => {
						    let entries = tables.get(resource);
						    if (entries === undefined) {
						      entries = { next: 1, live: new Map() };
						      tables.set(resource, entries);
						    }
						    return entries;
						  };
						  const handle = (resource, value) => {
						    const entries = table(resource);
						    const index = entries.next++;
						    entries.live.set(index, value);
						    return index;
						  };
						  const resourceOf = (resource, index, own) => {
						    const live = table(resource).live;
						    if (!live.has(index)) throw new TypeError(resource + " has no handle " + index);
						    const value = live.get(index);
						    if (own) live.delete(index);
						    return value;
						  };

						  // A value of cases as JavaScript holds it: an option its payload or undefined,
						  // an enum its case's name, a result or a variant { tag, val }.
						  const caseValue = (t, index, payload) =>
						    t.k === "option"
						      ? index === 0
						        ? undefined
						        : payload
						      : t.k === "enum"
						        ? t.c[index]
						        : t.of[index] === null
						          ? { tag: t.c[index] }
						          : { tag: t.c[index], val: payload };
						  const caseIndex = (t, value) => {
						    const index =
						      t.k === "option"
						        ? value == null
						          ? 0
						          : 1
						        : t.c.indexOf(t.k === "enum" ? value : value?.tag);
						    if (index < 0) throw new TypeError(String(value?.tag ?? value) + " is no case of " + t.c);
						    return index;
						  };

						  const loadAs = {
						    bool: (m, p) => m.getUint8(p) !== 0,
						    s8: (m, p) => m.getInt8(p),
						    u8: (m, p) => m.getUint8(p),
						    s16: (m, p) => m.getInt16(p, true),
						    u16: (m, p) => m.getUint16(p, true),
						    s32: (m, p) => m.getInt32(p, true),
						    u32: (m, p) => m.getUint32(p, true),
						    s64: (m, p) => m.getBigInt64(p, true),
						    u64: (m, p) => m.getBigUint64(p, true),
						    f32: (m, p) => m.getFloat32(p, true),
						    f64: (m, p) => m.getFloat64(p, true),
						    char: (m, p) => String.fromCodePoint(m.getUint32(p, true)),
						  };
						  const storeAs = {
						    bool: (m, p, v) => m.setUint8(p, v ? 1 : 0),
						    s8: (m, p, v) => m.setInt8(p, Number(v)),
						    u8: (m, p, v) => m.setUint8(p, Number(v)),
						    s16: (m, p, v) => m.setInt16(p, Number(v), true),
						    u16: (m, p, v) => m.setUint16(p, Number(v), true),
						    s32: (m, p, v) => m.setInt32(p, Number(v), true),
						    u32: (m, p, v) => m.setUint32(p, Number(v), true),
						    s64: (m, p, v) => m.setBigInt64(p, BigInt(v), true),
						    u64: (m, p, v) => m.setBigUint64(p, BigInt(v), true),
						    f32: (m, p, v) => m.setFloat32(p, Number(v), true),
						    f64: (m, p, v) => m.setFloat64(p, Number(v), true),
						    char: (m, p, v) => m.setUint32(p, String(v).codePointAt(0), true),
						  };
						  // A scalar off its core value, and onto one: an i32 arrives signed, an i64
						  // as a BigInt.
						  const fromCore = {
						    bool: (v) => v !== 0,
						    s8: (v) => (v << 24) >> 24,
						    u8: (v) => v & 0xff,
						    s16: (v) => (v << 16) >> 16,
						    u16: (v) => v & 0xffff,
						    s32: (v) => v,
						    u32: (v) => v >>> 0,
						    s64: (v) => v,
						    u64: (v) => BigInt.asUintN(64, v),
						    f32: (v) => v,
						    f64: (v) => v,
						    char: (v) => String.fromCodePoint(v >>> 0),
						  };
						  const toCore = {
						    bool: (v) => (v ? 1 : 0),
						    s64: (v) => BigInt.asIntN(64, BigInt(v)),
						    u64: (v) => BigInt.asIntN(64, BigInt(v)),
						    f32: (v) => Number(v),
						    f64: (v) => Number(v),
						    char: (v) => String(v).codePointAt(0),
						  };
						  const coreOf = { s64: "i64", u64: "i64", f32: "f32", f64: "f64" };

						  const octetsOf = (value) =>
						    typeof value === "string"
						      ? encoder.encode(value)
						      : value instanceof Uint8Array
						        ? value
						        : new Uint8Array(value);
						  const load = (t, ptr) => {
						    switch (t.k) {
						      case "string":
						      case "bytes": {
						        const m = view();
						        const at = m.getUint32(ptr, true);
						        const len = m.getUint32(ptr + 4, true);
						        return t.k === "string"
						          ? decoder.decode(new Uint8Array(exports.memory.buffer, at, len))
						          : new Uint8Array(exports.memory.buffer.slice(at, at + len));
						      }
						      case "list": {
						        const m = view();
						        const at = m.getUint32(ptr, true);
						        const n = m.getUint32(ptr + 4, true);
						        return Array.from({ length: n }, (_, i) => load(t.of, at + i * t.of.s));
						      }
						      case "record":
						        return Object.fromEntries(t.f.map((key, i) => [key, load(t.of[i], ptr + t.at[i])]));
						      case "tuple":
						        return t.of.map((field, i) => load(field, ptr + t.at[i]));
						      case "own":
						      case "borrow":
						        return resourceOf(t.r, view().getInt32(ptr, true), t.k === "own");
						      case "option":
						      case "enum":
						      case "result":
						      case "variant": {
						        const m = view();
						        const index = t.d === 1 ? m.getUint8(ptr) : t.d === 2 ? m.getUint16(ptr, true) : m.getUint32(ptr, true);
						        return caseValue(t, index, t.of[index] === null ? undefined : load(t.of[index], ptr + t.o));
						      }
						      default:
						        return loadAs[t.k](view(), ptr);
						    }
						  };
						  const store = (t, value, ptr) => {
						    switch (t.k) {
						      case "string":
						      case "bytes": {
						        const octets = t.k === "string" ? encoder.encode(String(value)) : octetsOf(value);
						        const at = alloc(octets.length, 1);
						        new Uint8Array(exports.memory.buffer, at, octets.length).set(octets);
						        const m = view();
						        m.setUint32(ptr, at, true);
						        m.setUint32(ptr + 4, octets.length, true);
						        return;
						      }
						      case "list": {
						        const items = [...value];
						        const at = alloc(items.length * t.of.s, t.of.a);
						        items.forEach((item, i) => store(t.of, item, at + i * t.of.s));
						        const m = view();
						        m.setUint32(ptr, at, true);
						        m.setUint32(ptr + 4, items.length, true);
						        return;
						      }
						      case "record":
						        t.f.forEach((key, i) => store(t.of[i], value[key], ptr + t.at[i]));
						        return;
						      case "tuple":
						        t.of.forEach((field, i) => store(field, value[i], ptr + t.at[i]));
						        return;
						      case "own":
						        view().setInt32(ptr, handle(t.r, value), true);
						        return;
						      case "option":
						      case "enum":
						      case "result":
						      case "variant": {
						        const index = caseIndex(t, value);
						        const m = view();
						        if (t.d === 1) m.setUint8(ptr, index);
						        else if (t.d === 2) m.setUint16(ptr, index, true);
						        else m.setUint32(ptr, index, true);
						        if (t.of[index] !== null) store(t.of[index], t.k === "option" ? value : value.val, ptr + t.o);
						        return;
						      }
						      default:
						        storeAs[t.k](view(), ptr, value);
						    }
						  };
						  // A value off core values: `next(type)` takes the next one as that core type.
						  // A case's payload rides the variant's joined types, so each of its values is
						  // taken back to its own type -- an i32 or an f32 out of an i64, an f32 out of
						  // an i32.
						  const bits = new DataView(new ArrayBuffer(8));
						  const coerce = (have, want, value) => {
						    if (have === want) return value;
						    if (want === "f64") {
						      bits.setBigInt64(0, value, true);
						      return bits.getFloat64(0, true);
						    }
						    const word = have === "i64" ? Number(BigInt.asIntN(32, value)) : value;
						    if (want === "i32") return word;
						    bits.setInt32(0, word, true);
						    return bits.getFloat32(0, true);
						  };
						  const liftFlat = (t, next) => {
						    switch (t.k) {
						      case "string":
						      case "bytes": {
						        const at = next("i32") >>> 0;
						        const len = next("i32") >>> 0;
						        return t.k === "string"
						          ? decoder.decode(new Uint8Array(exports.memory.buffer, at, len))
						          : new Uint8Array(exports.memory.buffer.slice(at, at + len));
						      }
						      case "list": {
						        const at = next("i32") >>> 0;
						        const n = next("i32") >>> 0;
						        return Array.from({ length: n }, (_, i) => load(t.of, at + i * t.of.s));
						      }
						      case "record":
						        return Object.fromEntries(t.f.map((key, i) => [key, liftFlat(t.of[i], next)]));
						      case "tuple":
						        return t.of.map((field) => liftFlat(field, next));
						      case "own":
						      case "borrow":
						        return resourceOf(t.r, next("i32"), t.k === "own");
						      case "option":
						      case "enum":
						      case "result":
						      case "variant": {
						        const index = next("i32");
						        const joined = t.fl.slice(1).map((type) => [type, next(type)]);
						        let at = 0;
						        const inner = (want) => {
						          const [have, value] = joined[at++];
						          return coerce(have, want, value);
						        };
						        return caseValue(t, index, t.of[index] === null ? undefined : liftFlat(t.of[index], inner));
						      }
						      default:
						        return fromCore[t.k](next(coreOf[t.k] ?? "i32"));
						    }
						  };
						  // An answer that is one core value -- the result of a function whose result
						  // flattens to one: a scalar, a handle, a value of cases with no payload, a
						  // record or a tuple of one such.
						  const lowerFlat = (t, value) => {
						    switch (t.k) {
						      case "own":
						        return handle(t.r, value);
						      case "record":
						        return lowerFlat(t.of[0], value[t.f[0]]);
						      case "tuple":
						        return lowerFlat(t.of[0], value[0]);
						      case "option":
						      case "enum":
						      case "result":
						      case "variant":
						        return caseIndex(t, value);
						      default:
						        return (toCore[t.k] ?? ((v) => Number(v) | 0))(value);
						    }
						  };

						  // One WIT function the host answers: the core values in, the host handed its
						  // JavaScript arguments, its answer lowered -- into the return area the module
						  // passed last when the result is more than one core value. A result's ok arm
						  // is the answer, its error arm a WitError the host throws; anything else it
						  // throws traps the call, as a failed import does.
						  const lifted = (what, call, params, result, retptr, raises) =>
						    (...core) => {
						      let at = 0;
						      const next = () => core[at++];
						      const args = params.map((t) => liftFlat(t, next));
						      const area = retptr ? core[core.length - 1] >>> 0 : 0;
						      const answer = (value) =>
						        retptr ? store(result, value, area) : result === null ? undefined : lowerFlat(result, value);
						      const ok = raises ? (value) => answer({ tag: "ok", val: value }) : answer;
						      const failed = (error) => {
						        if (raises && error instanceof WitError) return answer({ tag: "err", val: error.payload });
						        throw error;
						      };
						      let value;
						      try {
						        value = call(...args);
						      } catch (error) {
						        return failed(error);
						      }
						      if (typeof value?.then === "function") {
						        if (!marked.has(what)) {
						          console.error(unmarked(what));
						          throw new TypeError(unmarked(what));
						        }
						        return value.then(ok, failed);
						      }
						      return ok(value);
						    };
						""");
		Map<CanonicalLayout, String> layouts = layoutNames(surface);
		out.append("\n  // The layouts of the WIT types crossing here, one per distinct type.\n");
		for (Map.Entry<CanonicalLayout, String> layout : layouts.entrySet()) {
			out.append("  const ")
				.append(layout.getValue())
				.append(" = ")
				.append(descriptor(layout.getKey(), layouts))
				.append(";\n");
		}
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

	// --host-output's two streams, a line at a time. A line the module has not ended
	// waits for its end only until control is back with the host (a microtask): what a
	// call wrote before it trapped is shown whole, and a log line is never held hostage
	// by a request that may not come. Decoded as a stream, so a character split between
	// two writes is one character.
	private static void outputStreams(StringBuilder out) {
		out.append("""

				  // Standard output and error (--host-output), a line at a time: descriptor 1
				  // to console.log, 2 to console.error. A line not yet ended is shown once
				  // control is back with the host, so what a call wrote before it trapped is
				  // shown too.
				  const lines = (log) => {
				    const utf8 = new TextDecoder();
				    let pending = "";
				    let queued = false;
				    const flush = () => {
				      queued = false;
				      if (pending !== "") {
				        log(pending);
				        pending = "";
				      }
				    };
				    return (octets) => {
				      const ended = (pending + utf8.decode(octets, { stream: true })).split("\\n");
				      pending = ended.pop();
				      for (const line of ended) log(line);
				      if (pending !== "" && !queued) {
				        queued = true;
				        queueMicrotask(flush);
				      }
				    };
				  };
				  const streams = [
				    null,
				    lines((line) => console.log("%s", line)),
				    lines((line) => console.error("%s", line)),
				  ];
				""");
	}

	// preview1's fd_write(fd, iovs, iovs_len, nwritten) -> errno over the two streams:
	// every iovec of the call, the count written back, EBADF for any other descriptor.
	private static void outputEntry(StringBuilder out, Import imp) {
		out.append("      // ")
			.append(signature(imp.paramTypes(), imp.returnType()))
			.append(" -- the --host-output standard output and error, implemented here\n");
		out.append("      ").append(jsKey(imp.field())).append(": (fd, iovs, count, written) => {\n");
		out.append("""
				        const stream = streams[fd];
				        if (!stream) return 8;
				        const memory = new DataView(exports.memory.buffer);
				        let total = 0;
				        for (let i = 0; i < count; i++) {
				          const ptr = memory.getUint32(iovs + 8 * i, true);
				          const len = memory.getUint32(iovs + 8 * i + 4, true);
				          stream(new Uint8Array(exports.memory.buffer, ptr, len));
				          total += len;
				        }
				        memory.setUint32(written, total, true);
				        return 0;
				      },
				""");
	}

	// --host-timer: the order the module is entered in, which the waits hold. A queue as
	// the other suspending builds have -- but one whose turn a call that idles hands on,
	// which a promise chain cannot -- and what each call brought along, kept for it.
	private static void timerPlumbing(StringBuilder out, Surface surface) {
		out.append("""

				  // --host-timer: the host waits for the module. One call is in it at a time, as
				  // its re-entry guard demands -- but a call that IDLES (env.idle) gives it up
				  // until it is rung (env.ring) or its time comes, and then takes its turn
				  // behind whatever call is in the module by then.
				  let busy = false;
				  const turns = [];
				  const enter = () =>
				    new Promise((resolve) => {
				      if (busy) turns.push(resolve);
				      else {
				        busy = true;
				        resolve();
				      }
				    });
				  const leave = () => {
				    const next = turns.shift();
				    if (next) next();
				    else busy = false;
				  };
				  const queued = async (work) => {
				    await enter();
				    try {
				      return await work();
				    } finally {
				      leave();
				    }
				  };
				  // What the call in the module brought along -- worker()'s request body and
				  // response -- which is that call's again whenever it runs; the calls idling,
				  // by token; and whether a call trapped, after which none runs here again.
				  let context;
				  const bells = new Map();
				  let discarded = false;
				  // setTimeout waits 2^31 - 1 milliseconds at most.
				  const later = (ms) =>
				    new Promise((resolve) =>
				      setTimeout(resolve, Math.min(Math.max(ms, 0), 2147483647)),
				    );
				""");
		if (surface.turn()) {
			out.append("""
					  // The program's own turns (env.wake): what its threads have left with no call
					  // idling is taken in a call of __ronto_turn at the time the module names (0n:
					  // at once), behind whatever call is in the module then. One is armed, the
					  // soonest asked for; `turning` settles once none is.
					  let armed = null;
					  let alarm;
					  let turning = null;
					  let turned;
					  // Set once the entry points are: a turn is asked for in _initialize at the
					  // earliest, and its timer fires after instantiate has gone on to set it.
					  let turn;
					  const wake = (at) => {
					    if (discarded || (armed !== null && armed <= at)) return;
					    clearTimeout(alarm);
					    armed = at;
					    turning ??= new Promise((resolve) => (turned = resolve));
					    alarm = setTimeout(
					      () => {
					        armed = null;
					        turn();
					      },
					      Math.min(Math.max(Number((at + 999999n) / 1000000n) - Date.now(), 0), 2147483647),
					    );
					  };
					""");
		}
		out.append(surface.setTime() ? """
				  // The module's clock moves when a wait is over, as it does per call.
				  const clock = () => exports.__ronto_set_time(BigInt(Date.now()) * 1000000n);
				""" : """
				  // The module reads no clock, so a wait moves none.
				  const clock = () => {};
				""");
	}

	// --host-timer's three waits (compiler/HostTimer), which the file implements: a wait
	// for a time holds the module, an idle hands it on.
	private static void timerEntry(StringBuilder out, Surface surface, Import imp) {
		out.append("      // ").append(signature(imp.paramTypes(), imp.returnType()));
		switch (imp.field()) {
			case HostTimer.SLEEP_FIELD -> out.append("""
					 -- --host-timer's wait for a time, implemented
					      // here: the call keeps the module, and its clock moves before it returns
					      sleep: new WebAssembly.Suspending(async (ms) => {
					        for (let left = ms; left > 0; left -= 2147483647) await later(left);
					        clock();
					      }),
					""");
			case HostTimer.IDLE_FIELD -> out.append("""
					 -- --host-timer's idle, implemented here: the
					      // module is the next call's until ring(token) or, `at` not 0n, that time
					      // (nanoseconds since the Unix epoch); then this call takes its turn again
					      idle: new WebAssembly.Suspending(async (token, at) => {
					        const mine = context;
					        let timer;
					        await new Promise((resolve) => {
					          bells.set(token, resolve);
					          if (at !== 0n) {
					            timer = setTimeout(
					              resolve,
					              Math.min(
					                Math.max(Number((at + 999999n) / 1000000n) - Date.now(), 0),
					                2147483647,
					              ),
					            );
					          }
					          leave();
					        });
					        clearTimeout(timer);
					        bells.delete(token);
					        await enter();
					        context = mine;
					        if (discarded) throw new Error("instance discarded by an earlier trap");
					        clock();
					      }),
					""");
			case HostTimer.RING_FIELD -> out.append("""
					 -- --host-timer's ring, implemented here
					      ring: (token) => bells.get(token)?.(),
					""");
			case HostTimer.WAKE_FIELD -> out.append("""
					 -- --host-timer's ask for a turn, implemented here
					      wake: (at) => wake(at),
					""");
			default -> throw new IllegalStateException("--host-timer imports no env." + imp.field());
		}
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
				""");
		// Where the host waits for the module (--host-timer) its top level is entered
		// through promising too, and may wait.
		boolean loadTraps = !surface.waits();
		if (loadTraps) {
			out.append("""

					  // While the module loads, a suspension TRAPS: its top level runs on a stack
					  // no WebAssembly.promising entered, and the trap names nobody -- so the
					  // import that asked for one is named here first.
					  let loading = true;
					  const whileLoading = (key) =>
					    "host." +
					    key +
					    " was called while the module loads, where it cannot be waited for: the call" +
					    " traps. Call it from an export";
					""");
		}
		out.append("""

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
				""");
		if (surface.setTime()) {
			out.append("""
					    // A suspension is where the host's time passes, and the module's clock is
					    // the host's to move: written again once the import has settled, so what
					    // the module waited for has taken the time it took.
					    const entry = wrap(key, given[SUSPENDING]);
					    return new WebAssembly.Suspending(async (...core) => {
					""");
			if (loadTraps) {
				out.append("      if (loading) console.error(whileLoading(key));\n");
			}
			out.append("""
					      try {
					        return await entry(...core);
					      } finally {
					        exports.__ronto_set_time(BigInt(Date.now()) * 1000000n);
					      }
					    });
					  };
					""");
		}
		else if (loadTraps) {
			out.append("""
					    const entry = wrap(key, given[SUSPENDING]);
					    return new WebAssembly.Suspending((...core) => {
					      if (loading) console.error(whileLoading(key));
					      return entry(...core);
					    });
					  };
					""");
		}
		else {
			out.append("""
					    return new WebAssembly.Suspending(wrap(key, given[SUSPENDING]));
					  };
					""");
		}
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
			case OCTETS -> reentrant ? "writeParkOctets" : "writeOctets";
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
			case BYTES, OCTETS -> {
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
		if (surface.initExport() != null && surface.waits()) {
			out.append("""
					  // The top level may wait for the host as any call may, so it is entered as one.
					""");
			out.append("  await queued(() => WebAssembly.promising(exports.")
				.append(surface.initExport())
				.append(")());\n");
		}
		else if (surface.initExport() != null) {
			out.append("  exports.").append(surface.initExport()).append("();\n");
		}
	}

	// The entry points, and the two things a call through one owes the module: the arena
	// bracket around whatever it stages, and -- once a host import can suspend -- the one
	// promise chain the module's re-entry guard demands.
	private static void exports(StringBuilder out, Surface surface) {
		if (surface.waits()) {
			waitedExports(out, surface);
			return;
		}
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

	// --host-timer: every entry point through WebAssembly.promising and onto the queue,
	// the state a call brought along the module's whenever that call runs.
	private static void waitedExports(StringBuilder out, Surface surface) {
		if (surface.exports().isEmpty()) {
			out.append("""

					  // This module exports no rontolisp:wasm-export: its whole program is the top
					  // level that has just run.
					""");
			out.append(hasReader(surface) ? "  return { exports, drop };\n" : "  return { exports };\n");
			return;
		}
		out.append("""

				  // Every entry point is entered through WebAssembly.promising: the host's waits
				  // suspend it.
				""");
		for (Export export : surface.exports()) {
			out.append("  const ")
				.append(entryName(export))
				.append(" = WebAssembly.promising(exports[\"")
				.append(export.exportName())
				.append("\"]);\n");
		}
		waitedCall(out, surface);
		for (Export export : surface.exports()) {
			entryPoint(out, surface, export);
		}
		out.append("""

				  // Host state that belongs to ONE call is handed over with it, and is `context`
				  // whenever that call runs: a call that idles lets others in, whose state is
				  // theirs. The entry points `work` is handed enter the module directly, because
				  // the queue they would take is the one they are in.
				""");
		out.append("  const inside = {\n");
		for (Export export : surface.exports()) {
			out.append("    ")
				.append(jsKey(jsName(export.exportName())))
				.append(": ")
				.append(factoryName(export))
				.append("((work) => work()),\n");
		}
		out.append("""
				  };
				  const serially = (work, state) =>
				    queued(() => {
				      if (discarded) throw new Error("instance discarded by an earlier trap");
				      context = state;
				      return work(inside);
				    });
				""");
		if (surface.turn()) {
			out.append("""
					  // Retires the instance: a call idling in it wakes to a refusal, and no turn
					  // is taken in it.
					  const discard = () => {
					    discarded = true;
					    for (const ring of bells.values()) ring();
					    clearTimeout(alarm);
					    armed = null;
					    if (turning) {
					      turning = null;
					      turned();
					    }
					  };
					  // The program's own turn: a call as any other, with nothing to hand over;
					  // one that traps retires the instance, as a request's does.
					  const turnEntry = WebAssembly.promising(exports.__ronto_turn);
					  turn = async () => {
					    try {
					      await serially(() =>
					        call((work) => work(), turnEntry, () => [], () => undefined),
					      );
					    } catch (error) {
					      if (!discarded) {
					        console.error("__ronto_turn failed:", error);
					        discard();
					      }
					    }
					    if (armed === null && turning) {
					      turning = null;
					      turned();
					    }
					  };
					""");
		}
		out.append("""

				  return {
				    exports,
				""");
		for (Export export : surface.exports()) {
			out.append("    ")
				.append(jsKey(jsName(export.exportName())))
				.append(": ")
				.append(factoryName(export))
				.append("((work) => serially(() => work())),\n");
		}
		if (hasReader(surface)) {
			out.append("    drop,\n");
		}
		out.append("""
				    serially,
				    get context() {
				      return context;
				    },
				    get discarded() {
				      return discarded;
				    },
				""");
		if (surface.turn()) {
			out.append("""
					    // Whether the program has a turn of its own armed: a promise that settles
					    // once none is, or null.
					    get turning() {
					      return turning;
					    },
					    discard,
					  };
					""");
		}
		else {
			out.append("""
					    // Retires the instance: a call idling in it wakes to a refusal.
					    discard() {
					      discarded = true;
					      for (const ring of bells.values()) ring();
					    },
					  };
					""");
		}
	}

	private static void waitedCall(StringBuilder out, Surface surface) {
		out.append("""

				  // One call into the module: stage the arguments, enter, decode the result out
				  // of the scratch it sits in, and only THEN pop the arena that scratch is in. A
				  // TRAP skips the pop and leaves the module's state half-written with it, so a
				  // host that keeps serving instantiates again rather than calling back in.
				""");
		if (surface.arena()) {
			out.append("""
					  // A call that idles holds nothing of the arena, so whichever call leaves pops
					  // it back to where the first of the calls in hand found it.
					  let calls = 0;
					  let base = 0;
					""");
		}
		out.append("""
				  const call = (run, entry, stage, decode) => {
				    const work = async () => {
				""");
		if (hasReader(surface)) {
			out.append("      readers.forEach((drop) => drop());\n");
		}
		if (surface.setTime()) {
			out.append("      // The module's clock moves only when the host moves it: per call.\n      clock();\n");
		}
		if (surface.arena()) {
			out.append("""
					      if (calls++ === 0) base = exports.__ronto_alloc_mark();
					      try {
					        const value = decode(await entry(...stage()));
					        exports.__ronto_alloc_reset(base);
					        return value;
					      } finally {
					        calls--;
					      }
					""");
		}
		else {
			out.append("      return decode(await entry(...stage()));\n");
		}
		out.append("""
				    };
				    return run(work);
				  };

				""");
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
