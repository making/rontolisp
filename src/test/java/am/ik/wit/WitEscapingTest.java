package am.ik.wit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A WIT identifier that collides with a keyword is {@code %}-escaped in the source text
 * ({@code %type}, {@code %flags}, {@code %stream} all occur in the WASI WIT itself), but
 * the {@code %} is source escaping only: the name -- and the component-model label it
 * becomes -- is the bare word. The model therefore holds the bare word and the printer
 * puts the {@code %} back.
 */
class WitEscapingTest {

	@Test
	void escapedIdentifiersAreBareInTheModel() {
		WitDocument document = WitParser.parse("""
				package example:esc;

				world w {
				  export count: func(%type: string, %flags: u32) -> s32;
				}
				""");
		WitItem.World world = document.world();
		WitItem.ExportNamed export = (WitItem.ExportNamed) world.items().get(0);
		WitFunc func = ((WitItem.Extern.ExternFunc) export.extern()).func();
		assertThat(func.params().stream().map(WitFunc.Param::name)).containsExactly("type", "flags");
	}

	@Test
	void escapedFunctionNameIsBareInTheModel() {
		WitDocument document = WitParser.parse("""
				package example:esc;

				interface i {
				  %stream: func() -> u32;
				}
				""");
		WitItem.InterfaceDef iface = (WitItem.InterfaceDef) document.items().get(1);
		assertThat(((WitItem.FuncDef) iface.items().get(0)).name()).isEqualTo("stream");
	}

	@Test
	void printerReEscapesExactlyTheKeywordIdentifiers() {
		String source = """
				package example:esc;

				world w {
				  export count: func(%type: string, name: string) -> s32;
				}
				""";
		assertThat(WitPrinter.print(WitParser.parse(source))).isEqualTo(source);
	}

	@Test
	void anEscapedTypeReferenceIsAlwaysANamedType() {
		// %list names a user type called `list`; it is never the built-in list type.
		WitDocument document = WitParser.parse("""
				package example:esc;

				interface i {
				  type %list = u32;

				  get: func() -> %list;
				}
				""");
		WitItem.InterfaceDef iface = (WitItem.InterfaceDef) document.items().get(1);
		assertThat(((WitItem.TypeAlias) iface.items().get(0)).name()).isEqualTo("list");
		WitFunc func = ((WitItem.FuncDef) iface.items().get(1)).func();
		assertThat(func.result()).isEqualTo(new WitType.Named("list"));
	}

}
