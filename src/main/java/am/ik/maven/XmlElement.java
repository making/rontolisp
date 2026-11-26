package am.ik.maven;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * An element of a parsed POM or {@code settings.xml}: its name as written (no namespace
 * processing, as Maven's reader), the character data directly inside it -- text, CDATA
 * and resolved references, comments and processing instructions dropped -- its attributes
 * and its child elements in document order.
 *
 * @param name the element name, prefix included
 * @param text the element's own character data, untrimmed
 * @param attributes the attributes in document order, references resolved
 * @param children the child elements
 * @param empty whether it was written as an empty-element tag ({@code <a/>})
 * @param line the line the start tag is on, 1-based
 * @param textAt how many child elements precede the first character of its data that is
 * not XML whitespace, {@code -1} when all of it is whitespace: where a pull parser asking
 * for the next tag meets text
 */
record XmlElement(String name, String text, Map<String, String> attributes, List<XmlElement> children, boolean empty,
		int line, int textAt) {

	/**
	 * Copies the attributes and the children.
	 * @param name the element name
	 * @param text the character data
	 * @param attributes the attributes
	 * @param children the child elements
	 * @param empty whether it was an empty-element tag
	 * @param line the start tag's line
	 * @param textAt the child count before the first non-whitespace data, or {@code -1}
	 */
	XmlElement {
		attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
		children = List.copyOf(children);
	}

	/**
	 * Returns the first child of the given name.
	 * @param childName the name
	 * @return the child, or {@code null}
	 */
	@Nullable XmlElement child(String childName) {
		for (XmlElement child : this.children) {
			if (child.name.equals(childName)) {
				return child;
			}
		}
		return null;
	}

	/**
	 * Returns the children of the given name, in document order.
	 * @param childName the name
	 * @return the children
	 */
	List<XmlElement> children(String childName) {
		List<XmlElement> matching = new ArrayList<>();
		for (XmlElement child : this.children) {
			if (child.name.equals(childName)) {
				matching.add(child);
			}
		}
		return matching;
	}

}
