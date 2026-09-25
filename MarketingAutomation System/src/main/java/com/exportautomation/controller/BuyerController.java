package com.exportautomation.controller;

import com.exportautomation.dto.ApiResponse;
import com.exportautomation.dto.BatchClassifyResponse;
import com.exportautomation.dto.ClassifyBuyerResponse;
import com.exportautomation.dto.CsvImportResult;
import com.exportautomation.dto.CreateBuyerRequest;
import com.exportautomation.dto.UpdateBuyerRequest;
import com.exportautomation.entity.Buyer;
import com.exportautomation.service.BuyerService;
import com.exportautomation.service.LeadClassificationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/buyers")
@CrossOrigin(origins = "*")
public class BuyerController {

    @Autowired
    private BuyerService buyerService;

    @Autowired
    private LeadClassificationService leadClassificationService;

    @GetMapping
    public ResponseEntity<ApiResponse> getAllBuyers() {
        List<Buyer> buyers = buyerService.getAllBuyers();
        return ResponseEntity.ok(ApiResponse.success("Buyers retrieved successfully", buyers));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse> getBuyerById(@PathVariable Long id) {
        Buyer buyer = buyerService.getBuyerById(id);
        return ResponseEntity.ok(ApiResponse.success("Buyer retrieved successfully", buyer));
    }

    @PostMapping
    public ResponseEntity<ApiResponse> createBuyer(@Valid @RequestBody CreateBuyerRequest request) {
        Buyer created = buyerService.createBuyer(request);
        return new ResponseEntity<>(ApiResponse.success("Buyer created successfully", created), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse> updateBuyer(@PathVariable Long id,
                                                   @Valid @RequestBody UpdateBuyerRequest request) {
        Buyer updated = buyerService.updateBuyer(id, request);
        return ResponseEntity.ok(ApiResponse.success("Buyer updated successfully", updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deleteBuyer(@PathVariable Long id) {
        buyerService.deleteBuyer(id);
        return ResponseEntity.ok(ApiResponse.success("Buyer deleted successfully", null));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse> searchBuyers(@RequestParam String keyword) {
        List<Buyer> buyers = buyerService.searchBuyers(keyword);
        return ResponseEntity.ok(ApiResponse.success("Search results retrieved successfully", buyers));
    }

    @GetMapping("/country/{country}")
    public ResponseEntity<ApiResponse> getBuyersByCountry(@PathVariable String country) {
        List<Buyer> buyers = buyerService.getBuyersByCountry(country);
        return ResponseEntity.ok(ApiResponse.success("Buyers retrieved by country successfully", buyers));
    }

    @GetMapping("/classification/{classification}")
    public ResponseEntity<ApiResponse> getBuyersByClassification(@PathVariable String classification) {
        List<Buyer> buyers = buyerService.getBuyersByClassification(classification);
        return ResponseEntity.ok(ApiResponse.success("Buyers retrieved by classification successfully", buyers));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse> getBuyersByStatus(@PathVariable String status) {
        List<Buyer> buyers = buyerService.getBuyersByStatus(status);
        return ResponseEntity.ok(ApiResponse.success("Buyers retrieved by status successfully", buyers));
    }

    @PostMapping("/import")
    public ResponseEntity<ApiResponse> importCsv(@RequestParam(value = "file", required = false) MultipartFile file) {
        CsvImportResult result = buyerService.importCsv(file);
        return ResponseEntity.ok(ApiResponse.success("CSV import completed", result));
    }

    @GetMapping("/source/{sourcePlatform}")
    public ResponseEntity<ApiResponse> getBuyersBySourcePlatform(@PathVariable String sourcePlatform) {
        List<Buyer> buyers = buyerService.getBuyersBySourcePlatform(sourcePlatform);
        return ResponseEntity.ok(ApiResponse.success("Buyers retrieved by source platform successfully", buyers));
    }

    @GetMapping("/business-type/{businessType}")
    public ResponseEntity<ApiResponse> getBuyersByBusinessType(@PathVariable String businessType) {
        List<Buyer> buyers = buyerService.getBuyersByBusinessType(businessType);
        return ResponseEntity.ok(ApiResponse.success("Buyers retrieved by business type successfully", buyers));
    }

    @GetMapping("/product/{productName}")
    public ResponseEntity<ApiResponse> getBuyersByProductName(@PathVariable String productName) {
        List<Buyer> buyers = buyerService.getBuyersByProductName(productName);
        return ResponseEntity.ok(ApiResponse.success("Buyers retrieved by product name successfully", buyers));
    }

    @PostMapping("/{id}/classify")
    public ResponseEntity<ApiResponse> classifyBuyer(@PathVariable Long id) {
        ClassifyBuyerResponse result = leadClassificationService.classifyBuyer(id);
        return ResponseEntity.ok(ApiResponse.success("Buyer classified successfully", result));
    }

    @PostMapping("/classify")
    public ResponseEntity<ApiResponse> classifyAllUnclassified() {
        BatchClassifyResponse result = leadClassificationService.classifyAllUnclassified();
        return ResponseEntity.ok(ApiResponse.success("Batch classification completed", result));
    }
}
