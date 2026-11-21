package am.ik.servlet.httpserver;

import java.util.EnumSet;

import jakarta.servlet.DispatcherType;

/**
 * One mapping of a filter: the kinds of dispatch it takes part in and either the URL
 * patterns or the servlet names it is applied to (Servlet 6.1, 6.2.4).
 */
final class FilterMapping {

	final FilterEntry filter;

	final String[] urlPatterns;

	final String[] servletNames;

	private final EnumSet<DispatcherType> dispatcherTypes;

	FilterMapping(FilterEntry filter, EnumSet<DispatcherType> dispatcherTypes, String[] urlPatterns,
			String[] servletNames) {
		this.filter = filter;
		this.dispatcherTypes = dispatcherTypes == null || dispatcherTypes.isEmpty() ? EnumSet.of(DispatcherType.REQUEST)
				: EnumSet.copyOf(dispatcherTypes);
		this.urlPatterns = urlPatterns == null ? null : urlPatterns.clone();
		this.servletNames = servletNames == null ? null : servletNames.clone();
	}

	/**
	 * Whether this mapping applies its filter to a dispatch.
	 * @param type the kind of dispatch
	 * @param path the path inside the context, or null for a dispatch by name
	 * @param servletName the name of the servlet the dispatch ends at
	 * @return whether the filter is in the chain
	 */
	boolean applies(DispatcherType type, String path, String servletName) {
		if (!this.dispatcherTypes.contains(type)) {
			return false;
		}
		if (this.servletNames != null) {
			for (String name : this.servletNames) {
				if (name.equals("*") || name.equals(servletName)) {
					return true;
				}
			}
			return false;
		}
		if (path == null) {
			return false;
		}
		for (String pattern : this.urlPatterns) {
			if (matches(pattern, path)) {
				return true;
			}
		}
		return false;
	}

	private static boolean matches(String pattern, String path) {
		if (pattern.equals(path) || pattern.equals("/*")) {
			return true;
		}
		if (pattern.endsWith("/*")) {
			int prefix = pattern.length() - 2;
			return path.regionMatches(0, pattern, 0, prefix) && (path.length() == prefix || path.charAt(prefix) == '/');
		}
		if (pattern.startsWith("*.")) {
			int slash = path.lastIndexOf('/');
			int dot = path.lastIndexOf('.');
			return dot > slash && dot < path.length() - 1 && path.length() - dot == pattern.length() - 1
					&& path.endsWith(pattern.substring(1));
		}
		return false;
	}

}
