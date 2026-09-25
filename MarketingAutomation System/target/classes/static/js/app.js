const App = (() => {
  const routes = {
    dashboard: { title: "Dashboard", icon: "&#9632;", render: Dashboard.render },
    buyers:    { title: "Buyers",     icon: "&#9823;", render: Buyers.render },
    campaigns: { title: "Campaigns",  icon: "&#9993;", render: Campaigns.render },
    tracking:  { title: "Email Tracking", icon: "&#9201;", render: Tracking.render },
  };

  let currentPage = "dashboard";

  function init() {
    setupSidebar();
    setupMobileMenu();
    navigateTo(getHash() || "dashboard");
    startStatusPolling();
  }

  function getHash() {
    return location.hash.replace("#", "") || null;
  }

  function setupSidebar() {
    document.querySelectorAll(".sidebar-nav a").forEach((a) => {
      a.addEventListener("click", (e) => {
        e.preventDefault();
        const page = a.dataset.page;
        navigateTo(page);
        closeMobileMenu();
      });
    });

    window.addEventListener("hashchange", () => {
      const page = getHash() || "dashboard";
      if (page !== currentPage) navigateTo(page);
    });
  }

  function setupMobileMenu() {
    const toggle = document.getElementById("menuToggle");
    const sidebar = document.getElementById("sidebar");
    const overlay = document.getElementById("sidebarOverlay");

    if (toggle) toggle.addEventListener("click", openMobileMenu);
    if (overlay) overlay.addEventListener("click", closeMobileMenu);
  }

  function openMobileMenu() {
    document.getElementById("sidebar").classList.add("open");
    document.getElementById("sidebarOverlay").classList.add("visible");
  }

  function closeMobileMenu() {
    document.getElementById("sidebar").classList.remove("open");
    document.getElementById("sidebarOverlay").classList.remove("visible");
  }

  function navigateTo(page) {
    if (!routes[page]) page = "dashboard";
    currentPage = page;
    location.hash = page;

    document.querySelectorAll(".sidebar-nav a").forEach((a) => {
      a.classList.toggle("active", a.dataset.page === page);
    });

    document.getElementById("pageTitle").textContent = routes[page].title;
    document.getElementById("pageIcon").innerHTML = routes[page].icon;

    const container = document.getElementById("pageContent");
    container.innerHTML = "";
    routes[page].render(container);
  }

  async function startStatusPolling() {
    const dot = document.getElementById("statusDot");
    const label = document.getElementById("statusLabel");

    async function check() {
      const ok = await API.checkHealth();
      dot.classList.toggle("connected", ok);
      label.textContent = ok ? "API Online" : "API Offline";
    }

    check();
    setInterval(check, 15000);
  }

  return { init };
})();
