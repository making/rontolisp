package am.ik.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for narrowing a module's export surface, against the real WASI 0.3 adapter blob
 * -- the module the pass exists for.
 */
class WasmExportsTest {

	private static byte[] adapter() {
		try (InputStream in = WasmExportsTest.class
			.getResourceAsStream("/am/ik/rontolisp/codegen/wasm/component/adapter.wasm")) {
			return java.util.Objects.requireNonNull(in, "adapter.wasm").readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static LinkedHashMap<String, String> keep(String... names) {
		LinkedHashMap<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < names.length; i += 2) {
			map.put(names[i], names[i + 1]);
		}
		return map;
	}

	@Test
	void keepingEveryExportUnderItsOwnNameChangesNothing() {
		byte[] adapter = adapter();
		LinkedHashMap<String, String> identity = new LinkedHashMap<>();
		WasmExports.names(adapter).forEach(name -> identity.put(name, name));

		assertThat(WasmExports.retain(adapter, identity)).isSameAs(adapter);
	}

	@Test
	void everythingNotRetainedStopsBeingAShakerRootAndGoes() {
		byte[] adapter = adapter();

		byte[] narrowed = WasmExports.retain(adapter, keep("fd_write", "fd_write"));
		assertThat(WasmExports.names(narrowed)).containsExactly("fd_write");

		byte[] shaken = WasmTreeShaker.shake(narrowed);
		assertThat(shaken.length).isLessThan(adapter.length / 2);
		assertThat(WasmExports.names(shaken)).containsExactly("fd_write");
		// path_open was the only caller of the filesystem-side lowerings it reached.
		assertThat(WasmImports.functionFields(shaken, "w")).doesNotContain("open-at", "get-directories", "read-dir",
				"drop-desc");
	}

	@Test
	void retainingTheNarrowImplementationUnderThePreview1NameDropsTheFilesystemSurface() {
		// The point of the rename: the adapter's stdio-only fd_write becomes the module's
		// `fd_write`, so nothing reaches wasi:filesystem at all.
		byte[] shaken = WasmTreeShaker
			.shake(WasmExports.retain(adapter(), keep("fd_write_stdio", "fd_write", "fd_read_stdin", "fd_read")));

		assertThat(WasmExports.names(shaken)).containsExactly("fd_write", "fd_read");
		assertThat(WasmImports.functionFields(shaken, "w")).containsExactly("stdout-write", "stderr-write",
				"stdin-read", "stream-new", "stream-read", "stream-write", "stream-drop-w", "future-read-cli",
				"future-drop-cli", "waitable-set-new", "waitable-join", "waitable-set-wait");
	}

	@Test
	void aNameTheModuleDoesNotExportIsRejectedRatherThanIgnored() {
		assertThatThrownBy(() -> WasmExports.retain(adapter(), keep("fd_pwrite", "fd_write")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("does not export 'fd_pwrite'");
	}

}
