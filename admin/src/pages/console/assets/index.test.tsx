import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

let canPlatformAdmin = false;
const navigated: string[] = [];

vi.mock('@umijs/max', () => ({
  useAccess: () => ({ canPlatformAdmin }),
  Navigate: ({ to }: { to: string }) => {
    navigated.push(to);
    return null;
  },
}));

const { default: AssetsIndex } = await import('./index');

afterEach(() => {
  cleanup();
  navigated.length = 0;
});

describe('AssetsIndex', () => {
  it('sends the platform admin to the customers page', () => {
    canPlatformAdmin = true;
    render(<AssetsIndex />);
    expect(navigated).toEqual(['/console/assets/customers']);
  });

  it('sends the customer admin to the stations page (no customers item)', () => {
    canPlatformAdmin = false;
    render(<AssetsIndex />);
    expect(navigated).toEqual(['/console/assets/stations']);
  });
});
