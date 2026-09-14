/*
 * Copyright (C) 2025 Toshiaki Maki <makingx@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package am.ik.rontolisp.codegen.wasm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.testsupport.HostWasmtime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks of host imports on {@code --no-gc --component}, driven WITHOUT a
 * hand-written host: the consumer component's imports are satisfied by a second rontolisp
 * component that {@code wit-export}s the same interface, the two are composed with
 * {@code wasm-tools compose}, and wasmtime runs the result. Nothing smaller can do it --
 * {@code wasmtime --invoke} cannot host a component that imports a user interface -- and
 * the composition is the real test: the provider's lifted {@code :string} exports and the
 * consumer's lowered imports have to agree through the canonical ABI byte for byte, in
 * both directions, with the instance types matching down to the parameter names.
 *
 * <p>
 * What is pinned:
 * <ul>
 * <li>a scalar import, a {@code :string} argument and a {@code :string} result all cross
 * between the two components, on both optimize levels;</li>
 * <li>the {@code rontolisp:wit-import} lowering (the WIT's names) type-checks against a
 * provider built from the same {@code .wit};</li>
 * <li>a PRINTING consumer composes too: the print micro-adapter's shim and the import
 * shim are two tables the core instantiates against, and its async-lifted export still
 * answers.</li>
 * </ul>
 */
@EnabledIf("am.ik.rontolisp.codegen.wasm.NoGcWasmComponentImportE2eTest#toolsAreAvailable")
class NoGcWasmComponentImportE2eTest {

