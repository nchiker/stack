package org.stackdocs.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Fictional counterexamples for the user-reported address-as-title failure; no private text. */
final class NamingChecks {
    static void run(Consumer<String> mark) {
        List<String> available=Arrays.asList("bills","medical","receipts","banking","ids","house");
        String body="\nMedical invoice\nPatient name Synthetic\nAmount due 25\nDue date May 2";
        String[] headers={"42 Cedar Street\nMaple Veterinary Clinic","42\nCedar Street\nMaple Veterinary Clinic",
            "Cedar St.\nSuite 2\nMaple Veterinary Clinic","Pineville, NY\nMaple Veterinary Clinic",
            "Tel (555) 010-1234\nMaple Veterinary Clinic","contact@example.invalid\nMaple Veterinary Clinic",
            "PO Box 40\nMaple Veterinary Clinic","London SW1A 1AA\nMaple Veterinary Clinic",
            "Toronto ON M5V 2T6\nMaple Veterinary Clinic","INVOICE\n42 Cedar Street\nMaple Veterinary Clinic"};
        for(String header:headers) {
            SuggestionRules.Result result=SuggestionRules.analyze(Arrays.asList(header+body),available,false,false);
            WorkflowRepositoryChecks.require(result.title.equals("Maple Veterinary Clinic · Bill"),"Issuer beats address/contact/generic invoice: "+header);
            WorkflowRepositoryChecks.require(result.stacks.containsKey("bills") && result.stacks.containsKey("medical"),"Classification retained");
        }
        SuggestionRules.Result noIssuer=SuggestionRules.analyze(Arrays.asList("42 Cedar Street\nPineville, NY\nTel (555) 010-1234\nAmount due 25\nDue date May 2"),available,false,false);
        WorkflowRepositoryChecks.require(noIssuer.title.isEmpty() && noIssuer.stacks.containsKey("bills"),"Address-only evidence abstains on name while retaining filing");
        SuggestionRules.Result type=SuggestionRules.analyze(Arrays.asList("42 Cedar Street\nInvoice\nAmount due 25"),available,false,false);
        WorkflowRepositoryChecks.require(type.title.equals("Invoice"),"Supported generic document heading remains available");
        SuggestionRules.Result later=SuggestionRules.analyze(Arrays.asList("42 Cedar Street\nAmount due 25","Maple Veterinary Clinic\nMedical invoice\nPatient name Synthetic"),available,false,false);
        WorkflowRepositoryChecks.require(later.title.isEmpty(),"Later-page issuer does not become an invented first-page heading");
        mark.accept("Address/contact/city/postal/split-line and generic-Invoice counterexamples prefer actual veterinary issuer + Bill; address-only name abstention retains categories; no later-page heading invention");
    }
}
