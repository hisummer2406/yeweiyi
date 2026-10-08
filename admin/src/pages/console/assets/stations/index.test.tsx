import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('./StationsTab', () => ({
  default: () => <div>stations-stub</div>,
}));

const { default: ConsoleStations } = await import('./index');

afterEach(cleanup);

describe('ConsoleStations', () => {
  it('renders the station list directly, without a device-assignment tab', () => {
    render(<ConsoleStations />);
    expect(screen.getByText('stations-stub')).toBeTruthy();
    expect(screen.queryByRole('tab')).toBeNull();
  });
});
