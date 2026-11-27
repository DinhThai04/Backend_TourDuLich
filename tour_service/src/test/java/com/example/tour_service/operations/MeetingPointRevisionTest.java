package com.example.tour_service.operations;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MeetingPointRevisionTest {
    @Test void editingOnlyOneStopKeepsOtherSelectionsAndRestoringContentDoesNotRestoreOldKey() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new MeetingPointService(jdbc, mock(OperationAudit.class));
        when(jdbc.queryForList(anyString(), eq(1))).thenReturn(List.of(java.util.Map.of("id", 1)));
        var oldA = new DepartureMeetingPoint("A", null, LocalTime.of(6, 0), null, "original-a");
        var oldB = new DepartureMeetingPoint("B", null, LocalTime.of(6, 15), null, "original-b");
        var movedA = new DepartureMeetingPoint("A", null, LocalTime.of(6, 5), null);

        options(jdbc, List.of(oldA, oldB));
        var afterEdit = service.replace(1, List.of(oldB, movedA));
        assertEquals(oldB.confirmationKey(), afterEdit.getFirst().confirmationKey());
        assertNotEquals(oldA.confirmationKey(), afterEdit.getLast().confirmationKey());

        options(jdbc, afterEdit);
        var restored = service.replace(1, List.of(oldA, oldB));
        assertNotEquals(oldA.confirmationKey(), restored.getFirst().confirmationKey());
        assertNotEquals(afterEdit.getLast().confirmationKey(), restored.getFirst().confirmationKey());
        assertEquals(oldB.confirmationKey(), restored.getLast().confirmationKey());

        options(jdbc, List.of(oldB));
        var reinserted = service.replace(1, List.of(oldB, oldA));
        assertNotEquals(oldA.confirmationKey(), reinserted.getLast().confirmationKey());
    }

    @SuppressWarnings("unchecked")
    private static void options(JdbcTemplate jdbc, List<DepartureMeetingPoint> points) {
        when(jdbc.query(contains("UNION ALL"), any(RowMapper.class), eq(1), eq(1))).thenReturn(points);
    }
}
