package am.ik.rontolisp.eval;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ClUnicodeTablesTest {

	// A UCD slice big enough to exercise every reader in build/read.lisp: a two-line
	// <..., First>/<..., Last> RANGE (the shape only UnicodeData.txt uses), a
	// decomposition with a compatibility tag, a numeric value that is a ratio, a
	// simple case-mapping pair, a mirrored character and one code point that
	// UnicodeData.txt never mentions at all (2028, reached only through PropList).
	private static final String UNICODE_DATA = """
			0000;<control>;Cc;0;BN;;;;;N;NULL;;;;
			0028;LEFT PARENTHESIS;Ps;0;ON;;;;;Y;OPENING PARENTHESIS;;;;
			0041;LATIN CAPITAL LETTER A;Lu;0;L;;;;;N;;;;0061;
			0061;LATIN SMALL LETTER A;Ll;0;L;;;;;N;;;0041;;0041
			00BD;VULGAR FRACTION ONE HALF;No;0;ON;<fraction> 0031 2044 0032;;;1/2;N;FRACTION ONE HALF;;;;
			00C0;LATIN CAPITAL LETTER A WITH GRAVE;Lu;0;L;0041 0300;;;;N;;;;00E0;
			0300;COMBINING GRAVE ACCENT;Mn;230;NSM;;;;;N;NON-SPACING GRAVE;;;;
			0660;ARABIC-INDIC DIGIT ZERO;Nd;0;AN;;0;0;0;N;;;;;
			D800;<Non Private Use High Surrogate, First>;Cs;0;L;;;;;N;;;;;
			DB7F;<Non Private Use High Surrogate, Last>;Cs;0;L;;;;;N;;;;;
			FF1A;FULLWIDTH COLON;Po;0;CS;<wide> 003A;;;;N;;;;;
			""";

	private static final String SCRIPTS = """
			# Scripts-1.0.0.txt
			0041..005A    ; Latin # L&  [26] LATIN CAPITAL LETTER A..Z
			0660..0669    ; Arabic # Nd [10] ARABIC-INDIC DIGIT ZERO..NINE
			""";

	private static final String BLOCKS = """
			0000..007F; Basic Latin
			0600..06FF; Arabic
			""";

	private static final String WORD_BREAK = """
			0300..036F    ; Extend
			0041..005A    ; ALetter
			""";

	private static final String PROP_LIST = """
			0028          ; Quotation_Mark # Ps  LEFT PARENTHESIS
			2028          ; White_Space # Zl  LINE SEPARATOR
			FDD0..FDEF    ; Noncharacter_Code_Point # Cn [32] <noncharacter>
			""";

	private static final String DERIVED_AGE = """
			0000..0041    ; 1.1 #  [66] <control>..LATIN CAPITAL LETTER A
			00BD          ; 3.2 #       VULGAR FRACTION ONE HALF
			""";

	private static final String BIDI_MIRRORING = """
			0028; 0029 # LEFT PARENTHESIS
			""";

	private static final String JAMO = """
			1100; G   # HANGUL CHOSEONG KIYEOK
			1161; A   # HANGUL JUNGSEONG A
			11A8; G   # HANGUL JONGSEONG KIYEOK
			""";

	private static final String PROPERTY_ALIASES = """
			# ================================================
			age  ; Age
			bc   ; Bidi_Class
			gc   ; General_Category
			""";

	private static final String IDNA = """
			0041          ; mapped   ; 0061   # LATIN CAPITAL LETTER A
			0300          ; valid    ;        ; NV8
			D800..DB7F    ; disallowed
			""";

	private static final String SPECIAL_CASING = """
			00DF; 00DF; 0053 0073; 0053 0053; # LATIN SMALL LETTER SHARP S
			0130; 0069 0307; 0130; 0130; # LATIN CAPITAL LETTER I WITH DOT ABOVE
			03A3; 03C2; 03A3; 03A3; Final_Sigma; # GREEK CAPITAL LETTER SIGMA
			""";

	private static final String CASE_FOLDING = """
			0041; C; 0061; # LATIN CAPITAL LETTER A
			00DF; F; 0073 0073; # LATIN SMALL LETTER SHARP S
			""";

	private static final String COMPOSITION_EXCLUSIONS = """
			# (1) Script Specifics
			0958
			""";

	private static final Map<String, String> FILES = Map.ofEntries(Map.entry("UnicodeData.txt", UNICODE_DATA),
			Map.entry("Scripts.txt", SCRIPTS), Map.entry("Blocks.txt", BLOCKS),
			Map.entry("WordBreakProperty.txt", WORD_BREAK), Map.entry("PropList.txt", PROP_LIST),
			Map.entry("DerivedAge.txt", DERIVED_AGE), Map.entry("BidiMirroring.txt", BIDI_MIRRORING),
			Map.entry("Jamo.txt", JAMO), Map.entry("PropertyAliases.txt", PROPERTY_ALIASES),
			Map.entry("IdnaMappingTable.txt", IDNA), Map.entry("SpecialCasing.txt", SPECIAL_CASING),
			Map.entry("CaseFolding.txt", CASE_FOLDING), Map.entry("CompositionExclusions.txt", COMPOSITION_EXCLUSIONS));

	private static final SourceLoader LOADER = path -> {
		String file = path.substring(path.lastIndexOf('/') + 1);
		String source = FILES.get(file);
		if (source == null) {
			throw new IOException("no such data file: " + path);
		}
		return source;
	};

	private static Map<String, String> generate() {
		return ClUnicodeTables.sources("cl-unicode", LOADER);
	}

	@Test
	void generatesTheThreeComponentsTheReleaseDoesNotShip() {
		assertThat(ClUnicodeTables.generates("lists.lisp")).isTrue();
		assertThat(ClUnicodeTables.generates("hash-tables.lisp")).isTrue();
		assertThat(ClUnicodeTables.generates("methods.lisp")).isTrue();
		// Every other component of cl-unicode is the real upstream source.
		assertThat(ClUnicodeTables.generates("api.lisp")).isFalse();
		assertThat(ClUnicodeTables.generates("util.lisp")).isFalse();
	}

	@Test
	void listsAreThePushnewOrderOfTheDataFiles() {
		String lists = generate().get("lists.lisp");
		// pushnew prepends, so a list reads back in REVERSE order of first appearance,
		// and *general-categories* starts life holding Cn.
		assertThat(lists).contains("(setq cl-unicode::*general-categories* "
				+ "'(cl-unicode-names::PO cl-unicode-names::CS cl-unicode-names::ND cl-unicode-names::MN "
				+ "cl-unicode-names::NO cl-unicode-names::LL cl-unicode-names::LU cl-unicode-names::PS "
				+ "cl-unicode-names::CC cl-unicode-names::CN))");
		// A decomposition's leading <tag> is a property symbol of its own.
		assertThat(lists).contains("(setq cl-unicode::*compatibility-formatting-tags* "
				+ "'(cl-unicode-names::<WIDE> cl-unicode-names::<FRACTION>))");
		assertThat(lists).contains("(setq cl-unicode::*scripts* '(cl-unicode-names::ARABIC cl-unicode-names::LATIN))");
		assertThat(lists)
			.contains("(setq cl-unicode::*code-blocks* '(cl-unicode-names::ARABIC cl-unicode-names::BASICLATIN))");
		// Noncharacter_Code_Point is the one PropList property read-binary-properties
		// skips, and BidiMirrored (which PropList never names) is seeded first.
		assertThat(lists)
			.contains("(setq cl-unicode::*binary-properties* "
					+ "'(cl-unicode-names::WHITESPACE cl-unicode-names::QUOTATIONMARK cl-unicode-names::BIDIMIRRORED))")
			.doesNotContain("cl-unicode-names::NONCHARACTERCODEPOINT cl-unicode-names::WHITESPACE");
	}

	@Test
	void aMethodIsABalancedTreeOfConstantRanges() {
		String methods = generate().get("methods.lisp");
		// The property-symbol methods answer the NAME and the symbol, as dump-method
		// does when it is given no equality test.
		assertThat(methods).contains("(defmethod cl-unicode::general-category ((code-point integer))")
			.contains("(let ((symbol (cl-unicode::tree-lookup code-point '(((")
			.contains("(values (cl-unicode::property-name symbol) symbol)))");
		// The others are a bare lookup.
		assertThat(methods).contains("(defmethod cl-unicode::combining-class ((code-point integer))\n"
				+ "  (cl-unicode::tree-lookup code-point '(((");
		// U+0300's combining class is 230 and it is a range of exactly one code point;
		// every unassigned neighbour folds into the surrounding 0 range.
		assertThat(methods).contains("((768 . 768) . 230)");
		// The last range stops at #x10FFFE, not #x10FFFF: build-range-list's loop
		// returns at (1- +code-point-limit+) without covering it.
		assertThat(methods).contains(" . 1114110) . nil)");
		// A ratio prints as a ratio, and an integral numeric value as an integer.
		assertThat(methods).contains("(defmethod cl-unicode::numeric-value ((code-point integer))")
			.contains("((189 . 189) . 1/2)")
			.contains("((1632 . 1632) . 0)");
		// A decomposition keeps its tag symbol in front of the code points.
		assertThat(methods).contains("((189 . 189) . (cl-unicode-names::<FRACTION> 49 8260 50))");
		// disallowed is the one IDNA status read-idna-mapping drops, so the surrogate
		// range it covers has no mapping while a mapped one does.
		assertThat(methods).contains("(defmethod cl-unicode::idna-mapping ((code-point integer))")
			.contains("((65 . 65) . (cl-unicode-names::MAPPED (97) nil))")
			.contains("((768 . 768) . (cl-unicode-names::VALID nil cl-unicode-names::NV8))");
	}

	@Test
	void aHashTableIsFilledInBoundedChunks() {
		String tables = generate().get("hash-tables.lisp");
		assertThat(tables).contains("(clrhash cl-unicode::*canonical-names*)")
			.contains("(clrhash cl-unicode::*names-to-code-points*)")
			.contains("(clrhash cl-unicode::*code-points-to-names*)")
			.contains("(clrhash cl-unicode::*composition-mappings*)")
			// The Hangul syllable names are computed at load time, not tabulated.
			.endsWith("(cl-unicode::add-hangul-names)\n");
		// A name is keyed by its canonicalized form, which is why the lookup table is
		// equalp: nothing here upcases, the UCD names simply are upper case.
		assertThat(tables).contains("(\"LATINCAPITALLETTERA\" . 65)").contains("(65 . \"LATIN CAPITAL LETTER A\")");
		// A <...> name is not a name: read-character-data nils it out.
		assertThat(tables).doesNotContain("\"<control>\"").doesNotContain("\"<Non Private Use High Surrogate");
		// ... but its Unicode 1.0 name is still a name.
		assertThat(tables).contains("(0 . \"NULL\")");
		// The case-mapping triple is (lower upper title) with nil for the ones the row
		// left empty.
		assertThat(tables).contains("(65 . (97 nil nil))").contains("(97 . (nil 65 65))");
		// A composition mapping is a DOTTED pair (second . composite) in an alist under
		// the first code point -- canonical-composition reads it with assoc/cdr.
		assertThat(tables).contains("(65 . ((768 . 192)))");
		// A special-casing rule is (conditions lower upper title); an unconditional one
		// carries nil where the conditions go.
		assertThat(tables).contains("(223 . ((nil (223) (83 83) (83 115))))")
			.contains("((\"Final_Sigma\") (962) (931) (931))");
		// Every alias of a property maps to the property's own symbol, canonicalized
		// and upcased.
		assertThat(tables).contains("(\"GENERALCATEGORY\" . cl-unicode-names::GENERALCATEGORY)")
			.contains("(\"GC\" . cl-unicode-names::GENERALCATEGORY)");
	}

	@Test
	void aRangeSpansTwoRowsOnlyInUnicodeData() {
		// D800..DB7F is one <..., First>/<..., Last> pair: the Last row is consumed with
		// the First one, so it never becomes a record of its own -- and the whole range
		// gets the category.
		String methods = generate().get("methods.lisp");
		assertThat(methods).contains("((55296 . 56191) . cl-unicode-names::CS)");
	}

	@Test
	void canonicalizeDropsSeparatorsButKeepsTheTwoAmbiguousEndings() {
		assertThat(ClUnicodeTables.canonicalize("LATIN SMALL LETTER A")).isEqualTo("LATINSMALLLETTERA");
		assertThat(ClUnicodeTables.canonicalize("Bidi_Class")).isEqualTo("BidiClass");
		assertThat(ClUnicodeTables.canonicalize("NO-BREAK SPACE")).isEqualTo("NOBREAKSPACE");
		// UTR #18: a hyphen that a space introduces at the very end stays, because
		// dropping it would merge two distinct Unicode names.
		assertThat(ClUnicodeTables.canonicalize("HANGUL JUNGSEONG O-E")).isEqualTo("HANGULJUNGSEONG O-E");
		assertThat(ClUnicodeTables.canonicalize("HANGUL JUNGSEONG -A")).isEqualTo("HANGULJUNGSEONG -A");
	}

	@Test
	void aMissingDataFileNamesTheFileAndTheRelease() {
		SourceLoader missing = path -> {
			throw new IOException("no filesystem");
		};
		assertThatExceptionOfType(java.io.UncheckedIOException.class)
			.isThrownBy(() -> ClUnicodeTables.sources("cl-unicode", missing))
			.withMessageContaining("UnicodeData.txt");
	}

}
