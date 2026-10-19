package org.stackdocs.app;

/** Debug-only, non-exported view host. Instrumentation injects isolated dependencies, never app.library. */
public final class IsolatedCheckActivity extends android.app.Activity {
    static java.util.function.Function<android.app.Activity,android.view.View> content;
    static java.util.function.BiConsumer<Integer,android.content.Intent> result;
    static android.content.res.Configuration configuration;
    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(configuration==null ? base : base.createConfigurationContext(configuration));
    }
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(content==null) { finish(); return; }
        setContentView(content.apply(this));
    }
    @Override protected void onActivityResult(int request,int code,android.content.Intent data) {
        super.onActivityResult(request,code,data);
        if(result!=null) result.accept(request,data);
    }
}
