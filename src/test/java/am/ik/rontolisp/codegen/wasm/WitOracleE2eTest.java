package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import am.ik.wit.WitItem;
import am.ik.wit.WitParser;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code --emit-wit} contract against the real tool: the WIT text recorded by
 * a component compile is byte-identical to what {@code wasm-tools component wit} prints
 * for the same bytes (the serve variants differ by exactly the {@code use} clause that
 * tool omits and the template restores -- see {@link WitEmitter}). Runs only where a
 * {@code wasm-tools} binary is on {@code PATH} (locally; the Docker integration image has
 * only wasmtime), so the always-on line-level pins live in {@link WitEmitterTest}.
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.WitOracleE2eTest#wasmToolsIsAvailable")
class WitOracleE2eTest {

	@TempDir
	Path tempDir;

	static boolean wasmToolsIsAvailable() {
		try {
			return new ProcessBuilder("wasm-tools", "--version").start().waitFor() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

	private String oracle(byte[] component) throws Exception {
		Path file = tempDir.resolve("component.wasm");
		Files.write(file, component);
		Process process = new ProcessBuilder("wasm-tools", "component", "wit", file.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as(output).isZero();
		return output;
	}

	@Test
	void gcComponentWitMatchesWasmToolsByteForByte() throws Exception {
		// Every export-line shape at once: scalars, :string/:s-expr, :async, :as, void.
		List<am.ik.rontolisp.LispVal> program = LispReader.readAllFromString("""
				(defun pure-add (a b) (+ a b))
				(defun scale (x f) (* x f))
				(defun evenp2 (n) (= 0 (mod n 2)))
				(defun greet (s) (concatenate 'string "hello " s))
				(defun swap (e) (list (cadr e) (car e)))
				(defun noisy-mul (a b) (print (* a b)) (* a b))
				(defun side-effect (n) n)
				(defun narrow (a b c d) (+ a b c d))
				(defun wide (a b) (+ a b))
				(rontolisp:wasm-export 'pure-add :params '(:int :int) :returns :int)
				(rontolisp:wasm-export 'scale :params '(:float :float) :returns :float)
				(rontolisp:wasm-export 'evenp2 :params '(:int) :returns :bool)
				(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
				(rontolisp:wasm-export 'swap :params '(:s-expr) :returns :s-expr)
				(rontolisp:wasm-export 'noisy-mul :params '(:int :int) :returns :int :async t)
				(rontolisp:wasm-export 'side-effect :params '(:int) :returns nil :as "drop-it")
				(rontolisp:wasm-export 'narrow :params '(:s8 :s16 :u8 :u16) :returns :u8)
				(rontolisp:wasm-export 'wide :params '(:s32 :u32) :returns :u32)
				(print "top level ran")
				""");
		WasmLispCompiler compiler = WasmLispCompiler.builder().component(true).build();
		byte[] component = compiler.compile(program);
		assertThat(compiler.componentWit()).isEqualTo(oracle(component));
		// Without --optimize nothing is cut: the import block is embedded whole, and the
		// fixed template with it -- the members nothing in this program binds included.
		WasmLispCompiler whole = WasmLispCompiler.builder().component(true).optimize(OptimizeLevel.NONE).build();
		byte[] wholeComponent = whole.compile(program);
		assertThat(whole.componentWit()).isEqualTo(oracle(wholeComponent))
			.contains("import wasi:cli/terminal-stdin@0.3.0;", "set-times-at: async func(", "get-arguments: func(",
					"wait-for: async func(");
	}

	@Test
	void anOptimizedComponentsPrunedWasiSurfaceMatchesWasmToolsByteForByte() throws Exception {
		// Under --optimize the FIXED WASI surface is narrowed to what the shaken core
		// still
		// reaches, so the emitted world names fewer interfaces than the variant's
		// template.
		// Every other case here compiles at OptimizeLevel.NONE, which would leave that
		// whole path un-oracled: an emitted world that still advertised a dropped
		// interface
		// is exactly the divergence this file exists to catch.
		WasmLispCompiler compiler = WasmLispCompiler.builder().component(true).optimize(OptimizeLevel.DEFAULT).build();
		byte[] component = compiler.compile(LispReader.readAllFromString("""
				(defun greet (s) (concatenate 'string "hello " s))
				(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
				(print "top level ran")
				"""));
		assertThat(compiler.componentWit()).isEqualTo(oracle(component));
		assertThat(compiler.componentWit()).doesNotContain("wasi:filesystem", "wasi:clocks", "wasi:random");
	}

	@Test
	void anOptimizedComponentThatUsesEveryWasiInterfaceKeepsThemAll() throws Exception {
		// The other end of the same pruning: a program reaching files, the clock, the
		// environment, stdin, random and the terminal keeps every interface -- each cut
		// to the members it binds -- and the emitted world still byte-matches the tool.
		WasmLispCompiler compiler = WasmLispCompiler.builder().component(true).optimize(OptimizeLevel.DEFAULT).build();
		List<am.ik.rontolisp.LispVal> program = LispReader.readAllFromString("""
				(with-open-file (s "x.txt" :direction :output) (format s "hi~%"))
				(print (directory "*.txt"))
				(print (get-universal-time))
				(print (random 10))
				(print (uiop:getenv "HOME"))
				(print (read-line))
				(print (interactive-stream-p *standard-input*))
				(print (%file-set-times "x.txt" t nil 0))
				""");
		program = am.ik.rontolisp.eval.StdinLibrary.process(program,
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT, false);
		// The prelude splice defines uiop:getenv over the %host-getenv primitive, which
		// is what EnvironmentLibrary binds to wasi:cli/environment (the CLI's order).
		program = am.ik.rontolisp.eval.LispPreludeLibrary.process(program, am.ik.rontolisp.reader.Features.WASM);
		program = am.ik.rontolisp.eval.EnvironmentLibrary.process(program,
				am.ik.rontolisp.compiler.WitExportDirective.Backend.WASM_COMPONENT);
		program = am.ik.rontolisp.eval.WitLibrary.process(program);
		byte[] component = compiler.compile(program);
		assertThat(compiler.componentWit()).isEqualTo(oracle(component));
		assertThat(compiler.componentWit())
			.contains("wasi:filesystem/types@0.3.0", "wasi:filesystem/preopens@0.3.0", "wasi:clocks/system-clock@0.3.0",
					"wasi:random/random@0.3.0", "wasi:cli/environment@0.3.0", "wasi:cli/stdin@0.3.0",
					"wasi:cli/terminal-stdin@0.3.0", "set-times-at: async func(", "variant new-timestamp {")
			// members of those interfaces the program does not bind
			.doesNotContain("rename-at", "get-arguments", "wait-for");
	}

	@Test
	void aUserImportOfAPackageTheFixedWorldNamesSharesItsBlock() throws Exception {
		// uiop:quit binds wasi:cli/exit as a user import, beside the fixed wasi:cli
		// interfaces: one package, so one block, its interfaces in the world's order --
		// two blocks of a package are no WIT.
		byte[] component = compileViaCli("""
				(print "bye")
				(uiop:quit 0)
				""");
		String wit = Files.readString(this.tempDir.resolve("prog.wit"));
		assertThat(wit).isEqualTo(oracle(component)).containsOnlyOnce("package wasi:cli@0.3.0 {");
	}

	@Test
	void aPrunedWorldWithNoWasiCliImportStillOrdersItsPackagesLikeWasmTools() throws Exception {
		// wasm-tools prints package definitions in the order the WORLD first names them.
		// That used to agree with the template's own order for free, because
		// wasi:cli/types
		// is always the first import. Pruning breaks the coincidence: a program that
		// reaches
		// no stdio at all keeps wasi:cli only through the fixed `export wasi:cli/run`,
		// which
		// the tool prints LAST. Both --optimize legs above print, so neither reaches this
		// shape.
		WasmLispCompiler compiler = WasmLispCompiler.builder().component(true).optimize(OptimizeLevel.DEFAULT).build();
		byte[] component = compiler.compile(LispReader.readAllFromString("""
				(defun roll () (random 100))
				(rontolisp:wasm-export 'roll :returns :int)
				"""));
		assertThat(compiler.componentWit()).isEqualTo(oracle(component));
		assertThat(compiler.componentWit()).contains("package wasi:random@0.3.0").doesNotContain("import wasi:cli/");
	}

	@Test
	void gcInterfaceExportWitMatchesWasmToolsByteForByte() throws Exception {
		// An interface export (`export docs:adder/add@0.1.0`) bundles its functions into
		// an
		// exported component instance and reconstructs into a trailing package block. The
		// whole document -- the `export <id>;` reference plus the package block -- must
		// byte-match what wasm-tools prints for that exported instance. This is what a
		// world
		// with a separated interface (`rontolisp:wit-export`, which lowers into exactly
		// these :interface directives) rides on.
		WasmLispCompiler compiler = WasmLispCompiler.builder().component(true).build();
		byte[] component = compiler.compile(LispReader.readAllFromString(
				"""
						(defun add (x y) (+ x y))
						(defun negate (x) (- x))
						(rontolisp:wasm-export 'add :params '(:int :int) :param-names '(x y) :returns :int :interface "docs:adder/add@0.1.0")
						(rontolisp:wasm-export 'negate :params '(:int) :param-names '(x) :returns :int :interface "docs:adder/add@0.1.0")
						"""));
		assertThat(compiler.componentWit()).isEqualTo(oracle(component));
	}

	@Test
	void gcMixedFlatAndInterfaceExportsMatchWasmToolsByteForByte() throws Exception {
		// A world can mix a freestanding function export with an interface export: the
		// flat
		// export stays a top-level function, the interface export becomes an instance,
		// and
		// both the export ORDER and the trailing package block must byte-match
		// wasm-tools. Two interfaces' members and a flat export of one label are three
		// exports: the core module names a member by its interface too.
		String program = """
				(defun ping (n) n)
				(defun add (x y) (+ x y))
				(defun twice (x y) (* 2 (+ x y)))
				(rontolisp:wasm-export 'ping :params '(:int) :param-names '(n) :returns :int)
				(rontolisp:wasm-export 'add :params '(:int :int) :param-names '(x y) :returns :int :interface "docs:adder/add@0.1.0")
				(rontolisp:wasm-export 'twice :as "add" :params '(:int :int) :param-names '(x y) :returns :int :interface "docs:adder/twice@0.1.0")
				(rontolisp:wasm-export 'ping :as "add" :params '(:int) :param-names '(n) :returns :int)
				""";
		WasmLispCompiler compiler = WasmLispCompiler.builder().component(true).build();
		byte[] component = compiler.compile(LispReader.readAllFromString(program));
		assertThat(compiler.componentWit()).isEqualTo(oracle(component));
		// A reactor lifts no wasi:cli/run, so its interface instance takes the index the
		// run instance and its export took: the world it emits is still the tool's.
		WasmLispCompiler reactor = WasmLispCompiler.builder().component(true).reactor(true).build();
		byte[] reactorComponent = reactor.compile(LispReader.readAllFromString(program));
		assertThat(reactor.componentWit()).isEqualTo(oracle(reactorComponent)).doesNotContain("wasi:cli/run");
		// A world whose exports carry compound types declares them: an exported
		// interface's through a shim component that names them, the world's own as type
		// imports, every type the scope defines, in the tool's order. The world emitted
		// is the tool's reading of the component, and the WIT's own definitions.
		Path shapes = Path.of("examples/wit/shapes");
		for (List<String> shape : List.of(List.of("--component"), List.of("--component", "--no-wasi"))) {
			byte[] exporting = compileFileViaCli(shapes.resolve("shapes.lisp"), "shapes", shape);
			String wit = Files.readString(this.tempDir.resolve("shapes.wit"));
			assertThat(wit).as(shape.toString())
				.isEqualTo(oracle(exporting))
				.contains("  record summary {\n    count: u32,\n    total: f64,\n  }\n\n  export summarize: "
						+ "func(areas: list<f64>) -> summary;\n  export example:shapes/geometry@0.1.0;")
				.contains("    bounds: func(ps: list<point>) -> option<tuple<point, point>>;");
		}
		// A world that names types is the world its component is: `wasm-tools component
		// targets` holds the component against the WIT. A type of another interface is
		// the using scope's own (a value type is structural: no instance is imported),
		// an alias of a flat type is declared though its export's wrapper is the flat
		// one (on the scalar backend too), and an interface of types alone is exported.
		Files.writeString(this.tempDir.resolve("uses.wit"), """
				package example:uses@0.1.0;

				interface geometry {
				  record unused { a: string }
				  record point { x: coord, y: coord }
				  type coord = s32;
				  enum color { red, green }
				  type id = u32;
				}

				interface shapes {
				  record segment { a: point, b: point, c: tint }
				  use geometry.{point, color as tint};
				  flip: func(p: point) -> point;
				  len: func(s: segment) -> s32;
				}

				interface ids {
				  use geometry.{id};
				  next: func(i: id) -> id;
				}

				world typed {
				  use geometry.{point, id};
				  record pair { a: point, b: id }
				  export geometry;
				  export shapes;
				  export ids;
				  export head: func(p: pair) -> point;
				  export inc: func(i: id) -> id;
				}

				world flat {
				  export ids;
				}
				""");
		String functions = """
				(defun flip (p) (list :x (getf p :y) :y (getf p :x)))
				(defun len (s) (+ (getf (getf s :a) :x) (getf (getf s :b) :y)))
				(defun next (i) (+ i 1))
				(defun head (p) (getf p :a))
				(defun inc (i) (+ i 1))
				""";
		for (String world : List.of("typed", "flat")) {
			Path lisp = this.tempDir.resolve(world + ".lisp");
			Files.writeString(lisp, ("flat".equals(world) ? "(defun next (i) (+ i 1))\n" : functions)
					+ "(rontolisp:wit-export \"uses.wit\" :world " + world + ")\n");
			List<List<String>> outputs = new java.util.ArrayList<>(
					List.of(List.of("--component"), List.of("--component", "--no-wasi")));
			if ("flat".equals(world)) {
				// the scalar backend's component, which carries the flat types alone
				outputs.add(List.of("--component", "--no-gc"));
			}
			for (List<String> shape : outputs) {
				byte[] exporting = compileFileViaCli(lisp, world, shape);
				String wit = Files.readString(this.tempDir.resolve(world + ".wit"));
				assertThat(wit).as(world + shape).isEqualTo(oracle(exporting));
				Process targets = new ProcessBuilder("wasm-tools", "component", "targets",
						this.tempDir.resolve("uses.wit").toString(), "--world", world,
						this.tempDir.resolve(world + ".wasm").toString())
					.redirectErrorStream(true)
					.start();
				String said = new String(targets.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				assertThat(targets.waitFor()).as(world + shape + said).isZero();
				if ("typed".equals(world)) {
					assertThat(wit).contains(
							"  record point {\n    x: s32,\n    y: s32,\n  }\n\n  type id = u32;\n\n  record pair {")
						.contains("  export head: func(p: pair) -> point;\n  export inc: func(i: id) -> id;\n"
								+ "  export example:uses/geometry@0.1.0;\n  export example:uses/shapes@0.1.0;\n"
								+ "  export example:uses/ids@0.1.0;")
						.contains("  interface ids {\n    type id = u32;\n\n    next: func(i: id) -> id;\n  }")
						.contains("    enum tint {\n      red,\n      green,\n    }");
				}
			}
		}
		// A resource of an exported interface is a type the component defines: its
		// functions are the instance's exports under the canonical ABI's names, and a
		// second interface that names it in a use clause exports that same type (its
		// shim takes it as the first instance exports it, so the tool reads a use
		// clause back, not a second resource). The interface that declares the
		// resource is exported first, whatever the world's order. The world emitted
		// is the tool's reading, and the component is the world's.
		Path example = Path.of("examples/wit/resource");
		Files.createDirectories(this.tempDir.resolve("wit"));
		Files.writeString(this.tempDir.resolve("wit/counters.wit"),
				Files.readString(example.resolve("wit/counters.wit"))
					.replace("  export registry;\n  export report;", "  export report;\n  export registry;"));
		Path reversed = this.tempDir.resolve("reversed.lisp");
		Files.copy(example.resolve("counters.lisp"), reversed);
		for (Path source : List.of(example.resolve("counters.lisp"), reversed)) {
			for (List<String> shape : List.of(List.of("--component"), List.of("--component", "--no-wasi"))) {
				byte[] counters = compileFileViaCli(source, "counters", shape);
				String emitted = Files.readString(this.tempDir.resolve("counters.wit"));
				assertThat(emitted).as(source + " " + shape)
					.isEqualTo(oracle(counters))
					.contains("  export example:counters/registry@0.1.0;\n  export example:counters/report@0.1.0;")
					.contains("    resource counter {\n      constructor(name: string, start: u32);\n"
							+ "      add: func(by: u32) -> u32;\n      read: func() -> reading;\n"
							+ "      merge: static func(a: counter, b: borrow<counter>) -> counter;\n    }")
					.contains("    leader: func(counters: list<counter>) -> result<counter, string>;")
					.contains("  interface report {\n    use registry.{counter};\n\n"
							+ "    summary: func(c: borrow<counter>) -> string;\n  }");
				if (shape.contains("--no-wasi")) {
					assertTargets(source.resolveSibling("wit/counters.wit"), "counters",
							this.tempDir.resolve("counters.wasm"));
				}
			}
		}
		// A resource of an interface the world does not export is one the component
		// imports: the interface is an instance import -- of what the program binds of
		// it and of the resource --, the type is aliased out for the exports, and the
		// world's own use clause is a type import equal to it. The world emitted is the
		// tool's reading, and a component importing nothing else is the world's. A
		// clause no function reaches imports nothing.
		byte[] reporter = compileFileViaCli(example.resolve("reporter.lisp"), "reporter", List.of("--component"));
		assertThat(Files.readString(this.tempDir.resolve("reporter.wit"))).isEqualTo(oracle(reporter))
			.contains("  import example:counters/registry@0.1.0;\n\n  export example:counters/report@0.1.0;")
			.contains("  interface report {\n    use registry.{counter};\n\n"
					+ "    summary: func(c: borrow<counter>) -> string;\n  }");
		Files.writeString(this.tempDir.resolve("wit/relaying.wit"),
				Files.readString(example.resolve("wit/counters.wit")) + RELAYING);
		Path relay = Files.writeString(this.tempDir.resolve("relay.lisp"), RELAY.formatted("peeking"));
		for (List<String> shape : List.of(List.of("--component"), List.of("--component", "--optimize"))) {
			byte[] relaying = compileFileViaCli(relay, "relay", shape);
			assertThat(Files.readString(this.tempDir.resolve("relay.wit"))).as(shape.toString())
				.isEqualTo(oracle(relaying))
				.contains("  import example:counters/registry@0.1.0;\n"
						+ "  use example:counters/registry@0.1.0.{counter as held};\n\n"
						+ "  export peek: func(c: borrow<held>) -> u32;\n  export example:counters/relay@0.1.0;")
				.contains("  interface relay {\n    use registry.{counter};\n\n    record tagged {");
		}
		assertTargets(this.tempDir.resolve("wit/relaying.wit"), "peeking", this.tempDir.resolve("relay.wasm"));
		Path untouched = Files.writeString(this.tempDir.resolve("untouched.lisp"),
				"(defun twice (x) (* 2 x))\n(rontolisp:wit-export \"wit/relaying.wit\" :world untouched)\n");
		byte[] flat = compileFileViaCli(untouched, "untouched", List.of("--component", "--no-wasi"));
		assertThat(Files.readString(this.tempDir.resolve("untouched.wit"))).isEqualTo(oracle(flat))
			.isEqualTo("package root:component;\n\nworld root {\n  export twice: func(x: u32) -> u32;\n}\n");
		assertTargets(this.tempDir.resolve("wit/relaying.wit"), "untouched", this.tempDir.resolve("untouched.wasm"));
		// The scalar backend's component defines a resource the same way, the integer
		// its constructor answers the representation; a resource whose program defines
		// no <resource>-drop has no destructor.
		Path cells = Files.writeString(this.tempDir.resolve("cells.lisp"), CELLS_PROVIDER);
		Files.writeString(this.tempDir.resolve("cells.wit"), CELLS);
		Path undropped = Files.writeString(this.tempDir.resolve("undropped.lisp"),
				CELLS_PROVIDER.replace("(defun cell-drop (c) (setq *live* (- *live* 1)))\n", ""));
		for (Path source : List.of(cells, undropped)) {
			for (List<String> shape : List.of(List.of("--component", "--no-gc"),
					List.of("--component", "--no-gc", "--no-wasi"))) {
				byte[] scalar = compileFileViaCli(source, "scalar", shape);
				assertThat(Files.readString(this.tempDir.resolve("scalar.wit"))).as(source + " " + shape)
					.isEqualTo(oracle(scalar))
					.contains("    resource cell {\n      constructor(v: u32);\n      get: func() -> u32;\n"
							+ "      plus: static func(a: cell, b: borrow<cell>) -> cell;\n    }");
				assertTargets(this.tempDir.resolve("cells.wit"), "provider", this.tempDir.resolve("scalar.wasm"));
			}
		}
	}

	// A world of a resource and the flat types alone, which the scalar backend exports.
	private static final String CELLS = """
			package example:cells@0.1.0;

			interface cells {
			  resource cell {
			    constructor(v: u32);
			    get: func() -> u32;
			    plus: static func(a: cell, b: borrow<cell>) -> cell;
			  }
			  live: func() -> u32;
			}

			interface peek {
			  use cells.{cell};
			  twice: func(c: borrow<cell>) -> u32;
			}

			world provider {
			  export cells;
			  export peek;
			}
			""";

	// CELLS implemented over integers: a cell is its value.
	private static final String CELLS_PROVIDER = """
			(defvar *live* 0)
			(defun cell-new (v) (setq *live* (+ *live* 1)) v)
			(defun cell-get (self) self)
			(defun cell-plus (a b) (+ a b))
			(defun cell-drop (c) (setq *live* (- *live* 1)))
			(defun live () *live*)
			(defun twice (c) (* 2 c))
			(rontolisp:wit-export "cells.wit" :world provider)
			""";

	// CELLS' caller: a cell lent, one handed over and one answered, and none left.
	private static final String CELLS_CALLER = """
			(rontolisp:wit-import "cells.wit" :interface "example:cells/cells" :package c)
			(rontolisp:wit-import "cells.wit" :interface "example:cells/peek" :package p)
			(let ((a (c:cell-new 3))
			      (b (c:cell-new 40)))
			  (format t "get   ~s~%" (c:cell-get a))
			  (format t "twice ~s~%" (p:twice b))
			  (format t "live  ~s~%" (c:live))
			  (let ((s (c:cell-plus a b)))
			    (format t "plus  ~s~%" (c:cell-get s))
			    (format t "live  ~s~%" (c:live))
			    (c:cell-drop s))
			  (format t "live  ~s~%" (c:live))
			  (c:cell-drop b)
			  (format t "live  ~s~%" (c:live)))
			""";

	// Worlds over the example's registry, implemented by RELAY: an exported interface
	// takes the counters another component keeps, lent and owned, alone and inside a
	// list, a record and an option, and so does a function of the world. (`wasm-tools
	// compose` 1.263.0 panics on a component whose function of the world names a
	// resource its world's use clause imports, the tool's own component too.)
	private static final String RELAYING = """

			interface relay {
			  use registry.{counter};
			  record tagged { tag: string, c: borrow<counter> }
			  bump-all: func(cs: list<borrow<counter>>, by: u32) -> u32;
			  label: func(t: tagged) -> string;
			  fresh: func(name: string) -> counter;
			  pick: func(cs: list<counter>) -> option<counter>;
			}

			world relaying {
			  export relay;
			}

			world peeking {
			  use registry.{counter as held};
			  export relay;
			  export peek: func(c: borrow<held>) -> u32;
			}

			world untouched {
			  use registry.{counter};
			  export twice: func(x: u32) -> u32;
			}
			""";

	// Where the Java API's annotations are.
	private static final String API = "rontolisp-java-api/src/main/java";

	// The program written into a directory of its own with the WIT beside it under
	// wit/: a Java program's source tree is its directory, every @WitExport class of
	// which it exports.
	private Path beside(Path wit, String file, String text) throws Exception {
		Path program = this.tempDir.resolve(file);
		Files.createDirectories(program.resolveSibling("wit"));
		Files.copy(wit, program.resolveSibling("wit").resolve(wit.getFileName()),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return Files.writeString(program, text);
	}

	// reporter.lisp in Java: the counter is an object of the binding of the
	// @WitImport interface standing for registry.
	private static final String REPORTER_JAVA = """
			import am.ik.rontolisp.wit.WitExport;
			import am.ik.rontolisp.wit.WitImport;

			@WitExport(value = "reporter", wit = "wit/counters.wit")
			public class Reporter {

			    @WitImport(value = "example:counters/registry", wit = "wit/counters.wit")
			    public interface Registry {

			        interface Counter {

			            Reading read();
			        }

			        record Reading(String name, int value) {
			        }
			    }

			    public static class Report {

			        public static String summary(Registry.Counter c) {
			            Registry.Reading reading = c.read();
			            return reading.name() + " is at " + reading.value();
			        }
			    }
			}
			""";

	// reporter.lisp in Clojure: the counter is the integer handle.
	private static final String REPORTER_CLOJURE = """
			(ns reporter
			  (:require [rontolisp.wit :as wit]))

			(wit/import "wit/counters.wit" {:interface "example:counters/registry" :as reg})

			(defn summary [c]
			  (let [{:keys [name value]} (reg/counter-read c)]
			    (str name " is at " value)))

			(wit/export "wit/counters.wit" {:world "reporter" :reactor true})
			""";

	// RELAY in Java. A counter lent to a call is closed when the call ends -- label
	// finds bump-all's kept one closed -- and its close() releases nothing: the export
	// releases the loan. One handed over is the method's, one answered the caller's.
	private static final String RELAY_JAVA = """
			import java.util.List;
			import java.util.Optional;
			import java.util.ServiceLoader;

			import am.ik.rontolisp.wit.WitExport;
			import am.ik.rontolisp.wit.WitImport;

			@WitExport(value = "relaying", wit = "wit/relaying.wit")
			public class Relaying {

			    @WitImport(value = "example:counters/registry", wit = "wit/relaying.wit")
			    public interface Registry {

			        Counter counterNew(String name, int start);

			        interface Counter extends AutoCloseable {

			            int add(int by);

			            Reading read();

			            @Override
			            void close();
			        }

			        record Reading(String name, int value) {
			        }
			    }

			    static Registry.Counter kept;

			    public static class Relay {

			        public record Tagged(String tag, Registry.Counter c) {
			        }

			        public static int bumpAll(List<Registry.Counter> cs, int by) {
			            int n = 0;
			            for (Registry.Counter c : cs) {
			                n += c.add(by);
			                kept = c;
			            }
			            return n;
			        }

			        public static String label(Tagged t) {
			            String name = t.c().read().name();
			            t.c().close();
			            try {
			                kept.read();
			                return "a loan outlived its call";
			            }
			            catch (IllegalStateException e) {
			                return name;
			            }
			        }

			        public static Registry.Counter fresh(String name) {
			            return ServiceLoader.load(Registry.class).findFirst().orElseThrow().counterNew(name, 1);
			        }

			        public static Optional<Registry.Counter> pick(List<Registry.Counter> cs) {
			            for (int i = 1; i < cs.size(); i++) {
			                cs.get(i).close();
			            }
			            return cs.isEmpty() ? Optional.empty() : Optional.of(cs.get(0));
			        }
			    }
			}
			""";

	// Loops rather than the sequence functions, so an --optimize component of it
	// imports nothing of WASI.
	private static final String RELAY = """
			(rontolisp:wit-import "wit/relaying.wit" :interface "example:counters/registry" :package reg)
			(defun bump-all (cs by)
			  (let ((n 0)) (dolist (c cs n) (setq n (+ n (reg:counter-add c by))))))
			(defun label (tagged) (getf (reg:counter-read (getf tagged :c)) :name))
			(defun fresh (name) (reg:counter-new name 1))
			(defun pick (cs) (dolist (c (rest cs)) (reg:counter-drop c)) (car cs))
			(defun peek (c) (getf (reg:counter-read c) :value))
			(rontolisp:wit-export "wit/relaying.wit" :world %s :reactor t)
			""";

	// RELAY's caller: the counters it makes, lent to the relay and handed over both
	// ways, and none left at the end.
	private static final String RELAY_CALLER = """
			(rontolisp:wit-import "wit/relaying.wit" :interface "example:counters/registry" :package reg)
			(rontolisp:wit-import "wit/relaying.wit" :interface "example:counters/relay" :package relay)
			(let ((a (reg:counter-new "a" 1))
			      (b (reg:counter-new "b" 5)))
			  (format t "bump-all ~s~%" (relay:bump-all (list a b) 10))
			  (format t "label    ~s~%" (relay:label (list :tag "t" :c b)))
			  (let ((f (relay:fresh "f")))
			    (format t "fresh    ~s~%" (reg:counter-read f))
			    (reg:counter-drop f))
			  (let ((p (relay:pick (list (reg:counter-new "x" 3) (reg:counter-new "y" 9)))))
			    (format t "pick     ~s~%" (reg:counter-read p))
			    (reg:counter-drop p))
			  (format t "pick     ~s~%" (relay:pick nil))
			  (format t "live     ~s~%" (reg:live))
			  (reg:counter-drop a)
			  (reg:counter-drop b)
			  (format t "live     ~s~%" (reg:live)))
			""";

	// Composes the root component with a provider and a middle one that imports the
	// provider's interface too -- from the same instance, which wasm-tools wires
	// through instantiations of its own, the plain compose wiring a dependency's
	// imports to nothing -- and answers what the composition prints.
	private String composedRun(String root, String provider, String providerIface, String middle, String middleIface)
			throws Exception {
		Path config = Files.writeString(this.tempDir.resolve("compose.yml"), """
				dependencies:
				  provider: %s
				  middle: %s
				instantiations:
				  root:
				    arguments:
				      %s: provider
				      %s: middle
				  middle:
				    dependency: middle
				    arguments:
				      %s: provider
				""".formatted(this.tempDir.resolve(provider + ".wasm"), this.tempDir.resolve(middle + ".wasm"),
				providerIface, middleIface, providerIface));
		Path composed = this.tempDir.resolve("composed.wasm");
		Process compose = new ProcessBuilder("wasm-tools", "compose", this.tempDir.resolve(root + ".wasm").toString(),
				"-c", config.toString(), "-o", composed.toString())
			.redirectErrorStream(true)
			.start();
		String composing = new String(compose.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(compose.waitFor()).as(composing).isZero();
		Process run = new ProcessBuilder("wasmtime", "run", composed.toString()).redirectErrorStream(true).start();
		String printed = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(run.waitFor()).as(printed).isZero();
		return printed;
	}

	private void assertTargets(Path wit, String world, Path wasm) throws Exception {
		Process targets = new ProcessBuilder("wasm-tools", "component", "targets", wit.toString(), "--world", world,
				wasm.toString())
			.redirectErrorStream(true)
			.start();
		String said = new String(targets.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(targets.waitFor()).as(world + " " + said).isZero();
	}

	@Test
	@EnabledIf("am.ik.rontolisp.testsupport.HostWasmtime#isAvailable")
	void aComponentCallsWhatAnotherExportsOverCompoundTypes() throws Exception {
		// The two directions meet: what a wit-import lowers a wit-export lifts, and
		// back. The example's command, composed with the component that exports the
		// interface, prints what it prints on the interpreter, where the exporting
		// file's functions answer in process -- whichever language implements the
		// world: the Lisp file, or the Java class beside it, whose records, sealed
		// interface and lists the same values are. An async func the command binds
		// with :blocking t waits for its answer, the Java one's after a sleep, and
		// its result is a WitResult the Java method answers.
		// A resource crosses the same way: the exporting component's handles stand for
		// the values its functions made, lent or handed over as the WIT says, alone and
		// inside a list or a result, and a second interface's function takes the first
		// one's resource -- in Java the objects of the class standing for it, in Clojure
		// any value its constructor's var answers.
		Map<Path, List<Map.Entry<String, List<String>>>> examples = new java.util.LinkedHashMap<>();
		examples.put(Path.of("examples/wit/shapes"), List.of(Map.entry("shapes.lisp", List.of("--component")),
				Map.entry("Shapes.java", List.of("--component", "--java-source-path", API))));
		examples.put(Path.of("examples/wit/resource"),
				List.of(Map.entry("counters.lisp", List.of("--component")),
						Map.entry("counters.lisp", List.of("--component", "--no-wasi")),
						Map.entry("Counters.java", List.of("--component", "--java-source-path", API)),
						Map.entry("counters.clj", List.of("--component"))));
		Map<Path, List<String>> lines = Map.of(Path.of("examples/wit/shapes"),
				List.of("bounds   ((:X 1 :Y 1) (:X 3 :Y 9))", "area     \"a dot has no area\"",
						"caption  \"a filled bold green dot called origin\"", "embolden (:DASHED :BOLD)",
						"total    15.0\ntotal    \"a dot has no area\""),
				Path.of("examples/wit/resource"),
				List.of("read     (:NAME \"apples\" :VALUE 7)", "summary  \"pears is at 10\"",
						"merged   (:NAME \"apples+pears\" :VALUE 17)", "leader   \"apples+pears is at 17\"",
						"live     1\nlive     0\nleader   \"no counter to lead\""));
		Map<Path, String> printedBy = new java.util.HashMap<>();
		for (Map.Entry<Path, List<Map.Entry<String, List<String>>>> example : examples.entrySet()) {
			Path dir = example.getKey();
			java.io.ByteArrayOutputStream interpreted = new java.io.ByteArrayOutputStream();
			new am.ik.rontolisp.cli.RontoLispCli(new java.io.ByteArrayInputStream(new byte[0]),
					new java.io.PrintStream(interpreted, true, StandardCharsets.UTF_8))
				.run(new String[] { dir.resolve("app.lisp").toString() });
			printedBy.put(dir, interpreted.toString(StandardCharsets.UTF_8));
			compileFileViaCli(dir.resolve("app.lisp"), "app", List.of("--component"));
			for (Map.Entry<String, List<String>> exporter : example.getValue()) {
				compileFileViaCli(dir.resolve(exporter.getKey()), "exporting", exporter.getValue());
				Path composed = this.tempDir.resolve("composed.wasm");
				Process compose = new ProcessBuilder("wasm-tools", "compose",
						this.tempDir.resolve("app.wasm").toString(), "-d",
						this.tempDir.resolve("exporting.wasm").toString(), "-o", composed.toString())
					.redirectErrorStream(true)
					.start();
				String composing = new String(compose.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				assertThat(compose.waitFor()).as(composing).isZero();
				Process run = new ProcessBuilder("wasmtime", "run", composed.toString()).redirectErrorStream(true)
					.start();
				String printed = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				assertThat(run.waitFor()).as(printed).isZero();
				assertThat(printed).as(exporter.getKey() + exporter.getValue())
					.isEqualTo(interpreted.toString(StandardCharsets.UTF_8))
					.contains(lines.get(dir));
			}
		}
		// A component that takes a resource it imports: the reporter answers report
		// over the counters the exporting component keeps -- the command and the
		// reporter import registry from its one instance, a dependency's import the
		// composition's own instantiations wire --, and the command prints what it
		// prints on the interpreter. A handle lent to an export is released once the
		// function has answered (wasmtime traps a task that ends holding one), alone, in
		// a list and in a record; one handed over or answered is the receiver's.
		// The Java reporter's counter is an object of the @WitImport interface standing
		// for registry, the Clojure one's the integer.
		Path resource = Path.of("examples/wit/resource");
		compileFileViaCli(resource.resolve("counters.lisp"), "counters", List.of("--component"));
		compileFileViaCli(resource.resolve("app.lisp"), "app", List.of("--component"));
		String registry = "example:counters/registry@0.1.0";
		Map<Path, List<String>> reporters = new java.util.LinkedHashMap<>();
		reporters.put(resource.resolve("reporter.lisp"), List.of("--component"));
		reporters.put(beside(resource.resolve("wit/counters.wit"), "reporter-java/Reporter.java", REPORTER_JAVA),
				List.of("--component", "--java-source-path", API));
		reporters.put(beside(resource.resolve("wit/counters.wit"), "reporter-clj/reporter.clj", REPORTER_CLOJURE),
				List.of("--component"));
		for (Map.Entry<Path, List<String>> reporter : reporters.entrySet()) {
			compileFileViaCli(reporter.getKey(), "reporter", reporter.getValue());
			assertThat(composedRun("app", "counters", registry, "reporter", "example:counters/report@0.1.0"))
				.as(reporter.getKey().toString())
				.isEqualTo(printedBy.get(resource));
		}
		Files.createDirectories(this.tempDir.resolve("wit"));
		Files.writeString(this.tempDir.resolve("wit/relaying.wit"),
				Files.readString(resource.resolve("wit/counters.wit")) + RELAYING);
		compileFileViaCli(Files.writeString(this.tempDir.resolve("caller.lisp"), RELAY_CALLER), "caller",
				List.of("--component"));
		Map<Path, List<String>> relays = new java.util.LinkedHashMap<>();
		relays.put(Files.writeString(this.tempDir.resolve("relay.lisp"), RELAY.formatted("relaying")),
				List.of("--component"));
		relays.put(beside(this.tempDir.resolve("wit/relaying.wit"), "relay-java/Relaying.java", RELAY_JAVA),
				List.of("--component", "--java-source-path", API));
		for (Map.Entry<Path, List<String>> relay : relays.entrySet()) {
			compileFileViaCli(relay.getKey(), "relay", relay.getValue());
			assertThat(composedRun("caller", "counters", registry, "relay", "example:counters/relay@0.1.0").lines())
				.as(relay.getKey().toString())
				.containsExactly("bump-all 26", "label    \"b\"", "fresh    (:NAME \"f\" :VALUE 1)",
						"pick     (:NAME \"x\" :VALUE 3)", "pick     NIL", "live     2", "live     0");
		}
		// The scalar backend's resource, called by the default backend's command: what
		// plus takes over is not released, what it answers is a new handle.
		Files.writeString(this.tempDir.resolve("cells.wit"), CELLS);
		compileFileViaCli(Files.writeString(this.tempDir.resolve("cells.lisp"), CELLS_PROVIDER), "scalar",
				List.of("--component", "--no-gc"));
		compileFileViaCli(Files.writeString(this.tempDir.resolve("cells-caller.lisp"), CELLS_CALLER), "cells-caller",
				List.of("--component"));
		Path cellsComposed = this.tempDir.resolve("cells-composed.wasm");
		Process composeCells = new ProcessBuilder("wasm-tools", "compose",
				this.tempDir.resolve("cells-caller.wasm").toString(), "-d",
				this.tempDir.resolve("scalar.wasm").toString(), "-o", cellsComposed.toString())
			.redirectErrorStream(true)
			.start();
		String composingCells = new String(composeCells.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(composeCells.waitFor()).as(composingCells).isZero();
		Process runCells = new ProcessBuilder("wasmtime", "run", cellsComposed.toString()).redirectErrorStream(true)
			.start();
		String printedCells = new String(runCells.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(runCells.waitFor()).as(printedCells).isZero();
		assertThat(printedCells.lines()).containsExactly("get   3", "twice 80", "live  2", "plus  43", "live  2",
				"live  1", "live  0");
		// A call made while another waits: an async func's call is a task that waits
		// inside the component, and the host runs a plain func of the same instance
		// meanwhile. What the waiting call holds in memory -- here the place its own
		// async call answers into -- stays: the call made meanwhile releases what it
		// staged and no more. The waiter is the wasm-GC backend's, a record crossing,
		// and the scalar backend's, a string, whose call waits in a :blocking t import.
		Files.writeString(this.tempDir.resolve("waiting.wit"), """
				package example:waiting@0.1.0;

				interface host {
				  slow-echo: async func(s: string, ms: u32) -> string;
				}

				interface api {
				  record note { text: string }
				  later: async func(n: note, ms: u32) -> note;
				  size: func(n: note) -> u32;
				}

				interface plain {
				  later: async func(s: string, ms: u32) -> string;
				  size: func(s: string) -> u32;
				}

				world provider {
				  export host;
				}

				world waiter {
				  import host;
				  export api;
				}

				world plain-waiter {
				  import host;
				  export plain;
				}
				""");
		String repeated = """
				(let ((out (make-string-output-stream)))
				  (dotimes (i 400) (format out "~a~4,'0d|" s i))
				  (get-output-stream-string out))
				""";
		Path provider = Files.writeString(this.tempDir.resolve("provider.lisp"), """
				(defun slow-echo (s ms) (sleep (/ ms 1000.0)) %s)
				(rontolisp:wit-export "waiting.wit" :world provider)
				""".formatted(repeated));
		compileFileViaCli(provider, "provider", List.of("--component"));
		Map<String, List<String>> waiters = new java.util.LinkedHashMap<>();
		waiters.put("""
				(rontolisp:wit-import "waiting.wit" :interface "example:waiting/host@0.1.0" :package host)
				(rontolisp:async-defun echoed (n ms)
				  (list :text (rontolisp:await (host:slow-echo (getf n :text) ms))))
				(defun later (n ms) (echoed n ms))
				(defun size (n) (length (getf n :text)))
				(rontolisp:wit-export "waiting.wit" :world waiter)
				""", List.of("api", "'(:text \"ab\")", "'(:text \"0123456789\")",
				"(getf (rontolisp:await pending) :text)", "--component"));
		waiters.put("""
				(rontolisp:wit-import "waiting.wit" :interface "example:waiting/host@0.1.0" :package host
				  :blocking t)
				(defun later (s ms) (host:slow-echo s ms))
				(defun size (s) (length s))
				(rontolisp:wit-export "waiting.wit" :world plain-waiter)
				""",
				List.of("plain", "\"ab\"", "\"0123456789\"", "(rontolisp:await pending)", "--component", "--no-gc"));
		for (Map.Entry<String, List<String>> waiting : waiters.entrySet()) {
			List<String> call = waiting.getValue();
			Path waiter = Files.writeString(this.tempDir.resolve("waiter.lisp"), waiting.getKey());
			Path caller = Files.writeString(this.tempDir.resolve("caller.lisp"), """
					(rontolisp:wit-import "waiting.wit" :interface "example:waiting/%1$s@0.1.0" :package api)
					(defun repeated (s) %5$s)
					(let ((pending (api:later %2$s 100)))
					  (print (api:size %3$s))
					  (print (mismatch %4$s (repeated "ab"))))
					""".formatted(call.get(0), call.get(1), call.get(2), call.get(3), repeated));
			compileFileViaCli(waiter, "waiter", call.subList(4, call.size()));
			compileFileViaCli(caller, "caller", List.of("--component"));
			// the tool wires a component's own imports, so the inner pair is composed
			// first
			for (List<String> step : List.of(List.of("waiter", "provider", "waiting"),
					List.of("caller", "waiting", "called"))) {
				Process compose = new ProcessBuilder("wasm-tools", "compose",
						this.tempDir.resolve(step.get(0) + ".wasm").toString(), "-d",
						this.tempDir.resolve(step.get(1) + ".wasm").toString(), "-o",
						this.tempDir.resolve(step.get(2) + ".wasm").toString())
					.redirectErrorStream(true)
					.start();
				String composing = new String(compose.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				assertThat(compose.waitFor()).as(composing).isZero();
			}
			Process run = new ProcessBuilder("wasmtime", "run", this.tempDir.resolve("called.wasm").toString())
				.redirectErrorStream(true)
				.start();
			String printed = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(run.waitFor()).as(call + printed).isZero();
			assertThat(printed.lines().map(String::strip).filter(line -> !line.isEmpty())).as(call.toString())
				.containsExactly("10", "NIL");
		}
	}

	@Test
	void aUserImportsWorldDescribesTheTypeTheComponentCarries() throws Exception {
		// rontolisp:fetch, rontolisp:tcp-* and rontolisp:tls-* are libraries over
		// canon-lowered USER imports of wasi:http, wasi:sockets and wasi:tls, so their
		// world is printed by the user-import emitter, which deliberately keeps type
		// aliases (`type headers = fields`) and fully-qualified `use` clauses where the
		// tool expands, abbreviates or inlines them. So the pin is the type, not the
		// text: every interface
		// declares the same functions with the same expanded types as the tool reads
		// out of the bytes -- the async-lowered ones (`send`, `connect`,
		// `resolve-addresses`) included, and every name the text uses defined. The last
		// program imports `ops` alone: the `point` it uses is defined in place, as the
		// component declares it, not `use`d from an interface the world lacks.
		Files.writeString(this.tempDir.resolve("pair.wit"), """
				package docs:pair@0.1.0;

				interface shapes {
				    record point { x: s32, y: s32 }
				}

				interface ops {
				    use shapes.{point};
				    norm: func(p: point) -> s32;
				}
				""");
		for (String program : List.of("(print (rontolisp:fetch \"http://127.0.0.1:9/\"))",
				"(close (rontolisp:tls-connect \"example.com\" 443))", """
						(rontolisp:wit-import "pair.wit" :interface "docs:pair/ops@0.1.0" :package ops)
						(print (ops:norm '(:x 1 :y 2)))
						""")) {
			byte[] component = compileViaCli(program);
			String wit = Files.readString(this.tempDir.resolve("prog.wit"));
			assertThat(surface(wit)).as(program).isEqualTo(surface(oracle(component)));
			if (program.contains("tls")) {
				// a resource no bound function is a member of prints as the tool does
				assertThat(wit).contains("    resource error;\n");
			}
		}
	}

	// Each interface's functions -> their signatures with every type expanded to its
	// structure (a resource by its defining interface and name). Throws on a name the
	// document does not define.
	private static Map<String, Map<String, String>> surface(String wit) {
		WitResolver resolver = new WitResolver(WitParser.parse(wit));
		Map<String, Map<String, String>> surface = new TreeMap<>();
		for (String id : resolver.interfaceIds()) {
			WitItem.InterfaceDef iface = Objects.requireNonNull(resolver.findInterface(id));
			WitCanonicalAbi abi = new WitCanonicalAbi(resolver, iface);
			Map<String, String> funcs = new TreeMap<>();
			for (WitResolver.Func func : WitResolver.functions(iface)) {
				StringBuilder sig = new StringBuilder(func.def().func().async() ? "async (" : "(");
				for (var param : func.def().func().params()) {
					sig.append(param.name()).append(": ").append(expand(abi, param.type())).append(", ");
				}
				sig.append(") -> ").append(expand(abi, abi.resultType(func)));
				funcs.put(func.def().kind() + " " + func.resource() + "." + func.def().name(), sig.toString());
			}
			surface.put(id, funcs);
		}
		return surface;
	}

	private static String expand(WitCanonicalAbi abi, @Nullable WitType type) {
		return switch (type) {
			case null -> "_";
			case WitType.Prim prim -> prim.name();
			case WitType.ListOf list -> "list<" + expand(abi, list.element()) + ">";
			case WitType.OptionOf opt -> "option<" + expand(abi, opt.element()) + ">";
			case WitType.ResultOf res -> "result<" + expand(abi, res.ok()) + ", " + expand(abi, res.err()) + ">";
			case WitType.TupleOf tuple ->
				"tuple<" + String.join(", ", tuple.elements().stream().map(e -> expand(abi, e)).toList()) + ">";
			case WitType.StreamOf stream -> "stream<" + expand(abi, stream.element()) + ">";
			case WitType.FutureOf future -> "future<" + expand(abi, future.element()) + ">";
			case WitType.BorrowOf borrow -> "borrow<" + resource(abi, borrow.resource()) + ">";
			case WitType.OwnOf own -> "own<" + resource(abi, own.resource()) + ">";
			case WitType.Named named -> {
				WitResolver.Owned owned = abi.resolveOwned(named);
				WitCanonicalAbi in = abi.scopedTo(owned.owner());
				yield switch (owned.item()) {
					case WitItem.TypeAlias alias -> expand(in, alias.target());
					case WitItem.ResourceDef ignored -> "own<" + resource(abi, named.name()) + ">";
					case WitItem.RecordDef record -> "record{"
							+ String.join(", ",
									record.fields().stream().map(f -> f.name() + ": " + expand(in, f.type())).toList())
							+ "}";
					case WitItem.VariantDef variant -> "variant{" + String.join(", ",
							variant.cases().stream().map(c -> c.name() + "(" + expand(in, c.payload()) + ")").toList())
							+ "}";
					case WitItem.EnumDef en ->
						"enum{" + String.join(", ", en.cases().stream().map(WitItem.Case::name).toList()) + "}";
					case WitItem.FlagsDef flags ->
						"flags{" + String.join(", ", flags.cases().stream().map(WitItem.Case::name).toList()) + "}";
					default -> throw new IllegalStateException("not a type: " + owned.item());
				};
			}
		};
	}

	private static String resource(WitCanonicalAbi abi, String name) {
		WitResolver.Owned owned = abi.resolveOwned(new WitType.Named(name));
		return Objects.requireNonNull(abi.resolver().canonicalId(owned.owner())) + "#"
				+ WitComponentTypeEncoder.definedNameOf(owned.item());
	}

	@Test
	void noGcComponentWitsMatchWasmToolsByteForByte() throws Exception {
		NoGcWasmCompiler plain = NoGcWasmCompiler.builder().optimize(OptimizeLevel.NONE).component(true).build();
		byte[] plainComponent = plain.compile(LispReader.readAllFromString("""
				(defun big-add (a b) (+ a b))
				(defun shout (s) (concatenate 'string s "!"))
				(defun huge (a b) (+ a b))
				(rontolisp:wasm-export 'big-add :params '(:long :long) :returns :long)
				(rontolisp:wasm-export 'shout :params '(:string) :returns :string)
				(rontolisp:wasm-export 'huge :params '(:u64 :u64) :returns :u64)
				"""));
		assertThat(plain.componentWit()).isEqualTo(oracle(plainComponent));
		NoGcWasmCompiler print = NoGcWasmCompiler.builder().optimize(OptimizeLevel.NONE).component(true).build();
		byte[] printComponent = print.compile(LispReader.readAllFromString("""
				(defun hello () (print "hi"))
				(rontolisp:wasm-export 'hello)
				"""));
		assertThat(print.componentWit()).isEqualTo(oracle(printComponent));
	}

	@Test
	void noGcComponentImportWitsMatchWasmToolsByteForByte() throws Exception {
		// Both import-name shapes the wrap emits: an interface id prints as
		// `import docs:host/env@0.1.0;` plus its package block, a plain label as the
		// inline `import env: interface { ... }` -- and each function under its
		// :param-names (the labels of the instance type). The lists are what the core
		// REACHES: the uncalled `spare` is not in the type, so it must not be in the WIT.
		NoGcWasmCompiler byId = NoGcWasmCompiler.builder().optimize(OptimizeLevel.NONE).component(true).build();
		byte[] idComponent = byId.compile(LispReader.readAllFromString(
				"""
						(rontolisp:wasm-import 'add :from "docs:host/env@0.1.0" :as "add" :params '(:s32 :s32) :param-names '(a b) :returns :s32)
						(rontolisp:wasm-import 'greet :from "docs:host/env@0.1.0" :as "greet" :params '(:string) :param-names '(name) :returns :string)
						(rontolisp:wasm-import 'spare :from "docs:host/env@0.1.0" :as "spare" :params '() :returns :void)
						(defun run (n) (length (greet (if (> (add n n) 10) "big" "small"))))
						(rontolisp:wasm-export 'run :params '(:s32) :returns :s32)
						"""));
		assertThat(byId.componentWit()).isEqualTo(oracle(idComponent));
		NoGcWasmCompiler byLabel = NoGcWasmCompiler.builder().optimize(OptimizeLevel.NONE).component(true).build();
		byte[] labelComponent = byLabel.compile(LispReader.readAllFromString("""
				(rontolisp:wasm-import 'host-log :from "env" :as "host-log" :params '(:string) :returns :void)
				(rontolisp:wasm-import 'measure :from "env" :as "measure" :params '(:s32) :returns :s64 :blocking t)
				(defun run (n) (host-log "hello") (measure n))
				(defun size (n) (* n 2))
				(rontolisp:wasm-export 'run :params '(:s32) :returns :s64 :async t)
				(rontolisp:wasm-export 'size :params '(:s32) :returns :s32)
				"""));
		// an import the call waits for and the export whose task waits are `async func`s
		assertThat(byLabel.componentWit()).isEqualTo(oracle(labelComponent))
			.contains("measure: async func(p0: s32) -> s64;", "export run: async func(p0: s32) -> s64;",
					"export size: func(p0: s32) -> s32;");
		// The print micro-adapter's fixed stdout import precedes the user import.
		NoGcWasmCompiler print = NoGcWasmCompiler.builder().optimize(OptimizeLevel.NONE).component(true).build();
		byte[] printComponent = print.compile(LispReader.readAllFromString("""
				(rontolisp:wasm-import 'measure :from "env" :as "measure" :params '(:s32) :returns :s64)
				(defun run (n) (print n) (measure n))
				(rontolisp:wasm-export 'run :params '(:s32) :returns :s64)
				"""));
		assertThat(print.componentWit()).isEqualTo(oracle(printComponent));
	}

	@Test
	void noGcInterfaceExportWitMatchesWasmToolsByteForByte() throws Exception {
		// The adapter-free reactor carries an interface export too: its exported instance
		// starts at component instance 0 (no run instance, no imports), and the emitted
		// WIT
		// -- `export docs:calc/ops@1.0.0;` plus its package block -- must byte-match
		// wasm-tools.
		// A second interface's member of the label is a second export.
		NoGcWasmCompiler compiler = NoGcWasmCompiler.builder().optimize(OptimizeLevel.NONE).component(true).build();
		byte[] component = compiler.compile(LispReader.readAllFromString(
				"""
						(defun add (x y) (+ x y))
						(defun twice (x y) (* 2 (+ x y)))
						(rontolisp:wasm-export 'add :params '(:long :long) :param-names '(x y) :returns :long :interface "docs:calc/ops@1.0.0")
						(rontolisp:wasm-export 'twice :as "add" :params '(:long :long) :param-names '(x y) :returns :long :interface "docs:calc/doubled@1.0.0")
						"""));
		assertThat(compiler.componentWit()).isEqualTo(oracle(component));
	}

	@Test
	void serveComponentWitMatchesWasmToolsModuloTheRestoredUseClause() throws Exception {
		// wasm-tools drops the handler interface's `use types.{...};` and prints a WIT
		// that does not parse; the template restores it, so the emitted text is the
		// oracle plus exactly that clause -- and, unlike the oracle, round-trips the
		// parser.
		byte[] component = compileViaCli("""
				(defun h (env) (list 200 nil (list "x")))
				(rontolisp:http-handler 'h)
				""");
		String restored = "  interface handler {\n    use types.{request, response, error-code};\n\n";
		String wit = Files.readString(this.tempDir.resolve("prog.wit"));
		assertThat(wit).contains(restored);
		// Strip ONLY the restored handler clause -- the client interface's own use
		// clause is genuine and the oracle prints it too.
		assertThat(wit.replace(restored, "  interface handler {\n")).isEqualTo(oracle(component));
	}

	@Test
	void aServedComponentsUserImportIsInItsRealTypeToo() throws Exception {
		// The import side is what --emit-wit uniquely reports (the export side is a
		// fixpoint), so the oracle is the only proof that the WIT text a SERVED component
		// records is the type the component really carries -- the fixed wasi:http surface
		// plus the interface the handler calls.
		Files.writeString(tempDir.resolve("kv.wit"), """
				package wasi:keyvalue@0.2.0-draft;

				interface store {
				    variant error {
				        no-such-store,
				        access-denied,
				        other(string)
				    }
				    open: func(identifier: string) -> result<bucket, error>;
				    resource bucket {
				        get: func(key: string) -> result<option<list<u8>>, error>;
				    }
				}
				""");
		byte[] component = compileViaCli("""
				(rontolisp:wit-import "kv.wit" :interface "wasi:keyvalue/store@0.2.0-draft" :package kv)
				(defun handle (request)
				  (list 200 nil (list (kv:bucket-get (kv:open "") (getf request :path-info)))))
				(rontolisp:http-handler 'handle)
				""");
		String restored = "  interface handler {\n    use types.{request, response, error-code};\n\n";
		String wit = Files.readString(this.tempDir.resolve("prog.wit"));
		assertThat(wit).contains("  import wasi:keyvalue/store@0.2.0-draft;");
		assertThat(wit.replace(restored, "  interface handler {\n")).isEqualTo(oracle(component));
	}

	// Compiles a file of the checkout through the real CLI into the temporary directory,
	// its world beside it (--emit-wit).
	private byte[] compileFileViaCli(Path source, String name, List<String> shape) throws Exception {
		Path wasm = this.tempDir.resolve(name + ".wasm");
		List<String> args = new java.util.ArrayList<>(List.of(source.toString(), "-o", wasm.toString(), "--emit-wit"));
		args.addAll(shape);
		new am.ik.rontolisp.cli.RontoLispCli(new java.io.ByteArrayInputStream(new byte[0]),
				new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
			.run(args.toArray(new String[0]));
		return Files.readAllBytes(wasm);
	}

	// Compiles source to a --component component through the REAL CLI (writing
	// prog.wit via --emit-wit alongside prog.wasm), so the emitted WIT and the bytes are
	// exactly what a user gets -- the pruned, full-pipeline component the
	// WasiWitDefinitions
	// fixtures are generated from. serve.lisp's HTTP glue is spliced by the CLI's
	// ServeLibrary step; a bare in-process pipeline would skip LibraryDefunPruner and
	// leave
	// redundant type declarations wasm-tools then prints twice.
	private byte[] compileViaCli(String source) throws Exception {
		Path lisp = this.tempDir.resolve("prog.lisp");
		Files.writeString(lisp, source);
		Path wasm = this.tempDir.resolve("prog.wasm");
		new am.ik.rontolisp.cli.RontoLispCli(new java.io.ByteArrayInputStream(new byte[0]),
				new java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
			.run(new String[] { lisp.toString(), "-o", wasm.toString(), "--component", "--emit-wit" });
		return Files.readAllBytes(wasm);
	}

}
