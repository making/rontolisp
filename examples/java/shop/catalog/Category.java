package shop.catalog;

public enum Category {
    OFFICE, HOME, BUNDLE, GARDEN;

    public String label() {
        return name().toLowerCase();
    }
}
