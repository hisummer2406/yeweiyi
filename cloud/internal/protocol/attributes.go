package protocol

// ThingsBoard's own device-side attribute topics.
const (
	AttributesTopic            = "v1/devices/me/attributes"           // publish client attrs; subscribe for shared attr updates
	AttributesRequestTopicPfx  = "v1/devices/me/attributes/request/"  // + requestId
	AttributesResponseTopicPfx = "v1/devices/me/attributes/response/" // + requestId
)

// Client attributes — device-owned, reported by the device on connect and on change. These are the
// values that describe *what the device is*, as opposed to what it is currently measuring. Anything
// that changes on the snapshot cadence belongs in telemetry instead; anything that changes only when
// somebody reconfigures the device belongs here, so it is not re-stored 2,880 times a day.
const (
	AttrProtocolVersion  = "protocolVersion"  // int, == Version
	AttrDeviceModel      = "deviceModel"      // string, 设备型号
	AttrHardwareVersion  = "hardwareVersion"  // string, 硬件版本
	AttrTankCount        = "tankCount"        // int, 油罐数量
	AttrLocalControlMode = "localControlMode" // bool, true = 现场手动模式，远程控制会被拒绝
)

// Per-tank client attributes, addressed with TankKey — e.g. TankKey(1, AttrProbeAddress).
const (
	AttrProbeAddress = "probeAddress" // string, 探针地址
	AttrProbeType    = "probeType"    // string, 探针类型
	AttrCapacityL    = "capacityL"    // double, 油罐公称容积, litres

	// AttrCalibrationHeightMm/AttrCalibrationVolumeL are the device's persisted height→volume
	// calibration curve (paired arrays, same length, ascending by height) — see ParseTankCalibration
	// in calibration.go for the parser and doc comment on what this is and isn't.
	//
	// **Not yet a confirmed/frozen part of V1** — zhongchengyeweiyi#42 待办1 /
	// hisummer2406/yeweiyi-cloud#14. Declared here so the shape can be parsed and tested ahead of
	// that decision, matching the pattern already used for stockEvent's omitempty `source` field
	// (see stock.go).
	AttrCalibrationHeightMm = "calibrationHeightMm" // []float64 (mm)
	AttrCalibrationVolumeL  = "calibrationVolumeL"  // []float64 (L), same length/order as the height array
)

// Shared attributes — cloud-owned, pushed down to the device by ThingsBoard. The device subscribes
// to AttributesTopic to receive updates and MUST apply them without needing a restart.
const (
	SharedReportIntervalSec = "reportIntervalSec" // int, overrides SnapshotIntervalSec
	SharedAlarmThresholds   = "alarmThresholds"   // JSON, per-tank thresholds; authoritative copy lives in the business DB
)

// OTA attribute keys. These are ThingsBoard's *reserved* names for its own OTA feature, not names
// this protocol gets to choose — they are listed here so nothing in V1 accidentally redefines them,
// and so the Android side knows exactly which keys its OTA client must read and write.
//
// Direction: ThingsBoard writes the Shared* keys when a firmware package is assigned to the device;
// the device reads them, downloads, verifies the checksum, flashes, and reports progress by writing
// the Client* keys back.
//
// Scope caveat carried over from CLAUDE.md: ThingsBoard's OTA feature is the distribution half only.
// The device-side download/verify/flash client does not exist in ~/Developer/zhongchengyeweiyi today,
// and adopting ThingsBoard does not create it.
const (
	SharedFwTitle             = "fw_title"
	SharedFwVersion           = "fw_version"
	SharedFwSize              = "fw_size"
	SharedFwChecksum          = "fw_checksum"
	SharedFwChecksumAlgorithm = "fw_checksum_algorithm"

	ClientCurrentFwTitle   = "current_fw_title"
	ClientCurrentFwVersion = "current_fw_version"
	ClientFwState          = "fw_state" // DOWNLOADING/DOWNLOADED/VERIFIED/UPDATING/UPDATED/FAILED
)
