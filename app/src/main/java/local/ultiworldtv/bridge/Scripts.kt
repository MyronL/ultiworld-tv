package local.ultiworldtv.bridge

import org.json.JSONObject

/**
 * JavaScript run inside ultiworld.com pages by [WebBridge.evaluate]. Each is the body of an async
 * function: it may `await` and must `return` something JSON can carry.
 *
 * The selectors follow the site's markup as of October 2026 (video-grid__*, mux-player, the
 * uw/v1 scorebar and heartbeat endpoints). When Ultiworld changes its site, this is the file to fix.
 */
object Scripts {
    /** Reads every `.video-grid` in a document into shelves of cards. Shared by the library and collection pages. */
    private const val GRIDS_FN = """
        function uwGrids(doc, base) {
          const txt = (el, sel) => { const e = el.querySelector(sel); return e ? e.textContent.trim() : null; };
          return [...doc.querySelectorAll('.video-grid')].map((g) => {
            let p = g.previousElementSibling, hops = 0;
            while (p && hops < 4 && !p.textContent.trim()) { p = p.previousElementSibling; hops++; }
            const heading = p && /\bh\d\b/.test(p.className || '') ? p.textContent.trim() : '';
            const items = [...g.querySelectorAll('.video-grid__item')].map((it) => {
              const a = it.querySelector('a.video-grid__item-link') || it.querySelector('a[href]');
              if (!a) return null;
              const img = it.querySelector('img');
              const url = new URL(a.getAttribute('href'), base).href;
              const subs = [...it.querySelectorAll('.video-grid__item-subtitle a')].map((x) => x.textContent.trim());
              const badge = txt(it, '.badge--bottom-left');
              return {
                title: txt(it, '.video-grid__item-title') || a.getAttribute('title') || '',
                url: url,
                image: img ? (img.getAttribute('data-src') || img.getAttribute('src')) : null,
                badge: badge,
                live: !!badge && /^live$/i.test(badge),
                event: subs[0] || null,
                division: subs[1] || null,
                collection: !/\/video\/\d+\//.test(new URL(url).pathname),
              };
            }).filter(Boolean);
            return { title: heading, items: items };
          }).filter((s) => s.items.length);
        }
    """

    private const val WAIT_FN = """
        const uwWait = async (test, ms) => {
          const end = Date.now() + ms;
          while (Date.now() < end) { const v = test(); if (v) return v; await new Promise((r) => setTimeout(r, 200)); }
          return null;
        };
    """

    const val IS_CHALLENGE = """
        return document.title.indexOf('Just a moment') === 0
          || !!document.querySelector('#challenge-form, #challenge-stage, #cf-challenge-running');
    """

    /** Logged in when the page offers a logout link (WordPress adds no body class on this theme). */
    const val IS_LOGGED_IN = """return !!document.querySelector('a[href*="action=logout"]');"""

    const val LOGOUT_URL = """
        const a = document.querySelector('a[href*="action=logout"]');
        return a ? a.href : null;
    """

    /** Run on the loaded /video/ page: the library's shelves (Live Now, Upcoming Videos, Recent Videos, ...). */
    const val LIBRARY = """
        $GRIDS_FN
        $WAIT_FN
        await uwWait(() => document.querySelector('.video-grid'), 5000);
        return {
          loggedIn: !!document.querySelector('a[href*="action=logout"]'),
          shelves: uwGrids(document, location.href),
        };
    """

    /**
     * Today's games from the public score feed the site's score bar reads: a manifest of current events,
     * then one request per event for its games on the event's current day.
     */
    const val SCORES = """
        const base = '/wp-json/uw/v1/scorebar/';
        const get = async (u) => { const r = await fetch(u, { credentials: 'omit' }); return r.ok ? r.json() : null; };
        const manifest = await get(base);
        if (!manifest || !Array.isArray(manifest.events)) return { events: [], poll: 60 };
        const events = await Promise.all(manifest.events.map(async (ev) => {
          const days = ev.days || [];
          let day = days.indexOf(ev.today) !== -1 ? ev.today : null;
          if (!day) { const past = days.filter((d) => d <= ev.today); day = past.length ? past[past.length - 1] : days[0]; }
          const payload = day ? await get(base + encodeURIComponent(ev.id) + '/' + day + '/') : null;
          return {
            id: ev.id, title: ev.short_title || ev.title, url: ev.url, day: day, timeZone: ev.time_zone,
            games: payload && Array.isArray(payload.games) ? payload.games : [],
          };
        }));
        return { events: events, livestreams: manifest.livestreams || [], poll: manifest.poll_interval || 60 };
    """

    /** Every card on a filtered library page (an event, team pack or feature), fetched without navigating. */
    fun collection(url: String) = """
        $GRIDS_FN
        const url = ${JSONObject.quote(url)};
        const r = await fetch(url, { credentials: 'include' });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const doc = new DOMParser().parseFromString(await r.text(), 'text/html');
        const seen = new Set();
        const items = [];
        uwGrids(doc, url).forEach((s) => s.items.forEach((it) => { if (!seen.has(it.url)) { seen.add(it.url); items.push(it); } }));
        return items;
    """

