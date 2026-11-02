/** Keep a submission identity until its result is known, including edits followed by undo. */
export function createForwardRequestCache(makeId = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
}) {
  const requests = new Map<string, string>();
  const key = (payload: object, scope: string) => JSON.stringify([scope, payload]);
  return {
    get(payload: object, scope = '') {
      const fingerprint = key(payload, scope);
      let id = requests.get(fingerprint);
      if (!id) { id = makeId(); requests.set(fingerprint, id); }
      return id;
    },
    acknowledge(payload: object, scope = '') { requests.delete(key(payload, scope)); },
    clear() { requests.clear(); },
  };
}
