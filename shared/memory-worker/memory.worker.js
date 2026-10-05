// The Aive's browser memory database: SQLDelight's web-worker protocol over the official SQLite
// WebAssembly build, persisted in the Origin Private File System through the "opfs-sahpool" VFS.
// That VFS needs no COOP/COEP headers, so it works on static hosts such as GitHub Pages.
//
// Protocol (app.cash.sqldelight web-worker-driver):
//   request  { id, action: "exec" | "begin_transaction" | "end_transaction" | "rollback_transaction", sql?, params? }
//   response { id, results: { values: [[...], ...] } }  or  { id, error }
//
// There is deliberately no in-memory fallback here: a database that forgets on reload must not pass
// for persistence. Every request fails instead; an error starting with OPFS_UNAVAILABLE means the
// browser has no OPFS (the app keeps its Settings store), anything else (typically another tab
// holding the database) leaves the app with session-only memory.
import sqlite3InitModule from "@sqlite.org/sqlite-wasm";

// Each project's memory bank is its own worker, named "bank-<stem>", with its own OPFS pool and
// database (separate pools also mean separate locks). An unnamed worker opens the old shared
// database, which is read once to split it into banks and otherwise left untouched as the backup.
const BANK = self.name && self.name.startsWith("bank-") ? self.name.slice("bank-".length) : null;
const DATABASE_PATH = BANK ? `/aive-memory-${BANK}.db` : "/aive-memory.db";
const POOL_NAME = BANK ? `aive-memory-${BANK}` : "aive-memory";

const OPFS_UNAVAILABLE = "OPFS_UNAVAILABLE";

const ready = (async () => {
  if (!(self.navigator && self.navigator.storage && self.navigator.storage.getDirectory)) {
    throw new Error(`${OPFS_UNAVAILABLE}: this browser has no Origin Private File System`);
  }
  const sqlite3 = await sqlite3InitModule();
  const pool = await sqlite3.installOpfsSAHPoolVfs({ name: POOL_NAME });
  return new pool.OpfsSAHPoolDb(DATABASE_PATH);
})();

function run(db, sql, params) {
  const values = db.exec({
    sql,
    bind: params && params.length ? params : undefined,
    rowMode: "array",
    returnValue: "resultRows",
  });
  return { values };
}

function handle(db, data) {
  switch (data && data.action) {
    case "exec":
      if (!data.sql) throw new Error("exec: missing query string");
      return run(db, data.sql, data.params);
    case "begin_transaction":
      return run(db, "BEGIN TRANSACTION;");
    case "end_transaction":
      return run(db, "END TRANSACTION;");
    case "rollback_transaction":
      return run(db, "ROLLBACK TRANSACTION;");
    default:
      throw new Error(`Unsupported action: ${data && data.action}`);
  }
}

self.onmessage = (event) => {
  const data = event.data;
  ready
    .then((db) => postMessage({ id: data.id, results: handle(db, data) }))
    .catch((error) => postMessage({ id: data.id, error: String((error && error.message) || error) }));
};
