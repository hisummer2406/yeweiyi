package protocol

// Values of the per-tank AttrTankShape client attribute. Language-neutral on purpose: unlike
// probeType (a localized display string such as "磁致伸缩" in the shared fixture), a client branches
// on this to pick which tank illustration to draw, so it must not change when the device's UI
// language does.
const (
	TankShapeHorizontal = "horizontal" // 卧罐 — device-side OilCanSet.gunType == 0
	TankShapeVertical   = "vertical"   // 立罐 — device-side OilCanSet.gunType == 1
)

// ParseTankShape reads one tank's shape out of a client-attributes map — the shape
// tbclient.Client.Attributes returns for CLIENT_SCOPE.
//
// ok=false means "unknown", and callers must treat it as such rather than as horizontal: the
// attribute is absent when the device has no storage-tank settings record for that tank, when it
// runs a build that predates the attribute, or when it has not reconnected since the user changed
// the setting. A null value (what ThingsBoard reports for a deleted attribute) and any value this
// build does not recognise are unknown too, so a shape added later does not break older clients.
// Unlike ParseTankCalibration there is no error case: a shape is only ever used to choose a picture.
//
// Not yet a confirmed part of V1 — see zhongchengyeweiyi#43 / yeweiyi-cloud#45.
func ParseTankShape(attrs map[string]any, tankIndex int) (string, bool) {
	s, _ := attrs[TankKey(tankIndex, AttrTankShape)].(string)
	switch s {
	case TankShapeHorizontal, TankShapeVertical:
		return s, true
	}
	return "", false
}
