package com.example.ai_service.conversation;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import static com.example.ai_service.conversation.ChatModels.*;

/** Reconciles explicitly closed passenger declarations; never guesses missing ages or totals. */
final class DeclaredParty {
    private static final String UNIT="(?:mot|hai|ba|bon|tu|nam|lam|sau|bay|tam|chin)";
    private static final String NUMBER="(?:[0-9]{1,3}|mot tram|(?:hai|ba|bon|nam|sau|bay|tam|chin) muoi(?: "+UNIT+")?|muoi(?: "+UNIT+")?|khong|"+UNIT+")";
    private static final Pattern GROUP=Pattern.compile("\\b(?:nhom(?: toi)? )?chi (?:co|gom) ("+NUMBER+") nguoi lon([^.!?;\\n]{0,700}?)\\btong ("+NUMBER+") nguoi\\b");
    private static final Pattern CHILD=Pattern.compile("\\b(?:("+NUMBER+") )?(?:be|tre(?: em)?) ("+NUMBER+") tuoi\\b");
    private static final Set<Field> FIELDS=EnumSet.of(Field.ADULTS,Field.CHILDREN,Field.TODDLERS,Field.INFANTS,Field.UNCLASSIFIED_CHILDREN);
    static Plan reconcile(Plan plan,String message) {
        if(plan==null || (plan.intent()!=Intent.SEARCH_TOURS && plan.intent()!=Intent.CLARIFY))return plan;
        String text=Normalizer.normalize(message.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","").replaceAll("[\\t ]+"," ");
        var group=GROUP.matcher(text);
        if(!group.find())return plan;
        String adultsText=group.group(1),childrenText=group.group(2),totalText=group.group(3);
        if(group.find())return clarify(plan); // Two declarations are not silently merged.
        int adults=number(adultsText),total=number(totalText),children=0,toddlers=0,infants=0;
        var child=CHILD.matcher(childrenText);var remainder=new StringBuffer();
        while(child.find()) {
            int count=child.group(1)==null?1:number(child.group(1)),age=number(child.group(2));
            if(count<1 || age<0 || age>100)return clarify(plan);
            if(age<2)infants+=count;else if(age<5)toddlers+=count;else if(age<12)children+=count;else adults+=count;
            child.appendReplacement(remainder,"");
        }
        child.appendTail(remainder);
        String extra=remainder.toString().replaceAll("\\bva\\b","").replaceAll("[\\s,:]","");
        if(!extra.isEmpty() || adults<0 || total<1 || total>100 || adults+children+toddlers+infants!=total)return clarify(plan);
        var changes=new ArrayList<Change>();
        for(var change:plan.changes())if(!FIELDS.contains(change.field()))changes.add(change);
        int[] counts={adults,children,toddlers,infants,0};Field[] fields={Field.ADULTS,Field.CHILDREN,Field.TODDLERS,Field.INFANTS,Field.UNCLASSIFIED_CHILDREN};
        for(int i=0;i<fields.length;i++)changes.add(new Change(fields[i],Action.SET,Integer.toString(counts[i])));
        Hint hint=plan.clarification()==Hint.PASSENGER_DETAILS_REQUIRED?Hint.NONE:plan.clarification();
        Intent intent=plan.intent()==Intent.CLARIFY && plan.clarification()==Hint.PASSENGER_DETAILS_REQUIRED?Intent.SEARCH_TOURS:plan.intent();
        return new Plan(intent,plan.reset(),List.copyOf(changes),plan.positions(),hint);
    }
    private static Plan clarify(Plan plan) {
        return new Plan(Intent.CLARIFY,plan.reset(),plan.changes().stream().filter(c->!FIELDS.contains(c.field())).toList(),List.of(),Hint.PASSENGER_DETAILS_REQUIRED);
    }
    private static int number(String word) {
        if(word.matches("[0-9]{1,3}"))return Integer.parseInt(word);
        if(word.equals("mot tram"))return 100;
        var parts=word.split(" ");
        if(parts.length==1)return unit(parts[0]);
        if(parts[0].equals("muoi"))return 10+unit(parts[1]);
        return unit(parts[0])*10+(parts.length==3?unit(parts[2]):0);
    }
    private static int unit(String word) {
        return switch(word){case "khong"->0;case "mot"->1;case "hai"->2;case "ba"->3;case "bon","tu"->4;case "nam","lam"->5;case "sau"->6;case "bay"->7;case "tam"->8;case "chin"->9;case "muoi"->10;default->-1;};
    }
}
