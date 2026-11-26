package am.ik.wit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An interface defines each member name once: a type and a function may not share a name,
 * nor may two types, two functions, or a {@code use}d name and a local one.
 * {@code wasm-tools component wit} refuses every such shape with "name `x` is defined
 * more than once" (measured 2026-10-13, wasm-tools 1.254.0); the reader used to accept
 * them, so a program could depend on a file every other tool rejects. Each refusal below
 * is pinned in that tool's words.
 *
 * <p>
 * The same holds outside interfaces: a world that imports one name twice, two top-level
 * items in one package sharing a name, two resource methods sharing a name, and a
 * function repeating a parameter are all refused, each in wasm-tools' words. What that
 * tool accepts, the reader accepts too: repeated {@code record} fields and
 * {@code enum}/{@code flags}/{@code variant} cases, one name in two interfaces of a
 * package, a world importing and exporting one name, and an export beside a type of the
 * same name.
 */
class WitDuplicateNameTest {

	private static void assertDuplicate(String source, String name) {
		assertThatThrownBy(() -> WitParser.parse(source)).isInstanceOf(WitParseException.class)
			.hasMessageContaining("name `" + name + "` is defined more than once");
	}

	private static void assertRefused(String source, String message) {
		assertThatThrownBy(() -> WitParser.parse(source)).isInstanceOf(WitParseException.class)
			.hasMessageContaining(message);
	}

	@Test
	void aTypeAndAFunctionMayNotShareAName() {
		assertDuplicate("""
				package example:shipping@0.1.0;

				interface rates {
				  record quote {
				    cents: u32,
				  }

				  quote: func(sku: string) -> quote;
				}

				world shop {
				  import rates;
				}
				""", "quote");
	}

	@Test
	void aFunctionAndALaterTypeMayNotShareANameEither() {
		assertDuplicate("""
				package example:dup@0.1.0;

				interface i {
				  quote: func(sku: string) -> string;

				  record quote {
				    cents: u32,
				  }
				}

				world w {
				  import i;
				}
				""", "quote");
	}

	@Test
	void twoFunctionsMayNotShareAName() {
		assertDuplicate("""
				package example:dup@0.1.0;

				interface i {
				  quote: func(sku: string) -> string;
				  quote: func(sku: string) -> string;
				}

				world w {
				  import i;
				}
				""", "quote");
	}

	@Test
	void twoTypesMayNotShareAName() {
		assertDuplicate("""
				package example:dup@0.1.0;

				interface i {
				  record quote {
				    cents: u32,
				  }

				  record quote {
				    cents: u32,
				  }
				}

				world w {
				  import i;
				}
				""", "quote");
	}

	@Test
	void aResourceAndAFunctionMayNotShareAName() {
		assertDuplicate("""
				package example:dup@0.1.0;

				interface i {
				  resource quote;

				  quote: func() -> string;
				}

				world w {
				  import i;
				}
				""", "quote");
	}

	@Test
	void aTypeAliasAndAFunctionMayNotShareAName() {
		assertDuplicate("""
				package example:dup@0.1.0;

				interface i {
				  type quote = string;

				  quote: func() -> quote;
				}

				world w {
				  import i;
				}
				""", "quote");
	}

	@Test
	void aUsedNameMayNotCollideWithALocalOne() {
		assertDuplicate("""
				package example:dup@0.1.0;

				interface types {
				  record quote {
				    cents: u32,
				  }
				}

				interface i {
				  use types.{quote};

				  record quote {
				    cents: u32,
				  }

				  f: func() -> quote;
				}

				world w {
				  import i;
				}
				""", "quote");
	}

	@Test
	void aLocalFunctionMayNotCollideWithALaterUse() {
		assertDuplicate("""
				package example:dup@0.1.0;

				interface types {
				  record quote {
				    cents: u32,
				  }
				}

				interface i {
				  quote: func() -> string;

				  use types.{quote};
				}

				world w {
				  import i;
				}
				""", "quote");
	}

	@Test
	void aUsedNameMayNotArriveTwiceFromDifferentPaths() {
		assertDuplicate("""
				package example:dup@0.1.0;

				interface t1 {
				  record quote {
				    cents: u32,
				  }
				}

				interface t2 {
				  record quote {
				    cents: u32,
				  }
				}

				interface i {
				  use t1.{quote};
				  use t2.{quote};

				  f: func() -> quote;
				}

				world w {
				  import i;
				}
				""", "quote");
	}

	@Test
	void aUseAliasCollidesUnderTheAlias() {
		assertDuplicate("""
				package example:dup@0.1.0;

				interface types {
				  record quote {
				    cents: u32,
				  }
				}

				interface i {
				  use types.{quote as q};

				  q: func() -> string;
				}

				world w {
				  import i;
				}
				""", "q");
	}

