import { t } from './i18n';
import { codeOfBody } from './loginFlow';
import { errorKey } from './errorKey';
import { ApiError } from './request';

// The text a user reads for a failed call, in the current language. Never the cloud's own wording.
export function describeError(err: unknown): string {
  if (err instanceof ApiError) return t(errorKey(err.status, codeOfBody(err.body)));
  return t('err.generic');
}
