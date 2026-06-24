package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import am.ik.wasm.ComponentWriter;

/**
 * Wraps a rontolisp core module (compiled in component mode) into a WASI 0.2 (Preview 2)
 * <strong>component</strong> that prints through {@code wasi:cli/stdout} and
 * {@code wasi:io/streams} and is runnable with {@code wasmtime run}.
 *
 * <p>
 * The wrapped core module keeps importing the eight {@code wasi_snapshot_preview1}
 * functions and printing/reading through them; those imports are satisfied by an
 * <em>adapter</em> core module that implements them on top of WASI 0.2 services:
 * {@code fd_write}/{@code fd_read}/{@code path_open}/{@code fd_close} over
 * {@code wasi:filesystem} + {@code wasi:io/streams} (so file I/O works), and
 * {@code random_get}/{@code clock_time_get}/{@code environ_*} over
 * {@code wasi:random}/{@code wasi:clocks}/{@code wasi:cli/environment}. The only change
 * the core module itself needs is to import its linear memory and export a {@code run}
 * entry (see {@link WasmLispCompiler}).
 *
 * <p>
 * A lowered WASI import with a {@code list<u8>} parameter needs the canonical memory, and
 * that memory must exist before the importing module is instantiated. To avoid the
 * instantiate-before-memory cycle without the lazy funcref trampoline that
 * {@code wasm-tools} emits, a tiny shared "memory" core module is instantiated first; it
 * exports the memory (and a {@code cabi_realloc}) that both the canonical lowering and
 * the main module use.
 *
 * <p>
 * Three byte blobs are loaded from classpath resources next to this class (under
 * {@code component/}) because they are fixed and independent of the compiled program:
 * {@code import-block.bin} (the unified WASI import declarations for all ten interfaces,
 * captured whole from a {@code wasm-tools}-generated reference) and the two helper core
 * modules {@code mem.wasm} / {@code adapter.wasm}. The remaining wiring is emitted
 * programmatically below. These artifacts are <em>generated</em>; their sources (the
 * {@code .wat}, the WIT world and a regeneration script) live outside the resources tree
 * under {@code src/wasm-component/} (see its {@code README.md}). Each was validated with
 * {@code wasm-tools validate -f component-model} and executed with {@code wasmtime run}.
 */
public final class WasmComponentBuilder {

	/** Classpath location of the embedded component blobs, relative to this class. */
	private static final String RES = "component/";

	/**
	 * Pre-built component type/import sections declaring every imported WASI 0.2
	 * interface, captured from a {@code wasm-tools}-generated reference for a single WIT
	 * world (see {@code src/wasm-component/README.md}). In order, component instances 0-9
	 * are: {@code wasi:io/error}, {@code wasi:io/streams} (one instance declaring both
	 * the {@code input-stream} and {@code output-stream} resources, shared by stdout,
	 * stdin and the filesystem), {@code wasi:cli/stdout}, {@code wasi:random/random},
	 * {@code wasi:clocks/wall-clock}, {@code wasi:clocks/monotonic-clock},
	 * {@code wasi:cli/environment}, {@code wasi:filesystem/types},
	 * {@code wasi:filesystem/preopens} and {@code wasi:cli/stdin}. The block defines
	 * component types 0-15, so the next free component type index is 16.
	 */
	private static final byte[] IMPORT_BLOCK = resource("import-block.bin");

	/**
	 * A core module exporting a linear {@code memory} and a bump-allocator
	 * {@code cabi_realloc}, shared by the canonical lowering and the main module. Source:
	 * {@code src/wasm-component/mem.wat}.
	 */
	private static final byte[] MEM_MODULE = resource("mem.wasm");

	/**
	 * The preview1-to-0.2 adapter core module: it imports the shared memory and the
	 * lowered WASI 0.2 functions and exports the eight preview1-style functions rontolisp
	 * imports. {@code fd_write}/{@code fd_read}/{@code path_open}/{@code fd_close}
	 * implement file I/O over {@code wasi:filesystem} (get-directories /
	 * descriptor.open-at / read-via-stream / write-via-stream) and
	 * {@code wasi:io/streams} (input-stream blocking-read, output-stream
	 * blocking-write-and-flush) using a small fd table;
	 * {@code random_get}/{@code clock_time_get}/{@code environ_*} bridge
	 * {@code wasi:random}/{@code wasi:clocks}/{@code wasi:cli/environment}. All adapter
	 * scratch and the fd table live in page 5 of the shared memory, clear of the
	 * rontolisp layout. Source: {@code src/wasm-component/adapter.wat}.
	 */
	private static final byte[] ADAPTER_MODULE = resource("adapter.wasm");

	private WasmComponentBuilder() {
	}

