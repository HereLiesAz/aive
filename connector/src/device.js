/**
 * One short conversation with the paired device through the relay: open a WebSocket as
 * role=client, seal the requests, unseal the replies, stop at the answer to a final ping (so a
 * request the device never answers does not wait for the timeout), and close. Frame sizes follow
 * docs/Remote-API.md: requests here are a few hundred bytes, replies over the relay's limit are
 * dropped by [openFrame].
 */
import { ClientSession } from "./seal.js";

export const TIMEOUT_MS = 20_000;

export class DeviceError extends Error {
  /** @param code one of device_offline, pairing_revoked, relay_unreachable, relay_busy, timeout */
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

/**
 * @param pairing {relay, room, clientToken, key}
 * @param requests host requests without `id`; ids 1..n are assigned, then a ping gets n+1
 * @param opts {fetchImpl, timeoutMs, graceMs, now}; graceMs waits that long after the pong for
 *        messages the device sends asynchronously (run events)
 * @returns the unsealed host messages, in arrival order, ending with the pong
 */
export async function converse(pairing, requests, { fetchImpl = fetch, timeoutMs = TIMEOUT_MS, graceMs = 0, now = Date.now } = {}) {
  const url = `${pairing.relay}/v1/rooms/${pairing.room}?role=client`;
  let response;
  try {
    response = await fetchImpl(url, { headers: { Upgrade: "websocket", Authorization: `Bearer ${pairing.clientToken}` } });
  } catch {
    throw new DeviceError("relay_unreachable", "The relay could not be reached.");
  }
  if (response.status === 404 || response.status === 401 || response.status === 403) {
    throw new DeviceError("pairing_revoked", "This pairing no longer exists on the relay.");
  }
  if (response.status === 429) throw new DeviceError("relay_busy", "The relay is busy with other connections to this device.");
  const ws = response.webSocket;
  if (response.status !== 101 || !ws) throw new DeviceError("relay_unreachable", "The relay did not accept the connection.");
  ws.accept();

  const session = new ClientSession({ room: pairing.room, key: pairing.key, now });
  const pingId = requests.length + 1;
  const messages = [];
  let hostOnline = null;
  let gotPong = false;
  let settled = false;
  let chain = Promise.resolve();

  return new Promise((resolve, reject) => {
    let timer;
    let graceTimer;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      clearTimeout(graceTimer);
      try {
        ws.close(1000, "done");
      } catch {
        // already closed
      }
      if (error) reject(error);
      else resolve(messages);
    };
    timer = setTimeout(
      () =>
        finish(
          hostOnline === false || hostOnline === null
            ? new DeviceError("device_offline", "The device did not answer. It is probably offline.")
            : new DeviceError("timeout", "The device did not answer within 20 seconds."),
        ),
      timeoutMs,
    );

    const handle = async (raw) => {
      let frame;
      try {
        frame = JSON.parse(typeof raw === "string" ? raw : "");
      } catch {
        return;
      }
      if (!frame || typeof frame !== "object") return;
      if (frame.event === "host-online") hostOnline = true;
      else if (frame.event === "host-offline") {
        hostOnline = false;
        if (!gotPong) finish(new DeviceError("device_offline", "The Aive device is offline."));
        return;
      }
      if (typeof frame.data !== "string") return;
      const message = await session.unseal(frame.data);
      if (!message || settled) return;
      messages.push(message);
      if (message.t === "pong" && message.id === pingId) {
        gotPong = true;
        if (graceMs > 0) graceTimer = setTimeout(() => finish(), graceMs);
        else finish();
      }
    };

    ws.addEventListener("message", (event) => {
      chain = chain.then(() => handle(event.data)).catch(() => {});
    });
    ws.addEventListener("close", (event) => {
      if (settled) return;
      const code = event && event.code;
      if (code === 4001 || code === 4002) finish(new DeviceError("pairing_revoked", "The pairing was reset or deleted on the device."));
      else if (gotPong) finish();
      else finish(new DeviceError("relay_unreachable", "The relay closed the connection."));
    });
    ws.addEventListener("error", () => finish(new DeviceError("relay_unreachable", "The relay connection failed.")));

    (async () => {
      try {
        const all = [...requests.map((r, i) => ({ ...r, id: i + 1 })), { t: "ping", id: pingId }];
        for (const message of all) ws.send(JSON.stringify({ data: await session.seal(message) }));
      } catch {
        finish(new DeviceError("relay_unreachable", "Could not send to the relay."));
      }
    })();
  });
}
