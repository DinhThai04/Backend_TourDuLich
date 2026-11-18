package com.example.ai_service.advisor;

/** Page context is used only for recognized clauses without another named target or unsupported date filter. */
public record TourPageQuestion(boolean price, boolean itinerary, boolean firstDay, DepartureWindow departures) {
    public enum DepartureWindow { UPCOMING, THIS_WEEKEND, NEXT_WEEK }

    public static TourPageQuestion parse(String message) {
        boolean price = false;
        boolean itinerary = false;
        boolean firstDay = false;
        DepartureWindow departures = null;
        for (String part : message.split("[,;]+")) {
            String normalized = PolicyCatalog.normalize(part);
            for (String clause : normalized.split("\\s+va\\s+")) {
                String text = clause.replaceFirst("^(cho toi hoi |cho minh hoi |cho toi xem |cho minh xem |toi muon biet |minh muon biet |xem )", "")
                        .replaceAll("\\b(?:tour|chuyen di) nay\\b", "").replaceAll("\\s+", " ").strip();
                text = text.replaceFirst(" (?:vay|the|a|nhe)$", "");
                if (text.matches("(?:gia(?: tham khao)?(?: nguoi lon)?(?: la)?(?: bao nhieu(?: tien)?)?|bao nhieu(?: tien)?|chi phi bao nhieu)")) {
                    price = true;
                } else if (text.matches("ngay (?:dau|dau tien|1) di dau")) {
                    itinerary = true;
                    firstDay = true;
                } else if (text.matches("lich trinh(?: nhu the nao)?|di nhung dau")) {
                    itinerary = true;
                } else {
                    var window = departureWindow(text);
                    if (window == null || (departures != null && departures != window)) return null;
                    departures = window;
                }
            }
        }
        return price || itinerary || departures != null ? new TourPageQuestion(price, itinerary, firstDay, departures) : null;
    }

    private static DepartureWindow departureWindow(String text) {
        var window = DepartureWindow.UPCOMING;
        if (text.matches(".*\\bcuoi tuan(?: nay)?\\b.*")) {
            window = DepartureWindow.THIS_WEEKEND;
            text = text.replaceFirst("\\bcuoi tuan(?: nay)?\\b", "");
        } else if (text.matches(".*\\btuan sau\\b.*")) {
            window = DepartureWindow.NEXT_WEEK;
            text = text.replaceFirst("\\btuan sau\\b", "");
        }
        text = text.replaceAll("\\s+", " ").strip();
        return text.matches("(?:con |co )?(?:lich khoi hanh|chuyen|lich di|chuyen di|khoi hanh|lich)(?: nao| gan nhat| sap toi)?(?: khong| khi nao| ngay nao)?|khi nao (?:di|khoi hanh)|ngay nao (?:di|khoi hanh)")
                ? window : null;
    }
}
