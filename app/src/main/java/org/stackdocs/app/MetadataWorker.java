package org.stackdocs.app;

import java.util.*;
import java.util.concurrent.*;

final class MetadataWorker {
    private final StackApplication app;
    private final ExecutorService analysis=Executors.newSingleThreadExecutor();
    private boolean active;
    MetadataWorker(StackApplication app){this.app=app;}
    void kick() {
        if(active||!app.access.allowed())return;
        MetadataStore.Snapshot snap;
        try{snap=app.library.metadata.claim();}catch(RuntimeException unavailable){return;}
        if(snap==null)return;active=true;
        analysis.execute(() -> {
            List<DetailValue> values=new ArrayList<>();boolean failed=false;
            try{values=MetadataRules.analyze(snap);}catch(RuntimeException error){failed=true;}
            List<DetailValue> result=values;boolean failure=failed;
            app.io.execute(() -> {try{app.library.metadata.complete(snap,result,failure);}catch(RuntimeException unavailable){app.library.metadata.complete(snap,Collections.emptyList(),true);}finally{active=false;app.notifyLibraryChanged();}kick();});
        });
    }
}
