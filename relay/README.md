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

This serves the relay at `https://aive-relay.<account>.workers.dev`. The first deploy applies two Durable Object migrations (`RemoteRoom`, then `CreateLimiter`, which counts room creations per client address); deploy with `wrangler deploy`, not `versions upload`, so migrations are applied. A Cloudflare Workers Builds project for this repository must use `relay` as its root directory.

## Limits

Frames are capped at 1,000,000 bytes, each socket may send a burst of 40 frames then 10 per second, one client address may create 20 rooms per hour, and a room admits 8 clients. See the Limits section of [`docs/Remote-API.md`](../docs/Remote-API.md#limits).
