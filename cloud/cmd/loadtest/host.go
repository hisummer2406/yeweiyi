package main

import (
	"fmt"
	"runtime"
)

func osName() string { return runtime.GOOS + "/" + runtime.GOARCH }
func numCPU() int    { return runtime.NumCPU() }

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

var _ = fmt.Sprintf
