package com.example.ai_service.catalog;

import com.example.ai_service.conversation.ChatFailure;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.util.Set;

/** HTML-to-plain-text only, no browser, URL fetch or execution. Output must still be rendered as React text. */
public final class CatalogText {
    private CatalogText() {}
    public record Cleaned(String text,boolean truncated) {}
    public static Cleaned clean(String source,int limit) {
        if(source==null) return new Cleaned("",false);
        if(source.length()>128000) throw ChatFailure.catalog();
        var text=new StringBuilder();
        var hidden=Set.of("script","style","head","iframe","object","template");
        try {
            new ParserDelegator().parse(new StringReader(source),new HTMLEditorKit.ParserCallback() {
                int depth;
                @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attributes,int position) {
                    if(hidden.contains(tag.toString())) depth++;
                    if(depth==0) text.append(' ');
                }
                @Override public void handleEndTag(HTML.Tag tag,int position) {
                    if(hidden.contains(tag.toString()) && depth>0) depth--;
                    if(depth==0) text.append(' ');
                }
                @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet attributes,int position) {
                    if(hidden.contains(tag.toString())) {
                        if(attributes.getAttribute(HTML.Attribute.ENDTAG)!=null) depth=Math.max(0,depth-1); else depth++;
                    }
                    if(depth==0) text.append(' ');
                }
                @Override public void handleText(char[] data,int position) { if(depth==0) text.append(data).append(' '); }
            },true);
        } catch(IOException impossible) { throw ChatFailure.catalog(); }
        String value=text.toString().replace('<',' ').replace('>',' ').replaceAll("[\\p{Cntrl}\\s\\u00a0]+"," ").strip();
        int length=value.codePointCount(0,value.length());
        if(length<=limit) return new Cleaned(value,false);
        return new Cleaned(value.substring(0,value.offsetByCodePoints(0,Math.max(0,limit-1)))+"…",true);
    }
}
