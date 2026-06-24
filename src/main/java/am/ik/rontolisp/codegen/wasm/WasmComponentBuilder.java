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
 * The wrapped core module keeps importing the four {@code wasi_snapshot_preview1}
 * functions and printing through {@code fd_write}; those imports are satisfied by an
 * <em>adapter</em> core module that implements {@code fd_write} on top of WASI 0.2
 * streams (and stubs {@code fd_read}/{@code path_open}/{@code fd_close}, so reading and
 * file I/O are not yet available in component mode). The only change the core module
 * itself needs is to import its linear memory and export a {@code run} entry (see
 * {@link WasmLispCompiler}).
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
 * Several byte blobs are loaded from classpath resources next to this class (under
 * {@code component/}) because they are fixed and independent of the compiled program: the
 * WASI import declarations (resource / variant / result / list type encodings that are
 * impractical to hand-build) and the two helper core modules. The core modules ship with
 * a {@code .wat} disassembly for reading and the {@code .bin} fragments are documented in
 * {@code component/README.md}; each was validated with
 * {@code wasm-tools validate -f component-model} and executed with {@code wasmtime run}.
 */
public final class WasmComponentBuilder {

	/** Classpath location of the embedded component blobs, relative to this class. */
	private static final String RES = "component/";

	/**
	 * Pre-built component sections declaring the imported WASI 0.2 interfaces
	 * {@code wasi:io/error}, {@code wasi:io/streams} (the {@code output-stream} resource
	 * and its {@code blocking-write-and-flush} method) and {@code wasi:cli/stdout}
	 * ({@code get-stdout}). Establishes component types 0-4 and component instances 0-2.
	 */
	private static final byte[] IMPORT_BLOCK = resource("import-block.bin");

	/**
	 * A core module exporting a linear {@code memory} and a bump-allocator
	 * {@code cabi_realloc}, shared by the canonical lowering and the main module. See
	 * {@code component/mem.wat}.
	 */
	private static final byte[] MEM_MODULE = resource("mem.wasm");

	/**
	 * The preview1-to-0.2 adapter core module: it imports the shared memory and the
	 * lowered WASI functions ({@code get-stdout} / {@code write} / {@code drop} /
	 * {@code get-random-u64} / {@code wall-now} / {@code mono-now}) and exports the
	 * preview1-style functions rontolisp imports -- {@code fd_write} (over
	 * {@code wasi:io/streams}), {@code random_get} (over {@code wasi:random}),
	 * {@code clock_time_get} (over {@code wasi:clocks} wall/monotonic), plus stub
	 * {@code environ_sizes_get}/{@code environ_get}/{@code fd_read}/{@code path_open}/
	 * {@code fd_close}. The stream-error result area is at linear offset 256 and the
	 * wall-clock datetime scratch at 512, both outside the rontolisp fixed I/O layout.
	 * See {@code component/adapter.wat}.
	 */
	private static final byte[] ADAPTER_MODULE = resource("adapter.wasm");

	/**
	 * The {@code wasi:clocks/wall-clock} instance type (its {@code now} returns a
	 * {@code datetime} record of {@code seconds: u64} / {@code nanoseconds: u32}).
	 * Captured from a wasm-tools-generated reference.
	 */
	private static final byte[] WALL_CLOCK_TYPE = resource("wall-clock-type.bin");

	/**
	 * The {@code wasi:clocks/monotonic-clock} instance type (its {@code now} returns a
	 * {@code u64} instant).
	 */
	private static final byte[] MONOTONIC_CLOCK_TYPE = resource("monotonic-clock-type.bin");

