package org.stackdocs.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.IOException;
import java.util.HashSet;

/** Strict JSON gate before Android's permissive JSON parser; bounded depth and duplicate keys. */
final class ArchiveJson {
    private final String text;
    private int at;
    private ArchiveJson(String text) { this.text=text; }
    static JSONObject read(String text) throws IOException {
        ArchiveJson parser=new ArchiveJson(text);
        parser.value(0); parser.space();
        if(parser.at!=text.length()) throw invalid();
        try { return new JSONObject(text); } catch(JSONException error) { throw invalid(); }
    }
    private void space() { while(at<text.length() && " \r\n\t".indexOf(text.charAt(at))>=0) at++; }
    private char peek() throws IOException { if(at>=text.length()) throw invalid(); return text.charAt(at); }
    private void expect(char c) throws IOException { space(); if(peek()!=c) throw invalid(); at++; }
    private void value(int depth) throws IOException {
        if(depth>16) throw invalid();
        space(); char c=peek();
        if(c=='{') {
            at++; space(); HashSet<String> keys=new HashSet<>();
            if(peek()=='}') {at++;return;}
            do {
                space(); String key=string(); if(!keys.add(key)) throw invalid();
                expect(':'); value(depth+1); space(); c=peek(); at++;
                if(c=='}') return; if(c!=',') throw invalid();
            } while(true);
        }
        if(c=='[') {
            at++; space(); if(peek()==']') {at++;return;}
            do { value(depth+1); space(); c=peek(); at++; if(c==']') return; if(c!=',') throw invalid(); } while(true);
        }
        if(c=='"') {string();return;}
        for(String literal:new String[]{"true","false","null"}) if(text.startsWith(literal,at)) {at+=literal.length();return;}
        int start=at;
        if(c=='-') {at++;c=peek();}
        if(c=='0') at++;
        else { if(c<'1'||c>'9') throw invalid(); while(at<text.length() && digit(text.charAt(at))) at++; }
        if(at<text.length() && text.charAt(at)=='.') {at++;int first=at;while(at<text.length() && digit(text.charAt(at)))at++;if(first==at)throw invalid();}
        if(at<text.length() && (text.charAt(at)=='e'||text.charAt(at)=='E')) {
            at++;if(at<text.length() && (text.charAt(at)=='+'||text.charAt(at)=='-'))at++;
            int first=at;while(at<text.length() && digit(text.charAt(at)))at++;if(first==at)throw invalid();
        }
        if(at-start>24) throw invalid();
    }
    private String string() throws IOException {
        if(peek()!='"') throw invalid(); int start=at++;
        boolean closed=false;
        while(at<text.length()) {
            char c=text.charAt(at++);
            if(c=='"') {closed=true;break;}
            if(c<32) throw invalid();
            if(c=='\\') {
                c=peek(); at++;
                if(c=='u') {for(int i=0;i<4;i++) {if(Character.digit(peek(),16)<0)throw invalid();at++;}}
                else if("\"\\/bfnrt".indexOf(c)<0)throw invalid();
            }
            if(at-start>2*1024*1024)throw invalid();
        }
        if(!closed) throw invalid();
        try {return new JSONArray("["+text.substring(start,at)+"]").getString(0);} catch(JSONException error){throw invalid();}
    }
    private static boolean digit(char c) {return c>='0'&&c<='9';}
    private static IOException invalid() {return new IOException("The backup manifest is malformed, duplicated or too deeply nested.");}
}
