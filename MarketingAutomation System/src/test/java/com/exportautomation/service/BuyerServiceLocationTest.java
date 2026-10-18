package com.exportautomation.service;

import com.exportautomation.dto.CreateBuyerRequest;
import com.exportautomation.dto.UpdateBuyerRequest;
import com.exportautomation.entity.Buyer;
import com.exportautomation.repository.BuyerRepository;
import com.exportautomation.repository.CampaignRecipientExclusionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests (no Spring context, no database) for buyer city/state handling:
 * provided locations are stored and an update that does not carry a location
 * never wipes the city/state discovered by buyer search.
 */
@ExtendWith(MockitoExtension.class)
class BuyerServiceLocationTest {

    @Mock
    private BuyerRepository buyerRepository;

    @Mock
    private CampaignRecipientExclusionRepository exclusionRepository;

    @InjectMocks
    private BuyerService buyerService;

    private static CreateBuyerRequest base() {
        CreateBuyerRequest request = new CreateBuyerRequest();
        request.setBuyerName("John Smith");
        request.setCompanyName("Global Imports Ltd.");
        request.setEmail("John@GlobalImports.com");
        request.setCountry("USA");
        request.setProductName("Singing Bowls");
        return request;
    }

    private static UpdateBuyerRequest updateRequest() {
        UpdateBuyerRequest request = new UpdateBuyerRequest();
        request.setBuyerName("John Smith");
        request.setCompanyName("Global Imports Ltd.");
        request.setEmail("john@globalimports.com");
        request.setCountry("USA");
        request.setProductName("Singing Bowls");
        return request;
    }

    private static Buyer existingBuyer() {
        Buyer buyer = new Buyer();
        buyer.setId(5L);
        buyer.setCity("Austin");
        buyer.setState("Texas");
        return buyer;
    }

    private void stubCreate() {
        when(buyerRepository.existsByEmailIgnoreCase("john@globalimports.com")).thenReturn(false);
        when(buyerRepository.save(any(Buyer.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubUpdate() {
        when(buyerRepository.findById(5L)).thenReturn(Optional.of(existingBuyer()));
        when(buyerRepository.existsByEmailIgnoreCaseAndIdNot("john@globalimports.com", 5L)).thenReturn(false);
        when(buyerRepository.save(any(Buyer.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createBuyerStoresTrimmedCityAndState() {
        CreateBuyerRequest request = base();
        request.setCity("  Austin  ");
        request.setState("  Texas ");
        stubCreate();

        Buyer saved = buyerService.createBuyer(request);

        assertEquals("Austin", saved.getCity());
        assertEquals("Texas", saved.getState());
    }

    @Test
    void createBuyerLeavesLocationNullWhenNotProvided() {
        stubCreate();

        Buyer saved = buyerService.createBuyer(base());

        assertNull(saved.getCity());
        assertNull(saved.getState());
    }

    @Test
    void updateBuyerWithoutLocationPreservesExistingCityAndState() {
        stubUpdate();

        Buyer saved = buyerService.updateBuyer(5L, updateRequest());

        assertEquals("Austin", saved.getCity());
        assertEquals("Texas", saved.getState());
    }

    @Test
    void updateBuyerWithLocationReplacesCityAndState() {
        stubUpdate();

        UpdateBuyerRequest request = updateRequest();
        request.setCity("Dallas");
        request.setState("TX");

        Buyer saved = buyerService.updateBuyer(5L, request);

        assertEquals("Dallas", saved.getCity());
        assertEquals("TX", saved.getState());
    }

    @Test
    void updateBuyerWithBlankLocationClearsCityAndState() {
        stubUpdate();

        UpdateBuyerRequest request = updateRequest();
        request.setCity("  ");
        request.setState("");

        Buyer saved = buyerService.updateBuyer(5L, request);

        assertNull(saved.getCity());
        assertNull(saved.getState());
    }
}
