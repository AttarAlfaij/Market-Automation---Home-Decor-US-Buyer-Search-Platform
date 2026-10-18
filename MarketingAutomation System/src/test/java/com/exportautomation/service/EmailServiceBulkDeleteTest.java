package com.exportautomation.service;

import com.exportautomation.entity.Buyer;
import com.exportautomation.entity.Campaign;
import com.exportautomation.entity.CampaignRecipientExclusion;
import com.exportautomation.exception.ResourceNotFoundException;
import com.exportautomation.repository.BuyerRepository;
import com.exportautomation.repository.CampaignRecipientExclusionRepository;
import com.exportautomation.repository.CampaignRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests (no Spring context, no network) for the campaign bulk-delete
 * flow: only the selected buyers are excluded from THIS campaign, and no buyer
 * rows, campaigns or unrelated records are ever deleted.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceBulkDeleteTest {

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private BuyerRepository buyerRepository;

    @Mock
    private CampaignRecipientExclusionRepository exclusionRepository;

    @InjectMocks
    private EmailService emailService;

    private static Campaign campaign(long id) {
        Campaign c = new Campaign();
        c.setId(id);
        return c;
    }

    private static Buyer buyer(long id) {
        Buyer b = new Buyer();
        b.setId(id);
        return b;
    }

    private void stubCampaign(long id) {
        when(campaignRepository.findById(id)).thenReturn(Optional.of(campaign(id)));
    }

    private void stubBuyers(long... ids) {
        for (long id : ids) {
            when(buyerRepository.findById(id)).thenReturn(Optional.of(buyer(id)));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void bulkDeleteRemovesOnlySelectedBuyersFromCampaign() {
        stubCampaign(1L);
        stubBuyers(1L, 2L, 3L);
        when(exclusionRepository.findBuyerIdsByCampaignIdAndBuyerIdIn(eq(1L), any()))
                .thenReturn(List.of(3L));

        ArgumentCaptor<List<CampaignRecipientExclusion>> captor = ArgumentCaptor.forClass((Class) List.class);
        when(exclusionRepository.saveAll(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = emailService.removeRecipientsFromCampaign(1L, List.of(1L, 2L, 3L));

        assertEquals(Long.valueOf(1L), result.get("campaignId"));
        assertEquals(Integer.valueOf(3), result.get("deleted"));
        assertEquals(List.of(1L, 2L, 3L), result.get("buyerIds"));

        List<CampaignRecipientExclusion> saved = captor.getValue();
        assertEquals(2, saved.size());
        assertEquals(1L, saved.get(0).getBuyerId());
        assertEquals(2L, saved.get(1).getBuyerId());
        assertEquals(1L, saved.get(0).getCampaignId());
        assertEquals(1L, saved.get(1).getCampaignId());

        verify(buyerRepository, never()).delete(any(Buyer.class));
        verify(campaignRepository, never()).delete(any(Campaign.class));
        verify(campaignRepository, never()).deleteById(anyLong());
        verify(exclusionRepository, never()).deleteByBuyerId(anyLong());
        verify(exclusionRepository, never()).deleteByCampaignId(anyLong());
    }

    @Test
    void bulkDeleteDeduplicatesSelectedBuyerIds() {
        stubCampaign(1L);
        stubBuyers(1L, 2L);
        when(exclusionRepository.findBuyerIdsByCampaignIdAndBuyerIdIn(eq(1L), any()))
                .thenReturn(List.of());
        when(exclusionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = emailService.removeRecipientsFromCampaign(1L, List.of(1L, 1L, 2L));

        assertEquals(Integer.valueOf(2), result.get("deleted"));
        assertEquals(List.of(1L, 2L), result.get("buyerIds"));
    }

    @Test
    void bulkDeleteSkipsBuyersAlreadyExcluded() {
        stubCampaign(1L);
        stubBuyers(1L, 2L);
        when(exclusionRepository.findBuyerIdsByCampaignIdAndBuyerIdIn(eq(1L), any()))
                .thenReturn(List.of(1L, 2L));

        Map<String, Object> result = emailService.removeRecipientsFromCampaign(1L, List.of(1L, 2L));

        assertEquals(Integer.valueOf(2), result.get("deleted"));
        verify(exclusionRepository, never()).saveAll(any());
        verify(buyerRepository, never()).delete(any(Buyer.class));
    }

    @Test
    void bulkDeleteRejectsEmptySelection() {
        assertThrows(IllegalArgumentException.class,
                () -> emailService.removeRecipientsFromCampaign(1L, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> emailService.removeRecipientsFromCampaign(1L, null));
        verifyNoInteractions(campaignRepository, buyerRepository, exclusionRepository);
    }

    @Test
    void bulkDeleteRejectsUnknownCampaign() {
        when(campaignRepository.findById(9L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> emailService.removeRecipientsFromCampaign(9L, List.of(1L)));

        assertTrue(ex.getMessage().contains("Campaign not found"));
        verifyNoInteractions(buyerRepository, exclusionRepository);
    }

    @Test
    void bulkDeleteRejectsUnknownBuyerWithoutDeletingAnything() {
        stubCampaign(1L);
        when(buyerRepository.findById(1L)).thenReturn(Optional.of(buyer(1)));
        when(buyerRepository.findById(99L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> emailService.removeRecipientsFromCampaign(1L, List.of(1L, 99L)));

        assertTrue(ex.getMessage().contains("Buyer not found with id: 99"));
        verifyNoInteractions(exclusionRepository);
        verify(buyerRepository, never()).delete(any(Buyer.class));
    }
}
