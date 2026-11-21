package am.ik.servlet.httpserver;

import java.util.Locale;

/**
 * The media type of a file name, for the extensions a web application serves most.
 */
final class MimeTypes {

	private MimeTypes() {
	}

	static String of(String file) {
		int dot = file.lastIndexOf('.');
		if (dot < 0 || dot < file.lastIndexOf('/')) {
			return null;
		}
		switch (file.substring(dot + 1).toLowerCase(Locale.ROOT)) {
			case "html":
			case "htm":
				return "text/html";
			case "css":
				return "text/css";
			case "js":
			case "mjs":
				return "text/javascript";
			case "json":
				return "application/json";
			case "xml":
				return "application/xml";
			case "txt":
				return "text/plain";
			case "csv":
				return "text/csv";
			case "svg":
				return "image/svg+xml";
			case "png":
				return "image/png";
			case "jpg":
			case "jpeg":
				return "image/jpeg";
			case "gif":
				return "image/gif";
			case "webp":
				return "image/webp";
			case "ico":
				return "image/x-icon";
			case "pdf":
				return "application/pdf";
			case "wasm":
				return "application/wasm";
			case "woff2":
				return "font/woff2";
			default:
				return null;
		}
	}

}
