package socket

import (
	"errors"
	"fmt"
	"strings"

	"github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	parser "github.com/go-gost/x/config/parsing/service"
	"github.com/go-gost/x/internal/servicecontrol"
	"github.com/go-gost/x/registry"
)

// Serialize commands that replace registry entries and configuration together.
// This lock is never taken by packet forwarding or traffic accounting.
var serviceMutationMu = &servicecontrol.MutationMu

func createServices(req createServicesRequest) error {
	serviceMutationMu.Lock()
	defer serviceMutationMu.Unlock()

	if len(req.Data) == 0 {
		return errors.New("services list cannot be empty")
	}

	// Validate the complete batch before binding any listener.
	names := make(map[string]bool, len(req.Data))
	for i := range req.Data {
		name := strings.TrimSpace(req.Data[i].Name)
		if name == "" {
			return errors.New("service name is required")
		}
		if names[name] || registry.ServiceRegistry().IsRegistered(name) {
			return errors.New("service " + name + " already exists")
		}
		names[name] = true
		req.Data[i].Name = name
	}

	var parsedServices []struct {
		config  config.ServiceConfig
		service service.Service
	}
	committed := false
	defer func() {
		if !committed {
			for _, ps := range parsedServices {
				if registry.ServiceRegistry().Get(ps.config.Name) == ps.service {
					registry.ServiceRegistry().Unregister(ps.config.Name)
				}
				ps.service.Close()
			}
		}
	}()

	for _, serviceConfig := range req.Data {
		svc, err := parser.ParseService(&serviceConfig)
		if err != nil {
			return errors.New("create service " + serviceConfig.Name + " failed: " + err.Error())
		}

		parsedServices = append(parsedServices, struct {
			config  config.ServiceConfig
			service service.Service
		}{serviceConfig, svc})
	}

	// 第二阶段：注册所有服务
	for _, ps := range parsedServices {
		if err := registry.ServiceRegistry().Register(ps.config.Name, ps.service); err != nil {
			return errors.New("service " + ps.config.Name + " already exists")
		}
	}

	if err := config.OnUpdate(func(c *config.Config) error {
		for i := range parsedServices {
			c.Services = append(c.Services, &parsedServices[i].config)
		}
		return nil
	}); err != nil {
		return err
	}
	committed = true
	for _, ps := range parsedServices {
		go ps.service.Serve()
	}

	return nil
}

