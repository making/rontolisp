package am.ik.rontolisp.eval;

import java.util.Arrays;
import java.util.Objects;

import am.ik.rontolisp.clojure.ClojureBoundary;
import am.ik.rontolisp.compiler.WitTypeMapper;
import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The host boundary the Clojure lowering reads while a program lowers
 * ({@link ClojureHostBoundary}): the compiler's own vocabulary and WIT descriptions,
 * handed over in the Clojure package's terms.
 */
class ClojureHostBoundaryTest {

	/**
	 * The Clojure side spells the compiler's house representations member for member, and
	 * the boundary maps one onto the other by name: a representation added to the
	 * compiler and not here would fail the mapping at the first WIT that names it.
	 */
	@Test
	void theRepresentationsAreTheCompilersMemberForMember() {
		assertThat(Arrays.stream(ClojureBoundary.Rep.values()).map(Enum::name))
			.containsExactlyElementsOf(Arrays.stream(WitTypeMapper.Rep.values()).map(Enum::name).toList());
	}

	@Test
	void theDesignatorsAreTheWasmBoundarysWithItsAliases() {
		assertThat(ClojureHostBoundary.INSTANCE.designators())
			.contains(":S32", ":INT", ":LONG", ":U64", ":FLOAT", ":BOOL", ":STRING", ":S-EXPR", ":BYTES", ":EXTERN")
			// the void result is no parameter type, and the packed float arrays cross the
			// JVM boundary only
			.doesNotContain(":VOID", ":FLOAT-VECTOR", ":FLOAT-MATRIX");
	}

	@Test
	void anInterfaceIsDescribedWithItsCanonicalIdAndAWitRefusalIsAReadError() {
		String wit = """
				package example:host@0.1.0;

				interface math {
				  add-ints: func(a: s32, b: s32) -> s32;
				  ready: func() -> option<bool>;
				}
				""";
		ClojureBoundary.WitInterface described = ClojureHostBoundary.INSTANCE.importInterface(wit, "host.wit",
				"example:host/math");
		assertThat(described.id()).isEqualTo("example:host/math@0.1.0");
		assertThat(described.members().get(0).params().get(1).type().rep()).isEqualTo(ClojureBoundary.Rep.INT);
		ClojureBoundary.Type ready = Objects.requireNonNull(described.members().get(1).result());
		assertThat(ready.rep()).isEqualTo(ClojureBoundary.Rep.NIL_OR_VALUE);
		assertThat(Objects.requireNonNull(ready.element()).rep()).isEqualTo(ClojureBoundary.Rep.BOOLEAN);
		assertThatThrownBy(() -> ClojureHostBoundary.INSTANCE.importInterface(wit, "host.wit", "nope"))
			.isInstanceOf(LispReadException.class)
			.hasMessage("host.wit: no interface 'nope' (found: example:host/math@0.1.0)");
	}

	@Test
	void aWorldIsDescribedAfterTheChecksThatReadNoProgram() {
		String wit = """
				package local:greeter;

				world greeter {
				  export greet: func(name: string) -> string;
				  export short: func(name: string) -> bool;
				}
				""";
		ClojureBoundary.WitWorld described = ClojureHostBoundary.INSTANCE.exportWorld(wit, "greeter.wit", null);
		assertThat(described.name()).isEqualTo("greeter");
		assertThat(Objects.requireNonNull(described.exports().get(1).result()).rep())
			.isEqualTo(ClojureBoundary.Rep.BOOLEAN);
		assertThatThrownBy(() -> ClojureHostBoundary.INSTANCE.exportWorld(wit, "greeter.wit", "other"))
			.isInstanceOf(LispReadException.class)
			.hasMessage("greeter.wit: no world named 'other' (found: greeter)");
	}

}
