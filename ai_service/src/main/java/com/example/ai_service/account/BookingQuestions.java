package com.example.ai_service.account;

import com.example.ai_service.advisor.PolicyCatalog;
import java.util.regex.Pattern;

/** Routes read-only personal questions locally; no booking text or facts are sent to the model. */
public final class BookingQuestions {
    private BookingQuestions() {}
    private static final Pattern ID=Pattern.compile("(?:booking id|ma booking|booking|ma dat cho|ma dat tour|ma don|don so|booking so)\\s*#?\\s*(\\d{1,10})(?!\\d)");
    public static String text(String value) { return PolicyCatalog.normalize(value); }
    public static Integer explicitId(String message) {
        var match=ID.matcher(text(message));
        if(!match.find()) return null;
        try { return Integer.valueOf(match.group(1)); } catch(NumberFormatException invalid) { return -1; }
    }
    public static boolean personal(String message,Integer selected) {
        String s=" "+text(message)+" ";
        if(explicitId(message)!=null) return true;
        if(has(s,"don cua toi","don cua minh","booking cua toi","booking cua minh","tour da dat","toi da dat",
                "minh da dat","em da dat","toi dat tour","minh dat tour","toi chua thanh toan","toi chua tra tien",
                "lich su dat","kiem tra booking","tra cuu booking","tra cuu don","toi con bao lau","con may ngay",
                "don cua nguoi khac","booking cua nguoi khac")) return true;
        boolean subject=has(s,"toi","minh","em","cua toi","cua minh");
        boolean fact=has(s,"thanh toan","tra tien","giu cho","dat cho","dat tour","booking","hoa don","han");
        if(subject && fact && has(s,"da","chua","con","khi nao","bao lau","kiem tra","tinh trang","trang thai")) return true;
        return selected!=null && has(s,"con bao lau","con bao nhieu","han luc nao","het han chua","tra tien khi nao",
                "da tra tien chua","da thanh toan chua","con cho khong","don nay","tour nay");
    }
    public static boolean has(String normalized,String... phrases) {
        String s=" "+normalized.strip()+" ";
        for(String p:phrases) if(s.contains(" "+p+" ")) return true;
        return false;
    }
}
