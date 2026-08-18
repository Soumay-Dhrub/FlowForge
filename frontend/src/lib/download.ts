/**
 * Handing a fetched payload to the browser as a file.
 *
 * The export endpoints are authenticated, so a plain `<a href>` or `window.open` would issue a
 * request with no `Authorization` header and be answered 401. The bytes therefore come back through
 * the shared Axios instance as a blob, and this turns that blob into a download.
 */

/** Streams an already-fetched blob to disk under `filename`. */
export function triggerBrowserDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  // Firefox only honours a programmatic click on a node that is in the document.
  document.body.appendChild(link);
  link.click();
  link.remove();
  // The object URL pins the blob in memory until it is revoked; the click has already read it.
  URL.revokeObjectURL(url);
}

/** Serialises a value as pretty-printed JSON and downloads it under `filename`. */
export function downloadAsJson(value: unknown, filename: string): void {
  const blob = new Blob([JSON.stringify(value, null, 2)], { type: "application/json" });
  triggerBrowserDownload(blob, filename);
}