	@Test
	void aUseAliasFreesTheOriginalName() {
		assertThatCode(() -> WitParser.parse("""
				package example:dup@0.1.0;

				interface types {
				  record quote {
				    cents: u32,
				  }
				}

				interface i {
				  use types.{quote as q};

				  record quote {
				    cents: u32,
				  }

				  f: func(a: q, b: quote) -> string;
				}

				world w {
				  import i;
				}
				""")).doesNotThrowAnyException();
	}

	@Test
	void anInlineInterfaceInAWorldChecksNamesToo() {
		assertDuplicate("""
				package example:dup@0.1.0;

				world w {
				  import x: interface {
				    f: func();
				    f: func();
				  }
				}
				""", "f");
	}

	@Test
	void theSameNameInTwoInterfacesIsFine() {
		assertThatCode(() -> WitParser.parse("""
				package example:dup@0.1.0;

				interface a {
				  record quote {
				    cents: u32,
				  }
				}

				interface b {
				  quote: func() -> string;
				}

				world w {
				  import a;
				  import b;
				}
				""")).doesNotThrowAnyException();
	}

	@Test
	void theRefusalNamesTheWitLine() {
		assertThatThrownBy(() -> WitParser.parse("""
				package example:dup@0.1.0;

				interface i {
				  quote: func(sku: string) -> string;
				  quote: func(sku: string) -> string;
				}

				world w {
				  import i;
				}
				""")).isInstanceOf(WitParseException.class)
			.hasMessageContaining("name `quote` is defined more than once")
			.hasMessageContaining("at line 5, column 3");
	}

	@Test
	void aWorldMayNotImportOneInterfaceTwice() {
		assertRefused("""
				package example:dup@0.1.0;

				interface a {
				  f: func();
				}

				world w {
				  import a;
				  import a;
				}
				""", "interface cannot be imported more than once");
	}

	@Test
	void aWorldMayNotExportOneInterfaceTwice() {
		assertRefused("""
				package example:dup@0.1.0;

				interface a {
				  f: func();
				}

				world w {
				  export a;
				  export a;
				}
				""", "interface cannot be exported more than once");
	}

	@Test
	void aWorldMayNotImportOneFuncTwice() {
		assertRefused("""
				package example:dup@0.1.0;

				world w {
				  import quote: func();
				  import quote: func();
				}
				""", "import `quote` conflicts with prior func of same name");
	}

	@Test
	void aWorldMayNotImportOneInlineInterfaceTwice() {
		assertRefused("""
				package example:dup@0.1.0;

				world w {
				  import x: interface {
				    f: func();
				  }
				  import x: interface {
				    g: func();
				  }
				}
				""", "import `x` conflicts with prior interface of same name");
	}

	@Test
	void aWorldImportFuncMayNotFollowAnInterfaceOfTheSameName() {
		assertRefused("""
				package example:dup@0.1.0;

				world w {
				  import x: interface {
				    f: func();
				  }
				  import x: func();
				}
				""", "import `x` conflicts with prior interface of same name");
	}

	@Test
	void aWorldImportInterfaceMayNotFollowAFuncOfTheSameName() {
		assertRefused("""
				package example:dup@0.1.0;

				world w {
				  import x: func();
				  import x: interface {
				    f: func();
				  }
				}
				""", "import `x` conflicts with prior func of same name");
	}

	@Test
	void aWorldImportMayNotShareANameWithAType() {
		assertRefused("""
				package example:dup@0.1.0;

				world w {
				  type r = string;
				  import r: func();
				}
				""", "import `r` conflicts with prior type of same name");
	}

	@Test
	void aWorldImportStillLosesToALaterType() {
		// wasm-tools resolves a world's types against its imports however they are
		// ordered, so the error blames the import's line even when the type comes
		// second.
		assertThatThrownBy(() -> WitParser.parse("""
				package example:dup@0.1.0;

				world w {
				  import r: func();
				  type r = string;
				}
				""")).isInstanceOf(WitParseException.class)
			.hasMessageContaining("import `r` conflicts with prior type of same name")
			.hasMessageContaining("at line 4, column 10");
	}

	@Test
	void aWorldImportMayNotShareANameWithAUsedName() {
		assertRefused("""
				package example:dup@0.1.0;

				interface t {
				  record r {
				    x: string,
				  }
				}

				world w {
				  use t.{r};
				  import r: func();
				}
				""", "import `r` conflicts with prior type of same name");
	}

	@Test
	void aWorldMayNotExportOneFuncTwice() {
		assertRefused("""
				package example:dup@0.1.0;

				world w {
				  export a: func();
				  export a: func();
				}
				""", "export `a` conflicts with prior func of same name");
	}

	@Test
	void aWorldMayNotExportOneInlineInterfaceTwice() {
		assertRefused("""
				package example:dup@0.1.0;

				world w {
				  export x: interface {
				    f: func();
				  }
				  export x: interface {
				    g: func();
				  }
				}
				""", "export `x` conflicts with prior interface of same name");
	}

	@Test
	void aWorldMayNotDefineOneTypeTwice() {
		assertDuplicate("""
				package example:dup@0.1.0;

				world w {
				  type r = string;
				  type r = u32;
				}
				""", "r");
	}

