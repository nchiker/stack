package org.stackdocs.app;

import org.json.JSONArray;

/** P1 review checks that need only org.json: the placeholder locator a computer-only link gets (M4) passes the production LinkRecord validation. */
public final class LinkRulesHost {
    static int failures;
    static void check(boolean ok, String name) { if (!ok) { failures++; System.out.println("FAIL " + name); } }

    public static void main(String[] args) throws Exception {
        String placeholder = LinkBudget.PLACEHOLDER_LOCATORS;
        java.util.List<LinkRecord.Locator> locators = LinkRecord.parseLocators(placeholder);
        check(locators.size() == 1 && "owner".equals(locators.get(0).type) && "stack-sync".equals(locators.get(0).token), "the placeholder is exactly one owner locator");
        check(new JSONArray(placeholder).length() >= 1 && new JSONArray(placeholder).length() <= 8, "1 to 8 locators, as a backup requires");
        check(placeholder.equals("[{\"type\":\"owner\",\"token\":\"stack-sync\"}]"), "same text as the computer's PLACEHOLDER_LOCATORS");
        // The same text is accepted inside a whole record (what a restore of a backup parses).
        LinkRecord record = LinkRecord.parse("{\"contract\":1,\"owner\":\"app.stub.owner\",\"link_id\":\"" + java.util.UUID.randomUUID() + "\",\"kind\":\"doc\",\"title\":\"T\","
            + "\"mime\":\"application/x-amipro-document\",\"modified\":1,\"hash\":\"" + "a".repeat(64) + "\",\"locators\":" + placeholder + "}");
        check(record.locators.size() == 1, "a record carrying the placeholder validates");
        if (failures != 0) { System.out.println(failures + " failures"); System.exit(1); }
        System.out.println("Link rules host checks passed");
    }
}
