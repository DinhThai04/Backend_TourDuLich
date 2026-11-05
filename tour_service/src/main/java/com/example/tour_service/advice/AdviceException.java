package com.example.tour_service.advice;

public class AdviceException extends RuntimeException {
    private final int status;
    public AdviceException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
    public static AdviceException invalid(String message) { return new AdviceException(400, message); }
    public static AdviceException unavailable() {
        return new AdviceException(503, "Chưa thể kiểm tra dữ liệu tour. Vui lòng thử lại sau.");
    }
}
