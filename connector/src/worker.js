/**
 * The Aive connector: a remote MCP server for Claude's connectors directory, in front of one
 * paired Aive device reached through the existing relay (docs/Connector.md).
 */
import { SUPPORTED_VERSIONS, handleMessage } from "./mcp.js";
import { DeviceError, converse } from "./device.js";
import {
  authorizationServerMetadata,
  authorizeStart,
  authorizeSubmit,
  baseUrl,
  googleCallback,
  mcpLimited,
  pairingForToken,
  protectedResourceMetadata,
  purgeAccount,
  register,
  resourceUrl,
  revoke,
  token,
  withCors,
} from "./oauth.js";
import { allowedRelayOrigin } from "./seal.js";
import { errorResult } from "./tools.js";
import { homePage } from "./pages.js";
import { bearerOf, json } from "./worker-helpers.js";

export { Account, Limiter, Registry, Txn } from "./store.js";

const MAX_MCP_BODY = 64 * 1024;
const ORIGINS = ["https://claude.ai", "https://claude.com"];

const originAllowed = (origin) => {
  if (!origin) return true;
  if (ORIGINS.includes(origin)) return true;
  try {
    const u = new URL(origin);
    return u.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(u.hostname);
  } catch {
    return false;
  }
};

function unauthorized(env, bad) {
  const challenge = `Bearer realm="aive", resource_metadata="${baseUrl(env)}/.well-known/oauth-protected-resource"${bad ? ', error="invalid_token"' : ""}`;
  return json({ error: bad ? "invalid_token" : "unauthorized" }, 401, { "www-authenticate": challenge });
}

async function mcp(request, env, deps) {
  if (request.method !== "POST") return new Response(null, { status: 405, headers: { allow: "POST" } });
  if (!originAllowed(request.headers.get("origin"))) return json({ error: "forbidden_origin" }, 403);
  const bearer = bearerOf(request);
  if (!bearer) return unauthorized(env, false);
  const auth = await pairingForToken(env, bearer);
  if (!auth) return unauthorized(env, true);

  const headerVersion = request.headers.get("mcp-protocol-version");
  if (headerVersion && !SUPPORTED_VERSIONS.includes(headerVersion)) return json({ error: "unsupported_protocol_version", supported: SUPPORTED_VERSIONS }, 400);
  const text = await request.text();
  let message;
  try {
    message = text.length <= MAX_MCP_BODY ? JSON.parse(text) : undefined;
  } catch {
    message = undefined;
  }
  if (message === undefined) return json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error." } }, 400);

  const { pairing, acct } = auth;
  const origin = allowedRelayOrigin(pairing.relay, [new URL(env.RELAY_URL).origin, ...String(env.EXTRA_RELAYS || "").split(",").map((s) => s.trim()).filter(Boolean)]);
  const ctx = {
    version: headerVersion || "2025-03-26",
    talk: (requests, opts) => {
      if (!origin) throw new DeviceError("relay_unreachable", "The relay is not allowed.");
      return converse({ ...pairing, relay: origin }, requests, { fetchImpl: deps.fetch, now: deps.now, ...opts });
    },
    onDeviceError: async (e) => {
      if (e.code === "pairing_revoked") await purgeAccount(env, acct);
    },
  };
  if (message && message.method === "tools/call" && (await mcpLimited(env, acct))) {
    return json({ jsonrpc: "2.0", id: message.id ?? null, result: errorResult("rate_limited", "Too many requests in a short time. Wait a moment and try again.") });
  }
  const reply = await handleMessage(message, ctx);
  return reply ? json(reply) : new Response(null, { status: 202 });
}

/** Builds the Worker. [deps] lets tests supply fetch and the clock; production uses the globals. */
export function createWorker(overrides = {}) {
  const deps = { fetch: (...a) => fetch(...a), now: () => Date.now(), ...overrides };
  return {
    async fetch(request, env) {
      const url = new URL(request.url);
      const path = url.pathname;
      if (request.method === "OPTIONS" && path !== "/mcp") return withCors(new Response(null, { status: 204 }));
      try {
        switch (path) {
          case "/":
            return homePage();
          case "/mcp":
            return await mcp(request, env, deps);
          case "/.well-known/oauth-protected-resource":
          case "/.well-known/oauth-protected-resource/mcp":
            return withCors(protectedResourceMetadata(env));
          case "/.well-known/oauth-authorization-server":
            return withCors(authorizationServerMetadata(env));
          case "/register":
            return request.method === "POST" ? withCors(await register(request, env)) : new Response(null, { status: 405, headers: { allow: "POST" } });
          case "/authorize":
            if (request.method === "GET") return await authorizeStart(request, env);
            if (request.method === "POST") return await authorizeSubmit(request, env, deps);
            return new Response(null, { status: 405, headers: { allow: "GET, POST" } });
          case "/google/callback":
            return request.method === "GET" ? await googleCallback(request, env, deps) : new Response(null, { status: 405, headers: { allow: "GET" } });
          case "/token":
            return request.method === "POST" ? withCors(await token(request, env)) : new Response(null, { status: 405, headers: { allow: "POST" } });
          case "/revoke":
            return request.method === "POST" ? withCors(await revoke(request, env)) : new Response(null, { status: 405, headers: { allow: "POST" } });
          default:
            return new Response("Not found", { status: 404 });
        }
      } catch {
        // Never echo internals; nothing sensitive is logged either.
        return json({ error: "server_error" }, 500);
      }
    },
  };
}

export { resourceUrl };
export default createWorker();
