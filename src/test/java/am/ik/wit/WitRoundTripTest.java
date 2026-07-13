package am.ik.wit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips the whole in-repo WIT corpus through {@link WitLexer} / {@link WitParser} /
 * {@link WitPrinter}:
 *
 * <ul>
 * <li><strong>verbatim</strong> — lexing any corpus file and reassembling the tokens
 * reproduces it byte-for-byte, and the file parses;</li>
 * <li><strong>canonical</strong> — the {@code --wit} templates are captured verbatim from
 * {@code wasm-tools component wit}, so canonical-printing their parsed models must
 * reproduce them byte-for-byte (this pins the canonical style to the real tool);</li>
 * <li><strong>stability</strong> — for the hand-written {@code deps/**} corpus (whose
 * formatting is not canonical), the canonical print must re-parse to the identical model
 * and print byte-stably.</li>
 * </ul>
 */
class WitRoundTripTest {

	static List<Path> corpus() throws IOException {
		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(Path.of("src/wasm-component/deps"))) {
			walk.filter(p -> p.toString().endsWith(".wit")).sorted().forEach(files::add);
		}
		try (Stream<Path> walk = Files.walk(Path.of("src/wasm-component"), 1)) {
			walk.filter(p -> p.toString().endsWith(".wit")).sorted().forEach(files::add);
		}
		files.add(Path.of("examples/count-vowels/count_vowels_component.wit"));
		files.addAll(templates());
		return files;
	}

	static List<Path> templates() throws IOException {
		try (Stream<Path> walk = Files.walk(Path.of("src/test/resources/am/ik/rontolisp/codegen/wasm/component/wit"))) {
			return walk.filter(p -> p.toString().endsWith(".wit")).sorted().toList();
		}
	}

	@Test
	void verbatimRoundTripIsByteIdentical() throws IOException {
		List<Path> corpus = corpus();
		assertThat(corpus).hasSizeGreaterThan(40);
		for (Path file : corpus) {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			assertThat(WitPrinter.printVerbatim(WitLexer.lex(text))).as(file.toString()).isEqualTo(text);
			WitParser.parse(text); // and the whole corpus is inside the parsed subset
		}
	}

	@Test
	void canonicalPrintReproducesWasmToolsOutput() throws IOException {
		for (Path template : templates()) {
			String text = Files.readString(template, StandardCharsets.UTF_8);
			assertThat(WitPrinter.print(WitParser.parse(text))).as(template.toString()).isEqualTo(text);
		}
	}

	@Test
	void canonicalPrintIsModelFaithfulAndStable() throws IOException {
		for (Path file : corpus()) {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			WitDocument model = WitParser.parse(text);
			String canonical = WitPrinter.print(model);
			assertThat(WitParser.parse(canonical)).as(file.toString()).isEqualTo(model);
			assertThat(WitPrinter.print(WitParser.parse(canonical))).as(file.toString()).isEqualTo(canonical);
		}
	}

}
