package org.stackdocs.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One durable scan draft; all access uses the application IO executor. */
final class ScanDraftStore {
    final File directory;
    private final AtomicFile manifest;

    static final class Draft {
        final String id;
        final List<PageState> pages;
        String name="";
        boolean titleChosen,filingChosen,choiceTracking=true;
        final List<String> stackIds=new ArrayList<>();
        Draft(String id, List<PageState> pages) { this.id = id; this.pages = pages; }
    }

    ScanDraftStore(Context context) {
        directory = new File(context.getFilesDir(), "scan_draft");
        manifest = new AtomicFile(new File(directory, "draft.json"));
    }

    Draft load() throws IOException {
        if (!manifest.getBaseFile().exists() && !new File(directory, "draft.json.bak").exists()) {
            return new Draft(UUID.randomUUID().toString(), new ArrayList<>());
        }
        try {
            JSONObject json = new JSONObject(new String(manifest.readFully(), StandardCharsets.UTF_8));
            if (json.getInt("version") != 1) throw new JSONException("Unsupported scan draft version");
            String id = json.getString("id");
            UUID.fromString(id);
            JSONArray array = json.getJSONArray("pages");
            if (array.length() > 100) throw new JSONException("Too many pages");
            List<PageState> pages = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String pageId = item.getString("id");
                UUID.fromString(pageId);
                PageState page = new PageState(pageId, pageId + ".jpg", 0,
                        item.getInt("rotation"), PageGeometry.decode(item.optString("crop", "")),
                        item.getString("enhancement"), item.optBoolean("flatten", false));
                if (!file(page).isFile()) throw new IOException("A scan page is missing. Keep the draft and try again.");
                pages.add(page);
            }
            Draft draft=new Draft(id,pages);
            draft.name=json.optString("name","");
            draft.titleChosen=json.optBoolean("title_chosen",false);
            draft.filingChosen=json.optBoolean("filing_chosen",false);
            draft.choiceTracking=json.optBoolean("choice_tracking",false);
            JSONArray stacks=json.optJSONArray("stack_ids");
            if(stacks!=null) for(int i=0;i<stacks.length();i++) draft.stackIds.add(stacks.getString(i));
            return draft;
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("The unfinished scan could not be read. Its photos have been kept on this device.", error);
        }
    }

    Draft append(byte[] jpeg) throws IOException {
        Draft draft = load();
        if (draft.pages.size() >= 100) throw new IOException("Save this scan before adding more pages (100-page limit).");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Not enough space to keep the scan.");
        String id = UUID.randomUUID().toString();
        File image = new File(directory, id + ".jpg");
        boolean saved = false;
        try {
            try (FileOutputStream output = new FileOutputStream(image)) {
                output.write(jpeg);
                output.getFD().sync();
            }
            Bitmap preview = PageRenderer.preview(image, "image/jpeg", 0, 768);
            float[] crop;
            try { crop = BoundaryDetector.suggest(preview); } finally { preview.recycle(); }
            draft.pages.add(new PageState(id, id + ".jpg", 0, 0, crop, "black_white"));
            write(draft);
            saved = true;
            return draft;
        } finally {
            if (!saved) image.delete();
        }
    }

    Draft edit(int position, PageState page) throws IOException {
        Draft draft = load();
        if (!draft.pages.get(position).id.equals(page.id)) throw new IOException("This page changed. Please reopen it.");
        draft.pages.set(position, page);
        write(draft);
        return draft;
    }

    Draft remove(int position) throws IOException {
        Draft draft = load();
        PageState page = draft.pages.remove(position);
        write(draft);
        file(page).delete();
        return draft;
    }

    Draft choices(String id,String name,boolean titleChosen,List<String> stacks,boolean filingChosen) throws IOException {
        Draft draft=load();
        if(!draft.id.equals(id)) return draft; // A delayed keystroke cannot resurrect a discarded/saved draft.
        draft.name=name; draft.titleChosen=titleChosen; draft.filingChosen=filingChosen;
        draft.stackIds.clear(); draft.stackIds.addAll(stacks);
        write(draft); return draft;
    }

    File file(PageState page) { return new File(directory, page.sourceFile); }

    void discard() throws IOException {
        // Remove the manifest first; no saved library files live in this directory.
        manifest.delete();
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (file.getName().matches("[a-f0-9-]{36}\\.jpg") && !file.delete()) {
                throw new IOException("Some discarded scan photos could not be removed. Please try again.");
            }
        }
    }

    private void write(Draft draft) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not save the scan draft.");
        FileOutputStream output = null;
        try {
            JSONObject json = new JSONObject();
            json.put("version", 1);
            json.put("id", draft.id);
            json.put("name",draft.name); json.put("title_chosen",draft.titleChosen);
            json.put("filing_chosen",draft.filingChosen); json.put("choice_tracking",draft.choiceTracking);
            json.put("stack_ids",new JSONArray(draft.stackIds));
            JSONArray pages = new JSONArray();
            for (PageState page : draft.pages) {
                JSONObject item = new JSONObject();
                item.put("id", page.id);
                item.put("rotation", page.rotation);
                item.put("crop", PageGeometry.encode(page.crop));
                item.put("enhancement", page.enhancement);
                item.put("flatten", page.flatten);
                pages.put(item);
            }
            json.put("pages", pages);
            output = manifest.startWrite();
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            manifest.finishWrite(output);
        } catch (JSONException | IOException error) {
            if (output != null) manifest.failWrite(output);
            throw new IOException("The scan draft could not be saved. Check available storage.", error);
        }
    }
}
