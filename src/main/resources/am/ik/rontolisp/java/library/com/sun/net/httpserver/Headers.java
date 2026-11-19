// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The fields of a request or a response: a map of names to their values, a name
// kept with its first letter in upper case and the others in lower, so that it is
// found whatever its case. What a name and a value may hold, and the words of each
// refusal, are a JDK 25's answers to a probe.
package com.sun.net.httpserver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import rontolisp.internal.HttpServing;

public class Headers implements Map<String, List<String>> {

    private final HashMap<String, List<String>> map = new HashMap<>();

    public Headers() {
    }

    public Headers(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            put(entry.getKey(), new ArrayList<String>(entry.getValue()));
        }
    }

    public static Headers of(String... headers) {
        if (headers.length % 2 != 0) {
            throw new IllegalArgumentException("wrong number, " + headers.length + ", of elements");
        }
        Headers made = new Headers();
        for (int i = 0; i < headers.length; i += 2) {
            if (headers[i] == null || headers[i + 1] == null) {
                throw new NullPointerException();
            }
            made.add(headers[i], headers[i + 1]);
        }
        return HttpServing.fixed(made);
    }

    public static Headers of(Map<String, List<String>> headers) {
        return HttpServing.fixed(new Headers(headers));
    }

    // The name as it is kept: its first letter in upper case, the others in lower.
    private static String kept(String key) {
        if (key == null) {
            throw new NullPointerException();
        }
        StringBuilder made = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '\r') {
                throw new IllegalArgumentException("Illegal CR found in header");
            }
            if (c == '\n') {
                throw new IllegalArgumentException("Illegal LF found in header");
            }
            if (i == 0 && c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            }
            else if (i > 0 && c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
            made.append(c);
        }
        return made.toString();
    }

    // A value holds a line end only as one folding it: CR, LF and a space or a tab.
    private static void checked(String value) {
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c == '\r') {
                if (i >= length - 2) {
                    throw new IllegalArgumentException("Illegal CR found in header");
                }
                if (value.charAt(i + 1) != '\n') {
                    throw new IllegalArgumentException("Illegal char found after CR in header");
                }
                char folded = value.charAt(i + 2);
                if (folded != ' ' && folded != '\t') {
                    throw new IllegalArgumentException("No whitespace found after CRLF in header");
                }
                i += 2;
            }
            else if (c == '\n') {
                throw new IllegalArgumentException("Illegal LF found in header");
            }
        }
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public boolean containsKey(Object key) {
        return map.containsKey(kept((String) key));
    }

    public boolean containsValue(Object value) {
        return map.containsValue(value);
    }

    public List<String> get(Object key) {
        return map.get(kept((String) key));
    }

    public String getFirst(String key) {
        List<String> values = map.get(kept(key));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public List<String> put(String key, List<String> value) {
        for (String each : value) {
            checked(each);
        }
        return map.put(kept(key), value);
    }

    public void add(String key, String value) {
        String name = kept(key);
        checked(value);
        List<String> values = map.get(name);
        if (values == null) {
            values = new ArrayList<String>();
            map.put(name, values);
        }
        values.add(value);
    }

    public void set(String key, String value) {
        ArrayList<String> values = new ArrayList<String>();
        values.add(value);
        put(key, values);
    }

    public List<String> remove(Object key) {
        return map.remove(kept((String) key));
    }

    public void putAll(Map<? extends String, ? extends List<String>> t) {
        for (Map.Entry<? extends String, ? extends List<String>> entry : t.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    public void clear() {
        map.clear();
    }

    public Set<String> keySet() {
        return map.keySet();
    }

    public Collection<List<String>> values() {
        return map.values();
    }

    public Set<Map.Entry<String, List<String>>> entrySet() {
        return map.entrySet();
    }

    public boolean equals(Object o) {
        return map.equals(o);
    }

    public int hashCode() {
        return map.hashCode();
    }

    public String toString() {
        return "Headers { " + map + " }";
    }
}
