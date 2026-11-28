package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.WitExportInliner;
import am.ik.rontolisp.reader.LispReader;
import am.ik.wit.WitDocument;
import am.ik.wit.WitItem;
import am.ik.wit.WitParser;
import am.ik.wit.WitResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WitScaffolder} and the {@code --scaffold-wit} CLI mode: "someone
 * handed me a {@code .wit}, now what".
 *
 * <p>
 * The definition-of-done item: the generated file must <strong>compile unchanged</strong>
 * -- the stubs signal (a Java method throws) at run time, not at compile time, so the
 * contract check passes and the world can be filled in one export at a time.
 */
class WitScaffolderTest {

	@TempDir
	Path tempDir;

	private static final String WORLD = """
			package root:component;

			world analyzer {
			  /// Count the vowels in the given text.
			  /// Case-insensitive.
			  export count-vowels: func(s: string) -> s32;
			  export shout: async func(text: string, loud: bool) -> string;
			  export ping: func();
			}
			""";

	private String runCli(String... args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(out));
		cli.run(args);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static final String TWO_WORLDS = """
			package root:component;

			world first {
			  export a: func();
			}

			world second {
			  export b: func();
			}
			""";

	@Test
	void scaffoldsOneStubPerExportWithTheWitNamesDocsAndSignature() {
		String lisp = WitScaffolder.scaffold(WORLD, "wit/analyzer.wit", null);
		assertThat(lisp).startsWith(";;;; Implementation of the WIT world 'analyzer' (wit/analyzer.wit).\n")
			// The directive names the world, so the generated file is checked against it.
			.contains("(rontolisp:wit-export \"wit/analyzer.wit\" :world analyzer)")
			// One stub per export, parameters spelled as the WIT spells them, the docs
			// and
			// the signature above it; an async func is rendered as such.
			.contains("""
					;;; Count the vowels in the given text.
					;;; Case-insensitive.
					;;; WIT: count-vowels: func(s: string) -> s32
					(defun count-vowels (s)
					""")
			.contains(";;; WIT: shout: async func(text: string, loud: bool) -> string\n(defun shout (text loud)")
			.contains(";;; WIT: ping: func()\n(defun ping ()")
			// The bodies signal rather than fail to compile.
			.contains("(error \"count-vowels is not implemented yet\"))")
			.contains("(error \"ping is not implemented yet\"))")
			.endsWith("\n");
		// An interface's functions, exported by reference or inline, are stubbed as a
		// world's function is.
		assertThat(WitScaffolder.scaffold("""
				package docs:adder@0.1.0;

				interface add {
				  /// Add two integers.
				  add: func(x: s32, y: s32) -> s32;
				  negate: func(x: s32) -> s32;
				}

				world adder {
				  export add;
				}
				""", "adder.wit", "adder")).contains(";;; Add two integers.\n;;; WIT: add: func(x: s32, y: s32) -> s32")
			.contains("(defun add (x y)")
			.contains("(defun negate (x)")
			.contains("(rontolisp:wit-export \"adder.wit\" :world adder)");
		assertThat(WitScaffolder.scaffold("""
				package root:component;

				world calc {
				  export ops: interface {
				    add: func(x: s32, y: s32) -> s32;
				  }
				}
				""", "calc.wit", "calc")).contains("(defun add (x y)").contains(":world calc)");
	}

	@Test
	void scaffoldsTheNamedWorldAndRefusesAnAmbiguousOrMissingOne() throws Exception {
		assertThat(WitScaffolder.scaffold(TWO_WORLDS, "w.wit", "second")).contains("(defun b ()")
			.contains(":world second)")
			.doesNotContain("(defun a ()");
		assertThatThrownBy(() -> WitScaffolder.scaffold(TWO_WORLDS, "w.wit", null))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("--scaffold-wit: w.wit declares 2 worlds (first, second); name one with --world");
		assertThatThrownBy(() -> WitScaffolder.scaffold(TWO_WORLDS, "w.wit", "third"))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("--scaffold-wit: w.wit has no world named 'third' (found: first, second)");
		assertThatThrownBy(() -> WitScaffolder.scaffold("package root:component;\n", "w.wit", null))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("--scaffold-wit: w.wit declares no world");
		Path wit = this.tempDir.resolve("two.wit");
		Files.writeString(wit, TWO_WORLDS);
		assertThatThrownBy(() -> runCli("--scaffold-wit", wit.toString()))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("declares 2 worlds (first, second); name one with --world");
	}

