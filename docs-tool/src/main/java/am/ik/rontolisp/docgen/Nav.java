package am.ik.rontolisp.docgen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *         subpages:
 *           - file: reference/uiop/os.md
 *             title: uiop/os
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
	 * @param subpages pages that belong to this one and are reachable only THROUGH it:
	 * they are rendered, chained to each other with previous/next links and given a back
	 * link to this page, but they are NOT sidebar rows. The sidebar carries one row per
	 * topic, so a page whose sub-pages are a breakdown of that one topic (the uiop
	 * sub-package pages under "The uiop Package") lists them here instead of spilling
	 * them into the section
	 */
	public record Page(String file, String title, List<Page> subpages) {

		public Page(String file, String title) {
			this(file, title, List.of());
		}
	}

	/**
	 * Returns every sidebar page in document order (used for prev/next navigation).
	 * Sub-pages are deliberately absent: they are neither sidebar rows nor stops on the
	 * top-level previous/next chain, and are rendered from their parent instead.
	 */
	public List<Page> flatPages() {
		List<Page> all = new ArrayList<>();
		for (Section section : this.sections) {
			all.addAll(section.pages());
		}
		return all;
	}

	/**
	 * Finds a page by its Markdown file path, searching top-level pages and every nesting
	 * of {@code subpages} -- used to resolve a catalog category's {@code index_page} for
	 * its title and position, whether that page is a sidebar row or a subpage.
	 */
	public Optional<Page> findPage(String file) {
		for (Section section : this.sections) {
			Optional<Page> found = findPage(section.pages(), file);
			if (found.isPresent()) {
				return found;
			}
		}
		return Optional.empty();
	}

	private static Optional<Page> findPage(List<Page> pages, String file) {
		for (Page page : pages) {
			if (page.file().equals(file)) {
				return Optional.of(page);
			}
			Optional<Page> found = findPage(page.subpages(), file);
			if (found.isPresent()) {
				return found;
			}
		}
		return Optional.empty();
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
					sections.add(new Section(sectionTitle, parsePages(sectionMap.get("pages"))));
				}
			}
			return new Nav(title, langName, sections);
		}
	}

	/** Parses a {@code pages:} / {@code subpages:} list; sub-pages nest arbitrarily. */
	@SuppressWarnings("unchecked")
	private static List<Page> parsePages(Object rawPages) {
		List<Page> pages = new ArrayList<>();
		if (rawPages instanceof List<?> pageList) {
			for (Object rawPage : pageList) {
				Map<String, Object> pageMap = (Map<String, Object>) rawPage;
				String file = String.valueOf(pageMap.get("file"));
				String title = String.valueOf(pageMap.getOrDefault("title", file));
				pages.add(new Page(file, title, parsePages(pageMap.get("subpages"))));
			}
		}
		return pages;
	}

}
