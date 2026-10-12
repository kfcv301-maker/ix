package socket

import (
	"os"

	"github.com/go-gost/x/config"
)

func saveConfig() error {

	file := "gost.json"

	f, err := os.CreateTemp(".", ".gost-config-*")
	if err != nil {
		return err
	}
	defer f.Close()
	defer os.Remove(f.Name())

	if err := config.Global().Write(f, "json"); err != nil {

		return err
	}
	if err := f.Sync(); err != nil {
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Rename(f.Name(), file)
}
