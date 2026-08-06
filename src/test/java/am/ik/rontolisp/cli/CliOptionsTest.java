package am.ik.rontolisp.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CliOptions} argument parsing. The value-less flags (e.g. {@code --simd},
 * {@code --optimize}) must be recognized even as the trailing argument: a flag that is
 * not registered as value-less is parsed as expecting a value and, when it is the last
 * argument, is dropped entirely -- silently disabling the feature (the {@code --simd}
 * bug).
 */
class CliOptionsTest {

	@Test
	void trailingValueLessFlagsAreRecognized() {
		// The reproduction of the --simd bug: --simd trailing after `-o Out.class` must
		// be
		// contains-true. Before --simd was registered as value-less it consumed no value
		// and was dropped, so `contains("--simd")` returned false and the JVM
		// acceleration
		// was silently never enabled.
		CliOptions options = CliOptions.build(new String[] { "prog.lisp", "-o", "Out.class", "--simd" });
		assertThat(options.contains("--simd")).isTrue();
		assertThat(options.getNokey()).isEqualTo("prog.lisp");
		assertThat(options.get("-o")).isEqualTo("Out.class");
	}

	@Test
	void everyValueLessFlagIsRecognizedTrailing() {
		for (String flag : new String[] { "--simd", "--optimize", "--no-gc", "--component", "--no-wasi", "--dynamic",
				"--buffered-output", "--emit-wit" }) {
			CliOptions options = CliOptions.build(new String[] { "prog.lisp", "-o", "Out.class", flag });
			assertThat(options.contains(flag)).as(flag).isTrue();
			assertThat(options.get("-o")).as(flag).isEqualTo("Out.class");
		}
	}

	@Test
	void aValueLessFlagDoesNotSwallowTheFollowingArgument() {
		// --simd must not consume the next token as its value.
		CliOptions options = CliOptions.build(new String[] { "prog.lisp", "--simd", "-o", "Out.class" });
		assertThat(options.contains("--simd")).isTrue();
		assertThat(options.get("-o")).isEqualTo("Out.class");
	}

	@Test
	void aGluedValueIsReadWithoutMakingTheFlagConsumeAnArgument() {
		// --optimize=size gives a value-less flag a value without moving it out of
		// noValueKeys -- which it cannot leave: the space form would then read the
		// following -o as the level.
		CliOptions options = CliOptions.build(new String[] { "prog.lisp", "--optimize=size", "-o", "out.wasm" });
		assertThat(options.contains("--optimize")).isTrue();
		assertThat(options.get("--optimize")).isEqualTo("size");
		assertThat(options.get("-o")).isEqualTo("out.wasm");
		assertThat(options.getNokey()).isEqualTo("prog.lisp");
	}

	@Test
	void theBareFlagKeepsItsEmptyValue() {
		CliOptions options = CliOptions.build(new String[] { "prog.lisp", "--optimize", "-o", "out.wasm" });
		assertThat(options.contains("--optimize")).isTrue();
		assertThat(options.get("--optimize")).isEmpty();
		assertThat(options.get("-o")).isEqualTo("out.wasm");
	}

	@Test
	void onlyTheFirstEqualsSignSplitsAGluedValue() {
		CliOptions options = CliOptions.build(new String[] { "prog.lisp", "--world=ns:pkg/world=v1" });
		assertThat(options.get("--world")).isEqualTo("ns:pkg/world=v1");
	}

	@Test
	void theSpaceFormOfAValuedOptionIsRejectedRatherThanSwallowingTheInputFile() {
		// --optimize stays a no-value key, so its "value" would land as a second
		// positional argument and used to REPLACE prog.lisp: the compiler then looked
		// for a file named "size". Nothing takes a second positional, so say so.
		assertThatThrownBy(() -> CliOptions.build(new String[] { "prog.lisp", "--optimize", "size", "-o", "out.wasm" }))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("unexpected extra argument 'size'")
			.hasMessageContaining("--optimize=size");
	}

	@Test
	void aValueGivenAsTheFollowingArgumentKeepsItsEqualsSigns() {
		// The glued form must not reach into a value the space form supplies.
		CliOptions options = CliOptions.build(new String[] { "prog.lisp", "--system-path", "/a=b:/c" });
		assertThat(options.get("--system-path")).isEqualTo("/a=b:/c");
		assertThat(options.getNokey()).isEqualTo("prog.lisp");
	}

}
