package org.stackdocs.app;

import org.json.JSONObject;
import org.json.JSONException;
import java.math.BigDecimal;
import java.util.Locale;

/** Exact portable value. qualifier is ISO currency/unknown, date precision, or empty. */
final class DetailValue {
    final String field,kind,label,value,qualifier,evidence,origin,state;
    final long revision;
    DetailValue(String field,String kind,String label,String value,String qualifier,String evidence,String origin,String state,long revision) {
        this.field=field;this.kind=kind;this.label=label;this.value=value;this.qualifier=qualifier;
        this.evidence=evidence;this.origin=origin;this.state=state;this.revision=revision;
    }
    static void validate(String kind,String label,String value,String qualifier) {
        LibraryRepository.cleanName(label);
        if(value.trim().isEmpty()||value.length()>2000||value.indexOf('\u0000')>=0)throw new IllegalArgumentException("Enter a value with 1–2,000 characters.");
        switch(kind) {
            case "amount":
                if(!value.matches("-?[0-9]{1,15}(\\.[0-9]{1,6})?"))throw new IllegalArgumentException("Use an exact decimal amount, such as 24.50.");
                new BigDecimal(value);
                if(!qualifier.equals("unknown")) {
                    boolean supported=false;for(java.util.Currency currency:java.util.Currency.getAvailableCurrencies())if(currency.getCurrencyCode().equals(qualifier))supported=true;
                    if(!supported)throw new IllegalArgumentException("Use a supported three-letter currency or unknown.");
                }
                break;
            case "date":
                // java.time is unavailable below API 26 without desugaring. Strict calendar validates all supported APIs.
                String pattern=qualifier.equals("day")?"yyyy-MM-dd":qualifier.equals("month")?"yyyy-MM":qualifier.equals("year")?"yyyy":null;
                if(pattern==null||!value.matches(qualifier.equals("day")?"[0-9]{4}-[0-9]{2}-[0-9]{2}":qualifier.equals("month")?"[0-9]{4}-[0-9]{2}":"[0-9]{4}"))throw new IllegalArgumentException("Use an ISO date and day, month or year precision.");
                java.text.SimpleDateFormat format=new java.text.SimpleDateFormat(pattern,Locale.ROOT);format.setLenient(false);
                try{if(!format.format(format.parse(value)).equals(value))throw new Exception();}catch(Exception invalid){throw new IllegalArgumentException("Enter a valid calendar date.");}
                break;
            case "organization": LibraryRepository.cleanName(value);if(!qualifier.isEmpty())throw new IllegalArgumentException("Organization has no qualifier.");break;
            case "text": if(!qualifier.isEmpty())throw new IllegalArgumentException("Text has no qualifier.");break;
            default: throw new IllegalArgumentException("Unsupported detail type.");
        }
    }
    boolean automatic(){if(!origin.equals("accepted"))return false;try{return new JSONObject(evidence).optString("acceptance_actor").equals("automatic");}catch(JSONException invalid){return false;}}
    JSONObject json() {
        try{return new JSONObject().put("field_id",field).put("kind",kind).put("label",label).put("value",value)
                .put("qualifier",qualifier).put("evidence",evidence).put("origin",origin).put("state",state).put("revision",revision);}
        catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }
    static DetailValue read(JSONObject row) throws JSONException {
        return new DetailValue(row.getString("field_id"),row.getString("kind"),row.getString("label"),row.getString("value"),row.getString("qualifier"),row.getString("evidence"),row.getString("origin"),row.getString("state"),row.getLong("revision"));
    }
}
