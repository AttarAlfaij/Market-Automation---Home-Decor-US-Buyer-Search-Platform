package com.exportautomation.dto;

import java.util.ArrayList;
import java.util.List;

public class CsvImportResult {

    private int totalRecords;
    private int imported;
    private int duplicates;
    private int invalid;
    private List<RejectedRow> rejectedRows;

    public CsvImportResult() {
        this.rejectedRows = new ArrayList<>();
    }

    public int getTotalRecords() { return totalRecords; }
    public void setTotalRecords(int totalRecords) { this.totalRecords = totalRecords; }

    public int getImported() { return imported; }
    public void setImported(int imported) { this.imported = imported; }

    public int getDuplicates() { return duplicates; }
    public void setDuplicates(int duplicates) { this.duplicates = duplicates; }

    public int getInvalid() { return invalid; }
    public void setInvalid(int invalid) { this.invalid = invalid; }

    public List<RejectedRow> getRejectedRows() { return rejectedRows; }
    public void setRejectedRows(List<RejectedRow> rejectedRows) { this.rejectedRows = rejectedRows; }

    public void addRejectedRow(int row, String email, String reason) {
        this.rejectedRows.add(new RejectedRow(row, email, reason));
    }

    public static class RejectedRow {
        private int row;
        private String email;
        private String reason;

        public RejectedRow(int row, String email, String reason) {
            this.row = row;
            this.email = email;
            this.reason = reason;
        }

        public int getRow() { return row; }
        public void setRow(int row) { this.row = row; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
