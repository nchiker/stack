#!/usr/bin/env python3
"""Run production stack icon/color regressions using an Android SDK jar."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class StackIconTests(unittest.TestCase):
    def test_imported_and_remapped_categories(self):
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        if not sdk:
            for line in (ROOT / 'local.properties').read_text().splitlines():
                if line.startswith('sdk.dir='):
                    sdk = line[len('sdk.dir='):]
        self.assertTrue(sdk, 'Set ANDROID_HOME to an installed Android SDK')
        android = Path(sdk) / 'platforms/android-36/android.jar'
        self.assertTrue(android.is_file(), 'Android 36 SDK jar is required')
        with tempfile.TemporaryDirectory(prefix='stack-icon-check-') as directory:
            work = Path(directory)
            runner = work / 'StackIconChecks.java'
            runner.write_text('''package org.stackdocs.app;
import java.util.Locale;
public class StackIconChecks {
    static void expect(String id, String name, Icons.Symbol expected) {
        StackCategory stack = new StackCategory(id, name, 0xffaabbcc, true, 3);
        if (Icons.category(stack) != expected)
            throw new AssertionError("Wrong icon for " + id + "/" + name);
        StackStyle.ringColor(stack);
        if (!stack.id.equals(id) || !stack.name.equals(name) || stack.count != 3
                || stack.color != 0xffaabbcc || !stack.visible)
            throw new AssertionError("Icon resolution changed stack data");
    }
    public static void main(String[] args) {
        String[] names = {"Bills", "Banking", "IDs", "House", "Medical", "Receipts", "Starred"};
        Icons.Symbol[] icons = {Icons.Symbol.BILLS, Icons.Symbol.BANKING, Icons.Symbol.IDS,
            Icons.Symbol.HOUSE, Icons.Symbol.MEDICAL, Icons.Symbol.RECEIPTS, Icons.Symbol.STARRED};
        int[] colors = {0xFFB5E2ED, 0xFFB5DCC1, 0xFFF7E1A3, 0xFFF2C5DC,
            0xFFB9DCED, 0xFFDDC4ED, 0xFFF4D6BB};
        Locale original = Locale.getDefault();
        try {
            // Case matching must also work on a phone using a Turkish locale.
            for (Locale locale : new Locale[]{Locale.US, Locale.forLanguageTag("tr-TR")}) {
                Locale.setDefault(locale);
                for (int n = 0; n < names.length; n++) {
                    // The source backup and additive ID collisions both use opaque IDs.
                    expect("legacy-opaque-" + n, names[n], icons[n]);
                    expect("restore-remapped-" + n, "  " + names[n].toUpperCase(Locale.ROOT) + "  ", icons[n]);
                    expect(names[n].toLowerCase(Locale.ROOT), "Renamed personal stack", icons[n]);
                    StackCategory imported = new StackCategory("legacy-opaque-" + n, names[n], 0xFFE4EDE2, true, 3);
                    if (StackStyle.ringColor(imported) != colors[n])
                        throw new AssertionError("Imported stack lost standard ring color");
                    StackCategory renamed = new StackCategory(names[n].toLowerCase(Locale.ROOT), "Renamed", 0xFFE4EDE2, true, 3);
                    if (StackStyle.ringColor(renamed) != colors[n])
                        throw new AssertionError("Renamed starter lost standard ring color");
                }
            }
            expect("bills", "Medical", Icons.Symbol.BILLS);
            expect("opaque-custom", "Synthetic category", Icons.Symbol.FOLDER);
            expect("opaque-custom", "Bills for later", Icons.Symbol.FOLDER);
            expect("search", "Search", Icons.Symbol.FOLDER);
            java.util.Set<Integer> customColors = new java.util.HashSet<>();
            for (int n = 0; n < 32; n++) {
                StackCategory custom = new StackCategory("opaque-custom-" + n, "Custom", 0xFFE4EDE2, true, 0);
                int ring = StackStyle.ringColor(custom);
                customColors.add(ring);
                if (ring == 0xFFE4EDE2 || (ring >>> 24) != 255)
                    throw new AssertionError("Neutral custom category lacks an opaque pastel ring");
                StackCategory renamed = new StackCategory(custom.id, "Renamed", custom.color, false, 25);
                if (StackStyle.ringColor(renamed) != ring || custom.color != 0xFFE4EDE2)
                    throw new AssertionError("Custom color must be stable across rename, visibility and count changes without modifying storage");
            }
            if (customColors.size() < 4) throw new AssertionError("Custom defaults all use the same color");
            if (StackStyle.ringColor(new StackCategory("opaque-custom", "Custom", 0xFF123456, true, 0)) != 0xFF123456)
                throw new AssertionError("Explicit custom color was replaced");
            System.out.println("PASS: imported icons and ring colors, renamed starters, varied stable custom pastels, explicit colors, locale and data preservation");
        } finally { Locale.setDefault(original); }
    }
}
''')
            source = ROOT / 'app/src/main/java/org/stackdocs/app'
            subprocess.run(['javac', '-cp', str(android), '-d', str(work),
                            str(source / 'Icons.java'), str(source / 'StackCategory.java'),
                            str(source / 'StackStyle.java'),
                            str(runner)], check=True)
            subprocess.run(['java', '-cp', os.pathsep.join([str(work), str(android)]),
                            'org.stackdocs.app.StackIconChecks'], check=True)


if __name__ == '__main__':
    unittest.main()
