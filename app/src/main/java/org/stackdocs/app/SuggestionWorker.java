package org.stackdocs.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Text-only analysis never blocks UI, repository IO or native OCR. */
final class SuggestionWorker {
    private final SuggestionStore store;
    private final ExecutorService io, worker=Executors.newSingleThreadExecutor();
    private final Runnable changed;
    private boolean active;
    private final java.util.function.BooleanSupplier permitted;
    SuggestionWorker(SuggestionStore store,ExecutorService io,Runnable changed) {this(store,io,changed,() -> true);}
    SuggestionWorker(SuggestionStore store,ExecutorService io,Runnable changed,java.util.function.BooleanSupplier permitted) { this.store=store; this.io=io; this.changed=changed;this.permitted=permitted; }
    void shutdown() { worker.shutdown(); }
    void kick() {
        if(active||!permitted.getAsBoolean()) return;
        SuggestionJob job;
        try { job=store.claim(); } catch(RuntimeException unavailable) { return; }
        if(job==null) return;
        active=true; changed.run();
        worker.execute(() -> {
            SuggestionRules.Result result=null; boolean failed=false;
            try { result=SuggestionRules.analyze(job); } catch(RuntimeException failure) { failed=true; }
            SuggestionRules.Result computed=result; boolean failure=failed;
            io.execute(() -> {
                try { store.complete(job,computed,failure); changed.run(); }
                finally { active=false; }
                kick();
            });
        });
    }
}
