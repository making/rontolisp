package am.ik.servlet.httpserver;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The media type of a file name, for the extensions a web application serves most.
 */
final class MimeTypes {

	private MimeTypes() {
	}

	static @Nullable String of(String file) {
		int dot = file.lastIndexOf('.');
		if (dot < 0 || dot < file.lastIndexOf('/')) {
			return null;
		}
		return switch (file.substring(dot + 1).toLowerCase(Locale.ROOT)) {
			case "html", "htm" -> "text/html";
			case "css" -> "text/css";
			case "js", "mjs" -> "text/javascript";
			case "json" -> "application/json";
			case "xml" -> "application/xml";
			case "txt" -> "text/plain";
			case "csv" -> "text/csv";
			case "svg" -> "image/svg+xml";
			case "png" -> "image/png";
			case "jpg", "jpeg" -> "image/jpeg";
			case "gif" -> "image/gif";
			case "webp" -> "image/webp";
			case "avif" -> "image/avif";
			case "ico" -> "image/x-icon";
			case "pdf" -> "application/pdf";
			case "zip" -> "application/zip";
			case "wasm" -> "application/wasm";
			case "woff" -> "font/woff";
			case "woff2" -> "font/woff2";
			case "ttf" -> "font/ttf";
			case "otf" -> "font/otf";
			case "mp3" -> "audio/mpeg";
			case "mp4" -> "video/mp4";
			case "webm" -> "video/webm";
			default -> null;
		};
	}

}
