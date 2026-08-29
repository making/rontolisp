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
 * The shipped {@code metal} library as a library: it parses, its public names are exactly
 * the ones the package registry exports, the JVM compile path splices it (pulling
 * {@code appkit} in behind it, since {@code metal:run}'s clock is {@code appkit:timer})
 * and the WASM one refuses a program that reaches it. Nothing here opens a window or
 * touches a GPU (CI has neither); the visible behavior is {@code examples/macos/metal-*}.
 */
class MetalLibraryTest {

	/**
	 * Every {@code metal:} single-colon name mentioned anywhere in the library, which is
	 * the whole of its public surface: the defuns, the class and its readers, and the
	 * exported constants. The internal helpers are spelled {@code metal::} and do not
	 * appear here.
	 */
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
				if (name.startsWith("METAL:") && !name.startsWith("METAL::")) {
					out.add(name.substring("METAL:".length()));
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
		assertThat(externalNames(MetalLibrary.forms()))
			.containsExactlyInAnyOrderElementsOf(PackageRegistry.metalFunctionNames());
	}

	@Test
	void qualifiedNamesAreRecognized() {
		assertThat(MetalLibrary.isMetalQualified("METAL:ATTACH")).isTrue();
		assertThat(MetalLibrary.isMetalQualified("METAL::%DEPTH-TEXTURE")).isTrue();
		assertThat(MetalLibrary.isMetalQualified("APPKIT:WINDOW")).isFalse();
		assertThat(MetalLibrary.isMetalQualified("ATTACH")).isFalse();
	}

	@Test
	void theCompilePathSplicesTheLibraryExactlyWhenMetalIsReferenced() {
		List<LispVal> unrelated = read("(print 1)");
		assertThat(MetalLibrary.process(unrelated)).isSameAs(unrelated);
		assertThat(MetalLibrary.process(read("(objc:send x \"y\")"))).hasSize(1);
		assertThat(MetalLibrary.process(read("(metal:attach w)"))).hasSize(MetalLibrary.forms().size() + 1);
		assertThat(MetalLibrary.process(read("(in-package metal) (attach w)")))
			.hasSize(MetalLibrary.forms().size() + 2);
	}

	@Test
	void aProgramThatDrawsAFrameDoesNotCarryTheDepthState() {
		// metal is large for what most programs use of it, so every definition is a
		// defun/defconstant the pruner can key by name; only the context class is a
		// root.
		List<LispVal> pruned = LibraryDefunPruner
			.prune(MetalLibrary.process(read("(metal:frame *ctx* (lambda (e) e))")));
		List<String> names = definitionNames(pruned);
		assertThat(names).contains("METAL:FRAME");
		assertThat(names).doesNotContain("METAL:DEPTH-STATE", "METAL:SHARED-BUFFER", "METAL:PIPELINE", "METAL:RESIZE");
	}

	@Test
	void theCompilePathRefusesMetalOnTheWasmBackendsAndCompilesItForTheJvm(@TempDir Path dir) throws IOException {
		Path source = dir.resolve("gpu.lisp");
		Files.writeString(source, "(print (metal:attach (appkit:window \"hi\")))\n");
		assertThatThrownBy(() -> compile(source, "-o", dir.resolve("prog.wasm").toString()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Cannot compile: METAL:ATTACH")
			.hasMessageContaining("not in a .wasm");
		// The JVM backend carries the binding as an embedded blob, and the splice chain
		// pulls appkit in behind metal: metal:run's clock is appkit:timer.
		Path prog = dir.resolve("Prog.class");
		compile(source, "-o", prog.toString());
		String bytes = Files.readString(prog, StandardCharsets.ISO_8859_1);
		assertThat(bytes).contains("RontoLispObjcBridge").contains("METAL$colonATTACH");
	}

	private static List<String> definitionNames(List<LispVal> forms) {
		return forms.stream()
			.filter(LispCons.class::isInstance)
			.map(LispCons.class::cast)
			.filter(cons -> cons.car() instanceof LispSymbol op && op.name().startsWith("DEF"))
			.filter(cons -> cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol)
			.map(cons -> ((LispSymbol) ((LispCons) cons.cdr()).car()).name())
			.toList();
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
