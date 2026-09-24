package service

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/go-gost/core/observer/stats"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/internal/util/crypto"
	"github.com/go-gost/x/registry"
	"github.com/rs/xid"
)

var httpReportURL string
var configReportURL string
var httpReportToken string
var httpAESCrypto *crypto.AESCrypto // 新增：HTTP上报加密器

// A report sequence is scoped to one service and this agent process. The
// server uses this session + sequence pair to acknowledge HTTP retries once
// without persisting an unbounded row for every five-second report.
var trafficReportSessionID = xid.New().String()
var trafficReportSessionStartedAt = time.Now().UnixNano() / int64(time.Millisecond)

// TrafficReportItem 流量报告项（压缩格式）
type TrafficReportItem struct {
	N string `json:"n"` // 服务名（name缩写）
	U int64  `json:"u"` // 上行流量（up缩写）
	D int64  `json:"d"` // 下行流量（down缩写）
	I string `json:"i"` // Agent session / boot identifier
	Q uint64 `json:"q"` // Per-service report sequence
	B int64  `json:"b"` // Agent session start time in milliseconds
}

const (
	trafficBatchInterval = 750 * time.Millisecond
	trafficBatchLimit    = 128
)

type trafficReportDelivery struct {
	acknowledged bool
}

type queuedTrafficReport struct {
	item   TrafficReportItem
	result chan trafficReportDelivery
}

type trafficBatchRequest struct {
	Version int                 `json:"v"`
	Items   []TrafficReportItem `json:"items"`
}

type trafficBatchAck struct {
	N string `json:"n"`
	I string `json:"i"`
	Q uint64 `json:"q"`
	B int64  `json:"b"`
}

type trafficBatchResponse struct {
	Type         string            `json:"type"`
	Acknowledged []trafficBatchAck `json:"acknowledged"`
}

type trafficBatchCapability uint8

const (
	trafficBatchUnknown trafficBatchCapability = iota
	trafficBatchSupported
	trafficBatchLegacy
)

// trafficBatcher owns at most one unsent report per service. A service keeps
// its counters until the matching delivery channel is acknowledged, so a
// timeout can neither duplicate nor lose a report.
var trafficBatcher = struct {
	sync.Mutex
	pending    map[string]*queuedTrafficReport
	wake       chan struct{}
	started    bool
	capability trafficBatchCapability
}{
	pending: make(map[string]*queuedTrafficReport),
	wake:    make(chan struct{}, 1),
}

var trafficHTTPClient = &http.Client{Timeout: 5 * time.Second}

// StartTrafficReporter coalesces the per-service snapshots that arrive every
// few seconds into bounded HTTP batches. It is deliberately separate from the
// config reporter, whose full inventory has very different timing needs.
func StartTrafficReporter(ctx context.Context) {
	trafficBatcher.Lock()
	if trafficBatcher.started {
		trafficBatcher.Unlock()
		return
	}
	trafficBatcher.started = true
	trafficBatcher.Unlock()
	go runTrafficBatcher(ctx)
}

// enqueueTrafficReport returns a single completion channel for this exact
// report. Calling code should retain it until it receives a delivery result.
func enqueueTrafficReport(report TrafficReportItem) <-chan trafficReportDelivery {
	key := trafficReportKey(report)
	trafficBatcher.Lock()
	if existing := trafficBatcher.pending[key]; existing != nil {
		result := existing.result
		trafficBatcher.Unlock()
		return result
	}
	queued := &queuedTrafficReport{item: report, result: make(chan trafficReportDelivery, 1)}
	trafficBatcher.pending[key] = queued
	trafficBatcher.Unlock()
	notifyTrafficBatcher()
	return queued.result
}

func cancelTrafficReport(report TrafficReportItem) {
	trafficBatcher.Lock()
	delete(trafficBatcher.pending, trafficReportKey(report))
	trafficBatcher.Unlock()
}

func notifyTrafficBatcher() {
	select {
	case trafficBatcher.wake <- struct{}{}:
	default:
	}
}

func runTrafficBatcher(ctx context.Context) {
	ticker := time.NewTicker(trafficBatchInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-trafficBatcher.wake:
			// Let the short ticker collect reports from other services first.
		case <-ticker.C:
			flushTrafficBatch(ctx)
		}
	}
}

