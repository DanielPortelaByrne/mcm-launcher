/* MCM Home (for Amélia). SBT's own live channel is broken at SBT's end: it goes through an ad server
   (aovivo.maissbt.com, "SSAI_TITAN_V3") that answers 504 "Unable to obtain template playlist" because the
   feed behind it is gone. The same programmes (Domingo Legal, the novelas, the news) run on SBT Rio, whose
   working stream is in the app's own configuration but not in its channel list. So:
   1. When the app reads its stream configuration, the SBT channel is given SBT Rio's stream.
   2. Only if that did not happen is SBT moved to the end of the channel list (/epg), so the app opens on a
      channel that plays instead of on an error.
   Nothing else in the app is changed. */
(function () {
  var SBT = "6353271246112";              // the SBT channel ("Simulcast")
  var RIO = /SBT Rio/i;                    // the configuration entry whose stream works
  var fixed = false;
  var original = window.fetch;
  if (!original) return;

  function rewrite(response, change) {
    return response.clone().json().then(function (data) {
      var next = change(data);
      if (!next) return response;
      return new Response(JSON.stringify(next), { status: response.status, statusText: response.statusText, headers: response.headers });
    }).catch(function () { return response; });
  }

  function giveSbtTheRioStream(data) {
    var list = Array.isArray(data) ? data : data && Array.isArray(data.entries) ? data.entries : null;
    if (!list) return null;
    var sbt = list.filter(function (e) { return e && e.channelOvpId === SBT && e.ssaiUrl; })[0];
    var rio = list.filter(function (e) { return e && RIO.test(e.title || "") && e.ssaiUrl; })[0];
    if (!sbt || !rio) return null;
    sbt.ssaiUrl = rio.ssaiUrl;
    fixed = true;
    return data;
  }

  function sbtLast(list) {
    if (fixed || !Array.isArray(list)) return null;
    return list.filter(function (c) { return c && c.id !== SBT; }).concat(list.filter(function (c) { return c && c.id === SBT; }));
  }

  window.fetch = function (input) {
    var url = typeof input === "string" ? input : (input && input.url) || "";
    var pending = original.apply(this, arguments);
    if (/\/epg(\?|$)/.test(url)) return pending.then(function (r) { return rewrite(r, sbtLast); });
    if (/\/content\/entr(y|ies)/.test(url)) return pending.then(function (r) { return rewrite(r, giveSbtTheRioStream); });
    return pending;
  };
})();
