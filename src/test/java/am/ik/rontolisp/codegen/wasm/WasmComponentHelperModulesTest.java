package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prebuilt modules a component instantiates beside the core must satisfy whatever the
 * core imports, or the whole component fails to load -- under {@code wasmtime serve} that
 * is a server that answers no request at all.
 * <p>
 * The shared memory module must declare at least the pages the core's
 * {@code "mem"/"memory"} import asks for ("mismatch in memory limits"): the serve
 * component once used a 16-page module as shipped, so a served program with more static
 * data than that (tiny-routes over lack-request) never started. And the serve preview1
 * bridge must export every preview1 function a core can import.
 */
class WasmComponentHelperModulesTest {

	@Test
	void aMemoryModuleIsSizedForItsCoreAndNeverShrunk() throws Exception {
		byte[] plain = resource("mem.wasm");
		assertThat(WasmComponentBuilder.memModuleFor(coreImportingMemory(4))).isEqualTo(plain);
		assertThat(WasmComponentBuilder.memoryMinPagesOf(WasmComponentBuilder.memModuleFor(coreImportingMemory(9))))
			.isEqualTo(9);
		// A served component's is the same module, its allocator the HEAP_PTR bump, at
		// the sixteen pages the serve bridge imports at least.
		byte[] served = WasmComponentBuilder.memModuleFor(16, coreImportingMemory(10));
		assertThat(WasmComponentBuilder.memoryMinPagesOf(served)).isEqualTo(16);
		assertThat(am.ik.wasm.WasmExports.names(served)).contains("cabi_realloc");
		assertThat(
				WasmComponentBuilder.memoryMinPagesOf(WasmComponentBuilder.memModuleFor(16, coreImportingMemory(20))))
			.isEqualTo(20);
	}

	@Test
	void theServeBridgeExportsEveryPreview1FunctionACoreCanImport() throws Exception {
		// The serve component instantiates the core against this bridge, so a preview1
		// import it lacks fails the whole component at load: file-position became a
		// file_position_get/_set import under --component, and a served ningle app
		// (whose program names file-position) no longer started.
		assertThat(am.ik.wasm.WasmExports.names(resource("adapter-http-server-p1.wasm")))
			.containsAll(WasmComponentBuilder.PREVIEW1_FUNCS);
	}

	// A module whose only content is the (import "mem" "memory" (memory N)) a component
	// core module carries.
	private static byte[] coreImportingMemory(int pages) {
		ByteArrayOutputStream entry = new ByteArrayOutputStream();
		entry.write(1); // one import
		writeName(entry, "mem");
		writeName(entry, "memory");
		entry.write(0x02); // memory
		entry.write(0x00); // limits: min only
		entry.write(pages); // LEB128, < 128
		byte[] body = entry.toByteArray();
		ByteArrayOutputStream module = new ByteArrayOutputStream();
		module.writeBytes(new byte[] { 0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00 });
		module.write(0x02); // import section
		module.write(body.length);
		module.writeBytes(body);
		return module.toByteArray();
	}

	private static void writeName(ByteArrayOutputStream out, String name) {
		byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
		out.write(bytes.length);
		out.writeBytes(bytes);
	}

	private static byte[] resource(String name) throws Exception {
		try (InputStream in = Objects
			.requireNonNull(WasmComponentBuilder.class.getResourceAsStream("component/" + name), name)) {
			return in.readAllBytes();
		}
	}

}
