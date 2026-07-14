package am.ik.wit;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WitParser#parseLocated(String)} and {@link WitLocations}: the
 * source position of an item is kept BESIDE the model (by identity), so a consumer such
 * as {@code rontolisp:wit-export} can report a contract violation against the WIT line
 * that declared it -- without the {@link WitItem} records losing their value semantics
 * (the round-trip and idempotence pins in {@link WitRoundTripTest} depend on model
 * equality).
 */
class WitLocationsTest {

	// 1 package, 2 blank, 3 doc, 4 world, 5 export, 6 blank, 7 export, 8 close.
	private static final String SOURCE = """
			package root:component;

			/// Analyze a piece of text.
			world analyzer {
			  export count-vowels: func(s: string) -> s32;

			  export shout: func(s: string) -> string;
			}
			""";

	private static WitItem.World world(WitDocument document) {
		return (WitItem.World) document.items().get(1);
	}

	@Test
	void locatesTheWorldAtItsOwnLineNotItsDocComment() {
		WitParseResult parsed = WitParser.parseLocated(SOURCE);
		WitItem.World world = world(parsed.document());
		// The doc comment rides in the leading trivia; an item starts at its first token.
		assertThat(world.meta().docs()).containsExactly(" Analyze a piece of text.");
		assertThat(parsed.locations().lineOf(world)).isEqualTo(4);
		assertThat(parsed.locations().columnOf(world)).isEqualTo(1);
		assertThat(parsed.locations().offsetOf(world)).isEqualTo(SOURCE.indexOf("world analyzer"));
	}

	@Test
	void locatesEveryExportItemOfTheWorld() {
		WitParseResult parsed = WitParser.parseLocated(SOURCE);
		List<WitItem> items = world(parsed.document()).items();
		assertThat(items).hasSize(2);
		WitLocations locations = parsed.locations();
		assertThat(locations.lineOf(items.get(0))).isEqualTo(5);
		assertThat(locations.lineOf(items.get(1))).isEqualTo(7);
		// A world item is indented by the canonical two spaces.
		assertThat(locations.columnOf(items.get(0))).isEqualTo(3);
		assertThat(locations.columnOf(items.get(1))).isEqualTo(3);
	}

	@Test
	void locatesTheHeaderAndTheWorldsInsideAPackageBlock() {
		String source = """
				package root:component;

				package example:app {
				  world first {
				    export a: func();
				  }
				}
				""";
		WitParseResult parsed = WitParser.parseLocated(source);
		WitLocations locations = parsed.locations();
		WitItem.PackageHeader header = (WitItem.PackageHeader) parsed.document().items().get(0);
		WitItem.PackageBlock block = (WitItem.PackageBlock) parsed.document().items().get(1);
		WitItem.World first = (WitItem.World) block.items().get(0);
		assertThat(locations.lineOf(header)).isEqualTo(1);
		assertThat(locations.lineOf(block)).isEqualTo(3);
		assertThat(locations.lineOf(first)).isEqualTo(4);
		assertThat(locations.lineOf(first.items().get(0))).isEqualTo(5);
	}

	@Test
	void parsingWithLocationsYieldsTheSameModelAsPlainParsing() {
		// The positions live outside the model: parseLocated must not perturb it, or the
		// WitRoundTripTest equality pins would be testing a different document.
		WitParseResult parsed = WitParser.parseLocated(SOURCE);
		assertThat(parsed.document()).isEqualTo(WitParser.parse(SOURCE));
		assertThat(WitPrinter.print(parsed.document())).isEqualTo(WitPrinter.print(WitParser.parse(SOURCE)));
	}

	@Test
	void anItemFromAnotherParseIsUnknown() {
		// The table is keyed by IDENTITY, deliberately: an equal item from a different
		// parse (or a copy) is not the item that was located here.
		WitParseResult parsed = WitParser.parseLocated(SOURCE);
		WitItem.World other = world(WitParser.parseLocated(SOURCE).document());
		assertThat(other).isEqualTo(world(parsed.document()));
		assertThat(parsed.locations().offsetOf(other)).isEqualTo(-1);
		assertThat(parsed.locations().lineOf(other)).isZero();
		assertThat(parsed.locations().columnOf(other)).isZero();
	}

	@Test
	void noneKnowsNothing() {
		// A document built in memory (through the Wit DSL) has no source, so every lookup
		// is unknown and an error message degrades to line 0 rather than lying.
		WitItem.World world = Wit.world("root");
		assertThat(WitLocations.none().offsetOf(world)).isEqualTo(-1);
		assertThat(WitLocations.none().lineOf(world)).isZero();
		assertThat(WitLocations.none().columnOf(world)).isZero();
	}

	@Test
	void lineAndColumnOfAnOffsetAreOneBased() {
		String source = "ab\ncd\n";
		assertThat(WitLocations.lineOf(source, 0)).isEqualTo(1);
		assertThat(WitLocations.columnOf(source, 0)).isEqualTo(1);
		assertThat(WitLocations.lineOf(source, 1)).isEqualTo(1);
		assertThat(WitLocations.columnOf(source, 1)).isEqualTo(2);
		// The character right after a newline opens the next line at column 1.
		assertThat(WitLocations.lineOf(source, 3)).isEqualTo(2);
		assertThat(WitLocations.columnOf(source, 3)).isEqualTo(1);
		assertThat(WitLocations.lineOf(source, 4)).isEqualTo(2);
		assertThat(WitLocations.columnOf(source, 4)).isEqualTo(2);
	}

}