	/**
	 * Assemble a runnable WASI 0.2 component around the given rontolisp core module.
	 * @param coreModule the rontolisp core module compiled in component mode (imports its
	 * memory, imports {@code wasi_snapshot_preview1}, and exports a {@code run} function
	 * returning {@code i32})
	 * @return the WASI 0.2 component binary
	 */
	public static byte[] build(byte[] coreModule) {
		final ComponentWriter c = new ComponentWriter();
		// All imported WASI interfaces in one block: component instances 0-9, types 0-15.
		c.writeRaw(IMPORT_BLOCK);
		// Core modules: 0 = shared memory, 1 = adapter, 2 = rontolisp.
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, MEM_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, ADAPTER_MODULE);
		c.rawSection(ComponentWriter.SEC_CORE_MODULE, coreModule);
		// Instantiate the shared memory module (core instance 0).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceInstantiate(0, List.of(), List.of()))));
		// Alias the shared memory (core memory 0) and cabi_realloc (core func 0).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List
			.of(ComponentWriter.aliasCoreMemory(0, "memory"), ComponentWriter.aliasCoreFunc(0, "cabi_realloc"))));
		// Alias the imported WASI functions (component funcs 0-11) and the resource types
		// to drop (component types 16-18). Instance indices: 1 = io/streams, 2 = stdout,
		// 3 = random, 4 = wall-clock, 5 = monotonic-clock, 6 = environment,
		// 7 = filesystem/types, 8 = filesystem/preopens, 9 = stdin.
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(
				// func 0 get-stdout, 1 write, 2 read, 3 random, 4 wall, 5 mono, 6 env
				ComponentWriter.aliasInstanceFunc(2, "get-stdout"),
				ComponentWriter.aliasInstanceFunc(1, "[method]output-stream.blocking-write-and-flush"),
				ComponentWriter.aliasInstanceFunc(1, "[method]input-stream.blocking-read"),
				ComponentWriter.aliasInstanceFunc(3, "get-random-u64"), ComponentWriter.aliasInstanceFunc(4, "now"),
				ComponentWriter.aliasInstanceFunc(5, "now"), ComponentWriter.aliasInstanceFunc(6, "get-environment"),
				// func 7 open-at, 8 read-via-stream, 9 write-via-stream, 10
				// get-directories, 11 get-stdin
				ComponentWriter.aliasInstanceFunc(7, "[method]descriptor.open-at"),
				ComponentWriter.aliasInstanceFunc(7, "[method]descriptor.read-via-stream"),
				ComponentWriter.aliasInstanceFunc(7, "[method]descriptor.write-via-stream"),
				ComponentWriter.aliasInstanceFunc(8, "get-directories"),
				ComponentWriter.aliasInstanceFunc(9, "get-stdin"),
				// type 16 output-stream, 17 input-stream, 18 descriptor (for
				// resource.drop)
				ComponentWriter.aliasInstanceType(1, "output-stream"),
				ComponentWriter.aliasInstanceType(1, "input-stream"),
				ComponentWriter.aliasInstanceType(7, "descriptor"))));
		// Lower them to core funcs 1-15 (core func 0 = cabi_realloc). Canonical options
		// mirror what wasm-tools chooses for each WIT function (memory 0, realloc = core
		// func 0). The order here is the adapter's "w" import order below.
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLower(0), // 1
																											// get-stdout
				ComponentWriter.canonLowerMemory(1, 0), // 2 write
				ComponentWriter.canonLower(2, 0, 0), // 3 read (memory, realloc)
				ComponentWriter.canonResourceDrop(16), // 4 drop output-stream
				ComponentWriter.canonResourceDrop(17), // 5 drop input-stream
				ComponentWriter.canonResourceDrop(18), // 6 drop descriptor
				ComponentWriter.canonLower(3), // 7 get-random-u64
				ComponentWriter.canonLowerMemory(4, 0), // 8 wall-clock.now
				ComponentWriter.canonLower(5), // 9 monotonic-clock.now
				ComponentWriter.canonLowerMemoryReallocUtf8(6, 0, 0), // 10
																		// get-environment
				ComponentWriter.canonLowerMemoryUtf8(7, 0), // 11 open-at
				ComponentWriter.canonLowerMemory(8, 0), // 12 read-via-stream
				ComponentWriter.canonLowerMemory(9, 0), // 13 write-via-stream
				ComponentWriter.canonLowerMemoryReallocUtf8(10, 0, 0), // 14
																		// get-directories
				ComponentWriter.canonLower(11)))); // 15 get-stdin
		// Group the lowered functions for the adapter's "w" import (core instance 1).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
						List.of("get-stdout", "write", "read", "drop-out", "drop-in", "drop-desc", "get-random-u64",
								"wall-now", "mono-now", "get-environment", "open-at", "read-via-stream",
								"write-via-stream", "get-directories", "get-stdin"),
						List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)))));
		// Instantiate the adapter (core instance 2): mem = instance 0, w = instance 1.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// Instantiate rontolisp (core instance 3): mem = instance 0,
		// wasi_snapshot_preview1 = adapter instance 2.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.coreInstanceInstantiate(2, List.of("mem", "wasi_snapshot_preview1"), List.of(0, 2)))));
		// Alias rontolisp's run (core func 16).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(3, "run"))));
		// Types: 19 = result<_,_>, 20 = func () -> result<_,_>.
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter
			.vec(List.of(ComponentWriter.definedResultVoid(), ComponentWriter.funcTypeResultType(19))));
		// Lift run (core func 16) into component func 12 with type 20.
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLift(16, 20))));
		// Component instance 10 exporting run, exported as the wasi:cli/run interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 12))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.2.0", 10))));
		return c.toByteArray();
	}

	/**
	 * Load a fixed component blob bundled as a classpath resource next to this class.
	 * @param name the file name under {@code component/}
	 * @return the raw bytes
	 */
	private static byte[] resource(String name) {
		try (InputStream in = WasmComponentBuilder.class.getResourceAsStream(RES + name)) {
			if (in == null) {
				throw new IllegalStateException("Missing component resource: " + RES + name);
			}
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to read component resource: " + RES + name, ex);
		}
	}

}
