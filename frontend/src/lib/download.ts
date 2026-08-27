
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
