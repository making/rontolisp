package am.ik.rontolisp.ansi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TopLevelSplitterTest {

	@Test
	void splitsSuccessiveForms() {
		assertThat(TopLevelSplitter.split("(a 1)\n(b 2)\n")).containsExactly("(a 1)", "(b 2)");
	}

	@Test
	void keepsAFeatureGuardAttachedToTheFormItGuards() {
		// Splitting the guard off would run a form written for another implementation.
		assertThat(TopLevelSplitter.split("#+sbcl (a)\n(b)")).containsExactly("#+sbcl (a)", "(b)");
		assertThat(TopLevelSplitter.split("#-(or allegro cmu) (a)\n(b)")).containsExactly("#-(or allegro cmu) (a)",
				"(b)");
	}

	@Test
	void keepsQuoteAndFunctionPrefixesAttached() {
		assertThat(TopLevelSplitter.split("'(a)\n#'(lambda () 1)\n`(x ,y)")).containsExactly("'(a)", "#'(lambda () 1)",
				"`(x ,y)");
	}

	@Test
	void parenthesesInsideStringsAndCharacterLiteralsDoNotCloseAForm() {
		assertThat(TopLevelSplitter.split("(a \")\" #\\( #\\) b)\n(c)")).containsExactly("(a \")\" #\\( #\\) b)",
				"(c)");
		assertThat(TopLevelSplitter.split("(a \"x\\\"(\" b)")).containsExactly("(a \"x\\\"(\" b)");
	}

	@Test
	void skipsCommentsBetweenForms() {
		assertThat(TopLevelSplitter.split("; a (\n(a)\n#| b ) #| nested |# |#\n(b)")).containsExactly("(a)", "(b)");
	}

	@Test
	void readsTheLiteralDispatchesAsOneDatum() {
		assertThat(TopLevelSplitter.split("(a #2A((1 2) (3 4)) #(1 2) #*1010 #x1f #S(foo :a 1) #P\"/x/y\")"))
			.hasSize(1);
	}

	@Test
	void staysWithOneFormPerDatumAcrossLines() {
		String source = """
				(deftest cons.1
				  (cons 'a
				        'b)
				  (a . b))

				(deftest cons.2 (car '(1)) 1)
				""";
		assertThat(TopLevelSplitter.split(source)).hasSize(2);
		assertThat(TopLevelSplitter.split(source).get(1)).isEqualTo("(deftest cons.2 (car '(1)) 1)");
	}

}
