/* CRAS frontend configuration */
window.CRAS_CONFIG = {
  API_URL: "http://localhost:8080",
  AI_URL: "http://localhost:8001",

  /*
   * DEMO_AUTOFILL = true  -> the "Demo accounts" buttons also fill in the password.
   * DEMO_AUTOFILL = false -> they only fill the e-mail; type the password yourself.
   * Keep these passwords identical to cras.seed.* in backend/src/main/resources/application.properties.
   * Set DEMO_AUTOFILL to false (or delete the DEMO block) for anything that is not a local demo.
   */
  DEMO_AUTOFILL: true,
  DEMO: {
    admin:   { email: "admin@cras.local",   password: "CRAS_Admin#82Xq!" },
    manager: { email: "manager@cras.local", password: "CRAS_Manager#73Lp!" },
    user:    { email: "user@cras.local",    password: "CRAS_User#91Km!" }
  }
};
