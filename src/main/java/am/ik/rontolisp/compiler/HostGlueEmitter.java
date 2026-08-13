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
	 */
	public record Surface(List<Import> imports, @Nullable Import entropy, List<Export> exports, boolean arena,
			boolean seedRandom, boolean setTime, @Nullable String initExport) {

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
				const decoder = new TextDecoder();

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
				// between them -- the (ptr, len) pair a :string crosses as, the __ronto_alloc
				// bracket around a call, and the read(2) cursor a :bytes result is pulled
				// through.
				//
				// What a declaration cannot state is what a host function DOES, so that is the
				// one thing this file asks for: `host` is a plain function per import, keyed by
				// import module and field, taking and answering ordinary JavaScript values --
				// never a (ptr, len) pair, and, where an entry answers `chunk` below, a
				// Uint8Array or a string with null for the end of them.
				//
				""");
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
			out.append("//       ").append(jsKey(imp.field())).append(": ").append(sketch(imp)).append(",\n");
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
		if (canSuspend(surface)) {
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

	// Only the helpers this module's declarations actually reach are written: a program
	// that never crosses a string is not handed a decoder to read past.
	private static void memoryHelpers(StringBuilder out, Surface surface) {
		boolean readString = surface.imports()
			.stream()
			.anyMatch(i -> i.paramTypes().stream().anyMatch(HostGlueEmitter::isText))
				|| surface.exports().stream().anyMatch(e -> isText(e.returnType()));
		boolean readBytes = surface.imports().stream().anyMatch(i -> i.paramTypes().contains(BoundaryType.BYTES))
				|| surface.exports().stream().anyMatch(e -> e.returnType() == BoundaryType.BYTES);
		boolean writeString = surface.imports().stream().anyMatch(i -> isText(i.returnType()))
				|| surface.exports().stream().anyMatch(e -> e.paramTypes().stream().anyMatch(HostGlueEmitter::isText));
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
		if (reserve) {
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
					importEntry(out, imp);
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
		if (surface.imports().stream().anyMatch(i -> i.returnType() == BoundaryType.BYTES)) {
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
	private static void importEntry(StringBuilder out, Import imp) {
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
			.append(importResult(imp.returnType()))
			.append(");\n      }),\n");
	}

	// How the host's answer is handed back to the module.
	private static String importResult(BoundaryType type) {
		return switch (type) {
			case STRING, S_EXPR -> "writeString";
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
		if (suspends) {
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
			entryPoint(out, export);
		}
		String run = suspends ? "serialised" : "(work) => work()";
		if (suspends) {
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
		if (suspends) {
			out.append("    serially,\n");
		}
		out.append("  };\n");
	}

	private static void call(StringBuilder out, Surface surface, boolean suspends) {
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
		if (surface.imports().stream().anyMatch(i -> i.returnType() == BoundaryType.BYTES)) {
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

	private static void entryPoint(StringBuilder out, Export export) {
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
			bytesEntryPoint(out, export, setup, staged);
			return;
		}
		out.append("      return call(\n        run,\n        ")
			.append(entryName(export))
			.append(",\n        () => {\n");
		for (String line : setup) {
			out.append("          ").append(line).append('\n');
		}
		out.append("          return [").append(String.join(", ", staged)).append("];\n        },\n        ");
		out.append(exportResult(export.returnType())).append(",\n      );\n    };\n\n");
	}

	// The mirror of the import side's read(2) shape: the CALLER passes the buffer, and an
	// undersized one is answered with the value's FULL length -- so the only sound reply
	// to a short answer is to ask again with exactly that much, never to truncate.
	private static void bytesEntryPoint(StringBuilder out, Export export, List<String> setup, List<String> staged) {
		out.append("      let at = 0;\n      const pull = (cap) =>\n        call(\n          run,\n          ")
			.append(entryName(export))
			.append(",\n          () => {\n");
		for (String line : setup) {
			out.append("            ").append(line).append('\n');
		}
		out.append("            const out = reserve(cap);\n            at = out[0];\n            return [")
			.append(String.join(", ", concat(staged, "out[0]", "out[1]")))
			.append("];\n          },\n          (n) => (n > cap ? n : readBytes(at, n)),\n        );\n");
		out.append("""
				      // A short answer is the value's full length, so ask once more for that much.
				      const grow = (value) => (typeof value === "number" ? pull(value) : value);
				""");
		out.append("      const first = pull(").append(BYTES_BUFFER).append(");\n");
		out.append("      return typeof first?.then === \"function\" ? first.then(grow) : grow(first);\n    };\n\n");
	}

	private static String exportResult(BoundaryType type) {
		return switch (type) {
			case STRING, S_EXPR -> "([ptr, len]) => readString(ptr, len)";
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
