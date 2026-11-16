/**
 * The consent pages: plain HTML, no script, strict CSP (a per-response nonce allows only the one
 * inline stylesheet), dark and monochrome like the app. Every dynamic value is escaped.
 */
import { escapeHtml as h, randomToken } from "./util.js";

export const PRIVACY_URL = "https://hereliesaz.github.io/aive/privacy";
export const DOCS_URL = "https://github.com/HereLiesAz/aive/blob/main/docs/Connector.md";

const CSS = `
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;background:#000;color:#e6e6e6;font:16px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
main{max-width:34rem;margin:0 auto;padding:2rem 1rem 3rem}
h1{font-size:1.4rem;font-weight:600;margin:0 0 1rem}
h2{font-size:1rem;font-weight:600;margin:1.5rem 0 .4rem;color:#fff}
p,li{color:#cfcfcf}
ul{padding-left:1.2rem;margin:.3rem 0}
.box{border:1px solid #444;padding:1rem;margin:1rem 0}
.err{border-color:#fff;color:#fff}
label{display:block;margin:1rem 0 .3rem;color:#fff}
input[type=password]{width:100%;background:#000;color:#fff;border:1px solid #777;padding:.7rem;font:inherit}
input[type=password]:focus,button:focus{outline:2px solid #fff;outline-offset:2px}
.row{display:flex;gap:.7rem;flex-wrap:wrap;margin-top:1.2rem}
button{background:#fff;color:#000;border:1px solid #fff;padding:.7rem 1.2rem;font:inherit;cursor:pointer}
button.quiet{background:#000;color:#e6e6e6;border-color:#777}
a{color:#fff}
small{color:#9a9a9a}
`;

function layout(title, bodyHtml) {
  const nonce = randomToken(16);
  const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>${h(title)}</title>
<style nonce="${nonce}">${CSS}</style></head>
<body><main>${bodyHtml}</main></body></html>`;
  return { html, nonce };
}

function respond(title, bodyHtml, status = 200, extraHeaders = {}) {
  const { html, nonce } = layout(title, bodyHtml);
  return new Response(html, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "content-security-policy": `default-src 'none'; style-src 'nonce-${nonce}'; base-uri 'none'; frame-ancestors 'none'`,
      "x-content-type-options": "nosniff",
      "x-frame-options": "DENY",
      "referrer-policy": "no-referrer",
      ...extraHeaders,
    },
  });
}

const CAN = `<h2>Claude will be able to</h2>
<ul><li>see whether your Aive is online</li><li>read the swarm your Aive is showing and its Story</li><li>see what is waiting for you</li></ul>
<h2>Claude will not be able to</h2>
<ul><li>approve, reject or change anything</li><li>start a swarm or run a prompt</li><li>change your settings</li></ul>
<p>You decide everything on your own device.</p>`;

const TRUST = `<h2>What this connector keeps</h2>
<p>Your Google account's ID and email address, and your pairing code, encrypted. It reads your swarm's content only while Claude is asking for it, and does not store it. Turning off Remote access in The Aive, or using Reset pairing, deletes the pairing here too. <a href="${PRIVACY_URL}">Privacy policy</a></p>`;

export function errorPage(message, status = 400) {
  return respond("Aive connector", `<h1>Something is wrong</h1><div class="box err" role="alert">${h(message)}</div><p>Start again from Claude.</p>`, status);
}

export function pairingPage({ email, csrf, error, status = 200 }) {
  return respond(
    "Connect Claude to your Aive",
    `<h1>Connect Claude to your Aive</h1>
<p>Signed in as <strong>${h(email)}</strong>.</p>
${CAN}${TRUST}
${error ? `<div class="box err" role="alert">${h(error)}</div>` : ""}
<form method="post" action="/authorize" autocomplete="off">
<input type="hidden" name="csrf" value="${h(csrf)}">
<label for="code">Pairing code</label>
<small>In The Aive: Settings, Remote Access, then Copy. Anyone with the code can reach your Aive, so paste it only here.</small>
<input id="code" name="code" type="password" autocomplete="off" spellcheck="false" required>
<div class="row"><button type="submit" name="decision" value="allow">Allow</button><button class="quiet" type="submit" name="decision" value="deny" formnovalidate>Cancel</button></div>
</form>`,
    status,
  );
}

export function confirmPage({ email, csrf }) {
  return respond(
    "Connect Claude to your Aive",
    `<h1>Connect Claude to your Aive</h1>
<p>Signed in as <strong>${h(email)}</strong>. Your Aive is already paired.</p>
${CAN}${TRUST}
<form method="post" action="/authorize">
<input type="hidden" name="csrf" value="${h(csrf)}">
<div class="row"><button type="submit" name="decision" value="allow">Allow</button><button class="quiet" type="submit" name="decision" value="repair">Use a different pairing code</button><button class="quiet" type="submit" name="decision" value="deny">Cancel</button></div>
</form>`,
  );
}

export function homePage() {
  return respond(
    "The Aive connector",
    `<h1>The Aive connector</h1>
<p>This is the connector that lets Claude read the state of your own Aive device. Add it from Claude's connector settings.</p>
<ul><li><a href="${DOCS_URL}">How it works</a></li><li><a href="${PRIVACY_URL}">Privacy policy</a></li></ul>`,
  );
}
