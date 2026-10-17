package socket

import (
	"io"
	"net"
	"strings"
	"testing"

	corelogger "github.com/go-gost/core/logger"
	"github.com/go-gost/x/config"
	parser "github.com/go-gost/x/config/parsing/service"
	_ "github.com/go-gost/x/handler/forward/local"
	_ "github.com/go-gost/x/listener/tcp"
	_ "github.com/go-gost/x/listener/udp"
	xlogger "github.com/go-gost/x/logger"
	"github.com/go-gost/x/registry"
)

func init() { corelogger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard))) }

func serviceTestConfig(name, addr, network string) config.ServiceConfig {
	return config.ServiceConfig{Name: name, Addr: addr, Listener: &config.ListenerConfig{Type: network}, Handler: &config.HandlerConfig{Type: network}}
}

func isolatedServiceConfig(t *testing.T) {
	t.Helper()
	previous := config.Global()
	config.Set(&config.Config{})
	t.Cleanup(func() {
		for _, cfg := range config.Global().Services {
			if svc := registry.ServiceRegistry().Get(cfg.Name); svc != nil {
				registry.ServiceRegistry().Unregister(cfg.Name)
				svc.Close()
			}
		}
		config.Set(previous)
	})
}

func assertTCPPortFree(t *testing.T, addr string) {
	t.Helper()
	probe, err := net.Listen("tcp4", addr)
	if err != nil {
		t.Fatalf("failed command retained TCP port %s: %v", addr, err)
	}
	probe.Close()
}

func unusedTCPAddress(t *testing.T) string {
	t.Helper()
	probe, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := probe.Addr().String()
	probe.Close()
	return addr
}

// UDP's ephemeral allocator does not account for TCP listeners or TIME_WAIT
// sockets. Reserve TCP first so the failed batch genuinely creates its TCP
// member before reaching the deliberately occupied UDP member.
func blockUDPOnFreeTCPPort(t *testing.T) net.PacketConn {
	t.Helper()
	for attempt := 0; attempt < 50; attempt++ {
		tcp, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		udp, udpErr := net.ListenPacket("udp4", tcp.Addr().String())
		closeErr := tcp.Close()
		if closeErr != nil {
			if udp != nil {
				udp.Close()
			}
			t.Fatal(closeErr)
		}
		if udpErr == nil {
			t.Cleanup(func() { udp.Close() })
			assertTCPPortFree(t, udp.LocalAddr().String())
			return udp
		}
	}
	t.Fatal("could not reserve UDP on an available TCP port")
	return nil
}

func TestCreateReleasesTCPWhenSecondUDPBindFails(t *testing.T) {
	isolatedServiceConfig(t)
	blocker := blockUDPOnFreeTCPPort(t)
	addr := blocker.LocalAddr().String()
	assertTCPPortFree(t, addr)
	req := createServicesRequest{Data: []config.ServiceConfig{serviceTestConfig("cleanup_tcp", addr, "tcp"), serviceTestConfig("cleanup_udp", addr, "udp")}}
	for i := 0; i < 5; i++ {
		if err := createServices(req); err == nil {
			t.Fatal("expected UDP bind failure")
		} else if !strings.Contains(err.Error(), "cleanup_udp") {
			t.Fatalf("batch did not reach its blocked UDP member: %v", err)
		}
		assertTCPPortFree(t, addr)
		if registry.ServiceRegistry().IsRegistered("cleanup_tcp") || len(config.Global().Services) != 0 {
			t.Fatal("failed batch changed registry or configuration")
		}
	}
}

func TestCreateValidatesEntireBatchBeforeBinding(t *testing.T) {
	isolatedServiceConfig(t)
	addr := unusedTCPAddress(t)
	if err := registry.ServiceRegistry().Register("already_registered_udp", nil); err != nil {
		t.Fatal(err)
	}
	defer registry.ServiceRegistry().Unregister("already_registered_udp")
	err := createServices(createServicesRequest{Data: []config.ServiceConfig{serviceTestConfig("preflight_tcp", addr, "tcp"), serviceTestConfig("already_registered_udp", addr, "udp")}})
	if err == nil {
		t.Fatal("expected registry conflict")
	}
	assertTCPPortFree(t, addr)
	err = createServices(createServicesRequest{Data: []config.ServiceConfig{serviceTestConfig("duplicate", addr, "tcp"), serviceTestConfig("duplicate", addr, "udp")}})
	if err == nil {
		t.Fatal("expected duplicate name conflict")
	}
	assertTCPPortFree(t, addr)
}

func TestParserFailureAfterListenerInitReleasesPort(t *testing.T) {
	addr := unusedTCPAddress(t)
	cfg := serviceTestConfig("invalid_handler", addr, "tcp")
	cfg.Handler.Type = "nonexistent-handler"
	if _, err := parser.ParseService(&cfg); err == nil {
		t.Fatal("expected handler error")
	}
	assertTCPPortFree(t, addr)
}

