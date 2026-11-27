package com.example.booking_service.operations;

import com.example.booking_service.dto.response.ApiResponse;
import com.example.booking_service.reservation.BookingAccess;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/admin/audit-logs")
public class AdminAuditController {
    private final BookingAccess access;
    private final ReportingOperationsClient reports;

    public AdminAuditController(BookingAccess access, ReportingOperationsClient reports) {
        this.access = access;
        this.reports = reports;
    }

    @GetMapping
    public ApiResponse<OperationsInsights.AuditPage> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam Map<String, String> input) {
        requireAdmin();
        var filters = new LinkedHashMap<String,String>();
        for (String key : Set.of("keyword", "actorId", "actionGroup", "targetType", "fromDate", "toDate")) {
            String value = input.get(key);
            if (value != null && !value.isBlank()) filters.put(key, value.strip());
        }
        return ApiResponse.<OperationsInsights.AuditPage>builder().result(reports.adminAudit(page, size, filters)).build();
    }

    @GetMapping("/actors")
    public ApiResponse<java.util.List<Map<String,String>>> actors(@RequestParam(defaultValue = "") String keyword) {
        requireAdmin();
        if (keyword.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Từ khóa quá dài");
        return ApiResponse.<java.util.List<Map<String,String>>>builder().result(reports.auditActors(keyword)).build();
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String,Object>> detail(@PathVariable long id) {
        requireAdmin();
        return ApiResponse.<Map<String,Object>>builder().result(reports.adminAuditDetail(id)).build();
    }

    private void requireAdmin() {
        if (!access.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cần quyền quản trị để xem nhật ký");
    }
}
