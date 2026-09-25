const Dashboard = (() => {
  async function render(container) {
    container.innerHTML = `
      <div class="cards-grid" id="statCards">
        <div class="stat-card"><div class="spinner"></div></div>
      </div>
      <div class="dashboard-panels">
        <div class="panel">
          <div class="panel-header">
            <h2>Recent Buyers</h2>
            <a href="#buyers" class="btn btn-secondary btn-sm">View All</a>
          </div>
          <div class="panel-body" id="recentBuyersWrap">
            <div class="state-message"><div class="spinner"></div><p>Loading buyers...</p></div>
          </div>
        </div>
        <div class="panel">
          <div class="panel-header">
            <h2>Recent Campaigns</h2>
            <a href="#campaigns" class="btn btn-secondary btn-sm">View All</a>
          </div>
          <div class="panel-body" id="recentCampaignsWrap">
            <div class="state-message"><div class="spinner"></div><p>Loading campaigns...</p></div>
          </div>
        </div>
      </div>
    `;

    try {
      const [buyers, campaigns] = await Promise.all([
        API.getBuyers(),
        API.getCampaigns(),
      ]);
      renderCards(buyers, campaigns);
      renderRecentBuyers(buyers);
      renderRecentCampaigns(campaigns);
    } catch (err) {
      document.getElementById("statCards").innerHTML = `
        <div class="stat-card state-message error-state">
          <div class="state-icon">&#9888;</div>
          <p>${escapeHtml(err.message)}</p>
        </div>
      `;
      document.getElementById("recentBuyersWrap").innerHTML = `
        <div class="state-message error-state"><p>Failed to load data</p></div>
      `;
      document.getElementById("recentCampaignsWrap").innerHTML = `
        <div class="state-message error-state"><p>Failed to load data</p></div>
      `;
    }
  }

  function renderCards(buyers, campaigns) {
    var totalBuyers = buyers.length;
    var totalCampaigns = campaigns.length;
    var sentCount = buyers.filter(function (b) { return b.emailStatus === "CONTACTED"; }).length;
    var pendingCount = buyers.filter(function (b) { return b.emailStatus === "PENDING"; }).length;
    var hotLeads = buyers.filter(function (b) { return b.classification === "HOT"; }).length;

    document.getElementById("statCards").innerHTML = `
      <div class="stat-card">
        <div class="stat-icon blue">&#9823;</div>
        <div class="stat-text">
          <span class="stat-label">Total Buyers</span>
          <span class="stat-value">${totalBuyers}</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon cyan">&#9993;</div>
        <div class="stat-text">
          <span class="stat-label">Campaigns</span>
          <span class="stat-value">${totalCampaigns}</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green">&#10003;</div>
        <div class="stat-text">
          <span class="stat-label">Emails Sent</span>
          <span class="stat-value">${sentCount}</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber">&#9200;</div>
        <div class="stat-text">
          <span class="stat-label">Pending</span>
          <span class="stat-value">${pendingCount}</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon red">&#9733;</div>
        <div class="stat-text">
          <span class="stat-label">Hot Leads</span>
          <span class="stat-value">${hotLeads}</span>
        </div>
      </div>
    `;
  }

  function renderRecentBuyers(buyers) {
    var wrap = document.getElementById("recentBuyersWrap");

    if (buyers.length === 0) {
      wrap.innerHTML = `
        <div class="state-message">
          <div class="state-icon">&#128203;</div>
          <p>No buyers yet. Import buyers from the Buyers page.</p>
        </div>
      `;
      return;
    }

    var recent = buyers.slice(-5).reverse();

    wrap.innerHTML = `
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Buyer</th>
              <th>Company</th>
              <th>Country</th>
              <th>Classification</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            ${recent.map(function (b) {
              return '<tr>'
                + '<td>' + escapeHtml(b.buyerName) + '</td>'
                + '<td>' + escapeHtml(b.companyName) + '</td>'
                + '<td>' + escapeHtml(b.country || "-") + '</td>'
                + '<td>' + badge(b.classification) + '</td>'
                + '<td>' + badge(b.emailStatus) + '</td>'
                + '</tr>';
            }).join("")}
          </tbody>
        </table>
      </div>
    `;
  }

  function renderRecentCampaigns(campaigns) {
    var wrap = document.getElementById("recentCampaignsWrap");

    if (campaigns.length === 0) {
      wrap.innerHTML = `
        <div class="state-message">
          <div class="state-icon">&#9993;</div>
          <p>No campaigns yet. Create your first campaign.</p>
        </div>
      `;
      return;
    }

    var recent = campaigns.slice(-5).reverse();

    wrap.innerHTML = `
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Campaign</th>
              <th>Subject</th>
              <th>Target</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            ${recent.map(function (c) {
              return '<tr>'
                + '<td><a href="#campaigns" class="campaign-link">' + escapeHtml(c.campaignName) + '</a></td>'
                + '<td>' + escapeHtml(c.emailSubject || "-") + '</td>'
                + '<td>' + badge(c.targetAudience) + '</td>'
                + '<td>' + formatDate(c.createdAt) + '</td>'
                + '</tr>';
            }).join("")}
          </tbody>
        </table>
      </div>
    `;
  }

  return { render };
})();
