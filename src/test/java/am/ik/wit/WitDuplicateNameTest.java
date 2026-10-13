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
 */
class WitDuplicateNameTest {

	private static void assertDuplicate(String source, String name) {
		assertThatThrownBy(() -> WitParser.parse(source)).isInstanceOf(WitParseException.class)
			.hasMessageContaining("name `" + name + "` is defined more than once");
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

}
