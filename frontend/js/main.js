/* Sign-in flow and start-up. */
(function () {
  const { $, cfg, state: S } = CRAS;
  const form = $("#login-form"), emailEl = $("#login-email"), passEl = $("#login-password");
  const errEl = $("#login-error"), btn = $("#login-btn");

  $("#demo-hint").textContent = cfg.DEMO_AUTOFILL
    ? "Demo buttons fill in the e-mail and password."
    : "Demo buttons fill in the e-mail; passwords are in application.properties.";

  $("#demo-box").addEventListener("click", (e) => {
    const chip = e.target.closest("[data-demo]");
    if (!chip) return;
    const acc = (cfg.DEMO || {})[chip.dataset.demo];
    if (!acc) return;
    emailEl.value = acc.email;
    passEl.value = cfg.DEMO_AUTOFILL ? acc.password : "";
    errEl.textContent = "";
    (cfg.DEMO_AUTOFILL ? btn : passEl).focus();
  });

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    errEl.textContent = "";
    const email = emailEl.value.trim();
    const password = passEl.value;
    if (!email || !password) { errEl.textContent = "Enter your e-mail and password."; return; }
    btn.disabled = true;
    const label = btn.textContent;
    btn.innerHTML = '<span class="spin"></span>Signing in';
    try {
      const res = await CRAS.api("/api/auth/login", { method: "POST", body: { email, password }, auth: false });
      S.token = res.token;
      S.user = { email: res.email, fullName: res.fullName, role: res.role };
      sessionStorage.setItem("cras.token", res.token);
      await CRAS.enter();
    } catch (err) {
      errEl.textContent = err.message;      // e.g. "Invalid email or password (2 attempts left)" or "Cannot reach the CRAS server…"
      passEl.select();
    } finally {
      btn.disabled = false;
      btn.textContent = label;
    }
  });

  $("#logout-btn").addEventListener("click", () => CRAS.logout(""));
  window.addEventListener("hashchange", () => { if (S.token && (location.hash || "").slice(1) !== S.current) CRAS.go(location.hash.slice(1)); });

  // Restore a session if a valid token is still stored for this tab.
  (async () => {
    const t = sessionStorage.getItem("cras.token");
    if (!t) return;
    S.token = t;
    try {
      const me = await CRAS.api("/api/auth/me");
      S.user = { email: me.email, fullName: me.fullName, role: me.role };
      await CRAS.enter();
    } catch (err) {
      S.token = null;
      sessionStorage.removeItem("cras.token");
      if (err.network) errEl.textContent = err.message;
    }
  })();
})();