func flushTrafficBatch(ctx context.Context) {
	queued, capability := takeTrafficBatch()
	if len(queued) == 0 {
		return
	}

	if capability == trafficBatchLegacy {
		deliverLegacyTrafficReports(ctx, queued)
		return
	}

	items := make([]TrafficReportItem, 0, len(queued))
	for _, pending := range queued {
		items = append(items, pending.item)
	}
	acknowledged, compatibilityKnown, err := sendTrafficReportBatch(ctx, items)
	if err != nil {
		for _, pending := range queued {
			pending.result <- trafficReportDelivery{}
		}
		return
	}
	if !compatibilityKnown {
		// Older panels return plain "ok" for an unknown object. That request
		// did not contain a top-level flow item, so resend each exact sequence
		// through the legacy protocol without acknowledging it prematurely.
		trafficBatcher.Lock()
		trafficBatcher.capability = trafficBatchLegacy
		trafficBatcher.Unlock()
		deliverLegacyTrafficReports(ctx, queued)
		return
	}

	trafficBatcher.Lock()
	trafficBatcher.capability = trafficBatchSupported
	trafficBatcher.Unlock()
	for _, pending := range queued {
		_, ok := acknowledged[trafficReportKey(pending.item)]
		pending.result <- trafficReportDelivery{acknowledged: ok}
	}
}

func takeTrafficBatch() ([]*queuedTrafficReport, trafficBatchCapability) {
	trafficBatcher.Lock()
	defer trafficBatcher.Unlock()
	queued := make([]*queuedTrafficReport, 0, trafficBatchLimit)
	for key, pending := range trafficBatcher.pending {
		queued = append(queued, pending)
		delete(trafficBatcher.pending, key)
		if len(queued) == trafficBatchLimit {
			break
		}
	}
	return queued, trafficBatcher.capability
}

func deliverLegacyTrafficReports(ctx context.Context, queued []*queuedTrafficReport) {
	for _, pending := range queued {
		success, err := sendTrafficReport(ctx, pending.item)
		if err != nil {
			fmt.Printf("发送流量报告失败: %v", err)
		}
		pending.result <- trafficReportDelivery{acknowledged: success}
	}
}

func trafficReportKey(report TrafficReportItem) string {
	return report.N + "\x00" + report.I + "\x00" + strconv.FormatUint(report.Q, 10) + "\x00" + strconv.FormatInt(report.B, 10)
}

func SetHTTPReportURL(addr string, secret string) {
	var err error
	httpReportURL, err = buildPanelHTTPURL(addr, "/flow/upload")
	if err != nil {
		fmt.Printf("❌ 设置流量上报地址失败: %v\n", err)
		httpReportURL = ""
	}
	configReportURL, err = buildPanelHTTPURL(addr, "/flow/config")
	if err != nil {
		fmt.Printf("❌ 设置配置上报地址失败: %v\n", err)
		configReportURL = ""
	}
	httpReportToken = secret

	// 创建 AES 加密器
	httpAESCrypto, err = crypto.NewAESCrypto(secret)
	if err != nil {
		fmt.Printf("❌ 创建 HTTP AES 加密器失败: %v\n", err)
		httpAESCrypto = nil
	} else {
		fmt.Printf("🔐 HTTP AES 加密器创建成功\n")
	}
}

// sendTrafficReport 发送流量报告到HTTP接口
func sendTrafficReport(ctx context.Context, reportItems TrafficReportItem) (bool, error) {
	jsonData, err := json.Marshal(reportItems)
	if err != nil {
		return false, fmt.Errorf("序列化报告数据失败: %v", err)
	}
	responseText, err := postTrafficPayload(ctx, jsonData)
	if err != nil {
		return false, err
	}
	if responseText == "ok" {
		return true, nil
	}
	return false, fmt.Errorf("服务器响应: %s (期望: ok)", responseText)
}

// sendTrafficReportBatch returns an acknowledgement map for the exact report
// keys the panel committed. A literal "ok" means an older panel and triggers
// the caller's safe single-report fallback.
func sendTrafficReportBatch(ctx context.Context, items []TrafficReportItem) (map[string]struct{}, bool, error) {
	jsonData, err := json.Marshal(trafficBatchRequest{Version: 2, Items: items})
	if err != nil {
		return nil, false, fmt.Errorf("序列化批量流量报告失败: %v", err)
	}
	responseText, err := postTrafficPayload(ctx, jsonData)
	if err != nil {
		return nil, false, err
	}
	if responseText == "ok" {
		return nil, false, nil
	}

	var response trafficBatchResponse
	if err := json.Unmarshal([]byte(responseText), &response); err != nil {
		return nil, true, fmt.Errorf("解析批量流量响应失败: %v", err)
	}
	if response.Type != "flow_batch" {
		return nil, true, fmt.Errorf("未知批量流量响应: %s", responseText)
	}
	acknowledged := make(map[string]struct{}, len(response.Acknowledged))
	for _, ack := range response.Acknowledged {
		acknowledged[trafficReportKey(TrafficReportItem{N: ack.N, I: ack.I, Q: ack.Q, B: ack.B})] = struct{}{}
	}
	return acknowledged, true, nil
}

