// Called when the phone's network comes back (from none, or when it switches and is usable again).
// Returns the function that stops listening: call it when the page goes away, or the callback of a
// destroyed page keeps firing.
export function onNetworkBack(callback: () => void): () => void {
  const listener: WechatMiniprogram.OnNetworkStatusChangeCallback = (res) => {
    if (res.isConnected) callback();
  };
  try {
    wx.onNetworkStatusChange(listener);
  } catch {
    return () => {};
  }
  return () => {
    try {
      wx.offNetworkStatusChange(listener as unknown as WechatMiniprogram.OffNetworkStatusChangeCallback);
    } catch {
      // nothing to remove
    }
  };
}
