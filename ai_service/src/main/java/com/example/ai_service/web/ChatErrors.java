package com.example.ai_service.web;

import com.example.ai_service.account.BookingHelpController;
import com.example.ai_service.conversation.ChatFailure;
import com.example.ai_service.model.ModelFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes={ChatController.class,PolicyController.class,BookingHelpController.class})
public class ChatErrors {
    private static final Logger LOG=LoggerFactory.getLogger(ChatErrors.class);
    public record ErrorDetail(String type,boolean retryable,Integer retryAfterSeconds) {}
    public record ErrorBody(int code,String message,Object result,String requestId,ErrorDetail error) {}
    @ExceptionHandler(ChatFailure.class)
    public ResponseEntity<ErrorBody> chat(ChatFailure failure,HttpServletRequest request) {
        String type=failure.type();
        boolean retryable="CONVERSATION_BUSY".equals(type) || "CATALOG_UNAVAILABLE".equals(type)
                || "BOOKING_UNAVAILABLE".equals(type) || "AI_CAPACITY_REACHED".equals(type)
                || "AI_SESSION_LIMIT".equals(type);
        Integer retryAfter=failure.retryAfterSeconds()!=null?failure.retryAfterSeconds()
                :("CONVERSATION_BUSY".equals(type)?1:null);
        return response(failure.status(),type,retryable,retryAfter,(String)request.getAttribute("ai.requestId"));
    }
    @ExceptionHandler(ModelFailure.class)
    public ResponseEntity<ErrorBody> model(ModelFailure failure,HttpServletRequest request) {
        return response(failure.httpStatus(),failure.type().name(),failure.retryable(),failure.retryAfterSeconds(),(String)request.getAttribute("ai.requestId"));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorBody> invalid() { return response(400,"INVALID_REQUEST",false,null,null); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorBody> mediaType() { return response(415,"INVALID_REQUEST",false,null,null); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception failure,HttpServletRequest request) {
        String requestId=(String)request.getAttribute("ai.requestId");
        LOG.error("ai_request_failed request_id={} exception={}",requestId,failure.getClass().getName());
        return response(500,"AI_INTERNAL_ERROR",false,null,requestId);
    }
    static ResponseEntity<ErrorBody> response(int status,String type,boolean retryable,Integer retryAfter,String requestId) {
        String message=switch(type) {
            case "AI_INPUT_TOO_LARGE" -> "Nội dung và ngữ cảnh tư vấn quá dài. Bạn vui lòng rút gọn câu hỏi hoặc bắt đầu phiên mới.";
            case "AI_QUOTA_EXHAUSTED" -> "Hạn mức tư vấn hiện đã hết. Bạn vẫn có thể tìm tour bằng bộ lọc trên website.";
            case "CONVERSATION_EXPIRED" -> "Phiên tư vấn đã hết hạn hoặc không còn tồn tại. Bạn vui lòng bắt đầu phiên mới.";
            case "CONVERSATION_BUSY" -> "Phiên đang xử lý một tin nhắn. Bạn vui lòng chờ phản hồi trước.";
            case "REQUEST_ID_CONFLICT" -> "Mã yêu cầu đã được dùng cho nội dung khác.";
            case "INVALID_REQUEST","PAYLOAD_TOO_LARGE" -> "Yêu cầu tư vấn không hợp lệ hoặc vượt giới hạn.";
            case "NOT_FOUND" -> "Không tìm thấy tài nguyên.";
            case "METHOD_NOT_ALLOWED" -> "Phương thức không được hỗ trợ cho đường dẫn này.";
            case "AI_SCOPE_REJECTED" -> "Trợ lý SGU Tour chỉ hỗ trợ tour, lịch trình và chính sách trên website. Không xử lý yêu cầu viết/chạy code, SQL hoặc truy cập hệ thống nội bộ.";
            default -> "Dịch vụ tư vấn tạm thời chưa sẵn sàng. Bạn vẫn có thể tìm tour bằng bộ lọc trên website.";
        };
        var builder=ResponseEntity.status(status);
        if(retryAfter!=null) builder.header("Retry-After",retryAfter.toString());
        return builder.body(new ErrorBody(status*10,message,null,requestId,new ErrorDetail(type,retryable,retryAfter)));
    }
}
