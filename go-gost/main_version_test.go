package main

import (
	"io"
	"os"
	"os/exec"
	"strings"
	"testing"
)

func TestPanelAgentVersionSubprocessSkipsPackageConfiguration(t *testing.T) {
	if os.Getenv("LUNARIS_TEST_VERSION_PROCESS") == "1" {
		main()
		return
	}
	dir := t.TempDir()
	// Invalid private JSON would produce a package-init configuration error if
	// the read-only version path accidentally touched the node configuration.
	if err := os.WriteFile(dir+string(os.PathSeparator)+"config.json", []byte("invalid private configuration"), 0600); err != nil {
		t.Fatal(err)
	}
	command := exec.Command(os.Args[0], "-test.run=^TestPanelAgentVersionSubprocessSkipsPackageConfiguration$", "--panel-agent-version")
	command.Env = append(os.Environ(), "LUNARIS_TEST_VERSION_PROCESS=1")
	command.Dir = dir
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("version subprocess failed: %v", err)
	}
	if !strings.Contains(string(output), panelAgentVersion) || strings.Contains(string(output), "protocol block config") || strings.Contains(string(output), "invalid private") {
		t.Fatalf("version path touched configuration: %q", output)
	}
}

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
