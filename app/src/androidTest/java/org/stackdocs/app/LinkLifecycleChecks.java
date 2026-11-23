package org.stackdocs.app;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Slice 4: link lifecycle. The stub owner (StubOwnerProvider, in the test APK, same signing key as Stack) answers `stat` and
 * `refresh` as a check dictates; `removed` goes through the real LinkProvider. Synthetic data only.
 */
final class LinkLifecycleChecks {
    private static StackApplication app;
    private static String stubOwner;

    private static void require(boolean ok, String what) { WorkflowRepositoryChecks.require(ok, what); }
    private static <T> T io(java.util.concurrent.Callable<T> work) throws Exception { return app.io.submit(work).get(); }
    private static String hash() { return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64); }

    private static String record(String owner, String linkId, String title, String hash) throws Exception {
        return new JSONObject().put("contract", 1).put("owner", owner).put("link_id", linkId).put("kind", "doc").put("title", title)
            .put("mime", "application/x-amipro-document").put("modified", 1_800_000_000_000L).put("hash", hash)
            .put("locators", new JSONArray().put(new JSONObject().put("type", "owner").put("token", "private/1"))).toString();
    }

    private static LinkStore.Link publish(String owner, String title, String hash) throws Exception {
        String linkId = UUID.randomUUID().toString();
        LinkStore.Result result = io(() -> app.library.links.publish(LinkRecord.parse(record(owner, linkId, title, hash)), "lifecycle check text", null));
        return io(() -> app.library.links.get(result.id));
    }

    /** The stub owner's settings; it runs in another process, so each change is pushed to it with a provider call. */
    static final class S {
        static Bundle stat, refresh; static long delay; static boolean fail;
        static void push() {
            Bundle extras = new Bundle();
            if (stat != null) extras.putBundle("stat", stat);
            if (refresh != null) extras.putBundle("refresh", refresh);
            extras.putLong("delay", delay); extras.putBoolean("fail", fail);
            app.getContentResolver().call(Uri.parse("content://" + stubOwner + ".stacklink"), "configure", null, extras);
        }
        static Bundle report() { return app.getContentResolver().call(Uri.parse("content://" + stubOwner + ".stacklink"), "report", null, null); }
    }

    private static String state(LinkStore.Link link) throws Exception { return io(() -> app.library.links.get(link.id)).state; }

    private static Bundle statReply(Object exists, Object hash, Object modified, Object locators, String error) {
        Bundle b = new Bundle();
        if (exists instanceof Boolean) b.putBoolean("exists", (Boolean) exists); else if (exists != null) b.putString("exists", exists.toString());
        if (hash != null) b.putString("hash", hash.toString());
        if (modified != null) b.putLong("modified", (Long) modified);
        if (locators != null) b.putString("locators", locators.toString());
        if (error != null) b.putString("error", error);
        return b;
    }

    private static String ownerLocators() throws Exception { return new JSONArray().put(new JSONObject().put("type", "owner").put("token", "moved/2")).toString(); }

    private static Bundle moved(String owner, String linkId, String uuid, String path) {
        try {
            String json = new JSONObject().put("contract", 1).put("owner", owner).put("link_id", linkId)
                .put("locators", new JSONArray().put(new JSONObject().put("type", "filen").put("uuid", uuid).put("path", path))).toString();
            return app.getContentResolver().call(Uri.parse("content://" + app.getPackageName() + ".link"), "moved", json, null);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static Bundle unshared(String owner, String linkId) {
        try {
            String json = new JSONObject().put("contract", 1).put("owner", owner).put("link_id", linkId).toString();
            return app.getContentResolver().call(Uri.parse("content://" + app.getPackageName() + ".link"), "unshared", json, null);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static Bundle removed(String owner, String linkId, String reason) {
        ContentResolver resolver = app.getContentResolver();
        try {
            String json = new JSONObject().put("contract", 1).put("owner", owner).put("link_id", linkId).put("reason", reason).toString();
            return resolver.call(Uri.parse("content://" + app.getPackageName() + ".link"), "removed", json, null);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    static void run(Context target, String testPackage, Consumer<String> mark) throws Exception {
        app = (StackApplication) target.getApplicationContext();
        stubOwner = testPackage;
        String me = target.getPackageName();
        try {
            S.push();
            require(LinkCallers.trustedOwnerProvider(target, stubOwner), "the stub owner's provider is trusted (served by its own package, same certificate)");
            require(!LinkCallers.trustedOwnerProvider(target, "no.such.owner") && !LinkCallers.trustedOwnerProvider(target, "android"), "a missing owner, or one that does not serve its authority, is not trusted");

            // stat: exists, changed hash, new locators.
            String h1 = hash(), h2 = hash();
            LinkStore.Link a = publish(stubOwner, "Lifecycle A", h1);
            final long stamp = Clock.now() - 1000;
            S.stat = statReply(true, h2, stamp, ownerLocators(), null); S.push();
            LinkChecker.Result r = LinkChecker.check(app, a);
            require(LinkStore.LIVE.equals(r.state) && r.changed && !r.busy, "stat with a new hash is live and reports the change: state=" + r.state + " changed=" + r.changed + " busy=" + r.busy + " calls=" + S.report().getInt("calls") + " last=" + S.report().getString("method") + " reply=" + S.stat);
            require("stat".equals(S.report().getString("method")) && a.linkId.equals(S.report().getString("argument")), "stat is called with the link id");
            LinkStore.Link after = io(() -> app.library.links.get(a.id));
            require(after.hash.equals(h1) && after.locators.contains("moved") && after.modified == 1_800_000_000_000L, "stat updates locators but never the stored hash or text, and leaves the saved copy's date alone while the owner's file differs");
            mark.accept("Lifecycle: stat live, changed hash, new locators");

            // refresh.
            S.refresh = new Bundle(); S.refresh.putBoolean("ok", true); S.push();
            require(LinkChecker.refresh(app, a) == null && "refresh".equals(S.report().getString("method")), "refresh ok");
            S.refresh = new Bundle(); S.refresh.putString("error", "protected"); S.push();
            require(LinkChecker.refresh(app, a).contains("password-protected"), "refresh of a protected file says so");
            mark.accept("Lifecycle: refresh");

            // Failures are only ever unreachable.
            Object[][] bad = {
                {statReply(null, null, null, null, "unreachable"), "an error reply"},
                {statReply("false", null, null, null, null), "exists that is text"},
                {statReply(null, h2, null, null, null), "exists missing"},
                {statReply(true, "not-a-hash", null, null, null), "a bad hash"},
                {statReply(false, null, null, null, "unreachable"), "exists:false together with an error"},
            };
            for (Object[] c : bad) {
                S.stat = (Bundle) c[0]; S.push();
                LinkChecker.Result x = LinkChecker.check(app, a);
                require(LinkStore.UNREACHABLE.equals(x.state) && LinkStore.UNREACHABLE.equals(state(a)), c[1] + " is unreachable, never missing");
            }
            S.stat = null; S.push();
            require(LinkStore.UNREACHABLE.equals(LinkChecker.check(app, a).state), "a null reply is unreachable");
            S.fail = true; S.push();
            require(LinkStore.UNREACHABLE.equals(LinkChecker.check(app, a).state), "a provider that throws is unreachable");
            S.fail = false; S.push();
            S.stat = statReply(true, h1, null, "[{\"type\":\"bogus\"}]", null); S.push();
            LinkStore.Link keep = io(() -> app.library.links.get(a.id));
            require(LinkStore.LIVE.equals(LinkChecker.check(app, a).state), "a reachable owner makes it live again");
            require(io(() -> app.library.links.get(a.id)).locators.equals(keep.locators), "invalid locators in a reply keep the old locators");
            mark.accept("Lifecycle: malformed and failed answers are unreachable");

            S.stat = statReply(true, h1, stamp, null, null); S.push();
            LinkChecker.check(app, a);
            require(io(() -> app.library.links.get(a.id)).modified == stamp, "with a matching hash the check moves the modified date");

            // exists:false -> missing, and back.
            S.stat = statReply(false, null, null, null, null); S.push();
            require(LinkStore.MISSING.equals(LinkChecker.check(app, a).state), "exists:false makes it missing");
            require(io(() -> app.library.links.list("lifecycle")).stream().anyMatch(l -> l.id.equals(a.id)), "a missing record is still listed and searchable");
            S.stat = statReply(true, h1, null, null, null); S.push();
            require(LinkStore.LIVE.equals(LinkChecker.check(app, a).state), "missing returns to live when the owner finds it");
            mark.accept("Lifecycle: missing and back");

            // A hung owner: time-boxed, late answer discarded, one call in flight, no stuck state.
            S.stat = statReply(false, null, null, null, null); S.push();
            S.delay = 7000; S.push();
            LinkStore.Link slow = a;
            java.util.concurrent.Future<LinkChecker.Result> hung = java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> LinkChecker.check(app, slow));
            Thread.sleep(500);
            LinkChecker.Result second = LinkChecker.check(app, a);
            require(second.busy, "a second call to an owner with a call still running is not started");
            LinkChecker.Result late = hung.get(10, java.util.concurrent.TimeUnit.SECONDS);
            require(LinkStore.UNREACHABLE.equals(late.state), "a call that takes too long is unreachable, not missing");
            Thread.sleep(3000);
            require(LinkStore.UNREACHABLE.equals(state(a)), "the late exists:false answer is thrown away");
            S.delay = 0; S.push(); S.stat = statReply(true, h1, null, null, null); S.push();
            require(LinkStore.LIVE.equals(LinkChecker.check(app, a).state), "the owner can be checked again once the call ended");
            mark.accept("Lifecycle: hung owner");

            // A publish between the stat and its write wins.
            long rev = io(() -> app.library.links.revision(a.id));
            io(() -> app.library.links.publish(LinkRecord.parse(record(stubOwner, a.linkId, "Lifecycle A", h2)), "newer text", null));
            require(!io(() -> app.library.links.applyCheck(a.id, rev, new LinkStore.Outcome(LinkStore.MISSING, null, -1))), "a stale check result is dropped after a publish");
            require(LinkStore.LIVE.equals(state(a)), "the publish stays");

            // Removal by the owner (real provider; the caller is Stack itself).
            LinkStore.Link mine = publish(me, "Lifecycle removal", hash());
            Bundle bad1 = removed("some.other.app", mine.linkId, "deleted");
            require("invalid".equals(bad1.getString("error")) && LinkStore.LIVE.equals(state(mine)), "removed for another owner is refused");
            require("invalid".equals(removed(me, mine.linkId, "gone").getString("error")), "an unknown reason is refused");
            require("invalid".equals(app.getContentResolver().call(Uri.parse("content://" + me + ".link"), "removed", "{\"contract\":1,\"owner\":\"" + me + "\",\"link_id\":\"" + mine.linkId + "\",\"reason\":\"deleted\",\"x\":1}", null).getString("error")), "an extra field is refused");
            require("unsupported_contract".equals(app.getContentResolver().call(Uri.parse("content://" + me + ".link"), "removed", "{\"contract\":2,\"owner\":\"" + me + "\",\"link_id\":\"" + mine.linkId + "\",\"reason\":\"deleted\"}", null).getString("error")), "another contract number is refused");
            Bundle unknown = removed(me, UUID.randomUUID().toString(), "deleted");
            require(unknown.getBoolean("ok") && !unknown.getBoolean("changed") && unknown.getString("error") == null, "an unknown link id is a harmless no-op");
            require("invalid".equals(app.getContentResolver().call(Uri.parse("content://" + me + ".link"), "moved", "{}", null).getString("error")), "an empty moved message is refused");
            Bundle ok = removed(me, mine.linkId, "trashed");
            require(ok.getBoolean("ok") && ok.getBoolean("changed") && LinkStore.TRASHED.equals(state(mine)), "removed moves the record to Recently deleted");
            require(io(() -> app.library.links.list("lifecycle")).stream().noneMatch(l -> l.id.equals(mine.id)) && io(() -> app.library.links.text(mine.id)).contains("lifecycle"), "a trashed record is out of the list but its text is kept");
            require(io(() -> app.library.links.trashed()).stream().anyMatch(t -> t.id.equals(mine.id) && "trashed".equals(t.reason)), "it shows in Recently deleted with the reason");
            final long trashedRevision = io(() -> app.library.links.revision(mine.id));
            require(!io(() -> app.library.links.applyCheck(mine.id, trashedRevision, new LinkStore.Outcome(LinkStore.LIVE, null, -1))), "a check never changes a trashed record");
            io(() -> app.library.links.publish(LinkRecord.parse(record(me, mine.linkId, "Lifecycle removal", mine.hash)), "lifecycle check text", null));
            require(LinkStore.LIVE.equals(state(mine)), "a publish brings a trashed record back to live");
            mark.accept("Lifecycle: removed, trash, publish restores");

            // Slice 5: moved and unshared (real provider; the caller is Stack itself).
            final String uuid1 = UUID.randomUUID().toString(), uuid2 = UUID.randomUUID().toString();
            String sharedRecord = new JSONObject(record(me, mine.linkId, "Lifecycle removal", mine.hash)).put("shared", true)
                .put("locators", new JSONArray().put(new JSONObject().put("type", "filen").put("uuid", uuid1).put("path", "/Docs/a.sam"))).toString();
            io(() -> app.library.links.publish(LinkRecord.parse(sharedRecord), "lifecycle check text", null));
            require(io(() -> app.library.links.get(mine.id)).shared, "the record is shared");
            io(() -> { app.library.links.rename(mine.id, "My own title"); return true; });
            require("invalid".equals(moved("some.other.app", mine.linkId, uuid2, "/Docs/b.sam").getString("error")), "moved for another owner is refused");
            require("invalid".equals(app.getContentResolver().call(Uri.parse("content://" + me + ".link"), "moved", new JSONObject().put("contract", 1).put("owner", me).put("link_id", mine.linkId).put("locators", new JSONArray()).toString(), null).getString("error")), "moved without locators is refused");
            Bundle unknownMove = moved(me, UUID.randomUUID().toString(), uuid2, "/x");
            require(unknownMove.getBoolean("ok") && !unknownMove.getBoolean("changed"), "moved for an unknown link is a harmless no-op");
            Bundle move = moved(me, mine.linkId, uuid2, "/Docs/sub/b.sam");
            LinkStore.Link movedLink = io(() -> app.library.links.get(mine.id));
            require(move.getBoolean("ok") && move.getBoolean("changed") && movedLink.locators.contains(uuid2) && !movedLink.locators.contains(uuid1), "moved replaces the locators");
            require("My own title".equals(movedLink.title) && LinkStore.LIVE.equals(movedLink.state) && movedLink.hash.equals(mine.hash), "moved changes nothing else");
            require(!moved(me, mine.linkId, uuid2, "/Docs/sub/b.sam").getBoolean("changed"), "the same move again changes nothing");
            removed(me, mine.linkId, "trashed");
            require(LinkStore.TRASHED.equals(state(mine)), "the file went to Filen's trash");
            require(moved(me, mine.linkId, uuid1, "/Docs/a.sam").getBoolean("changed") && LinkStore.LIVE.equals(state(mine)), "moved after trashed brings the record back to live");
            removed(me, mine.linkId, "deleted");
            moved(me, mine.linkId, uuid2, "/Docs/b.sam");
            require(LinkStore.TRASHED.equals(state(mine)), "moved never revives a record the owner deleted");
            io(() -> app.library.links.publish(LinkRecord.parse(sharedRecord), "lifecycle check text", null));
            require("invalid".equals(unshared("some.other.app", mine.linkId).getString("error")), "unshared for another owner is refused");
            Bundle unshare = unshared(me, mine.linkId);
            require(unshare.getBoolean("ok") && unshare.getBoolean("changed") && !io(() -> app.library.links.get(mine.id)).shared && LinkStore.LIVE.equals(state(mine)), "unshared clears the shared flag and keeps the record live");
            require(!unshared(me, mine.linkId).getBoolean("changed"), "unshared twice changes nothing");
            mark.accept("Lifecycle: moved and unshared");
            // Filen-origin events: matched by the Filen UUID, only from Filen Files (here the caller is Stack, so the provider refuses; the store is tested directly).
            String filenMessage = new JSONObject().put("contract", 1).put("filen_uuid", uuid2).put("path", "/x").toString();
            require("invalid".equals(app.getContentResolver().call(Uri.parse("content://" + me + ".link"), "moved", filenMessage, null).getString("error")), "a Filen-origin event from any other app is refused");
            io(() -> app.library.links.publish(LinkRecord.parse(new JSONObject(sharedRecord).put("locators", new JSONArray()
                .put(new JSONObject().put("type", "filen").put("uuid", uuid2).put("path", "/Docs/b.sam"))
                .put(new JSONObject().put("type", "owner").put("token", "private/1"))).toString()), "lifecycle check text", null));
            require(io(() -> app.library.links.filenMoved(uuid2, "/Docs/new/b.sam")) == 1, "a Filen move finds the record by UUID");
            LinkStore.Link byUuid = io(() -> app.library.links.get(mine.id));
            require(byUuid.locators.replace("\\/", "/").contains("/Docs/new/b.sam") && byUuid.locators.replace("\\/", "/").contains("private/1"), "a Filen move replaces only the filen locator");
            require(io(() -> app.library.links.filenMoved(UUID.randomUUID().toString(), "/y")) == 0, "a Filen move of an unknown file changes nothing");
            require(io(() -> app.library.links.filenRemoved(uuid2, "trashed")) == 1 && LinkStore.TRASHED.equals(state(mine)), "Filen trashing the file moves the record to Recently deleted");
            require(io(() -> app.library.links.filenMoved(uuid2, "/Docs/back.sam")) == 1 && LinkStore.LIVE.equals(state(mine)), "restoring from Filen's trash brings it back");
            io(() -> app.library.links.filenRemoved(uuid2, "trashed"));
            require(io(() -> app.library.links.filenRemoved(uuid2, "deleted")) == 1, "deleting for good upgrades a record that sat in Filen's trash");
            io(() -> app.library.links.filenMoved(uuid2, "/Docs/ghost.sam"));
            require(LinkStore.TRASHED.equals(state(mine)), "a record deleted for good is never revived by a later move");
            io(() -> app.library.links.publish(LinkRecord.parse(new JSONObject(sharedRecord).put("locators", new JSONArray().put(new JSONObject().put("type", "filen").put("uuid", uuid2).put("path", "/Docs/b.sam"))).toString()), "lifecycle check text", null));
            require(io(() -> app.library.links.filenUnshared(uuid2)) == 1 && !io(() -> app.library.links.get(mine.id)).shared, "Filen ending the share clears shared");
            // list_owned: the caller's own records only, no text, no protected/trashed rows.
            Bundle listed = app.getContentResolver().call(Uri.parse("content://" + me + ".link"), "list_owned", new JSONObject().put("contract", 1).put("owner", me).toString(), null);
            JSONArray owned = new JSONArray(listed.getString("records"));
            boolean found = false;
            for (int i = 0; i < owned.length(); i++) {
                JSONObject ownedRow = owned.getJSONObject(i);
                require(ownedRow.length() == 6 && ownedRow.has("link_id") && ownedRow.has("locators") && !ownedRow.has("text") && !ownedRow.has("preview"), "list_owned entries carry only ids, title, hash, modified, state and filen locators");
                if (ownedRow.getString("link_id").equals(mine.linkId)) found = true;
            }
            require(found && listed.getInt("count") == owned.length() && !listed.getBoolean("truncated"), "list_owned lists the caller's own live record");
            require("invalid".equals(app.getContentResolver().call(Uri.parse("content://" + me + ".link"), "list_owned", new JSONObject().put("contract", 1).put("owner", "some.other.app").toString(), null).getString("error")), "list_owned for another owner is refused");
            LinkStore.Link throwaway = publish(me, "Lifecycle list_owned throwaway", hash());
            io(() -> app.library.links.trash(throwaway.id, "user"));
            Bundle afterList = app.getContentResolver().call(Uri.parse("content://" + me + ".link"), "list_owned", new JSONObject().put("contract", 1).put("owner", me).toString(), null);
            require(!afterList.getString("records").contains(throwaway.linkId), "list_owned leaves out trashed records");
            mark.accept("Lifecycle: Filen-origin events and list_owned");

            // User removal, restore, expiry.
            require(!io(() -> app.library.links.deleteTrashed(mine.id)) && io(() -> app.library.links.get(mine.id)) != null, "Delete for good never deletes a record that is not in Recently deleted");
            io(() -> app.library.links.trash(mine.id, "user"));
            io(() -> { app.library.links.restore(mine.id); return true; });
            require(LinkStore.UNREACHABLE.equals(state(mine)), "a record the user removed returns as unreachable until the next check");
            io(() -> app.library.links.markRemoved(me, mine.linkId, "deleted"));
            io(() -> { app.library.links.restore(mine.id); return true; });
            require(LinkStore.MISSING.equals(state(mine)), "a record the owner reported gone returns as missing");
            long now = Clock.now();
            io(() -> app.library.links.sweep(now + 10L * 24 * 3600 * 1000));
            require(LinkStore.MISSING.equals(state(mine)), "missing for 10 days stays missing");
            io(() -> app.library.links.sweep(now + 31L * 24 * 3600 * 1000));
            require(LinkStore.TRASHED.equals(state(mine)), "missing for 30 days becomes trashed");
            io(() -> app.library.links.sweep(now + 62L * 24 * 3600 * 1000));
            require(io(() -> app.library.links.get(mine.id)) == null, "trashed for 30 days is deleted for good");
            mark.accept("Lifecycle: restore and 30-day expiry");
        } finally {
            S.stat = null; S.refresh = null; S.delay = 0; S.fail = false; S.push();
            io(() -> { for (LinkStore.Link l : app.library.links.list("")) if (l.owner.equals(stubOwner) || l.title.startsWith("Lifecycle")) app.library.links.delete(l.id); for (LinkStore.Trashed t : app.library.links.trashed()) if (t.title.startsWith("Lifecycle")) app.library.links.delete(t.id); return true; });
        }
    }

    /**
     * Against a real owner app on the phone (nothing is faked): for every AmiPro record that is not in Recently deleted, run the real stat,
     * then the real refresh, and report states and whether the stored hash changed. Run with `-e linkE2e true`.
     */
    static void endToEnd(Context target, Consumer<String> mark) throws Exception {
        app = (StackApplication) target.getApplicationContext();
        app.libraryReady.await();
        for (LinkStore.Link link : io(() -> app.library.links.list(""))) {
            if (!"app.amipro.editor".equals(link.owner) && !"app.amipro.editor.debug".equals(link.owner)) continue;
            LinkChecker.Result checked = LinkChecker.check(app, link);
            LinkStore.Link afterStat = io(() -> app.library.links.get(link.id));
            String problem = LinkChecker.refresh(app, link);
            Thread.sleep(1500);
            LinkStore.Link afterRefresh = io(() -> app.library.links.get(link.id));
            mark.accept("E2E " + link.title + " (protected=" + link.isProtected + "): stat -> state=" + checked.state + " changedFlag=" + checked.changed + " busy=" + checked.busy
                + "; stored hash kept after stat=" + afterStat.hash.equals(link.hash) + "; refresh -> " + (problem == null ? "ok" : problem)
                + "; hash after refresh=" + (afterRefresh.hash.equals(link.hash) ? "unchanged" : "CHANGED") + "; state after=" + afterRefresh.state + "; text chars=" + io(() -> app.library.links.text(link.id)).length());
        }
    }
}
