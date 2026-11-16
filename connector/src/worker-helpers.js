import { json } from "./util.js";

export { json };

/** The bearer token of [request], or "". */
export function bearerOf(request) {
  const match = (request.headers.get("authorization") || "").match(/^Bearer\s+(\S+)$/i);
  return match ? match[1] : "";
}
