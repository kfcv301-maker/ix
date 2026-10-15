import { useEffect, useState } from 'react';
import { diagnoseTunnelForwards, getTunnelForwardDiagnosisTask, cancelTunnelForwardDiagnosisTask } from '@/api';
import { createTunnelDiagnosisController, emptyDiagnosisState } from '@/utils/tunnel-diagnosis';

export function useTunnelDiagnosis() {
  const [state, setState] = useState(emptyDiagnosisState);
  const [controller] = useState(() => createTunnelDiagnosisController({
    start: diagnoseTunnelForwards, get: getTunnelForwardDiagnosisTask, cancel: cancelTunnelForwardDiagnosisTask,
  }, setState));
  useEffect(() => () => controller.close(false), [controller]);
  return { ...state, start: controller.start, cancel: controller.cancel, close: controller.close };
}
