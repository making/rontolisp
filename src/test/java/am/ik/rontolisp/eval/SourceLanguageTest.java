package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The source-language seam: one read for user source, one pick of the language.
 */
class SourceLanguageTest {

	@Test
	void thePlainReadParsesForms() {
		List<LispVal> forms = SourceLanguage.COMMON_LISP.read("(+ 1 2)", Features.INTERPRETER, null);
		assertThat(forms).hasSize(1);
	}

	@Test
	void aReadEvalDatumArrivesAsAMarker() {
		// Through the seam, #. takes the marker read -- the consumer resolves each
		// marker just before the top-level form holding it evaluates.
		List<LispVal> forms = SourceLanguage.COMMON_LISP.read("(+ 1 #.(+ 1 1))", Features.INTERPRETER, null);
		assertThat(forms).containsExactly(LispReader.readFromString("(+ 1 (%read-eval (+ 1 1)))"));
		assertThat(SourceLanguage.usesReadEvalMarkers("(+ 1 #.(+ 1 1))")).isTrue();
		assertThat(SourceLanguage.usesReadEvalMarkers("(+ 1 2)")).isFalse();
	}

	@Test
	void theStrictReadRefusesReadEval() {
		assertThatThrownBy(() -> SourceLanguage.COMMON_LISP.readStrict("(+ 1 #.(+ 1 1))", Features.INTERPRETER))
			.hasMessageContaining("#.");
	}

	@Test
	void everyExtensionReadsCommonLispUntilALanguageClaimsIt() {
		assertThat(SourceLanguage.forFile("hello.lisp", null)).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThat(SourceLanguage.forFile("hello.scm", null)).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThat(SourceLanguage.forFile(null, null)).isEqualTo(SourceLanguage.COMMON_LISP);
	}

	@Test
	void theOverrideNamesTheEntryLanguage() {
		assertThat(SourceLanguage.forFile("hello.lisp", "common-lisp")).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThat(SourceLanguage.forFile("hello.lisp", "cl")).isEqualTo(SourceLanguage.COMMON_LISP);
		assertThatThrownBy(() -> SourceLanguage.forFile("hello.lisp", "elvish"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("--source-language");
	}

	@Test
	void theFileSpellingsLiveInOnePlace() {
		assertThat(SourceLanguage.isSourceFile("foo.lisp")).isTrue();
		assertThat(SourceLanguage.isSourceFile("foo")).isFalse();
		assertThat(SourceLanguage.fileNameForModule("UTIL")).isEqualTo("util.lisp");
	}

}