	@Test
	void cliWritesTheSkeletonToTheOutputFileOrPrintsIt() throws Exception {
		Path wit = this.tempDir.resolve("analyzer.wit");
		Files.writeString(wit, WORLD);
		Path lisp = this.tempDir.resolve("analyzer.lisp");
		runCli("--scaffold-wit", wit.toString(), "-o", lisp.toString());
		// The path the generated source names resolves against the generated file's own
		// directory (both are in tempDir here), the way wit-export -- like load -- reads
		// it.
		assertThat(Files.readString(lisp)).contains("(rontolisp:wit-export \"analyzer.wit\" :world analyzer)")
			.contains("(defun count-vowels (s)");
		assertThat(runCli("--scaffold-wit", wit.toString())).contains("(defun count-vowels (s)")
			.contains("(rontolisp:wit-export \"" + wit + "\" :world analyzer)");
	}

	@Test
	void theScaffoldedProgramCompilesUnchanged() throws Exception {
		// The definition-of-done item: feed the generated source straight back through
		// the compiler. It must pass its own contract check (every export has a defun of
		// the right arity) and compile -- the stubs signal at RUN time. Both shapes: a
		// world's own function, and the idiomatic separated interface.
		Path wit = this.tempDir.resolve("analyzer.wit");
		Files.writeString(wit, """
				package root:component;

				world analyzer {
				  /// Count the vowels in the given text.
				  export count-vowels: func(s: string) -> s32;
				  export ping: func();
				}
				""");
		Path lisp = this.tempDir.resolve("analyzer.lisp");
		runCli("--scaffold-wit", wit.toString(), "-o", lisp.toString());
		assertThatCode(() -> WasmLispCompiler.builder()
			.component(true)
			.optimize(OptimizeLevel.NONE)
			.build()
			.compile(WitExportInliner.inline(LispReader.readAllFromString(Files.readString(lisp)),
					this.tempDir.toString(), WitExportDirective.Backend.WASM_GC, SourceLoader.fileSystem())))
			.doesNotThrowAnyException();
		Files.writeString(this.tempDir.resolve("adder.wit"), """
				package docs:adder@0.1.0;

				interface add {
				  add: func(x: s32, y: s32) -> s32;
				}

				world adder {
				  export add;
				}
				""");
		// A resource's functions are stubbed too, named as an import binds them.
		Files.writeString(this.tempDir.resolve("res.wit"), """
				package docs:res@0.1.0;

				interface counters {
				  resource counter {
				    constructor(start: u32);
				    add: func(n: u32) -> u32;
				    merge: static func(a: counter, b: borrow<counter>) -> counter;
				  }
				}

				world res {
				  export counters;
				}
				""");
		// And end-to-end through the CLI, which is how a user meets it.
		for (String name : List.of("analyzer", "adder", "res")) {
			Path source = this.tempDir.resolve(name + ".lisp");
			runCli("--scaffold-wit", this.tempDir.resolve(name + ".wit").toString(), "-o", source.toString());
			Path wasm = this.tempDir.resolve(name + ".wasm");
			runCli(source.toString(), "-o", wasm.toString(), "--component");
			assertThat(Files.readAllBytes(wasm)).startsWith(new byte[] { 0x00, 0x61, 0x73, 0x6D });
		}
		assertThat(Files.readString(this.tempDir.resolve("res.lisp")))
			.contains(";;; WIT: constructor(start: u32)\n(defun counter-new (start)\n")
			.contains(";;; WIT: add: func(n: u32) -> u32\n(defun counter-add (self n)\n")
			.contains(";;; WIT: merge: static func(a: counter, b: borrow<counter>) -> counter\n"
					+ "(defun counter-merge (a b)\n");
	}

	private static final String API = "rontolisp-java-api/src/main/java";

