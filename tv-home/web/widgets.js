// Two small glass widgets either side of the Night look's big clock: India's time with the dollar in
// rupees on the left, 22 carat gold (8 g) in Hyderabad, Dubai and New York on the right (rates.js).
// app.css shows them only on the Night look's main screen.
(function () {
  function el(html) { var d = document.createElement("div"); d.innerHTML = html; return d.firstChild; }
  var left = el('<aside class="wdg wdg-l" aria-label="India">' +
    '<div><div class="wk">India</div><div class="wbig" id="w-ist"></div><div class="wsm" id="w-day"></div></div>' +
    '<div><div class="wrule"></div><div class="wrow"><span>$1</span><b id="w-usd">–</b></div></div></aside>');
  var right = el('<aside class="wdg wdg-r" aria-label="Gold">' +
    '<div class="wk">Gold 22K · 8 g</div>' +
    '<div class="wrow"><span>Hyderabad</span><b id="w-hyd">–</b></div>' +
    '<div class="wrow"><span>Dubai</span><b id="w-dxb">–</b></div>' +
    '<div class="wrow"><span>New York</span><b id="w-ny">–</b></div></aside>');
  document.body.appendChild(left); document.body.appendChild(right);
  function $(id) { return document.getElementById(id); }

  function tick() { $("w-ist").textContent = window.indiaTime(); $("w-day").textContent = window.indiaDay(); }
  function rates() {
    window.couchRates(function (r) {
      $("w-usd").textContent = r.usd; $("w-hyd").textContent = r.hyd; $("w-dxb").textContent = r.dxb; $("w-ny").textContent = r.ny;
    });
  }
  tick(); rates();
  setInterval(tick, 10000);
  setInterval(rates, window.couchRates.KEEP);
})();
