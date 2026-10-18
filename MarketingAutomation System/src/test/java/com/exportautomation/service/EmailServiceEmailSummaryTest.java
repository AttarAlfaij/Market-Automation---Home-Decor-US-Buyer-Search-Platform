package com.exportautomation.service;

import com.exportautomation.repository.EmailLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests (no Spring context, no network, no database) for the date-ranged
 * email summary that backs the Email Tracking summary view and its donut
 * chart: counts per status, inclusive day boundaries and range validation.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceEmailSummaryTest {

    @Mock
    private EmailLogRepository emailLogRepository;

    @InjectMocks
    private EmailService emailService;

    private static EmailLogRepository.StatusCountRow row(String status, long count) {
        return new EmailLogRepository.StatusCountRow() {
            @Override
            public String getStatus() {
                return status;
            }

            @Override
            public long getCnt() {
                return count;
            }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void allTimeSummaryAggregatesStatusCountsAndPercentages() {
        when(emailLogRepository.countGroupedByStatus())
                .thenReturn(List.of(row("SENT", 8), row("FAILED", 2)));

        Map<String, Object> summary = emailService.getEmailSummary(null, null);

        assertEquals(10L, summary.get("total"));
        assertEquals(8L, summary.get("successful"));
        assertEquals(2L, summary.get("failed"));
        assertNull(summary.get("startDate"));
        assertNull(summary.get("endDate"));

        List<Map<String, Object>> counts = (List<Map<String, Object>>) summary.get("statusCounts");
        assertEquals(2, counts.size());
        assertEquals("SENT", counts.get(0).get("status"));
        assertEquals(8L, counts.get(0).get("count"));
        assertEquals(80L, counts.get(0).get("percentage"));
        assertEquals("FAILED", counts.get(1).get("status"));
        assertEquals(2L, counts.get(1).get("count"));
        assertEquals(20L, counts.get(1).get("percentage"));

        verify(emailLogRepository).countGroupedByStatus();
    }

    @Test
    void dateRangeUsesInclusiveDayBoundaries() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        when(emailLogRepository.countGroupedByStatusBetween(any(), any()))
                .thenReturn(List.of(row("SENT", 5)));

        Map<String, Object> summary = emailService.getEmailSummary(start, end);

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(emailLogRepository).countGroupedByStatusBetween(fromCaptor.capture(), toCaptor.capture());

        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), fromCaptor.getValue());
        // The end day is inclusive: the query must run up to the start of Feb 1.
        assertEquals(LocalDateTime.of(2026, 2, 1, 0, 0), toCaptor.getValue());
        assertEquals("2026-01-01", summary.get("startDate"));
        assertEquals("2026-01-31", summary.get("endDate"));
        assertEquals(5L, summary.get("total"));
    }

    @Test
    void blankOrNullStatusIsReportedAsUnknown() {
        when(emailLogRepository.countGroupedByStatus()).thenReturn(List.of(row(null, 3)));

        Map<String, Object> summary = emailService.getEmailSummary(null, null);

        List<Map<String, Object>> counts = (List<Map<String, Object>>) summary.get("statusCounts");
        assertEquals(1, counts.size());
        assertEquals("UNKNOWN", counts.get(0).get("status"));
        assertEquals(3L, counts.get(0).get("count"));
        assertEquals(100L, counts.get(0).get("percentage"));
    }

    @Test
    void summaryWithoutEmailsReturnsZeroTotal() {
        when(emailLogRepository.countGroupedByStatus()).thenReturn(List.of());

        Map<String, Object> summary = emailService.getEmailSummary(null, null);

        assertEquals(0L, summary.get("total"));
        assertEquals(0L, summary.get("successful"));
        assertEquals(0L, summary.get("failed"));
        assertEquals(0, ((List<?>) summary.get("statusCounts")).size());
    }

    @Test
    void rejectsHalfOpenDateRange() {
        assertThrows(IllegalArgumentException.class,
                () -> emailService.getEmailSummary(LocalDate.of(2026, 1, 1), null));
        assertThrows(IllegalArgumentException.class,
                () -> emailService.getEmailSummary(null, LocalDate.of(2026, 1, 31)));
        verifyNoInteractions(emailLogRepository);
    }

    @Test
    void rejectsStartDateAfterEndDate() {
        assertThrows(IllegalArgumentException.class,
                () -> emailService.getEmailSummary(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)));
        verifyNoInteractions(emailLogRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void statusesAreNormalisedToUpperCase() {
        when(emailLogRepository.countGroupedByStatus())
                .thenReturn(List.of(row("sent", 4), row("FAILED", 1)));

        Map<String, Object> summary = emailService.getEmailSummary(null, null);

        List<Map<String, Object>> counts = (List<Map<String, Object>>) summary.get("statusCounts");
        assertEquals("SENT", counts.get(0).get("status"));
        // SENT/FAILED lookups (successful/failed counts) must still match.
        assertEquals(4L, summary.get("successful"));
        assertEquals(1L, summary.get("failed"));
    }
}