func updateServices(req updateServicesRequest) (resultErr error) {
	serviceMutationMu.Lock()
	defer serviceMutationMu.Unlock()

	if len(req.Data) == 0 {
		return errors.New("services list cannot be empty")
	}

	previous := make([]*config.ServiceConfig, len(req.Data))
	names := make(map[string]bool, len(req.Data))
	current := config.Global()
	for i := range req.Data {
		serviceConfig := &req.Data[i]
		name := strings.TrimSpace(serviceConfig.Name)
		if name == "" {
			return errors.New("service name is required")
		}
		if names[name] {
			return fmt.Errorf("duplicate service %s", name)
		}
		names[name] = true
		serviceConfig.Name = name
		if registry.ServiceRegistry().Get(name) == nil {
			return errors.New("service " + name + " not found")
		}
		for _, cfg := range current.Services {
			if cfg.Name == name {
				previous[i] = cfg
				break
			}
		}
		if previous[i] == nil {
			return fmt.Errorf("service %s configuration not found", name)
		}
		if err := preserveServicePolicy(serviceConfig, previous[i]); err != nil {
			return err
		}
		if previous[i].Metadata != nil && previous[i].Metadata["paused"] == true {
			md := make(map[string]any, len(serviceConfig.Metadata)+1)
			for k, v := range serviceConfig.Metadata {
				md[k] = v
			}
			md["paused"] = true
			serviceConfig.Metadata = md
		}
		listenerType, handlerType := "tcp", "auto"
		if serviceConfig.Listener != nil && strings.TrimSpace(serviceConfig.Listener.Type) != "" {
			listenerType = serviceConfig.Listener.Type
		}
		if serviceConfig.Handler != nil && strings.TrimSpace(serviceConfig.Handler.Type) != "" {
			handlerType = serviceConfig.Handler.Type
		}
		if registry.ListenerRegistry().Get(listenerType) == nil {
			return fmt.Errorf("unknown listener: %s", listenerType)
		}
		if registry.HandlerRegistry().Get(handlerType) == nil {
			return fmt.Errorf("unknown handler: %s", handlerType)
		}
	}
	staged := make([]service.Service, 0, len(req.Data))
	committed := false
	defer func() {
		if committed {
			return
		}
		for i, svc := range staged {
			if registry.ServiceRegistry().Get(req.Data[i].Name) == svc {
				registry.ServiceRegistry().Unregister(req.Data[i].Name)
			}
			svc.Close()
		}
		// Updating an existing port requires releasing the old listener. Restore
		// its original configuration when the replacement cannot be installed.
		for _, cfg := range previous {
			svc, err := parser.ParseService(cfg)
			if err == nil {
				paused := cfg.Metadata != nil && cfg.Metadata["paused"] == true
				if paused {
					svc.Close()
				}
				err = registry.ServiceRegistry().Register(cfg.Name, svc)
				if err != nil {
					svc.Close()
				} else if !paused {
					go svc.Serve()
				}
			}
			if err != nil {
				resultErr = errors.Join(resultErr, fmt.Errorf("restore service %s failed: %w", cfg.Name, err))
			}
		}
	}()
	for _, cfg := range previous {
		old := registry.ServiceRegistry().Get(cfg.Name)
		old.Close()
		registry.ServiceRegistry().Unregister(cfg.Name)
	}
	for i := range req.Data {
		svc, err := parser.ParseService(&req.Data[i])
		if err != nil {
			return fmt.Errorf("update service %s failed: %w", req.Data[i].Name, err)
		}
		staged = append(staged, svc)
	}
	for i, svc := range staged {
		if err := registry.ServiceRegistry().Register(req.Data[i].Name, svc); err != nil {
			return fmt.Errorf("service %s already exists", req.Data[i].Name)
		}
	}
	if err := config.OnUpdate(func(c *config.Config) error {
		for j := range req.Data {
			for i := range c.Services {
				if c.Services[i].Name == req.Data[j].Name {
					c.Services[i] = &req.Data[j]
					break
				}
			}
		}
		return nil
	}); err != nil {
		return err
	}
	committed = true
	for i, svc := range staged {
		if req.Data[i].Metadata != nil && req.Data[i].Metadata["paused"] == true {
			svc.Close()
		} else {
			go svc.Serve()
		}
	}
	return nil
}

func preserveServicePolicy(incoming, current *config.ServiceConfig) error {
	return servicecontrol.PreservePolicy(incoming, current)
}

func deleteServices(req deleteServicesRequest) error {
	serviceMutationMu.Lock()
	defer serviceMutationMu.Unlock()

	if len(req.Services) == 0 {
		return errors.New("services list cannot be empty")
	}

	// Validate the full request first. Missing members are already deleted;
	// a lost ACK or partially restored TCP/UDP pair must be safe to retry.
	names := make(map[string]bool, len(req.Services))
	for _, serviceName := range req.Services {
		name := strings.TrimSpace(serviceName)
		if name == "" {
			return errors.New("service name is required")
		}
		names[name] = true
	}

	var closeErr error
	for name := range names {
		if svc := registry.ServiceRegistry().Get(name); svc != nil {
			registry.ServiceRegistry().Unregister(name)
			closeErr = errors.Join(closeErr, svc.Close())
		}
	}

	// Remove stale configuration too, even when its listener was absent.
	updateErr := config.OnUpdate(func(c *config.Config) error {
		services := c.Services
		c.Services = nil
		for _, s := range services {
			if s == nil || !names[s.Name] {
				c.Services = append(c.Services, s)
			}
		}
		return nil
	})

	return errors.Join(closeErr, updateErr)
}

