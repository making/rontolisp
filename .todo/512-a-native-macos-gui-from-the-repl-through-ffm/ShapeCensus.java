import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.*;

/** How many distinct FFM downcall SHAPES does a real AppKit surface need? */
public class ShapeCensus {
	static final Linker K = Linker.nativeLinker();
	static final AddressLayout P = ValueLayout.ADDRESS;
	static final Arena A = Arena.global();

	/** ObjC type encoding -> the FFM shape letter, or null when out of scope */
	static String simplify(String t) {
		char c = t.charAt(0);
		return switch (c) {
			case '@', '#', ':', '*', '^' -> "P";
			case 'q', 'Q', 'l', 'L' -> "J";
			case 'i', 'I', 's', 'S' -> "I";
			case 'd' -> "D";
			case 'f' -> "F";
			case 'B', 'c', 'C' -> "B";
			case 'v' -> "V";
			case '{' -> "S(" + t.replaceAll("[^df]", "").length() + t.replaceAll("[^dfqQ]", "").replaceAll("[df]", "") + ")";
			default -> null;
		};
	}

	/** split an encoding like "@68@0:8{CGRect=...}16Q48Q56B64" into return + argument types */
	static List<String> split(String e) {
		List<String> out = new ArrayList<>();
		int i = 0;
		while (i < e.length()) {
			char c = e.charAt(i);
			if (Character.isDigit(c)) { i++; continue; }
			if (c == 'r' || c == 'n' || c == 'N' || c == 'o' || c == 'O' || c == 'R' || c == 'V') { i++; continue; }
			if (c == '{' || c == '(' || c == '[') {
				char close = c == '{' ? '}' : c == '(' ? ')' : ']';
				int depth = 0, j = i;
				for (; j < e.length(); j++) {
					if (e.charAt(j) == c) depth++;
					else if (e.charAt(j) == close && --depth == 0) break;
				}
				out.add(e.substring(i, Math.min(j + 1, e.length())));
				i = j + 1;
			} else if (c == '^') {
				out.add("^"); i += 2;
			} else { out.add(String.valueOf(c)); i++; }
		}
		return out;
	}

	public static void main(String[] a) throws Throwable {
		SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", A);
		SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit", A);
		MethodHandle getClass_ = K.downcallHandle(objc.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(P, P));
		MethodHandle copyList = K.downcallHandle(objc.find("class_copyMethodList").orElseThrow(), FunctionDescriptor.of(P, P, P));
		MethodHandle enc = K.downcallHandle(objc.find("method_getTypeEncoding").orElseThrow(), FunctionDescriptor.of(P, P));
		MethodHandle mName = K.downcallHandle(objc.find("method_getName").orElseThrow(), FunctionDescriptor.of(P, P));
		MethodHandle selName = K.downcallHandle(objc.find("sel_getName").orElseThrow(), FunctionDescriptor.of(P, P));

		String[] klasses = { "NSApplication", "NSWindow", "NSView", "NSControl", "NSButton", "NSTextField",
				"NSSlider", "NSMenu", "NSMenuItem", "NSColor", "NSImage", "NSImageView", "NSStackView",
				"NSScrollView", "NSTableView", "NSBox", "NSProgressIndicator", "NSString", "NSArray",
				"NSDictionary", "NSNumber", "NSEvent", "NSScreen", "NSFont", "NSBezierPath" };
		Map<String, Integer> shapes = new TreeMap<>();
		int total = 0, skipped = 0;
		for (String k : klasses) {
			MemorySegment c = (MemorySegment) getClass_.invokeExact(A.allocateFrom(k));
			if (c.address() == 0) continue;
			try (Arena t = Arena.ofConfined()) {
				MemorySegment n = t.allocate(ValueLayout.JAVA_INT);
				MemorySegment list = (MemorySegment) copyList.invokeExact(c, n);
				int count = n.get(ValueLayout.JAVA_INT, 0);
				MemorySegment ms = list.reinterpret((long) count * 8);
				for (int i = 0; i < count; i++) {
					MemorySegment m = ms.getAtIndex(P, i);
					MemorySegment e = (MemorySegment) enc.invokeExact(m);
					if (e.address() == 0) continue;
					String s = e.reinterpret(Long.MAX_VALUE).getString(0);
					List<String> parts = split(s);
					StringBuilder sb = new StringBuilder();
					boolean bad = parts.isEmpty();
					for (String p : parts) {
						String q = simplify(p);
						if (q == null) { bad = true; break; }
						sb.append(sb.isEmpty() ? "" : ",").append(q);
					}
					total++;
					if (bad) { skipped++; continue; }
					shapes.merge(sb.toString(), 1, Integer::sum);
				}
			}
		}
		System.out.println("methods seen: " + total + "   encodings not mapped: " + skipped);
		System.out.println("distinct shapes: " + shapes.size());
		List<Map.Entry<String, Integer>> top = new ArrayList<>(shapes.entrySet());
		top.sort((x, y) -> y.getValue() - x.getValue());
		int cum = 0, mapped = total - skipped;
		for (int i = 0; i < top.size(); i++) {
			cum += top.get(i).getValue();
			if (i < 20) System.out.printf("%3d  %-34s %5d   cumulative %.1f%%%n", i + 1, top.get(i).getKey(), top.get(i).getValue(), 100.0 * cum / mapped);
			if (i == 24) System.out.printf("     top 25 shapes cover %.1f%% of the mapped methods%n", 100.0 * cum / mapped);
			if (i == 49) System.out.printf("     top 50 shapes cover %.1f%%%n", 100.0 * cum / mapped);
			if (i == 99) System.out.printf("     top 100 shapes cover %.1f%%%n", 100.0 * cum / mapped);
		}
	}
}