	@ParameterizedTest
	@CsvSource(delimiter = '|',
			textBlock = """
					examples/wit/shapes/wit/shapes.wit     | Shapes   |                                                                         | public static WitResult<Double, String> area(Shape s) {
					examples/wit/keyvalue/wit/keyvalue.wit | PageHits | world page-hits { import store; export hits: func(page: string) -> u32; } | WitResult<Optional<byte[]>, Error> get(String key);
					examples/wit/sockets/wit/sockets.wit   | Echo     | world echo { import types; export echo: func(port: u16) -> string; }      | record Ipv4Address(byte v0, byte v1, byte v2, byte v3) {
					examples/wit/resource/wit/counters.wit | Client   | world client { import report; export lead: func() -> string; }            | String summary(Registry.Counter c);
					examples/wit/resource/wit/counters.wit | Counters | counters                                                                  | public static class Counter implements AutoCloseable {
					examples/wit/resource/wit/counters.wit | Reporter | reporter                                                                  | public static String summary(Registry.Counter c) {
					""")
	void aJavaSkeletonPassesTheCheckAndCompilesToTheWorld(String example, String className, String world,
			String declared) throws Exception {
		// The example worlds, the three of interfaces alone given a world importing one:
		// the generated class compiles unchanged, every @WitImport interface checked,
		// to a component exporting what the world exports. report takes registry's
		// counter with use, so registry's interface is generated too; an alias of a
		// tuple is a record of its name. A resource the world exports is a class, its
		// constructor, methods and static methods the resource's, one the component
		// imports the member interface standing for it.
		Path wit = this.tempDir.resolve("wit").resolve(Path.of(example).getFileName());
		Files.createDirectories(wit.getParent());
		// a world written beside the file's, or one of the file's named
		boolean written = world != null && world.contains(" ");
		Files.writeString(wit, Files.readString(Path.of(example)) + (written ? "\n" + world + "\n" : ""));
		String worldName = world == null ? WitScaffolder.worldName(Files.readString(wit), null, wit.toString())
				: world.split(" ")[written ? 1 : 0];
		Path java = this.tempDir.resolve(className + ".java");
		runCli("--scaffold-wit", wit.toString(), "--world", worldName, "-o", java.toString());
		assertThat(Files.readString(java)).doesNotContain("Left out").contains(declared);
		Path wasm = this.tempDir.resolve(className + ".wasm");
		runCli(java.toString(), "--java-source-path", API, "-o", wasm.toString(), "--component", "--emit-wit");
		assertThat(exportsOf(Files.readString(this.tempDir.resolve(className + ".wit")), "root"))
			.isEqualTo(exportsOf(Files.readString(wit), worldName));
	}

	@Test
	void aJavaSkeletonCarriesTheMappingTheDocsAndTheSignatures() throws Exception {
		Path wit = this.tempDir.resolve("wit").resolve("shapes.wit");
		Files.createDirectories(wit.getParent());
		Files.copy(Path.of("examples/wit/shapes/wit/shapes.wit"), wit);
		Path java = this.tempDir.resolve("Shapes.java");
		runCli("--scaffold-wit", wit.toString(), "-o", java.toString());
		// a result is a WitResult, a tuple a record named after where it stands, flags a
		// Set of an enum; the docs are Javadoc above the WIT signature
		assertThat(Files.readString(java)).contains("""
				import java.util.List;
				import java.util.Optional;
				import java.util.Set;

				import am.ik.rontolisp.wit.WitExport;
				import am.ik.rontolisp.wit.WitResult;

				@WitExport(value = "shapes", wit = "wit/shapes.wit")
				public class Shapes {

				    public record Summary(int count, double total) {
				    }

				    /**
				     * How many areas, and their sum.
				     *
				     * WIT: {@code summarize: func(areas: list<f64>) -> summary}
				     */
				    public static Summary summarize(List<Double> areas) {
				        throw new UnsupportedOperationException("summarize is not implemented yet");
				    }
				""")
			.contains("""
					        public sealed interface Shape {

					            record Circle(double value) implements Shape {
					            }

					            record Rect(Point value) implements Shape {
					            }

					            record Dot() implements Shape {
					            }
					        }
					""")
			.contains("public static Optional<BoundsResult> bounds(List<Point> ps) {")
			.contains("public record BoundsResult(Point v0, Point v1) {")
			.contains("public static Set<Style> embolden(Set<Style> s) {")
			.contains("public record Tagged(Shape shape, Color color, Set<Style> style, Optional<String> label) {")
			.contains("WIT: {@code total-area: async func(shapes: list<shape>) -> result<f64, string>}");
		// printed, by --source-language java, the class named after the world
		assertThat(runCli("--scaffold-wit", wit.toString(), "--source-language", "java"))
			.startsWith("// The skeleton of a class implementing the WIT world 'shapes' (" + wit + ").")
			.contains("public class Shapes {");
		assertThatThrownBy(() -> runCli("--scaffold-wit", wit.toString(), "-o", "shapes.clj"))
			.hasMessage("--scaffold-wit writes Common Lisp or Java, not clojure");
	}

