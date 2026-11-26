package am.ik.maven;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The named character references Maven's POM reader accepts beyond XML's five: the XHTML
 * 1.0 Latin-1, special and symbol sets, the table plexus-utils' MXParser is created with
 * ({@code EntityReplacementMap.defaultEntityReplacementMap}, 248 names, dumped from
 * plexus-utils 3.6.1). So a POM whose description says {@code &copy;} or {@code &nbsp;}
 * reads, as it does in Maven, though an XML parser would reject it.
 */
final class XhtmlEntities {

	/**
	 * Runs of consecutive code points: {@code first name name ...;}, the first name
	 * standing for the run's first code point.
	 */
	private static final String RUNS = "160 nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not "
			+ "shy reg macr deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 "
			+ "frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave "
			+ "Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml "
			+ "Yacute THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml "
			+ "igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute "
			+ "ucirc uuml yacute thorn yuml;"
			+ "338 OElig oelig;352 Scaron scaron;376 Yuml;710 circ;732 tilde;8194 ensp emsp;8201 thinsp;"
			+ "8204 zwnj zwj lrm rlm;8211 ndash mdash;8216 lsquo rsquo sbquo;8220 ldquo rdquo bdquo;"
			+ "8224 dagger Dagger;8240 permil;8249 lsaquo rsaquo;8364 euro;402 fnof;"
			+ "913 Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda Mu Nu Xi Omicron Pi Rho;"
			+ "931 Sigma Tau Upsilon Phi Chi Psi Omega;"
			+ "945 alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigmaf "
			+ "sigma tau upsilon phi chi psi omega;"
			+ "977 thetasym upsih;982 piv;8226 bull;8230 hellip;8242 prime Prime;8254 oline;8260 frasl;"
			+ "8472 weierp;8465 image;8476 real;8482 trade;8501 alefsym;8592 larr uarr rarr darr harr;"
			+ "8629 crarr;8656 lArr uArr rArr dArr hArr;8704 forall;8706 part exist;8709 empty;"
			+ "8711 nabla isin notin;8715 ni;8719 prod;8721 sum minus;8727 lowast;8730 radic;"
			+ "8733 prop infin;8736 ang;8743 and or cap cup int;8756 there4;8764 sim;8773 cong;"
			+ "8776 asymp;8800 ne equiv;8804 le ge;8834 sub sup nsub;8838 sube supe;8853 oplus;"
			+ "8855 otimes;8869 perp;8901 sdot;8968 lceil rceil lfloor rfloor;9001 lang rang;9674 loz;"
			+ "9824 spades;9827 clubs;9829 hearts diams;";

	private static final Map<String, Integer> CODE_POINTS = parse();

	private XhtmlEntities() {
	}

	/**
	 * Returns the code point a name stands for.
	 * @param name the entity name, without {@code &} and {@code ;}
	 * @return the code point, or {@code null} when the table has no such name
	 */
	static @Nullable Integer codePoint(String name) {
		return CODE_POINTS.get(name);
	}

	/**
	 * Returns the number of names in the table.
	 * @return the size
	 */
	static int size() {
		return CODE_POINTS.size();
	}

	private static Map<String, Integer> parse() {
		Map<String, Integer> table = new HashMap<>();
		for (String run : RUNS.split(";")) {
			String[] words = run.trim().split(" ");
			int codePoint = Integer.parseInt(words[0]);
			for (int i = 1; i < words.length; i++) {
				table.put(words[i], codePoint + i - 1);
			}
		}
		return Map.copyOf(table);
	}

}
