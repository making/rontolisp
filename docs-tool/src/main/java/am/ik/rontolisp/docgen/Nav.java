package am.ik.rontolisp.docgen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * Navigation model for one language, loaded from a {@code nav.yaml} file. The file lists
 * the documentation structure as ordered sections of pages; the flat page order also
 * drives the previous/next links rendered on each page.
 *
 * <p>
 * Expected {@code nav.yaml} shape:
 *
 * <pre>
 * title: rontolisp
 * lang_name: English
 * sections:
 *   - title: Getting Started
 *     pages:
 *       - file: index.md
 *         title: Introduction
 * </pre>
 */
public record Nav(String title, String langName, List<Section> sections) {

	/** A titled group of pages shown as one block in the sidebar. */
	public record Section(String title, List<Page> pages) {
	}

	/**
	 * A single documentation page.
	 *
	 * @param file the Markdown source path relative to the language directory (e.g.
	 * {@code reference/data-types.md})
	 * @param title the human-readable title shown in the sidebar and {@code <title>}
	 */
	public record Page(String file, String title) {
	}

	/** Returns every page in document order (used for prev/next navigation). */
	public List<Page> flatPages() {
		List<Page> all = new ArrayList<>();
		for (Section section : this.sections) {
			all.addAll(section.pages());
		}
		return all;
	}

	/** Loads a {@code nav.yaml} into a {@link Nav}. */
	@SuppressWarnings("unchecked")
	public static Nav load(Path navYaml) throws IOException {
		try (InputStream in = Files.newInputStream(navYaml)) {
			Map<String, Object> root = new Yaml().load(in);
			if (root == null) {
				throw new IOException("Empty nav file: " + navYaml);
			}
			String title = String.valueOf(root.getOrDefault("title", "Documentation"));
			String langName = root.get("lang_name") == null ? null : String.valueOf(root.get("lang_name"));
			List<Section> sections = new ArrayList<>();
			Object rawSections = root.get("sections");
			if (rawSections instanceof List<?> sectionList) {
				for (Object rawSection : sectionList) {
					Map<String, Object> sectionMap = (Map<String, Object>) rawSection;
					String sectionTitle = String.valueOf(sectionMap.getOrDefault("title", ""));
					List<Page> pages = new ArrayList<>();
					Object rawPages = sectionMap.get("pages");
					if (rawPages instanceof List<?> pageList) {
						for (Object rawPage : pageList) {
							Map<String, Object> pageMap = (Map<String, Object>) rawPage;
							String file = String.valueOf(pageMap.get("file"));
							String pageTitle = String.valueOf(pageMap.getOrDefault("title", file));
							pages.add(new Page(file, pageTitle));
						}
					}
					sections.add(new Section(sectionTitle, pages));
				}
			}
			return new Nav(title, langName, sections);
		}
	}

}
