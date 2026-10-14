/** Fields consumed by the authenticated price and order streams. */
export function parseSseFrame(frame: string): { event: string; data: string } | null {
  let event = 'message';
  const data: string[] = [];
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      data.push(line.slice(5).trimStart());
    } else {
      // Comments, heartbeat lines, and unused SSE fields carry no payload here.
    }
  }
  return data.length ? { event, data: data.join('\n') } : null;
}
