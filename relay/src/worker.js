/**
 * The Aive remote relay. One Durable Object room per pairing. The Aive connects as the single
 * host; IDEaz instances connect as clients. Frames are opaque AES-GCM ciphertext sealed by the
 * apps with the pairing key, so the relay never sees prompts, code or results.
 *
 *   GET /v1/rooms/{roomId}?role=host|client   (WebSocket upgrade)
 *   Authorization: Bearer <relay token from the pairing code>
 *
 * The first connection claims the room by storing SHA-256(token); later ones must match.
 * Client -> relay: {"data": "<sealed>"}            relay -> host:   {"from": "<clientId>", "data": ...}
 * Host   -> relay: {"to": "<clientId>", "data"}    relay -> client: {"data": ...}
 * Relay events:    {"event": "host-online" | "host-offline"} to clients.
 */

const ROOM_ID = /^[A-Za-z0-9_-]{16,64}$/;
const MAX_FRAME_BYTES = 1_000_000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const match = url.pathname.match(/^\/v1\/rooms\/([^/]+)$/);
    if (!match || !ROOM_ID.test(match[1])) return new Response("Not found", { status: 404 });
    if (request.headers.get("Upgrade") !== "websocket") return new Response("Expected WebSocket", { status: 426 });
    const role = url.searchParams.get("role");
    if (role !== "host" && role !== "client") return new Response("Bad role", { status: 400 });
    const token = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
    if (token.length < 32) return new Response("Unauthorized", { status: 401 });
    const room = env.ROOMS.get(env.ROOMS.idFromName(match[1]));
    return room.fetch(request);
  },
};

async function sha256(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export class RemoteRoom {
  constructor(state) {
    this.state = state;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const role = url.searchParams.get("role");
    const token = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
    const presented = await sha256(token);
    const stored = await this.state.storage.get("tokenHash");
    if (stored === undefined) {
      await this.state.storage.put("tokenHash", presented);
    } else if (!timingSafeEqual(stored, presented)) {
      return new Response("Unauthorized", { status: 401 });
    }

    const [client, server] = Object.values(new WebSocketPair());
    if (role === "host") {
      // One host per room: a reconnecting device replaces its stale socket.
      for (const old of this.state.getWebSockets("host")) old.close(4000, "Replaced by a newer host");
      this.state.acceptWebSocket(server, ["host"]);
      this.broadcastToClients({ event: "host-online" });
    } else {
      const clientId = crypto.randomUUID();
      this.state.acceptWebSocket(server, ["client", clientId]);
      server.send(JSON.stringify({ event: this.hostSocket() ? "host-online" : "host-offline" }));
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    if (typeof message !== "string" || message.length > MAX_FRAME_BYTES) {
      ws.close(1009, "Frame too large or not text");
      return;
    }
    let frame;
    try {
      frame = JSON.parse(message);
    } catch {
      return;
    }
    if (typeof frame.data !== "string") return;
    const tags = this.state.getTags(ws);
    if (tags[0] === "host") {
      for (const target of this.state.getWebSockets(String(frame.to || ""))) {
        target.send(JSON.stringify({ data: frame.data }));
      }
    } else {
      const host = this.hostSocket();
      if (host) host.send(JSON.stringify({ from: tags[1], data: frame.data }));
      else ws.send(JSON.stringify({ event: "host-offline" }));
    }
  }

  async webSocketClose(ws) {
    if (this.state.getTags(ws)[0] === "host" && !this.hostSocket(ws)) {
      this.broadcastToClients({ event: "host-offline" });
    }
  }

  hostSocket(except) {
    return this.state.getWebSockets("host").find((socket) => socket !== except && socket.readyState === 1);
  }

  broadcastToClients(event) {
    const text = JSON.stringify(event);
    for (const socket of this.state.getWebSockets("client")) {
      try {
        socket.send(text);
      } catch {}
    }
  }
}
