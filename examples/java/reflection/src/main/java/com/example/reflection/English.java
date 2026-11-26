package com.example.reflection;

// A greeting the program finds by its name: it names this class nowhere.
public class English {

    private final String name;

    public English(String name) {
        this.name = name;
    }

    public String greet() {
        return "Hello, " + name + "!";
    }
}
