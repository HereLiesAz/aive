/**
 * The Aive remote relay. One Durable Object room per pairing. The Aive connects as the single
 * host; IDEaz instances connect as clients. The relay forwards frames without reading them; any
 * end-to-end sealing is up to the apps. Protocol: the "Relay" section of docs/Remote-API.md.
 *
 *   POST   /v1/rooms/{roomId}                 Bearer <hostToken>, body {"clientToken": "..."}
 *          Creates the room, or rotates its client token. Only the host token can do this.
 *   DELETE /v1/rooms/{roomId}                 Bearer <hostToken>. Deletes the room.
 *   GET    /v1/rooms/{roomId}?role=host|client  WebSocket upgrade.
 *          Bearer <hostToken> for role=host, Bearer <clientToken> for role=client.
 *
 * The room stores only SHA-256 hashes of both tokens. A client token never authenticates as host.
 * Client -> relay: {"data": "..."}                relay -> host:   {"from": "<clientId>", "data": ...}
 * Host   -> relay: {"to": "<clientId>", "data"}   relay -> client: {"data": ...}
 * Relay events:    {"event": "host-online" | "host-offline"} to clients.
 */

import {
  TokenBucket,
  countCreate,
  MAX_CLIENTS_PER_ROOM,
  MAX_FRAME_BYTES,
  ROOM_ID,
  ROOM_TTL_MILLIS,
  bearerToken,
  isStrongToken,
  routeFrame,
  sha256Hex,
  timingSafeEqual,
  utf8Length,
} from "./protocol.js";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const match = url.pathname.match(/^\/v1\/rooms\/([^/]+)$/);
    if (!match || !ROOM_ID.test(match[1])) return new Response("Not found", { status: 404 });
    if (!isStrongToken(bearerToken(request))) return new Response("Unauthorized", { status: 401 });
    if (request.method === "GET") {
      if (request.headers.get("Upgrade") !== "websocket") return new Response("Expected WebSocket", { status: 426 });
      const role = url.searchParams.get("role");
      if (role !== "host" && role !== "client") return new Response("Bad role", { status: 400 });
    } else if (request.method !== "POST" && request.method !== "DELETE") {
      return new Response("Method not allowed", { status: 405 });
    }
    if (request.method === "POST" && env.LIMITER) {
      // Anyone may create a room, so creation is metered per client address.
      const ip = request.headers.get("CF-Connecting-IP") || "unknown";
      const verdict = await env.LIMITER.get(env.LIMITER.idFromName(ip)).fetch("https://limiter/create");
      if (verdict.status === 429) return new Response("Too many rooms created; try again later", { status: 429 });
    }
    const room = env.ROOMS.get(env.ROOMS.idFromName(match[1]));
    return room.fetch(request);
  },
};

/** One instance per client address: counts room creations in a fixed window. */
export class CreateLimiter {
  constructor(state) {
    this.state = state;
  }

  async fetch() {
    const { window, allowed } = countCreate(await this.state.storage.get("window"), Date.now());
    await this.state.storage.put("window", window);
    return new Response(null, { status: allowed ? 204 : 429 });
  }
}

export class RemoteRoom {
  constructor(state) {
    this.state = state;
    this.buckets = new WeakMap();
  }

  async fetch(request) {
    const token = bearerToken(request);
    if (request.method === "POST") return this.create(request, token);
    if (request.method === "DELETE") return this.destroy(token);
    return this.connect(new URL(request.url).searchParams.get("role"), token);
  }

  /** Creates the room for the host presenting [hostToken], or rotates its client token. */
  async create(request, hostToken) {
    let body;
    try {
      body = await request.json();
    } catch {
      return new Response("Expected JSON", { status: 400 });
    }
    const clientToken = body && body.clientToken;
    if (!isStrongToken(clientToken)) return new Response("Weak client token", { status: 400 });
    if (clientToken === hostToken) return new Response("Host and client tokens must differ", { status: 400 });
    const hostHash = await sha256Hex(hostToken);
    const stored = await this.state.storage.get("hostTokenHash");
    if (stored !== undefined && !timingSafeEqual(stored, hostHash)) {
      return new Response("Room belongs to another host", { status: 403 });
    }
    await this.state.storage.put({
      hostTokenHash: hostHash,
      clientTokenHash: await sha256Hex(clientToken),
      lastHostAt: Date.now(),
    });
    await this.state.storage.setAlarm(Date.now() + ROOM_TTL_MILLIS);
    // A rotated client token revokes everyone who joined with the old one.
    for (const socket of this.state.getWebSockets("client")) socket.close(4001, "Client token rotated");
    return new Response(null, { status: stored === undefined ? 201 : 204 });
  }