	static boolean toolsAreAvailable() {
		try {
			return HostWasmtime.isAvailable() && new ProcessBuilder("wasm-tools", "--version").start().waitFor() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

	@TempDir
	Path tempDir;

	@BeforeEach
	void writeWit() throws Exception {
		Files.writeString(this.tempDir.resolve("bench.wit"), WIT, StandardCharsets.UTF_8);
	}

	private static final String WIT = """
			package docs:bench;

			interface env {
			  add: func(a: s32, b: s32) -> s32;
			  greet: func(name: string) -> string;
			  shout: func(s: string);
			}

			world provider {
			  export env;
			}

			world consumer {
			  import env;
			  export run: func(n: s32) -> s32;
			}
			""";

	// The provider: the interface implemented as ordinary defuns, lifted as the
	// exported instance `docs:bench/env` (shout counts its argument so it is not
	// shaken away).
	private static final String PROVIDER = """
			(rontolisp:wit-export "bench.wit" :world provider)
			(defun add (a b) (+ a b))
			(defun greet (name) (concatenate 'string "hello " name))
			(defun shout (s) (length s))
			""";

	// The consumer, bound from the same WIT: run(n) = (length (greet ...)), so the
	// answer is 9 ("hello big") or 11 ("hello small") only if the scalar, the string
	// argument and the string result all crossed.
	private static final String CONSUMER = """
			(rontolisp:wit-import "bench.wit" :interface "docs:bench/env" :package host)
			(defun run (n) (host:shout "hey") (length (host:greet (if (> (host:add n n) 10) "big" "small"))))
			(rontolisp:wasm-export 'run :params '(:s32) :returns :s32)
			""";

	@Test
	void scalarsAndStringsCrossBothWaysBetweenTwoComposedComponents() throws Exception {
		for (OptimizeLevel level : List.of(OptimizeLevel.NONE, OptimizeLevel.SIZE)) {
			Path composed = compose(CONSUMER, level, "consumer-" + level.spelling());
			assertThat(invoke(composed, "run(6)")).as("optimize=%s", level.spelling()).isEqualTo("9");
			assertThat(invoke(composed, "run(3)")).as("optimize=%s", level.spelling()).isEqualTo("11");
		}
	}

	@Test
	void theWitImportLoweringIsTheHandWrittenBlock() throws Exception {
		// The canonical import names the lowering writes (the interface id as the
		// module, the WIT label as the field, the WIT parameter names) are exactly what
		// a hand-written directive spells, so the two programs are the SAME bytes -- and
		// the hand-written one composes against the provider just the same.
		String handWritten = """
				(defpackage host (:use cl) (:export add greet shout))
				(rontolisp:wasm-import 'host:add :from "docs:bench/env" :as "add" :params '(:s32 :s32) :param-names '("a" "b") :returns :s32)
				(rontolisp:wasm-import 'host:greet :from "docs:bench/env" :as "greet" :params '(:string) :param-names '("name") :returns :string)
				(rontolisp:wasm-import 'host:shout :from "docs:bench/env" :as "shout" :params '(:string) :param-names '("s") :returns :void)
				(defun run (n) (host:shout "hey") (length (host:greet (if (> (host:add n n) 10) "big" "small"))))
				(rontolisp:wasm-export 'run :params '(:s32) :returns :s32)
				""";
		byte[] fromWit = compileComponent(CompileFrontendAccess.noGcComponent(CONSUMER, this.tempDir.toString()),
				OptimizeLevel.SIZE);
		byte[] byHand = compileComponent(CompileFrontendAccess.noGcComponent(handWritten, this.tempDir.toString()),
				OptimizeLevel.SIZE);
		assertThat(fromWit).isEqualTo(byHand);
		Files.write(this.tempDir.resolve("hand.wasm"), byHand);
		Path composed = composeFiles("hand.wasm", "hand-composed.wasm");
		assertThat(invoke(composed, "run(6)")).isEqualTo("9");
	}

	@Test
	void aPrintingConsumerComposesThroughBothShims() throws Exception {
		// print makes every export an async lift over the print micro-adapter, whose
		// fd_write shim sits beside the generated import shim: two tables, two fixups,
		// one core. The output carries the printed string, then the invoked result.
		String printing = """
				(rontolisp:wit-import "bench.wit" :interface "docs:bench/env" :package host)
				(defun run (n) (let ((g (host:greet "there"))) (print g) (+ (host:add n n) (length g))))
				(rontolisp:wasm-export 'run :params '(:s32) :returns :s32)
				""";
		Path composed = compose(printing, OptimizeLevel.NONE, "printing");
		assertThat(invoke(composed, "run(5)").lines().toList()).containsExactly("\"hello there\"", "21");
	}

	private Path compose(String consumer, OptimizeLevel level, String name) throws Exception {
		Files.write(this.tempDir.resolve(name + ".wasm"),
				compileComponent(CompileFrontendAccess.noGcComponent(consumer, this.tempDir.toString()), level));
		return composeFiles(name + ".wasm", name + "-composed.wasm");
	}

	private Path composeFiles(String consumerFile, String composedFile) throws Exception {
		Path provider = this.tempDir.resolve("provider.wasm");
		if (!Files.exists(provider)) {
			Files.write(provider, compileComponent(
					CompileFrontendAccess.noGcComponent(PROVIDER, this.tempDir.toString()), OptimizeLevel.SIZE));
		}
		Path composed = this.tempDir.resolve(composedFile);
		// `wasm-tools compose` is deprecated in favour of wac but still ships; it prints
		// the deprecation on stderr and exits zero.
		run("wasm-tools", "compose", this.tempDir.resolve(consumerFile).toString(), "-d", provider.toString(), "-o",
				composed.toString());
		return composed;
	}

	private static String invoke(Path composed, String invocation) throws Exception {
		return run("wasmtime", "run", "--invoke", invocation, composed.toString());
	}

	private static byte[] compileComponent(List<LispVal> program, OptimizeLevel level) {
		return NoGcWasmCompiler.builder().optimize(level).component(true).build().compile(program);
	}

	private static String run(String... command) throws Exception {
		Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int exit = process.waitFor();
		assertThat(exit).as("%s exited %d:%n%s", String.join(" ", command), exit, output).isZero();
		return output.trim();
	}

}