	@Test
	void aJavaSkeletonLeavesOutWhatTheCheckRefusesWordedAsItWordsIt() throws Exception {
		Path wit = this.tempDir.resolve("app.wit");
		Files.writeString(wit, """
				package test:skip;

				interface host {
				  flag: func(x: option<bool>);
				  send: async func(data: stream<u8>);
				  ok: func(x: u32) -> u32;
				}

				world exports {
				  export check: func(x: option<bool>) -> u32;
				  export fine: func() -> u32;
				}

				world imports {
				  import host;
				  export fine: func() -> u32;
				}
				""");
		String option = "an option of a type one of whose values the boundary cannot tell from none";
		// each refusal is the check's: the declaration left out, written in, is refused
		// in those words; an async func handed a stream is started, answering the
		// future of its result, which the check takes
		String started = "WitFuture<Void> send(Consumer<OutputStream> data);";
		Map<String, List<String>> worlds = Map.of("exports", List.of("public static int fine() {",
				"public static int check(Optional<Boolean> x) {\n        return 0;\n    }\n\n    public static int fine() {",
				"check (check of world exports, app.wit:10): its parameter x is option<bool>, " + option), "imports",
				List.of(started, started + "\n\n        void flag(Optional<Boolean> x);",
						"flag (flag of test:skip/host, app.wit:4): its parameter x is option<bool>, " + option));
		for (Map.Entry<String, List<String>> world : worlds.entrySet()) {
			List<String> skipped = new ArrayList<>();
			Path java = this.tempDir.resolve("App.java");
			String source = WitScaffolder.scaffoldJava(Files.readString(wit), wit.toString(), "app.wit",
					java.toString(), world.getKey(), "App", skipped::add);
			List<String> refusals = world.getValue().subList(2, world.getValue().size());
			assertThat(skipped).isEqualTo(refusals);
			assertThat(source).contains("//   " + refusals.get(0)).contains(world.getValue().get(0));
			Files.writeString(java,
					source.replace(world.getValue().get(0), world.getValue().get(1))
						.replace("import am.ik.rontolisp.wit.WitExport;",
								"import java.util.Optional;\n\nimport am.ik.rontolisp.wit.WitExport;"));
			assertThatThrownBy(() -> runCli(java.toString(), "--java-source-path", API, "-o",
					this.tempDir.resolve("app.wasm").toString(), "--component"))
				.hasMessageEndingWith(refusals.get(0));
		}
		// an export item the boundary refuses whole, with every export it holds: an
		// interface naming a type of another that holds a handle
		Path held = this.tempDir.resolve("held.wit");
		Files.writeString(held, """
				package test:held;

				interface store {
				  resource blob;
				  record pair { b: borrow<blob> }
				  make: func() -> blob;
				}

				interface files {
				  use store.{pair};
				  size: func(p: pair) -> u32;
				}

				world held {
				  import store;
				  export files;
				  export fine: func() -> u32;
				}
				""");
		List<String> skipped = new ArrayList<>();
		String source = WitScaffolder.scaffoldJava(Files.readString(held), held.toString(), "held.wit",
				this.tempDir.resolve("Held.java").toString(), "held", "Held", skipped::add);
		assertThat(skipped)
			.containsExactly("held.wit:16: interface 'test:held/files' uses 'pair' of test:held/store, a type "
					+ "that holds a handle: name the resource in the use clause and define the type in the interface");
		assertThat(source).contains("public static int fine() {").doesNotContain("size(");
	}

	// The exports of a world, each function with its signature: what a component
	// compiled from it exports.
	private static List<String> exportsOf(String witText, String worldName) {
		WitDocument document = WitParser.parse(witText);
		WitResolver resolver = new WitResolver(document);
		List<String> exports = new ArrayList<>();
		for (WitItem.World world : worlds(document.items())) {
			if (!world.name().equals(worldName)) {
				continue;
			}
			for (WitItem item : world.items()) {
				if (item instanceof WitItem.ExportNamed named
						&& named.extern() instanceof WitItem.Extern.ExternFunc func) {
					exports.add(named.name() + ": " + WitScaffolder.signature(func.func()));
				}
				if (item instanceof WitItem.ExportRef ref) {
					WitItem.InterfaceDef iface = resolver.findInterface(ref.target().toString());
					String id = resolver.canonicalId(java.util.Objects.requireNonNull(iface));
					for (WitItem member : iface.items()) {
						if (member instanceof WitItem.FuncDef func) {
							exports.add(id + "#" + func.name() + ": " + WitScaffolder.signature(func.func()));
						}
						// a resource's functions, with it
						if (member instanceof WitItem.ResourceDef resource && resource.body() != null) {
							for (WitItem inner : resource.body()) {
								if (inner instanceof WitItem.FuncDef func) {
									exports.add(id + "#" + resource.name() + "." + func.kind() + " " + func.name()
											+ ": " + WitScaffolder.signature(func.func()));
								}
							}
						}
					}
				}
			}
		}
		return exports.stream().sorted().toList();
	}

	private static List<WitItem.World> worlds(List<WitItem> items) {
		List<WitItem.World> worlds = new ArrayList<>();
		for (WitItem item : items) {
			if (item instanceof WitItem.World world) {
				worlds.add(world);
			}
			if (item instanceof WitItem.PackageBlock block) {
				worlds.addAll(worlds(block.items()));
			}
		}
		return worlds;
	}

}
