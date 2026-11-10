// India time, the dollar in rupees, and 22 carat gold (8 g) in Hyderabad, Dubai and New York, for the
// home's widgets (widgets.js) and the screensaver. Free sources with no account; kept 30 minutes in
// this browser's storage, which the home and the screensaver share (same address).
(function () {
  var KEEP = 30 * 60000, OZ = 31.1035;
  // Indian gold is quoted with the import duty on top of the world price (6% since July 2024); GST is added at the shop.
  var INDIA_DUTY = 1.06;

  function money(n, cur, d) {
    try { return new Intl.NumberFormat(cur === "INR" ? "en-IN" : "en-US", { style: "currency", currency: cur, maximumFractionDigits: d || 0 }).format(n); }
    catch (e) { return cur + " " + Math.round(n); }
  }
  function view(r) {
    var g = r.gold / OZ * (22 / 24) * 8;   // 8 g of 22 carat gold (one sovereign), in dollars
    return { usd: money(r.inr, "INR", 2), hyd: "≈ " + money(g * r.inr * INDIA_DUTY, "INR"), dxb: money(g * r.aed, "AED"),
      ny: money(g, "USD"), at: new Date(r.at).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }) };
  }

  /** Calls back with the formatted rates: at once from storage if there, again when fresh ones arrive. */
  window.couchRates = function (cb) {
    var kept = null;
    try { kept = JSON.parse(localStorage.getItem("couch-rates")); } catch (e) {}
    if (kept && kept.inr && kept.gold) cb(view(kept));
    if (kept && Date.now() - kept.at < KEEP) return;
    Promise.all([
      fetch("https://open.er-api.com/v6/latest/USD").then(function (r) { return r.json(); }),
      fetch("https://api.gold-api.com/price/XAU").then(function (r) { return r.json(); })
    ]).then(function (a) {
      var r = { inr: a[0].rates.INR, aed: a[0].rates.AED, gold: a[1].price, at: Date.now() };
      if (!r.inr || !r.aed || !r.gold) return;
      try { localStorage.setItem("couch-rates", JSON.stringify(r)); } catch (e) {}
      cb(view(r));
    }).catch(function () {});
  };
  window.couchRates.KEEP = KEEP;

  /** The time in India, "11:38 PM". */
  window.indiaTime = function (d) {
    try { return (d || new Date()).toLocaleTimeString("en-US", { timeZone: "Asia/Kolkata", hour: "numeric", minute: "2-digit" }); }
    catch (e) { return ""; }
  };
  /** The day in India, "Wednesday". */
  window.indiaDay = function (d) {
    try { return (d || new Date()).toLocaleDateString("en-US", { timeZone: "Asia/Kolkata", weekday: "long" }); }
    catch (e) { return ""; }
  };
})();
