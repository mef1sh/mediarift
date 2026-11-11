/*
 * Small helper that the mod runs inside EVERY page of the player window (more than once: it installs itself only once).
 *   1. VOLUME GUARD: the volume of the mod always wins. Sites (YouTube Music...) set the volume of every new song by themselves,
 *      which made the sound jump loud for a moment. Now the page can set what it likes: the real volume = its value x our value.
 *   2. RESOLUTION: switches the YouTube player to the resolution of the mod settings (1080 / 720 / 480).
 *   3. remembers the last media error (for /psb debug).
 */
(function () {
  try {
    if (window.__mrShim) return;
    window.__mrShim = 1;
    var S = window.__mr = { err: '', vf: 1, res: 0 };

    // ---- 2. resolution (YouTube): the player of YouTube can be told which quality to use
    var QN = { 1080: 'hd1080', 720: 'hd720', 480: 'large' };
    S.applyRes = function () {
      try {
        var q = QN[S.res];
        if (!q) return;
        var p = document.getElementById('movie_player');
        if (!p || !p.setPlaybackQualityRange || !p.getVideoData) return;
        var key = ((p.getVideoData() || {}).video_id || '') + '|' + q;
        if (p.__mrKey !== key) { p.__mrKey = key; p.__mrTry = 0; }                   // a new video: start again
        if (p.getPlaybackQuality && p.getPlaybackQuality() === q) return;           // already there
        if (p.__mrTry++ < 4) {                                                       // (the video may not have this quality at all)
          p.setPlaybackQualityRange(q, q);
          if (p.setPlaybackQuality) p.setPlaybackQuality(q);
        }
      } catch (e) {}
    };
    S.setRes = function (h) { S.res = Number(h) || 0; S.applyRes(); };

    // ---- 1. volume guard
    var vd = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
    if (vd && vd.get && vd.set) {
      Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
        configurable: true,
        enumerable: vd.enumerable,
        get: function () { return this.__pv === undefined ? vd.get.call(this) : this.__pv; },
        set: function (v) {
          v = Number(v);
          if (!(v >= 0 && v <= 1)) { return vd.set.call(this, v); }   // let the browser throw its normal error
          this.__pv = v;
          vd.set.call(this, v * S.vf);
        }
      });
      S.fixVol = function (m) {
        try {
          if (m.__pv === undefined) m.__pv = 1;
          var t = m.__pv * S.vf;
          if (Math.abs(vd.get.call(m) - t) > 0.001) vd.set.call(m, t);
        } catch (e) {}
      };
      // the mod changes the volume: f = 0..1
      S.setVol = function (f) {
        S.vf = Math.max(0, Math.min(1, f));
        var l = document.querySelectorAll('video,audio');
        for (var i = 0; i < l.length; i++) S.fixVol(l[i]);
      };
      ['loadstart', 'loadedmetadata', 'play', 'playing', 'volumechange', 'emptied'].forEach(function (n) {
        document.addEventListener(n, function (e) {
          var t = e.target;
          if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) { S.fixVol(t); S.applyRes(); }
        }, true);
      });
    }

    // ---- 3. the last media error
    document.addEventListener('error', function (e) {
      var t = e.target;
      if (t && t.error) {
        S.err = t.error.code + ': ' + (t.error.message || '') + ' [' + String(t.currentSrc || t.src || '').slice(0, 110) + ']';
      }
    }, true);

    // ---- /psb debug
    S.debug = function () {
      var v = document.createElement('video');
      var can = ['audio/mpeg', 'audio/mp4; codecs="mp4a.40.2"', 'audio/webm; codecs="opus"',
                 'video/mp4; codecs="avc1.42E01E"', 'video/webm; codecs="vp9"'];
      function short(t) { return t.replace(/; codecs="(.*)"/, ' ($1)'); }
      var lines = [];
      lines.push('Page: ' + location.hostname + '  |  volume guard: ' + (S.setVol ? 'on' : 'OFF') + ' (x' + S.vf.toFixed(2) + ')');
      lines.push('The browser can play: ' + can.map(function (t) { return short(t) + ' = ' + (v.canPlayType(t) || 'NO'); }).join('  |  '));
      lines.push('Last media error on this page: ' + (S.err || 'none'));
      lines.forEach(function (l) { console.log('MRDBG|' + l); });
    };
  } catch (e) {}
})();
