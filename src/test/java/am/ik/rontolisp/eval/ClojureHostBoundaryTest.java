package am.ik.rontolisp.eval;

import java.util.Arrays;
import java.util.Objects;

import am.ik.rontolisp.clojure.ClojureBoundary;
import am.ik.rontolisp.compiler.WitTypeMapper;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;
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

	/**
	 * A member's types arrive whole, every nested type with its labels: what the Clojure
	 * lowering converts a record, a variant, an enum, flags, a tuple, a list and a
	 * result's two arms by.
	 */
	@Test
	void aTypeArrivesWithEveryTypeNestedInIt() {
		String wit = """
				package example:host@0.1.0;

				interface shapes {
				  enum color { red, DNS-blue }
				  flags perms { read, write }
				  record point { x: s32, at: tuple<s32, string>, c: option<color> }
				  variant figure { none, dot(point) }
				  draw: func(f: figure, ps: list<point>, p: perms) -> result<point, figure>;
				}
				""";
		ClojureBoundary.Function draw = ClojureHostBoundary.INSTANCE.importInterface(wit, "s.wit", "shapes")
			.members()
			.get(0);
		ClojureBoundary.Type figure = draw.params().get(0).type();
		assertThat(figure.rep()).isEqualTo(ClojureBoundary.Rep.TAGGED_LIST);
		assertThat(figure.parts().stream().map(ClojureBoundary.Part::label)).containsExactly("none", "dot");
		assertThat(figure.parts().get(0).type()).isNull();
		ClojureBoundary.Type point = Objects.requireNonNull(figure.parts().get(1).type());
		assertThat(point.parts().stream().map(part -> part.label() + ":" + type(part.type()).rep()))
			.containsExactly("x:INT", "at:TUPLE_LIST", "c:NIL_OR_VALUE");
		assertThat(type(type(point.parts().get(2).type()).element()).parts().stream().map(ClojureBoundary.Part::label))
			.containsExactly("red", "DNS-blue");
		assertThat(type(draw.params().get(1).type().element()).rep()).isEqualTo(ClojureBoundary.Rep.PLIST);
		assertThat(draw.params().get(2).type().parts().stream().map(ClojureBoundary.Part::label))
			.containsExactly("read", "write");
		ClojureBoundary.Type result = type(draw.result());
		assertThat(type(result.element()).rep()).isEqualTo(ClojureBoundary.Rep.PLIST);
		assertThat(type(result.error()).rep()).isEqualTo(ClojureBoundary.Rep.TAGGED_LIST);
	}

	private static ClojureBoundary.Type type(ClojureBoundary.@Nullable Type type) {
		return Objects.requireNonNull(type);
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
