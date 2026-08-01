/* Replay of a recorded engine session.
 *
 * Deliberately small and dependency-free: it swaps text and bar widths on
 * elements the server already rendered. It computes nothing about the market
 * — every number it displays came out of torihiki on the JVM and is carried
 * in the JSON payload above. If this file were deleted the page would still
 * render a correct, complete first frame; that is the intended failure mode.
 */
(function () {
  var el = document.getElementById("tk-data");
  if (!el) return;
  var d = JSON.parse(el.textContent);
  var frames = d.frames, tick = d.tick, lots = d.lots;
  if (!frames || !frames.length) return;

  function usd(t) {
    var c = t * tick, s = String(Math.floor(c / 100)), r = c % 100;
    return s.replace(/\B(?=(\d{3})+(?!\d))/g, ",") + "." + String(r).padStart(2, "0");
  }
  function coins(l) {
    var n = l < 0 ? -l : l;
    return (l < 0 ? "-" : "") + Math.floor(n / lots) + "." + String(n % lots).padStart(3, "0");
  }
  function set(id, v) { var e = document.getElementById(id); if (e) e.textContent = v; }

  function rows(host, list, side, maxCum) {
    if (!host) return;
    var html = "";
    for (var i = 0; i < list.length; i++) {
      var r = list[i], w = Math.min(100, Math.round(100 * r[2] / (maxCum || 1)));
      html += '<div class="tk-row tk-row--' + side + '">' +
        '<span class="tk-bar" style="width:' + w + '%"></span>' +
        '<span class="tk-px">' + usd(r[0]) + "</span>" +
        '<span class="tk-sz">' + coins(r[1]) + "</span>" +
        '<span class="tk-cum">' + coins(r[2]) + "</span></div>";
    }
    host.innerHTML = html;
  }

  var i = 0;
  var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function draw() {
    var f = frames[i];
    var maxCum = 1;
    f.b.concat(f.a).forEach(function (r) { if (r[2] > maxCum) maxCum = r[2]; });
    rows(document.getElementById("tk-asks"), f.a.slice().reverse(), "ask", maxCum);
    rows(document.getElementById("tk-bids"), f.b, "bid", maxCum);
    set("tk-last", "$" + usd(f.l));
    set("tk-oracle", "$" + usd(f.o));
    set("tk-oracle2", "$" + usd(f.o));
    set("tk-equity", "$" + usd(Math.floor(f.e / tick)));
    set("tk-height", f.h);
    set("tk-height2", f.h);
    set("tk-resting", f.r);
    set("tk-root", f.rt);
    var t = document.getElementById("tk-trades");
    if (t) {
      var html = "";
      for (var k = 0; k < f.t.length; k++) {
        var x = f.t[k];
        html += '<div class="tk-row tk-row--' + (x[2] === 0 ? "bid" : "ask") + '">' +
          '<span class="tk-px">' + usd(x[0]) + "</span>" +
          '<span class="tk-sz">' + coins(x[1]) + "</span>" +
          '<span class="tk-cum">' + Math.floor(x[3] / 1000) + "</span></div>";
      }
      t.innerHTML = html;
    }
    var size = f.p[0];
    set("tk-pos-size", coins(size < 0 ? -size : size));
    set("tk-pos-entry", "$" + usd(f.p[1]));
    var pnl = document.getElementById("tk-pos-upnl");
    if (pnl) {
      pnl.textContent = (f.p[2] < 0 ? "-$" : "+$") + usd(Math.floor(Math.abs(f.p[2]) / tick));
      pnl.className = "tk-px " + (f.p[2] < 0 ? "tk-down" : "tk-up");
    }
    i = (i + 1) % frames.length;
  }

  draw();
  /* Respecting prefers-reduced-motion is part of the accessibility contract
     this stack already keeps; a ticking book is motion. */
  if (!reduced) setInterval(draw, 900);
})();
