package am.ik.maven;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

/**
 * A plugin's or an execution's {@code <configuration>}, element by element, as Maven's
 * reader builds it (plexus-utils' {@code Xpp3Dom}): a leaf's value is its text trimmed --
 * {@code null} for an empty-element tag ({@code <a/>}), the empty string for
 * {@code <a></a>} -- and an element with children has none.
 *
 * @param name the element name
 * @param value the leaf value, {@code null} for an empty-element tag or an element with
 * children
 * @param attributes the attributes, in document order
 * @param children the child elements, in document order
 */
public record ConfigurationNode(String name, @Nullable String value, Map<String, String> attributes,
		List<ConfigurationNode> children) {

	/**
	 * Copies the attributes and children.
	 * @param name the element name
	 * @param value the leaf value
	 * @param attributes the attributes
	 * @param children the child elements
	 */
	public ConfigurationNode {
		attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
		children = List.copyOf(children);
	}

	/**
	 * The first child of a name, {@code Xpp3Dom.getChild}.
	 * @param childName the name
	 * @return the child, or {@code null}
	 */
	public @Nullable ConfigurationNode child(String childName) {
		for (ConfigurationNode child : this.children) {
			if (child.name.equals(childName)) {
				return child;
			}
		}
		return null;
	}

	/** The node a parsed element is, as {@code Xpp3DomBuilder.build(parser, true)}. */
	static ConfigurationNode of(XmlElement element) {
		List<ConfigurationNode> children = new ArrayList<>();
		for (XmlElement child : element.children()) {
			children.add(of(child));
		}
		String value = null;
		if (children.isEmpty() && !element.empty()) {
			value = element.text().trim();
		}
		return new ConfigurationNode(element.name(), value, element.attributes(), children);
	}

	/** Every value and attribute value through a function, {@code ${...}} expansion. */
	ConfigurationNode map(UnaryOperator<String> function) {
		Map<String, String> mapped = new LinkedHashMap<>();
		for (Map.Entry<String, String> attribute : this.attributes.entrySet()) {
			mapped.put(attribute.getKey(), function.apply(attribute.getValue()));
		}
		List<ConfigurationNode> children = new ArrayList<>();
		for (ConfigurationNode child : this.children) {
			children.add(child.map(function));
		}
		return new ConfigurationNode(this.name, this.value == null ? null : function.apply(this.value), mapped,
				children);
	}

	/**
	 * Merges a recessive tree into a dominant one, {@code Xpp3Dom.mergeXpp3Dom}: the
	 * dominant's {@code combine.self="override"} keeps it whole; otherwise an empty value
	 * and each attribute the dominant lacks come from the recessive, and the recessive's
	 * children are appended before the dominant's ({@code combine.children="append"}) or
	 * merged by name, the n-th recessive child of a name into the n-th dominant one (a
	 * dominant child marked {@code combine.self="remove"} is dropped instead), a
	 * recessive child with no dominant child of its name appended, one beyond their count
	 * dropped.
	 * @param dominant the dominant tree, or {@code null}
	 * @param recessive the recessive tree, or {@code null}
	 * @return the merged tree
	 */
	static @Nullable ConfigurationNode merge(@Nullable ConfigurationNode dominant,
			@Nullable ConfigurationNode recessive) {
		if (dominant == null) {
			return recessive;
		}
		if (recessive == null || "override".equals(dominant.attributes.get("combine.self"))) {
			return dominant;
		}
		String value = dominant.value;
		if (isBlank(value) && !isBlank(recessive.value)) {
			value = recessive.value;
		}
		Map<String, String> attributes = new LinkedHashMap<>(dominant.attributes);
		for (Map.Entry<String, String> attribute : recessive.attributes.entrySet()) {
			if (isBlank(attributes.get(attribute.getKey()))) {
				attributes.put(attribute.getKey(), attribute.getValue());
			}
		}
		List<ConfigurationNode> children = new ArrayList<>(dominant.children);
		if (!recessive.children.isEmpty()) {
			// read after the recessive's attributes joined, as Xpp3Dom reads it
			if ("append".equals(attributes.get("combine.children"))) {
				children = new ArrayList<>(recessive.children);
				children.addAll(dominant.children);
			}
			else {
				children = mergeChildren(dominant.children, recessive.children);
			}
		}
		return new ConfigurationNode(dominant.name, value, attributes, children);
	}

	private static List<ConfigurationNode> mergeChildren(List<ConfigurationNode> dominant,
			List<ConfigurationNode> recessive) {
		// the positions of the dominant children of each name the recessive also has
		Map<String, Iterator<Integer>> common = new HashMap<>();
		for (ConfigurationNode child : recessive) {
			if (common.containsKey(child.name)) {
				continue;
			}
			List<Integer> positions = new ArrayList<>();
			for (int i = 0; i < dominant.size(); i++) {
				if (dominant.get(i).name.equals(child.name)) {
					positions.add(i);
				}
			}
			if (!positions.isEmpty()) {
				common.put(child.name, positions.iterator());
			}
		}
		List<@Nullable ConfigurationNode> merged = new ArrayList<>(dominant);
		List<ConfigurationNode> appended = new ArrayList<>();
		for (ConfigurationNode child : recessive) {
			Iterator<Integer> positions = common.get(child.name);
			if (positions == null) {
				appended.add(child);
			}
			else if (positions.hasNext()) {
				int position = positions.next();
				ConfigurationNode target = merged.get(position);
				if (target != null && "remove".equals(target.attributes.get("combine.self"))) {
					merged.set(position, null);
				}
				else {
					merged.set(position, merge(target, child));
				}
			}
		}
		List<ConfigurationNode> result = new ArrayList<>();
		for (ConfigurationNode child : merged) {
			if (child != null) {
				result.add(child);
			}
		}
		result.addAll(appended);
		return result;
	}

	private static boolean isBlank(@Nullable String value) {
		return value == null || value.trim().isEmpty();
	}

}
