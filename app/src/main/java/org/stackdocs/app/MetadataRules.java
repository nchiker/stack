package org.stackdocs.app;

import org.json.JSONObject;
import org.json.JSONException;
import java.util.*;
import java.util.regex.*;

/** Independently authored, conservative printed-English local rules. Ambiguity abstains. */
final class MetadataRules {
    static final String VERSION="local-details-6";
    private static final Pattern DATE=Pattern.compile("(?i)^(document date|invoice date|statement date|date|due date|payment due)\\s*:?\\s*(.+)$");
    private static final String DATE_VALUE="(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}|[A-Za-z]{3,9} [0-9]{1,2}, [0-9]{4}|[0-9]{1,2} [A-Za-z]{3,9} [0-9]{4})";
    private static final Pattern INLINE_DATE=Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])(document date|invoice date|statement date|due date|payment due|date)\\s*:?\\s*("+DATE_VALUE+")(?![\\p{L}\\p{N}/-])");
    private static final Pattern DATE_TOKEN=Pattern.compile(DATE_VALUE);
    private static final Pattern AMOUNT=Pattern.compile("(?i)^(grand total|total amount|total|amount due|balance due)\\s*:?\\s*(?:(USD|EUR|GBP|CAD|AUD|JPY|CHF)\\s*)?([$€£]?)\\s*(-?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,15})(?:\\.[0-9]{1,6})?)\\s*(USD|EUR|GBP|CAD|AUD|JPY|CHF)?$");
    private static final Pattern AMOUNT_ONLY=Pattern.compile("(?i)^(grand total|total amount|total|amount due|balance due)\\s*:?$");
    private static final String NUMBER_LABEL="(client|patient|invoice|account|customer|reference)\\s*(?:#|number\\b|no\\b\\.?|id\\b)";
    private static final String IDENTIFIER="[A-Za-z0-9][A-Za-z0-9._/-]{0,79}";
    private static final Pattern NUMBER=Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])"+NUMBER_LABEL+"\\s*:?\\s*("+IDENTIFIER+")(?![\\p{L}\\p{N}._/-])");
    private static final Pattern NUMBER_ONLY=Pattern.compile("(?i)^"+NUMBER_LABEL+"\\s*:?$");
    private static final Pattern STAFF=Pattern.compile("(?i)^staff name\\s*:\\s*(.+)$");
    private static final Pattern STAFF_ONLY=Pattern.compile("(?i)^staff name\\s*:?$");
    static List<DetailValue> analyze(MetadataStore.Snapshot snap) {
        Map<String,DetailValue> found=new LinkedHashMap<>();Set<String> ambiguous=new HashSet<>();
        for(int page=0;page<snap.text.pages.size();page++) {
            String[] lines=snap.text.pages.get(page).split("\\r?\\n");
            for(int line=0;line<Math.min(lines.length,400);line++) {
                String raw=lines[line].trim().replaceAll("[ \\t]+"," ");if(raw.length()>250||raw.isEmpty())continue;
                Matcher date=DATE.matcher(raw);String amountRaw=raw;
                if(AMOUNT_ONLY.matcher(raw).matches()&&nextLine(lines,line).length()<=100)amountRaw=raw+" "+nextLine(lines,line);
                Matcher amount=AMOUNT.matcher(amountRaw);
                Matcher number=NUMBER.matcher(raw);
                while(number.find())number(found,ambiguous,snap,number.group(1),number.group(2),page,raw);
                Matcher numberOnly=NUMBER_ONLY.matcher(raw);
                if(numberOnly.matches()){String next=nextLine(lines,line);if(next.matches(IDENTIFIER))number(found,ambiguous,snap,numberOnly.group(1),next,page,raw+"\n"+next);}
                Matcher staff=STAFF.matcher(raw);String staffName=staff.matches()?staff.group(1):STAFF_ONLY.matcher(raw).matches()?nextLine(lines,line):null;
                if(staffName!=null&&staffName.length()<=80&&staffName.matches("[\\p{L}][\\p{L} .’'\\-]{1,79}")&&!staffName.matches("(?i)(staff name|document date|invoice date|statement date|date|due date|payment due|grand total|total amount|total|amount due|balance due|not available|unknown|none)"))
                    put(found,ambiguous,value(snap,"text:staff-name","text","Staff name",staffName,"",page,staff.matches()?raw:raw+"\n"+staffName));
                Matcher inline=INLINE_DATE.matcher(raw);List<String[]> dates=new ArrayList<>();List<Integer> ends=new ArrayList<>(),starts=new ArrayList<>();Set<String> roles=new HashSet<>();
                while(inline.find()){dates.add(new String[]{inline.group(1),inline.group(2)});starts.add(inline.start());ends.add(inline.end());}
                for(int at=0;at<dates.size();at++) {
                    String role=dateRole(dates.get(at)[0]),field="date:"+(role.equals("unknown")?"document":role);roles.add(role);
                    String value=normalizeDate(dates.get(at)[1]);String tail=raw.substring(ends.get(at),at+1<dates.size()?starts.get(at+1):raw.length());
                    if(value==null||DATE_TOKEN.matcher(tail).find()){ambiguous.add(field);continue;}
                    put(found,ambiguous,value(snap,field,"date",role.equals("unknown")?"Date (role unknown)":role.equals("due")?"Due date":"Document date",value,"day",page,raw));
                }
                if(date.matches()&&!roles.contains(dateRole(date.group(1))))ambiguous.add("date:"+(dateRole(date.group(1)).equals("unknown")?"document":dateRole(date.group(1))));
                if(amount.matches()) {
                    String role=amount.group(1).toLowerCase(Locale.ROOT).contains("due")?"due":"total";
                    String currency=amount.group(2)!=null?amount.group(2).toUpperCase(Locale.ROOT):amount.group(5)!=null?amount.group(5).toUpperCase(Locale.ROOT):amount.group(3).equals("€")?"EUR":amount.group(3).equals("$")?"USD":"unknown";
                    if(amount.group(2)!=null&&amount.group(5)!=null&&!amount.group(2).equalsIgnoreCase(amount.group(5))){ambiguous.add("amount:"+role);continue;}
                    if((amount.group(3).equals("€")&&!currency.equals("EUR"))||(amount.group(3).equals("£")&&!currency.equals("GBP")&&!currency.equals("unknown"))||(amount.group(3).equals("$")&&!Arrays.asList("USD","CAD","AUD","unknown").contains(currency))){ambiguous.add("amount:"+role);continue;}
                    put(found,ambiguous,value(snap,"amount:"+role,"amount",role.equals("due")?"Amount due":"Total",amount.group(4).replace(",",""),currency,page,amountRaw));
                }
                // Require strong issuer vocabulary and P0 address/contact guards, first-page heading only.
                if(page==0&&line<8&&SuggestionRules.headingScore(raw)>=2&&raw.matches("(?i).*\\b(bank|credit union|clinic|hospital|utilities|energy|insurance|pharmacy|llc|ltd|inc|company|cooperative)\\b.*"))
                    put(found,ambiguous,value(snap,"organization:issuer","organization","Organization",raw,"",page,raw));
            }
        }
        for(String field:ambiguous)found.remove(field);
        return new ArrayList<>(found.values());
    }
    private static String nextLine(String[] lines,int line){for(int next=line+1;next<Math.min(lines.length,line+4);next++){String text=lines[next].trim();if(!text.isEmpty())return text;}return "";}
    private static void number(Map<String,DetailValue> found,Set<String> ambiguous,MetadataStore.Snapshot snap,String label,String identifier,int page,String raw){
        // Identifiers stay text: retain leading zeros/punctuation; require a digit to avoid reading the next column's heading as a value.
        if(!identifier.matches(".*[0-9].*")||identifier.endsWith(".")||identifier.endsWith("/")||identifier.endsWith("-"))return;
        String role=label.toLowerCase(Locale.ROOT);put(found,ambiguous,value(snap,"text:"+role+"-number","text",Character.toUpperCase(role.charAt(0))+role.substring(1)+" #",identifier,"",page,raw));
    }
    private static String dateRole(String label){return label.equalsIgnoreCase("date")?"unknown":label.toLowerCase(Locale.ROOT).contains("due")?"due":"document";}
    private static void put(Map<String,DetailValue> found,Set<String> ambiguous,DetailValue value) {
        DetailValue previous=found.get(value.field);if(previous!=null&&(!previous.value.equals(value.value)||!previous.qualifier.equals(value.qualifier)))ambiguous.add(value.field);else found.put(value.field,value);
    }
    private static DetailValue value(MetadataStore.Snapshot snap,String field,String kind,String label,String value,String qualifier,int page,String raw) {
        try {
            JSONObject evidence=new JSONObject().put("rule",VERSION).put("config",OcrModel.CONFIG).put("input",snap.text.input).put("text_evidence",snap.text.evidence).put("page",page+1).put("raw",raw).put("role",label.equals("Date (role unknown)")?"unknown":field.substring(field.indexOf(':')+1));
            if(kind.equals("amount")){Matcher money=AMOUNT.matcher(raw);if(money.matches()){String basis=money.group(2)!=null||money.group(5)!=null?"explicit-code":money.group(3).equals("$")?"bare-dollar-default":money.group(3).isEmpty()?"unknown":"symbol";evidence.put("currency_basis",basis);if(basis.equals("bare-dollar-default"))evidence.put("dollar_currency_default","USD");}}
            return new DetailValue(field,kind,label,value,qualifier,evidence.toString(),"accepted","saved",0);
        } catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }
    static String normalizeDate(String raw) {
        String clean=raw.trim();
        if(clean.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {try{DetailValue.validate("date","Date",clean,"day");return clean;}catch(IllegalArgumentException invalid){return null;}}
        Matcher slash=Pattern.compile("([0-9]{1,2})/([0-9]{1,2})/([0-9]{4})").matcher(clean);
        if(slash.matches()) {
            int a=Integer.parseInt(slash.group(1)),b=Integer.parseInt(slash.group(2));
            if(a<=12&&b<=12&&a!=b)return null;
            String value=String.format(Locale.ROOT,"%s-%02d-%02d",slash.group(3),a>12?b:a,a>12?a:b);
            try{DetailValue.validate("date","Date",value,"day");return value;}catch(IllegalArgumentException invalid){return null;}
        }
        for(String pattern:new String[]{"MMMM d, yyyy","MMM d, yyyy","d MMMM yyyy","d MMM yyyy"}) {
            java.text.SimpleDateFormat format=new java.text.SimpleDateFormat(pattern,Locale.ENGLISH);format.setLenient(false);
            java.text.ParsePosition at=new java.text.ParsePosition(0);Date date=format.parse(clean,at);
            if(date!=null&&at.getIndex()==clean.length())return new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(date);
        }
        return null;
    }
    static List<String> completions(String title,long addedAt,List<DetailValue> saved,List<DetailValue> offered) {
        LinkedHashSet<String> names=new LinkedHashSet<>();if(!title.trim().isEmpty())names.add(title);
        String date=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date(addedAt)),org="";
        List<DetailValue> all=new ArrayList<>(saved);all.addAll(offered);
        for(DetailValue value:all){if(value.kind.equals("organization"))org=value.value;if(value.field.equals("date:document"))date=value.value;}
        if(!org.isEmpty())names.add(SuggestionRules.clean(org+" · "+date));
        names.add("Document · "+date);names.add("Receipt · "+date);names.add("Bill · "+date);
        return new ArrayList<>(names).subList(0,Math.min(5,names.size()));
    }
}
