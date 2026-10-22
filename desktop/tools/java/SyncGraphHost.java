package org.stackdocs.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONException;
import org.json.JSONObject;

/** Runs unchanged production graph code; not Android SQLite/lifecycle evidence. */
public final class SyncGraphHost {
    public static void main(String[] args) throws Exception {
        JSONObject input = ArchiveJson.read(Files.readString(Path.of(args[1])));
        switch (args[0]) {
            case "cells":
                System.out.println(SyncProtocol.canonical(SyncGraph.cells(input)));
                return;
            case "project":
                System.out.println(SyncProtocol.canonical(SyncGraph.project(input)));
                return;
            case "validate":
                try {
                    SyncGraph.validateChanges(input);
                    System.out.println(new JSONObject().put("accepted", true));
                } catch (IOException | JSONException rejected) {
                    System.out.println(new JSONObject().put("accepted", false));
                }
                return;
            default:
                throw new IllegalArgumentException("Unknown graph probe");
        }
    }
}
