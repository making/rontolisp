package am.ik.rontolisp.eval;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shipped {@code scene} library as a library: it parses, its public names are exactly
 * the ones the package registry exports, the compile path splices it TOGETHER WITH
 * everything it stands on (geom, metal, linalg, appkit) and the WASM backends refuse a
 * program that reaches it. Nothing here opens a window (CI has no display); the visible
 * behavior is {@code examples/macos/scene-*}.
 */
class SceneLibraryTest {

	private static Set<String> externalNames(List<LispVal> forms) {
		Set<String> out = new TreeSet<>();
		for (LispVal form : forms) {
			collect(form, out);
		}
		return out;
	}

	private static void collect(LispVal form, Set<String> out) {
		switch (form) {
			case LispSymbol sym -> {
				String name = sym.name();
				if (name.startsWith("SCENE:") && !name.startsWith("SCENE::")) {
					out.add(name.substring("SCENE:".length()));
				}
			}
			case LispCons cons -> {
				collect(cons.car(), out);
				collect(cons.cdr(), out);
			}
			default -> {
			}
		}
	}

	@Test
	void theLibrarysPublicNamesAreExactlyWhatThePackageExports() {
		assertThat(externalNames(SceneLibrary.forms()))
			.containsExactlyInAnyOrderElementsOf(PackageRegistry.sceneFunctionNames());
	}

	@Test
	void qualifiedNamesAreRecognized() {
		assertThat(SceneLibrary.isSceneQualified("SCENE:VIEWER")).isTrue();
		assertThat(SceneLibrary.isSceneQualified("SCENE::%RENDER")).isTrue();
		assertThat(SceneLibrary.isSceneQualified("METAL:ATTACH")).isFalse();
		assertThat(SceneLibrary.isSceneQualified("VIEWER")).isFalse();
	}

	@Test
	void theCompilePathSplicesTheLibraryExactlyWhenSceneIsReferenced() {
		List<LispVal> unrelated = read("(print 1)");
		assertThat(SceneLibrary.process(unrelated)).isSameAs(unrelated);
		assertThat(SceneLibrary.process(read("(metal:attach w)"))).hasSize(1);
		assertThat(SceneLibrary.process(read("(scene:viewer)"))).hasSize(SceneLibrary.forms().size() + 1);
		assertThat(SceneLibrary.process(read("(in-package scene) (viewer)"))).hasSize(SceneLibrary.forms().size() + 2);
	}

	@Test
	void aSceneProgramPullsInEverythingItStandsOn(@TempDir Path dir) throws IOException {
		// The order of the splice chain is the point: scene's bodies reference geom:,
		// metal:, linalg: and appkit:, and each of those passes runs AFTER scene's, so
		// the reference it introduces is seen.
		Path source = dir.resolve("view.lisp");
		Files.writeString(source, "(scene:add (scene:viewer) (geom:box 10))\n");
		Path prog = dir.resolve("Prog.class");
		compile(source, "-o", prog.toString());
		String bytes = Files.readString(prog, StandardCharsets.ISO_8859_1);
		assertThat(bytes).contains("SCENE$colonVIEWER")
			.contains("METAL$colonATTACH")
			.contains("GEOM$colonBOX")
			.contains("APPKIT$colonWINDOW")
			.contains("RontoLispObjcBridge");
	}

	@Test
	void theCompilePathRefusesSceneOnTheWasmBackendsNamingThePackage(@TempDir Path dir) throws IOException {
		Path source = dir.resolve("view.lisp");
		Files.writeString(source, "(scene:viewer :title \"hi\")\n");
		for (String[] output : new String[][] { { "-o", dir.resolve("prog.wasm").toString() },
				{ "-o", dir.resolve("comp.wasm").toString(), "--component" } }) {
			assertThatThrownBy(() -> compile(source, output)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Cannot compile: SCENE:VIEWER")
				.hasMessageContaining("not in a .wasm");
		}
	}

	private static void compile(Path source, String... options) {
		List<String> args = new ArrayList<>();
		args.add(source.toString());
		args.addAll(List.of(options));
		new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()))
			.run(args.toArray(String[]::new));
	}

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

}
