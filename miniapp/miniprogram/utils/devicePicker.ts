// wx.showActionSheet accepts at most 6 items and fails (silently, without a fail handler) beyond that.
// A customer admin sees every device of their customer and a platform admin sees all of them, so a
// device list can easily be longer. This pages a long list through the sheet: up to 5 devices plus a
// "more…" row (its text is passed in, in the current language) that opens the next page.
export const ACTION_SHEET_MAX = 6;

export interface DevicePage {
  // What to hand to wx.showActionSheet: the devices on this page, plus the "more" row if there are more.
  items: string[];
  // The device behind items[i], for i < devices.length. Anything at index >= devices.length is "more".
  devices: string[];
  hasMore: boolean;
  // Pass this as `offset` to devicePage() to get the page after this one.
  nextOffset: number;
}

export function devicePage(names: string[], offset: number, moreLabel = '…'): DevicePage {
  const remaining = names.slice(offset);
  if (remaining.length <= ACTION_SHEET_MAX) {
    return { items: remaining, devices: remaining, hasMore: false, nextOffset: names.length };
  }
  const devices = remaining.slice(0, ACTION_SHEET_MAX - 1);
  return {
    items: [...devices, moreLabel],
    devices,
    hasMore: true,
    nextOffset: offset + devices.length,
  };
}
