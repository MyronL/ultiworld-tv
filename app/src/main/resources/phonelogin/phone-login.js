// The phone login page's own script. PhoneLoginServer serves it inline after @noble/ciphers, which gives it `gcm`.
//
// The QR code's link ends in #<key>. Browsers never send that part, so the key goes from the TV's screen to the
// phone's camera and nowhere else. The page seals the email and password with it and posts only the sealed bytes.
// Without the key (the link typed by hand, or scripts off) the form stays hidden and the page says to scan the code.
{
  const b64url = (bytes) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const unb64url = (text) => Uint8Array.from(atob(text.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));

  const form = document.getElementById('login');
  const hash = location.hash.slice(1);
  const key = /^[A-Za-z0-9_-]{43}$/.test(hash) ? unb64url(hash) : null;
  if (form && key && key.length === 32) {
    const code = new URLSearchParams(location.search).get('k') || '';
    const username = document.getElementById('username');
    const password = document.getElementById('password');
    const button = form.querySelector('button');
    document.getElementById('scan').hidden = true;
    form.hidden = false;
    username.focus();
    // Back to this page from the next one: the button works again.
    window.addEventListener('pageshow', () => {
      button.disabled = false;
      button.textContent = 'Log in on the TV';
    });
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      const enc = new TextEncoder();
      const login = new URLSearchParams({ username: username.value, password: password.value }).toString();
      // A fresh nonce for every try; the TV turns down one it has seen. The code is bound in as associated data.
      const nonce = crypto.getRandomValues(new Uint8Array(12));
      const sealed = gcm(key, nonce, enc.encode(code)).encrypt(enc.encode(login));
      form.elements.n.value = b64url(nonce);
      form.elements.c.value = b64url(sealed);
      // Keep the key on the next page, so a failed try can be fixed there.
      form.action = location.pathname + location.search + location.hash;
      button.disabled = true;
      button.textContent = 'Logging in…';
      form.submit();
    });
  }
}
