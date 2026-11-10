package am.ik.wit;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WitPackageAssembler}: a package published as a directory is the
 * document a single file nesting its dependencies would parse to, and every position
 * stays in the file it was written in.
 */
class WitPackageAssemblerTest {

	private static WitSourceFile file(String name, String text) {
		return new WitSourceFile(name, text);
	}

	private static final WitSourceFile STORE = file("wit/store.wit", """
			interface store {
			  resource bucket;
			  read: func(b: borrow<bucket>) -> input-stream;

			  use wasi:io/streams@0.2.0.{input-stream};
			}
			""");

	// 1 doc, 2 interface, 3 use, 4 blank, 5 increment
	private static final WitSourceFile ATOMIC = file("wit/atomic.wit", """
			/// Atomic operations.
			interface atomics {
			  use store.{bucket};

			  increment: func(b: borrow<bucket>, delta: u64) -> u64;
			}
			""");

	private static final WitSourceFile WORLD = file("wit/world.wit", """
			package wasi:keyvalue@0.2.0;

			world imports {
			  import atomics;
			}
			""");

	// a dependency directory: one file declares its package, one does not
	private static final List<WitSourceFile> IO = List.of(file("wit/deps/io/error.wit", """
			interface error {
			  resource error;
			}
			"""), file("wit/deps/io/streams.wit", """
			package wasi:io@0.2.0;

			interface streams {
			  use error.{error};
			  resource input-stream;
			}
			"""));

	// a dependency file nesting a package of its own
	private static final List<WitSourceFile> CLOCKS = List.of(file("wit/deps/clocks.wit", """
			package wasi:clocks@0.2.0;

			interface wall-clock {
			  now: func() -> u64;
			}

			package wasi:time@0.1.0 {
			  interface zone {
			    offset: func() -> s32;
			  }
			}
			"""));

	@Test
	void aPackageDirectoryIsOneDocumentWhosePositionsStayInTheirFiles() {
		WitParseResult parsed = WitPackageAssembler.assemble(List.of(ATOMIC, STORE, WORLD), List.of(CLOCKS, IO));
		WitResolver resolver = new WitResolver(parsed.document());
		assertThat(resolver.interfaceIds()).containsExactlyInAnyOrder("wasi:keyvalue/atomics@0.2.0",
				"wasi:keyvalue/store@0.2.0", "wasi:clocks/wall-clock@0.2.0", "wasi:time/zone@0.1.0",
				"wasi:io/error@0.2.0", "wasi:io/streams@0.2.0");
		// a use clause reaches a sibling file of its package, and a dependency's package
		WitItem.InterfaceDef atomics = Objects.requireNonNull(resolver.findInterface("wasi:keyvalue/atomics@0.2.0"));
		WitItem.InterfaceDef store = Objects.requireNonNull(resolver.findInterface("wasi:keyvalue/store@0.2.0"));
		assertThat(Objects.requireNonNull(resolver.resolveOwned(atomics, "bucket")).owner()).isSameAs(store);
		assertThat(Objects.requireNonNull(resolver.resolveOwned(store, "input-stream")).owner().name())
			.isEqualTo("streams");
		WitItem.InterfaceDef streams = Objects.requireNonNull(resolver.findInterface("wasi:io/streams@0.2.0"));
		assertThat(Objects.requireNonNull(resolver.resolveOwned(streams, "error")).owner().name()).isEqualTo("error");
		// positions per file
		WitLocations locations = parsed.locations();
		WitItem increment = atomics.items().get(1);
		assertThat(locations.fileOf(increment)).isEqualTo("wit/atomic.wit");
		assertThat(locations.lineOf(increment)).isEqualTo(5);
		assertThat(locations.fileOf(store)).isEqualTo("wit/store.wit");
		assertThat(locations.lineOf(store)).isEqualTo(1);
		// the document prints to one text that parses back to it
		assertThat(WitParser.parse(WitPrinter.print(parsed.document()))).isEqualTo(parsed.document());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			package a:b;|package a:c;|wit/two.wit:1: package a:c is not the package a:b that wit/one.wit declares
			interface x {}|interface x {}|wit/two.wit:1: duplicate item named `x` (first at wit/one.wit:1)
			interface x {}|interface {}|wit/two.wit: Expected
			""")
	void aPackageThatDisagreesWithItselfNamesTheFileAndLine(String one, String two, String message) {
		assertThatThrownBy(() -> WitPackageAssembler
			.assemble(List.of(file("wit/one.wit", one), file("wit/two.wit", two)), List.of()))
			.isInstanceOf(WitParseException.class)
			.hasMessageStartingWith(message);
		assertThatThrownBy(() -> WitPackageAssembler.assemble(List.of(WORLD),
				List.of(List.of(file("wit/deps/x.wit", "interface x {}")))))
			.hasMessage("wit/deps/x.wit:1: a dependency declares no package (`package namespace:name;`)");
	}

}