func pauseServices(req pauseServicesRequest) error {
	serviceMutationMu.Lock()
	defer serviceMutationMu.Unlock()

	if len(req.Services) == 0 {
		return errors.New("services list cannot be empty")
	}

	// 第一阶段：验证配置，并筛选仍需要暂停的服务。暂停命令必须
	// 幂等：面板可能在节点已经执行成功、但确认消息超时时重试。
	var servicesToPause []struct {
		name    string
		service service.Service
	}

	cfg := config.Global()
	serviceConfigs := make(map[string]*config.ServiceConfig)
	for _, serviceConfig := range cfg.Services {
		serviceConfigs[serviceConfig.Name] = serviceConfig
	}
	for _, serviceName := range req.Services {
		name := strings.TrimSpace(serviceName)
		if name == "" {
			return errors.New("service name is required")
		}

		serviceConfig := serviceConfigs[name]
		if serviceConfig == nil {
			return errors.New(fmt.Sprintf("service %s configuration not found", name))
		}
		if serviceConfig.Metadata != nil && serviceConfig.Metadata["paused"] == true {
			continue
		}

		svc := registry.ServiceRegistry().Get(name)
		if svc == nil {
			return errors.New(fmt.Sprintf("service %s not found", name))
		}

		servicesToPause = append(servicesToPause, struct {
			name    string
			service service.Service
		}{name, svc})
	}
	if len(servicesToPause) == 0 {
		return nil
	}

	// 第二阶段：事务性暂停所有服务
	var pausedServices []struct {
		name          string
		service       service.Service
		serviceConfig *config.ServiceConfig
	}

	// 逐个暂停服务，如果失败则回滚
	for _, stp := range servicesToPause {
		serviceConfig := serviceConfigs[stp.name]
		if serviceConfig == nil {
			// 找不到配置，回滚已暂停的服务
			rollbackPausedServices(pausedServices)
			return errors.New(fmt.Sprintf("service %s configuration not found", stp.name))
		}

		// 暂停服务
		stp.service.Close()

		// 记录已暂停的服务
		pausedServices = append(pausedServices, struct {
			name          string
			service       service.Service
			serviceConfig *config.ServiceConfig
		}{stp.name, stp.service, serviceConfig})
	}

	// 第三阶段：更新配置，标记暂停状态
	err := config.OnUpdate(func(c *config.Config) error {
		for _, stp := range servicesToPause {
			for i := range c.Services {
				if c.Services[i].Name == stp.name {
					if c.Services[i].Metadata == nil {
						c.Services[i].Metadata = make(map[string]any)
					}
					c.Services[i].Metadata["paused"] = true
					break
				}
			}
		}
		return nil
	})

	if err != nil {
		// 配置更新失败，需要回滚所有暂停的服务
		rollbackPausedServices(pausedServices)
		return errors.New(fmt.Sprintf("Failed to update config, rolling back paused services: %v", err))
	}

	return nil
}

