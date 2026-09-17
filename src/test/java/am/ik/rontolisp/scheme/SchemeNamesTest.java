package am.ik.rontolisp.scheme;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemeNamesTest {

	@Test
	void anIdentifierWithALowercaseLetterIsSpelledVerbatim() {
		assertThat(SchemeNames.mangle("car")).isEqualTo("car");
		assertThat(SchemeNames.mangle("list->vector")).isEqualTo("list->vector");
		assertThat(SchemeNames.mangle("MiXed")).isEqualTo("MiXed");
		assertThat(SchemeNames.mangle("x%y")).isEqualTo("x%y");
	}

	@Test
	void aSpellingThatCouldCollideIsEscaped() {
		assertThat(SchemeNames.mangle("CAR")).isEqualTo("s%CAR");
		assertThat(SchemeNames.mangle("T")).isEqualTo("s%T");
		assertThat(SchemeNames.mangle("NIL")).isEqualTo("s%NIL");
		assertThat(SchemeNames.mangle("+")).isEqualTo("s%+");
		assertThat(SchemeNames.mangle("a:b")).isEqualTo("s%a%cb");
		assertThat(SchemeNames.mangle("&rest")).isEqualTo("s%&rest");
		assertThat(SchemeNames.mangle("#f")).isEqualTo("s%#f");
		assertThat(SchemeNames.mangle("100%")).isEqualTo("s%100%%");
	}

	@Test
	void theEscapeIsInjective() {
		// A user identifier that already looks escaped is escaped again, so no two
		// identifiers share a spelling.
		assertThat(SchemeNames.mangle("s%CAR")).isEqualTo("s%s%%CAR");
		assertThat(SchemeNames.mangle("s%x")).isEqualTo("s%s%%x");
	}

}