    /**
     * Run on a loaded video page. The site's own paywall script swaps in a `<mux-player>` once it has
     * checked the subscription; this waits for that and reads the stream's playback ID and signed token.
     *
     * It stops waiting early when no player is coming: right away when logged out (the paywall message is
     * already on the page), or shortly after the paywall check swapped in something other than a player.
     */
    const val PLAYBACK = """
        $WAIT_FN
        const WALL = '[data-content-endpoint], [data-authorization-endpoint]';
        const loggedIn = !!document.querySelector('a[href*="action=logout"]');
        const wall0 = document.querySelector(WALL);
        const holder = wall0 ? wall0.parentElement : null;
        let goneAt = 0;
        const found = await uwWait(() => {
          const p = document.querySelector('mux-player[playback-id]');
          if (p) return { player: p };
          const stopped = document.querySelector('.uw-stream-stopped');
          if (stopped) return { stopped: stopped };
          const wall = document.querySelector(WALL);
          if (!loggedIn && wall) return { locked: wall };
          if (wall0 && !wall && !goneAt) goneAt = Date.now();
          if (goneAt && Date.now() - goneAt > 2500) return { locked: holder };
          return null;
        }, 20000);
        // The visible lines of a message block, without the analytics scripts inlined in it.
        const clean = (el) => {
          if (!el) return '';
          const c = el.cloneNode(true);
          c.querySelectorAll('script, style, noscript, button, a.button, .button').forEach((x) => x.remove());
          // Curly quotes to straight: the page title and the heading spell "Drag’n" differently.
          const norm = (s) => (s || '').replace(/[‘’]/g, "'").replace(/[“”]/g, '"').replace(/\s+/g, ' ').trim();
          const title = norm(document.title.split(' - ')[0]);
          // The website's calls to action (subscribe, buy a pack, log in) don't apply on the TV.
          const cta = /^(subscribe|log ?in|sign ?up|already have|buy )/i;
          let lines = [...c.querySelectorAll('h1, h2, h3, h4, h5, p, li')]
            .map((x) => norm(x.textContent))
            .map((t) => (title && t.startsWith(title) ? norm(t.slice(title.length)) : t))
            .filter((t, i, all) => t && !cta.test(t) && all.indexOf(t) === i);
          if (!lines.length) lines = [norm(c.textContent)];
          return lines.join('\n').slice(0, 400);
        };
        if (found && found.player) {
          const p = found.player;
          return {
            status: 'ok',
            playbackId: p.getAttribute('playback-id'),
            token: p.getAttribute('playback-token'),
            streamType: p.getAttribute('stream-type') || '',
            title: p.getAttribute('metadata-video-title') || document.title,
            poster: p.getAttribute('poster'),
          };
        }
        if (found && found.stopped) return { status: 'stopped', message: clean(found.stopped) };
        if (found && found.locked) return { status: 'locked', message: clean(found.locked) };
        const wall = document.querySelector(WALL);
        if (wall) return { status: 'locked', message: clean(wall) };
        return { status: 'missing', message: '' };
    """

    /**
     * The site's device-limit check-in, sent the way its own player sends it: `streaming=true` every
     * 80 seconds while playing, `streaming=false` otherwise.
     */
    fun heartbeat(streaming: Boolean) = """
        const url = (window.uwHeartbeatConfig && uwHeartbeatConfig.url) || '/wp-json/uw/v1/heartbeat/';
        const r = await fetch(url, {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest' },
          body: 'streaming=' + ${if (streaming) "'true'" else "'false'"},
        });
        let j = null;
        try { j = await r.json(); } catch (e) {}
        return {
          status: r.status,
          sessionValid: !(j && j.session_valid === false),
          stopStreaming: !!(j && Array.isArray(j.actions) && j.actions.indexOf('stop_streaming') !== -1),
        };
    """

    /**
     * Makes a web form usable with a TV remote: up and down move between fields and buttons, OK on a
     * checkbox ticks it, and the focused element gets a visible outline. Ticks "Remember me" so the login
     * lasts. Run after each page load while the WebView is on screen.
     */
    const val REMOTE_FRIENDLY_FORM = """
        if (!window.__uwRemote) {
          window.__uwRemote = true;
          const style = document.createElement('style');
          style.textContent = ':focus { outline: 4px solid #00b3c7 !important; outline-offset: 3px !important; }';
          document.head.appendChild(style);
          const focusables = () => [...document.querySelectorAll(
            'input:not([type=hidden]):not([disabled]), button:not([disabled]), select, textarea, a[href], [tabindex]:not([tabindex="-1"])'
          )].filter((el) => el.offsetParent !== null);
          document.addEventListener('keydown', (e) => {
            const els = focusables();
            if (!els.length) return;
            const i = els.indexOf(document.activeElement);
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
              const n = e.key === 'ArrowDown' ? Math.min(els.length - 1, i + 1) : Math.max(0, i - 1);
              els[n].focus();
              els[n].scrollIntoView({ block: 'center' });
              e.preventDefault();
            } else if (e.key === 'Enter' && document.activeElement && document.activeElement.type === 'checkbox') {
              document.activeElement.click();
              e.preventDefault();
            }
          }, true);
        }
        const remember = document.querySelector('#rememberme, input[name=rememberme]');
        if (remember && !remember.checked) remember.checked = true;
        const first = document.querySelector('#user_login, input[name=log], input[type=email], input[type=text]');
        if (first && document.activeElement === document.body) first.focus();
        return true;
    """
}
