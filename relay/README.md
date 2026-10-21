# aive-relay

Cloudflare Worker that lets IDEaz reach The Aive on another device. It forwards frames between one host (The Aive) and its paired clients (IDEaz) without reading them; the apps seal every frame end to end. The Aive connects to it only while the user has turned on Remote access. Nothing in this repository deploys it. For the wire protocol, see [`docs/Remote-API.md`](../docs/Remote-API.md#relay).

## Test

~~~sh
cd relay
npm test
~~~

The tests run the worker against an in-memory fake of the Durable Object state with plain Node (20 or later); no dependencies are installed.

## Deploy

~~~sh
cd relay
npx wrangler@4 deploy
~~~

This serves the relay at `https://aive-relay.<account>.workers.dev`.
