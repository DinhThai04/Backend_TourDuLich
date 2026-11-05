package com.example.ai_service.security;

import com.example.ai_service.account.BookingQuestions;
import com.example.ai_service.conversation.ChatFailure;
import com.example.ai_service.conversation.ChatModels;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Early rejection only. The security boundary remains typed plans, fixed read APIs and no execution tools.
 *
 * <p>The single documented request limit is {@link #MAX_MESSAGE_POINTS} = 16000 code points (UTF-8 worst
 * case 64000 bytes, well below the model context). {@code ChatBodyLimit} accepts that worst case plus the
 * JSON envelope and answers 413 beyond it, so the transport and validation caps agree. The prompt
 * byte/token budget in {@code BudgetedLanguageModel} stays a separate, stricter guard for model calls.
 */
public final class AiInputPolicy {
    public static final int MAX_MESSAGE_POINTS = ChatModels.MAX_MESSAGE_POINTS;
    /** Supported ceiling for the documented cap; a larger value would exceed the bounded request body. */
    public static final int MAX_SUPPORTED_MESSAGE_POINTS = 16000;

    private AiInputPolicy() {}
    private static Pattern pattern(String value) { return Pattern.compile(value,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE|Pattern.DOTALL); }
    private static final List<Pattern> SYNTAX=List.of(
        pattern("```|/\\*|\\*/|<\\s*(?:/?[a-z][a-z0-9:_-]*\\b[^>]{0,200}>|!doctype|\\?php)|\\bon(?:error|load)\\s*=|(?:javascript|vbscript|data|file|gopher)\\s*:"),
        pattern("<\\|(?:im_start|im_end|system|assistant|developer|tool)[^>]{0,30}>|\\[/?INST\\]|<<\\s*/?SYS\\s*>>|\\[\\s*(?:system|developer|tool)\\s*\\]|[\"']role[\"']\\s*:\\s*[\"'](?:system|developer|tool)[\"']"),
        pattern("\\bselect\\s+(?:\\*|[a-z0-9_`.\\\"()]+(?:\\s*,\\s*[a-z0-9_`.\\\"()]+)*)\\s+from\\b|\\b(?:insert\\s+into|delete\\s+from|union\\s+(?:all\\s+)?select)\\b|\\bupdate\\s+[a-z0-9_`.]+\\s+set\\b|\\b(?:drop|alter|truncate|create)\\s+(?:table|database|schema)\\b|\\binformation_schema\\b|\\b(?:show\\s+(?:tables|databases|schemas|variables)|xp_cmdshell|pg_read_file|pg_sleep)\\b|@@(?:version|datadir)|\\b(?:sleep|benchmark|load_file)\\s*\\("),
        pattern("(?:['\";]\\s*(?:--|/\\*))|\\b(?:or|and)\\s+(?:[0-9]+|['\"][^'\"]{0,30}['\"])\\s*=\\s*(?:[0-9]+|['\"])|\\$\\s*(?:where|ne|regex|function)\\b"),
        pattern("\\b(?:function|def)\\s+[a-z_$][a-z0-9_$]*\\s*\\(|\\b(?:public|private)\\s+(?:static\\s+)?(?:class|void|string|int)\\b|\\b(?:console\\.log|eval|exec|system|Runtime\\.getRuntime|ProcessBuilder)\\s*\\(|System\\.out\\.|\\b(?:import|package)\\s+[a-z0-9_.]+\\s*;|=>\\s*\\{"),
        pattern("\\$\\{|\\$\\(|\\{\\{|\\b(?:curl|wget)\\s+https?://|\\brm\\s+-[a-z]*r[a-z]*f\\b|\\b(?:cmd\\s+/c|powershell\\s+-|npm\\s+(?:install|exec)|pip\\s+install)\\b|(?:\\.\\.[/\\\\])|/etc/(?:passwd|shadow)|[a-z]:\\\\(?:windows|users)\\\\"),
        pattern("\\b(?:gsk_|sk-(?:proj-)?)[a-z0-9_-]{16,}|\\bBearer\\s+[a-z0-9._~-]{20,}|[a-z0-9_-]{12,}\\.[a-z0-9_-]{12,}\\.[a-z0-9_-]{12,}|\\b(?:api[_ -]?key|password)\\s*[:=]\\s*\\S{4,}")
    );
    private static final List<Pattern> INSTRUCTIONS=List.of(
        pattern("\\b(?:ignore|disregard|forget|override|bypass|discard|overrule)\\b.{0,100}\\b(?:previous|prior|system|developer|instructions|rules|policy|schema|safety|constraints)\\b|\\b(?:bo qua|quen|ghi de|vo hieu hoa|vuot qua|lach)\\b.{0,100}\\b(?:huong dan|chi dan|quy tac|quy dinh|chinh sach|chi thi|system|developer|prompt|bao mat|gioi han|schema)\\b|\\bbo\\s+(?:(?:cac|nhung|toan bo|tat ca)\\s+)?(?:quy tac|quy dinh|gioi han|huong dan|chi dan)\\b"),
        pattern("\\b(?:reveal|show|print|expose|dump|leak|hien thi|in ra|tiet lo|cho xem|doc|lay|cho toi)\\b.{0,100}\\b(?:system prompt|developer prompt|hidden instructions|environment variables|api key|secret key|jwt|password hash|database password|bien moi truong|chi dan noi bo|cau hinh noi bo|mat khau database|mat khau mysql)\\b|\\.env\\b"),
        pattern("\\b(?:you are now|act as|pretend to be|tu gio|hay dong vai|gia lam|dong vai)\\b.{0,100}\\b(?:developer|programmer|dba|admin|system|unrestricted|dan|jailbreak|khong gioi han|toan quyen|lap trinh vien)\\b"),
        pattern("\\b(?:viet|tao|sinh|lam|xay dung|chay|thuc thi|sua|debug|giai thich|huong dan|giup|help|write|generate|build|implement|run|execute|fix|explain|teach|provide|give)\\b.{0,120}\\b(?:code|ma nguon|lap trinh|javascript|typescript|python|sql|html|css|script|algorithm|function|spring ?boot|react|backend|frontend)\\b|\\b(?:viet|tao)\\s+(?:mot\\s+)?ham\\b|\\b(?:write|code|program)\\s+(?:in\\s+)?(?:java|c\\+\\+|c#)\\b"),
        pattern("\\b(?:write|code|program)\\s+(?:(?:in|a|an)\\s+)?(?:java\\b|c\\+\\+(?!\\w)|c#(?!\\w))|\\b(?:viet|tao|lap)\\s+(?:mot\\s+)?(?:chuong trinh|ung dung)\\s+(?:bang\\s+)?(?:java\\b|python\\b|c\\+\\+(?!\\w)|c#(?!\\w))"),
        pattern("\\b(?:decode|giai ma|giai nen)\\b|\\b(?:base64|rot13|hex|unicode|urlencode|ma hoa)\\b.{0,100}\\b(?:execute|run|thuc thi|chay|lam theo|prompt|instructions|code|sql)\\b"),
        pattern("\\b(?:viet|sang tac|lam|giai|dich|write|compose|solve|translate)\\b.{0,80}\\b(?:bai tho|tho tinh|bai van|tieu luan|bai tap|phuong trinh|truyen ngan|loi bai hat|poem|essay|homework|equation|lyrics|source code)\\b"),
        pattern("\\b(?:mat khau|api key)\\s*[:=]\\s*\\S{4,}")
    );

