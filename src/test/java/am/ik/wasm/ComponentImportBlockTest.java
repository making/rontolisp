package am.ik.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the component import-block pruner, against the real blobs the WASI 0.3
 * component builders embed.
 *
 * <p>
 * The decisive one is {@link #pruningToStdoutReproducesTheBlobWasmToolsGeneratesForIt}:
 * the repo happens to carry a SECOND blob that {@code wasm-tools} generated independently
 * for exactly the world this pruner is asked to reduce the base blob to, so pruning can
 * be checked against that tool's own output rather than against this pass's idea of the
 * grammar.
 */
class ComponentImportBlockTest {

	private static final String CLI_TYPES = "wasi:cli/types@0.3.0";

	private static final String STDOUT = "wasi:cli/stdout@0.3.0";

	private static final String STDERR = "wasi:cli/stderr@0.3.0";

	private static final String FS_TYPES = "wasi:filesystem/types@0.3.0";

	private static final String FS_PREOPENS = "wasi:filesystem/preopens@0.3.0";

	private static byte[] blob(String name) {
		try (InputStream in = ComponentImportBlockTest.class
			.getResourceAsStream("/am/ik/rontolisp/codegen/wasm/component/" + name)) {
			return java.util.Objects.requireNonNull(in, name).readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Test
	void readsEveryInterfaceOfTheBaseBlockInBlockOrder() {
		ComponentImportBlock block = ComponentImportBlock.parse(blob("import-block.bin"));

		assertThat(block.interfaceIds()).containsExactly(CLI_TYPES, STDOUT, "wasi:cli/stdin@0.3.0",
				"wasi:cli/environment@0.3.0", "wasi:clocks/types@0.3.0", "wasi:clocks/system-clock@0.3.0",
				"wasi:clocks/monotonic-clock@0.3.0", FS_TYPES, FS_PREOPENS, "wasi:random/random@0.3.0", STDERR);
		assertThat(block.instanceOf()).containsEntry(CLI_TYPES, 0).containsEntry(STDERR, 10);
		assertThat(block.typeCount()).isEqualTo(17);
	}

	@Test
	void readsTheServeBlockWhoseOrderIsNotItsWorldsOrder() {
		// wasm-tools hoists an interface ahead of the one that `use`s it, so the serve
		// block starts with wasi:clocks/types even though uni-http-server.wit starts with
		// wasi:http/types. A caller must read the names rather than assume the WIT order.
		ComponentImportBlock block = ComponentImportBlock.parse(blob("import-block-http-server.bin"));

		assertThat(block.interfaceIds()).containsExactly("wasi:clocks/types@0.3.0", "wasi:http/types@0.3.0",
				"wasi:http/client@0.3.0", "wasi:random/random@0.3.0", "wasi:clocks/system-clock@0.3.0",
				"wasi:clocks/monotonic-clock@0.3.0", CLI_TYPES, STDOUT, STDERR);
		assertThat(block.typeCount()).isEqualTo(15);
	}

	@Test
	void keepingEverythingIsByteIdentical() {
		for (String name : List.of("import-block.bin", "import-block-http-server.bin", "import-block-nogc-print.bin")) {
			byte[] bytes = blob(name);
			ComponentImportBlock block = ComponentImportBlock.parse(bytes);

			ComponentImportBlock.Pruned kept = block.prune(block.interfaceIds());

			assertThat(kept.bytes()).as(name).isEqualTo(bytes);
			assertThat(kept.instanceOf()).isEqualTo(block.instanceOf());
			assertThat(kept.typeCount()).isEqualTo(block.typeCount());
		}
	}

	@Test
	void pruningToStdoutReproducesTheBlobWasmToolsGeneratesForIt() {
		// import-block-nogc-print.bin is what `wasm-tools component new` emits for a
		// world
		// importing exactly wasi:cli/stdout (which pulls in wasi:cli/types). Pruning
		// either
		// of the bigger blobs down to those two must land on the same bytes -- the
		// grammar
		// and the renumbering are checked against that tool, not against themselves.
		byte[] expected = blob("import-block-nogc-print.bin");

		for (String name : List.of("import-block.bin", "import-block-http-server.bin")) {
			ComponentImportBlock.Pruned pruned = ComponentImportBlock.parse(blob(name))
				.prune(Set.of(CLI_TYPES, STDOUT));

			assertThat(pruned.bytes()).as(name).isEqualTo(expected);
			assertThat(pruned.instanceOf()).containsExactly(java.util.Map.entry(CLI_TYPES, 0),
					java.util.Map.entry(STDOUT, 1));
			assertThat(pruned.typeCount()).isEqualTo(3);
		}
	}

	@Test
	void anInterfaceKeepsTheOneWhoseTypesItProjects() {
		// preopens' instance type aliases wasi:filesystem/types' `descriptor` resource
		// out
		// of that interface's instance: a resource is its defining interface's type, so
		// the
		// projection cannot outlive it. The chain is TRANSITIVE and two links long here:
		// wasi:filesystem/types in turn projects wasi:clocks/system-clock's `instant`,
		// which it `use`s for descriptor-stat's three timestamps, so asking for
		// preopens alone keeps the clock too.
		ComponentImportBlock.Pruned pruned = ComponentImportBlock.parse(blob("import-block.bin"))
			.prune(Set.of(FS_PREOPENS));

		assertThat(pruned.instanceOf().keySet()).containsExactly("wasi:clocks/system-clock@0.3.0", FS_TYPES,
				FS_PREOPENS);
	}

	@Test
	void aStandaloneInterfaceKeepsNothingElse() {
		// wasi:clocks/system-clock defines its `instant` record inline instead of
		// `use`ing
		// wasi:clocks/types, so a program that only reads the clock drops the types
		// interface too.
		ComponentImportBlock.Pruned pruned = ComponentImportBlock.parse(blob("import-block.bin"))
			.prune(Set.of("wasi:clocks/system-clock@0.3.0"));

		assertThat(pruned.instanceOf().keySet()).containsExactly("wasi:clocks/system-clock@0.3.0");
		assertThat(pruned.typeCount()).isEqualTo(1);
	}

	@Test
	void everySubsetOfTheBaseBlockStaysWellFormed() {
		// The renumbering has to hold for every combination, not just the ones a program
		// happens to produce: each surviving group's instance and type references must
		// land
		// inside the pruned block.
		ComponentImportBlock block = ComponentImportBlock.parse(blob("import-block.bin"));
		List<String> ids = List.copyOf(block.interfaceIds());

		for (int mask = 1; mask < (1 << ids.size()); mask++) {
			LinkedHashSet<String> keep = new LinkedHashSet<>();
			for (int i = 0; i < ids.size(); i++) {
				if ((mask & (1 << i)) != 0) {
					keep.add(ids.get(i));
				}
			}
			ComponentImportBlock.Pruned pruned = block.prune(keep);
			// Re-parsing is the well-formedness check: the parser consumes every byte and
			// throws on anything it does not recognize.
			ComponentImportBlock reparsed = ComponentImportBlock.parse(pruned.bytes());
			assertThat(reparsed.interfaceIds()).as("mask %d", mask)
				.containsExactlyElementsOf(pruned.instanceOf().keySet());
			assertThat(reparsed.instanceOf()).isEqualTo(pruned.instanceOf());
			assertThat(reparsed.typeCount()).isEqualTo(pruned.typeCount());
			assertThat(keep).allSatisfy(id -> assertThat(pruned.instanceOf()).containsKey(id));
		}
	}

	@Test
	void anUnknownInterfaceIsRejectedByName() {
		ComponentImportBlock block = ComponentImportBlock.parse(blob("import-block.bin"));

		assertThatThrownBy(() -> block.prune(Set.of("wasi:sockets/types@0.3.0")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not import 'wasi:sockets/types@0.3.0'");
	}

	@Test
	void aBlockItCannotAccountForThrowsRatherThanBeCopiedThrough() {
		// The pass owes "throw rather than emit a corrupt component": a byte it cannot
		// classify must not be silently carried into a renumbered block.
		byte[] corrupt = blob("import-block.bin").clone();
		// The base block's first type section body: `01 42 02 ...` -- make the instance
		// type a component type (0x41), which the grammar does not allow here.
		corrupt[3] = 0x41;

		assertThatThrownBy(() -> ComponentImportBlock.parse(corrupt)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("instance type");
	}

}