func resumeServices(req resumeServicesRequest) error {
	serviceMutationMu.Lock()
	defer serviceMutationMu.Unlock()
	if len(req.Services) == 0 {
		return errors.New("services list cannot be empty")
	}
	var servicesToResume []struct {
		name          string
		service       service.Service
		serviceConfig *config.ServiceConfig
	}
	names := make(map[string]bool, len(req.Services))
	cfg := config.Global()
	for _, serviceName := range req.Services {
		name := strings.TrimSpace(serviceName)
		if name == "" {
			return errors.New("service name is required")
		}
		if names[name] {
			continue
		}
		names[name] = true
		var serviceConfig *config.ServiceConfig
		for _, s := range cfg.Services {
			if s.Name == name {
				serviceConfig = s
				break
			}
		}

		if serviceConfig == nil {
			return errors.New(fmt.Sprintf("service %s configuration not found", name))
		}

		svc := registry.ServiceRegistry().Get(name)
		paused := serviceConfig.Metadata != nil && serviceConfig.Metadata["paused"] == true
		if !paused && svc != nil {
			continue
		}
		servicesToResume = append(servicesToResume, struct {
			name          string
			service       service.Service
			serviceConfig *config.ServiceConfig
		}{name, svc, serviceConfig})
	}
	// Keep the closed, paused registry entries until the complete batch has
	// parsed successfully. A failed TCP/UDP pair can then be retried without
	// leaving one side missing or one side running.
	staged := make([]service.Service, 0, len(servicesToResume))
	committed := false
	defer func() {
		if committed {
			return
		}
		for i, svc := range staged {
			str := servicesToResume[i]
			if registry.ServiceRegistry().Get(str.name) == svc {
				registry.ServiceRegistry().Unregister(str.name)
				if str.service != nil {
					_ = registry.ServiceRegistry().Register(str.name, str.service)
				}
			}
			svc.Close()
		}
	}()
	for _, str := range servicesToResume {
		if str.service != nil {
			str.service.Close()
		}
		svc, err := parser.ParseService(str.serviceConfig)
		if err != nil {
			return fmt.Errorf("resume service %s failed: %w", str.name, err)
		}
		staged = append(staged, svc)
	}
	for i, svc := range staged {
		str := servicesToResume[i]
		registry.ServiceRegistry().Unregister(str.name)
		if err := registry.ServiceRegistry().Register(str.name, svc); err != nil {
			if str.service != nil {
				_ = registry.ServiceRegistry().Register(str.name, str.service)
			}
			return fmt.Errorf("service %s already exists", str.name)
		}
	}
	err := config.OnUpdate(func(c *config.Config) error {
		for _, str := range servicesToResume {
			for i := range c.Services {
				if c.Services[i].Name == str.name {
					if c.Services[i].Metadata != nil {
						delete(c.Services[i].Metadata, "paused")
						// 如果 metadata 为空，设置为 nil
						if len(c.Services[i].Metadata) == 0 {
							c.Services[i].Metadata = nil
						}
					}
					break
				}
			}
		}
		return nil
	})
	if err != nil {
		return fmt.Errorf("failed to update resumed services configuration: %w", err)
	}
	committed = true
	for _, svc := range staged {
		go svc.Serve()
	}
	return nil
}

func rollbackPausedServices(pausedServices []struct {
	name          string
	service       service.Service
	serviceConfig *config.ServiceConfig
}) {
	for _, pss := range pausedServices {
		registry.ServiceRegistry().Unregister(pss.name)
		// 重新解析并启动服务
		svc, err := parser.ParseService(pss.serviceConfig)
		if err != nil {
			continue // 回滚失败，记录日志但继续处理其他服务
		}

		if err := registry.ServiceRegistry().Register(pss.name, svc); err != nil {
			svc.Close()
			continue // 回滚失败，记录日志但继续处理其他服务
		}

		go svc.Serve()

		// 移除暂停状态标记
		config.OnUpdate(func(c *config.Config) error {
			for i := range c.Services {
				if c.Services[i].Name == pss.name {
					if c.Services[i].Metadata != nil {
						delete(c.Services[i].Metadata, "paused")
						if len(c.Services[i].Metadata) == 0 {
							c.Services[i].Metadata = nil
						}
					}
					break
				}
			}
			return nil
		})
	}
}

func rollbackResumedServices(resumedServices []struct {
	name          string
	service       service.Service
	serviceConfig *config.ServiceConfig
}) {
	for _, rss := range resumedServices {
		// 关闭已恢复的服务
		if svc := registry.ServiceRegistry().Get(rss.name); svc != nil {
			svc.Close()
		}

		// 重新标记为暂停状态
		config.OnUpdate(func(c *config.Config) error {
			for i := range c.Services {
				if c.Services[i].Name == rss.name {
					if c.Services[i].Metadata == nil {
						c.Services[i].Metadata = make(map[string]any)
					}
					c.Services[i].Metadata["paused"] = true
					break
				}
			}
			return nil
		})
	}
}

type resumeServicesRequest struct {
	Services []string `json:"services"`
}

type pauseServicesRequest struct {
	Services []string `json:"services"`
}

type deleteServicesRequest struct {
	Services []string `json:"services"`
}

type updateServicesRequest struct {
	Data []config.ServiceConfig `json:"data"`
}

type createServicesRequest struct {
	Data []config.ServiceConfig `json:"data"`
}
