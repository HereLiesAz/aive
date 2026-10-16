// Runs the relay against an in-memory fake of the Durable Object state: `npm test` (Node 20+).
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { test } from "node:test";

import { MAX_CLIENTS_PER_ROOM, ROOM_TTL_MILLIS, isStrongToken, routeFrame } from "../src/protocol.js";
import worker, { RemoteRoom } from "../src/worker.js";

// Node's Response rejects status 101, which the Workers runtime uses for upgrades.
globalThis.Response = class {
  constructor(body, init = {}) {
    this.body = body;
    this.status = init.status ?? 200;
    this.webSocket = init.webSocket;
  }
};

class FakeSocket {
  constructor() {
    this.readyState = 1;
    this.sent = [];
    this.closed = null;
  }
  send(text) {
    if (this.readyState !== 1) throw new Error("closed");
    this.sent.push(JSON.parse(text));
  }
  close(code, reason) {
    this.readyState = 3;
    this.closed = { code, reason };
  }
}

globalThis.WebSocketPair = class {
  constructor() {
    this[0] = new FakeSocket();
    this[1] = new FakeSocket();
  }
};

class FakeState {
  constructor() {
    this.data = new Map();
    this.alarmAt = null;
    this.sockets = [];
    const state = this;
    this.storage = {
      async get(key) {
        return state.data.get(key);
      },
      async put(key, value) {
        if (typeof key === "object") for (const [k, v] of Object.entries(key)) state.data.set(k, v);
        else state.data.set(key, value);
      },
      async deleteAll() {
        state.data.clear();
      },
      async setAlarm(at) {
        state.alarmAt = at;
      },
      async deleteAlarm() {
        state.alarmAt = null;
      },
    };
  }
  acceptWebSocket(socket, tags) {
    this.sockets.push({ socket, tags });
  }
  getWebSockets(tag) {
    return this.sockets.filter((entry) => tag === undefined || entry.tags.includes(tag)).map((entry) => entry.socket);
  }
  getTags(socket) {
    return this.sockets.find((entry) => entry.socket === socket).tags;
  }
}

const token = () => randomBytes(32).toString("base64url");

function request(method, token, { role, body } = {}) {
  const headers = new Map([["authorization", `Bearer ${token}`]]);
  if (method === "GET") headers.set("upgrade", "websocket");
  return {
    method,
    url: `https://relay.test/v1/rooms/room-abcdefghijklmnop${role ? `?role=${role}` : ""}`,
    headers: { get: (name) => headers.get(name.toLowerCase()) ?? null },
    json: async () => body,
  };
}

async function pairedRoom() {
  const state = new FakeState();
  const room = new RemoteRoom(state);
  const hostToken = token();
  const clientToken = token();
  const created = await room.fetch(request("POST", hostToken, { body: { clientToken } }));
  assert.equal(created.status, 201);
  return { state, room, hostToken, clientToken };
}

test("tokens must look like 32 random bytes", () => {
  assert.ok(isStrongToken(token()));
  assert.ok(!isStrongToken("a".repeat(43)));
  assert.ok(!isStrongToken("short"));
  assert.ok(!isStrongToken(undefined));
});

test("the worker rejects weak tokens before reaching a room", async () => {
  const response = await worker.fetch(request("GET", "a".repeat(64), { role: "host" }), {});
  assert.equal(response.status, 401);
});

test("only the host token creates and connects as host; only hashes are stored", async () => {
  const { state, room, hostToken, clientToken } = await pairedRoom();
  assert.ok(![...state.data.values()].includes(hostToken));
  assert.ok(![...state.data.values()].includes(clientToken));
  assert.equal((await room.fetch(request("GET", clientToken, { role: "host" }))).status, 401);
  assert.equal((await room.fetch(request("GET", hostToken, { role: "client" }))).status, 401);
  assert.equal((await room.fetch(request("GET", hostToken, { role: "host" }))).status, 101);
  assert.equal((await room.fetch(request("GET", clientToken, { role: "client" }))).status, 101);
  // Another "host" cannot take the room over.
  assert.equal((await room.fetch(request("POST", token(), { body: { clientToken: token() } }))).status, 403);
});

test("clients cannot create rooms that do not exist", async () => {
  const room = new RemoteRoom(new FakeState());
  assert.equal((await room.fetch(request("GET", token(), { role: "client" }))).status, 404);
});

test("host and client tokens must differ", async () => {
  const room = new RemoteRoom(new FakeState());
  const same = token();
  assert.equal((await room.fetch(request("POST", same, { body: { clientToken: same } }))).status, 400);
});

test("frames route between host and one client only", async () => {
  const { state, room, hostToken, clientToken } = await pairedRoom();
  const host = (await room.fetch(request("GET", hostToken, { role: "host" }))).webSocket;
  await room.fetch(request("GET", clientToken, { role: "client" }));
  await room.fetch(request("GET", clientToken, { role: "client" }));
  const [first, second] = state.getWebSockets("client");
  const firstId = state.getTags(first)[1];
  await room.webSocketMessage(first, JSON.stringify({ data: "sealed" }));
  const hostServer = state.getWebSockets("host")[0];
  await room.webSocketMessage(hostServer, JSON.stringify({ to: firstId, data: "reply" }));
  await room.webSocketMessage(hostServer, JSON.stringify({ to: "client", data: "broadcast attempt" }));
  assert.deepEqual(first.sent.at(-1), { data: "reply" });
  assert.ok(!second.sent.some((frame) => frame.data));
  assert.ok(host); // the peer end handed to the caller
  assert.deepEqual(routeFrame(["client", firstId], JSON.stringify({ data: "x" })), {
    to: "host",
    frame: { from: firstId, data: "x" },
  });
});

test("rooms cap their clients", async () => {
  const { room, clientToken } = await pairedRoom();
  for (let i = 0; i < MAX_CLIENTS_PER_ROOM; i++) {
    assert.equal((await room.fetch(request("GET", clientToken, { role: "client" }))).status, 101);
  }
  assert.equal((await room.fetch(request("GET", clientToken, { role: "client" }))).status, 429);
});

test("a host socket error tells clients the host is offline", async () => {
  const { state, room, hostToken, clientToken } = await pairedRoom();
  await room.fetch(request("GET", hostToken, { role: "host" }));
  await room.fetch(request("GET", clientToken, { role: "client" }));
  const hostServer = state.getWebSockets("host")[0];
  await room.webSocketError(hostServer, new Error("reset"));
  assert.deepEqual(state.getWebSockets("client")[0].sent.at(-1), { event: "host-offline" });
});

test("the alarm deletes a room whose host stayed away for the TTL", async () => {
  const { state, room } = await pairedRoom();
  await room.alarm();
  assert.ok(state.data.has("hostTokenHash"), "kept before the TTL");
  state.data.set("lastHostAt", Date.now() - ROOM_TTL_MILLIS - 1);
  await room.alarm();
  assert.equal(state.data.size, 0);
  assert.equal(state.alarmAt, null);
});

test("rotating the client token disconnects old clients", async () => {
  const { state, room, hostToken, clientToken } = await pairedRoom();
  await room.fetch(request("GET", clientToken, { role: "client" }));
  const newClientToken = token();
  assert.equal((await room.fetch(request("POST", hostToken, { body: { clientToken: newClientToken } }))).status, 204);
  assert.equal(state.getWebSockets("client")[0].closed.code, 4001);
  assert.equal((await room.fetch(request("GET", clientToken, { role: "client" }))).status, 401);
});
