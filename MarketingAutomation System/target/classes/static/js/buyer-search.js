
const BuyerSearch = (() => {
  let searchResults = [];
  let searchMeta = null;
  let lastProductName = "";

  const COUNTRIES = [
    { value: "USA", label: "USA" },
    { value: "Canada", label: "Canada" }
];

const CANADA_PROVINCES = [
    { value: "", label: "All Canada (nationwide)" },
    { value: "Alberta", label: "Alberta" },
    { value: "British Columbia", label: "British Columbia" },
    { value: "Manitoba", label: "Manitoba" },
    { value: "New Brunswick", label: "New Brunswick" },
    { value: "Newfoundland and Labrador", label: "Newfoundland and Labrador" },
    { value: "Northwest Territories", label: "Northwest Territories" },
    { value: "Nova Scotia", label: "Nova Scotia" },
    { value: "Nunavut", label: "Nunavut" },
    { value: "Ontario", label: "Ontario" },
    { value: "Prince Edward Island", label: "Prince Edward Island" },
    { value: "Quebec", label: "Quebec" },
    { value: "Saskatchewan", label: "Saskatchewan" },
    { value: "Yukon", label: "Yukon" }
];

function stateOptionsHtml(country) {
  const options = country === "Canada" ? CANADA_PROVINCES : US_STATES;
  return options.map(s => `<option value="${s.value}">${s.label}</option>`).join("");
}

const US_STATES = [
    { value: "", label: "All USA (nationwide)" },
    { value: "Alabama", label: "Alabama" },
    { value: "Alaska", label: "Alaska" },
    { value: "Arizona", label: "Arizona" },
    { value: "Arkansas", label: "Arkansas" },
    { value: "California", label: "California" },
    { value: "Colorado", label: "Colorado" },
    { value: "Connecticut", label: "Connecticut" },
    { value: "Delaware", label: "Delaware" },
    { value: "Florida", label: "Florida" },
    { value: "Georgia", label: "Georgia" },
    { value: "Hawaii", label: "Hawaii" },
    { value: "Idaho", label: "Idaho" },
    { value: "Illinois", label: "Illinois" },
    { value: "Indiana", label: "Indiana" },
    { value: "Iowa", label: "Iowa" },
    { value: "Kansas", label: "Kansas" },
    { value: "Kentucky", label: "Kentucky" },
    { value: "Louisiana", label: "Louisiana" },
    { value: "Maine", label: "Maine" },
    { value: "Maryland", label: "Maryland" },
    { value: "Massachusetts", label: "Massachusetts" },
    { value: "Michigan", label: "Michigan" },
    { value: "Minnesota", label: "Minnesota" },
    { value: "Mississippi", label: "Mississippi" },
    { value: "Missouri", label: "Missouri" },
    { value: "Montana", label: "Montana" },
    { value: "Nebraska", label: "Nebraska" },
    { value: "Nevada", label: "Nevada" },
    { value: "New Hampshire", label: "New Hampshire" },
    { value: "New Jersey", label: "New Jersey" },
    { value: "New Mexico", label: "New Mexico" },
    { value: "New York", label: "New York" },
    { value: "North Carolina", label: "North Carolina" },
    { value: "North Dakota", label: "North Dakota" },
    { value: "Ohio", label: "Ohio" },
    { value: "Oklahoma", label: "Oklahoma" },
    { value: "Oregon", label: "Oregon" },
    { value: "Pennsylvania", label: "Pennsylvania" },
    { value: "Rhode Island", label: "Rhode Island" },
    { value: "South Carolina", label: "South Carolina" },
    { value: "South Dakota", label: "South Dakota" },
    { value: "Tennessee", label: "Tennessee" },
    { value: "Texas", label: "Texas" },
    { value: "Utah", label: "Utah" },
    { value: "Vermont", label: "Vermont" },
    { value: "Virginia", label: "Virginia" },
    { value: "Washington", label: "Washington" },
    { value: "West Virginia", label: "West Virginia" },
    { value: "Wisconsin", label: "Wisconsin" },
    { value: "Wyoming", label: "Wyoming" }
  ];

  function render(container) {
    container.innerHTML = `
      <div class="panel" style="margin-bottom:20px;">
        <div class="panel-header">
          <h2>Search Buyers</h2>
        </div>
        <div class="panel-body">
          <div class="form-row">
            <div class="form-group">
              <label>Product Name <span class="required">*</span></label>
              <input type="text" id="searchProductName" placeholder="e.g. Singing Bowls" />
            </div>
            <div class="form-group">
              <label>Result Limit</label>
              <input type="number" id="searchResultLimit" min="1" max="50" value="10" placeholder="10" />
            </div>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>Country</label>
              <select id="searchCountry">
                ${COUNTRIES.map(c => `<option value="${c.value}" ${c.value === "USA" ? "selected" : ""}>${c.label}</option>`).join("")}
              </select>
            </div>
            <div class="form-group">
              <label>State / Province</label>
              <select id="searchState">
                ${stateOptionsHtml("USA")}
              </select>
            </div>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>City</label>
              <input type="text" id="searchCity" placeholder="e.g. Los Angeles, Houston" />
            </div>
          </div>
          <div class="form-actions">
            <button class="btn btn-primary" id="searchUsaBtn">Search Buyers</button>
          </div>
        </div>
      </div>

      <div class="panel" id="searchResultsPanel" style="display:none;">
        <div class="panel-header">
          <h2 id="searchResultsTitle">Search Results</h2>
          <button class="btn btn-primary btn-sm" id="downloadSearchCsvBtn" disabled>No emails available</button>
        </div>
        <div id="searchResultsWrap">
          <div class="state-message">
            <div class="state-icon">&#128269;</div>
            <p>Enter a product name and click Search</p>
          </div>
        </div>
      </div>

      <div class="panel" id="emailDiscoveryPanel" style="display:none;">
        <div class="panel-header">
          <h2>Email Discovery</h2>
        </div>
        <div class="panel-body">
          <div id="emailDiscoveryWrap">
            <p style="color: var(--color-text-muted); margin-bottom: 16px;">
              Visit buyer websites to discover publicly available email addresses.
            </p>
            <button class="btn btn-primary" id="discoverEmailsBtn">Discover Emails</button>
            <div id="emailDiscoveryStatus" style="margin-top: 16px;"></div>
          </div>
        </div>
      </div>
    `;

    document.getElementById("searchUsaBtn").addEventListener("click", onSearch);
    document.getElementById("searchCountry").addEventListener("change", (e) => {
      document.getElementById("searchState").innerHTML = stateOptionsHtml(e.target.value);
      document.getElementById("searchCity").value = "";
    });
    document.getElementById("downloadSearchCsvBtn").addEventListener("click", onDownloadSearchCsv);
    document.getElementById("discoverEmailsBtn").addEventListener("click", onDiscoverEmails);
  }

  async function onSearch() {
    const productName = document.getElementById("searchProductName").value.trim();
    const resultLimit = parseInt(document.getElementById("searchResultLimit").value) || 10;
    const state = document.getElementById("searchState").value.trim();
    const city = document.getElementById("searchCity").value.trim();
    const country = document.getElementById("searchCountry").value.trim();

    if (!productName) {
      showToast("Please enter a product name", "error");
      return;
    }

    lastProductName = productName;
    const btn = document.getElementById("searchUsaBtn");
    btn.disabled = true;
    btn.textContent = "Searching...";

    const resultsPanel = document.getElementById("searchResultsPanel");
    const resultsWrap = document.getElementById("searchResultsWrap");
    resultsPanel.style.display = "block";

    let searchDesc = `"${escapeHtml(productName)}"`;
    if (city) searchDesc += ` in ${escapeHtml(city)}`;
    if (state) searchDesc += `, ${escapeHtml(state)}`;
    searchDesc += ` (${escapeHtml(country)})`;

    resultsWrap.innerHTML = `
      <div class="state-message">
        <div class="spinner"></div>
        <p>Searching for ${escapeHtml(country)} buyers of ${searchDesc}...</p>
      </div>
    `;

    document.getElementById("emailDiscoveryPanel").style.display = "none";

    try {
      const data = await API.searchBuyersUSA(productName, resultLimit, state || null, city || null, country || "USA");
      const resultData = data.data;
      searchResults = resultData.results || [];
      searchMeta = {
        totalFound: resultData.totalFound || 0,
        totalSaved: resultData.totalSaved || 0
      };

      document.getElementById("searchResultsTitle").textContent =
        `Search Results \u2014 Found ${searchMeta.totalFound}, Saved ${searchMeta.totalSaved} new buyers`;

      renderSearchResults();
      updateDownloadCsvButton();

      if (searchMeta.totalSaved > 0) {
        document.getElementById("emailDiscoveryPanel").style.display = "block";
        document.getElementById("emailDiscoveryStatus").innerHTML = "";
        document.getElementById("discoverEmailsBtn").disabled = false;
        document.getElementById("discoverEmailsBtn").textContent = "Discover Emails";
      }
    } catch (err) {
      resultsWrap.innerHTML = `
        <div class="state-message error-state">
          <div class="state-icon">&#9888;</div>
          <p>${escapeHtml(err.message)}</p>
        </div>
      `;
    } finally {
      btn.disabled = false;
      btn.textContent = "Search Buyers";
    }
  }

  async function onDiscoverEmails() {
    if (!lastProductName) {
      showToast("Please search for a product first", "error");
      return;
    }

    const btn = document.getElementById("discoverEmailsBtn");
    const statusDiv = document.getElementById("emailDiscoveryStatus");
    btn.disabled = true;
    btn.textContent = "Discovering...";

    statusDiv.innerHTML = `
      <div class="state-message">
        <div class="spinner"></div>
        <p>Fetching buyer websites and scanning for emails...</p>
      </div>
    `;

    try {
      const buyers = await API.getBuyersByProduct(lastProductName);
      const buyerIds = (buyers || []).map(b => b.id);

      if (buyerIds.length === 0) {
        statusDiv.innerHTML = `
          <div class="state-message">
            <p>No saved buyers found for "${escapeHtml(lastProductName)}". Search first to save buyers.</p>
          </div>
        `;
        btn.disabled = false;
        btn.textContent = "Discover Emails";
        return;
      }

      const data = await API.discoverEmails(buyerIds);
      const resultData = data.data;
      const emails = resultData.emails || [];
      const totalProcessed = resultData.totalBuyersProcessed || 0;
      const totalFound = resultData.totalEmailsFound || 0;

      if (emails.length === 0) {
        statusDiv.innerHTML = `
          <div class="state-message">
            <div class="state-icon">&#128231;</div>
            <p>No emails found after scanning ${totalProcessed} buyer website(s).</p>
          </div>
        `;
        btn.disabled = false;
        btn.textContent = "Discover Emails";
        return;
      }

      let emailListHtml = emails.map(e =>
        `<tr><td style="padding:6px 12px;border-bottom:1px solid var(--color-border);font-size:0.85rem;">${escapeHtml(e)}</td></tr>`
      ).join("");

      statusDiv.innerHTML = `
        <div style="margin-bottom:12px;">
          <span style="font-weight:600;color:var(--color-success);font-size:0.9rem;">
            &#10003; Found ${totalFound} unique email(s) from ${totalProcessed} buyer(s)
          </span>
        </div>
        <div class="table-wrap" style="max-height:300px;overflow-y:auto;margin-bottom:16px;">
          <table>
            <thead><tr><th>Email</th></tr></thead>
            <tbody>${emailListHtml}</tbody>
          </table>
        </div>
        <div style="display:flex;gap:12px;">
          <button class="btn btn-primary" id="downloadCsvBtn">Download CSV</button>
          <button class="btn btn-secondary" id="copyEmailsBtn">Copy All Emails</button>
        </div>
      `;

      document.getElementById("downloadCsvBtn").addEventListener("click", () => downloadCsv(emails));
      document.getElementById("copyEmailsBtn").addEventListener("click", () => {
        navigator.clipboard.writeText(emails.join("\n")).then(() => {
          showToast("Emails copied to clipboard", "success");
        });
      });

    } catch (err) {
      statusDiv.innerHTML = `
        <div class="state-message error-state">
          <div class="state-icon">&#9888;</div>
          <p>${escapeHtml(err.message)}</p>
        </div>
      `;
    } finally {
      btn.disabled = false;
      btn.textContent = "Discover Emails";
    }
  }

  function downloadCsv(emails) {
    let csv = "email\n";
    emails.forEach(e => { csv += e + "\n"; });

    const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "discovered-emails.csv";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    showToast("CSV downloaded successfully", "success");
  }

  function updateDownloadCsvButton() {
    const btn = document.getElementById("downloadSearchCsvBtn");
    const emails = getUniqueEmails();
    if (emails.length > 0) {
      btn.disabled = false;
      btn.textContent = `Download CSV (${emails.length})`;
    } else {
      btn.disabled = true;
      btn.textContent = "No emails available";
    }
  }

  function getUniqueEmails() {
    const seen = new Set();
    const unique = [];
    for (const r of searchResults) {
      if (r.email && !seen.has(r.email.toLowerCase())) {
        seen.add(r.email.toLowerCase());
        unique.push(r.email);
      }
    }
    return unique;
  }

  function onDownloadSearchCsv() {
    const emails = getUniqueEmails();
    if (emails.length === 0) return;

    let csv = "email\n";
    emails.forEach(e => { csv += e + "\n"; });

    const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "discovered-emails.csv";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    showToast("CSV downloaded successfully", "success");
  }

  function sourceLabelFromUrl(url) {
    try {
      const h = new URL(url || "").hostname.replace(/^www\./, "").toLowerCase();
      if (/linkedin\.com$/.test(h)) return "LinkedIn";
      if (/facebook\.com$/.test(h)) return "Facebook";
    } catch (_) {}
    return "Google Search";
  }

  function renderSearchResults() {
    const wrap = document.getElementById("searchResultsWrap");

    if (searchResults.length === 0) {
      wrap.innerHTML = `
        <div class="state-message">
          <div class="state-icon">&#128269;</div>
          <p>No matching buyers found</p>
        </div>
      `;
      return;
    }

    wrap.innerHTML = `
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Buyer Name</th>
              <th>Company</th>
              <th>Location</th>
              <th>City</th>
              <th>State</th>
              <th>Email</th>
              <th>Business Type</th>
              <th>Source</th>
              <th>Relevant URL</th>
            </tr>
          </thead>
          <tbody>
            ${searchResults.map((r) => `
              <tr>
                <td>${escapeHtml(r.buyerName && r.buyerName !== r.companyName ? r.buyerName : "-")}</td>
                <td>${escapeHtml(r.companyName)}</td>
                <td>${escapeHtml(r.location || "-")}</td>
                <td>${escapeHtml(r.city || "-")}</td>
                <td>${escapeHtml(r.state || "-")}</td>
                <td>${r.email ? escapeHtml(r.email) : '<span style="color:var(--color-text-muted);">Not found</span>'}</td>
                <td>${escapeHtml(r.businessType || "-")}</td>
                <td>${escapeHtml(sourceLabelFromUrl(r.sourceUrl))}</td>
                <td><a href="${escapeHtml(r.sourceUrl || r.website || "#")}" target="_blank" rel="noopener noreferrer" class="campaign-link">${escapeHtml(truncate(r.sourceUrl || r.website || "-", 40))}</a></td>
              </tr>
            `).join("")}
          </tbody>
        </table>
      </div>
    `;
  }

  function truncate(str, max) {
    if (!str) return "";
    return str.length > max ? str.substring(0, max) + "..." : str;
  }

  return { render };
})();