	@Test
	void aWorldUseMayNotArriveTwice() {
		assertDuplicate("""
				package example:dup@0.1.0;

				interface t {
				  record r {
				    x: string,
				  }
				}

				world w {
				  use t.{r};
				  use t.{r};
				}
				""", "r");
	}

	@Test
	void aWorldUseMayNotCollideWithAType() {
		assertDuplicate("""
				package example:dup@0.1.0;

				interface t {
				  record r {
				    x: string,
				  }
				}

				world w {
				  use t.{r};
				  type r = string;
				}
				""", "r");
	}

	@Test
	void twoInterfacesMayNotShareAName() {
		assertRefused("""
				package example:dup@0.1.0;

				interface i {
				  f: func();
				}

				interface i {
				  g: func();
				}
				""", "duplicate item named `i`");
	}

	@Test
	void twoWorldsMayNotShareAName() {
		assertRefused("""
				package example:dup@0.1.0;

				world w {
				  import x: func();
				}

				world w {
				  import y: func();
				}
				""", "duplicate item named `w`");
	}

	@Test
	void anInterfaceAndAWorldMayNotShareAName() {
		assertRefused("""
				package example:dup@0.1.0;

				interface w {
				  f: func();
				}

				world w {
				  import x: func();
				}
				""", "duplicate item named `w`");
	}

	@Test
	void twoInterfacesInOnePackageBlockMayNotShareAName() {
		assertRefused("""
				package example:hdr@0.1.0;

				interface top {
				  f: func();
				}

				package example:other@0.1.0 {
				  interface i {
				    f: func();
				  }

				  interface i {
				    g: func();
				  }
				}
				""", "duplicate item named `i`");
	}

	@Test
	void twoResourceMethodsMayNotShareAName() {
		assertRefused("""
				package example:dup@0.1.0;

				interface i {
				  resource r {
				    m: func();
				    m: func();
				  }
				}

				world w {
				  import i;
				}
				""", "duplicate function name `m`");
	}

	@Test
	void aResourceMayNotDefineTwoConstructors() {
		assertRefused("""
				package example:dup@0.1.0;

				interface i {
				  resource r {
				    constructor(x: string);
				    constructor(y: string);
				  }
				}

				world w {
				  import i;
				}
				""", "duplicate constructors");
	}

	@Test
	void aFunctionMayNotRepeatAParameter() {
		assertRefused("""
				package example:dup@0.1.0;

				interface i {
				  f: func(x: string, x: u32);
				}

				world w {
				  import i;
				}
				""", "param `x` is defined more than once");
	}

	@Test
	void aConstructorMayNotRepeatAParameter() {
		assertRefused("""
				package example:dup@0.1.0;

				interface i {
				  resource r {
				    constructor(x: string, x: u32);
				  }
				}

				world w {
				  import i;
				}
				""", "param `x` is defined more than once");
	}

	@Test
	void aWorldImportFuncMayNotRepeatAParameter() {
		assertRefused("""
				package example:dup@0.1.0;

				world w {
				  import f: func(x: string, x: u32);
				}
				""", "param `x` is defined more than once");
	}

	@Test
	void duplicateRecordFieldsAndCasesStayAccepted() {
		assertThatCode(() -> WitParser.parse("""
				package example:dup@0.1.0;

				interface i {
				  record r {
				    x: string,
				    x: u32,
				  }

				  enum e {
				    a,
				    a,
				  }

				  flags fl {
				    b,
				    b,
				  }

				  variant v {
				    c,
				    c,
				  }

				  f: func() -> r;
				}

				world w {
				  import i;
				}
				""")).doesNotThrowAnyException();
	}

	@Test
	void aWorldMayImportAndExportOneName() {
		assertThatCode(() -> WitParser.parse("""
				package example:dup@0.1.0;

				interface a {
				  f: func();
				}

				world w {
				  import a;
				  export a;
				}
				""")).doesNotThrowAnyException();
	}

	@Test
	void aWorldExportBesideATypeOfTheSameNameIsFine() {
		assertThatCode(() -> WitParser.parse("""
				package example:dup@0.1.0;

				world w {
				  type r = string;
				  export r: func();
				}
				""")).doesNotThrowAnyException();
	}

	@Test
	void aWorldReferenceBesideANamedItemOfTheSameNameIsFine() {
		assertThatCode(() -> WitParser.parse("""
				package example:dup@0.1.0;

				interface a {
				  f: func();
				}

				world w {
				  import a;
				  import a: func();
				}
				""")).doesNotThrowAnyException();
	}

	@Test
	void theSameNameInTwoPackagesIsFine() {
		assertThatCode(() -> WitParser.parse("""
				package example:hdr@0.1.0;

				interface i {
				  f: func();
				}

				package example:other@0.1.0 {
				  interface i {
				    g: func();
				  }
				}
				""")).doesNotThrowAnyException();
	}

}
