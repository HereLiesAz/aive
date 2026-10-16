# aive-relay

Cloudflare Worker that lets IDEaz reach The Aive on another device. Frames are sealed end to end with the pairing key, so the relay only forwards ciphertext. For the protocol, see [`docs/Remote-API.md`](../docs/Remote-API.md#relay).

## Deploy

~~~sh
cd relay
npx wrangler@4 deploy
~~~

This serves the relay at `https://aive-relay.<account>.workers.dev`. The apps default to `https://aive-relay.hereliesaz.workers.dev`.