func postTrafficPayload(ctx context.Context, jsonData []byte) (string, error) {
	if httpReportURL == "" {
		return "", fmt.Errorf("流量上报URL未设置")
	}

	var requestBody []byte

	// 如果有加密器，则加密数据
	if httpAESCrypto != nil {
		encryptedData, err := httpAESCrypto.Encrypt(jsonData)
		if err != nil {
			fmt.Printf("⚠️ 加密流量报告失败，发送原始数据: %v\n", err)
			requestBody = jsonData
		} else {
			// 创建加密消息包装器
			encryptedMessage := map[string]interface{}{
				"encrypted": true,
				"data":      encryptedData,
				"timestamp": time.Now().Unix(),
			}
			requestBody, err = json.Marshal(encryptedMessage)
			if err != nil {
				fmt.Printf("⚠️ 序列化加密流量报告失败，发送原始数据: %v\n", err)
				requestBody = jsonData
			}
		}
	} else {
		requestBody = jsonData
	}

	req, err := http.NewRequestWithContext(ctx, "POST", httpReportURL, bytes.NewBuffer(requestBody))
	if err != nil {
		return "", fmt.Errorf("创建HTTP请求失败: %v", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "GOST-Traffic-Reporter/1.0")
	req.Header.Set("Authorization", "Bearer "+httpReportToken)

	resp, err := trafficHTTPClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("发送HTTP请求失败: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("HTTP响应错误: %d %s", resp.StatusCode, resp.Status)
	}

	// 读取响应内容
	var responseBytes bytes.Buffer
	_, err = responseBytes.ReadFrom(resp.Body)
	if err != nil {
		return "", fmt.Errorf("读取响应内容失败: %v", err)
	}
	return strings.TrimSpace(responseBytes.String()), nil
}

// sendConfigReport 发送配置报告到HTTP接口
func sendConfigReport(ctx context.Context) (bool, error) {
	if configReportURL == "" {
		return false, fmt.Errorf("配置上报URL未设置")
	}

	// 获取配置数据
	configData, err := getConfigData()
	if err != nil {
		return false, fmt.Errorf("获取配置数据失败: %v", err)
	}

	var requestBody []byte

	// 如果有加密器，则加密数据
	if httpAESCrypto != nil {
		encryptedData, err := httpAESCrypto.Encrypt(configData)
		if err != nil {
			fmt.Printf("⚠️ 加密配置报告失败，发送原始数据: %v\n", err)
			requestBody = configData
		} else {
			// 创建加密消息包装器
			encryptedMessage := map[string]interface{}{
				"encrypted": true,
				"data":      encryptedData,
				"timestamp": time.Now().Unix(),
			}
			requestBody, err = json.Marshal(encryptedMessage)
			if err != nil {
				fmt.Printf("⚠️ 序列化加密配置报告失败，发送原始数据: %v\n", err)
				requestBody = configData
			}
		}
	} else {
		requestBody = configData
	}

	req, err := http.NewRequestWithContext(ctx, "POST", configReportURL, bytes.NewBuffer(requestBody))
	if err != nil {
		return false, fmt.Errorf("创建HTTP请求失败: %v", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "Config-Reporter/1.0")
	req.Header.Set("Authorization", "Bearer "+httpReportToken)

	client := &http.Client{
		Timeout: 10 * time.Second, // 配置上报可以稍长一些
	}

	resp, err := client.Do(req)
	if err != nil {
		return false, fmt.Errorf("发送HTTP请求失败: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("HTTP响应错误: %d %s", resp.StatusCode, resp.Status)
	}

	// 读取响应内容
	var responseBytes bytes.Buffer
	_, err = responseBytes.ReadFrom(resp.Body)
	if err != nil {
		return false, fmt.Errorf("读取响应内容失败: %v", err)
	}

	responseText := strings.TrimSpace(responseBytes.String())

	// 检查响应是否为"ok"
	if responseText == "ok" {
		return true, nil
	} else {
		return false, fmt.Errorf("服务器响应: %s (期望: ok)", responseText)
	}
}

// StartConfigReporter 启动配置定时上报器（每10分钟上报一次）
func StartConfigReporter(ctx context.Context) {
	if configReportURL == "" {
		fmt.Printf("⚠️ 配置上报URL未设置，跳过定时上报\n")
		return
	}

	fmt.Printf("🚀 配置定时上报器已启动，每10分钟上报一次（首次上报由 WebSocket 连接完成后触发）\n")

	// 创建10分钟定时器
	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()

	// 定时上报循环
	for {
		select {
		case <-ticker.C:
			go func() {
				success, err := sendConfigReport(ctx)
				if err != nil {
					fmt.Printf("❌ 定时配置上报失败: %v\n", err)
				} else if success {
					fmt.Printf("✅ 定时配置上报成功\n")
				}
			}()

		case <-ctx.Done():
			fmt.Printf("⏹️ 配置定时上报器已停止\n")
			return
		}
	}
}

// ReportConfigNow sends one best-effort inventory report after an Agent has
// reconnected to the panel. It complements (rather than replaces) the
// periodic reporter: the periodic job still repairs drift discovered while a
// connection remains up, while this path removes the long reconnect delay.
func ReportConfigNow() {
	if configReportURL == "" {
		fmt.Printf("⚠️ 配置上报URL未设置，跳过连接后的即时上报\n")
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if success, err := sendConfigReport(ctx); err != nil {
		fmt.Printf("❌ 连接后即时配置上报失败: %v\n", err)
	} else if success {
		fmt.Printf("✅ 连接后即时配置上报成功\n")
	}
}

// serviceStatus 接口定义
type serviceStatus interface {
	Status() *Status
}

// getConfigResponse 配置响应结构
type getConfigResponse struct {
	Config *config.Config `json:"config"`
}

// getConfigData 获取配置数据（避免循环依赖）
func getConfigData() ([]byte, error) {
	config.OnUpdate(func(c *config.Config) error {
		for _, svc := range c.Services {
			if svc == nil {
				continue
			}
			s := registry.ServiceRegistry().Get(svc.Name)
			ss, ok := s.(serviceStatus)
			if ok && ss != nil {
				status := ss.Status()
				svc.Status = &config.ServiceStatus{
					CreateTime: status.CreateTime().Unix(),
					State:      string(status.State()),
				}
				if st := status.Stats(); st != nil {
					svc.Status.Stats = &config.ServiceStats{
						TotalConns:   st.Get(stats.KindTotalConns),
						CurrentConns: st.Get(stats.KindCurrentConns),
						TotalErrs:    st.Get(stats.KindTotalErrs),
						InputBytes:   st.Get(stats.KindInputBytes),
						OutputBytes:  st.Get(stats.KindOutputBytes),
					}
				}
				for _, ev := range status.Events() {
					if !ev.Time.IsZero() {
						svc.Status.Events = append(svc.Status.Events, config.ServiceEvent{
							Time: ev.Time.Unix(),
							Msg:  ev.Message,
						})
					}
				}
			}
		}
		return nil
	})

	buf := &bytes.Buffer{}
	// 后端兼容旧版 {"config": {...}} 格式，但新 agent 直接上报配置体，
	// 避免服务、链和限速器在恢复流程中被错误解析为空。
	config.Global().Write(buf, "json")
	return buf.Bytes(), nil
}

// buildPanelHTTPURL 将面板地址转换为流量/配置上报地址。
// https:// 与 wss:// 地址会走 HTTPS，避免节点在 HTTPS 面板上回退成明文 HTTP。
func buildPanelHTTPURL(addr, endpoint string) (string, error) {
	rawAddr := strings.TrimSpace(addr)
	if rawAddr == "" {
		return "", fmt.Errorf("面板地址为空")
	}
	if !strings.Contains(rawAddr, "://") {
		rawAddr = "http://" + rawAddr
	}

	u, err := url.Parse(rawAddr)
	if err != nil {
		return "", fmt.Errorf("解析面板地址失败: %w", err)
	}
	if u.Host == "" {
		return "", fmt.Errorf("无效的面板地址: %s", addr)
	}

	switch strings.ToLower(u.Scheme) {
	case "http", "https":
		// 保持 HTTP/HTTPS。
	case "ws":
		u.Scheme = "http"
	case "wss":
		u.Scheme = "https"
	default:
		return "", fmt.Errorf("不支持的面板协议: %s", u.Scheme)
	}

	u.Path = strings.TrimRight(u.Path, "/") + endpoint
	return u.String(), nil
}
