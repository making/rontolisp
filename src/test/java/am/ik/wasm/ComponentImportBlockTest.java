package am.ik.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the component import-block pruner, against the real blobs the WASI 0.3
 * component builders embed.
 *
 * <p>
 * The decisive one is
 * {@link #pruningReproducesTheBlobWasmToolsGeneratesForTheSameMembers}: the repo carries
 * blobs that {@code wasm-tools} generated independently for exactly the worlds this
 * pruner is asked to reduce a bigger blob to, so pruning can be checked against that
 * tool's own output rather than against this pass's idea of the grammar.
 */
class ComponentImportBlockTest {

	private static final String CLI_TYPES = "wasi:cli/types@0.3.0";

	private static final String STDOUT = "wasi:cli/stdout@0.3.0";

	private static final String STDERR = "wasi:cli/stderr@0.3.0";

	private static final String SYSTEM_CLOCK = "wasi:clocks/system-clock@0.3.0";

	private static final String FS_TYPES = "wasi:filesystem/types@0.3.0";

	private static final String FS_PREOPENS = "wasi:filesystem/preopens@0.3.0";

	private static byte[] blob(String name) {
		try (InputStream in = ComponentImportBlockTest.class.getResourceAsStream(name)) {
			return java.util.Objects.requireNonNull(in, name).readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static byte[] embedded(String name) {
		return blob("/am/ik/rontolisp/codegen/wasm/component/" + name);
	}

	// descriptor.read-via-stream -> the name the instance type exports it under.
	private static String method(String name) {
		return "[method]descriptor." + name;
	}

	@Test
	void readsEveryInterfaceOfTheBaseBlockInBlockOrder() {
		ComponentImportBlock block = ComponentImportBlock.parse(embedded("import-block.bin"));

		assertThat(block.interfaceIds()).containsExactly(CLI_TYPES, STDOUT, "wasi:cli/stdin@0.3.0",
				"wasi:cli/environment@0.3.0", "wasi:clocks/types@0.3.0", SYSTEM_CLOCK,
				"wasi:clocks/monotonic-clock@0.3.0", FS_TYPES, FS_PREOPENS, "wasi:random/random@0.3.0", STDERR,
				"wasi:cli/terminal-input@0.3.0", "wasi:cli/terminal-output@0.3.0", "wasi:cli/terminal-stdin@0.3.0",
				"wasi:cli/terminal-stdout@0.3.0", "wasi:cli/terminal-stderr@0.3.0");
		assertThat(block.instanceOf()).containsEntry(CLI_TYPES, 0).containsEntry(STDERR, 10);
		assertThat(block.typeCount()).isEqualTo(25);
		// types and functions alike, in declaration order
		assertThat(block.members().get(SYSTEM_CLOCK)).containsExactly("instant", "now");
		assertThat(block.members().get(FS_TYPES)).startsWith("descriptor", "filesize", "error-code")
			.contains("new-timestamp", method("set-times-at"))
			.endsWith(method("metadata-hash-at"));
	}

	@Test
	void readsTheServeBlockWhoseOrderIsNotItsWorldsOrder() {
		// wasm-tools hoists an interface ahead of the one that `use`s it, so the serve
		// block starts with wasi:clocks/types even though uni-http-server.wit starts with
		// wasi:http/types. A caller must read the names rather than assume the WIT order.
		ComponentImportBlock block = ComponentImportBlock.parse(embedded("import-block-http-server.bin"));

		assertThat(block.interfaceIds()).containsExactly("wasi:clocks/types@0.3.0", "wasi:http/types@0.3.0",
				"wasi:http/client@0.3.0", "wasi:random/random@0.3.0", SYSTEM_CLOCK, "wasi:clocks/monotonic-clock@0.3.0",
				CLI_TYPES, STDOUT, STDERR);
		assertThat(block.typeCount()).isEqualTo(15);
	}

	@Test
	void keepingEverythingIsByteIdentical() {
		for (String name : List.of("import-block.bin", "import-block-http-server.bin", "import-block-nogc-print.bin")) {
			byte[] bytes = embedded(name);
			ComponentImportBlock block = ComponentImportBlock.parse(bytes);

			ComponentImportBlock.Pruned kept = block.prune(block.members());

			assertThat(kept.bytes()).as(name).isEqualTo(bytes);
			assertThat(kept.instanceOf()).isEqualTo(block.instanceOf());
			assertThat(kept.typeCount()).isEqualTo(block.typeCount());
			assertThat(kept.members()).isEqualTo(block.members());
		}
	}

	@Test
	void pruningReproducesTheBlobWasmToolsGeneratesForTheSameMembers() {
		// import-block-nogc-print.bin is what `wasm-tools component new` emits for a
		// world importing exactly wasi:cli/stdout (which pulls in wasi:cli/types).
		// Pruning either of the bigger blobs down to that one function must land on the
		// same bytes -- the grammar and the renumbering are checked against that tool,
		// not against themselves.
		byte[] stdout = embedded("import-block-nogc-print.bin");
		for (String name : List.of("import-block.bin", "import-block-http-server.bin")) {
			ComponentImportBlock.Pruned pruned = ComponentImportBlock.parse(embedded(name))
				.prune(Map.of(STDOUT, Set.of("write-via-stream")));

			assertThat(pruned.bytes()).as(name).isEqualTo(stdout);
			assertThat(pruned.instanceOf()).containsExactly(Map.entry(CLI_TYPES, 0), Map.entry(STDOUT, 1));
			assertThat(pruned.typeCount()).isEqualTo(3);
		}
		// The same inside one interface. import-block-no-stat.bin is what the tool emits
		// for the base block's stub core without its three functions over
		// `descriptor-stat` and `new-timestamp` (regen.sh makes both): the functions
		// leave with the four named types and the anonymous ones only they reached,
		// wasi:filesystem/types no longer projects wasi:clocks/system-clock's `instant`,
		// and every index after each hole moves down.
		ComponentImportBlock base = ComponentImportBlock.parse(embedded("import-block.bin"));
		Map<String, Set<String>> keep = new LinkedHashMap<>(base.members());
		keep.put(FS_TYPES,
				Set.of(method("read-via-stream"), method("append-via-stream"), method("write-via-stream"),
						method("open-at"), method("read-directory"), method("create-directory-at"),
						method("unlink-file-at"), method("remove-directory-at"), method("readlink-at"),
						method("rename-at"), method("symlink-at"), method("link-at"), method("metadata-hash-at")));

		ComponentImportBlock.Pruned pruned = base.prune(keep);

		assertThat(pruned.bytes()).isEqualTo(blob("import-block-no-stat.bin"));
		assertThat(pruned.instanceOf()).isEqualTo(base.instanceOf());
		assertThat(pruned.typeCount()).isEqualTo(base.typeCount() - 1);
		assertThat(pruned.members().get(FS_TYPES)).contains("directory-entry", "open-flags")
			.doesNotContain("descriptor-stat", "new-timestamp", "link-count", "instant", method("stat"));
	}

	@Test
	void aMemberKeepsTheInterfaceWhoseTypeItProjectsAndNothingElseOfIt() {
		// preopens' instance type aliases wasi:filesystem/types' `descriptor` resource
		// out of that interface's instance: a resource is its defining interface's type,
		// so the projection cannot outlive it. Nothing else of that interface is asked
		// for, so `descriptor` is all it still exports.
		ComponentImportBlock block = ComponentImportBlock.parse(embedded("import-block.bin"));

		ComponentImportBlock.Pruned preopens = block.prune(Map.of(FS_PREOPENS, Set.of("get-directories")));

		assertThat(preopens.members()).containsExactly(Map.entry(FS_TYPES, new LinkedHashSet<>(List.of("descriptor"))),
				Map.entry(FS_PREOPENS, new LinkedHashSet<>(List.of("descriptor", "get-directories"))));
		assertThat(preopens.typeCount()).isEqualTo(3);

		// The chain is TRANSITIVE: `stat` answers a descriptor-stat, whose timestamps are
		// wasi:clocks/system-clock's `instant` -- so the clock stays, for that one type.
		ComponentImportBlock.Pruned stat = block.prune(Map.of(FS_TYPES, Set.of(method("stat"))));

		assertThat(stat.instanceOf().keySet()).containsExactly(SYSTEM_CLOCK, FS_TYPES);
		assertThat(stat.members().get(SYSTEM_CLOCK)).containsExactly("instant");
		assertThat(stat.members().get(FS_TYPES)).containsExactly("descriptor", "filesize", "error-code",
				"descriptor-type", "link-count", "instant", "descriptor-stat", method("stat"));

		// wasi:clocks/system-clock defines its `instant` record inline instead of
		// `use`ing wasi:clocks/types, so a program that only reads the clock keeps
		// nothing else.
		ComponentImportBlock.Pruned clock = block.prune(Map.of(SYSTEM_CLOCK, Set.of("now")));

		assertThat(clock.instanceOf().keySet()).containsExactly(SYSTEM_CLOCK);
		assertThat(clock.typeCount()).isEqualTo(1);
	}

	@Test
	void everyCombinationOfAnInterfacesFunctionsStaysWellFormed() {
		// The renumbering has to hold for every combination, not just the ones a program
		// happens to produce. wasi:filesystem/types is the interface with the most
		// members and the only one projecting another's type for some of them: each of
		// the 65,535 non-empty sets of its sixteen functions, with the interfaces around
		// it kept whole, must come back as a block whose every index lands inside it.
		ComponentImportBlock block = ComponentImportBlock.parse(embedded("import-block.bin"));
		List<String> functions = java.util.Objects.requireNonNull(block.members().get(FS_TYPES))
			.stream()
			.filter(name -> name.startsWith("[method]"))
			.toList();
		assertThat(functions).hasSize(16);

		for (int mask = 1; mask < (1 << functions.size()); mask++) {
			Map<String, Set<String>> keep = new LinkedHashMap<>(block.members());
			LinkedHashSet<String> kept = new LinkedHashSet<>();
			for (int i = 0; i < functions.size(); i++) {
				if ((mask & (1 << i)) != 0) {
					kept.add(functions.get(i));
				}
			}
			keep.put(FS_TYPES, kept);
			ComponentImportBlock.Pruned pruned = block.prune(keep);
			// Re-parsing is the well-formedness check: the parser consumes every byte and
			// throws on anything it does not recognize, an index naming a type that is
			// not above it included.
			ComponentImportBlock reparsed = ComponentImportBlock.parse(pruned.bytes());
			assertThat(reparsed.members()).as("mask %d", mask).isEqualTo(pruned.members());
			assertThat(reparsed.instanceOf()).isEqualTo(pruned.instanceOf());
			assertThat(reparsed.typeCount()).isEqualTo(pruned.typeCount());
			assertThat(java.util.Objects.requireNonNull(pruned.members().get(FS_TYPES))
				.stream()
				.filter(functions::contains)).containsExactlyElementsOf(kept);
		}
	}

	@Test
	void anUnknownInterfaceOrMemberIsRejectedByName() {
		ComponentImportBlock block = ComponentImportBlock.parse(embedded("import-block.bin"));

		assertThatThrownBy(() -> block.prune(Map.of("wasi:sockets/types@0.3.0", Set.of())))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not import 'wasi:sockets/types@0.3.0'");
		assertThatThrownBy(() -> block.prune(Map.of(FS_TYPES, Set.of(method("sync")))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not export '[method]descriptor.sync'");
	}

	@Test
	void aBlockItCannotAccountForThrowsRatherThanBeCopiedThrough() {
		// The pass owes "throw rather than emit a corrupt component": a byte it cannot
		// classify must not be silently carried into a renumbered block.
		byte[] corrupt = embedded("import-block.bin").clone();
		// The base block's first type section body: `01 42 02 ...` -- make the instance
		// type a component type (0x41), which the grammar does not allow here.
		corrupt[3] = 0x41;

		assertThatThrownBy(() -> ComponentImportBlock.parse(corrupt)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("instance type");
	}

}
