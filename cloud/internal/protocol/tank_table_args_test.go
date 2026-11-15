package protocol_test

import (
	"encoding/json"
	"errors"
	"testing"

	"yeweiyi-cloud/internal/protocol"
)

func TestValidateArgs_TankTable(t *testing.T) {
	m := protocol.MethodConfigTankTable
	for _, ok := range []string{
		`{"tank":1,"shape":"horizontal","diameterMm":2600,"lengthMm":8000,"headMm":600}`,
		`{"tank":16,"shape":"horizontal","diameterMm":1200,"lengthMm":3000,"headMm":0}`,
		`{"tank":2,"shape":"vertical","heightMm":2500,"diameterMm":2000.5}`,
		`{"tank":3,"shape":"cuboid","heightMm":1500,"lengthMm":3000,"widthMm":20000}`,
	} {
		if err := protocol.ValidateArgs(m, json.RawMessage(ok)); err != nil {
			t.Errorf("%s: unexpected %v", ok, err)
		}
	}
	cases := []struct{ args, field, code string }{
		{`[1]`, "args", protocol.ArgInvalidJSON},
		{`{"shape":"vertical","heightMm":2500,"diameterMm":2000}`, "tank", protocol.ArgMissingTank},
		{`{"tank":17,"shape":"vertical","heightMm":2500,"diameterMm":2000}`, "tank", protocol.ArgMissingTank},
		{`{"tank":1,"shape":"sphere","heightMm":2500}`, "shape", protocol.ArgInvalidValue},
		{`{"tank":1,"heightMm":2500}`, "shape", protocol.ArgInvalidValue},
		{`{"tank":1,"shape":"vertical","heightMm":2500}`, "diameterMm", protocol.ArgInvalidValue},
		{`{"tank":1,"shape":"vertical","heightMm":"2500","diameterMm":2000}`, "heightMm", protocol.ArgInvalidValue},
		{`{"tank":1,"shape":"vertical","heightMm":99,"diameterMm":2000}`, "heightMm", protocol.ArgInvalidValue},
		{`{"tank":1,"shape":"cuboid","heightMm":1500,"lengthMm":20001,"widthMm":2000}`, "lengthMm", protocol.ArgInvalidValue},
		{`{"tank":1,"shape":"horizontal","diameterMm":2600,"lengthMm":8000,"headMm":-1}`, "headMm", protocol.ArgInvalidValue},
		{`{"tank":1,"shape":"vertical","heightMm":2500,"diameterMm":2000,"lengthMm":1}`, "lengthMm", protocol.ArgUnknownField},
	}
	for _, c := range cases {
		err := protocol.ValidateArgs(m, json.RawMessage(c.args))
		var ae *protocol.ArgsError
		if !errors.As(err, &ae) {
			t.Fatalf("%s: want ArgsError, got %v", c.args, err)
		}
		found := false
		for _, f := range ae.Fields {
			found = found || (f.Field == c.field && f.Code == c.code)
		}
		if !found {
			t.Errorf("%s: want %s/%s in %+v", c.args, c.field, c.code, ae.Fields)
		}
	}
}

func TestTankTableMethodIsAdminConfig(t *testing.T) {
	spec, ok := protocol.Methods[protocol.MethodConfigTankTable]
	if !ok || spec.Permission != protocol.PermAdmin || spec.Physical {
		t.Fatalf("config.setTankTable spec = %+v, %v", spec, ok)
	}
}