func TestResumeFailurePreservesPausedPairAndCanRetry(t *testing.T) {
	isolatedServiceConfig(t)
	addr := unusedTCPAddress(t)
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{serviceTestConfig("resume_tcp", addr, "tcp"), serviceTestConfig("resume_udp", addr, "udp")}}); err != nil {
		t.Fatal(err)
	}
	names := []string{"resume_tcp", "resume_udp"}
	if err := pauseServices(pauseServicesRequest{Services: names}); err != nil {
		t.Fatal(err)
	}
	oldTCP, oldUDP := registry.ServiceRegistry().Get(names[0]), registry.ServiceRegistry().Get(names[1])
	blocker, err := net.ListenPacket("udp4", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Close()
	if err := resumeServices(resumeServicesRequest{Services: names}); err == nil {
		t.Fatal("expected UDP bind failure")
	}
	assertTCPPortFree(t, addr)
	if registry.ServiceRegistry().Get(names[0]) != oldTCP || registry.ServiceRegistry().Get(names[1]) != oldUDP {
		t.Fatal("failed resume removed or replaced the paused registry entries")
	}
	for _, cfg := range config.Global().Services {
		if cfg.Metadata["paused"] != true {
			t.Fatal("failed resume removed paused state")
		}
	}
	blocker.Close()
	for i := 0; i < 20; i++ {
		if err := resumeServices(resumeServicesRequest{Services: append(names, names[0])}); err != nil {
			t.Fatal(err)
		}
		for _, cfg := range config.Global().Services {
			if cfg.Metadata["paused"] == true {
				t.Fatal("successful resume retained paused state")
			}
		}
		if err := pauseServices(pauseServicesRequest{Services: names}); err != nil {
			t.Fatal(err)
		}
		assertTCPPortFree(t, addr)
	}
}

func TestResumeRecoversMissingRegistryEntryFromExistingConfig(t *testing.T) {
	isolatedServiceConfig(t)
	addr := unusedTCPAddress(t)
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{serviceTestConfig("recover_tcp", addr, "tcp")}}); err != nil {
		t.Fatal(err)
	}
	if err := pauseServices(pauseServicesRequest{Services: []string{"recover_tcp"}}); err != nil {
		t.Fatal(err)
	}
	registry.ServiceRegistry().Unregister("recover_tcp")
	if err := resumeServices(resumeServicesRequest{Services: []string{"recover_tcp"}}); err != nil {
		t.Fatal(err)
	}
	if registry.ServiceRegistry().Get("recover_tcp") == nil {
		t.Fatal("resume did not recover the missing entry")
	}
}

func TestInvalidUpdateDoesNotReplaceRunningService(t *testing.T) {
	isolatedServiceConfig(t)
	addr := unusedTCPAddress(t)
	cfg := serviceTestConfig("update_preflight", addr, "tcp")
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{cfg}}); err != nil {
		t.Fatal(err)
	}
	old := registry.ServiceRegistry().Get(cfg.Name)
	cfg.Handler = &config.HandlerConfig{Type: "nonexistent-handler"}
	if err := updateServices(updateServicesRequest{Data: []config.ServiceConfig{cfg}}); err == nil {
		t.Fatal("expected handler error")
	}
	if registry.ServiceRegistry().Get(cfg.Name) != old {
		t.Fatal("invalid configuration replaced the running service")
	}
}

func TestFailedUpdateRestoresEntireOriginalPair(t *testing.T) {
	isolatedServiceConfig(t)
	addr := unusedTCPAddress(t)
	original := []config.ServiceConfig{serviceTestConfig("update_tcp", addr, "tcp"), serviceTestConfig("update_udp", addr, "udp")}
	if err := createServices(createServicesRequest{Data: original}); err != nil {
		t.Fatal(err)
	}
	blocker := blockUDPOnFreeTCPPort(t)
	badAddr := blocker.LocalAddr().String()
	replacements := []config.ServiceConfig{serviceTestConfig("update_tcp", badAddr, "tcp"), serviceTestConfig("update_udp", badAddr, "udp")}
	if err := updateServices(updateServicesRequest{Data: replacements}); err == nil {
		t.Fatal("expected UDP bind failure")
	} else if !strings.Contains(err.Error(), "update service update_udp failed:") {
		t.Fatalf("replacement did not reach its blocked UDP member: %v", err)
	}
	assertTCPPortFree(t, badAddr)
	for _, cfg := range config.Global().Services {
		if cfg.Addr != addr || registry.ServiceRegistry().Get(cfg.Name) == nil {
			t.Fatal("failed update did not restore original configuration and registry")
		}
	}
	probe, err := net.Listen("tcp4", addr)
	if err == nil {
		probe.Close()
		t.Fatal("original TCP listener was not restored")
	}
	udpProbe, err := net.ListenPacket("udp4", addr)
	if err == nil {
		udpProbe.Close()
		t.Fatal("original UDP listener was not restored")
	}
}

func TestPausedUpdateAndFailedReplacementRemainPaused(t *testing.T) {
	isolatedServiceConfig(t)
	addr := unusedTCPAddress(t)
	cfg := serviceTestConfig("update_paused", addr, "tcp")
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{cfg}}); err != nil {
		t.Fatal(err)
	}
	if err := pauseServices(pauseServicesRequest{Services: []string{cfg.Name}}); err != nil {
		t.Fatal(err)
	}
	newAddr := unusedTCPAddress(t)
	if err := updateServices(updateServicesRequest{Data: []config.ServiceConfig{serviceTestConfig(cfg.Name, newAddr, "tcp")}}); err != nil {
		t.Fatal(err)
	}
	assertTCPPortFree(t, newAddr)
	blocker, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Close()
	if err := updateServices(updateServicesRequest{Data: []config.ServiceConfig{serviceTestConfig(cfg.Name, blocker.Addr().String(), "tcp")}}); err == nil {
		t.Fatal("expected bind failure")
	}
	assertTCPPortFree(t, newAddr)
	state := config.Global().Services[0]
	if state.Metadata["paused"] != true || state.Addr != newAddr || registry.ServiceRegistry().Get(cfg.Name) == nil {
		t.Fatal("failed replacement did not preserve the paused original")
	}
	if err := resumeServices(resumeServicesRequest{Services: []string{cfg.Name}}); err != nil {
		t.Fatal(err)
	}
}
