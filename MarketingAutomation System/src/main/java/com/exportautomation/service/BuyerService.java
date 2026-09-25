package com.exportautomation.service;

import com.exportautomation.dto.CsvImportResult;
import com.exportautomation.dto.CreateBuyerRequest;
import com.exportautomation.dto.UpdateBuyerRequest;
import com.exportautomation.entity.Buyer;
import com.exportautomation.exception.DuplicateResourceException;
import com.exportautomation.exception.ResourceNotFoundException;
import com.exportautomation.repository.BuyerRepository;
import com.exportautomation.repository.CampaignRecipientExclusionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class BuyerService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    @Autowired
    private BuyerRepository buyerRepository;

    @Autowired
    private CampaignRecipientExclusionRepository exclusionRepository;

    public List<Buyer> getAllBuyers() {
        return buyerRepository.findAll();
    }

    public Buyer getBuyerById(Long id) {
        return buyerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Buyer not found with id: " + id));
    }

    public Buyer createBuyer(CreateBuyerRequest request) {
        String normalizedEmail = normalizeEmail(request.getEmail());
        validateEmailFormat(normalizedEmail);
        validateRequiredFields(request.getBuyerName(), request.getCompanyName(),
                normalizedEmail, request.getCountry(), request.getProductName());

        if (buyerRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new DuplicateResourceException("A buyer with this email already exists: " + normalizedEmail);
        }

        Buyer buyer = mapCreateRequestToEntity(request);
        buyer.setEmail(normalizedEmail);
        return buyerRepository.save(buyer);
    }

    public Buyer updateBuyer(Long id, UpdateBuyerRequest request) {
        Buyer existing = buyerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Buyer not found with id: " + id));

        String normalizedEmail = normalizeEmail(request.getEmail());
        validateEmailFormat(normalizedEmail);
        validateRequiredFields(request.getBuyerName(), request.getCompanyName(),
                normalizedEmail, request.getCountry(), request.getProductName());

        if (buyerRepository.existsByEmailIgnoreCaseAndIdNot(normalizedEmail, id)) {
            throw new DuplicateResourceException("Another buyer already uses this email: " + normalizedEmail);
        }

        existing.setBuyerName(request.getBuyerName().trim());
        existing.setCompanyName(request.getCompanyName().trim());
        existing.setEmail(normalizedEmail);
        existing.setWebsite(request.getWebsite() != null ? request.getWebsite().trim() : null);
        existing.setCountry(request.getCountry().trim());
        existing.setSourcePlatform(request.getSourcePlatform() != null ? request.getSourcePlatform().trim() : null);
        existing.setBusinessType(request.getBusinessType() != null ? request.getBusinessType().trim() : null);
        existing.setSourceUrl(request.getSourceUrl() != null ? request.getSourceUrl().trim() : null);
        existing.setProductName(request.getProductName().trim());
        existing.setClassification(request.getClassification() != null ? request.getClassification().trim() : null);
        existing.setEmailStatus(request.getEmailStatus() != null ? request.getEmailStatus().trim() : null);

        return buyerRepository.save(existing);
    }

    public void deleteBuyer(Long id) {
        Buyer existing = buyerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Buyer not found with id: " + id));
        exclusionRepository.deleteByBuyerId(id);
        buyerRepository.delete(existing);
    }

    public List<Buyer> searchBuyers(String keyword) {
        return buyerRepository.searchByKeyword(keyword);
    }

    public List<Buyer> getBuyersByCountry(String country) {
        return buyerRepository.findByCountryIgnoreCase(country);
    }

    public List<Buyer> getBuyersByClassification(String classification) {
        return buyerRepository.findByClassificationIgnoreCase(classification);
    }

    public List<Buyer> getBuyersByStatus(String status) {
        return buyerRepository.findByEmailStatusIgnoreCase(status);
    }

    public List<Buyer> getBuyersBySourcePlatform(String sourcePlatform) {
        return buyerRepository.findBySourcePlatformIgnoreCase(sourcePlatform);
    }

    public List<Buyer> getBuyersByBusinessType(String businessType) {
        return buyerRepository.findByBusinessTypeIgnoreCase(businessType);
    }

    public List<Buyer> getBuyersByProductName(String productName) {
        return buyerRepository.findByProductNameIgnoreCase(productName);
    }

    public CsvImportResult importCsv(MultipartFile file) {
        CsvImportResult result = new CsvImportResult();

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file provided or file is empty");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".csv") && !filename.endsWith(".CSV"))) {
            throw new IllegalArgumentException("File must be a CSV file");
        }

        Set<String> importedEmailsInBatch = new HashSet<>();
        List<String[]> rows = parseCsv(file);
        result.setTotalRecords(rows.size());

        for (int i = 0; i < rows.size(); i++) {
            int rowNum = i + 2;
            String[] row = rows.get(i);

            if (row.length < 9) {
                result.setInvalid(result.getInvalid() + 1);
                result.addRejectedRow(rowNum, row.length > 2 ? row[2] : "N/A",
                        "Missing required columns (expected 9, got " + row.length + ")");
                continue;
            }

            String buyerName = safeTrim(row[0]);
            String companyName = safeTrim(row[1]);
            String email = normalizeEmail(row[2]);
            String website = safeTrim(row[3]);
            String country = safeTrim(row[4]);
            String sourcePlatform = safeTrim(row[5]);
            String sourceUrl = safeTrim(row[6]);
            String businessType = safeTrim(row[7]);
            String productName = safeTrim(row[8]);

            if (buyerName.isEmpty() || companyName.isEmpty() || email.isEmpty()
                    || country.isEmpty() || productName.isEmpty()) {
                result.setInvalid(result.getInvalid() + 1);
                result.addRejectedRow(rowNum, email.isEmpty() ? "N/A" : email,
                        "Missing required fields (buyerName, companyName, email, country, productName are required)");
                continue;
            }

            if (!EMAIL_PATTERN.matcher(email).matches()) {
                result.setInvalid(result.getInvalid() + 1);
                result.addRejectedRow(rowNum, email, "Invalid email format");
                continue;
            }

            if (buyerRepository.existsByEmailIgnoreCase(email)) {
                result.setDuplicates(result.getDuplicates() + 1);
                result.addRejectedRow(rowNum, email, "Duplicate email already exists in database");
                continue;
            }

            if (importedEmailsInBatch.contains(email)) {
                result.setDuplicates(result.getDuplicates() + 1);
                result.addRejectedRow(rowNum, email, "Duplicate email found within CSV file");
                continue;
            }

            Buyer buyer = new Buyer();
            buyer.setBuyerName(buyerName);
            buyer.setCompanyName(companyName);
            buyer.setEmail(email);
            buyer.setWebsite(website.isEmpty() ? null : website);
            buyer.setCountry(country);
            buyer.setSourcePlatform(sourcePlatform.isEmpty() ? null : sourcePlatform);
            buyer.setSourceUrl(sourceUrl.isEmpty() ? null : sourceUrl);
            buyer.setBusinessType(businessType.isEmpty() ? null : businessType);
            buyer.setProductName(productName);

            buyerRepository.save(buyer);
            importedEmailsInBatch.add(email);
            result.setImported(result.getImported() + 1);
        }

        return result;
    }

    private List<String[]> parseCsv(MultipartFile file) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.trim().isEmpty()) {
                throw new IllegalArgumentException("CSV file is empty or has no header row");
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                rows.add(parseCsvLine(line));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read CSV file: " + e.getMessage());
        }
        return rows;
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    private String safeTrim(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private void validateEmailFormat(String email) {
        if (email == null || email.isEmpty()) {
            throw new IllegalArgumentException("Email must not be null or empty");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Invalid email format: " + email);
        }
    }

    private void validateRequiredFields(String buyerName, String companyName, String email,
                                         String country, String productName) {
        if (buyerName == null || buyerName.trim().isEmpty()) {
            throw new IllegalArgumentException("Buyer name is required");
        }
        if (companyName == null || companyName.trim().isEmpty()) {
            throw new IllegalArgumentException("Company name is required");
        }
        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (country == null || country.trim().isEmpty()) {
            throw new IllegalArgumentException("Country is required");
        }
        if (productName == null || productName.trim().isEmpty()) {
            throw new IllegalArgumentException("Product name is required");
        }
    }

    private Buyer mapCreateRequestToEntity(CreateBuyerRequest request) {
        Buyer buyer = new Buyer();
        buyer.setBuyerName(request.getBuyerName().trim());
        buyer.setCompanyName(request.getCompanyName().trim());
        buyer.setWebsite(request.getWebsite() != null ? request.getWebsite().trim() : null);
        buyer.setCountry(request.getCountry().trim());
        buyer.setSourcePlatform(request.getSourcePlatform() != null ? request.getSourcePlatform().trim() : null);
        buyer.setBusinessType(request.getBusinessType() != null ? request.getBusinessType().trim() : null);
        buyer.setSourceUrl(request.getSourceUrl() != null ? request.getSourceUrl().trim() : null);
        buyer.setProductName(request.getProductName().trim());
        buyer.setClassification(request.getClassification() != null ? request.getClassification().trim() : null);
        buyer.setEmailStatus(request.getEmailStatus() != null ? request.getEmailStatus().trim() : null);
        return buyer;
    }
}
