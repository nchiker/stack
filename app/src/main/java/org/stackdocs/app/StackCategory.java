package org.stackdocs.app;

final class StackCategory {
    final String id;
    final String name;
    final int color;
    final boolean visible;
    final int count;

    StackCategory(String id, String name, int color, boolean visible, int count) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.visible = visible;
        this.count = count;
    }
}
