// State diffing: previous known photos vs. current album contents.
export function diffAlbum(previous, current) {
  const prev = new Map(previous.map((p) => [p.id, p]));
  const cur = new Map(current.map((p) => [p.id, p]));
  return {
    added: current.filter((p) => !prev.has(p.id)),
    removed: previous.filter((p) => !cur.has(p.id)),
    unchanged: current.filter((p) => prev.has(p.id)),
  };
}
