package org.stackdocs.app.types;

/** Where the original opens: Stack's own viewer or another app (by package). */
public final class Opener {
    public static final Opener STACK_VIEWER = new Opener(null);
    public final String packageName;

    private Opener(String packageName) { this.packageName = packageName; }
    public static Opener app(String packageName) { return new Opener(packageName); }
    public boolean isStackViewer() { return packageName == null; }
}
