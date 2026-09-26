package am.ik.rontolisp.scheme;

import java.util.Arrays;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;
import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemeReaderTest {

	private static List<LispVal> read(String source) {
		return new SchemeReader(source, "test.scm").readAll();
	}

	private static String printed(String source) {
		return read(source).stream().map(LispVal::print).toList().toString();
	}

	@Test
	void aNamedFilesListHeadsAreLocatedAndABuffersAreNot() {
		List<LispVal> datums = read("(a\n  (b c))\n'(d)\n");
		LocatedCons outer = (LocatedCons) datums.get(0);
		assertThat(outer.file()).isEqualTo("test.scm");
		assertThat(outer.line()).isEqualTo(1);
		LispVal second = ((LispCons) outer.cdr()).car();
		assertThat(((LocatedCons) second).line()).isEqualTo(2);
		assertThat(((LispCons) outer.cdr())).isNotInstanceOf(LocatedCons.class);
		assertThat(((LocatedCons) datums.get(1)).line()).isEqualTo(3);
		assertThat(new SchemeReader("(a b)", null).readAll().get(0)).isNotInstanceOf(LocatedCons.class);
	}

	@Test
	void identifiersKeepTheirCase() {
		assertThat(printed("foo Foo FOO list->vector + ... 1+ -x"))
			.isEqualTo("[|foo|, |Foo|, FOO, |list->vector|, +, ..., 1+, |-x|]");
	}

	@Test
	void booleansAreTheirOwnDatums() {
		assertThat(read("#t #true #f #false")).containsExactly(SchemeReader.TRUE, SchemeReader.TRUE, SchemeReader.FALSE,
				SchemeReader.FALSE);
	}

	@Test
	void numbers() {
		assertThat(printed("42 -17 +5 1/2 -3/6 1.5 .5 -0.25 1e3 1.5e-2 #xff #b101 #o17 #d10 12345678901234567890"))
			.isEqualTo("[42, -17, 5, 1/2, -1/2, 1.5, 0.5, -0.25, 1000.0, 0.015, 255, 5, 15, 10, 12345678901234567890]");
	}

	@Test
	void radixAndExactnessPrefixesCombine() {
		// Gauche 0.9.15's answers for the same tokens (`.todo/889`): a radix prefix
		// overrides the caller's default, an exactness prefix converts the result,
		// either order, each at most once, and #e on a decimal is the exact rational
		// the digits spell rather than a flonum rounding.
		assertThat(printed("#e1.5 #i5 #e#x10 #x#e10 #i#o17 #e1/3 #i1/3 #e0.0 #e-2.5e2 #e1e10"))
			.isEqualTo("[3/2, 5.0, 16, 16, 15.0, 1/3, 0.3333333333333333, 0, -250, 10000000000]");
	}

	@Test
	void anExactnessPrefixOnAnInfinityOrANanIsANoOp() {
		assertThat(read("#e+inf.0")).containsExactly(new LispDouble(Double.POSITIVE_INFINITY));
		assertThat(read("#i+inf.0")).containsExactly(new LispDouble(Double.POSITIVE_INFINITY));
		assertThat(((LispDouble) read("#e+nan.0").getFirst()).value()).isNaN();
	}

	@Test
	void aRepeatedOrUnrecognizedPrefixIsAReadError() {
		assertThatThrownBy(() -> read("#b#x1")).isInstanceOf(LispReadException.class);
		assertThatThrownBy(() -> read("#e#e1")).isInstanceOf(LispReadException.class);
		assertThatThrownBy(() -> read("#z12")).isInstanceOf(LispReadException.class);
	}

	@Test
	void negativeZeroKeepsItsSign() {
		assertThat(printed("-0.0 -0. -.0 0.0")).isEqualTo("[-0.0, -0.0, -0.0, 0.0]");
	}

	@Test
	void charactersAndStrings() {
		assertThat(printed(
				"#\\a #\\A #\\space #\\newline #\\tab #\\x41 #\\( \"a\\n\\t\\\"\\\\b\" \"\\x41;\" \"a \\\n   b\""))
			.isEqualTo("[#\\a, #\\A, #\\Space, #\\Newline, #\\Tab, #\\A, #\\(, \"a\n\t\\\"\\\\b\", \"A\", \"a b\"]");
	}

	@Test
	void listsVectorsAndAbbreviations() {
		assertThat(printed("() (a . b) (a b . c) #(1 x) '(a) `(a ,b ,@c)")).isEqualTo(
				"[NIL, (|a| . |b|), (|a| |b| . |c|), #(1 |x|), (|quote| (|a|)), (|quasiquote| (|a| (|unquote| |b|) (|unquote-splicing| |c|)))]");
	}

	@Test
	void aBytevectorIsAPackedOctetVector() {
		List<LispVal> read = read("#u8(1 #xff 0) #U8() #u8( 7 )");
		assertThat(read).allSatisfy(datum -> assertThat(datum).isInstanceOf(LispIntVector.class));
		assertThat(read.stream().map(datum -> ((LispIntVector) datum).width()).toList()).containsOnly(8);
		assertThat(read.stream().map(datum -> Arrays.toString(((LispIntVector) datum).toLongArray())).toList())
			.containsExactly("[1, 255, 0]", "[]", "[7]");
	}

	@Test
	void aBytevectorHoldsOnlyBytes() {
		assertThatThrownBy(() -> read("#u8(1 256)"))
			.hasMessage("test.scm:1:7: a bytevector element must be a byte (0-255): 256");
		assertThatThrownBy(() -> read("#u8(a)"))
			.hasMessage("test.scm:1:5: a bytevector element must be a byte (0-255): a");
		assertThatThrownBy(() -> read("#u8(1.0)"))
			.hasMessage("test.scm:1:5: a bytevector element must be a byte (0-255): 1.0");
		assertThatThrownBy(() -> read("#u8(1 . 2)")).hasMessage("test.scm:1:7: a bytevector cannot be dotted");
		assertThatThrownBy(() -> read("#u8(1 2")).hasMessage("test.scm:1:1: unclosed '#u8('");
		assertThatThrownBy(() -> read("#u8 (1)")).hasMessage("test.scm:1:1: unsupported '#' syntax: #u8");
		assertThatThrownBy(() -> read("#u16(1)")).hasMessage("test.scm:1:1: unsupported '#' syntax: #u16");
	}

	@Test
	void commentsAreSkipped() {
		assertThat(printed("1 ; line\n #| block #| nested |# |# 2 #;(datum comment) 3 #;4")).isEqualTo("[1, 2, 3]");
	}

	@Test
	void aReadErrorNamesWhereTheConstructOpened() {
		assertThatThrownBy(() -> read("(define x\n  (list 1 2")).isInstanceOf(LispReadException.class)
			.hasMessage("test.scm:2:3: unclosed '('");
		assertThatThrownBy(() -> read("\n  \"never closed")).hasMessage("test.scm:2:3: unterminated string");
		assertThatThrownBy(() -> read("(a))")).hasMessage("test.scm:1:4: unexpected ')'");
		assertThatThrownBy(() -> read("(. a)")).hasMessage("test.scm:1:2: a dotted pair needs a datum before the '.'");
	}

	@Test
	void unsupportedSyntaxIsRefusedByName() {
		assertThatThrownBy(() -> read("[a]"))
			.hasMessage("test.scm:1:1: '[' is not a delimiter in R7RS; use parentheses");
		assertThatThrownBy(() -> read("#\\bogus")).hasMessage("test.scm:1:1: unknown character name: #\\bogus");
	}

	@Test
	void aVerticalLineIdentifierIsASymbolOfAnySpelling() {
		assertThat(read("|foo bar| || |1| |.| |a\\x41;b\\|c| |\\t\\\\\"| |#t|")).containsExactly(
				new LispSymbol("foo bar"), new LispSymbol(""), new LispSymbol("1"), new LispSymbol("."),
				new LispSymbol("aAb|c"), new LispSymbol("\t\\\""), new LispSymbol("#t"));
	}

	@Test
	void aVerticalLineIdentifierSpelledLikeABooleanIsNotOne() {
		assertThat(read("|#t| |#f| #t #f").stream().map(SchemeReader::isBoolean).toList()).containsExactly(false, false,
				true, true);
	}

	@Test
	void aVerticalLineDelimitsAndIsNeverFolded() {
		assertThat(read("a|b c|d")).containsExactly(new LispSymbol("a"), new LispSymbol("b c"), new LispSymbol("d"));
		assertThat(read("#!fold-case ABC |ABC|")).containsExactly(new LispSymbol("abc"), new LispSymbol("ABC"));
	}

	@Test
	void aVerticalLineIdentifierThatDoesNotCloseIsAnError() {
		assertThatThrownBy(() -> read("(a |b c")).isInstanceOf(LispReadException.class)
			.hasMessage("test.scm:1:4: unterminated '|' identifier");
		assertThatThrownBy(() -> read("|a\\qb|")).hasMessage("test.scm:1:3: unknown identifier escape: \\q");
	}

	@Test
	void infinitiesAndNanAreFlonumsInAnyCaseAndRadix() {
		assertThat(read("+inf.0 -inf.0 +INF.0 #x-Inf.0")).containsExactly(new LispDouble(Double.POSITIVE_INFINITY),
				new LispDouble(Double.NEGATIVE_INFINITY), new LispDouble(Double.POSITIVE_INFINITY),
				new LispDouble(Double.NEGATIVE_INFINITY));
		assertThat(read("+nan.0 -nan.0 +NaN.0")).allSatisfy(datum -> assertThat(datum)
			.isInstanceOfSatisfying(LispDouble.class, d -> assertThat(d.value()).isNaN()));
		assertThat(read("+inf.00 inf.0 +inf -nan")).containsExactly(new LispSymbol("+inf.00"), new LispSymbol("inf.0"),
				new LispSymbol("+inf"), new LispSymbol("-nan"));
	}

	@Test
	void foldCaseTogglesOnAndOffAndOnAgainWithinOneFile() {
		assertThat(printed("#!fold-case FOO Foo #!no-fold-case FOO Foo #!fold-case FOO"))
			.isEqualTo("[|foo|, |foo|, FOO, |Foo|, |foo|]");
	}

	@Test
	void foldCaseFoldsCharacterNamesNotTheCharacterItself() {
		assertThat(printed("#!fold-case #\\NEWLINE #\\A #\\SPACE")).isEqualTo("[#\\Newline, #\\A, #\\Space]");
		assertThatThrownBy(() -> read("#\\NEWLINE")).hasMessage("test.scm:1:1: unknown character name: #\\NEWLINE");
	}

	@Test
	void foldCaseFoldsAsStringFoldcaseDoes() {
		// R7RS 2.1: as if by string-foldcase -- full case folding, not lowercasing (a
		// final sigma folds to sigma, sharp s to "ss", a Cherokee letter to its capital).
		assertThat(printed("#!fold-case Straße ΧΑΟΣ ǅ")).isEqualTo("[|strasse|, χαοσ, ǆ]");
	}

	@Test
	void foldCaseDoesNotFoldStrings() {
		assertThat(printed("#!fold-case \"Foo\"")).isEqualTo("[\"Foo\"]");
	}

	@Test
	void foldCaseIsAtmosphereAndDoesNotShiftWhatFollows() {
		assertThatThrownBy(() -> read("#!fold-case (a))")).hasMessage("test.scm:1:16: unexpected ')'");
	}

	@Test
	void anUnknownBangDirectiveIsRefusedByName() {
		assertThatThrownBy(() -> read("#!bogus 1")).hasMessage("test.scm:1:1: unsupported '#' syntax: #!bogus");
	}

}
