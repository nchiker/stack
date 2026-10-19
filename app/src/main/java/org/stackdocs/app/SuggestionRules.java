package org.stackdocs.app;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Independently authored printed-English phrase rules. Scores are explanations, not probabilities. */
final class SuggestionRules {
    static final String VERSION="local-phrases-5";
    static final int PAGE_LIMIT=32768, TEXT_LIMIT=262144;
    static final class Result {
        final String title;
        final LinkedHashMap<String,String> stacks;
        final boolean partial, truncated;
        Result(String title, LinkedHashMap<String,String> stacks, boolean partial, boolean truncated) {
            this.title=title; this.stacks=stacks; this.partial=partial; this.truncated=truncated;
        }
        String encode() {
            try {
                JSONObject json=new JSONObject(); json.put("title",title); json.put("partial",partial); json.put("truncated",truncated);
                JSONObject categories=new JSONObject(); for(Map.Entry<String,String> entry:stacks.entrySet()) categories.put(entry.getKey(),entry.getValue());
                json.put("stacks",categories); return json.toString();
            } catch(JSONException error) { throw new IllegalStateException(error); }
        }
        static Result decode(String value) {
            try {
                JSONObject json=new JSONObject(value), categories=json.optJSONObject("stacks");
                LinkedHashMap<String,String> stacks=new LinkedHashMap<>();
                if(categories!=null) for(String id:new String[]{"bills","banking","ids","house","medical","receipts"})
                    if(categories.has(id)) stacks.put(id,categories.getString(id));
                return new Result(json.optString("title",""),stacks,json.optBoolean("partial"),json.optBoolean("truncated"));
            } catch(JSONException error) { throw new IllegalArgumentException("Suggestions could not be read. Try again.",error); }
        }
    }
    static Result analyze(SuggestionJob job) { return analyze(job.pages,job.stackIds,job.partial,job.truncated); }
    static Result analyze(List<String> pages,List<String> available,boolean partial,boolean truncated) {
        LinkedHashMap<String,String> categories=new LinkedHashMap<>();
        StringBuilder all=new StringBuilder();
        for(String page:pages) all.append(page.toLowerCase(Locale.ROOT)).append('\n');
        String text=all.toString().replaceAll("[^\\p{L}\\p{N}]+"," ");
        boolean example=has(text,"sample document|example only|not a valid|fictional story|vocabulary exercise");
        if (!example) {
            add(categories,available,"bills",!has(text,"not an invoice|not a bill") && has(text,"invoice|utility bill|amount due|payment due")
                    && has(text,"amount due|due date|payment due|invoice total|balance due|pay by"),"Invoice or bill with a payment deadline or amount due");
            add(categories,available,"banking",has(text,"bank statement|account statement|checking account|savings account|credit card statement")
                    && has(text,"opening balance|closing balance|account number|statement period|transactions|available credit")
                    || has(text,"mortgage statement") && has(text,"principal|interest|escrow|loan number|payment due"),"Account or bank statement with statement details");
            add(categories,available,"ids",has(text,"passport|driver s license|driver license|identity card|identification card|birth certificate")
                    && has(text,"date of birth|nationality|expiry|expires|license number|place of birth|given name"),"Identity document with identifying fields");
            add(categories,available,"house",has(text,"lease agreement|rental agreement|mortgage statement|property tax|home insurance|home inspection|electricity bill|water bill|gas bill")
                    && has(text,"tenant|landlord|property address|premises|mortgage|policy number|service address|meter|property tax|inspection report"),"Housing agreement, property or household service details");
            add(categories,available,"medical",(!has(text,"special offers|marketing|shopping list|to do|remember to|reminder|ask about")
                    || has(text,"patient name|date of service|diagnosis|test results|medical invoice")) && has(text,"patient|prescription|medical|clinic|hospital|laboratory")
                    && has(text,"diagnosis|treatment|dosage|patient name|date of service|test results|prescription|medical invoice"),"Medical care document with patient or treatment details");
            add(categories,available,"receipts",(has(text,"receipt|sales receipt|purchase receipt|transaction receipt")
                    || has(text,"thank you for shopping|thank you for your purchase") && has(text,"subtotal") && has(text,"cash|card|visa|amount paid"))
                    && has(text,"total|subtotal|amount paid|paid|change|visa|cash"),"Receipt with a purchase total or payment");
        }
        String title="";
        if (!example && !categories.isEmpty()) {
            // Only the first page supplies a heading. Classification still uses every bounded page.
            String heading="";
            if(!pages.isEmpty()) {
                String[] lines=pages.get(0).split("\\r?\\n",13);
                int best=0;
                for(int i=0;i<Math.min(lines.length,12);i++) {
                    String line=clean(lines[i]);
                    int score=headingScore(line);
                    if(score>best) {heading=line;best=score;}
                }
            }
            String type=categories.containsKey("receipts") ? "Receipt" : categories.containsKey("banking") ? "Account statement"
                    : categories.containsKey("ids") ? "Identity document" : categories.containsKey("bills") ? "Bill"
                    : categories.containsKey("medical") ? "Medical document" : "Housing document";
            if(!heading.isEmpty()) title=has(heading.toLowerCase(Locale.ROOT),"receipt|statement|invoice|bill|passport|license|agreement|prescription|certificate|inspection|identity card|identification card|insurance")
                    ? heading : heading+" · "+type;
        }
        return new Result(clean(title),categories,partial,truncated);
    }
    static int headingScore(String line) {
        if(line.length()<4 || line.length()>72 || line.split("\\s+").length>9) return 0;
        if(!line.matches(".*[\\p{L}]{3}.*") || line.matches(".*\\d{4,}.*")) return 0;
        String lower=line.toLowerCase(Locale.ROOT);
        if(has(lower,"account number|patient name|date of birth|address|amount due|total|dear|page|www|http|email|phone|telephone|fax|tel|due date|invoice number|invoice no|date of service|payment terms|thank you|nationality|given name|license number|expiry|expires|opening balance|closing balance|statement period")) return 0;
        if(has(lower,"pick up|remember|please|this is|ask about|your pet")) return 0;
        if(line.indexOf('@')>=0 || line.indexOf(':')>=0 || addressLine(line)) return 0;
        // Require issuer/type evidence or a short proper-name heading, not arbitrary prose.
        if(has(lower,"veterinary|veterinarian|animal hospital|clinic|hospital|laboratory|bank|credit union|pharmacy")) return 3;
        int capitals=0;
        for(String word:line.split("\\s+")) if(!word.isEmpty() && Character.isUpperCase(word.charAt(0))) capitals++;
        if(capitals>=2 && !line.endsWith(".") && !has(lower,"this is|please|your pet|remember|pick up")) return 2;
        if(has(lower,"receipt|statement|invoice|bill|passport|license|agreement|prescription|certificate|inspection|identity card|identification card|insurance|test report")) return 2;
        return line.matches("[\\p{Lu}][\\p{L}'’&-]{3,}") ? 1 : 0;
    }
    private static boolean addressLine(String line) {
        String lower=line.toLowerCase(Locale.ROOT);
        // OCR may separate a house number from its street, or put address/contact before the issuer.
        if(line.matches("^[#\\d].*") || has(lower,"p o box|po box|post office box|suite|ste|unit|zip code|postal code")) return true;
        if(lower.matches(".*\\b(street|st|road|rd|avenue|ave|boulevard|blvd|lane|ln|drive|dr|court|ct|way|highway|hwy|parkway|pkwy|terrace|place|pl|circle|cir)\\.?$")) return true;
        if(line.matches(".*\\d.*") && has(lower,"street|st|road|rd|avenue|ave|boulevard|blvd|lane|ln|drive|dr|court|ct|highway|hwy|parkway|pkwy")) return true;
        if(line.matches("(?i).*\\b[A-Z]{1,2}\\d[A-Z\\d]?\\s*\\d[A-Z]{2}\\b.*")
                || line.matches("(?i).*\\b[A-Z]\\d[A-Z]\\s*\\d[A-Z]\\d\\b.*")) return true;
        return line.matches(".*,[ \\t]*[A-Z]{2}(?:[ \\t]+[0-9-]+)?$");
    }
    private static boolean has(String text,String phrases) { return Pattern.compile("\\b(?:"+phrases+")\\b").matcher(text).find(); }
    private static void add(LinkedHashMap<String,String> result,List<String> available,String id,boolean supported,String why) {
        if(supported && available.contains(id)) result.put(id,why);
    }
    static String clean(String value) {
        String result=value.replaceAll("[\\p{Cntrl}/\\\\]"," ").replaceAll("\\s+"," ").trim();
        return result.substring(0,Math.min(100,result.length()));
    }
}