	/**
	 * The {@code wasi:cli/environment} instance type (its {@code get-environment} returns
	 * a {@code list<tuple<string, string>>}).
	 */
	private static final byte[] ENVIRONMENT_TYPE = resource("environment-type.bin");

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
		// Imported WASI interfaces: component types 0-4, component instances 0-2
		// (wasi:io/error, wasi:io/streams, wasi:cli/stdout).
		c.writeRaw(IMPORT_BLOCK);
		// Component type 5 + instance 3: wasi:random (get-random-u64 () -> u64).
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter
			.vec(List.of(ComponentWriter.instanceTypeWithFunc("get-random-u64", ComponentWriter.VT_U64))));
		c.rawSection(ComponentWriter.SEC_IMPORT,
				ComponentWriter.vec(List.of(ComponentWriter.importInstance("wasi:random/random@0.2.0", 5))));
		// Component type 6 + instance 4: wasi:clocks/wall-clock (now () -> datetime).
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(List.of(WALL_CLOCK_TYPE)));
		c.rawSection(ComponentWriter.SEC_IMPORT,
				ComponentWriter.vec(List.of(ComponentWriter.importInstance("wasi:clocks/wall-clock@0.2.0", 6))));
		// Component type 7 + instance 5: wasi:clocks/monotonic-clock (now () -> u64).
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(List.of(MONOTONIC_CLOCK_TYPE)));
		c.rawSection(ComponentWriter.SEC_IMPORT,
				ComponentWriter.vec(List.of(ComponentWriter.importInstance("wasi:clocks/monotonic-clock@0.2.0", 7))));
		// Component type 8 + instance 6: wasi:cli/environment (get-environment).
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(List.of(ENVIRONMENT_TYPE)));
		c.rawSection(ComponentWriter.SEC_IMPORT,
				ComponentWriter.vec(List.of(ComponentWriter.importInstance("wasi:cli/environment@0.2.0", 8))));
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
		// Alias imported WASI functions: component funcs 0 = get-stdout, 1 = write,
		// 2 = get-random-u64, 3 = wall-clock.now, 4 = monotonic-clock.now,
		// 5 = get-environment.
		c.rawSection(ComponentWriter.SEC_ALIAS,
				ComponentWriter.vec(List.of(ComponentWriter.aliasInstanceFunc(2, "get-stdout"),
						ComponentWriter.aliasInstanceFunc(1, "[method]output-stream.blocking-write-and-flush"),
						ComponentWriter.aliasInstanceFunc(3, "get-random-u64"),
						ComponentWriter.aliasInstanceFunc(4, "now"), ComponentWriter.aliasInstanceFunc(5, "now"),
						ComponentWriter.aliasInstanceFunc(6, "get-environment"))));
		// Lower them: core func 1 = get-stdout, 2 = write (memory/realloc), 3 =
		// resource.drop output-stream, 4 = get-random-u64, 5 = wall-clock.now (memory,
		// for
		// the record return), 6 = monotonic-clock.now, 7 = get-environment
		// (memory/realloc,
		// for the list return).
		c.rawSection(ComponentWriter.SEC_CANON,
				ComponentWriter.vec(List.of(ComponentWriter.canonLower(0), ComponentWriter.canonLower(1, 0, 0),
						ComponentWriter.canonResourceDrop(3), ComponentWriter.canonLower(2),
						ComponentWriter.canonLowerMemory(3, 0), ComponentWriter.canonLower(4),
						ComponentWriter.canonLower(5, 0, 0))));
		// Group the lowered functions for the adapter's "w" import (core instance 1).
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceFromFuncs(
					List.of("get-stdout", "write", "drop", "get-random-u64", "wall-now", "mono-now", "get-environment"),
					List.of(1, 2, 3, 4, 5, 6, 7)))));
		// Instantiate the adapter (core instance 2): mem = instance 0, w = instance 1.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter
			.vec(List.of(ComponentWriter.coreInstanceInstantiate(1, List.of("mem", "w"), List.of(0, 1)))));
		// Instantiate rontolisp (core instance 3): mem = instance 0,
		// wasi_snapshot_preview1 = adapter instance 2.
		c.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List
			.of(ComponentWriter.coreInstanceInstantiate(2, List.of("mem", "wasi_snapshot_preview1"), List.of(0, 2)))));
		// Alias rontolisp's run (core func 8).
		c.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(List.of(ComponentWriter.aliasCoreFunc(3, "run"))));
		// Types: 9 = result<_,_>, 10 = func () -> result<_,_>.
		c.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter
			.vec(List.of(ComponentWriter.definedResultVoid(), ComponentWriter.funcTypeResultType(9))));
		// Lift run into component func 6 with type 10.
		c.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(List.of(ComponentWriter.canonLift(8, 10))));
		// Component instance 7 exporting run, exported as the wasi:cli/run interface.
		c.rawSection(ComponentWriter.SEC_INSTANCE,
				ComponentWriter.vec(List.of(ComponentWriter.componentInstanceFromFunc("run", 6))));
		c.rawSection(ComponentWriter.SEC_EXPORT,
				ComponentWriter.vec(List.of(ComponentWriter.exportInstance("wasi:cli/run@0.2.0", 7))));
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
