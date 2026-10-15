/** Ticket requests can outlive a page, a foreground reconnect, or another attempt. */
export const createRealtimeConnectionGate = () => {
  let generation = 0;
  let pending = false;
  return {
    begin: (): number | undefined => {
      if (pending) return undefined;
      pending = true;
      return ++generation;
    },
    current: (attempt: number) => attempt === generation,
    settle: (attempt: number) => {
      if (attempt !== generation) return false;
      pending = false;
      return true;
    },
    invalidate: () => { generation++; pending = false; },
  };
};

/** Repeated online notifications retain metrics; a new Agent transport cannot. */
export const shouldResetRealtimeMetrics = (previousConnection: unknown, incomingConnection: unknown): boolean =>
  typeof incomingConnection === 'string' && incomingConnection.length > 0 && incomingConnection !== previousConnection;

/** Sequence is scoped to one browser socket, so a backend restart may start at 1. */
export const acceptRealtimeMetric = (message: { sequence?: unknown; reportedAt?: unknown; serverNow?: unknown }, previousSequence?: number,
  fallbackTime = Date.now()): { sequence?: number; reportedAt: number; sampledAt?: number } | undefined => {
  const sequence = Number(message.sequence);
  const validSequence = Number.isSafeInteger(sequence) && sequence > 0 ? sequence : undefined;
  if (validSequence !== undefined && previousSequence !== undefined && validSequence <= previousSequence) return undefined;
  const reportedAt = Number(message.reportedAt);
  const serverNow = Number(message.serverNow);
  const validSampleTime = Number.isFinite(reportedAt) && reportedAt > 0;
  const age = validSampleTime && Number.isFinite(serverNow) && serverNow > 0
    ? Math.max(0, serverNow - reportedAt) : 0;
  return {
    sequence: validSequence,
    // Freshness and charts use the browser clock; VPS clock skew must not make
    // an otherwise current report look expired. Rates use server sample deltas.
    reportedAt: fallbackTime - age,
    sampledAt: validSampleTime ? reportedAt : undefined,
  };
};

/** Reloaded configuration must not erase metrics already received for an online node. */
export const mergeRealtimeNodes = <T extends { id: number; status: number }, U extends {
  id: number; connectionStatus: 'online' | 'offline'; systemInfo?: unknown; connectionId?: string;
}>(incoming: T[], previous: U[], requestEventVersions?: ReadonlyMap<number, number>, currentEventVersions?: ReadonlyMap<number, number>) => {
  const known = new Map(previous.map(node => [node.id, node]));
  return incoming.map(node => {
    const old = known.get(node.id);
    const receivedDuringRequest = old !== undefined && currentEventVersions !== undefined
      && (currentEventVersions.get(node.id) || 0) > (requestEventVersions?.get(node.id) || 0);
    const online = receivedDuringRequest ? old!.connectionStatus === 'online' : node.status === 1;
    return {
      ...node,
      status: online ? 1 : 0,
      connectionStatus: (online ? 'online' : 'offline') as 'online' | 'offline',
      systemInfo: online && old?.connectionStatus === 'online' ? old.systemInfo : null,
      connectionId: old?.connectionId,
      copyLoading: false,
    };
  });
};