  async destroy(hostToken) {
    if (!(await this.matches("hostTokenHash", hostToken))) return new Response("Unauthorized", { status: 401 });
    await this.deleteRoom("Room deleted");
    return new Response(null, { status: 204 });
  }

  async connect(role, token) {
    if (role !== "host" && role !== "client") return new Response("Bad role", { status: 400 });
    if ((await this.state.storage.get("hostTokenHash")) === undefined) return new Response("No such room", { status: 404 });
    if (!(await this.matches(role === "host" ? "hostTokenHash" : "clientTokenHash", token))) {
      return new Response("Unauthorized", { status: 401 });
    }

    if (role === "client" && this.openClients().length >= MAX_CLIENTS_PER_ROOM) {
      return new Response("Room is full", { status: 429 });
    }
    const [client, server] = Object.values(new WebSocketPair());
    if (role === "host") {
      // One host per room: a reconnecting device replaces its stale socket. Only the host token
      // gets here, so a client can never evict or impersonate the host.
      for (const old of this.state.getWebSockets("host")) old.close(4000, "Replaced by a newer host");
      this.state.acceptWebSocket(server, ["host"]);
      await this.markHostSeen();
      this.broadcastToClients({ event: "host-online" });
    } else {
      const clientId = crypto.randomUUID();
      this.state.acceptWebSocket(server, ["client", clientId]);
      server.send(JSON.stringify({ event: this.hostSocket() ? "host-online" : "host-offline" }));
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    if (typeof message !== "string" || utf8Length(message) > MAX_FRAME_BYTES) {
      ws.close(1009, "Frame too large or not text");
      return;
    }
    let bucket = this.buckets.get(ws);
    if (!bucket) this.buckets.set(ws, (bucket = new TokenBucket()));
    if (!bucket.take()) {
      ws.close(1008, "Sending too fast");
      return;
    }
    const routed = routeFrame(this.state.getTags(ws), message);
    if (!routed) return;
    if (routed.to === "host") {
      const host = this.hostSocket();
      if (host) host.send(JSON.stringify(routed.frame));
      else ws.send(JSON.stringify({ event: "host-offline" }));
    } else {
      for (const target of this.state.getWebSockets(routed.to)) {
        try {
          target.send(JSON.stringify(routed.frame));
        } catch {}
      }
    }
  }

  async webSocketClose(ws) {
    await this.socketGone(ws);
  }

  /** A socket that errored is gone like a closed one, so clients still learn the host left. */
  async webSocketError(ws) {
    try {
      ws.close(1011, "WebSocket error");
    } catch {}
    await this.socketGone(ws);
  }

  /** Deletes the room once its host has stayed away for [ROOM_TTL_MILLIS]. */
  async alarm() {
    if (this.hostSocket()) {
      await this.markHostSeen();
      return;
    }
    const lastHostAt = (await this.state.storage.get("lastHostAt")) ?? 0;
    const due = lastHostAt + ROOM_TTL_MILLIS;
    if (Date.now() >= due) await this.deleteRoom("Room expired");
    else await this.state.storage.setAlarm(due);
  }

  async socketGone(ws) {
    if (this.state.getTags(ws)[0] === "host" && !this.hostSocket(ws)) {
      await this.markHostSeen();
      this.broadcastToClients({ event: "host-offline" });
    }
  }

  async markHostSeen() {
    const now = Date.now();
    await this.state.storage.put("lastHostAt", now);
    await this.state.storage.setAlarm(now + ROOM_TTL_MILLIS);
  }

  async deleteRoom(reason) {
    for (const socket of this.state.getWebSockets()) {
      try {
        socket.close(4002, reason);
      } catch {}
    }
    await this.state.storage.deleteAlarm();
    await this.state.storage.deleteAll();
  }

  async matches(key, token) {
    const stored = await this.state.storage.get(key);
    return stored !== undefined && timingSafeEqual(stored, await sha256Hex(token));
  }

  openClients() {
    return this.state.getWebSockets("client").filter((socket) => socket.readyState === 1);
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
