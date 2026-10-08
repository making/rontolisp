package am.ik.maven;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * An element of a parsed POM or {@code settings.xml}: its name as written (no namespace
 * processing, as Maven's reader), the character data directly inside it -- text, CDATA
 * and resolved references, comments and processing instructions dropped -- and its child
 * elements in document order.
 *
 * @param name the element name, prefix included
 * @param text the element's own character data, untrimmed
 * @param children the child elements
 * @param line the line the start tag is on, 1-based
 */
record XmlElement(String name, String text, List<XmlElement> children, int line) {

	/**
	 * Copies the children.
	 * @param name the element name
	 * @param text the character data
	 * @param children the child elements
	 * @param line the start tag's line
	 */
	XmlElement {
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
