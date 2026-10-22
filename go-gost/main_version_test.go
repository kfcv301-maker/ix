package main

import (
	"io"
	"os"
	"strings"
	"testing"
)

func TestPanelAgentVersionDoesNotLoadPrivateConfiguration(t *testing.T) {
	t.Chdir(t.TempDir())
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	previousOutput, previousFlag := os.Stdout, printPanelAgentVersion
	os.Stdout, printPanelAgentVersion = writer, true
	defer func() {
		os.Stdout, printPanelAgentVersion = previousOutput, previousFlag
		reader.Close()
		writer.Close()
	}()
	// No config.json exists. A startup/configuration attempt would terminate the
	// subprocess/test; the version branch must return before creating reporters.
	main()
	writer.Close()
	output, err := io.ReadAll(reader)
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(string(output)) != panelAgentVersion {
		t.Fatalf("unexpected public version output: %q", output)
	}
}
