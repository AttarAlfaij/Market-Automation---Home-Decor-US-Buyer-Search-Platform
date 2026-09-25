
const Buyers = (() => {
  const PAGE_SIZE = 15;
  let allBuyers = [];
  let filtered = [];
  let page = 1;
  let activeTab = "list";

  async function render(container) {
    container.innerHTML = `
      <div class="tab-bar">
        <button class="tab active" data-tab="list">All Buyers</button>
        <button class="tab" data-tab="search">Buyer Search</button>
      </div>
      <div id="buyersTabContent"></div>
    `;

    container.querySelectorAll(".tab").forEach((tab) => {
      tab.addEventListener("click", () => {
        container.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
        tab.classList.add("active");
        activeTab = tab.dataset.tab;
        renderTabContent();
      });
    });

    renderTabContent();
  }

  function renderTabContent() {
    const wrap = document.getElementById("buyersTabContent");
    wrap.innerHTML = "";

    if (activeTab === "search") {
      BuyerSearch.render(wrap);
    } else {
      renderAllBuyers(wrap);
    }
  }

  async function renderAllBuyers(container) {
    container.innerHTML = `
      <div class="panel">
        <div class="panel-header">
          <h2>All Buyers</h2>
          <div class="search-box">
            <input type="text" id="buyerSearch" placeholder="Search name, company, email, country..." />
          </div>
        </div>
        <div id="buyerTableWrap">
          <div class="state-message"><div class="spinner"></div><p>Loading buyers...</p></div>
        </div>
      </div>
    `;

    document.getElementById("buyerSearch").addEventListener("input", onSearch);

    try {
      allBuyers = await API.getBuyers();
      filtered = allBuyers;
      page = 1;
      renderTable();
    } catch (err) {
      document.getElementById("buyerTableWrap").innerHTML = `
        <div class="state-message error-state">
          <div class="state-icon">&#9888;</div>
          <p>${escapeHtml(err.message)}</p>
        </div>
      `;
    }
  }

  function onSearch(e) {
    const q = e.target.value.toLowerCase().trim();
    if (!q) {
      filtered = allBuyers;
    } else {
      filtered = allBuyers.filter((b) =>
        [b.buyerName, b.companyName, b.email, b.country, b.sourcePlatform, b.productName, b.classification]
          .filter(Boolean)
          .some((v) => v.toLowerCase().includes(q))
      );
    }
    page = 1;
    renderTable();
  }

  function renderTable() {
    const wrap = document.getElementById("buyerTableWrap");

    if (filtered.length === 0) {
      wrap.innerHTML = `
        <div class="state-message">
          <div class="state-icon">&#128269;</div>
          <p>No buyers found</p>
        </div>
      `;
      return;
    }

    const totalPages = Math.ceil(filtered.length / PAGE_SIZE);
    const start = (page - 1) * PAGE_SIZE;
    const slice = filtered.slice(start, start + PAGE_SIZE);

    wrap.innerHTML = `
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Buyer</th>
              <th>Company</th>
              <th>Email</th>
              <th>Country</th>
              <th>Source</th>
              <th>Product</th>
              <th>Classification</th>
              <th>Status</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            ${slice.map((b) => `
              <tr>
                <td>${escapeHtml(b.buyerName)}</td>
                <td>${escapeHtml(b.companyName)}</td>
                <td>${escapeHtml(b.email)}</td>
                <td>${escapeHtml(b.country || "-")}</td>
                <td>${escapeHtml(b.sourcePlatform || "-")}</td>
                <td>${escapeHtml(b.productName || "-")}</td>
                <td>${badge(b.classification)}</td>
                <td>${badge(b.emailStatus)}</td>
                <td>${formatDate(b.createdAt)}</td>
              </tr>
            `).join("")}
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <span>Showing ${start + 1}\u2013${Math.min(start + PAGE_SIZE, filtered.length)} of ${filtered.length}</span>
        <div class="pagination-btns">
          <button id="prevPage" ${page <= 1 ? "disabled" : ""}>&laquo; Prev</button>
          <button id="nextPage" ${page >= totalPages ? "disabled" : ""}>Next &raquo;</button>
        </div>
      </div>
    `;

    document.getElementById("prevPage").addEventListener("click", () => {
      if (page > 1) { page--; renderTable(); }
    });

    document.getElementById("nextPage").addEventListener("click", () => {
      if (page < totalPages) { page++; renderTable(); }
    });
  }

  return { render };
})();
