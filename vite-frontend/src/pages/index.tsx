import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { useState, useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import toast from 'react-hot-toast';
import axios from 'axios';
import { isWebViewFunc } from '@/utils/panel';
import { siteConfig, getCachedConfig } from '@/config/site';
import { login, LoginData, checkCaptcha } from "@/api";
import "@/utils/tac.css";
import "@/utils/tac.min.js";
import bgImage from "@/images/bg.jpg";
import "@/styles/login.css";


interface LoginForm {
  username: string;
  password: string;
  captchaId: string;
}



interface CaptchaConfig {
  requestCaptchaDataUrl: string;
  validCaptchaUrl: string;
  bindEl: string;
  validSuccess: (res: any, captcha: any, tac: any) => void;
  validFail?: (res: any, captcha: any, tac: any) => void;
  btnCloseFun?: (event: any, tac: any) => void;
  btnRefreshFun?: (event: any, tac: any) => void;
}

interface CaptchaStyle {
  btnUrl?: string;
  bgUrl?: string;
  logoUrl?: string | null;
  moveTrackMaskBgColor?: string;
  moveTrackMaskBorderColor?: string;
}

export default function IndexPage() {
  const [form, setForm] = useState<LoginForm>({
    username: "",
    password: "",
    captchaId: "",
  });
  const [loading, setLoading] = useState(false);
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [capsLock, setCapsLock] = useState(false);
  const [appName, setAppName] = useState(siteConfig.name);
  const [errors, setErrors] = useState<Partial<LoginForm>>({});
  const [showCaptcha, setShowCaptcha] = useState(false);
  const navigate = useNavigate();
  const tacInstanceRef = useRef<any>(null);
  const captchaContainerRef = useRef<HTMLDivElement>(null);
  const [isWebView, setIsWebView] = useState(false);
  useEffect(() => {
    let active = true;
    const updateBrand = async () => {
      try {
        const name = await getCachedConfig('app_name');
        if (active && name) setAppName(name);
      } catch { /* Keep the existing local brand while the server is unavailable. */ }
    };
    void updateBrand();
    window.addEventListener('configUpdated', updateBrand);
    return () => { active = false; window.removeEventListener('configUpdated', updateBrand); };
  }, []);
  // 清理验证码实例
  useEffect(() => {
    return () => {
      if (tacInstanceRef.current) {
        tacInstanceRef.current.destroyWindow();
        tacInstanceRef.current = null;
      }
    };
  }, []);
  // 检测是否在WebView中运行
  useEffect(() => {
    setIsWebView(isWebViewFunc());
  }, []);
  // 验证表单
  const validateForm = (): boolean => {
    const newErrors: Partial<LoginForm> = {};

    if (!form.username.trim()) {
      newErrors.username = '请输入用户名';
    }

    if (!form.password.trim()) {
      newErrors.password = '请输入密码';
    } else if (form.password.length < 6) {
      newErrors.password = '密码长度至少6位';
    }


    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 处理输入变化
  const handleInputChange = (field: keyof LoginForm, value: string) => {
    setForm(prev => ({ ...prev, [field]: value }));
    // 清除该字段的错误
    if (errors[field]) {
      setErrors(prev => ({ ...prev, [field]: undefined }));
    }
  };

  // 初始化验证码
  const initCaptcha = async () => {
    if (!window.TAC || !captchaContainerRef.current) {
      toast.error('验证码未能加载，请重试');
      setShowCaptcha(false);
      setLoading(false);
      return;
    }

    try {
      // 清理之前的验证码实例
      if (tacInstanceRef.current) {
        tacInstanceRef.current.destroyWindow();
        tacInstanceRef.current = null;
      }

      // 使用axios的baseURL，确保在WebView中使用正确的面板地址
      const baseURL = axios.defaults.baseURL || (import.meta.env.VITE_API_BASE ? `${import.meta.env.VITE_API_BASE}/api/v1/` : '/api/v1/');
      
      const config: CaptchaConfig = {
        requestCaptchaDataUrl: `${baseURL}captcha/generate`,
        validCaptchaUrl: `${baseURL}captcha/verify`, 
        bindEl: "#captcha-container",
        validSuccess: (res: any, _: any, tac: any) => {
          

          form.captchaId = res.data.validToken

          setShowCaptcha(false);
          tac.destroyWindow();
          performLogin();
        },
        validFail: (_: any, _captcha: any, tac: any) => {
          tac.reloadCaptcha();
        },
        btnCloseFun: (_event: any, tac: any) => {
          setShowCaptcha(false);
          tac.destroyWindow();
          setLoading(false);
        },
        btnRefreshFun: (_event: any, tac: any) => {
          tac.reloadCaptcha();
        }
      };

      // 检测暗黑模式
      const isDarkMode = document.documentElement.classList.contains('dark') || 
                        document.documentElement.getAttribute('data-theme') === 'dark' ||
                        window.matchMedia('(prefers-color-scheme: dark)').matches;
      
      // 根据主题调整颜色
      const trackColor = isDarkMode ? "#4a5568" : "#7db0be"; // 暗黑模式使用更深的灰蓝色
      
      const style: CaptchaStyle = {
        bgUrl: bgImage,
        logoUrl: null,
        moveTrackMaskBgColor: trackColor,
        moveTrackMaskBorderColor: trackColor
      };

      tacInstanceRef.current = new window.TAC(config, style);
      tacInstanceRef.current.init();

    } catch (error) {
      console.error('初始化验证码失败:', error);
      toast.error('验证码初始化失败，请刷新页面重试');
      setShowCaptcha(false);
      setLoading(false);
    }
  };

  // 执行登录请求
  const performLogin = async () => {


    try {
      const loginData: LoginData = {
        username: form.username.trim(),
        password: form.password,
        captchaId: form.captchaId,
      };

      const response = await login(loginData);
      
      if (response.code !== 0) {
        toast.error(response.msg || "登录失败");
        return;
      }

      // 检查是否需要强制修改密码
      if (response.data.requirePasswordChange) {
        localStorage.setItem('token', response.data.token);
        localStorage.setItem("role_id", response.data.role_id.toString());
        localStorage.setItem("name", response.data.name);
        localStorage.setItem("admin", (response.data.role_id === 0).toString());
        toast.success('检测到默认密码，即将跳转到修改密码页面');
        navigate("/change-password");
        return;
      }

      // 保存登录信息
      localStorage.setItem('token', response.data.token);
      localStorage.setItem("role_id", response.data.role_id.toString());
      localStorage.setItem("name", response.data.name);
      localStorage.setItem("admin", (response.data.role_id === 0).toString());

      // 登录成功
      toast.success('登录成功');
      navigate("/dashboard");

    } catch (error) {
      console.error('登录错误:', error);
      toast.error("网络错误，请稍后重试");
    } finally {
      setLoading(false);
    }
  };

  const handleLogin = async () => {
    if (loading) return;
    if (!validateForm()) return;

    setLoading(true);

    try {
      // 先检查是否需要验证码
      const checkResponse = await checkCaptcha();
      
      if (checkResponse.code !== 0) {
        toast.error("检查验证码状态失败，请重试" + checkResponse.msg);
        setLoading(false);
        return;
      }

      // 根据返回值决定是否显示验证码
      if (checkResponse.data === 0) {
        // 不需要验证码，直接登录
        await performLogin();
      } else {
        // 需要验证码，显示验证码弹层
        setShowCaptcha(true);
        // 延时初始化验证码，确保DOM已渲染
        setTimeout(() => {
          initCaptcha();
        }, 100);
      }
    } catch (error) {
      console.error('检查验证码状态错误:', error);
      toast.error("网络错误，请稍后重试" + error);
      setLoading(false);
    }
  };


  return (
    <div className="login-page">
      {isWebView && (
        <header className="login-header">
          <button className="login-header-link" onClick={() => navigate('/settings')}>面板设置 <span aria-hidden="true">↗</span></button>
        </header>
      )}

      <main className="login-stage">
        <div className="login-stack">
          <div className="login-brand">
            <h1>{appName}</h1>
            <p>转发管理控制台</p>
          </div>

          <section className="login-form-panel" aria-labelledby="login-heading">
            <div className="login-form-content">
              <div className="login-form-heading"><h2 id="login-heading">欢迎回来</h2><p>输入账号和密码以登录控制台。</p></div>
              <form className="login-form" noValidate aria-busy={loading} onSubmit={event => { event.preventDefault(); void handleLogin(); }}>
                  <Input
                    name="username"
                    autoComplete="username"
                    label="用户名"
                    placeholder="请输入用户名"
                    value={form.username}
                    onChange={(e) => handleInputChange('username', e.target.value)}
                    labelPlacement="outside"
                    classNames={{ base: 'login-field', inputWrapper: 'login-input-wrap', input: 'login-input', label: 'login-label' }}
                    variant="bordered"
                    isDisabled={loading}
                    isInvalid={!!errors.username}
                    errorMessage={errors.username}
                  />
                
                  <Input
                    name="password"
                    autoComplete="current-password"
                    label="密码"
                    placeholder="请输入密码"
                    type={passwordVisible ? 'text' : 'password'}
                    value={form.password}
                    onChange={(e) => handleInputChange('password', e.target.value)}
                    onKeyDown={event => setCapsLock(event.getModifierState('CapsLock'))}
                    onKeyUp={event => setCapsLock(event.getModifierState('CapsLock'))}
                    onBlur={() => setCapsLock(false)}
                    labelPlacement="outside"
                    classNames={{ base: 'login-field', inputWrapper: 'login-input-wrap', input: 'login-input', label: 'login-label' }}
                    variant="bordered"
                    isDisabled={loading}
                    isInvalid={!!errors.password}
                    errorMessage={errors.password}
                    endContent={<button className="login-password-toggle" type="button" disabled={loading} aria-label={passwordVisible ? '隐藏密码' : '显示密码'} aria-pressed={passwordVisible} onClick={() => setPasswordVisible(value => !value)}><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><path d="M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>{passwordVisible && <path d="m3 3 18 18"/>}</svg></button>}
                  />
                  {capsLock && <p className="login-caps-lock" role="status">大写锁定已开启</p>}
                  <Button
                    type="submit"
                    color="primary"
                    size="lg"
                    isLoading={loading}
                    isDisabled={loading}
                    className="login-submit"
                  >
                    {loading ? (showCaptcha ? "验证中..." : "登录中...") : "登录控制台"}
                  </Button>
                  <p className="login-form-note"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><rect x="5" y="10" width="14" height="11" rx="3"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg> 使用管理员分配的账号登录</p>
              </form>
            </div>
          </section>
        </div>
      </main>
      <footer className="login-footer"><span>{appName} <span className="login-footer-version">v{isWebView ? siteConfig.app_version : siteConfig.version}</span></span><a href="https://github.com/kfcv301-maker/ix" target="_blank" rel="noopener noreferrer">基于 flux-panel · 开源项目 <span aria-hidden="true">↗</span></a></footer>
        {/* 验证码弹层 */}
        {showCaptcha && (
          <div className="fixed inset-0 z-50 flex items-center justify-center" role="dialog" aria-modal="true" aria-label="登录安全验证">
            {/* 背景遮罩层 - 模糊效果，暗黑模式下更深 */}
            <div className="absolute inset-0 bg-black/60 dark:bg-black/80 backdrop-blur-sm captcha-backdrop-enter" />
           {/* 验证码容器 */}
           <div className="relative z-10 mb-4">
                <div 
                  id="captcha-container" 
                  ref={captchaContainerRef}
                  className="w-full flex justify-center"
                  style={{
                    filter: document.documentElement.classList.contains('dark') || 
                           document.documentElement.getAttribute('data-theme') === 'dark' ||
                           window.matchMedia('(prefers-color-scheme: dark)').matches 
                           ? 'brightness(0.8) contrast(0.9)' : 'none'
                  }}
                />
              </div>
          </div>
        )}
    </div>
  );
}
