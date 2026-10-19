const CHARACTER_GROUPS = [
  'ABCDEFGHJKLMNPQRSTUVWXYZ',
  'abcdefghijkmnopqrstuvwxyz',
  '23456789',
  '!@#$%&*+-=?',
] as const;

function randomIndex(length: number): number {
  const sample = new Uint32Array(1);
  const ceiling = Math.floor(0x100000000 / length) * length;
  do {
    globalThis.crypto.getRandomValues(sample);
  } while (sample[0] >= ceiling);
  return sample[0] % length;
}

/** Generate in memory with Web Crypto; always include all four character classes. */
export function generateUserPassword(): string {
  const alphabet = CHARACTER_GROUPS.join('');
  const characters = CHARACTER_GROUPS.map(group => group[randomIndex(group.length)]);
  while (characters.length < 20) characters.push(alphabet[randomIndex(alphabet.length)]);
  for (let index = characters.length - 1; index > 0; index--) {
    const other = randomIndex(index + 1);
    [characters[index], characters[other]] = [characters[other], characters[index]];
  }
  return characters.join('');
}
