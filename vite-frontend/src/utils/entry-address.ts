export type EntryAddressMode = 'NONE' | 'DEFAULT' | 'CUSTOM' | 'UNASSIGNED';

export interface AssignedEntryAddress {
  entryAddressMode?: EntryAddressMode | null;
  entryDomain?: string | null;
  inIp?: string | null;
  ip?: string | null;
}

export const UNASSIGNED_ENTRY_ADDRESS = '入口地址未分配';

/**
 * Render only the address resolved for this resource's owner. In particular,
 * a missing domain assignment must not fall back to an infrastructure address
 * or to the current viewer's assignment on another API response.
 * Backend responses must independently enforce the same tenant boundary.
 */
export function assignedEntryAddress(
  source: AssignedEntryAddress,
  canManageInfrastructure = false,
): string {
  if (source.entryAddressMode === 'UNASSIGNED') return '';
  const domain = source.entryDomain?.trim();
  if (domain) return domain;
  if (source.entryAddressMode === 'CUSTOM' || source.entryAddressMode === 'DEFAULT') return '';
  if (source.entryAddressMode === 'NONE' || canManageInfrastructure) {
    return source.inIp?.trim() || source.ip?.trim() || '';
  }
  return '';
}
