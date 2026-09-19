package am.ik.rontolisp.scheme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
		assertThat(SchemeNames.mangle("#!unspecific")).isEqualTo("s%#!unspecific");
		assertThat(SchemeNames.mangle("100%")).isEqualTo("s%100%%");
	}

	@Test
	void theEscapeIsInjective() {
		// A user identifier that already looks escaped is escaped again, so no two
		// identifiers share a spelling.
		assertThat(SchemeNames.mangle("s%CAR")).isEqualTo("s%s%%CAR");
		assertThat(SchemeNames.mangle("s%x")).isEqualTo("s%s%%x");
	}

	@ParameterizedTest
	@ValueSource(strings = { "car", "CAR", "a:b", "100%", "s%CAR", "#f", "#!unspecific", "", "a b", "&rest" })
	void unmangleUndoesMangle(String identifier) {
		assertThat(SchemeNames.unmangle(SchemeNames.mangle(identifier))).isEqualTo(identifier);
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc", "ABC", "list->vector", "+", "-", "...", "..", "+a", "-a", "+@", "+.a", "-..", ".a",
			"a@", "a.b", "a1", "!$%&*/:<=>?^_~", "λx", "+inf.0x", "+inf", "a+" })
	void anIdentifierIsWrittenBare(String identifier) {
		assertThat(SchemeNames.writtenWithVerticalLines(SchemeNames.mangle(identifier))).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "a b", "1", "1+", "+1", "-1.5", ".5", ".", "+.", "@a", "#foo", "a#", "a;b", "a|b",
			"a\\b", "{", "[", "a'b", "a,b", "a\"b", "+inf.0", "-INF.0", "+nan.0", "-nan.0", "#t", "#f", "#!unspecific",
			"a\tb" })
	void aSpellingThatWouldNotReadBackIsWrittenWithVerticalLines(String identifier) {
		assertThat(SchemeNames.writtenWithVerticalLines(SchemeNames.mangle(identifier))).isTrue();
	}

	@Test
	void aComponentEscapesOnlyTheSeparators() {
		assertThat(SchemeNames.component("util")).isEqualTo("util");
		assertThat(SchemeNames.component("x%y")).isEqualTo("x%y");
		assertThat(SchemeNames.component("a b")).isEqualTo("a|sb");
		assertThat(SchemeNames.component("a)b]c|d")).isEqualTo("a|pb|bc||d");
		assertThat(SchemeNames.libraryPrefix(java.util.List.of("a b")))
			.isNotEqualTo(SchemeNames.libraryPrefix(java.util.List.of("a", "b")));
	}

	@Test
	void theObjectsWithTheirOwnPrinterArmAreNeverWrittenWithVerticalLines() {
		assertThat(SchemeNames.writtenWithVerticalLines("#f")).isFalse();
		assertThat(SchemeNames.writtenWithVerticalLines(SchemeNames.UNSPECIFIED_NAME)).isFalse();
		assertThat(SchemeNames.writtenWithVerticalLines(SchemeBuiltins.ENVIRONMENT_NAME)).isFalse();
	}

}
