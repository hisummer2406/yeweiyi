package protocol

// The device's fixed 20-colour palette (ColorFragment.yanseId 1–20), as 0xRRGGBB. Shared with the device,
// admin and miniapp through fixtures/protocol/v1/oil_color_palette.json; config.setTankSettings only writes
// these, while a reported oilColor may be anything the device holds (its old free picker is disabled but
// old values remain).
var OilColorPalette = []int32{
	0xFF0000, 0xFFFF00, 0x0000FF, 0x008000, 0x800080, 0x254D2A, 0x6F1200, 0x000034, 0xF4B2D6, 0x4C0B71,
	0x00456C, 0x00ADEF, 0x342F93, 0xF4F08D, 0xF1645D, 0xFF00CD, 0x413100, 0x139300, 0xC1CD23, 0xFF9900,
}

const maxRGB = 0xFFFFFF

// IsPaletteColor reports whether v is one of the palette colours.
func IsPaletteColor(v int32) bool {
	for _, c := range OilColorPalette {
		if c == v {
			return true
		}
	}
	return false
}
