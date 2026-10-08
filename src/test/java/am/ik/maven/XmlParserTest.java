package am.ik.maven;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The POM XML reader accepts and rejects what Maven's reader (MXParser) does. The POM
 * fixtures cover the same ground against Maven itself (MavenOracleParityTest's
 * {@code reading} and {@code xml} cases); these pin the reader's own answers.
 */
class XmlParserTest {

	private static XmlElement parse(String xml) throws XmlParser.Malformed {
		return XmlParser.parse(xml.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void textCdataReferencesAndCommentsMakeOneValue() throws XmlParser.Malformed {
		XmlElement root = parse("<p><v>1&#46;<![CDATA[<0>]]><!-- c --><?pi x?>&lt;&#x41;&amp;</v></p>");

		assertThat(root.children("v")).singleElement().extracting(XmlElement::text).isEqualTo("1.<0><A&");
	}

	@Test
	void theXhtmlNamedReferencesResolveAsInMavensReader() throws XmlParser.Malformed {
		assertThat(parse("<p>&copy;&nbsp;&eacute;&euro;&hearts;&Omega;</p>").text()).isEqualTo("© é€♥Ω");
		// plexus-utils 3.6.1's default replacement map, dumped: the XHTML Latin-1,
		// special
		// and symbol sets less the four XML already predefines.
		assertThat(XhtmlEntities.size()).isEqualTo(248);
	}

	@Test
	void anUndeclaredReferenceIsRefused() {
		assertThatThrownBy(() -> parse("<p>\n  <d>&bogus;</d></p>")).isInstanceOf(XmlParser.Malformed.class)
			.hasMessage("could not resolve entity named 'bogus' (line 2, column 6)");
		assertThatThrownBy(() -> parse("<p>a & b</p>")).isInstanceOf(XmlParser.Malformed.class)
			.hasMessageStartingWith("entity reference names can not start with character ' '");
	}

	@Test
	void aDoctypeIsSkippedAndWhatItDeclaresIsNotUsed() throws XmlParser.Malformed {
		String doctype = "<!DOCTYPE p [ <!ENTITY v \"9\"> <!-- ] > --> <!ELEMENT p (#PCDATA)> ]>";

		assertThat(parse(doctype + "<p>x</p>").text()).isEqualTo("x");
		assertThatThrownBy(() -> parse(doctype + "<p>&v;</p>")).isInstanceOf(XmlParser.Malformed.class)
			.hasMessageStartingWith("could not resolve entity named 'v'");
	}

	@Test
	void theEncodingComesFromTheByteOrderMarkThenTheDeclaration() throws XmlParser.Malformed {
		byte[] latin1 = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><p>café</p>"
			.getBytes(StandardCharsets.ISO_8859_1);
		byte[] utf16 = "﻿<?xml version=\"1.0\" encoding=\"UTF-16\"?><p>é</p>".getBytes(StandardCharsets.UTF_16LE);
		byte[] utf8Bom = "﻿<p>é</p>".getBytes(StandardCharsets.UTF_8);

		assertThat(XmlParser.parse(latin1).text()).isEqualTo("café");
		assertThat(XmlParser.parse(utf16).text()).isEqualTo("é");
		assertThat(XmlParser.parse(utf8Bom).text()).isEqualTo("é");
		assertThatThrownBy(() -> parse("<?xml version=\"1.0\" encoding=\"NO-SUCH-CHARSET\"?><p/>"))
			.isInstanceOf(XmlParser.Malformed.class)
			.hasMessage("unsupported encoding 'NO-SUCH-CHARSET'");
	}

	@Test
	void lineEndsAreNormalized() throws XmlParser.Malformed {
		assertThat(parse("<p>a\r\nb\rc</p>").text()).isEqualTo("a\nb\nc");
	}

	@Test
	void whitespaceBeforeTheDeclarationIsAcceptedAndNothingAfterTheRootIsRead() throws XmlParser.Malformed {
		// Both measured on Maven 3.9.16: a POM opening with a blank line reads, and the
		// reader returns at the root's end tag.
		XmlElement root = parse("\n  <?xml version=\"1.0\"?>\n<p a='1' b=\"&amp;\"><c/></p> trailing <garbage");

		assertThat(root.name()).isEqualTo("p");
		assertThat(root.children()).extracting(XmlElement::name).containsExactly("c");
	}

	@Test
	void namesAreTakenAsWrittenWithoutNamespaces() throws XmlParser.Malformed {
		XmlElement root = parse("<pom:project xmlns:pom=\"urn:x\"><pom:version>1</pom:version></pom:project>");

		assertThat(root.name()).isEqualTo("pom:project");
		assertThat(root.child("pom:version")).isNotNull();
		assertThat(root.child("version")).isNull();
	}

	@Test
	void malformedStructureIsRefusedWithItsPosition() {
		assertThatThrownBy(() -> parse("<a><b></c></a>")).isInstanceOf(XmlParser.Malformed.class)
			.hasMessage("end tag name </c> must match start tag name <b> from line 1 (line 1, column 11)");
		assertThatThrownBy(() -> parse("<a>\n<b>")).isInstanceOf(XmlParser.Malformed.class)
			.hasMessageStartingWith(
					"no more data available - expected end tag </b> to close start tag <b> from line 2");
		assertThatThrownBy(() -> parse("<a><!-- x -- y --></a>")).isInstanceOf(XmlParser.Malformed.class)
			.hasMessageStartingWith("in comment after two dashes (--) next character must be > not ' '");
		assertThatThrownBy(() -> parse("<a><?xml version=\"1.0\"?></a>")).isInstanceOf(XmlParser.Malformed.class)
			.hasMessageStartingWith("processing instruction can not have PITarget with reserved xml name");
		assertThatThrownBy(() -> parse("<a b=\"1\" b=\"2\"/>")).isInstanceOf(XmlParser.Malformed.class)
			.hasMessageStartingWith("duplicated attribute b in <a>");
		assertThatThrownBy(() -> parse("text<a/>")).isInstanceOf(XmlParser.Malformed.class)
			.hasMessageStartingWith("only whitespace content allowed before start tag and not 't'");
	}

	@Test
	void deepNestingCostsNoStack() throws XmlParser.Malformed {
		int depth = 100_000;
		String xml = "<e>".repeat(depth) + "</e>".repeat(depth);

		XmlElement element = parse(xml);
		int seen = 1;
		while (!element.children().isEmpty()) {
			element = element.children().get(0);
			seen++;
		}
		assertThat(seen).isEqualTo(depth);
	}

}
