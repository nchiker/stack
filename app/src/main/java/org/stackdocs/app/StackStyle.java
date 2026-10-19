package org.stackdocs.app;

/** Stack presentation shared by imported and locally created categories. */
final class StackStyle {
    private static final int NEUTRAL_DEFAULT = 0xFFE4EDE2;
    private static final int[] PASTELS = {
        0xFFB5E2ED, 0xFFB5DCC1, 0xFFF7E1A3, 0xFFF2C5DC,
        0xFFB9DCED, 0xFFDDC4ED, 0xFFF4D6BB, 0xFFF0BFC6
    };

    private StackStyle() {}

    static int ringColor(StackCategory category) {
        // Match the same identity/name as the glyph, including imported UUIDs.
        switch (Icons.category(category)) {
            case BILLS: return 0xFFB5E2ED;
            case BANKING: return 0xFFB5DCC1;
            case IDS: return 0xFFF7E1A3;
            case HOUSE: return 0xFFF2C5DC;
            case MEDICAL: return 0xFFB9DCED;
            case RECEIPTS: return 0xFFDDC4ED;
            case STARRED: return 0xFFF4D6BB;
            default:
                // The migration used this neutral default for unknown Google
                // palette codes. Give those stacks a stable local color while
                // retaining any explicitly stored custom color.
                return category.color == NEUTRAL_DEFAULT
                        ? PASTELS[Math.floorMod(category.id.hashCode(), PASTELS.length)]
                        : category.color;
        }
    }
}