    public static void requireMessage(String message) {
        if(message==null || message.isBlank() || message.codePointCount(0,message.length())>MAX_MESSAGE_POINTS) throw ChatFailure.invalid();
        for(int i=0;i<message.length();) {
            char first=message.charAt(i);
            if(Character.isSurrogate(first) && (!Character.isHighSurrogate(first) || i+1>=message.length() || !Character.isLowSurrogate(message.charAt(i+1)))) throw ChatFailure.invalid();
            int cp=message.codePointAt(i);int type=Character.getType(cp);
            if((Character.isISOControl(cp) && cp!='\n' && cp!='\r' && cp!='\t') || type==Character.FORMAT || type==Character.PRIVATE_USE || type==Character.UNASSIGNED) throw ChatFailure.invalid();
            i+=Character.charCount(cp);
        }
        if(prohibited(message)) throw new ChatFailure(422,"AI_SCOPE_REJECTED");
    }
    private static boolean prohibited(String message) {
        String canonical=Normalizer.normalize(message,Normalizer.Form.NFKC);
        if(SYNTAX.stream().anyMatch(p->p.matcher(canonical).find())) return true;
        String folded=Normalizer.normalize(canonical,Normalizer.Form.NFD).replaceAll("\\p{M}+","")
                .replace('đ','d').replace('Đ','D').toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}]+"," ");
        // Website identifiers/promotions are not requests to program. Only remove these exact phrases.
        folded=folded.replaceAll("\\b(?:tour|booking|transaction|promo|discount|coupon|voucher) code\\b|\\bcode (?:tour|giam gia)\\b","identifier");
        String text=folded;
        return INSTRUCTIONS.stream().anyMatch(p->p.matcher(text).find());
    }
    public static boolean safeHistory(String message) {
        try { requireMessage(message); return !BookingQuestions.personal(message,null); }
        catch(ChatFailure rejected) { return false; }
    }
    public static boolean locationName(String value) {
        return value!=null && !value.isBlank() && value.length()<=120 && !value.contains("..")
                && value.matches("[\\p{L}\\p{M}\\p{N} .,'’()/&\\p{Pd}]+") && !prohibited(value);
    }
}
