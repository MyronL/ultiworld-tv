package io.github.myronl.ultiworldtv.bridge

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

    /**
     * Run on the loaded login page (WordPress's wp-login.php): whether the usual form is there, and whether
     * it carries a human check the app can't fill in for you.
     */
    const val LOGIN_FORM_STATE = """
        const form = document.querySelector('#loginform');
        return {
          hasForm: !!(form && form.querySelector('#user_login') && form.querySelector('#user_pass')),
          humanCheck: !!(form && form.querySelector(
            '.cf-turnstile, .g-recaptcha, .h-captcha, iframe[src*="recaptcha"], iframe[src*="turnstile"], iframe[src*="hcaptcha"]'
          )),
        };
    """

    /**
     * Fills in the login form and submits it. The submit runs after this returns, because the page is
     * replaced by the next one and could not answer afterwards. Ticks "Remember Me" so the login lasts.
     * Refuses unless the page is still Ultiworld's login page, so the password can't land anywhere else.
     */
    fun submitLogin(username: String, password: String) = """
        if (location.origin !== ${JSONObject.quote(WebBridge.ORIGIN)} || location.pathname.indexOf('wp-login.php') === -1) {
          throw new Error("Ultiworld's login page went away. Try again.");
        }
        const form = document.querySelector('#loginform');
        const set = (sel, v) => {
          const el = form.querySelector(sel);
          el.focus();
          el.value = v;
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
        };
        set('#user_login', ${JSONObject.quote(username)});
        set('#user_pass', ${JSONObject.quote(password)});
        const remember = form.querySelector('#rememberme');
        if (remember) remember.checked = true;
        const button = form.querySelector('#wp-submit, [type=submit]');
        setTimeout(() => (button ? button.click() : form.submit()), 50);
        return true;
    """

    /** WordPress's message after a failed login, without its "Lost your password?" link. */
    const val LOGIN_ERROR = """
        const e = document.querySelector('#login_error, .login .message.error, .notice-error');
        if (!e) return null;
        const c = e.cloneNode(true);
        c.querySelectorAll('a').forEach((a) => { if (/lost your password/i.test(a.textContent)) a.remove(); });
        return c.textContent.replace(/\s+/g, ' ').replace(/^Error:\s*/i, '').trim();
    """

    /** The site's own logout link (it carries WordPress's nonce). The site's scripts add it after load, so wait a little. */
    const val LOGOUT_URL = """
        $WAIT_FN
        const a = await uwWait(() => document.querySelector('a[href*="action=logout"]'), 5000);
        return a ? a.href : null;
    """

    /**
     * Fetches [url] (a page on the site) and parses it, without navigating. Cloudflare answers a request it wants
     * to check first with a `cf-mitigated: challenge` header; that comes back as `{ challenge: true }` so the app
     * can load a real page, which runs the check.
     */
    private const val FETCH_DOC_FN = """
        async function uwFetchDoc(url) {
          const r = await fetch(url, { credentials: 'include' });
          if (r.headers.get('cf-mitigated') === 'challenge') return { challenge: true };
          if (!r.ok) throw new Error('Ultiworld answered HTTP ' + r.status);
          return { doc: new DOMParser().parseFromString(await r.text(), 'text/html'), url: r.url };
        }
    """

    /**
     * The library's shelves (Live Now, Upcoming Videos, Recent Videos, ...), read from a fetched copy of /video/.
     * The shelves are in the page's HTML, so this is the same as loading the page, minus everything else it loads.
     */
    const val LIBRARY = """
        $GRIDS_FN
        $FETCH_DOC_FN
        const page = await uwFetchDoc('/video/');
        if (page.challenge) return page;
        return { shelves: uwGrids(page.doc, page.url) };
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
        $FETCH_DOC_FN
        const page = await uwFetchDoc(${JSONObject.quote(url)});
        if (page.challenge) return page;
        const seen = new Set();
        const items = [];
        uwGrids(page.doc, page.url).forEach((s) => s.items.forEach((it) => { if (!seen.has(it.url)) { seen.add(it.url); items.push(it); } }));
        return { items: items };
    """

    /**
     * Searches the video library the way the site's own search box does: Ultiworld's Algolia index, with the
     * search-only key the site puts on every page as `window.algoliaWP`. Library videos are the "Video" content
     * type outside the "Video" article category (those are articles with an embedded clip).
     */
    fun search(query: String) = """
        const cfg = window.algoliaWP;
        if (!cfg || !cfg.appId || !cfg.publicApiKey) throw new Error("Ultiworld's search isn't available right now");
        const index = encodeURIComponent(cfg.defaultIndex || 'uw_index');
        const auth = 'x-algolia-application-id=' + encodeURIComponent(cfg.appId) + '&x-algolia-api-key=' + encodeURIComponent(cfg.publicApiKey);
        const r = await fetch('https://' + cfg.appId.toLowerCase() + '-dsn.algolia.net/1/indexes/' + index + '/query?' + auth, {
          method: 'POST',
          credentials: 'omit',
          body: JSON.stringify({
            query: ${JSONObject.quote(query)},
            hitsPerPage: 48,
            filters: 'content_type:Video AND NOT categories:Video',
            attributesToRetrieve: ['title', 'url', 'image', 'post_date', '_tags', 'divisions'],
            attributesToHighlight: [],
          }),
        });
        if (!r.ok) throw new Error('Search answered HTTP ' + r.status);
        const j = await r.json();
        return (j.hits || [])
          .filter((h) => {
            if (!h.url) return false;
            const u = new URL(h.url, location.href);
            return u.host === location.host && /^\/video\/\d+\//.test(u.pathname);
          })
          .map((h) => ({
            title: h.title || '',
            url: new URL(h.url, location.href).href,
            image: h.image || null,
            badge: h.post_date || null,
            live: false,
            event: (h._tags || [])[0] || null,
            division: (h.divisions || [])[0] || null,
            collection: false,
          }));
    """

    /**
     * Run on a loaded video page. The site's own paywall script swaps in a `<mux-player>` once it has
     * checked the subscription; this waits for that and reads the stream's playback ID and signed token.
     *
     * It stops waiting early when no player is coming: right away when logged out (the paywall message is
     * already on the page), or a few seconds after the paywall check swapped in something other than a player.
     *
     * [loggedIn] comes from the login cookie, not the page: the page's "Log out" link is added by the site's
     * scripts after load, and reading it too early made a logged-in TV look logged out.
     */
    fun playback(loggedIn: Boolean) = """
        $WAIT_FN
        const WALL = '[data-content-endpoint], [data-authorization-endpoint]';
        const loggedIn = $loggedIn;
        const wall0 = document.querySelector(WALL);
        const holder = wall0 ? wall0.parentElement : null;
        let goneAt = 0;
        // A video that hasn't started: the paywall check swaps in a countdown ("will begin streaming in 22 hours").
        const soon = (el) => !!el && /\bbegins? streaming\b/i.test(el.textContent);
        const found = await uwWait(() => {
          const p = document.querySelector('mux-player[playback-id]');
          if (p) return { player: p };
          const stopped = document.querySelector('.uw-stream-stopped');
          if (stopped) return { stopped: stopped };
          const wall = document.querySelector(WALL);
          if (!loggedIn && wall) return { locked: wall };
          if (soon(holder)) return { upcoming: holder };
          if (wall0 && !wall && !goneAt) goneAt = Date.now();
          if (goneAt && Date.now() - goneAt > 5000) return { locked: holder };
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
          };
        }
        if (found && found.stopped) return { status: 'stopped', message: clean(found.stopped) };
        if (found && found.upcoming) return { status: 'upcoming', message: clean(found.upcoming) };
        if (found && found.locked) return { status: 'locked', message: clean(found.locked) };
        const wall = document.querySelector(WALL);
        if (wall) return { status: 'locked', message: clean(wall) };
        return { status: 'missing', message: '' };
    """

    /**
     * The site's device-limit check-in, sent the way its own player sends it: `streaming=true` every
     * 80 seconds while playing, `streaming=false` otherwise. Marked with [WebBridge.APP_CHECK_IN_PARAM]
     * so [WebBridge] can tell it from the page's own check-ins, which it blocks.
     */
    fun heartbeat(streaming: Boolean) = """
        const base = (window.uwHeartbeatConfig && uwHeartbeatConfig.url) || '/wp-json/uw/v1/heartbeat/';
        const url = base + (base.indexOf('?') === -1 ? '?' : '&') + '${WebBridge.APP_CHECK_IN_PARAM}=1';
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
     * Makes Cloudflare's check usable with a TV remote: up and down move between boxes and buttons, OK on a
     * checkbox ticks it, and the focused element gets a visible outline. Run after each page load while the
     * WebView is on screen.
     */
    const val REMOTE_FRIENDLY_PAGE = """
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
        return true;
    """
}
