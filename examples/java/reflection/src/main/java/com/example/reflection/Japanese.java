package com.example.reflection;

// A greeting the program finds by its name: it names this class nowhere.
public class Japanese {

    private final String name;

    public Japanese(String name) {
        this.name = name;
    }

    public String greet() {
        return "こんにちは、" + name + "さん";
    }
}
