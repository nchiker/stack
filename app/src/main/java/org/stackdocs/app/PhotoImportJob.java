package org.stackdocs.app;

import android.app.job.*;

public final class PhotoImportJob extends JobService {
    @Override public boolean onStartJob(JobParameters parameters){StackApplication app=(StackApplication)getApplication();if(!app.photos.enabled()||!app.photos.permission()||!app.access.allowed())return false;app.photos.kick(() -> new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> jobFinished(parameters,false)));return true;}
    @Override public boolean onStopJob(JobParameters parameters){return ((StackApplication)getApplication()).photos.enabled();}
}
